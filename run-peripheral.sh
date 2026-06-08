#!/usr/bin/env bash
# Alias de compatibilidad — usa iniciar.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/iniciar.sh" "$@"
