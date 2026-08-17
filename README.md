# Splatt Elite

Sistema de entrenamiento de tiro formado por una Raspberry Pi con cámara, una aplicación Android y comunicación Bluetooth Low Energy (BLE).

> Estado: desarrollo y pruebas. La calibración de visión y el centrado de impactos deben terminar de validarse en pista.

## Arquitectura activa

- **Raspberry Pi**: procesa la imagen, detecta la diana y el movimiento, gestiona el IMU, publica el visor web y actúa como periférico BLE.
- **Aplicación Android**: muestra diana, trayectoria, impactos, puntuación, sesiones y estadísticas.
- **Wi-Fi**: se utiliza para el visor de cámara integrado en la app.
- **BLE**: transporta el estado, las coordenadas y los comandos entre Raspberry y Android.

El software activo de la Raspberry está en:

```text
visor_movimiento_ble_raspberry.py
```

La carpeta `hardware/esp32_*` contiene prototipos y firmware históricos. No representa la arquitectura activa del sistema.

## Funciones actuales

- Conexión BLE automática mediante el UUID del servicio Splatt.
- Envío automático de la IP de la Raspberry a la app por BLE.
- Visor web de cámara integrado en Android.
- Representación de trayectoria e impactos sobre una diana escalada.
- Puntuación, estabilidad en el 10, estadísticas e historial de sesiones CSV.
- Ajuste automático de visión en la Raspberry.
- Centrado de impactos y ajuste manual mediante pad direccional.
- Distancia a la diana configurable entre 1 y 25 metros.
- Tema de diana normal o invertida y ampliación de la representación.

## Estructura

```text
app/                                   Aplicación Android
app/src/main/java/com/splatt/elite/    Código Kotlin y Jetpack Compose
visor_movimiento_ble_raspberry.py      Software activo de Raspberry Pi
hardware/                              Diseños, documentación y prototipos históricos
manual_pruebas.md                      Guía de comprobaciones
```

## Aplicación Android

### Requisitos

- Android Studio o Gradle.
- JDK 17 o posterior.
- Android SDK configurado.
- Depuración USB para instalar directamente en la tablet.

### Compilar

En Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

### Instalar en un dispositivo conectado

```powershell
.\gradlew.bat installDebug
```

## Raspberry Pi

### Archivo activo

La Raspberry ejecuta:

```text
/home/pi/visor_movimiento_ble.py
```

Para actualizarlo desde Windows:

```powershell
scp .\visor_movimiento_ble_raspberry.py pi@IP_DE_LA_RASPBERRY:/home/pi/visor_movimiento_ble.py
```

### Validar sintaxis

En la Raspberry:

```bash
python3 -m py_compile /home/pi/visor_movimiento_ble.py
```

### Ejecutar

```bash
sudo python3 /home/pi/visor_movimiento_ble.py
```

El arranque mediante servicio de sistema queda pendiente. En el estado actual, los comandos `btmgmt` que publican el anuncio BLE necesitan la ejecución manual anterior.

## Uso básico

1. Conectar Raspberry, tablet y PC al mismo punto de acceso cuando se necesite SSH.
2. Iniciar el programa de la Raspberry.
3. Abrir Splatt Elite en la tablet.
4. Esperar a que BLE conecte automáticamente.
5. Usar `PANEL DE CÁMARA` para comprobar la imagen.
6. Configurar la distancia real a la diana.
7. Realizar las calibraciones y pruebas de pista.

La IP puede cambiar en cada conexión. La Raspberry la anuncia por BLE y la app actualiza el panel automáticamente.

## Calibraciones

- **AJUSTAR VISIÓN**: inicia en la Raspberry el barrido de exposición y ganancia y guarda la mejor combinación.
- **CENTRAR IMPACTOS**: registra varios disparos en Android y aplica su promedio como centro.
- **Pad direccional**: permite corregir manualmente el centro en pasos pequeños.

## Seguridad del trabajo

- La rama `main` del nuevo repositorio debe representar siempre un estado compilable.
- Las mejoras deben realizarse en ramas separadas y validarse antes de integrarlas.
- Antes de sustituir el software de la Raspberry debe conservarse una copia del archivo que está funcionando.

## Licencia

Proyecto privado. No se concede permiso de distribución mientras no se añada una licencia explícita.
