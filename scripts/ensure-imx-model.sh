#!/usr/bin/env bash
# Empacota packerOut.zip -> network.rpk (Sony IMX500) e valida artefatos.
# O model_imx.onnx do conversor NÃO roda no ONNX Runtime (ops mct_quantizers).
# A IA usa o RPK no chip da câmera.
# Uso: bash scripts/ensure-imx-model.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL_DIR="${CAMERA_MODEL_DIR:-$ROOT/camera-service/modelCamera}"
PACKER="$MODEL_DIR/packerOut.zip"
RPK="$MODEL_DIR/network.rpk"
OUT="$MODEL_DIR/packaged"

echo "modelCamera: $MODEL_DIR"
for f in labels.txt packerOut.zip; do
  if [[ ! -f "$MODEL_DIR/$f" ]]; then
    echo "ERRO: faltando $MODEL_DIR/$f" >&2
    exit 1
  fi
  echo "  ok $f"
done
if [[ -f "$MODEL_DIR/model_imx.onnx" ]]; then
  echo "  ok model_imx.onnx (só conversão Sony — não é usado pelo ORT)"
fi

if [[ -f "$RPK" && -s "$RPK" ]]; then
  echo "RPK já existe: $RPK ($(du -h "$RPK" | cut -f1))"
  exit 0
fi

if ! command -v imx500-package >/dev/null 2>&1; then
  echo "ERRO: imx500-package não encontrado." >&2
  echo "  Instale no Raspberry Pi:" >&2
  echo "    sudo apt update" >&2
  echo "    sudo apt install -y imx500-tools imx500-all python3-picamera2" >&2
  echo "  Depois rode de novo: bash scripts/ensure-imx-model.sh" >&2
  exit 1
fi

mkdir -p "$OUT"
echo "Empacotando RPK com imx500-package (pode demorar) ..."
imx500-package -i "$PACKER" -o "$OUT"
FOUND="$(find "$OUT" -name '*.rpk' | head -n 1 || true)"
if [[ -z "$FOUND" ]]; then
  echo "ERRO: nenhum .rpk gerado em $OUT" >&2
  exit 1
fi
cp -f "$FOUND" "$RPK"
echo "RPK pronto: $RPK"
echo "Reinicie a app (./iniciar.sh). Backend esperado: imx500_rpk"
