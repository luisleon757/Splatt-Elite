#!/usr/bin/env bash
set -u

PASS=0
WARN=0
FAIL=0

ok() {
    printf '[OK]   %s\n' "$1"
    PASS=$((PASS + 1))
}

warn() {
    printf '[WARN] %s\n' "$1"
    WARN=$((WARN + 1))
}

fail() {
    printf '[FAIL] %s\n' "$1"
    FAIL=$((FAIL + 1))
}

have() {
    command -v "$1" >/dev/null 2>&1
}

section() {
    printf '\n==== %s ====\n' "$1"
}

section "Splatt Elite - diagnostico de recuperacion"
printf 'Fecha: %s\n' "$(date --iso-8601=seconds 2>/dev/null || date)"
printf 'Host: %s\n' "$(hostname 2>/dev/null || echo desconocido)"

section "Sistema"
if grep -q 'VERSION_ID="13"' /etc/os-release 2>/dev/null; then
    ok "Debian 13 detectado"
else
    warn "La version del sistema no coincide con Debian 13 de referencia"
fi

ARCH="$(uname -m 2>/dev/null || true)"
if [ "$ARCH" = "aarch64" ]; then
    ok "Arquitectura aarch64"
else
    fail "Arquitectura inesperada: ${ARCH:-desconocida}"
fi

MODEL="$(tr -d '\0' </proc/device-tree/model 2>/dev/null || true)"
if printf '%s' "$MODEL" | grep -q 'Raspberry Pi Zero 2 W'; then
    ok "Raspberry Pi Zero 2 W detectada"
else
    warn "Modelo distinto al de referencia: ${MODEL:-desconocido}"
fi

PYVER="$(python3 --version 2>&1 || true)"
if printf '%s' "$PYVER" | grep -q 'Python 3.13'; then
    ok "$PYVER"
else
    warn "Python distinto al de referencia: ${PYVER:-no disponible}"
fi

section "Archivos Splatt"
for f in \
    /home/pi/visor_movimiento_ble.py \
    /home/pi/splatt_imu.py \
    /home/pi/splatt_config.json \
    /home/pi/bluez-examples/example-gatt-server \
    /etc/systemd/system/splatt.service
 do
    if [ -f "$f" ]; then
        ok "Existe $f"
    else
        fail "Falta $f"
    fi
 done

if python3 -m json.tool /home/pi/splatt_config.json >/dev/null 2>&1; then
    ok "splatt_config.json es JSON valido"
else
    fail "splatt_config.json no es JSON valido"
fi

section "Dependencias Python"
python3 - <<'PY'
mods = [
    ("cv2", "OpenCV"),
    ("picamera2", "Picamera2"),
    ("flask", "Flask"),
    ("dbus", "D-Bus Python"),
    ("gi", "PyGObject"),
    ("numpy", "NumPy"),
    ("smbus", "SMBus"),
]
failed = []
for module, label in mods:
    try:
        __import__(module)
        print(f"[OKPY] {label}")
    except Exception as exc:
        failed.append((label, str(exc)))
        print(f"[FAILPY] {label}: {exc}")
raise SystemExit(1 if failed else 0)
PY
if [ $? -eq 0 ]; then
    ok "Imports Python principales"
else
    fail "Faltan una o mas dependencias Python"
fi

section "Configuracion de arranque"
CFG=/boot/firmware/config.txt
if grep -q '^dtparam=i2c_arm=on' "$CFG" 2>/dev/null; then
    ok "I2C principal habilitado"
else
    fail "Falta dtparam=i2c_arm=on"
fi
if grep -q '^dtoverlay=ov9281' "$CFG" 2>/dev/null; then
    ok "Overlay OV9281 configurado"
else
    fail "Falta dtoverlay=ov9281"
fi
if grep -q '^dtoverlay=i2c-gpio,bus=3,i2c_gpio_sda=23,i2c_gpio_scl=24' "$CFG" 2>/dev/null; then
    ok "Bus I2C 3 en GPIO23/GPIO24 configurado"
else
    fail "Falta overlay I2C bus 3 GPIO23/GPIO24"
fi

section "IMU"
if have i2cget; then
    WHO="$(i2cget -y 3 0x68 0x75 2>/dev/null || true)"
    if [ "$WHO" = "0x68" ]; then
        ok "IMU MPU-60x0/MPU6050 responde en bus 3, 0x68"
    else
        fail "IMU no responde como se esperaba (WHO_AM_I=${WHO:-sin respuesta})"
    fi
else
    fail "i2cget no esta instalado"
fi

section "Camara"
if have rpicam-hello; then
    CAMS="$(rpicam-hello --list-cameras 2>&1 || true)"
    if printf '%s' "$CAMS" | grep -qi 'ov9281'; then
        ok "Camara OV9281 detectada"
    else
        fail "OV9281 no detectada"
    fi
else
    fail "rpicam-hello no esta instalado"
fi

section "Servicios"
for svc in bluetooth.service pisugar-server.service splatt.service NetworkManager.service; do
    if systemctl is-enabled "$svc" >/dev/null 2>&1; then
        ok "$svc habilitado"
    else
        fail "$svc no esta habilitado"
    fi
    if systemctl is-active "$svc" >/dev/null 2>&1; then
        ok "$svc activo"
    else
        fail "$svc no esta activo"
    fi
done

section "PiSugar"
if [ -S /tmp/pisugar-server.sock ]; then
    ok "Socket PiSugar presente"
else
    fail "Falta /tmp/pisugar-server.sock"
fi

if have nc && [ -S /tmp/pisugar-server.sock ]; then
    BAT="$(printf 'get battery\n' | timeout 2s nc -U /tmp/pisugar-server.sock 2>/dev/null || true)"
    if printf '%s' "$BAT" | grep -q '^battery:'; then
        ok "PiSugar responde: $BAT"
    else
        fail "PiSugar no responde por UDS"
    fi
else
    warn "No se pudo probar PiSugar por UDS (falta nc o socket)"
fi

section "Bluetooth BLE"
if have bluetoothctl && bluetoothctl show 2>/dev/null | grep -q 'Powered: yes'; then
    ok "Bluetooth encendido"
else
    fail "Bluetooth no esta encendido"
fi

if have btmgmt; then
    ADV="$(btmgmt advinfo 2>/dev/null || true)"
    if printf '%s' "$ADV" | grep -Eq 'Instances list with [1-9]'; then
        ok "Existe al menos una instancia BLE anunciandose"
    else
        warn "No se detecta instancia BLE activa"
    fi
else
    warn "btmgmt no disponible"
fi

section "Puertos"
if have ss; then
    for port in 8000 8421 8422 8423; do
        if ss -lnt 2>/dev/null | grep -q ":${port} "; then
            ok "TCP $port escuchando"
        else
            fail "TCP $port no esta escuchando"
        fi
    done
else
    warn "ss no disponible; no se comprobaron puertos"
fi

section "Integridad de la version de referencia"
check_hash() {
    file="$1"
    expected="$2"
    if [ ! -f "$file" ]; then
        fail "No se puede verificar hash: falta $file"
        return
    fi
    actual="$(sha256sum "$file" | awk '{print $1}')"
    if [ "$actual" = "$expected" ]; then
        ok "SHA256 correcto: $file"
    else
        warn "SHA256 distinto: $file"
    fi
}

check_hash /home/pi/visor_movimiento_ble.py 1a4fb0a4316c3ccc32d54f8f4ba866d3dbecb44d12f433dc2fcdb1d75d444d31
check_hash /home/pi/splatt_imu.py 6dd6596f5acf028e0d4f3c8462cab3d9e9487144037336ce3de2f58767fa1f18
check_hash /home/pi/splatt_config.json c43c5b0dd777efa562c2d4f709ca79aa2e6cdb77af270147d8749aec39048413
check_hash /home/pi/bluez-examples/example-gatt-server a190508dd5bafd048ead4404535610c8c827181917cb569530f8de36ad329fd6

section "Resumen"
printf 'OK: %d  WARN: %d  FAIL: %d\n' "$PASS" "$WARN" "$FAIL"

if [ "$FAIL" -gt 0 ]; then
    printf 'RESULTADO: FALLO - revisar los puntos [FAIL].\n'
    exit 1
fi

if [ "$WARN" -gt 0 ]; then
    printf 'RESULTADO: OPERATIVO CON AVISOS.\n'
    exit 0
fi

printf 'RESULTADO: OK - sistema coherente con la referencia.\n'
exit 0
