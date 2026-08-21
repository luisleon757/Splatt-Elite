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
    UMBRAL_VIBRACION_DEBIL = 0.030
    UMBRAL_SALTO_DEBIL = 0.045
    BLOQUEO_DISPARO = 0.50
    DIAG_VENTANA = 0.020
    DIAG_FACTOR_INICIO = 0.70

    TAU_HP = 0.030
    PERIODO_MUESTREO_ACTIVO = 0.0025   # 400 Hz
    PERIODO_MUESTREO_STANDBY = 0.0200  # 50 Hz

    ANGULO_ENTRADA_HORIZONTAL = 10.0
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
        self.diag_activo = False
        self.diag_inicio = 0.0
        self.diag_vmax = 0.0
        self.diag_jmax = 0.0
        self.diag_j_en_vmax = 0.0
        self.diag_v_en_jmax = 0.0

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

    def _diagnosticar_candidato(self, t, vibracion, salto, disparo):
        # Solo observa durante PUNTERIA y fuera de los bloqueos normales.
        if (
            self.estado != "PUNTERIA"
            or t - self.estado_desde < 0.25
            or t - self.ultimo_disparo < self.BLOQUEO_DISPARO
        ):
            self.diag_activo = False
            return None

        inicio_candidato = (
            vibracion >= self.UMBRAL_VIBRACION * self.DIAG_FACTOR_INICIO
            or salto >= self.UMBRAL_SALTO * self.DIAG_FACTOR_INICIO
        )

        if not self.diag_activo:
            if not inicio_candidato:
                return None

            self.diag_activo = True
            self.diag_inicio = t
            self.diag_vmax = vibracion
            self.diag_jmax = salto
            self.diag_j_en_vmax = salto
            self.diag_v_en_jmax = vibracion
        else:
            if vibracion > self.diag_vmax:
                self.diag_vmax = vibracion
                self.diag_j_en_vmax = salto

            if salto > self.diag_jmax:
                self.diag_jmax = salto
                self.diag_v_en_jmax = vibracion

        # Caso fuerte: V y J coinciden en la misma muestra.
        if disparo:
            print(
                f"[IMU-DIAG] CONFIRMADO "
                f"Vmax={self.diag_vmax:.4f}g "
                f"(J={self.diag_j_en_vmax:.4f}) "
                f"Jmax={self.diag_jmax:.4f}g "
                f"(V={self.diag_v_en_jmax:.4f})",
                flush=True,
            )

            resultado = (vibracion, salto)
            self.diag_activo = False
            return resultado

        # Caso débil/no coincidente: comprobar los máximos de 20 ms.
        if t - self.diag_inicio >= self.DIAG_VENTANA:
            vmax = self.diag_vmax
            jmax = self.diag_jmax

            if (
                vmax >= self.UMBRAL_VIBRACION
                and jmax >= self.UMBRAL_SALTO
            ):
                print(
                    f"[IMU-DIAG] CONFIRMADO-VENTANA "
                    f"Vmax={vmax:.4f}g "
                    f"(J={self.diag_j_en_vmax:.4f}) "
                    f"Jmax={jmax:.4f}g "
                    f"(V={self.diag_v_en_jmax:.4f})",
                    flush=True,
                )

                self.diag_activo = False
                return (vmax, jmax)

            if (
                vmax >= self.UMBRAL_VIBRACION_DEBIL
                and jmax >= self.UMBRAL_SALTO_DEBIL
            ):
                print(
                    f"[IMU-DIAG] CONFIRMADO-DEBIL "
                    f"Vmax={vmax:.4f}g "
                    f"(J={self.diag_j_en_vmax:.4f}) "
                    f"Jmax={jmax:.4f}g "
                    f"(V={self.diag_v_en_jmax:.4f})",
                    flush=True,
                )

                self.diag_activo = False
                return (vmax, jmax)

            fallos = []

            if vmax < self.UMBRAL_VIBRACION:
                fallos.append("V")

            if jmax < self.UMBRAL_SALTO:
                fallos.append("J")

            motivo = "+".join(fallos)

            self.diag_activo = False

        return None

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

        disparo_detectado = self._diagnosticar_candidato(
            t,
            vibracion,
            salto,
            disparo,
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

                if disparo_detectado is not None:
                    disparo_v, disparo_j = disparo_detectado
                    self._registrar_disparo(
                        t,
                        disparo_v,
                        disparo_j,
                    )

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

            periodo_muestreo = (
                self.PERIODO_MUESTREO_STANDBY
                if self.estado == "STANDBY"
                else self.PERIODO_MUESTREO_ACTIVO
            )
            siguiente_muestra += periodo_muestreo

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
