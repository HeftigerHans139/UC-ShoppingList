#!/usr/bin/env bash
set -euo pipefail

APP_NAME="uc-shoppinglist"
APP_USER="ucshoppinglist"
APP_GROUP="ucshoppinglist"
APP_VERSION="1.0.5"
INSTALL_ROOT="/opt/${APP_NAME}"
REPO_DIR="${INSTALL_ROOT}/repo"
SERVER_SRC_DIR="${INSTALL_ROOT}/server"
ENV_FILE="/etc/default/${APP_NAME}"

REPO_URL="${REPO_URL:-}"
REPO_BRANCH="${REPO_BRANCH:-}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LOCAL_SERVER_DIR="${PROJECT_ROOT}/server"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck source=/dev/null
  . "${ENV_FILE}"
fi

REPO_URL="${REPO_URL:-${1:-}}"
REPO_BRANCH="${REPO_BRANCH:-main}"

if [[ -z "${REPO_URL}" ]]; then
  echo "Fehler: Keine GitHub-Quelle konfiguriert."
  echo "Bitte zuerst install-ubuntu.sh mit REPO_URL='https://github.com/HeftigerHans139/UC-ShoppingList.git' ausfuehren oder REPO_URL als Parameter setzen."
  exit 1
fi

if [[ "${EUID}" -ne 0 ]]; then
  echo "Bitte mit sudo oder als root ausfuehren."
  exit 1
fi

if [[ ! -d "${SERVER_SRC_DIR}" ]]; then
  echo "Fehler: ${SERVER_SRC_DIR} existiert nicht. Zuerst install-ubuntu.sh ausfuehren."
  exit 1
fi

echo "[1/4] Neueste Version von GitHub holen..."
if [[ -d "${REPO_DIR}/.git" ]]; then
  git -C "${REPO_DIR}" remote set-url origin "${REPO_URL}"
  git -C "${REPO_DIR}" fetch --prune origin
  git -C "${REPO_DIR}" checkout -f "${REPO_BRANCH}"
  git -C "${REPO_DIR}" reset --hard "origin/${REPO_BRANCH}"
else
  rm -rf "${REPO_DIR}"
  git clone --depth 1 --branch "${REPO_BRANCH}" "${REPO_URL}" "${REPO_DIR}"
fi

rsync -a --delete \
  --exclude node_modules \
  --exclude data \
  "${REPO_DIR}/server/" "${SERVER_SRC_DIR}/"

echo "[2/4] Abhaengigkeiten aktualisieren..."
cd "${SERVER_SRC_DIR}"
if [[ -f package-lock.json ]]; then
  npm ci --omit=dev --ignore-scripts
else
  npm install --omit=dev --ignore-scripts
fi

echo "[3/4] Rechte korrigieren..."
chown -R "${APP_USER}:${APP_GROUP}" "${INSTALL_ROOT}"

echo "[4/4] Dienst neu starten..."
systemctl restart "${APP_NAME}.service"
systemctl --no-pager --full status "${APP_NAME}.service" | sed -n '1,20p'

echo ""
echo "Update abgeschlossen (v${APP_VERSION})."
echo "Features: Einkauf, Kochen, Planung (neu), Notizen"
echo "Hinweis: Datenbank wird automatisch migriert."
