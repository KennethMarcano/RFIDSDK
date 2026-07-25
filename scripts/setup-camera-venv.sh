#!/usr/bin/env bash
# Cria/atualiza o venv do camera-service (evita erro externally-managed-environment).
# Uso: bash scripts/setup-camera-venv.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SVC="${ROOT}/camera-service"
VENV="${SVC}/.venv"
REQ="${SVC}/requirements.txt"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERRO: python3 não encontrado. Instale: sudo apt install python3 python3-venv python3-pip" >&2
  exit 1
fi

if [[ ! -f "$REQ" ]]; then
  echo "ERRO: requirements não encontrado: $REQ" >&2
  exit 1
fi

# python3-venv é obrigatório no Debian/Raspberry Pi OS Bookworm+
if ! python3 -c "import venv" >/dev/null 2>&1; then
  echo "Módulo venv ausente. Instale e rode de novo:"
  echo "  sudo apt update && sudo apt install -y python3-venv python3-pip"
  exit 1
fi

if [[ ! -x "${VENV}/bin/python" && ! -x "${VENV}/bin/python3" ]]; then
  echo "Criando ambiente virtual em ${VENV} ..."
  python3 -m venv "${VENV}"
fi

PY="${VENV}/bin/python3"
if [[ ! -x "$PY" ]]; then
  PY="${VENV}/bin/python"
fi

echo "Atualizando pip no venv ..."
"$PY" -m pip install --upgrade pip setuptools wheel

echo "Instalando dependências do camera-service ..."
"$PY" -m pip install -r "$REQ"

echo
echo "OK. Python do serviço: $PY"
"$PY" -c "import onnxruntime, PIL, numpy, fastapi, uvicorn; print('imports OK')"
echo
echo "A app Java / iniciar.sh usará este venv automaticamente."
