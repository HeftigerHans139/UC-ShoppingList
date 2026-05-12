import { WebSocketServer } from "ws";
import { createServer } from "http";
import crypto from "crypto";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import QRCode from "qrcode";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const DATA_DIR = path.join(__dirname, "..", "data");
const DATA_FILE = path.join(DATA_DIR, "lists.json");
const SETTINGS_FILE = path.join(DATA_DIR, "settings.json");
const UI_FILE = path.join(__dirname, "..", "public", "index.html");

/**
 * @typedef {{ port: number, authEnabled: boolean, passwordHash: string, sessionSecret: string }} Settings
 */
/** @type {Settings} */
let settings = loadSettings();

const PORT = 9085;

const server = createServer(handleHttp);
const wss = new WebSocketServer({ server, path: "/ws" });

/**
 * @typedef {{id: string, name: string, quantity: string, assignedTo: string, status: "open"|"prepared"|"done", done: boolean, checkedBy: string, updatedAt: number}} ShoppingItem
 * @typedef {{id: string, title: string, inviteCode: string, shared: boolean, items: ShoppingItem[]}} ShoppingList
 * @typedef {{lists: Record<string, ShoppingList>, inviteToList: Record<string, string>}} Db
 */

/** @type {Db} */
let db = loadDb();

/** @type {Map<string, Set<import("ws").WebSocket>>} */
const socketsByList = new Map();

/** @type {Map<string, number>} active session tokens -> expiry ms */
const sessions = new Map();

/** @type {Map<string, {id: string, pairCode: string, deviceName: string, requestedAt: number, status: "pending"|"approved"|"rejected", listId?: string, accessToken?: string}>} */
const accessRequests = new Map();

/** @type {Map<string, {token: string, listId: string, createdAt: number, expiresAt: number, used: boolean, deviceName: string}>} */
const accessTokens = new Map();

wss.on("connection", (socket, req) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const listId = (url.searchParams.get("listId") || "").trim();

  if (!listId || !db.lists[listId]) {
    sendError(socket, "invalid_list", "Liste nicht gefunden.");
    socket.close(1008, "invalid_list");
    return;
  }

  const list = db.lists[listId];
  registerSocket(listId, socket);

  socket.send(
    JSON.stringify({
      type: "snapshot",
      payload: list
    })
  );

  socket.on("message", (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString("utf-8"));
    } catch {
      sendError(socket, "invalid_json", "Nachricht ist kein gueltiges JSON.");
      return;
    }

    handleClientMessage(listId, socket, msg);
  });

  socket.on("close", () => {
    unregisterSocket(listId, socket);
  });
});

function handleClientMessage(listId, socket, msg) {
  const list = db.lists[listId];
  if (!list) {
    sendError(socket, "invalid_list", "Liste nicht gefunden.");
    return;
  }

  const type = msg?.type;

  if (type === "add_item") {
    const name = String(msg?.payload?.name || "").trim();
    const quantity = String(msg?.payload?.quantity || "").trim();
    const assignedTo = String(msg?.payload?.assignedTo || "").trim();
    const allowEmptyQuantity = Boolean(msg?.payload?.allowEmptyQuantity);

    if (!name) {
      sendError(socket, "invalid_name", "Name darf nicht leer sein.");
      return;
    }

    const item = {
      id: createId(10),
      name,
      quantity: allowEmptyQuantity ? quantity : (quantity || "1"),
      assignedTo,
      status: "open",
      done: false,
      checkedBy: "",
      updatedAt: Date.now()
    };

    list.items.push(item);
    saveDb();
    broadcast(listId, {
      type: "item_added",
      payload: item
    });
    return;
  }

  if (type === "toggle_item") {
    const id = String(msg?.payload?.id || "");
    const requestedStatus = normalizeItemStatus(msg?.payload?.status);
    const doneFromPayload = typeof msg?.payload?.done === "boolean" ? msg.payload.done : null;
    const done = requestedStatus === "done" || (requestedStatus == null && doneFromPayload === true);
    const checkedBy = done ? String(msg?.payload?.checkedBy || "").trim() : "";
    const item = list.items.find((x) => x.id === id);

    if (!item) {
      sendError(socket, "not_found", "Artikel nicht gefunden.");
      return;
    }

    item.status = requestedStatus || (done ? "done" : "open");
    item.done = item.status === "done";
    item.checkedBy = checkedBy;
    item.updatedAt = Date.now();
    saveDb();
    broadcast(listId, {
      type: "item_updated",
      payload: item
    });
    return;
  }

  if (type === "remove_item") {
    const id = String(msg?.payload?.id || "");
    const oldLen = list.items.length;
    list.items = list.items.filter((x) => x.id !== id);

    if (list.items.length === oldLen) {
      sendError(socket, "not_found", "Artikel nicht gefunden.");
      return;
    }

    saveDb();
    broadcast(listId, {
      type: "item_removed",
      payload: { id }
    });
    return;
  }

  if (type === "update_item") {
    const id = String(msg?.payload?.id || "");
    const name = String(msg?.payload?.name || "").trim();
    const quantity = String(msg?.payload?.quantity || "").trim();
    const assignedTo = String(msg?.payload?.assignedTo || "").trim();
    const status = normalizeItemStatus(msg?.payload?.status) || "open";

    if (!name) {
      sendError(socket, "invalid_name", "Name darf nicht leer sein.");
      return;
    }

    const item = list.items.find((x) => x.id === id);
    if (!item) {
      sendError(socket, "not_found", "Artikel nicht gefunden.");
      return;
    }

    item.name = name;
    item.quantity = quantity;
    item.assignedTo = assignedTo;
    item.status = status;
    item.done = status === "done";
    item.updatedAt = Date.now();
    saveDb();
    broadcast(listId, {
      type: "item_updated",
      payload: item
    });
    return;
  }

  sendError(socket, "unknown_type", `Unbekannter Nachrichtentyp: ${type}`);
}

function handleHttp(req, res) {
  const reqUrl = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
  const p = reqUrl.pathname;
  const method = req.method || "GET";

  // ── Statisches Web-UI ────────────────────────────────────────────────────
  if (method === "GET" && (p === "/" || p === "/ui" || p === "/index.html")) {
    if (fs.existsSync(UI_FILE)) {
      const html = fs.readFileSync(UI_FILE, "utf-8");
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end(html);
    }
    res.writeHead(404);
    return res.end("Web-UI fehlt (public/index.html).");
  }

  // ── Health ────────────────────────────────────────────────────────────────
  if (method === "GET" && p === "/health") {
    return sendJson(res, 200, { ok: true, uptime: process.uptime(), lists: Object.keys(db.lists).length });
  }

  // ── Auth: Login ──────────────────────────────────────────────────────────
  if (method === "POST" && p === "/api/auth/login") {
    return readJsonBody(req, res, (body) => {
      if (!settings.authEnabled) {
        return sendJson(res, 200, { token: createSessionToken() });
      }
      const pw = String(body?.password || "");
      const hash = hashPassword(pw);
      if (!settings.passwordHash || hash === settings.passwordHash) {
        return sendJson(res, 200, { token: createSessionToken() });
      }
      return sendJson(res, 401, { error: "Falsches Passwort" });
    });
  }

  // ── Auth: Status (kein Token noetig) ────────────────────────────────────
  if (method === "GET" && p === "/api/auth/status") {
    return sendJson(res, 200, { authEnabled: settings.authEnabled });
  }

  // ── ab hier Admin-Routen: Token-Check wenn Auth aktiv ───────────────────
  const authed = !settings.authEnabled || isAuthed(req);

  // ── Admin: Settings lesen ────────────────────────────────────────────────
  if (method === "GET" && p === "/api/admin/settings") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    return sendJson(res, 200, {
      port: settings.port,
      authEnabled: settings.authEnabled,
      hasPassword: !!settings.passwordHash
    });
  }

  // ── Admin: Settings speichern ────────────────────────────────────────────
  if (method === "POST" && p === "/api/admin/settings") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    return readJsonBody(req, res, (body) => {
      if (typeof body?.authEnabled === "boolean") settings.authEnabled = body.authEnabled;
      if (typeof body?.port === "number" && body.port > 0 && body.port < 65536) settings.port = body.port;
      if (typeof body?.newPassword === "string") {
        settings.passwordHash = body.newPassword.trim() ? hashPassword(body.newPassword.trim()) : "";
      }
      saveSettings();
      sendJson(res, 200, { ok: true });
    });
  }

  // ── Admin: Neustart ──────────────────────────────────────────────────────
  if (method === "POST" && p === "/api/admin/restart") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    sendJson(res, 200, { ok: true, message: "Server wird neu gestartet..." });
    setTimeout(() => process.exit(0), 300);
    return;
  }

  // ── Admin: Herunterfahren ────────────────────────────────────────────────
  if (method === "POST" && p === "/api/admin/shutdown") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    sendJson(res, 200, { ok: true, message: "Server wird heruntergefahren." });
    setTimeout(() => process.exit(0), 300);
    return;
  }

  // ── Admin: Alle Listen ────────────────────────────────────────────────────
  if (method === "GET" && p === "/api/admin/lists") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    const summary = Object.values(db.lists).map((l) => ({
      id: l.id,
      title: l.title,
      inviteCode: l.inviteCode,
      shared: l.shared ?? true,
      itemCount: l.items.length,
      connectedClients: socketsByList.get(l.id)?.size ?? 0
    }));
    return sendJson(res, 200, summary);
  }

  // ── Admin: offene Zugriffsanfragen ───────────────────────────────────────
  if (method === "GET" && p === "/api/admin/access/requests") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    cleanupExpiredAccessData();
    const list = Array.from(accessRequests.values())
      .sort((a, b) => b.requestedAt - a.requestedAt);
    return sendJson(res, 200, list);
  }

  // ── Admin: Anfrage annehmen und One-Time-Token ausstellen ───────────────
  if (method === "POST" && p === "/api/admin/access/approve") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    return readJsonBody(req, res, (body) => {
      cleanupExpiredAccessData();
      const requestId = String(body?.requestId || "").trim();
      const request = accessRequests.get(requestId);
      if (!request || request.status !== "pending") {
        return sendJson(res, 404, { error: "Anfrage nicht gefunden" });
      }

      const listId = resolveListId(body?.listId, request.listId);
      if (!listId) return sendJson(res, 400, { error: "Keine gueltige Liste vorhanden" });

      request.status = "approved";
      request.listId = listId;

      const oneTime = createAccessToken(listId, request.deviceName || "Neues Geraet");
      request.accessToken = oneTime.token;
      const deepLink = buildAccessLink(req, oneTime.token);

      QRCode.toDataURL(deepLink, { margin: 1, width: 300 })
        .then((qrDataUrl) => {
          sendJson(res, 200, {
            ok: true,
            requestId,
            listId,
            accessToken: oneTime.token,
            deepLink,
            qrDataUrl,
            expiresAt: oneTime.expiresAt
          });
        })
        .catch(() => sendJson(res, 500, { error: "QR konnte nicht erzeugt werden" }));
    });
  }

  // ── Admin: Anfrage ablehnen ──────────────────────────────────────────────
  if (method === "POST" && p === "/api/admin/access/reject") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    return readJsonBody(req, res, (body) => {
      const requestId = String(body?.requestId || "").trim();
      const request = accessRequests.get(requestId);
      if (!request) return sendJson(res, 404, { error: "Anfrage nicht gefunden" });
      request.status = "rejected";
      return sendJson(res, 200, { ok: true });
    });
  }

  // ── Admin: One-Time-Zugang direkt erzeugen (z. B. fuer QR) ──────────────
  if (method === "POST" && p === "/api/admin/access/create-token") {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    return readJsonBody(req, res, (body) => {
      cleanupExpiredAccessData();
      const listId = resolveListId(body?.listId);
      if (!listId) return sendJson(res, 400, { error: "Keine gueltige Liste vorhanden" });
      const deviceName = String(body?.deviceName || "Neues Geraet").trim() || "Neues Geraet";
      const oneTime = createAccessToken(listId, deviceName);
      const deepLink = buildAccessLink(req, oneTime.token);

      QRCode.toDataURL(deepLink, { margin: 1, width: 300 })
        .then((qrDataUrl) => {
          sendJson(res, 200, {
            ok: true,
            listId,
            accessToken: oneTime.token,
            deepLink,
            qrDataUrl,
            expiresAt: oneTime.expiresAt,
            deviceName
          });
        })
        .catch(() => sendJson(res, 500, { error: "QR konnte nicht erzeugt werden" }));
    });
  }

  // ── Admin: Liste loeschen ─────────────────────────────────────────────────
  if (method === "DELETE" && p.startsWith("/api/admin/lists/")) {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    const listId = p.split("/").pop() || "";
    if (!db.lists[listId]) return sendJson(res, 404, { error: "Liste nicht gefunden" });
    const code = db.lists[listId].inviteCode;
    delete db.lists[listId];
    delete db.inviteToList[code];
    saveDb();
    return sendJson(res, 200, { ok: true });
  }

  // ── Admin: Liste Sichtbarkeit aendern ─────────────────────────────────────
  if (method === "PATCH" && p.startsWith("/api/admin/lists/")) {
    if (!authed) return sendJson(res, 401, { error: "Nicht autorisiert" });
    const listId = p.split("/").pop() || "";
    const list = db.lists[listId];
    if (!list) return sendJson(res, 404, { error: "Liste nicht gefunden" });
    return readJsonBody(req, res, (body) => {
      if (typeof body?.shared === "boolean") list.shared = body.shared;
      if (typeof body?.title === "string" && body.title.trim()) list.title = body.title.trim();
      saveDb();
      sendJson(res, 200, { ok: true });
    });
  }

  // ── Oeffentliche API: Liste erstellen ─────────────────────────────────────
  if (method === "POST" && p === "/api/lists/create") {
    return readJsonBody(req, res, (body) => {
      const title = String(body?.title || "Gemeinsame Einkaufsliste").trim() || "Gemeinsame Einkaufsliste";
      const shared = body?.shared !== false;
      const list = createList(title, shared);
      sendJson(res, 201, { listId: list.id, inviteCode: list.inviteCode, title: list.title, shared: list.shared });
    });
  }

  // ── Oeffentliche API: Liste per Code beitreten ────────────────────────────
  if (method === "POST" && p === "/api/lists/join") {
    return readJsonBody(req, res, (body) => {
      const inviteCode = String(body?.inviteCode || "").trim().toUpperCase();
      if (!inviteCode) return sendJson(res, 400, { error: "inviteCode fehlt" });
      const listId = db.inviteToList[inviteCode];
      if (!listId || !db.lists[listId]) return sendJson(res, 404, { error: "Code ungueltig" });
      const list = db.lists[listId];
      if (!list.shared) return sendJson(res, 403, { error: "Diese Liste wird nicht geteilt" });
      sendJson(res, 200, { listId: list.id, inviteCode: list.inviteCode, title: list.title });
    });
  }

  // ── Oeffentliche API: Erstanfrage fuer Zugriff (pending approval) ────────
  if (method === "POST" && p === "/api/access/request") {
    return readJsonBody(req, res, (body) => {
      cleanupExpiredAccessData();
      const deviceName = String(body?.deviceName || "Neues Geraet").trim() || "Neues Geraet";
      const listId = resolveListId(body?.listId);
      const requestId = createId(16);
      const request = {
        id: requestId,
        pairCode: createCode(6),
        deviceName,
        requestedAt: Date.now(),
        status: "pending",
        listId
      };
      accessRequests.set(requestId, request);
      return sendJson(res, 201, request);
    });
  }

  // ── Oeffentliche API: Anfrage-Status pruefen ─────────────────────────────
  if (method === "GET" && p.startsWith("/api/access/request/") && p.split("/").length === 5) {
    cleanupExpiredAccessData();
    const requestId = p.split("/")[4] || "";
    const request = accessRequests.get(requestId);
    if (!request) return sendJson(res, 404, { error: "Anfrage nicht gefunden" });
    return sendJson(res, 200, request);
  }

  // ── Oeffentliche API: One-Time-Token einloesen ───────────────────────────
  if (method === "POST" && p === "/api/access/redeem") {
    return readJsonBody(req, res, (body) => {
      cleanupExpiredAccessData();
      const token = String(body?.accessToken || "").trim();
      const entry = accessTokens.get(token);
      if (!entry) return sendJson(res, 404, { error: "Token ungueltig" });
      if (entry.used) return sendJson(res, 410, { error: "Token bereits verwendet" });
      if (Date.now() > entry.expiresAt) {
        accessTokens.delete(token);
        return sendJson(res, 410, { error: "Token abgelaufen" });
      }
      const list = db.lists[entry.listId];
      if (!list) return sendJson(res, 404, { error: "Liste nicht gefunden" });

      entry.used = true;
      return sendJson(res, 200, {
        listId: list.id,
        inviteCode: list.inviteCode,
        title: list.title,
        shared: list.shared
      });
    });
  }

  // ── Oeffentliche API: Einzelne Liste ─────────────────────────────────────
  if (method === "GET" && p.startsWith("/api/lists/") && p.split("/").length === 4) {
    const listId = p.split("/")[3] || "";
    const list = db.lists[listId];
    if (!list) return sendJson(res, 404, { error: "Liste nicht gefunden" });
    return sendJson(res, 200, list);
  }

  // ── Oeffentliche API: Artikel per HTTP togglen (fuer Widget) ─────────────
  if (method === "PATCH" && /^\/api\/lists\/[^/]+\/items\/[^/]+$/.test(p)) {
    const parts = p.split("/");
    const listId = parts[3] || "";
    const itemId = parts[5] || "";
    const list = db.lists[listId];
    if (!list) return sendJson(res, 404, { error: "Liste nicht gefunden" });
    return readJsonBody(req, res, (body) => {
      const item = list.items.find((x) => x.id === itemId);
      if (!item) return sendJson(res, 404, { error: "Artikel nicht gefunden" });
      const requestedStatus = normalizeItemStatus(body?.status);
      if (typeof body?.done === "boolean") {
        item.done = body.done;
        item.status = body.done ? "done" : "open";
        item.updatedAt = Date.now();
      }
      if (requestedStatus) {
        item.status = requestedStatus;
        item.done = requestedStatus === "done";
        item.updatedAt = Date.now();
      }
      saveDb();
      broadcast(listId, { type: "item_updated", payload: item });
      sendJson(res, 200, item);
    });
  }

  sendJson(res, 404, { error: "not_found" });
}

function registerSocket(listId, socket) {
  if (!socketsByList.has(listId)) {
    socketsByList.set(listId, new Set());
  }
  socketsByList.get(listId).add(socket);
}

function unregisterSocket(listId, socket) {
  const bag = socketsByList.get(listId);
  if (!bag) {
    return;
  }
  bag.delete(socket);
  if (bag.size === 0) {
    socketsByList.delete(listId);
  }
}

function broadcast(listId, message) {
  const bag = socketsByList.get(listId);
  if (!bag) {
    return;
  }

  const encoded = JSON.stringify(message);
  for (const ws of bag) {
    if (ws.readyState === ws.OPEN) {
      ws.send(encoded);
    }
  }
}

function sendError(socket, code, message) {
  if (socket.readyState === socket.OPEN) {
    socket.send(JSON.stringify({ type: "error", payload: { code, message } }));
  }
}

function createList(title, shared = true) {
  let inviteCode = "";
  do {
    inviteCode = createCode(6);
  } while (db.inviteToList[inviteCode]);

  const listId = createId(12);
  const list = {
    id: listId,
    title,
    inviteCode,
    shared,
    items: []
  };

  db.lists[listId] = list;
  db.inviteToList[inviteCode] = listId;
  saveDb();
  return list;
}

function normalizeItemStatus(rawStatus) {
  const normalized = String(rawStatus || "").trim().toLowerCase();
  if (normalized === "open" || normalized === "prepared" || normalized === "done") {
    return normalized;
  }
  return null;
}

function loadDb() {
  try {
    if (!fs.existsSync(DATA_DIR)) {
      fs.mkdirSync(DATA_DIR, { recursive: true });
    }
    if (!fs.existsSync(DATA_FILE)) {
      const initialDb = { lists: {}, inviteToList: {} };
      fs.writeFileSync(DATA_FILE, JSON.stringify(initialDb, null, 2), "utf-8");
      return initialDb;
    }

    const raw = fs.readFileSync(DATA_FILE, "utf-8");
    const parsed = JSON.parse(raw);

    if (parsed && parsed.lists && parsed.inviteToList) {
      // ensure new fields exist on old data
      for (const list of Object.values(parsed.lists)) {
        if (typeof list.shared !== "boolean") list.shared = true;
        if (!Array.isArray(list.items)) list.items = [];
        for (const item of list.items) {
          item.quantity = typeof item.quantity === "string" ? item.quantity : "1";
          item.assignedTo = typeof item.assignedTo === "string" ? item.assignedTo : "";
          item.checkedBy = typeof item.checkedBy === "string" ? item.checkedBy : "";
          const status = normalizeItemStatus(item.status);
          item.status = status || (item.done ? "done" : "open");
          item.done = item.status === "done";
        }
      }
      return parsed;
    }

    // Migration from old format
    const migrated = { lists: {}, inviteToList: {} };
    for (const [listId, list] of Object.entries(parsed || {})) {
      const inviteCode = createCode(6);
      migrated.lists[listId] = {
        id: listId,
        title: String(list?.title || "Gemeinsame Einkaufsliste"),
        inviteCode,
        shared: true,
        items: Array.isArray(list?.items) ? list.items : []
      };
      migrated.inviteToList[inviteCode] = listId;
    }
    fs.writeFileSync(DATA_FILE, JSON.stringify(migrated, null, 2), "utf-8");
    return migrated;
  } catch (err) {
    console.error("Konnte Daten nicht laden:", err);
    return { lists: {}, inviteToList: {} };
  }
}

function saveDb() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(db, null, 2), "utf-8");
  } catch (err) {
    console.error("Konnte Daten nicht speichern:", err);
  }
}

function loadSettings() {
  try {
    if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
    if (!fs.existsSync(SETTINGS_FILE)) {
      const defaults = { port: 8080, authEnabled: false, passwordHash: "", sessionSecret: createId(32) };
      fs.writeFileSync(SETTINGS_FILE, JSON.stringify(defaults, null, 2), "utf-8");
      return defaults;
    }
    const raw = fs.readFileSync(SETTINGS_FILE, "utf-8");
    const parsed = JSON.parse(raw);
    if (!parsed.sessionSecret) {
      parsed.sessionSecret = createId(32);
      fs.writeFileSync(SETTINGS_FILE, JSON.stringify(parsed, null, 2), "utf-8");
    }
    return parsed;
  } catch (err) {
    console.error("Konnte Settings nicht laden:", err);
    return { port: 8080, authEnabled: false, passwordHash: "", sessionSecret: createId(32) };
  }
}

function saveSettings() {
  try {
    fs.writeFileSync(SETTINGS_FILE, JSON.stringify(settings, null, 2), "utf-8");
  } catch (err) {
    console.error("Konnte Settings nicht speichern:", err);
  }
}

function hashPassword(pw) {
  return crypto.createHmac("sha256", settings.sessionSecret).update(pw).digest("hex");
}

function createSessionToken() {
  const token = createId(32);
  sessions.set(token, Date.now() + 24 * 60 * 60 * 1000);
  return token;
}

function isAuthed(req) {
  const authHeader = String(req.headers?.authorization || "");
  const token = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
  if (!token) return false;
  const expiry = sessions.get(token);
  if (!expiry || Date.now() > expiry) {
    sessions.delete(token);
    return false;
  }
  return true;
}

function cleanupExpiredAccessData() {
  const now = Date.now();
  for (const [token, entry] of accessTokens.entries()) {
    if (entry.used || now > entry.expiresAt) {
      accessTokens.delete(token);
    }
  }

  for (const [id, request] of accessRequests.entries()) {
    if (now - request.requestedAt > 24 * 60 * 60 * 1000) {
      accessRequests.delete(id);
    }
  }
}

function resolveListId(...candidates) {
  for (const candidate of candidates) {
    const id = String(candidate || "").trim();
    if (id && db.lists[id]) return id;
  }
  const first = Object.keys(db.lists)[0];
  return first || "";
}

function createAccessToken(listId, deviceName) {
  const token = createId(30);
  const entry = {
    token,
    listId,
    createdAt: Date.now(),
    expiresAt: Date.now() + 10 * 60 * 1000,
    used: false,
    deviceName
  };
  accessTokens.set(token, entry);
  return entry;
}

function buildAccessLink(req, accessToken) {
  const host = String(req.headers.host || "localhost");
  const forwardedProto = String(req.headers["x-forwarded-proto"] || "").split(",")[0].trim();
  const scheme = forwardedProto || "http";
  const serverBase = `${scheme}://${host}`;
  const encodedServer = encodeURIComponent(serverBase);
  return `ucshoppinglist://join?server=${encodedServer}&access=${accessToken}`;
}

function createId(length) {
  return crypto.randomBytes(length).toString("base64url").slice(0, length);
}

function createCode(length) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let code = "";
  const bytes = crypto.randomBytes(length);
  for (let i = 0; i < length; i += 1) {
    code += alphabet[bytes[i] % alphabet.length];
  }
  return code;
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store"
  });
  res.end(body);
}

function readJsonBody(req, res, onBody) {
  let body = "";
  req.on("data", (chunk) => {
    body += chunk;
    if (body.length > 1024 * 32) {
      sendJson(res, 413, { error: "body_too_large" });
      req.destroy();
    }
  });
  req.on("end", () => {
    try {
      onBody(body ? JSON.parse(body) : {});
    } catch {
      sendJson(res, 400, { error: "invalid_json" });
    }
  });
}

server.listen(PORT, "0.0.0.0", () => {
  console.log(`UC ShoppingList Server laeuft auf http://0.0.0.0:${PORT}`);
});
