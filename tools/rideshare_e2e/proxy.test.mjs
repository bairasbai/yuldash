import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import http from "node:http";
import { createInterface } from "node:readline";
import test from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

function send(port, path, { method = "GET", key, body, signal } = {}) {
  return new Promise((resolve, reject) => {
    const headers = key ? { "Idempotency-Key": key } : {};
    const req = http.request({ hostname: "127.0.0.1", port, path, method, agent: false, headers, signal }, res => {
      let raw = "";
      res.setEncoding("utf8");
      res.on("data", chunk => { raw += chunk; });
      res.on("end", () => resolve({ status: res.statusCode, body: raw ? JSON.parse(raw) : null }));
      res.on("error", reject);
    });
    req.setTimeout(4_000, () => req.destroy(new Error("Test request timed out")));
    req.on("error", reject);
    req.end(body ? JSON.stringify(body) : undefined);
  });
}

test("wire faults mark when a write can reach the API", { timeout: 15_000 }, async t => {
  const written = new Map();
  const requests = [];
  const upstream = http.createServer((req, res) => {
    if (req.method === "POST") {
      const key = req.headers["idempotency-key"];
      requests.push(key);
      if (!written.has(key)) written.set(key, written.size + 1);
      res.end(JSON.stringify({ id: written.get(key) }));
    } else {
      res.end(JSON.stringify(Object.fromEntries(written)));
    }
  });
  const upstreamReady = once(upstream, "listening");
  upstream.listen(0, "127.0.0.1");
  await upstreamReady;
  t.after(() => new Promise(resolve => upstream.close(resolve)));

  const script = fileURLToPath(new URL("./proxy.mjs", import.meta.url));
  const proxy = spawn(process.execPath, [script], {
    cwd: fileURLToPath(new URL(".", import.meta.url)),
    env: { ...process.env, PROXY_PORT: "0", PROXY_CONTROL_PORT: "0",
      UPSTREAM_PORT: String(upstream.address().port), WIRE_LOG: "" },
    windowsHide: true, stdio: ["ignore", "pipe", "pipe"],
  });
  const proxyClosed = once(proxy, "close");
  const lines = createInterface({ input: proxy.stdout });
  t.after(async () => {
    lines.close();
    if (proxy.exitCode === null && proxy.signalCode === null) proxy.kill();
    await proxyClosed;
  });
  const [ready] = await once(lines, "line");
  const [, dataPort, controlPort] = /^proxy :(\d+) -> :\d+, control :(\d+)$/.exec(ready);
  t.diagnostic(JSON.stringify({
    owner: "Codex RS-16 wire-test", pid: proxy.pid, started: new Date().toISOString(),
    ports: [Number(dataPort), Number(controlPort), upstream.address().port],
  }));

  async function arm(mode, path) {
    const result = await send(controlPort, "/fault", { method: "POST", body: { mode, method: "POST", path: "^" + path + "$" } });
    assert.equal(result.status, 200);
  }

  async function logEntry(outcome, path) {
    for (let attempt = 0; attempt < 50; attempt++) {
      const { body } = await send(controlPort, "/log");
      const entry = body.find(row => row.outcome === outcome && (!path || path === row.path));
      if (entry) return entry;
      await delay(20);
    }
    assert.fail("No wire notification: " + outcome);
  }

  await t.test("normal and legacy drop operations keep their existing meaning", async () => {
    assert.equal((await send(dataPort, "/writes/normal", { method: "POST", key: "normal" })).status, 200);
    await arm("drop", "/writes/legacy");
    await assert.rejects(send(dataPort, "/writes/legacy", { method: "POST", key: "legacy" }), { code: "ECONNRESET" });
    assert.equal(requests.filter(key => key === "legacy").length, 1);
    await logEntry("response_dropped_after_server");
  });

  await t.test("before-forward cuts the client once and leaves the API unchanged", async () => {
    await arm("before-forward", "/writes/before");
    await assert.rejects(send(dataPort, "/writes/before", { method: "POST", key: "before" }), { code: "ECONNRESET" });
    assert.equal(requests.includes("before"), false);
    await logEntry("held_before_forward");
    const retry = await send(dataPort, "/writes/before", { method: "POST", key: "before" });
    assert.equal(retry.body.id, written.get("before"));
    assert.equal(requests.filter(key => key === "before").length, 1);
  });

  await t.test("held response stays unread until the test checks the result and drops it", async () => {
    await arm("hold-response", "/writes/after");
    let settled = false;
    const first = send(dataPort, "/writes/after", { method: "POST", key: "after" })
      .then(response => { settled = true; return { response }; }, error => { settled = true; return { error }; });
    const entry = await logEntry("response_held");
    assert.equal(entry.status, 200);
    const probe = await send(upstream.address().port, "/independent-check");
    const ref = probe.body.after;
    assert(Number.isInteger(ref));
    assert.equal(settled, false);
    assert.equal((await send(dataPort, "/through-proxy")).body.after, ref);
    assert.equal((await send(controlPort, "/fault/release", { method: "POST", body: { id: entry.n + 10000 } })).status, 404);
    assert.equal(settled, false);
    assert.equal((await send(controlPort, "/fault/release", { method: "POST", body: { id: entry.n } })).status, 200);
    assert.equal((await first).error?.code, "ECONNRESET");
    const released = await logEntry("held_response_dropped");
    assert.equal(released.held, entry.n);
    const repeat = await send(dataPort, "/writes/after", { method: "POST", key: "after" });
    assert.equal(repeat.body.id, ref);
    assert.equal((await send(controlPort, "/fault/release", { method: "POST", body: { id: entry.n } })).status, 404);
  });

  await t.test("a client that leaves first cannot be reported as a released hold", async () => {
    const path = "/writes/abandon";
    const invalid = await send(controlPort, "/fault", {
      method: "POST", body: { mode: "hold-response", method: "POST", path: "[" },
    });
    assert.equal(invalid.status, 400);

    await arm("hold-response", path);
    const client = new AbortController();
    const awaiting = send(dataPort, path, { method: "POST", key: "abandon", signal: client.signal })
      .then(() => null, error => error);
    const held = await logEntry("response_held", path);
    client.abort();
    assert.equal((await awaiting).code, "ABORT_ERR");
    assert.equal((await logEntry("response_hold_abandoned", path)).held, held.n);
    assert.equal((await send(controlPort, "/fault/release", { method: "POST", body: { id: held.n } })).status, 404);
  });
});
