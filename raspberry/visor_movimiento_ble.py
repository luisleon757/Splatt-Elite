import importlib.machinery
import importlib.util
import json
import socket
import struct
import subprocess
import threading
import time
from collections import deque
from pathlib import Path

import cv2
import dbus
import dbus.mainloop.glib
import dbus.service
import numpy as np
from flask import Flask, Response, jsonify
from picamera2 import Picamera2
from libcamera import Transform
from gi.repository import GLib
from splatt_imu import SplattIMU


# BLE
BLE_GATT_EXAMPLE = "/home/pi/bluez-examples/example-gatt-server"
BLE_SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
BLE_STATUS_UUID = "12345678-1234-5678-1234-56789abcdef1"
BLE_COMMAND_UUID = "12345678-1234-5678-1234-56789abcdef2"
BLE_CONFIG_UUID = "12345678-1234-5678-1234-56789abcdef3"

# Protocolo binario TRACE v1.
#
# START:
#   type=1, version, disparo_id, puntos, bloques
#
# DATA:
#   type=2, disparo_id, bloque, cantidad, puntos...
#
# Cada punto:
#   dt_ms(int16), x*100(int16), y*100(int16), valido(uint8)
#
# END:
#   type=3, disparo_id, puntos, bloques
TRACE_PROTOCOL_VERSION = 1
TRACE_MSG_START = 1
TRACE_MSG_DATA = 2
TRACE_MSG_END = 3

TRACE_NOTIFY_INTERVAL_MS = 20
TRACE_PACKET_MAX_BYTES = 180

TRACE_POINT_STRUCT = struct.Struct("<hhhB")
TRACE_DATA_HEADER_STRUCT = struct.Struct("<BHHB")
BLE_DEVICE_NAME = "Splatt_Elite"
BLE_ADVERTISEMENT_INSTANCE = "1"
BLE_NOTIFY_INTERVAL_MS = 100

PISUGAR_SOCKET = "/tmp/pisugar-server.sock"
BATTERY_REFRESH_SECONDS = 5.0

battery_cache_lock = threading.Lock()
battery_cache_percent = -1
battery_cache_updated = 0.0
trace_tx_lock = threading.Lock()
trace_tx_packets = deque()
trace_tx_next_shot_id = 0


def _trace_int16(value):
    return max(-32768, min(32767, int(value)))


def preparar_trace_ble(puntos, shot_boottime_ns):
    """Prepara la ultima traza completa para transmitir por BLE."""
    global trace_tx_next_shot_id

    if not puntos or shot_boottime_ns is None:
        return

    puntos_codificados = []

    for frame_ns, x, y, valido in puntos:
        dt_ms = round(
            (int(frame_ns) - int(shot_boottime_ns))
            / 1_000_000.0
        )

        if valido:
            x_100 = round(float(x) * 100.0)
            y_100 = round(float(y) * 100.0)
        else:
            # Las coordenadas de un punto no valido no se utilizan.
            x_100 = 0
            y_100 = 0

        puntos_codificados.append(
            TRACE_POINT_STRUCT.pack(
                _trace_int16(dt_ms),
                _trace_int16(x_100),
                _trace_int16(y_100),
                1 if valido else 0,
            )
        )

    puntos_por_bloque = max(
        1,
        (
            TRACE_PACKET_MAX_BYTES
            - TRACE_DATA_HEADER_STRUCT.size
        ) // TRACE_POINT_STRUCT.size,
    )

    total_puntos = len(puntos_codificados)

    total_bloques = (
        total_puntos + puntos_por_bloque - 1
    ) // puntos_por_bloque

    with trace_tx_lock:
        trace_tx_next_shot_id = (
            trace_tx_next_shot_id % 65535
        ) + 1

        disparo_id = trace_tx_next_shot_id

    paquetes = [
        struct.pack(
            "<BBHHH",
            TRACE_MSG_START,
            TRACE_PROTOCOL_VERSION,
            disparo_id,
            total_puntos,
            total_bloques,
        )
    ]

    for bloque in range(total_bloques):
        inicio = bloque * puntos_por_bloque
        fin = min(
            inicio + puntos_por_bloque,
            total_puntos,
        )

        datos = puntos_codificados[inicio:fin]

        paquete = TRACE_DATA_HEADER_STRUCT.pack(
            TRACE_MSG_DATA,
            disparo_id,
            bloque,
            len(datos),
        ) + b"".join(datos)

        paquetes.append(paquete)

    paquetes.append(
        struct.pack(
            "<BHHH",
            TRACE_MSG_END,
            disparo_id,
            total_puntos,
            total_bloques,
        )
    )

    with trace_tx_lock:
        # Solo interesa la traza definitiva mas reciente.
        trace_tx_packets.clear()
        trace_tx_packets.extend(paquetes)

    bytes_totales = sum(
        len(paquete)
        for paquete in paquetes
    )

    tamano_maximo = max(
        len(paquete)
        for paquete in paquetes
    )

    print(
        "[TRACE-BLE] preparado "
        f"disparo_id={disparo_id} "
        f"puntos={total_puntos} "
        f"bloques={total_bloques} "
        f"paquetes={len(paquetes)} "
        f"bytes={bytes_totales} "
        f"max_packet={tamano_maximo}",
        flush=True,
    )


def leer_bateria_pisugar():
    global battery_cache_percent
    global battery_cache_updated

    ahora = time.monotonic()

    with battery_cache_lock:
        if ahora - battery_cache_updated < BATTERY_REFRESH_SECONDS:
            return battery_cache_percent

    porcentaje = -1

    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
            sock.settimeout(0.5)
            sock.connect(PISUGAR_SOCKET)
            sock.sendall(b"get battery\n")
            respuesta = sock.recv(128).decode().strip()

        if respuesta.startswith("battery:"):
            porcentaje = int(round(float(respuesta.split(":", 1)[1].strip())))
            porcentaje = max(0, min(100, porcentaje))

    except Exception:
        porcentaje = -1

    with battery_cache_lock:
        battery_cache_percent = porcentaje
        battery_cache_updated = ahora

    return porcentaje


def obtener_ip_local():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return ""


LOCAL_IP = obtener_ip_local()

# La app trabaja sobre un lienzo 320 x 240. Se conserva la misma escala
# horizontal y vertical: el sensor 1280 x 800 queda centrado verticalmente.
APP_CENTER_X = 160.0
APP_CENTER_Y = 120.0
APP_COORDINATE_SCALE = 0.25

ble_status_lock = threading.Lock()
ble_status = {
    "state": 0,
    "shot_x": 0.0,
    "shot_y": 0.0,
    "time": 0,
    "x": 0.0,
    "y": 0.0,
    "v": 0,
    "s": 0,
    "c": 1,
    "f": 0,
    "frame_ms": 0,
    "shot_ms": 0,
}

calibration_active = threading.Event()
imu = SplattIMU()


def cargar_modulo(nombre, ruta):
    loader = importlib.machinery.SourceFileLoader(nombre, ruta)
    spec = importlib.util.spec_from_loader(nombre, loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


ble_gatt = cargar_modulo("bluez_gatt_splatt", BLE_GATT_EXAMPLE)


def actualizar_ble_status(**changes):
    with ble_status_lock:
        ble_status.update(changes)


def coordenadas_app(x_px, y_px):
    return (
        APP_CENTER_X + (float(x_px) - WIDTH / 2.0) * APP_COORDINATE_SCALE,
        APP_CENTER_Y + (float(y_px) - HEIGHT / 2.0) * APP_COORDINATE_SCALE,
    )


def manejar_comando_ble(command):
    print(f"Comando BLE: {command}", flush=True)

    if command == "start_calib":
        calibration_active.set()
        reset_event.set()
    elif command == "stop_calib":
        calibration_active.clear()
    elif command == "cancel_shot":
        actualizar_ble_status(s=0)
    elif command == "sleep":
        # De momento no apaga la Raspberry: solo deja constancia de la orden.
        print("Orden sleep recibida; apagado automático aún no habilitado", flush=True)


def manejar_config_ble(config):
    # Los controles dinámicos de cámara se conectarán en una fase posterior.
    print(f"Configuración BLE: {config}", flush=True)


class SplattBleApplication(ble_gatt.Application):
    def __init__(self, bus):
        self.path = "/"
        self.services = []
        dbus.service.Object.__init__(self, bus, self.path)
        self.add_service(SplattBleService(bus, 0))


class SplattBleService(ble_gatt.Service):
    def __init__(self, bus, index):
        super().__init__(bus, index, BLE_SERVICE_UUID, True)
        self.status = SplattStatusCharacteristic(bus, 0, self)
        self.add_characteristic(self.status)
        self.add_characteristic(SplattCommandCharacteristic(bus, 1, self))
        self.add_characteristic(SplattConfigCharacteristic(bus, 2, self))


class SplattStatusCharacteristic(ble_gatt.Characteristic):
    def __init__(self, bus, index, service):
        super().__init__(
            bus,
            index,
            BLE_STATUS_UUID,
            ["read", "notify"],
            service,
        )

        self.notifying = False
        self.active_shot_id = None
        self.last_status_notify_monotonic = 0.0

        # Un unico temporizador sirve para STATUS y TRACE.
        # TRACE necesita una cadencia de 20 ms.
        GLib.timeout_add(
            TRACE_NOTIFY_INTERVAL_MS,
            self._tick,
        )

    @staticmethod
    def _dbus_bytes(data):
        return dbus.Array(
            [dbus.Byte(value) for value in data],
            signature="y",
        )

    def _payload(self):
        with ble_status_lock:
            snapshot = dict(ble_status)

        state = int(snapshot.get("state", 0))
        x = int(round(float(snapshot.get("x", 0))))
        y = int(round(float(snapshot.get("y", 0))))
        shot_x = float(snapshot.get("shot_x", 0))
        shot_y = float(snapshot.get("shot_y", 0))
        valid = int(snapshot.get("v", 0))
        frame_ms = int(snapshot.get("frame_ms", 0))
        shot_ms = int(snapshot.get("shot_ms", 0))
        battery = leer_bateria_pisugar()

        return (
            f"{state},{x},{y},{valid},0,{LOCAL_IP},{battery},"
            f"{shot_x:.2f},{shot_y:.2f},{frame_ms},{shot_ms}"
        ).encode("ascii")

    def ReadValue(self, options):
        return self._dbus_bytes(self._payload())

    def StartNotify(self):
        if self.notifying:
            return

        self.notifying = True

        print(
            "Notificaciones BLE STATUS+TRACE activadas",
            flush=True,
        )

        self._notify_status()

    def StopNotify(self):
        self.notifying = False

        print(
            "Notificaciones BLE STATUS+TRACE desactivadas",
            flush=True,
        )

    def _emit(self, data):
        self.PropertiesChanged(
            ble_gatt.GATT_CHRC_IFACE,
            {
                "Value": self._dbus_bytes(data)
            },
            [],
        )

    def _notify_status(self):
        if not self.notifying:
            return

        self._emit(self._payload())

        self.last_status_notify_monotonic = (
            time.monotonic()
        )

    def _notify_trace(self, paquete):
        try:
            self._emit(paquete)

        except Exception as error:
            # Si desaparece la conexion durante una traza,
            # conservar el paquete para la siguiente conexion.
            with trace_tx_lock:
                trace_tx_packets.appendleft(paquete)

            print(
                f"[TRACE-BLE] error envio por STATUS: {error}",
                flush=True,
            )

            return False

        tipo = paquete[0]

        if tipo == TRACE_MSG_START:
            disparo_id = struct.unpack_from(
                "<H",
                paquete,
                2,
            )[0]

            self.active_shot_id = disparo_id

            print(
                "[TRACE-BLE] envio iniciado por STATUS "
                f"disparo_id={disparo_id}",
                flush=True,
            )

        elif tipo == TRACE_MSG_END:
            disparo_id = struct.unpack_from(
                "<H",
                paquete,
                1,
            )[0]

            print(
                "[TRACE-BLE] envio finalizado por STATUS "
                f"disparo_id={disparo_id}",
                flush=True,
            )

            self.active_shot_id = None

        return True

    def _tick(self):
        if stop_event.is_set():
            return False

        if not self.notifying:
            return True

        paquete = None

        with trace_tx_lock:
            if trace_tx_packets:
                paquete = trace_tx_packets.popleft()

        # Mientras exista TRACE pendiente, darle prioridad.
        # Un paquete cada 20 ms.
        if paquete is not None:
            self._notify_trace(paquete)
            return True

        # Sin TRACE, mantener STATUS a 100 ms.
        ahora = time.monotonic()

        if (
            ahora - self.last_status_notify_monotonic
            >= BLE_NOTIFY_INTERVAL_MS / 1000.0
        ):
            try:
                self._notify_status()

            except Exception as error:
                print(
                    f"[BLE] error STATUS: {error}",
                    flush=True,
                )

        return True


class SplattTextWriteCharacteristic(ble_gatt.Characteristic):
    def _decode(self, value):
        raw = bytes(int(byte) for byte in value)
        return raw.decode("utf-8", errors="replace").strip()


class SplattCommandCharacteristic(SplattTextWriteCharacteristic):
    def __init__(self, bus, index, service):
        super().__init__(bus, index, BLE_COMMAND_UUID, ["write"], service)

    def WriteValue(self, value, options):
        manejar_comando_ble(self._decode(value))


class SplattConfigCharacteristic(SplattTextWriteCharacteristic):
    def __init__(self, bus, index, service):
        super().__init__(bus, index, BLE_CONFIG_UUID, ["write"], service)

    def WriteValue(self, value, options):
        manejar_config_ble(self._decode(value))



def ejecutar_comando_sistema(args, obligatorio=False):

    result = subprocess.run(
        args,
        text=True,
        capture_output=True,
        input="\n",
        timeout=5,
        check=False,
    )

    if obligatorio and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise RuntimeError(f"{' '.join(args)}: {detail}")

    return result


def activar_anuncio_ble_directo():
    ejecutar_comando_sistema(
        ["btmgmt", "rm-adv", BLE_ADVERTISEMENT_INSTANCE]
    )

    result = ejecutar_comando_sistema(
        [
            "btmgmt",
            "add-adv",
            "-c",
            "-g",
            "-n",
            "-u",
            BLE_SERVICE_UUID,
            BLE_ADVERTISEMENT_INSTANCE,
        ],
        obligatorio=True,
    )

    print(
        f"Anuncio BLE activo: {BLE_DEVICE_NAME} "
        f"({result.stdout.strip()})",
        flush=True,
    )


def desactivar_anuncio_ble_directo():
    ejecutar_comando_sistema(
        ["btmgmt", "rm-adv", BLE_ADVERTISEMENT_INSTANCE]
    )


def ble_loop():
    gatt_manager = None
    ble_app = None

    try:
        dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
        bus = dbus.SystemBus()

        adapter = ble_gatt.find_adapter(bus)
        if not adapter:
            raise RuntimeError("No se encontró un adaptador con GattManager1")

        adapter_props = dbus.Interface(
            bus.get_object(ble_gatt.BLUEZ_SERVICE_NAME, adapter),
            ble_gatt.DBUS_PROP_IFACE,
        )
        adapter_props.Set(
            "org.bluez.Adapter1",
            "Powered",
            dbus.Boolean(True),
        )

        gatt_manager = dbus.Interface(
            bus.get_object(ble_gatt.BLUEZ_SERVICE_NAME, adapter),
            ble_gatt.GATT_MANAGER_IFACE,
        )

        ble_app = SplattBleApplication(bus)
        mainloop = GLib.MainLoop()

        def registered():
            print("Servicio GATT Splatt registrado", flush=True)
            try:
                activar_anuncio_ble_directo()
            except Exception as error:
                print(f"Error activando anuncio BLE: {error}", flush=True)

        def registration_failed(error):
            print(f"Error registrando GATT: {error}", flush=True)
            mainloop.quit()

        def check_stop():
            if stop_event.is_set():
                mainloop.quit()
                return False
            return True

        gatt_manager.RegisterApplication(
            ble_app.get_path(),
            {},
            reply_handler=registered,
            error_handler=registration_failed,
        )

        GLib.timeout_add(200, check_stop)
        print("Iniciando BLE Splatt Elite...", flush=True)
        mainloop.run()

    except Exception as error:
        print(f"BLE no disponible: {error}", flush=True)

    finally:
        desactivar_anuncio_ble_directo()

        if gatt_manager is not None and ble_app is not None:
            try:
                gatt_manager.UnregisterApplication(ble_app.get_path())
            except Exception:
                pass

        print("BLE detenido", flush=True)


# Cámara
WIDTH = 1280
HEIGHT = 800
CAMERA_FPS = 60
EXPOSURE_US = 3000
ANALOGUE_GAIN = 3.0

# Calibración automática de imagen
CALIB_EXPOSURES_US = [
    800,
    1200,
    1600,
    2000,
    2400,
    2800,
    3200,
]

CALIB_GAINS = [
    1.0,
    1.5,
    2.0,
    2.5,
    3.0,
]

CALIB_SETTLE_FRAMES = 6
CALIB_SAMPLE_FRAMES = 12

# Detector
EXPECTED_RADIUS = 25
MIN_RADIUS = 10
MAX_RADIUS = 28
MIN_CONTRAST = 8.0

# Validacion adicional basada en los datos reales de pista.
# SEARCH exige una referencia clara antes de activar TRACK.
ACQUIRE_MIN_RADIUS = 18
ACQUIRE_MAX_RADIUS = 28
ACQUIRE_MIN_CONTRAST = 40.0

# Una vez en TRACK permitimos algo mas de variacion.
TRACK_MIN_RADIUS = 16
TRACK_MAX_RADIUS = 28
TRACK_MIN_CONTRAST = 25.0

SEARCH_WIDTH = 700
SEARCH_HEIGHT = 500
REACQUIRE_ROI_SIZE = 360
TRACK_ROI_SIZE = 180

# Readquisicion:
# primero alrededor de la ultima posicion conocida y,
# si falla, barrido indefinido de todo el sensor por zonas solapadas.
LOCAL_REACQUIRE_FRAMES = 30
INITIAL_CENTER_SEARCH_FRAMES = 30

GLOBAL_SEARCH_TILES = (
    (0, 0, SEARCH_WIDTH, SEARCH_HEIGHT),
    (
        WIDTH - SEARCH_WIDTH,
        0,
        WIDTH,
        SEARCH_HEIGHT,
    ),
    (
        0,
        HEIGHT - SEARCH_HEIGHT,
        SEARCH_WIDTH,
        HEIGHT,
    ),
    (
        WIDTH - SEARCH_WIDTH,
        HEIGHT - SEARCH_HEIGHT,
        WIDTH,
        HEIGHT,
    ),
)

CONFIRM_FRAMES = 3
MAX_LOST_FRAMES = 5
MAX_CONFIRM_JUMP = 18.0
MAX_TRACK_ERROR = 55.0

# Retardo estimado entre deteccion IMU y salida del balin.
SHOT_EXIT_DELAY_MS = 3.0
SHOT_EXIT_DELAY_NS = int(SHOT_EXIT_DELAY_MS * 1_000_000)

# Visor
STREAM_EVERY_N_FRAMES = 4
JPEG_QUALITY = 65
TRAIL_LENGTH = 600
# Traza de alta resolucion de cada disparo.
# Maximo 30 s antes del disparo y 10 s despues.
TRACE_PRE_MAX_SECONDS = 30.0
TRACE_POST_MAX_SECONDS = 10.0

OUTPUT_IMAGE = Path("/home/pi/visor_movimiento_ultimo.jpg")
LOG_DIR = Path("/home/pi/splatt_logs")
CONFIG_FILE = Path("/home/pi/splatt_config.json")


def cargar_config_camara():
    if not CONFIG_FILE.exists():
        return {
            "exposure_us": EXPOSURE_US,
            "analogue_gain": ANALOGUE_GAIN,
        }

    try:
        data = json.loads(CONFIG_FILE.read_text())
        return {
            "exposure_us": int(
                data.get("exposure_us", EXPOSURE_US)
            ),
            "analogue_gain": float(
                data.get("analogue_gain", ANALOGUE_GAIN)
            ),
        }
    except Exception as error:
        print(
            f"Error cargando configuración: {error}",
            flush=True,
        )
        return {
            "exposure_us": EXPOSURE_US,
            "analogue_gain": ANALOGUE_GAIN,
        }


def guardar_config_camara(exposure_us, analogue_gain):
    data = {
        "exposure_us": int(exposure_us),
        "analogue_gain": float(analogue_gain),
    }

    CONFIG_FILE.write_text(
        json.dumps(data, indent=2)
    )

    print(
        f"Configuración guardada: "
        f"exposición={exposure_us} us "
        f"ganancia={analogue_gain:.2f}",
        flush=True,
    )



app = Flask(__name__)

stop_event = threading.Event()
reset_event = threading.Event()

frame_condition = threading.Condition()
latest_jpeg = None
latest_frame_number = 0

# El JPEG solo trabaja mientras haya un cliente conectado a /video.
video_clients_lock = threading.Lock()
video_clients = 0

# Cola de un único frame para el hilo JPEG.
# Si el codificador se retrasa, se conserva únicamente el más reciente.
jpeg_condition = threading.Condition()
pending_jpeg_image = None
pending_jpeg_number = 0


def evaluar_circulo(gray_roi, x, y, radius, reference=None):
    inner_radius = max(2, int(radius * 0.65))
    ring_inner = max(inner_radius + 1, int(radius * 1.15))
    ring_outer = int(radius * 1.55)

    roi_height, roi_width = gray_roi.shape

    if (
        x - ring_outer < 0
        or y - ring_outer < 0
        or x + ring_outer >= roi_width
        or y + ring_outer >= roi_height
    ):
        return None

    inner_mask = np.zeros_like(gray_roi, dtype=np.uint8)
    outer_mask = np.zeros_like(gray_roi, dtype=np.uint8)
    hole_mask = np.zeros_like(gray_roi, dtype=np.uint8)

    cv2.circle(inner_mask, (x, y), inner_radius, 255, -1)
    cv2.circle(outer_mask, (x, y), ring_outer, 255, -1)
    cv2.circle(hole_mask, (x, y), ring_inner, 255, -1)

    ring_mask = cv2.subtract(outer_mask, hole_mask)

    inner_mean = cv2.mean(gray_roi, mask=inner_mask)[0]
    ring_mean = cv2.mean(gray_roi, mask=ring_mask)[0]
    contrast = ring_mean - inner_mean

    if contrast < MIN_CONTRAST:
        return None

    score = (
        contrast * 4.0
        - abs(radius - EXPECTED_RADIUS) * 1.4
        - inner_mean * 0.08
    )

    if reference is not None:
        ref_x, ref_y, ref_radius = reference

        jump = np.hypot(x - ref_x, y - ref_y)
        radius_change = abs(radius - ref_radius)

        score -= jump * 1.1
        score -= radius_change * 1.5

    return {
        "x": float(x),
        "y": float(y),
        "radius": float(radius),
        "contrast": float(contrast),
        "inner_mean": float(inner_mean),
        "ring_mean": float(ring_mean),
        "score": float(score),
    }


def buscar_circulo(
    gray,
    x1,
    y1,
    x2,
    y2,
    reference_global=None,
):
    x1 = max(0, int(x1))
    y1 = max(0, int(y1))
    x2 = min(WIDTH, int(x2))
    y2 = min(HEIGHT, int(y2))

    roi = gray[y1:y2, x1:x2]

    if roi.size == 0:
        return None

    filtered = cv2.medianBlur(roi, 5)

    circles = cv2.HoughCircles(
        filtered,
        cv2.HOUGH_GRADIENT,
        dp=1.1,
        minDist=15,
        param1=80,
        param2=16,
        minRadius=MIN_RADIUS,
        maxRadius=MAX_RADIUS,
    )

    if circles is None:
        return None

    reference_local = None

    if reference_global is not None:
        reference_local = (
            reference_global[0] - x1,
            reference_global[1] - y1,
            reference_global[2],
        )

    best = None

    for local_x, local_y, radius in np.round(
        circles[0]
    ).astype(int):

        candidate = evaluar_circulo(
            roi,
            local_x,
            local_y,
            radius,
            reference=reference_local,
        )

        if candidate is None:
            continue

        candidate["x"] += x1
        candidate["y"] += y1

        if best is None or candidate["score"] > best["score"]:
            best = candidate

    return best


def calibrar_imagen(camera):
    resultados = []

    print("CALIBRACION: buscando referencia", flush=True)

    frame = camera.capture_array("main")
    gray = frame[:HEIGHT, :WIDTH]

    referencia = buscar_circulo(
        gray,
        (WIDTH - SEARCH_WIDTH) // 2,
        (HEIGHT - SEARCH_HEIGHT) // 2,
        (WIDTH + SEARCH_WIDTH) // 2,
        (HEIGHT + SEARCH_HEIGHT) // 2,
        reference_global=None,
    )

    if referencia is None:
        print(
            "CALIBRACION: no se pudo localizar la diana",
            flush=True,
        )
        return None

    ref_x = referencia["x"]
    ref_y = referencia["y"]
    ref_r = referencia["radius"]

    print(
        f"CALIBRACION: referencia "
        f"x={ref_x:.1f} y={ref_y:.1f} r={ref_r:.1f}",
        flush=True,
    )

    half = TRACK_ROI_SIZE // 2

    for exposure_us in CALIB_EXPOSURES_US:
        for analogue_gain in CALIB_GAINS:
            if not calibration_active.is_set():
                print("CALIBRACION: cancelada", flush=True)
                return None

            camera.set_controls({
                "AeEnable": False,
                "ExposureTime": exposure_us,
                "AnalogueGain": analogue_gain,
            })

            for _ in range(CALIB_SETTLE_FRAMES):
                camera.capture_array("main")

            contrastes = []
            interiores = []
            anillos = []
            xs = []
            ys = []
            radios = []
            detecciones = 0

            for _ in range(CALIB_SAMPLE_FRAMES):
                if not calibration_active.is_set():
                    print("CALIBRACION: cancelada", flush=True)
                    return None

                frame = camera.capture_array("main")
                gray = frame[:HEIGHT, :WIDTH]

                detection = buscar_circulo(
                    gray,
                    ref_x - half,
                    ref_y - half,
                    ref_x + half,
                    ref_y + half,
                    reference_global=(ref_x, ref_y, ref_r),
                )

                if detection is not None:
                    detecciones += 1
                    contrastes.append(detection["contrast"])
                    interiores.append(detection["inner_mean"])
                    anillos.append(detection["ring_mean"])
                    xs.append(detection["x"])
                    ys.append(detection["y"])
                    radios.append(detection["radius"])

            if detecciones == 0:
                score = -1000000.0
                contraste_medio = 0.0
                interior_medio = 0.0
                anillo_medio = 0.0
                x_medio = 0.0
                y_medio = 0.0
                radio_medio = 0.0
            else:
                contraste_medio = sum(contrastes) / len(contrastes)
                interior_medio = sum(interiores) / len(interiores)
                anillo_medio = sum(anillos) / len(anillos)

                x_medio = sum(xs) / len(xs)
                y_medio = sum(ys) / len(ys)
                radio_medio = sum(radios) / len(radios)

                tasa_deteccion = detecciones / CALIB_SAMPLE_FRAMES

                penalizacion_saturacion = max(
                    0.0,
                    anillo_medio - 225.0,
                ) * 2.0

                penalizacion_negro_alto = max(
                    0.0,
                    interior_medio - 180.0,
                ) * 0.5

                score = (
                    tasa_deteccion * 100.0
                    + contraste_medio * 2.0
                    - penalizacion_saturacion
                    - penalizacion_negro_alto
                )

            resultados.append({
                "exposure_us": exposure_us,
                "analogue_gain": analogue_gain,
                "detecciones": detecciones,
                "contraste": contraste_medio,
                "interior": interior_medio,
                "anillo": anillo_medio,
                "score": score,
            })

            print(
                f"CALIBRACION: exp={exposure_us} "
                f"gain={analogue_gain:.1f} "
                f"det={detecciones}/{CALIB_SAMPLE_FRAMES} "
                f"x={x_medio:.1f} y={y_medio:.1f} "
                f"r={radio_medio:.1f} "
                f"contraste={contraste_medio:.1f} "
                f"interior={interior_medio:.1f} "
                f"anillo={anillo_medio:.1f} "
                f"score={score:.1f}",
                flush=True,
            )

    mejor = max(
        resultados,
        key=lambda item: item["score"],
    )

    print(
        f"CALIBRACION: mejor exp={mejor['exposure_us']} "
        f"gain={mejor['analogue_gain']:.1f} "
        f"score={mejor['score']:.1f}",
        flush=True,
    )

    return mejor

def publicar_jpeg(image):
    global latest_jpeg
    global latest_frame_number

    ok, encoded = cv2.imencode(
        ".jpg",
        image,
        [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY],
    )

    if not ok:
        return

    with frame_condition:
        latest_jpeg = encoded.tobytes()
        latest_frame_number += 1
        frame_condition.notify_all()


def visor_activo():
    with video_clients_lock:
        return video_clients > 0


def solicitar_jpeg(image):
    global pending_jpeg_image
    global pending_jpeg_number

    with jpeg_condition:
        pending_jpeg_image = image
        pending_jpeg_number += 1
        jpeg_condition.notify()


def jpeg_loop():
    last_pending_number = 0

    while not stop_event.is_set():
        with jpeg_condition:
            jpeg_condition.wait_for(
                lambda: (
                    pending_jpeg_number != last_pending_number
                    or stop_event.is_set()
                ),
                timeout=1.0,
            )

            if stop_event.is_set():
                return

            if pending_jpeg_number == last_pending_number:
                continue

            image = pending_jpeg_image
            last_pending_number = pending_jpeg_number

        if image is None:
            continue

        publicar_jpeg(image)


def dibujar_trayectoria(image, trail):
    previous = None

    for point in trail:
        if previous is not None:
            distance = np.hypot(
                point[0] - previous[0],
                point[1] - previous[1],
            )

            if distance < 80:
                cv2.line(
                    image,
                    previous,
                    point,
                    (220, 220, 220),
                    1,
                )

        previous = point


def capture_loop():
    camera = Picamera2()

    configuration = camera.create_video_configuration(
        main={
            "size": (WIDTH, HEIGHT),
            "format": "YUV420",
        },
        controls={
            "FrameRate": CAMERA_FPS,
        },
        transform=Transform(
            hflip=True,
            vflip=True,
        ),
    )

    camera.configure(configuration)

    config_camara = cargar_config_camara()
    exposure_us = config_camara["exposure_us"]
    analogue_gain = config_camara["analogue_gain"]

    camera.set_controls({
        "AeEnable": False,
        "ExposureTime": exposure_us,
        "AnalogueGain": analogue_gain,
    })

    print(
        f"Camara: exposicion={exposure_us} us "
        f"ganancia={analogue_gain:.2f}",
        flush=True,
    )

    state = "SEARCH"

    confirmation_count = 0
    lost_count = 0
    search_fail_count = 0

    candidate_reference = None
    track_reference = None
    last_good_reference = None

    velocity_x = 0.0
    velocity_y = 0.0

    loss_events = 0
    reacquisitions = 0
    has_tracked = False

    frame_counter = 0
    detected_counter = 0

    trail = deque(maxlen=TRAIL_LENGTH)

    # Historial de posiciones para recuperar la puntería anterior al disparo.
    position_history = deque(maxlen=180)

    # Un disparo se mantiene pendiente hasta que la cámara haya entregado
    # un frame posterior a su timestamp. Así sabemos que ya han llegado
    # todos los frames físicamente capturados antes del disparo.
    pending_shots = deque(maxlen=16)

    # Indica que el disparo actual ya tiene una posición histórica válida.
    shot_position_valid = False

    # Traza completa de alta resolucion del disparo.
    # Cada elemento:
    # (SensorTimestamp_ns, x_app, y_app, valido)
    trace_disparo = deque()
    trace_disparo_activa = False
    trace_shot_boottime_ns = None
    trace_prev_imu_estado = "STANDBY"
    trace_post_truncated = False

    fps = 0.0
    previous_frame_time = time.monotonic()
    tracking_started_at = None

    last_search_mode = None
    last_overlay = None

    search_x1 = (WIDTH - SEARCH_WIDTH) // 2
    search_y1 = (HEIGHT - SEARCH_HEIGHT) // 2
    search_x2 = search_x1 + SEARCH_WIDTH
    search_y2 = search_y1 + SEARCH_HEIGHT

    LOG_DIR.mkdir(parents=True, exist_ok=True)
    csv_path = LOG_DIR / f"visor_{time.strftime('%Y%m%d_%H%M%S')}.csv"

    csv_file = csv_path.open(
        "w",
        encoding="utf-8",
        buffering=1,
    )

    print(f"Registro CSV: {csv_path}", flush=True)

    csv_file.write(
        "tiempo_s,x_px,y_px,radio_px,estado,"
        "contraste,vx_px_frame,vy_px_frame,"
        "fps,lost_count,search_fail_count\n"
    )

    # La cámara permanece parada mientras el arma está en STANDBY.
    # Se enciende al detectar postura de puntería o al calibrar.
    camera_running = False
    camera_idle_since = time.monotonic()
    camera_standby_delay_s = 5.0

    start_time = time.monotonic()

    try:
        while not stop_event.is_set():
            if reset_event.is_set():
                state = "SEARCH"

                confirmation_count = 0
                lost_count = 0
                search_fail_count = 0

                candidate_reference = None
                track_reference = None
                last_good_reference = None

                velocity_x = 0.0
                velocity_y = 0.0

                trail.clear()
                position_history.clear()
                pending_shots.clear()
                trace_disparo.clear()
                trace_disparo_activa = False
                trace_shot_boottime_ns = None
                trace_prev_imu_estado = "STANDBY"
                trace_post_truncated = False
                tracking_started_at = None
                reset_event.clear()

                print("Seguimiento reiniciado")

            # La IMU decide cuándo necesitamos la cámara.
            imu_status = imu.snapshot()
            imu_estado = imu_status.get("estado", "STANDBY")
            postura_punteria = bool(
                imu_status.get("postura_punteria", False)
            )

            # Traza completa del disparo:
            # STANDBY -> PUNTERIA -> DISPARO -> POST_DISPARO -> STANDBY
            if (
                trace_prev_imu_estado == "STANDBY"
                and imu_estado == "PUNTERIA"
            ):
                trace_disparo.clear()
                trace_disparo_activa = True
                trace_shot_boottime_ns = None
                trace_post_truncated = False

                print("[TRACE] punteria iniciada", flush=True)

            elif (
                trace_prev_imu_estado != "STANDBY"
                and imu_estado == "STANDBY"
                and trace_disparo_activa
            ):
                if (
                    trace_shot_boottime_ns is not None
                    and trace_disparo
                ):
                    first_ns = trace_disparo[0][0]
                    last_ns = trace_disparo[-1][0]

                    duracion_punteria = max(
                        0.0,
                        (trace_shot_boottime_ns - first_ns)
                        / 1_000_000_000.0,
                    )

                    duracion_post = max(
                        0.0,
                        (last_ns - trace_shot_boottime_ns)
                        / 1_000_000_000.0,
                    )

                    validos = sum(
                        1 for punto in trace_disparo
                        if punto[3] == 1
                    )

                    gaps = 0
                    dentro_gap = False

                    for punto in trace_disparo:
                        if punto[3] == 0:
                            if not dentro_gap:
                                gaps += 1
                                dentro_gap = True
                        else:
                            dentro_gap = False

                    shot_index = -1

                    for indice, punto in enumerate(trace_disparo):
                        if punto[0] <= trace_shot_boottime_ns:
                            shot_index = indice
                        else:
                            break

                    print(
                        "[TRACE] disparo finalizado "
                        f"punteria={duracion_punteria:.3f}s "
                        f"post={duracion_post:.3f}s "
                        f"puntos={len(trace_disparo)} "
                        f"validos={validos} "
                        f"gaps={gaps} "
                        f"shot_index={shot_index} "
                        f"post_limitado={int(trace_post_truncated)}",
                        flush=True,
                    )
                    preparar_trace_ble(
                        list(trace_disparo),
                        trace_shot_boottime_ns,
                    )

                else:
                    print(
                        "[TRACE] punteria descartada sin disparo",
                        flush=True,
                    )

                trace_disparo_activa = False
                trace_disparo.clear()
                trace_shot_boottime_ns = None
                trace_post_truncated = False

            trace_prev_imu_estado = imu_estado
            camera_needed = (
                calibration_active.is_set()
                or visor_activo()
                or imu_estado != "STANDBY"
                or postura_punteria
            )

            ahora_camara = time.monotonic()

            if camera_needed:
                camera_idle_since = None

                if not camera_running:
                    camera.start()
                    camera_running = True
                    previous_frame_time = time.monotonic()

                    print(
                        "[CAMARA] Encendida por actividad del arma",
                        flush=True,
                    )

            else:
                if camera_idle_since is None:
                    camera_idle_since = ahora_camara

                if (
                    camera_running
                    and ahora_camara - camera_idle_since
                    >= camera_standby_delay_s
                ):
                    camera.stop()
                    camera_running = False

                    print(
                        "[CAMARA] Apagada por STANDBY",
                        flush=True,
                    )

            # Aunque la cámara esté apagada, BLE y batería siguen activos.
            if not camera_running:
                actualizar_ble_status(
                    state=0,
                    shot_x=0,
                    shot_y=0,
                    time=0,
                    x=0,
                    y=0,
                    v=0,
                    s=0,
                    c=1,
                    f=0,
                    frame_ms=0,
                    shot_ms=0,
                )
                time.sleep(0.05)
                continue

            if calibration_active.is_set():
                mejor = calibrar_imagen(camera)

                if mejor is not None:
                    exposure_us = mejor["exposure_us"]
                    analogue_gain = mejor["analogue_gain"]

                    camera.set_controls({
                        "AeEnable": False,
                        "ExposureTime": exposure_us,
                        "AnalogueGain": analogue_gain,
                    })

                    guardar_config_camara(
                        exposure_us,
                        analogue_gain,
                    )

                    print(
                        f"CALIBRACION: aplicada "
                        f"exp={exposure_us} us "
                        f"gain={analogue_gain:.1f}",
                        flush=True,
                    )

                calibration_active.clear()

            request = camera.capture_request()

            try:
                frame_metadata = request.get_metadata()
                frame = request.make_array("main")
            finally:
                request.release()

            gray = frame[:HEIGHT, :WIDTH]

            # SensorTimestamp identifica temporalmente el fotograma
            # en CLOCK_BOOTTIME, el mismo reloj usado ahora por la IMU.
            frame_boottime_ns = frame_metadata.get(
                "SensorTimestamp"
            )

            frame_time_ms = (
                int(frame_boottime_ns // 1_000_000)
                if frame_boottime_ns is not None
                else 0
            )

            now = time.monotonic()
            timestamp = now - start_time

            frame_period = now - previous_frame_time
            previous_frame_time = now

            if frame_period > 0:
                instant_fps = 1.0 / frame_period

                if fps == 0:
                    fps = instant_fps
                else:
                    fps = fps * 0.9 + instant_fps * 0.1

            detection = None
            search_rectangle = None
            track_rectangle = None

            if imu_estado == "STANDBY":
                state = "SEARCH"
                confirmation_count = 0
                lost_count = 0
                search_fail_count = 0
                candidate_reference = None
                track_reference = None
                last_good_reference = None
                velocity_x = 0.0
                velocity_y = 0.0
                trail.clear()
                position_history.clear()
            else:
                if state == "SEARCH":
                    # Si ya tenemos un candidato valido, durante los
                    # siguientes frames nos concentramos a su alrededor
                    # para completar CONFIRM_FRAMES.
                    if (
                        candidate_reference is not None
                        and confirmation_count > 0
                    ):
                        half = REACQUIRE_ROI_SIZE // 2
                        ref_x, ref_y, _ = candidate_reference

                        x1 = ref_x - half
                        y1 = ref_y - half
                        x2 = ref_x + half
                        y2 = ref_y + half

                        search_reference = candidate_reference
                        search_mode = "CONFIRM"

                    # Tras perder TRACK, intentar primero cerca de la
                    # ultima posicion valida.
                    elif (
                        last_good_reference is not None
                        and search_fail_count
                        < LOCAL_REACQUIRE_FRAMES
                    ):
                        half = REACQUIRE_ROI_SIZE // 2
                        ref_x, ref_y, _ = last_good_reference

                        x1 = ref_x - half
                        y1 = ref_y - half
                        x2 = ref_x + half
                        y2 = ref_y + half

                        search_reference = last_good_reference
                        search_mode = "LOCAL"

                    # En la primera adquisicion damos unos frames a la
                    # zona central, que es donde normalmente se apunta.
                    elif (
                        last_good_reference is None
                        and search_fail_count
                        < INITIAL_CENTER_SEARCH_FRAMES
                    ):
                        x1 = search_x1
                        y1 = search_y1
                        x2 = search_x2
                        y2 = search_y2

                        search_reference = None
                        search_mode = "CENTER"

                    # Si lo anterior falla, barrer indefinidamente las
                    # cuatro zonas que, juntas, cubren 1280x800.
                    else:
                        if last_good_reference is not None:
                            global_offset = LOCAL_REACQUIRE_FRAMES
                        else:
                            global_offset = INITIAL_CENTER_SEARCH_FRAMES

                        tile_index = (
                            search_fail_count - global_offset
                        ) % len(GLOBAL_SEARCH_TILES)

                        x1, y1, x2, y2 = GLOBAL_SEARCH_TILES[
                            tile_index
                        ]

                        search_reference = None
                        search_mode = "GLOBAL"

                    if search_mode != last_search_mode:
                        if search_mode == "GLOBAL":
                            print(
                                "[SEARCH] modo=GLOBAL "
                                "cobertura=1280x800 en 4 zonas",
                                flush=True,
                            )
                        else:
                            print(
                                f"[SEARCH] modo={search_mode}",
                                flush=True,
                            )

                        last_search_mode = search_mode

                    search_rectangle = (
                        int(x1),
                        int(y1),
                        int(x2),
                        int(y2),
                    )

                    detection = buscar_circulo(
                        gray,
                        x1,
                        y1,
                        x2,
                        y2,
                        reference_global=search_reference,
                    )

                    # El contador avanza mientras sigamos en SEARCH.
                    # Un candidato aislado ya no reinicia la escalada.
                    search_fail_count += 1

                    if detection is not None:
                        if not (
                            ACQUIRE_MIN_RADIUS
                            <= detection["radius"]
                            <= ACQUIRE_MAX_RADIUS
                            and detection["contrast"]
                            >= ACQUIRE_MIN_CONTRAST
                        ):
                            detection = None

                    if detection is None:
                        confirmation_count = 0
                        candidate_reference = None

                    else:
                        current = (
                            detection["x"],
                            detection["y"],
                            detection["radius"],
                        )

                        if candidate_reference is None:
                            candidate_reference = current
                            confirmation_count = 1

                        else:
                            confirmation_jump = np.hypot(
                                current[0] - candidate_reference[0],
                                current[1] - candidate_reference[1],
                            )

                            radius_change = abs(
                                current[2] - candidate_reference[2]
                            )

                            if (
                                confirmation_jump <= MAX_CONFIRM_JUMP
                                and radius_change <= 5
                            ):
                                confirmation_count += 1

                                candidate_reference = (
                                    candidate_reference[0] * 0.6
                                    + current[0] * 0.4,
                                    candidate_reference[1] * 0.6
                                    + current[1] * 0.4,
                                    candidate_reference[2] * 0.6
                                    + current[2] * 0.4,
                                )
                            else:
                                candidate_reference = current
                                confirmation_count = 1

                        if confirmation_count >= CONFIRM_FRAMES:
                            track_reference = candidate_reference
                            last_good_reference = track_reference

                            velocity_x = 0.0
                            velocity_y = 0.0
                            lost_count = 0
                            search_fail_count = 0

                            state = "TRACK"

                            if has_tracked:
                                reacquisitions += 1
                                print("Diana recuperada")
                            else:
                                has_tracked = True
                                print("TRACK activado")

                else:
                    current_x, current_y, current_radius = track_reference

                    predicted_x = current_x + velocity_x
                    predicted_y = current_y + velocity_y

                    half = TRACK_ROI_SIZE // 2

                    track_rectangle = (
                        int(predicted_x - half),
                        int(predicted_y - half),
                        int(predicted_x + half),
                        int(predicted_y + half),
                    )

                    predicted_reference = (
                        predicted_x,
                        predicted_y,
                        current_radius,
                    )

                    detection = buscar_circulo(
                        gray,
                        predicted_x - half,
                        predicted_y - half,
                        predicted_x + half,
                        predicted_y + half,
                        reference_global=predicted_reference,
                    )

                    track_fail_reason = None

                    if detection is not None:
                        if not (
                            TRACK_MIN_RADIUS
                            <= detection["radius"]
                            <= TRACK_MAX_RADIUS
                        ):
                            track_fail_reason = (
                                f"radio_invalido={detection['radius']:.1f}px"
                            )
                            detection = None
                        elif (
                            detection["contrast"]
                            < TRACK_MIN_CONTRAST
                        ):
                            track_fail_reason = (
                                f"contraste_bajo="
                                f"{detection['contrast']:.1f}"
                            )
                            detection = None

                    if detection is None:
                        if track_fail_reason is None:
                            track_fail_reason = "sin_circulo"
                    else:
                        prediction_error = np.hypot(
                            detection["x"] - predicted_x,
                            detection["y"] - predicted_y,
                        )

                        if prediction_error > MAX_TRACK_ERROR:
                            track_fail_reason = (
                                f"error_prediccion={prediction_error:.1f}px"
                            )
                            detection = None

                    if detection is None:
                        lost_count += 1

                        if (
                            lost_count == 1
                            or lost_count >= MAX_LOST_FRAMES
                        ):
                            print(
                                f"[TRACK-DIAG] "
                                f"{track_fail_reason} "
                                f"fallos={lost_count}/{MAX_LOST_FRAMES} "
                                f"pred=({predicted_x:.1f},{predicted_y:.1f}) "
                                f"radio={current_radius:.1f}",
                                flush=True,
                            )

                        track_reference = (
                            predicted_x,
                            predicted_y,
                            current_radius,
                        )

                        if lost_count >= MAX_LOST_FRAMES:
                            loss_events += 1

                            state = "SEARCH"
                            confirmation_count = 0
                            candidate_reference = None
                            search_fail_count = 0

                            velocity_x = 0.0
                            velocity_y = 0.0

                            print("Diana perdida; buscando de nuevo")

                    else:
                        lost_count = 0

                        displacement_x = detection["x"] - current_x
                        displacement_y = detection["y"] - current_y

                        velocity_x = (
                            velocity_x * 0.65
                            + displacement_x * 0.35
                        )

                        velocity_y = (
                            velocity_y * 0.65
                            + displacement_y * 0.35
                        )

                        track_reference = (
                            detection["x"],
                            detection["y"],
                            detection["radius"],
                        )

                        last_good_reference = track_reference
                        detected_counter += 1

                        trail.append(
                            (
                                int(round(detection["x"])),
                                int(round(detection["y"])),
                            )
                        )


            if detection is not None and frame_counter % 30 == 0:
                print(
                    f"[LUZ] interior={detection['inner_mean']:.1f} "
                    f"anillo={detection['ring_mean']:.1f} "
                    f"contraste={detection['contrast']:.1f}",
                    flush=True,
                )

            # Estado combinado IMU + cámara para la app Android.

            camera_ok = (
                state == "TRACK"
                and track_reference is not None
            )

            if camera_ok:
                if tracking_started_at is None:
                    tracking_started_at = now

                source_x = (
                    detection["x"]
                    if detection is not None
                    else track_reference[0]
                )
                source_y = (
                    detection["y"]
                    if detection is not None
                    else track_reference[1]
                )
                app_x, app_y = coordenadas_app(source_x, source_y)

                position_history.append({
                    "monotonic": now,
                    "boottime_ns": frame_boottime_ns,
                    "x": app_x,
                    "y": app_y,
                    "valid": 1 if detection is not None else 0,
                })
            else:
                tracking_started_at = None
                app_x = 0
                app_y = 0

            # Guardar cada frame real mientras esta activo el disparo.
            if (
                trace_disparo_activa
                and frame_boottime_ns is not None
            ):
                guardar_trace = True

                if trace_shot_boottime_ns is not None:
                    limite_post_ns = (
                        trace_shot_boottime_ns
                        + int(
                            TRACE_POST_MAX_SECONDS
                            * 1_000_000_000
                        )
                    )

                    if frame_boottime_ns > limite_post_ns:
                        guardar_trace = False
                        trace_post_truncated = True

                if guardar_trace:
                    trace_disparo.append((
                        int(frame_boottime_ns),
                        float(app_x),
                        float(app_y),
                        1 if detection is not None else 0,
                    ))

                    # Antes de T se conservan solo los ultimos 30 s.
                    if trace_shot_boottime_ns is None:
                        limite_pre_ns = (
                            int(frame_boottime_ns)
                            - int(
                                TRACE_PRE_MAX_SECONDS
                                * 1_000_000_000
                            )
                        )

                        while (
                            trace_disparo
                            and trace_disparo[0][0] < limite_pre_ns
                        ):
                            trace_disparo.popleft()
            # Estados Android:
            # 0 = STANDBY
            # 1 = APUNTANDO
            # 2 = POST_DISPARO
            # 3 = ENFOQUE
            if imu_estado == "STANDBY":
                shot_position_valid = False
                actualizar_ble_status(
                    shot_x=0,
                    shot_y=0,
                    shot_ms=0,
                )
                app_state = 0
            elif imu_estado == "POST_DISPARO":
                # No anunciar RESULTADO a Android hasta que la posición
                # histórica del disparo haya quedado asociada.
                if shot_position_valid:
                    app_state = 2
                elif camera_ok:
                    app_state = 1
                else:
                    app_state = 3
            else:
                # Al terminar POST_DISPARO, borrar la posición del disparo
                # anterior para que nunca pueda reutilizarse en el siguiente.
                shot_position_valid = False
                actualizar_ble_status(
                    shot_x=0,
                    shot_y=0,
                    shot_ms=0,
                )
                app_state = 1 if camera_ok else 3

            # Recoger eventos IMU sin asociarlos todavía.
            while True:
                shot_event = imu.consume_shot()

                if shot_event is None:
                    break

                pending_shots.append(shot_event)
                if (
                    trace_disparo_activa
                    and trace_shot_boottime_ns is None
                    and shot_event.get("boottime_ns") is not None
                ):
                    trace_shot_boottime_ns = (
                        int(shot_event["boottime_ns"])
                        + SHOT_EXIT_DELAY_NS
                    )

                    print(
                        "[TRACE] disparo detectado "
                        f"T={trace_shot_boottime_ns}",
                        flush=True,
                    )

            # Asociar solamente cuando la cámara ya haya cruzado
            # temporalmente el instante físico del disparo.
            while pending_shots:
                shot_event = pending_shots[0]

                shot_boottime_ns = shot_event.get("boottime_ns")

                if shot_boottime_ns is None:
                    pending_shots.popleft()
                    print(
                        f"[CAMARA] DISPARO {shot_event['numero']} "
                        "sin timestamp IMU",
                        flush=True,
                    )
                    continue

                if frame_boottime_ns is None:
                    break

                # Instante estimado en el que el balin sale de la boca.
                target_boottime_ns = (
                    int(shot_boottime_ns)
                    + SHOT_EXIT_DELAY_NS
                )

                # Esperar hasta que la camara haya capturado
                # al menos un frame posterior a T_salida.
                if int(frame_boottime_ns) <= target_boottime_ns:
                    break

                posiciones_validas = [
                    posicion
                    for posicion in position_history
                    if (
                        posicion["valid"] == 1
                        and posicion.get("boottime_ns") is not None
                    )
                ]

                anteriores = [
                    posicion
                    for posicion in posiciones_validas
                    if int(posicion["boottime_ns"])
                    <= target_boottime_ns
                ]

                posteriores = [
                    posicion
                    for posicion in posiciones_validas
                    if int(posicion["boottime_ns"])
                    >= target_boottime_ns
                ]

                if not anteriores:
                    pending_shots.popleft()

                    print(
                        f"[CAMARA] DISPARO {shot_event['numero']} "
                        "sin posicion valida anterior a T_salida",
                        flush=True,
                    )
                    continue

                # Si el frame que acaba de llegar no fue valido,
                # esperar hasta 150 ms a una deteccion valida posterior.
                if not posteriores:
                    espera_post_ms = (
                        int(frame_boottime_ns)
                        - target_boottime_ns
                    ) / 1_000_000.0

                    if espera_post_ms <= 150.0:
                        break

                    pending_shots.popleft()

                    print(
                        f"[CAMARA] DISPARO {shot_event['numero']} "
                        "sin posicion valida posterior a T_salida "
                        f"tras {espera_post_ms:.1f} ms",
                        flush=True,
                    )
                    continue

                posicion_1 = anteriores[-1]
                posicion_2 = posteriores[0]

                t1 = int(posicion_1["boottime_ns"])
                t2 = int(posicion_2["boottime_ns"])

                dt_pre_ms = (
                    target_boottime_ns - t1
                ) / 1_000_000.0

                dt_post_ms = (
                    t2 - target_boottime_ns
                ) / 1_000_000.0

                if (
                    dt_pre_ms > 150.0
                    or dt_post_ms > 150.0
                ):
                    pending_shots.popleft()

                    print(
                        f"[CAMARA] DISPARO {shot_event['numero']} "
                        "descartado: interpolacion demasiado lejana "
                        f"pre={dt_pre_ms:.1f}ms "
                        f"post={dt_post_ms:.1f}ms",
                        flush=True,
                    )
                    continue

                if t2 == t1:
                    alpha = 0.0
                else:
                    alpha = (
                        target_boottime_ns - t1
                    ) / float(t2 - t1)

                alpha = max(0.0, min(1.0, alpha))

                x1 = float(posicion_1["x"])
                y1 = float(posicion_1["y"])
                x2 = float(posicion_2["x"])
                y2 = float(posicion_2["y"])

                x_interp = x1 + alpha * (x2 - x1)
                y_interp = y1 + alpha * (y2 - y1)

                pending_shots.popleft()

                shot_position_valid = True
                app_state = 2

                actualizar_ble_status(
                    shot_x=x_interp,
                    shot_y=y_interp,
                    shot_ms=int(
                        target_boottime_ns // 1_000_000
                    ),
                )

                print(
                    f"[CAMARA-TIMING] DISPARO "
                    f"{shot_event['numero']} "
                    f"T_salida=IMU+{SHOT_EXIT_DELAY_MS:.3f}ms "
                    f"dt1=-{dt_pre_ms:.3f}ms "
                    f"dt2=+{dt_post_ms:.3f}ms "
                    f"alpha={alpha:.6f} "
                    f"P1=({x1:.3f},{y1:.3f}) "
                    f"P2=({x2:.3f},{y2:.3f}) "
                    f"Pinterp=({x_interp:.3f},{y_interp:.3f})",
                    flush=True,
                )

                print(
                    f"[CAMARA] DISPARO {shot_event['numero']} "
                    f"interpolado X={x_interp:.3f} "
                    f"Y={y_interp:.3f}",
                    flush=True,
                )

            # Publicar BLE despues de asociar el disparo.
            actualizar_ble_status(
                state=app_state,
                time=(
                    int((now - tracking_started_at) * 1000)
                    if tracking_started_at is not None
                    else 0
                ),
                x=app_x,
                y=app_y,
                frame_ms=frame_time_ms,
                v=1 if (camera_ok and detection is not None) else 0,
                s=0,
                c=1,
                f=0,
            )

            frame_counter += 1

            if detection is None:
                csv_file.write(
                    f"{timestamp:.6f},,,,{state},,"
                    f"{velocity_x:.3f},{velocity_y:.3f},"
                    f"{fps:.3f},{lost_count},{search_fail_count}\n"
                )
            else:
                csv_file.write(
                    f"{timestamp:.6f},"
                    f"{detection['x']:.3f},"
                    f"{detection['y']:.3f},"
                    f"{detection['radius']:.3f},"
                    f"{state},"
                    f"{detection['contrast']:.3f},"
                    f"{velocity_x:.3f},"
                    f"{velocity_y:.3f},"
                    f"{fps:.3f},{lost_count},{search_fail_count}\n"
                )

            # El visor no consume CPU durante el funcionamiento normal.
            # Solo generamos imagen si existe un cliente conectado a /video.
            if not visor_activo():
                continue

            # Limitar además la frecuencia visual.
            if frame_counter % STREAM_EVERY_N_FRAMES != 0:
                continue

            overlay = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)

            # Centro óptico de la cámara.
            cv2.drawMarker(
                overlay,
                (WIDTH // 2, HEIGHT // 2),
                (255, 255, 255),
                cv2.MARKER_CROSS,
                22,
                1,
            )

            if search_rectangle is not None:
                cv2.rectangle(
                    overlay,
                    search_rectangle[:2],
                    search_rectangle[2:],
                    (180, 180, 180),
                    1,
                )

            if track_rectangle is not None:
                cv2.rectangle(
                    overlay,
                    track_rectangle[:2],
                    track_rectangle[2:],
                    (160, 160, 160),
                    1,
                )

            dibujar_trayectoria(overlay, trail)

            if detection is not None:
                point = (
                    int(round(detection["x"])),
                    int(round(detection["y"])),
                )

                radius = int(round(detection["radius"]))

                cv2.circle(
                    overlay,
                    point,
                    radius,
                    (0, 255, 0),
                    2,
                )

                cv2.drawMarker(
                    overlay,
                    point,
                    (0, 255, 0),
                    cv2.MARKER_CROSS,
                    25,
                    2,
                )

            state_color = (
                (0, 255, 0)
                if state == "TRACK"
                else (0, 255, 255)
            )

            cv2.putText(
                overlay,
                f"Estado: {state}",
                (25, 35),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.8,
                state_color,
                2,
            )

            cv2.putText(
                overlay,
                f"FPS: {fps:.1f}",
                (25, 70),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (255, 255, 255),
                2,
            )

            cv2.putText(
                overlay,
                f"Perdidas: {loss_events}  "
                f"Recuperaciones: {reacquisitions}",
                (25, 103),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (255, 255, 255),
                2,
            )

            last_overlay = overlay
            solicitar_jpeg(overlay)

    finally:
        if camera_running:
            camera.stop()
        csv_file.close()

        if last_overlay is not None:
            cv2.imwrite(str(OUTPUT_IMAGE), last_overlay)

        print(f"Imagen final: {OUTPUT_IMAGE}")
        print(f"Datos: {csv_path}")


def mjpeg_stream():
    global video_clients

    with video_clients_lock:
        video_clients += 1
        clients_now = video_clients

    print(
        f"[VISOR] Panel de cámara activo clientes={clients_now}",
        flush=True,
    )

    # No reutilizar un JPEG antiguo de una conexión anterior.
    with frame_condition:
        last_number = latest_frame_number

    try:
        while not stop_event.is_set():
            with frame_condition:
                frame_condition.wait_for(
                    lambda: (
                        latest_frame_number != last_number
                        or stop_event.is_set()
                    ),
                    timeout=1.0,
                )

                if stop_event.is_set():
                    return

                if latest_frame_number == last_number:
                    continue

                frame = latest_jpeg
                last_number = latest_frame_number

            if frame is None:
                continue

            yield (
                b"--frame\r\n"
                b"Content-Type: image/jpeg\r\n\r\n"
                + frame
                + b"\r\n"
            )

    finally:
        with video_clients_lock:
            video_clients = max(0, video_clients - 1)
            clients_now = video_clients

        print(
            f"[VISOR] Panel de cámara cerrado clientes={clients_now}",
            flush=True,
        )


@app.route("/")
def index():
    return """
    <!doctype html>
    <html lang="es">
      <head>
        <meta charset="utf-8">
        <title>Splatt - seguimiento</title>
        <style>
          body {
            margin: 0;
            padding: 15px;
            background: #111;
            color: #eee;
            font-family: sans-serif;
            text-align: center;
          }

          img {
            width: min(1280px, 98vw);
            height: auto;
            border: 1px solid #555;
          }

          button {
            margin: 12px;
            padding: 10px 24px;
            font-size: 16px;
          }
        </style>
      </head>

      <body>
        <h2>Splatt — seguimiento de la diana</h2>

        <img src="/video">

        <div>
          <button onclick="reiniciar()">
            Reiniciar seguimiento
          </button>
        </div>

        <script>
          async function reiniciar() {
            await fetch("/reset", {method: "POST"});
          }
        </script>
      </body>
    </html>
    """


@app.route("/video")
def video():
    return Response(
        mjpeg_stream(),
        mimetype="multipart/x-mixed-replace; boundary=frame",
    )


@app.route("/reset", methods=["POST"])
def reset():
    reset_event.set()
    return jsonify({"ok": True})


if __name__ == "__main__":
    camera_worker = threading.Thread(
        target=capture_loop,
        daemon=True,
        name="camera-detector",
    )
    ble_worker = threading.Thread(
        target=ble_loop,
        daemon=True,
        name="ble-server",
    )
    jpeg_worker = threading.Thread(
        target=jpeg_loop,
        daemon=True,
        name="jpeg-encoder",
    )

    imu.start()
    jpeg_worker.start()
    camera_worker.start()
    ble_worker.start()

    try:
        app.run(
            host="0.0.0.0",
            port=8000,
            threaded=True,
            debug=False,
            use_reloader=False,
        )

    finally:
        stop_event.set()

        with frame_condition:
            frame_condition.notify_all()

        with jpeg_condition:
            jpeg_condition.notify_all()

        imu.stop()
        camera_worker.join(timeout=5)
        jpeg_worker.join(timeout=3)
        ble_worker.join(timeout=3)
