# UC ShoppingList

Selbst hostbare Echtzeit-Einkaufsliste mit:

- Node.js WebSocket-Server (inkrementelle Updates, kein Full-Reload pro Aenderung)
- Android App mit Kotlin + Jetpack Compose
- Zweistufiges Hinzufuegen: zuerst Artikelname, danach Menge
- Liste erstellen oder per Einladungscode beitreten
- Offline-Aktionsqueue auf dem Client (wird bei Reconnect automatisch gesendet)

## Projektstruktur

```text
.
|- server/                  # Self-hosted Realtime-Backend
|- android-app/             # Android Client (Jetpack Compose)
|- roadmap.md
```

## 1) Server starten (lokal / Ubuntu / WSL2)

Voraussetzung: Node.js 20+

```bash
cd server
npm install
npm run dev
```

Hinweis fuer Windows PowerShell mit restriktiver Policy:

```powershell
cd server
npm.cmd install
npm.cmd run dev
```

Server startet standardmaessig auf `http://0.0.0.0:9085`.

### Vollautomatische Ubuntu-Installation (empfohlen)

Wenn der Server ohne manuelle Einzelschritte laufen soll (inkl. Abhaengigkeiten + systemd Autostart):

```bash
cd UC-ShoppingList
sudo ./release/install-ubuntu.sh
```

Was automatisch passiert:

- installiert Systemabhaengigkeiten und Node.js 20 (falls noetig)
- legt Systemdienst-Benutzer `ucshoppinglist` an
- klont immer automatisch das aktuelle GitHub-Repo `https://github.com/HeftigerHans139/UC-ShoppingList.git`
- installiert Server direkt in den Ordner, in dem das Skript liegt
- installiert npm-Abhaengigkeiten
- richtet systemd-Dienst `uc-shoppinglist.service` ein
- aktiviert Autostart und startet den Dienst sofort

Danach ist nur noch noetig:

- Webinterface oeffnen: `http://<server-ip>:9085/`
- Server im Webinterface konfigurieren (Port, Passwortschutz, etc.)

Nutzbefehle:

```bash
sudo systemctl status uc-shoppinglist.service
sudo systemctl restart uc-shoppinglist.service
sudo journalctl -u uc-shoppinglist.service -f
```

### Server-Update auf Ubuntu

Bei spaeteren Aenderungen im Projekt:

```bash
cd UC-ShoppingList
sudo ./release/update-ubuntu.sh
```

Das Update zieht automatisch die neueste Version vom konfigurierten GitHub-Repo und startet den Dienst neu.

API-Endpunkte:

- `POST /api/lists/create` erstellt Liste und gibt Einladungscode zurueck
- `POST /api/lists/join` verbindet per Einladungscode

WebSocket-Endpunkt:

`ws://<dein-server>:9085/ws?listId=<listId>`

## 2) Reverse Proxy mit HTTP und HTTPS

Ein Caddy-Proxy ist enthalten. HTTP bleibt aktiv und erlaubt.

- HTTP: `http://<server-ip>/...`
- WS: `ws://<server-ip>/ws?listId=<listId>`
- HTTPS: `https://<server-ip>/...` (internes Zertifikat)
- WSS: `wss://<server-ip>/ws?listId=<listId>`

Start:

```bash
docker compose up -d --build
```

Danach laeuft der Node-Server intern und Caddy stellt Ports 80/443 bereit.

## 3) Android App konfigurieren

Die Server-Verbindung wird direkt in der App im `Verbindungsmenue` gesetzt (Icon mit den verbundenen Lini en).

- Server URL eintragen: z. B. `http://10.0.2.2:9085` (Emulator) oder `http://<LAN-IP-deines-Servers>`
- Danach `Nur verbinden`, `Neue Liste` oder `Beitreten` waehlen

Dann in Android Studio den Ordner `android-app` oeffnen und starten.

Beim ersten Start:

1. Person A erstellt eine neue Liste.
2. Person A teilt den Einladungslink ueber das Link-Icon.
3. Person B tippt nur auf den Link und landet direkt im Join-Prozess.

Beispiel-Link:

`ucshoppinglist://join?server=http%3A%2F%2F192.168.178.50&code=ABC123`

## Realtime-Verhalten

- Beim Verbinden schickt der Server einmalig ein Snapshot-Event mit kompletter Liste.
- Danach werden nur Delta-Events gesendet:
  - `item_added`
  - `item_updated`
  - `item_removed`
- Wenn Person A ein Item erledigt oder entfernt, sieht Person B die Aenderung sofort.
- Bei kurzzeitigem Offline-Zustand werden lokale Aktionen gepuffert und spaeter automatisch gesendet.

## Docker Self-Hosting (ressourcenschonend)

```bash
docker compose up -d --build
```

Das Compose-Setup ist auf niedrigen Verbrauch ausgelegt:

 - schlankes `node:20-alpine`
 - RAM-Limit App-Server `128m`
 - CPU-Limit App-Server `0.50`
 - RAM-Limit Proxy `64m`
 - CPU-Limit Proxy `0.25`
 - Daten persistent in `server/data`

## Ubuntu/WSL2 Tipps fuer wenig Ressourcen

 - Nutze `npm start` statt `npm run dev` im Dauerbetrieb.
 - Starte den Server hinter Reverse Proxy nur einmal (kein Watch-Mode).
 - In WSL2 optional Limits in `%UserProfile%/.wslconfig` setzen:

```ini
[wsl2]
memory=1GB
processors=2
```

## Hinweise

- Speicherung ist bewusst leichtgewichtig in JSON-Datei (`server/data/lists.json`).
- Fuer Produktion: HTTPS + WSS, Authentifizierung und Reverse Proxy (z. B. Caddy/Nginx) ergaenzen.