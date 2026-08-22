#!/usr/bin/env bash
set -euo pipefail

# Splatt Elite - instalador de recuperación para Raspberry Pi
# Referencia: Raspberry Pi Zero 2 W, Debian 13 (trixie), arm64.
# Ejecutar desde la raíz del repositorio:
#   sudo bash raspberry/install.sh

if [[ ${EUID} -ne 0 ]]; then
    echo "ERROR: ejecuta este script con sudo/root."
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_HOME="/home/pi"
CONFIG_TXT="/boot/firmware/config.txt"
SERVICE_DST="/etc/systemd/system/splatt.service"

log() { printf '[Splatt] %s\n' "$*"; }
warn() { printf '[AVISO] %s\n' "$*" >&2; }
fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

[[ -f "${SCRIPT_DIR}/visor_movimiento_ble.py" ]] || fail "Falta raspberry/visor_movimiento_ble.py"
[[ -f "${SCRIPT_DIR}/splatt_imu.py" ]] || fail "Falta raspberry/splatt_imu.py"
[[ -f "${SCRIPT_DIR}/bluez_gatt_server.py" ]] || fail "Falta raspberry/bluez_gatt_server.py"
[[ -f "${SCRIPT_DIR}/splatt.service" ]] || fail "Falta raspberry/splatt.service"
[[ -f "${SCRIPT_DIR}/splatt_config.json" ]] || fail "Falta raspberry/splatt_config.json"

if [[ -r /proc/device-tree/model ]]; then
    MODEL="$(tr -d '\0' </proc/device-tree/model)"
    log "Hardware detectado: ${MODEL}"
    if [[ "${MODEL}" != *"Raspberry Pi Zero 2 W"* ]]; then
        warn "La versión de referencia fue validada en Raspberry Pi Zero 2 W."
    fi
fi

if [[ -r /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    log "Sistema detectado: ${PRETTY_NAME:-desconocido}"
    if [[ "${VERSION_ID:-}" != "13" ]]; then
        warn "La versión de referencia fue validada en Debian 13 (trixie)."
    fi
fi

log "Actualizando índices APT..."
apt-get update

log "Instalando dependencias de sistema y Python..."
DEBIAN_FRONTEND=noninteractive apt-get install -y \
    bluez \
    git \
    i2c-tools \
    netcat-openbsd \
    network-manager \
    python3 \
    python3-dbus \
    python3-flask \
    python3-gi \
    python3-numpy \
    python3-opencv \
    python3-picamera2 \
    python3-smbus \
    rpicam-apps-core

log "Configurando hostname y zona horaria..."
hostnamectl set-hostname splatt
timedatectl set-timezone Europe/Madrid

[[ -f "${CONFIG_TXT}" ]] || fail "No existe ${CONFIG_TXT}; comprueba la instalación de Raspberry Pi OS/Debian."

ensure_line() {
    local line="$1"
    if ! grep -Fxq "$line" "${CONFIG_TXT}"; then
        printf '%s\n' "$line" >>"${CONFIG_TXT}"
        log "Añadido a config.txt: ${line}"
    else
        log "Ya presente en config.txt: ${line}"
    fi
}

log "Configurando overlays de cámara e I2C..."
ensure_line "dtparam=i2c_arm=on"
ensure_line "camera_auto_detect=0"
ensure_line "dtoverlay=ov9281"
ensure_line "dtoverlay=i2c-gpio,bus=3,i2c_gpio_sda=23,i2c_gpio_scl=24"

log "Instalando archivos Splatt en ${TARGET_HOME}..."
install -o pi -g pi -m 0664 "${SCRIPT_DIR}/visor_movimiento_ble.py" "${TARGET_HOME}/visor_movimiento_ble.py"
install -o pi -g pi -m 0664 "${SCRIPT_DIR}/splatt_imu.py" "${TARGET_HOME}/splatt_imu.py"
install -o root -g root -m 0644 "${SCRIPT_DIR}/splatt_config.json" "${TARGET_HOME}/splatt_config.json"

mkdir -p "${TARGET_HOME}/bluez-examples"
install -o pi -g pi -m 0664 "${SCRIPT_DIR}/bluez_gatt_server.py" "${TARGET_HOME}/bluez-examples/example-gatt-server"

log "Instalando servicio systemd..."
install -o root -g root -m 0644 "${SCRIPT_DIR}/splatt.service" "${SERVICE_DST}"
systemctl daemon-reload
systemctl enable bluetooth.service
systemctl enable NetworkManager.service
systemctl enable splatt.service

log "Comprobando PiSugar..."
PISUGAR_OK=1
for pkg in pisugar-server pisugar-poweroff pisugar-programmer; do
    if ! dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q 'install ok installed'; then
        PISUGAR_OK=0
        warn "No está instalado: ${pkg}"
    fi
done

if [[ ${PISUGAR_OK} -eq 0 ]]; then
    warn "PiSugar 3 v2.3.2-1 no está disponible actualmente en los repositorios APT de la unidad de referencia."
    warn "Instala los tres paquetes PiSugar 2.3.2-1 antes de considerar completa la recuperación."
else
    systemctl enable pisugar-server.service
fi

log "Validando sintaxis Python..."
python3 -m py_compile "${TARGET_HOME}/splatt_imu.py" "${TARGET_HOME}/visor_movimiento_ble.py"

log "La configuración de hardware requiere reinicio para que los overlays tengan efecto."
log "Después del reinicio ejecuta: sudo bash ${SCRIPT_DIR}/diagnostics.sh"

if [[ ${PISUGAR_OK} -eq 0 ]]; then
    echo
    warn "Instalación BASE completada, pero falta PiSugar. No se arranca splatt.service todavía."
    exit 2
fi

echo
log "Instalación base completada. Reinicia la Raspberry antes de arrancar/validar Splatt."
log "Comando recomendado: sudo reboot"
