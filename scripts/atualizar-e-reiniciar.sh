#!/usr/bin/env bash
# Atualiza o código do remoto, recompila e agenda o reinício da app.
# Uso: bash scripts/atualizar-e-reiniciar.sh
# Chamado pelo botão "Atualizar" da UI — não precisa instalar systemd.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! command -v git >/dev/null 2>&1; then
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
git fetch --prune origin >/dev/null 2>&1

REMOTE_REF="origin/${BRANCH}"
if ! git rev-parse --verify "${REMOTE_REF}" >/dev/null 2>&1; then
  exit 1
fi

LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse "${REMOTE_REF}")"
if [[ "${LOCAL}" == "${REMOTE}" ]]; then
  exit 2
fi

git reset --hard "${REMOTE_REF}" >/dev/null 2>&1
bash "${ROOT}/build.sh" >/dev/null 2>&1

LOG="/tmp/rfidsdk-relaunch.log"
nohup bash -c "sleep 2; cd '${ROOT}' && exec ./iniciar.sh" >"${LOG}" 2>&1 &
disown || true
exit 0
