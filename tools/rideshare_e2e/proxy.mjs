// Transparent wire proxy in front of the stand API (clients → :8000 → API :8010).
// Logs every real request/response (method, path, status, client family), and on command
//  - "drop": forwards ONE matching request to the API, waits for its full response, then cuts
//    the client socket without sending it (server processed, client lost the answer);
//  - "before-forward": cuts one request before connecting to the API.
//  - "hold-response": waits for the API response, then keeps the client waiting for release.
//  - "down": refuses/cuts all client connections (network loss), "up" restores.
// Control: POST /fault {mode, method, path(regex), count}, GET /log?from=N.
// After independently checking the database, POST /fault/release {id: response_held.n}.
import http from "node:http";
import net from "node:net";
import { appendFileSync } from "node:fs";

const LISTEN = Number(process.env.PROXY_PORT || 8000);
const UP = Number(process.env.UPSTREAM_PORT || 8010);
const CONTROL = Number(process.env.PROXY_CONTROL_PORT || 8099);
const LOG = process.env.WIRE_LOG;
const wire = [];
let faults = [];
let down = false;
const sockets = new Set();
const heldResponses = new Map();
const requestFaultModes = new Set(["drop", "before-forward", "hold-response"]);

function family(ua = "") { return /okhttp|Dalvik|Android/i.test(ua) && !/Chrome/i.test(ua) ? "android" : /Chrome|Mozilla/i.test(ua) ? "browser" : /node|undici/i.test(ua) ? "harness" : "other"; }
function record(e) {
  e.n = wire.length + 1; e.at = new Date().toISOString(); wire.push(e);
  if (LOG) appendFileSync(LOG, JSON.stringify(e) + "\n");
  return e.n;
}
function cleanPath(url) { const [p, q] = url.split("?"); return q ? p + "?" + q.split("&").map((kv) => kv.split("=")[0] + "=…").join("&") : p; }
function takeFault(method, path) {
  const i = faults.findIndex((f) => requestFaultModes.has(f.mode) && (!f.method || f.method === method) && new RegExp(f.path).test(path));
  if (i < 0) return null;
  const f = faults[i]; f.count = (f.count || 1) - 1; if (f.count <= 0) faults.splice(i, 1);
  return f;
}

const server = http.createServer((req, res) => {
  const path = req.url;
  const fam = family(req.headers["user-agent"]);
  if (down) { record({ method: req.method, path: cleanPath(path), client: fam, outcome: "refused_down" }); req.socket.destroy(); return; }
  const fault = takeFault(req.method, path.split("?")[0]);
  if (fault?.mode === "before-forward") {
    record({ method: req.method, path: cleanPath(path), client: fam, outcome: "held_before_forward", fault: fault.tag || null });
    req.socket.destroy();
    return;
  }

  const t0 = Date.now();
  const up = http.request({ host: "127.0.0.1", port: UP, method: req.method, path, headers: req.headers }, (ur) => {
    if (fault) {
      ur.on("end", () => {
        const event = { method: req.method, path: cleanPath(path), client: fam, status: ur.statusCode, ms: Date.now() - t0, fault: fault.tag || null };
        if (fault.mode === "drop") {
          record({ ...event, outcome: "response_dropped_after_server" });
          req.socket.destroy();
        } else if (!res.destroyed) {
          const id = record({ ...event, outcome: "response_held" });
          heldResponses.set(id, { event, res });
          res.once("close", () => {
            if (heldResponses.delete(id)) record({ ...event, outcome: "response_hold_abandoned", held: id });
          });
        }
      });
      ur.on("error", () => res.destroy());
      ur.resume();
      return;
    }
    res.writeHead(ur.statusCode, ur.headers);
    ur.pipe(res);
    ur.on("end", () => record({ method: req.method, path: cleanPath(path), client: fam, status: ur.statusCode, ms: Date.now() - t0 }));
  });
  up.on("error", (e) => { record({ method: req.method, path: cleanPath(path), client: fam, outcome: "upstream_error", error: e.code }); res.destroy(); });
  req.pipe(up);
});
server.on("connection", (s) => { if (down) { s.destroy(); return; } sockets.add(s); s.on("close", () => sockets.delete(s)); });
server.on("upgrade", (req, socket, head) => {
  const fam = family(req.headers["user-agent"]);
  if (down) { socket.destroy(); return; }
  const upSock = net.connect(UP, "127.0.0.1", () => {
    let raw = `${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`;
    for (let i = 0; i < req.rawHeaders.length; i += 2) raw += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`;
    upSock.write(raw + "\r\n"); if (head && head.length) upSock.write(head);
    upSock.pipe(socket); socket.pipe(upSock);
    record({ method: "WS", path: cleanPath(req.url), client: fam, outcome: "upgrade" });
  });
  sockets.add(socket); sockets.add(upSock);
  const end = () => { socket.destroy(); upSock.destroy(); sockets.delete(socket); sockets.delete(upSock); };
  socket.on("error", end); upSock.on("error", end); socket.on("close", end); upSock.on("close", end);
});
server.keepAliveTimeout = 5000;

const control = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const u = new URL(req.url, "http://x");
    if (req.method === "POST" && (u.pathname === "/fault" || u.pathname === "/fault/release")) {
      let f;
      try {
        f = JSON.parse(body);
        if (!f || typeof f !== "object" || Array.isArray(f)) throw new TypeError();
      } catch {
        res.writeHead(400).end();
        return;
      }
      if (u.pathname === "/fault/release") {
        const held = heldResponses.get(f.id);
        if (!held) {
          res.writeHead(404).end();
          return;
        }
        heldResponses.delete(f.id);
        record({ ...held.event, outcome: "held_response_dropped", held: f.id });
        held.res.destroy();
        res.end(JSON.stringify({ ok: true }));
        return;
      }
      if (f.mode === "down") { down = true; for (const s of sockets) s.destroy(); sockets.clear(); }
      else if (f.mode === "up") down = false;
      else if (f.mode === "clear") faults = [];
      else {
        if (!requestFaultModes.has(f.mode)) {
          res.writeHead(400).end();
          return;
        }
        try {
          new RegExp(f.path);
        } catch {
          res.writeHead(400).end();
          return;
        }
        faults.push(f);
      }
      record({ method: "CONTROL", path: f.mode, fault: f.tag || null, detail: requestFaultModes.has(f.mode) ? `${f.method || "*"} ${f.path}` : null });
      res.end(JSON.stringify({ ok: true, down, faults }));
    } else if (u.pathname === "/log") {
      const from = Number(u.searchParams.get("from") || 0);
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify(wire.slice(from)));
    } else { res.statusCode = 404; res.end(); }
  });
});

server.listen(LISTEN, "127.0.0.1", () => {
  control.listen(CONTROL, "127.0.0.1", () => {
    console.log(`proxy :${server.address().port} -> :${UP}, control :${control.address().port}`);
  });
});
