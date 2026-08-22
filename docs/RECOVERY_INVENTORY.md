# Splatt Elite — Inventario de recuperación

Este documento describe la Raspberry de referencia usada para la versión estable de Splatt Elite. Su objetivo es permitir reconstruir el sistema de forma reproducible.

> No contiene contraseñas ni secretos. Los identificadores específicos de una instalación (Machine ID, Boot ID, PARTUUID, IP DHCP y similares) no deben copiarse literalmente a otra Raspberry.

## 1. Hardware de referencia

- Raspberry Pi Zero 2 W Rev 1.0
- Arquitectura: aarch64
- CPU: 4 × Cortex-A53
- RAM utilizable observada: 415 MiB
- Swap: ~415 MiB zram + ~415 MiB loop
- microSD: 28.9 GB
- Partición raíz: ext4, 28.4 GB
- Partición boot: vfat, 512 MB

## 2. Sistema operativo

- Debian GNU/Linux 13 (trixie)
- Debian 13.6
- Kernel: 6.18.34+rpt-rpi-v8
- Python: 3.13.5
- Hostname: `splatt`
- Zona horaria: `Europe/Madrid`
- NTP: activo y sincronizado
- Locale: `en_GB.UTF-8`

## 3. Servicios críticos

Todos habilitados al arranque:

- `splatt.service`
- `bluetooth.service`
- `pisugar-server.service`
- `NetworkManager.service`

El servicio principal ejecuta:

```text
/usr/bin/python3 /home/pi/visor_movimiento_ble.py
```

Directorio de trabajo:

```text
/home/pi
```

## 4. Cámara

Sensor detectado:

- OV9281
- 1280 × 800
- 10-bit MONO

Paquetes:

- `python3-picamera2` 0.3.36-1
- `python3-opencv` 4.10.0+dfsg-5
- `libcamera` 0.7.1+rpt20260609-1
- `rpicam-apps-core` 1.12.0-1
- `rpicam-apps-lite` 1.12.0-1

Configuración Splatt:

- Captura: 1280 × 800
- Formato: YUV420
- `CAMERA_FPS = 60`
- `hflip = true`
- `vflip = true`
- Exposición: 800 µs
- Ganancia analógica: 2.5
- Ventana de búsqueda: 700 × 500
- `TRACK_ROI_SIZE = 180`
- `REACQUIRE_ROI_SIZE = 360`
- `MAX_LOST_FRAMES = 5`
- `MAX_TRACK_ERROR = 55.0`
- Cámara apagada tras 5 s de STANDBY
- Streaming: 1 de cada 4 frames

Nota: `rpicam-hello --list-cameras` mostró modos a 30 fps, mientras Splatt solicita 60 fps. La configuración actual funciona y no debe modificarse en una recuperación sin validación adicional.

## 5. IMU

Hardware compatible con familia MPU-60x0 / MPU6050:

- Bus I2C: 3
- Dirección: `0x68`
- `WHO_AM_I`: `0x68`
- SDA: GPIO 23
- SCL: GPIO 24

Parámetros validados:

- `GYRO_BIAS = (-2.30, -1.43, -1.96)`
- `UMBRAL_VIBRACION = 0.045`
- `UMBRAL_SALTO = 0.060`
- `UMBRAL_VIBRACION_DEBIL = 0.030`
- `UMBRAL_SALTO_DEBIL = 0.045`
- `BLOQUEO_DISPARO = 0.50 s`
- `DIAG_VENTANA = 0.020 s`
- `DIAG_FACTOR_INICIO = 0.70`
- Muestreo activo: 0.0025 s (400 Hz)
- Muestreo STANDBY: 0.0200 s (50 Hz)
- Entrada horizontal: 10°
- Salida horizontal: 12°
- Tiempo entrada horizontal: 0.25 s
- Tiempo salida horizontal: 0.30 s
- POST_DISPARO: 1.50 s

## 6. I2C

Buses observados:

- i2c-0
- i2c-1
- i2c-2
- i2c-3
- i2c-10
- i2c-11

Bus 1:

- `0x57`
- `0x68`

Bus 3:

- `0x68` — IMU de Splatt

## 7. Overlays de arranque relevantes

Entradas críticas de `/boot/firmware/config.txt`:

```text
dtparam=i2c_arm=on
camera_auto_detect=0
arm_64bit=1

[all]
dtoverlay=ov9281
dtoverlay=i2c-gpio,bus=3,i2c_gpio_sda=23,i2c_gpio_scl=24
```

No copiar literalmente `PARTUUID` de `/boot/firmware/cmdline.txt` a otra tarjeta.

Región Wi-Fi actual:

```text
cfg80211.ieee80211_regdom=ES
```

## 8. Bluetooth / BLE

Versiones:

- BlueZ 5.82
- `bluez` 5.82-1.1+rpt1
- `bluez-firmware` 1.2-13+rpt2
- `libbluetooth3` 5.82-1.1+rpt1

Controlador:

- hci0
- Nombre: `splatt`
- BLE activo
- Rol central y peripheral disponible

UUIDs Splatt:

```text
Servicio: 12345678-1234-5678-1234-56789abcdef0
Estado:   12345678-1234-5678-1234-56789abcdef1
Comando:  12345678-1234-5678-1234-56789abcdef2
Config:   12345678-1234-5678-1234-56789abcdef3
```

- Intervalo de notificación BLE: 100 ms
- Una instancia de advertising activa según `btmgmt advinfo`

Payload compacto actual:

```text
estado,x,y,valida,tiempo,host,bateria,shot_x,shot_y
```

El campo `tiempo` se transmite actualmente como `0`; Android usa un reloj monotónico local para temporizar las trazas.

## 9. PiSugar

Modelo:

- PiSugar 3

Paquetes instalados:

- `pisugar-poweroff` 2.3.2-1
- `pisugar-programmer` 2.3.2-1
- `pisugar-server` 2.3.2-1

Servicio:

```text
pisugar-server.service
```

Opciones relevantes:

- Config: `/etc/pisugar-server/config.json`
- Web: `/usr/share/pisugar-server/web`
- HTTP: 8421
- WebSocket: 8422
- TCP: 8423
- UDS: `/tmp/pisugar-server.sock`
- Bus I2C configurado: 1

Comprobación de referencia:

```text
get battery
get battery_v
```

El socket respondió correctamente durante el inventario.

Importante: los paquetes PiSugar están instalados, pero `apt-cache policy` no mostró un repositorio APT que los ofrezca. Tampoco había copias `.deb` en la caché ni en `/home/pi`, `/root` o `/tmp`. La recuperación deberá preservar o descargar explícitamente esos paquetes.

## 10. Red

- Interfaz: `wlan0`
- Gestor: NetworkManager
- IPv4: DHCP
- Autoconexión: sí
- SSID de referencia: `SPLATT-S25`

La IP concreta no debe fijarse. Splatt obtiene la IP local dinámicamente y la publica por BLE.

Puertos observados:

- 8000/TCP — Splatt web
- 8421/TCP — PiSugar HTTP
- 8422/TCP — PiSugar WebSocket
- 8423/TCP — PiSugar TCP

## 11. Dependencias de sistema y Python

Paquetes relevantes:

- `python3` 3.13.5-1
- `python3-dbus` 1.4.0-1
- `python3-flask` 3.1.1-1
- `python3-gi` 3.50.0-4+b1
- `python3-numpy` 2.2.4
- `python3-smbus` 4.4-2
- `python3-smbus2` 0.4.3-1
- `python3-opencv` 4.10.0+dfsg-5
- `python3-picamera2` 0.3.36-1
- `i2c-tools` 4.4-2
- `network-manager` 1.52.1-1+rpt4
- `git` 2.47.3

`pip` no está instalado. La instalación de referencia usa paquetes Debian/APT.

Imports directos principales:

- cv2
- dbus
- Flask
- gi / GLib
- libcamera
- numpy
- picamera2
- smbus

## 12. Coordenadas y asociación de disparo

- Centro lógico Android: `(160, 120)`
- Escala de coordenadas: `0.25`
- Edad máxima aceptada de posición histórica previa al disparo: 150 ms
- `shot_x` / `shot_y` se transmiten por BLE

## 13. Archivos activos de referencia

Producción:

```text
/home/pi/visor_movimiento_ble.py
/home/pi/splatt_imu.py
/home/pi/splatt_config.json
/etc/systemd/system/splatt.service
/home/pi/bluez-examples/example-gatt-server
```

Copias equivalentes en el repositorio:

```text
raspberry/visor_movimiento_ble.py
raspberry/splatt_imu.py
raspberry/splatt_config.json
raspberry/splatt.service
raspberry/bluez_gatt_server.py
```

## 14. SHA-256 de la Raspberry inventariada

```text
1a4fb0a4316c3ccc32d54f8f4ba866d3dbecb44d12f433dc2fcdb1d75d444d31  visor_movimiento_ble.py
6dd6596f5acf028e0d4f3c8462cab3d9e9487144037336ce3de2f58767fa1f18  splatt_imu.py
c43c5b0dd777efa562c2d4f709ca79aa2e6cdb77af270147d8749aec39048413  splatt_config.json
a190508dd5bafd048ead4404535610c8c827181917cb569530f8de36ad329fd6  example-gatt-server
```

## 15. Referencia de funcionamiento

Durante el inventario:

- `splatt.service` llevaba aproximadamente 23 horas activo
- Proceso principal: `/usr/bin/python3 /home/pi/visor_movimiento_ble.py`
- RSS observado: ~75 MB
- CPU observada en ese instante: ~7.6 %
- GATT registrado correctamente
- Advertising `Splatt_Elite` activo
- Notificaciones BLE activadas
- PiSugar accesible por UDS

Estos valores son orientativos para comparar una instalación recuperada con la unidad de referencia.

## 16. Elementos que NO deben clonarse literalmente

- Contraseñas
- Claves Wi-Fi
- Machine ID
- Boot ID
- PARTUUID
- IP DHCP
- Dirección MAC Bluetooth
- Tokens o claves privadas

## 17. Pendientes de la versión de recuperación

1. Crear `install.sh` reproducible.
2. Crear `diagnostics.sh` con comprobaciones automáticas.
3. Resolver preservación/instalación reproducible de PiSugar 2.3.2-1.
4. Crear `VERSION` y notas de versión.
5. Conservar APK Android estable y su SHA-256.
6. Resolver firma Android estable para permitir actualizaciones sin desinstalar.
7. Crear procedimiento de exportación/importación de calibración Android.
8. Crear paquete ZIP de recuperación completo.
