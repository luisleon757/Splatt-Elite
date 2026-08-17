# Software de Raspberry Pi

Esta carpeta contiene todo el código propio necesario para ejecutar Splatt Elite en la Raspberry Pi:

- `visor_movimiento_ble.py`: cámara, detección, visor web y servicio BLE.
- `splatt_imu.py`: lectura del MPU6050 y detección del estado/disparo.
- `bluez_gatt_server.py`: clases GATT de referencia de BlueZ, distribuidas bajo LGPL-2.1-or-later.

## Instalación de archivos

Copiar los tres módulos a `/home/pi/`:

```bash
scp raspberry/*.py pi@IP_DE_LA_RASPBERRY:/home/pi/
```

## Dependencias del sistema

El programa está diseñado para Raspberry Pi OS con cámara y Bluetooth habilitados. Utiliza:

- Picamera2 y libcamera.
- OpenCV.
- NumPy.
- Flask.
- D-Bus y PyGObject.
- SMBus.
- BlueZ y `btmgmt`.

Paquetes habituales de Raspberry Pi OS:

```bash
sudo apt install python3-picamera2 python3-opencv python3-numpy python3-flask python3-dbus python3-gi python3-smbus bluez
```

## Validación

```bash
python3 -m py_compile /home/pi/visor_movimiento_ble.py /home/pi/splatt_imu.py /home/pi/bluez_gatt_server.py
```

## Ejecución actual

```bash
sudo python3 /home/pi/visor_movimiento_ble.py
```

El anuncio BLE mediante `btmgmt` funciona actualmente con ejecución manual. El arranque como servicio de sistema queda pendiente de una solución que no bloquee esos comandos.
