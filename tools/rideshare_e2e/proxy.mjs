// Transparent wire proxy in front of the stand API (clients → :8000 → API :8010).
// Logs every real request/response (method, path, status, client family), and on command
//  - "drop": forwards ONE matching request to the API, waits for its full response, then cuts
//    the client socket without sending it (server processed, client lost the answer);
//  - "down": refuses/cuts all client connections (network loss), "up" restores.
// Control: http://127.0.0.1:8099  POST /fault {mode, method, path(regex), count}  GET /log?from=N
import http from "node:http";
import net from "node:net";
import { appendFileSync } from "node:fs";

const LISTEN = Number(process.env.PROXY_PORT || 8000);
const UP = Number(process.env.UPSTREAM_PORT || 8010);
const LOG = process.env.WIRE_LOG;
const wire = [];
let faults = [];
let down = false;
const sockets = new Set();

function family(ua = "") { return /okhttp|Dalvik|Android/i.test(ua) && !/Chrome/i.test(ua) ? "android" : /Chrome|Mozilla/i.test(ua) ? "browser" : /node|undici/i.test(ua) ? "harness" : "other"; }
function record(e) {
  e.n = wire.length + 1; e.at = new Date().toISOString(); wire.push(e);
  if (LOG) appendFileSync(LOG, JSON.stringify(e) + "\n");
}
function cleanPath(url) { const [p, q] = url.split("?"); return q ? p + "?" + q.split("&").map((kv) => kv.split("=")[0] + "=…").join("&") : p; }
function takeFault(method, path) {
  const i = faults.findIndex((f) => f.mode === "drop" && (!f.method || f.method === method) && new RegExp(f.path).test(path));
  if (i < 0) return null;
  const f = faults[i]; f.count = (f.count || 1) - 1; if (f.count <= 0) faults.splice(i, 1);
  return f;
}

const server = http.createServer((req, res) => {
  const path = req.url;
  const fam = family(req.headers["user-agent"]);
  if (down) { record({ method: req.method, path: cleanPath(path), client: fam, outcome: "refused_down" }); req.socket.destroy(); return; }
  const fault = takeFault(req.method, path.split("?")[0]);
  const t0 = Date.now();
  const up = http.request({ host: "127.0.0.1", port: UP, method: req.method, path, headers: req.headers }, (ur) => {
    if (fault) {
      const chunks = [];
      ur.on("data", (c) => chunks.push(c));
      ur.on("end", () => {
        record({ method: req.method, path: cleanPath(path), client: fam, status: ur.statusCode, ms: Date.now() - t0, outcome: "response_dropped_after_server", fault: fault.tag || null });
        req.socket.destroy();
      });
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
server.listen(LISTEN, "127.0.0.1");

http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const u = new URL(req.url, "http://x");
    if (req.method === "POST" && u.pathname === "/fault") {
      const f = JSON.parse(body || "{}");
      if (f.mode === "down") { down = true; for (const s of sockets) s.destroy(); sockets.clear(); }
      else if (f.mode === "up") down = false;
      else if (f.mode === "clear") faults = [];
      else faults.push(f);
      record({ method: "CONTROL", path: f.mode, fault: f.tag || null, detail: f.mode === "drop" ? `${f.method || "*"} ${f.path}` : null });
      res.end(JSON.stringify({ ok: true, down, faults }));
    } else if (u.pathname === "/log") {
      const from = Number(u.searchParams.get("from") || 0);
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify(wire.slice(from)));
    } else { res.statusCode = 404; res.end(); }
  });
}).listen(8099, "127.0.0.1");
console.log(`proxy :${LISTEN} -> :${UP}, control :8099`);
