#!/usr/bin/env bash
# Alias de compatibilidad — usa start.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
exec "$ROOT/start.sh" "$@"
