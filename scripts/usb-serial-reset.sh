#!/usr/bin/env bash
# Reset seletivo de conversores USB-serial (RFID/balança) no Linux.
# NÃO desliga touch, teclado, mouse nem storage.
# Uso: bash scripts/usb-serial-reset.sh
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

write_auth() {
  local file="$1"
  local value="$2"
  if [[ -w "$file" ]]; then
    printf '%s\n' "$value" > "$file"
    return 0
  fi
  if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
    printf '%s\n' "$value" | sudo -n tee "$file" >/dev/null
    return 0
  fi
  return 1
}

reset_count=0
fail_count=0

shopt -s nullglob
for device in "$SYS_USB"/*; do
  [[ -d "$device" ]] || continue
  name="$(basename "$device")"
  # Só nós de dispositivo (1-1.2), não interfaces (1-1.2:1.0)
  [[ "$name" == *:* ]] && continue
  [[ -f "$device/idVendor" && -f "$device/authorized" ]] || continue

  vid="$(tr '[:upper:]' '[:lower:]' < "$device/idVendor" | tr -d '[:space:]')"
  [[ "$vid" =~ $SERIAL_VIDS_REGEX ]] || continue

  pid="$(tr -d '[:space:]' < "$device/idProduct" 2>/dev/null || echo "?")"
  product=""
  if [[ -f "$device/product" ]]; then
    product="$(tr -d '\n' < "$device/product" || true)"
  fi

  echo "Reset USB-serial: $name  ${vid}:${pid}  ${product}"
  if write_auth "$device/authorized" "0"; then
    sleep 0.7
    if write_auth "$device/authorized" "1"; then
      reset_count=$((reset_count + 1))
    else
      echo "  aviso: não foi possível reautorizar $name" >&2
      fail_count=$((fail_count + 1))
    fi
  else
    echo "  aviso: sem permissão em $device/authorized" >&2
    echo "  dica: configure sudoers NOPASSWD para este script ou rode com sudo." >&2
    fail_count=$((fail_count + 1))
  fi
done

if [[ "$reset_count" -eq 0 && "$fail_count" -eq 0 ]]; then
  echo "usb-serial-reset: nenhum conversor USB-serial conhecido conectado."
else
  echo "usb-serial-reset: $reset_count resetado(s), $fail_count falha(s)."
  sleep 1.5
fi

exit 0
