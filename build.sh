#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

OUT=out
SRC=src
LIBS="libs/lib_reader.jar:libs/lib_connect.jar:libs/jSerialComm-2.11.4.jar"
PDFBOX="libs/pdfbox-2.0.31.jar:libs/fontbox-2.0.31.jar:libs/commons-logging-1.2.jar"
MERCURY="SDKMERCURY/mercuryapi.jar:SDKMERCURY/ltkjava-1.0.0.6.jar:SDKMERCURY/slf4j-dependencies.jar"
CP="${LIBS}:${PDFBOX}:${MERCURY}"

mkdir -p "${OUT}/resources"
cp -f "${SRC}/resources/images.png" "${OUT}/resources/images.png" 2>/dev/null || true

mapfile -t SOURCES < <(find "${SRC}" -name '*.java' ! -path '*/payne/test/Test.java' | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "ERRO: nenhum arquivo .java encontrado em ${SRC}" >&2
  exit 1
fi

echo "Compilando projeto em ${OUT} ..."
javac -encoding UTF-8 -d "${OUT}" -cp "${CP}" -sourcepath "${SRC}" "${SOURCES[@]}"
echo "Compilacao concluida."
