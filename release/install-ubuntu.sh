#!/usr/bin/env bash
set -euo pipefail


APP_NAME="uc-shoppinglist"
APP_USER="ucshoppinglist"
APP_GROUP="ucshoppinglist"
APP_VERSION="1.0.5"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_ROOT="/opt/${APP_NAME}"
REPO_DIR="${INSTALL_ROOT}/repo"
SERVER_SRC_DIR="${INSTALL_ROOT}/server"
DATA_DIR="${INSTALL_ROOT}/data"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"
ENV_FILE="/etc/default/${APP_NAME}"
REPO_URL="${REPO_URL:-https://github.com/HeftigerHans139/UC-ShoppingList.git}"
REPO_BRANCH="${REPO_BRANCH:-main}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Bitte mit sudo oder als root ausfuehren."
  exit 1
fi

if [[ -f /etc/os-release ]]; then
  . /etc/os-release
  if [[ "${ID:-}" != "ubuntu" ]]; then
    echo "Warnung: Dieses Script ist auf Ubuntu ausgelegt. Fortfahren auf eigenes Risiko."
  fi
fi

echo "[1/8] Systempakete installieren..."
apt-get update -y
apt-get install -y curl ca-certificates gnupg lsb-release rsync git

echo "[2/8] Node.js 20 sicherstellen..."
NEED_NODE=1
if command -v node >/dev/null 2>&1; then
  NODE_MAJOR="$(node -v | sed 's/^v//' | cut -d. -f1)"
  if [[ "${NODE_MAJOR}" -ge 20 ]]; then
    NEED_NODE=0
  fi
fi

if [[ "${NEED_NODE}" -eq 1 ]]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi

echo "[3/8] Systembenutzer anlegen..."
if ! id -u "${APP_USER}" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "/home/${APP_USER}" --shell /usr/sbin/nologin "${APP_USER}"
fi

echo "[4/8] Verzeichnisse vorbereiten..."
mkdir -p "${INSTALL_ROOT}"
mkdir -p "${DATA_DIR}"

echo "[5/8] Repository von GitHub holen..."
if [[ -d "${REPO_DIR}/.git" ]]; then
  git -C "${REPO_DIR}" remote set-url origin "${REPO_URL}"
  git -C "${REPO_DIR}" fetch --prune origin
  git -C "${REPO_DIR}" checkout -f "${REPO_BRANCH}"
  git -C "${REPO_DIR}" reset --hard "origin/${REPO_BRANCH}"
else
  rm -rf "${REPO_DIR}"
  git clone --depth 1 --branch "${REPO_BRANCH}" "${REPO_URL}" "${REPO_DIR}"
fi

mkdir -p "${SERVER_SRC_DIR}"
rsync -a --delete \
  --exclude node_modules \
  --exclude data \
  "${REPO_DIR}/server/" "${SERVER_SRC_DIR}/"

echo "[6/8] Node-Abhaengigkeiten installieren..."
cd "${SERVER_SRC_DIR}"
if [[ -f package-lock.json ]]; then
  npm ci --omit=dev --ignore-scripts
else
  npm install --omit=dev --ignore-scripts
fi

echo "[7/8] Rechte setzen..."
chown -R "${APP_USER}:${APP_GROUP}" "${INSTALL_ROOT}"

cat > "${ENV_FILE}" <<EOF
REPO_URL=${REPO_URL}
REPO_BRANCH=${REPO_BRANCH}
EOF

cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=UC ShoppingList Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${APP_USER}
Group=${APP_GROUP}
WorkingDirectory=${SERVER_SRC_DIR}
ExecStart=/usr/bin/env node --max-old-space-size=96 src/index.js
Restart=always
RestartSec=2
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
EOF

echo "[8/8] systemd aktivieren und starten..."
systemctl daemon-reload
systemctl enable --now "${APP_NAME}.service"

IP_ADDR="$(hostname -I 2>/dev/null | awk '{print $1}')"
if [[ -z "${IP_ADDR}" ]]; then
  IP_ADDR="<server-ip>"
fi

echo
echo "Installation abgeschlossen (Version ${APP_VERSION})."
echo "Service: systemctl status ${APP_NAME}.service"
echo "Webinterface: http://${IP_ADDR}:9085/"
echo "Quelle: ${REPO_URL} (Branch: ${REPO_BRANCH})"
echo "Hinweis: Port/Auth danach im Webinterface einstellen."
echo "Features: Einkauf, Kochen, Planung (neu), Notizen"
