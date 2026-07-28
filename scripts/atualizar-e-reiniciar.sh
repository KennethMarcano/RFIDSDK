#!/usr/bin/env bash
# Atualiza o código do remoto, recompila e agenda o reinício da app.
# Uso: bash scripts/atualizar-e-reiniciar.sh
# Chamado pelo botão "Atualizar" da UI — não precisa instalar systemd.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> Atualizando RFIDSDK em ${ROOT}"

if ! command -v git >/dev/null 2>&1; then
  echo "ERRO: git não encontrado." >&2
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERRO: este diretório não é um repositório git." >&2
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "ERRO: javac não encontrado (JDK necessário para recompilar)." >&2
  exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "==> Branch: ${BRANCH}"
echo "==> Baixando atualizações (git fetch)..."
git fetch --prune origin

REMOTE_REF="origin/${BRANCH}"
if ! git rev-parse --verify "${REMOTE_REF}" >/dev/null 2>&1; then
  echo "ERRO: remoto ${REMOTE_REF} não encontrado. Configure o remote origin." >&2
  exit 1
fi

LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse "${REMOTE_REF}")"
if [[ "${LOCAL}" == "${REMOTE}" ]]; then
  echo "==> Já está atualizado (${LOCAL:0:8}). Reiniciando mesmo assim para aplicar build limpo."
else
  echo "==> Atualizando ${LOCAL:0:8} → ${REMOTE:0:8}"
fi

# Alinha com o remoto (evita conflito no dispositivo de campo).
git reset --hard "${REMOTE_REF}"

echo "==> Compilando..."
bash "${ROOT}/build.sh"

LOG="/tmp/rfidsdk-relaunch.log"
echo "==> Agendando reinício em 2s (log: ${LOG})"
# Desanexa do processo Java atual; sobe a app de novo após o exit.
nohup bash -c "sleep 2; cd '${ROOT}' && exec ./iniciar.sh" >"${LOG}" 2>&1 &
disown || true

echo "==> OK — a aplicação vai fechar e reabrir sozinha."
exit 0
