import math
import struct
import threading
import time
from collections import deque

import smbus


class SplattIMU:
    ADDR = 0x68

    GYRO_BIAS = (-2.30, -1.43, -1.96)

    UMBRAL_VIBRACION = 0.045
    UMBRAL_SALTO = 0.060
    BLOQUEO_DISPARO = 0.50

    TAU_HP = 0.030
    PERIODO_MUESTREO = 0.0025  # 400 Hz

    ANGULO_ENTRADA_HORIZONTAL = 8.0
    ANGULO_SALIDA_HORIZONTAL = 12.0
    TIEMPO_ENTRADA_HORIZONTAL = 0.25
    TIEMPO_SALIDA_HORIZONTAL = 0.30
    TIEMPO_POST_DISPARO = 1.50

    def __init__(self, bus_num=3):
        self.bus_num = bus_num
        self.bus = None

        self._running = False
        self._thread = None
        self._lock = threading.Lock()
        self._shot_events = deque(maxlen=16)

        self.estado = "STANDBY"
        self.estado_desde = 0.0
        self.contador_disparos = 0

        self.horizontal_desde = None
        self.fuera_horizontal_desde = None

        self.lp = None
        self.aceleracion_anterior = None
        self.ultimo_disparo = -10.0

        self.snapshot_data = {
            "estado": self.estado,
            "estado_desde": 0.0,
            "disparos": 0,
            "ax": 0.0,
            "ay": 0.0,
            "az": 0.0,
            "gx": 0.0,
            "gy": 0.0,
            "gz": 0.0,
            "giro": 0.0,
            "vibracion": 0.0,
            "salto": 0.0,
            "postura_mesa": False,
            "postura_punteria": False,
            "angulo_horizontal": 180.0,
            "error": None,
        }

    def start(self):
        if self._running:
            return

        self.bus = smbus.SMBus(self.bus_num)

        self.bus.write_byte_data(self.ADDR, 0x6B, 0x00)
        self.bus.write_byte_data(self.ADDR, 0x1A, 0x01)
        self.bus.write_byte_data(self.ADDR, 0x19, 0x00)
        self.bus.write_byte_data(self.ADDR, 0x1B, 0x10)
        self.bus.write_byte_data(self.ADDR, 0x1C, 0x10)

        self._running = True
        self._thread = threading.Thread(
            target=self._run,
            name="SplattIMU",
            daemon=True,
        )
        self._thread.start()

    def stop(self):
        self._running = False

        if self._thread is not None:
            self._thread.join(timeout=2.0)

        if self.bus is not None:
            try:
                self.bus.close()
            except Exception:
                pass

        self.bus = None

    def snapshot(self):
        with self._lock:
            return dict(self.snapshot_data)

    def consume_shot(self):
        with self._lock:
            if not self._shot_events:
                return None
            return self._shot_events.popleft()

    def _cambiar_estado(self, nuevo, t):
        if nuevo == self.estado:
            return

        anterior = self.estado
        self.estado = nuevo
        self.estado_desde = t

        self.horizontal_desde = None
        self.fuera_horizontal_desde = None

        print(f"[IMU] {t:8.3f}s | {anterior} -> {nuevo}")

    def _registrar_disparo(self, t, vibracion, salto):
        self.contador_disparos += 1
        self.ultimo_disparo = t

        evento = {
            "numero": self.contador_disparos,
            "tiempo": t,
            "monotonic": time.monotonic(),
            "vibracion": vibracion,
            "salto": salto,
        }

        with self._lock:
            self._shot_events.append(evento)

        print(
            f"[IMU] {t:8.3f}s | DISPARO {self.contador_disparos} "
            f"V={vibracion:.4f}g J={salto:.4f}g"
        )

        self._cambiar_estado("POST_DISPARO", t)

    def _procesar_estado(
        self,
        t,
        giro,
        vibracion,
        salto,
        angulo_horizontal,
    ):
        horizontal_entrada = (
            angulo_horizontal <= self.ANGULO_ENTRADA_HORIZONTAL
        )

        horizontal_mantenimiento = (
            angulo_horizontal <= self.ANGULO_SALIDA_HORIZONTAL
        )

        disparo = (
            vibracion >= self.UMBRAL_VIBRACION
            and salto >= self.UMBRAL_SALTO
            and t - self.ultimo_disparo >= self.BLOQUEO_DISPARO
        )

        if self.estado == "STANDBY":
            if horizontal_entrada:
                if self.horizontal_desde is None:
                    self.horizontal_desde = t
                elif (
                    t - self.horizontal_desde
                    >= self.TIEMPO_ENTRADA_HORIZONTAL
                ):
                    self._cambiar_estado("PUNTERIA", t)
            else:
                self.horizontal_desde = None

        elif self.estado == "PUNTERIA":
            if not horizontal_mantenimiento:
                if self.fuera_horizontal_desde is None:
                    self.fuera_horizontal_desde = t
                elif (
                    t - self.fuera_horizontal_desde
                    >= self.TIEMPO_SALIDA_HORIZONTAL
                ):
                    self._cambiar_estado("STANDBY", t)
            else:
                self.fuera_horizontal_desde = None

                if disparo and t - self.estado_desde >= 0.25:
                    self._registrar_disparo(t, vibracion, salto)

        elif self.estado == "POST_DISPARO":
            if not horizontal_mantenimiento:
                if self.fuera_horizontal_desde is None:
                    self.fuera_horizontal_desde = t
                elif (
                    t - self.fuera_horizontal_desde
                    >= self.TIEMPO_SALIDA_HORIZONTAL
                ):
                    self._cambiar_estado("STANDBY", t)
            else:
                self.fuera_horizontal_desde = None

                if t - self.estado_desde >= self.TIEMPO_POST_DISPARO:
                    self._cambiar_estado("PUNTERIA", t)

    def _run(self):
        inicio = time.perf_counter()
        ultimo_t = inicio
        siguiente_muestra = inicio

        while self._running:
            ahora = time.perf_counter()

            if ahora < siguiente_muestra:
                time.sleep(siguiente_muestra - ahora)
                ahora = time.perf_counter()

            siguiente_muestra += self.PERIODO_MUESTREO

            try:
                datos = self.bus.read_i2c_block_data(
                    self.ADDR,
                    0x3B,
                    14,
                )

                ax, ay, az, temperatura, gx, gy, gz = struct.unpack(
                    ">hhhhhhh",
                    bytes(datos),
                )

                ax /= 4096.0
                ay /= 4096.0
                az /= 4096.0

                gx = gx / 32.8 - self.GYRO_BIAS[0]
                gy = gy / 32.8 - self.GYRO_BIAS[1]
                gz = gz / 32.8 - self.GYRO_BIAS[2]

                giro = math.sqrt(gx * gx + gy * gy + gz * gz)

                dt = ahora - ultimo_t
                ultimo_t = ahora
                t = ahora - inicio

                aceleracion = (ax, ay, az)

                if self.lp is None:
                    self.lp = [ax, ay, az]
                    self.aceleracion_anterior = aceleracion
                    continue

                beta = dt / (self.TAU_HP + dt)

                for i, valor in enumerate(aceleracion):
                    self.lp[i] += beta * (valor - self.lp[i])

                hx = ax - self.lp[0]
                hy = ay - self.lp[1]
                hz = az - self.lp[2]

                vibracion = math.sqrt(
                    hx * hx + hy * hy + hz * hz
                )

                salto = math.sqrt(
                    (ax - self.aceleracion_anterior[0]) ** 2
                    + (ay - self.aceleracion_anterior[1]) ** 2
                    + (az - self.aceleracion_anterior[2]) ** 2
                )

                self.aceleracion_anterior = aceleracion

                postura_mesa = (
                    ay < -0.80
                    and abs(ax) < 0.35
                    and abs(az) < 0.35
                )

                angulo_horizontal = math.degrees(
                    math.atan2(
                        math.sqrt(ax * ax + ay * ay),
                        -az,
                    )
                )

                postura_punteria = (
                    angulo_horizontal
                    <= self.ANGULO_SALIDA_HORIZONTAL
                )

                self._procesar_estado(
                    t,
                    giro,
                    vibracion,
                    salto,
                    angulo_horizontal,
                )

                with self._lock:
                    self.snapshot_data.update({
                        "estado": self.estado,
                        "estado_desde": self.estado_desde,
                        "disparos": self.contador_disparos,
                        "ax": ax,
                        "ay": ay,
                        "az": az,
                        "gx": gx,
                        "gy": gy,
                        "gz": gz,
                        "giro": giro,
                        "vibracion": vibracion,
                        "salto": salto,
                        "postura_mesa": postura_mesa,
                        "postura_punteria": postura_punteria,
                        "angulo_horizontal": angulo_horizontal,
                        "error": None,
                    })

            except Exception as exc:
                with self._lock:
                    self.snapshot_data["error"] = str(exc)

                print(f"[IMU] Error: {exc}")
                time.sleep(0.1)


if __name__ == "__main__":
    imu = SplattIMU()
    imu.start()

    print("Prueba IMU activa. Pulsa Ctrl+C para terminar.")

    try:
        while True:
            time.sleep(1.0)
            datos = imu.snapshot()
            print(
                f"[IMU] estado={datos['estado']} "
                f"giro={datos['giro']:.2f}°/s "
                f"ang={datos['angulo_horizontal']:.1f}° "
                f"V={datos['vibracion']:.4f}g "
                f"J={datos['salto']:.4f}g "
                f"disparos={datos['disparos']}"
            )
    except KeyboardInterrupt:
        pass
    finally:
        imu.stop()
