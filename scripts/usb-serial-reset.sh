#!/usr/bin/env bash
# Reset seletivo de conversores USB-serial (RFID/balança) no Linux.
# NÃO desliga touch, teclado, mouse nem storage — na prática, em alguns hubs
# USB o reset ainda derrubava o touch da tela 7". Por isso NÃO é mais chamado
# automaticamente (iniciar.sh / Java / systemd). Use só manualmente se a porta
# serial do RFID/balança ficar morta após reboot a quente.
#
# Faz um reset REAL (equivalente a desconectar/reconectar o cabo), pois num
# reboot "a quente" o VBUS dos USB do Raspberry não é cortado e o chip
# CH340/FTDI fica no mesmo estado ruim com que ligou. Ordem por dispositivo:
#   1) USBDEVFS_RESET (ioctl, = usbreset) -> reset de barramento real
#   2) unbind/bind do driver              -> re-probe do driver serial
#   3) authorized 0/1                     -> fallback
#
# Uso: bash scripts/usb-serial-reset.sh   (idealmente como root)
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "usb-serial-reset: ignorado (não é Linux)."
  exit 0
fi

SYS_USB="/sys/bus/usb/devices"
if [[ ! -d "$SYS_USB" ]]; then
  echo "usb-serial-reset: sysfs USB indisponível."
  exit 0
fi

# VIDs conhecidos de USB-UART (minúsculos, 4 hex)
SERIAL_VIDS_REGEX='^(0403|10c4|1a86|067b|04d8|2341|2a03|26ac|0fe6|0557|9710|06cd|04b4|1d50|1b4f)$'

HAVE_ROOT=0
[[ "$(id -u)" -eq 0 ]] && HAVE_ROOT=1

SUDO=""
if [[ "$HAVE_ROOT" -eq 0 ]] && command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  SUDO="sudo -n"
fi

# Escreve num arquivo de sysfs com/sem sudo. Retorna 0 se conseguiu.
write_sysfs() {
  local file="$1"
  local value="$2"
  if [[ -w "$file" ]]; then
    printf '%s\n' "$value" > "$file" 2>/dev/null && return 0
  fi
  if [[ -n "$SUDO" ]]; then
    printf '%s\n' "$value" | $SUDO tee "$file" >/dev/null 2>&1 && return 0
  fi
  return 1
}

# Reset de barramento real via USBDEVFS_RESET (ioctl 0x5514). Requer python3.
usbfs_reset() {
  local device="$1"
  local busnum devnum node
  busnum="$(cat "$device/busnum" 2>/dev/null || true)"
  devnum="$(cat "$device/devnum" 2>/dev/null || true)"
  [[ -n "$busnum" && -n "$devnum" ]] || return 1
  node="$(printf '/dev/bus/usb/%03d/%03d' "$busnum" "$devnum")"
  [[ -e "$node" ]] || return 1
  command -v python3 >/dev/null 2>&1 || return 1

  local runner="python3"
  # Se não temos acesso de escrita ao node e há sudo, usa sudo.
  if [[ ! -w "$node" && -n "$SUDO" ]]; then
    runner="$SUDO python3"
  fi
  $runner - "$node" <<'PY' 2>/dev/null
import fcntl, sys
USBDEVFS_RESET = 0x5514
try:
    with open(sys.argv[1], 'wb') as fd:
        fcntl.ioctl(fd, USBDEVFS_RESET, 0)
except Exception:
    sys.exit(1)
PY
}

# unbind + bind no driver 'usb' força re-probe/re-enumeração do dispositivo.
rebind_driver() {
  local name="$1"
  local drv="/sys/bus/usb/drivers/usb"
  [[ -d "$drv" ]] || return 1
  write_sysfs "$drv/unbind" "$name" || return 1
  sleep 0.4
  write_sysfs "$drv/bind" "$name" || return 1
  return 0
}

authorized_toggle() {
  local device="$1"
  [[ -f "$device/authorized" ]] || return 1
  write_sysfs "$device/authorized" "0" || return 1
  sleep 0.6
  write_sysfs "$device/authorized" "1" || return 1
  return 0
}

reset_one() {
  local device="$1"
  local name="$2"
  local ok=1
  if usbfs_reset "$device"; then
    ok=0
  fi
  if rebind_driver "$name"; then
    ok=0
  fi
  if authorized_toggle "$device"; then
    ok=0
  fi
  return $ok
}

reset_count=0
fail_count=0

shopt -s nullglob
for device in "$SYS_USB"/*; do
  [[ -d "$device" ]] || continue
  name="$(basename "$device")"
  # Só nós de dispositivo (1-1.2), não interfaces (1-1.2:1.0)
  [[ "$name" == *:* ]] && continue
  [[ -f "$device/idVendor" ]] || continue

  vid="$(tr '[:upper:]' '[:lower:]' < "$device/idVendor" | tr -d '[:space:]')"
  [[ "$vid" =~ $SERIAL_VIDS_REGEX ]] || continue

  pid="$(tr -d '[:space:]' < "$device/idProduct" 2>/dev/null || echo "?")"
  product=""
  if [[ -f "$device/product" ]]; then
    product="$(tr -d '\n' < "$device/product" || true)"
  fi

  echo "Reset USB-serial: $name  ${vid}:${pid}  ${product}"
  if reset_one "$device" "$name"; then
    reset_count=$((reset_count + 1))
  else
    echo "  aviso: nenhum método de reset teve permissão para $name" >&2
    if [[ "$HAVE_ROOT" -eq 0 && -z "$SUDO" ]]; then
      echo "  dica: rode como root (systemd/sudo). Escrever no USB exige root." >&2
    fi
    fail_count=$((fail_count + 1))
  fi
done

if [[ "$reset_count" -eq 0 && "$fail_count" -eq 0 ]]; then
  echo "usb-serial-reset: nenhum conversor USB-serial conhecido conectado."
else
  echo "usb-serial-reset: $reset_count resetado(s), $fail_count falha(s)."
  # Espera a reenumeração e o driver criar /dev/ttyUSB* ou /dev/ttyACM*.
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if compgen -G "/dev/ttyUSB*" >/dev/null 2>&1 || compgen -G "/dev/ttyACM*" >/dev/null 2>&1; then
      break
    fi
    sleep 0.3
  done
  sleep 0.8
fi

exit 0
