#!/usr/bin/env bash
# Inicia la app en Linux:  ./iniciar.sh   (o: bash iniciar.sh)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

if ! command -v java >/dev/null 2>&1; then
  echo "Java no encontrado. Instala JDK 17+:" >&2
  echo "  sudo apt install openjdk-17-jdk    # Debian/Ubuntu" >&2
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "javac no encontrado. Instala el JDK completo (no solo JRE)." >&2
  exit 1
fi

"$ROOT/build.sh"

TMPDIR="${ROOT}/.tmp"
mkdir -p "${TMPDIR}"

if [[ ! -f "${ROOT}/libs/pdfbox-2.0.31.jar" ]] \
   || [[ ! -f "${ROOT}/libs/fontbox-2.0.31.jar" ]] \
   || [[ ! -f "${ROOT}/libs/commons-logging-1.2.jar" ]]; then
  echo "Bibliotecas PDF ausentes — tentando baixar ..."
  bash "${ROOT}/scripts/fetch-pdf-libs.sh"
fi

for jar in \
  "${ROOT}/libs/lib_reader.jar" \
  "${ROOT}/libs/lib_connect.jar" \
  "${ROOT}/libs/jSerialComm-2.11.4.jar" \
  "${ROOT}/libs/pdfbox-2.0.31.jar" \
  "${ROOT}/libs/fontbox-2.0.31.jar" \
  "${ROOT}/libs/commons-logging-1.2.jar"; do
  if [[ ! -f "$jar" ]]; then
    echo "ERRO: biblioteca ausente: $jar" >&2
    echo "Execute: bash scripts/fetch-pdf-libs.sh" >&2
    echo "Ou copie manualmente para libs/: pdfbox-2.0.31.jar, fontbox-2.0.31.jar, commons-logging-1.2.jar" >&2
    exit 1
  fi
done

CP="${ROOT}/libs/lib_reader.jar:${ROOT}/libs/lib_connect.jar:${ROOT}/libs/jSerialComm-2.11.4.jar"
CP="${CP}:${ROOT}/libs/pdfbox-2.0.31.jar:${ROOT}/libs/fontbox-2.0.31.jar:${ROOT}/libs/commons-logging-1.2.jar"
CP="${CP}:${ROOT}/SDKMERCURY/mercuryapi.jar:${ROOT}/SDKMERCURY/ltkjava-1.0.0.6.jar:${ROOT}/SDKMERCURY/slf4j-dependencies.jar"
CP="${CP}:${ROOT}/out"

JAVA_OPTS=(-Djava.io.tmpdir="${TMPDIR}" -Djava.library.path="${ROOT}/SDKMERCURY" -cp "$CP" com.peripheral.app.PeripheralApplication "$@")

# Java 22+ (opcional en versiones anteriores)
if java --help 2>&1 | grep -q 'enable-native-access'; then
  JAVA_OPTS=(--enable-native-access=ALL-UNNAMED "${JAVA_OPTS[@]}")
fi

# Reset seletivo de USB-serial (RFID/balança) antes do Java — evita cabo "morto"
# quando o periférico ligou antes do Raspberry no mesmo filtro de energia.
if [[ "$(uname -s)" == "Linux" ]]; then
  echo "Reescaneando conversores USB-serial (RFID/balança)..."
  bash "${ROOT}/scripts/usb-serial-reset.sh" || true
  export RFIDSDK_USB_WARMUP_DONE=1
fi

# Dependências do fallback IA (ONNX) — instalação leve/idempotente
if command -v python3 >/dev/null 2>&1 && [[ -f "${ROOT}/camera-service/requirements.txt" ]]; then
  if ! python3 -c "import onnxruntime, PIL, numpy, fastapi, uvicorn" >/dev/null 2>&1; then
    echo "Instalando dependências do camera-service (onnxruntime/Pillow/...) ..."
    python3 -m pip install --user -q -r "${ROOT}/camera-service/requirements.txt" || true
  fi
fi
export CAMERA_MODEL_DIR="${ROOT}/camera-service/modelCamera"

echo "Iniciando Periféricos eship ..."
echo "  O microserviço Python da câmera carrega o modelo IMX500 em modelCamera/ no boot."
if [[ "$(uname -m)" == "aarch64" ]]; then
  echo "  ARM64: Mercury RFID usa jSerialComm (JNI ThingMagic não suportado nesta arquitetura)."
fi
exec java "${JAVA_OPTS[@]}"
