#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

CP="libs/lib_reader.jar:libs/lib_connect.jar:libs/jSerialComm-2.11.4.jar:SDKMERCURY/mercuryapi.jar:SDKMERCURY/ltkjava-1.0.0.6.jar:SDKMERCURY/slf4j-dependencies.jar:out"
echo "Iniciando Periféricos eship (RFID + Balança + Fluxo) ..."
exec java --enable-native-access=ALL-UNNAMED -Djava.library.path="$PWD/SDKMERCURY" -cp "$CP" com.peripheral.app.PeripheralApplication "$@"
