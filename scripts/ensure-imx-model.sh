#!/usr/bin/env bash
# Empacota packerOut.zip -> network.rpk (Sony IMX500) e valida artefatos.
# Uso: bash scripts/ensure-imx-model.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_DIR="${CAMERA_MODEL_DIR:-$ROOT/camera-service/modelCamera}"
PACKER="$MODEL_DIR/packerOut.zip"
RPK="$MODEL_DIR/network.rpk"
OUT="$MODEL_DIR/packaged"

echo "modelCamera: $MODEL_DIR"
for f in labels.txt model_imx.onnx dnnParams.xml packerOut.zip; do
  if [[ ! -f "$MODEL_DIR/$f" ]]; then
    echo "ERRO: faltando $MODEL_DIR/$f" >&2
    exit 1
  fi
  echo "  ok $f"
done

if [[ -f "$RPK" && -s "$RPK" ]]; then
  echo "RPK já existe: $RPK"
  exit 0
fi

if ! command -v imx500-package >/dev/null 2>&1; then
  echo "Aviso: imx500-package não encontrado."
  echo "  No Raspberry Pi OS: sudo apt update && sudo apt install imx500-tools"
  echo "  O fallback IA via ONNX continua funcionando sem o RPK."
  exit 0
fi

mkdir -p "$OUT"
echo "Empacotando RPK com imx500-package ..."
imx500-package -i "$PACKER" -o "$OUT"
FOUND="$(find "$OUT" -name '*.rpk' | head -n 1 || true)"
if [[ -z "$FOUND" ]]; then
  echo "ERRO: nenhum .rpk gerado em $OUT" >&2
  exit 1
fi
cp -f "$FOUND" "$RPK"
echo "RPK pronto: $RPK"
