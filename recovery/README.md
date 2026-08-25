# Splatt Elite — Recuperación

Esta carpeta contiene archivos de referencia destinados a recuperar una versión funcional conocida de Splatt Elite.

## Android

APK de referencia:
`android/splatt-elite-reference-debug.apk`
SHA-256:
`5960722F38501B70C8FB5F6862EA9072C5289CDC5674653BD08744FDC55C5C65`
El mismo hash está guardado en:
`android/SHA256SUMS`

## Verificación en Windows
Desde la raíz del repositorio:
```powershell
(Get-FileHash recovery\android\splatt-elite-reference-debug.apk -Algorithm SHA256).Hash
```
El resultado debe ser exactamente:
```text
5960722F38501B70C8FB5F6862EA9072C5289CDC5674653BD08744FDC55C5C65
```

## Estado de referencia
* Rama de origen: `trazas-3-fases`
* Commit que incorpora el APK de recuperación: `9168bd3`
* Fecha de incorporación: `23/08/2026`

Este APK debe conservarse como referencia estable y no sustituirse sin crear previamente una nueva copia de recuperación verificable.


## Recuperación completa de Splatt Elite

El punto de recuperación completo está identificado por el tag:

`recovery-complete-2026-08-23`

Este tag permite recuperar exactamente el código fuente, la configuración de Raspberry Pi y el APK Android de referencia.

### 1. Recuperar el repositorio

En una instalación limpia de Raspberry Pi OS Lite, `git` puede no estar instalado. Si el comando `git` no existe, instalar únicamente este requisito previo:

```bash
sudo apt update
sudo apt install -y git
```

Después recuperar el repositorio:

```bash
git clone https://github.com/luisleon757/Splatt-Elite.git
cd Splatt-Elite
git checkout recovery-complete-2026-08-23
```

### 2. Recuperar la Raspberry Pi

La versión de referencia está preparada para:

* Raspberry Pi Zero 2 W
* Debian 13 (Trixie)
* Arquitectura arm64
* PiSugar 2.3.2-1

Desde la raíz del repositorio:

```bash
sudo bash raspberry/install.sh
```

Cuando termine:

```bash
sudo reboot
```

Después del reinicio:

```bash
sudo bash raspberry/diagnostics.sh
```

La configuración de cámara de referencia incluida en el repositorio es:

```json
{
  "exposure_us": 800,
  "analogue_gain": 2.5
}
```

### 3. Recuperar Android

APK de referencia:

`recovery/android/splatt-elite-reference-debug.apk`

SHA-256 esperado:

`5960722F38501B70C8FB5F6862EA9072C5289CDC5674653BD08744FDC55C5C65`

En Windows se puede verificar con:

```powershell
(Get-FileHash recovery\android\splatt-elite-reference-debug.apk -Algorithm SHA256).Hash
```

El hash obtenido debe coincidir exactamente con el indicado anteriormente.

### 4. Referencias Git

* Tag de recuperación completa: `recovery-complete-2026-08-23`
* Tag Android original: `recovery-android-2026-08-23`
* Commit que incorporó el APK: `9168bd3`
* Fecha: `23/08/2026`
No modificar ni mover este tag. Para futuras versiones estables se deberá crear un nuevo punto de recuperación con un tag diferente.

