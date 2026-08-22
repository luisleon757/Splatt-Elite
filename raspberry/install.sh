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
PISUGAR_DIR="${SCRIPT_DIR}/packages/pisugar"
PISUGAR_VERSION="2.3.2-1"
PISUGAR_ARCH="arm64"

log() { printf '[Splatt] %s\n' "$*"; }
warn() { printf '[AVISO] %s\n' "$*" >&2; }
fail() { printf '[ERROR] %s\n' "$*" >&2; exit 1; }

[[ -f "${SCRIPT_DIR}/visor_movimiento_ble.py" ]] || fail "Falta raspberry/visor_movimiento_ble.py"
[[ -f "${SCRIPT_DIR}/splatt_imu.py" ]] || fail "Falta raspberry/splatt_imu.py"
[[ -f "${SCRIPT_DIR}/bluez_gatt_server.py" ]] || fail "Falta raspberry/bluez_gatt_server.py"
[[ -f "${SCRIPT_DIR}/splatt.service" ]] || fail "Falta raspberry/splatt.service"
[[ -f "${SCRIPT_DIR}/splatt_config.json" ]] || fail "Falta raspberry/splatt_config.json"
[[ -f "${PISUGAR_DIR}/SHA256SUMS" ]] || fail "Falta raspberry/packages/pisugar/SHA256SUMS"
[[ -f "${PISUGAR_DIR}/pisugar-poweroff_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb" ]] || fail "Falta paquete pisugar-poweroff"
[[ -f "${PISUGAR_DIR}/pisugar-programmer_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb" ]] || fail "Falta paquete pisugar-programmer"
[[ -f "${PISUGAR_DIR}/pisugar-server_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb" ]] || fail "Falta paquete pisugar-server"

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

ARCH="$(dpkg --print-architecture 2>/dev/null || true)"
if [[ "${ARCH}" != "${PISUGAR_ARCH}" ]]; then
    fail "Arquitectura Debian ${ARCH:-desconocida}; la recuperación validada requiere ${PISUGAR_ARCH}."
fi
log "Arquitectura Debian: ${ARCH}"

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

log "Verificando integridad de PiSugar ${PISUGAR_VERSION}..."
(
    cd "${PISUGAR_DIR}"
    sha256sum -c SHA256SUMS
) || fail "Fallo de integridad en los paquetes PiSugar."

PISUGAR_EXACT=1
for pkg in pisugar-server pisugar-poweroff pisugar-programmer; do
    installed="$(dpkg-query -W -f='${Version}' "${pkg}" 2>/dev/null || true)"
    if [[ "${installed}" = "${PISUGAR_VERSION}" ]]; then
        log "${pkg} ya instalado: ${installed}"
    else
        PISUGAR_EXACT=0
        if [[ -n "${installed}" ]]; then
            log "${pkg}: instalada ${installed}; se fijará ${PISUGAR_VERSION}."
        else
            log "${pkg}: no instalado; se instalará ${PISUGAR_VERSION}."
        fi
    fi
done

if [[ ${PISUGAR_EXACT} -eq 0 ]]; then
    log "Instalando PiSugar ${PISUGAR_VERSION} desde el paquete de recuperación..."
    DEBIAN_FRONTEND=noninteractive apt-get install -y \
        "${PISUGAR_DIR}/pisugar-poweroff_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb" \
        "${PISUGAR_DIR}/pisugar-programmer_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb" \
        "${PISUGAR_DIR}/pisugar-server_${PISUGAR_VERSION}_${PISUGAR_ARCH}.deb"
fi

for pkg in pisugar-server pisugar-poweroff pisugar-programmer; do
    installed="$(dpkg-query -W -f='${Version}' "${pkg}" 2>/dev/null || true)"
    [[ "${installed}" = "${PISUGAR_VERSION}" ]] || fail "${pkg} no quedó en ${PISUGAR_VERSION} (actual: ${installed:-no instalado})."
done
log "PiSugar ${PISUGAR_VERSION} verificado."

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
systemctl enable pisugar-server.service
systemctl enable splatt.service

log "Validando sintaxis Python..."
python3 -m py_compile "${TARGET_HOME}/splatt_imu.py" "${TARGET_HOME}/visor_movimiento_ble.py"

log "La configuración de hardware requiere reinicio para que los overlays tengan efecto."
log "Después del reinicio ejecuta: sudo bash ${SCRIPT_DIR}/diagnostics.sh"

echo
log "Instalación de recuperación completada. Reinicia la Raspberry antes de arrancar/validar Splatt."
log "Comando recomendado: sudo reboot"
