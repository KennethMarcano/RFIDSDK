#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/resources
cp -f src/resources/images.png out/resources/images.png 2>/dev/null || true
CP="libs/lib_reader.jar:libs/lib_connect.jar:libs/jSerialComm-2.11.4.jar:SDKMERCURY/mercuryapi.jar:SDKMERCURY/ltkjava-1.0.0.6.jar:SDKMERCURY/slf4j-dependencies.jar:out"
exec java --enable-native-access=ALL-UNNAMED -Djava.library.path="$PWD/SDKMERCURY" -cp "$CP" com.peripheral.app.PeripheralApplication "$@"
