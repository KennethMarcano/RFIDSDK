#!/usr/bin/env bash
# Baixa pdfbox, fontbox e commons-logging para libs/ (necessário para etiquetas PDF).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIBS="${ROOT}/libs"
mkdir -p "${LIBS}"

MAVEN="https://repo1.maven.org/maven2"

download() {
  local url="$1"
  local dest="$2"
  local name
  name="$(basename "${dest}")"

  if [[ -f "${dest}" ]] && [[ -s "${dest}" ]]; then
    echo "  OK  ${name}"
    return 0
  fi

  echo "  GET ${name} ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "${dest}" "${url}"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "${dest}" "${url}"
  else
    echo "ERRO: instale curl ou wget para baixar ${name}" >&2
    return 1
  fi

  if [[ ! -s "${dest}" ]]; then
    echo "ERRO: download falhou para ${name}" >&2
    rm -f "${dest}"
    return 1
  fi
}

echo "Verificando bibliotecas PDF em ${LIBS} ..."

download "${MAVEN}/org/apache/pdfbox/pdfbox/2.0.31/pdfbox-2.0.31.jar" \
         "${LIBS}/pdfbox-2.0.31.jar"

download "${MAVEN}/org/apache/pdfbox/fontbox/2.0.31/fontbox-2.0.31.jar" \
         "${LIBS}/fontbox-2.0.31.jar"

download "${MAVEN}/commons-logging/commons-logging/1.2/commons-logging-1.2.jar" \
         "${LIBS}/commons-logging-1.2.jar"

echo "Bibliotecas PDF prontas."
