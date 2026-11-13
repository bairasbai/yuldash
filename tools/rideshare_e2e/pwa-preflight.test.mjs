import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkPwaStand } from "./lib/pwa-preflight.mjs";

test("checks one fresh stand and refuses wrong, unready or unverified backends", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "yuldash-preflight-"));
  t.after(() => rm(directory, { recursive: true }));
  const database = join(directory, "test.sqlite");
  await writeFile(database, "");
  const runId = "a".repeat(32);
  let readyStatus = 200;
  let ready = true;
  let redirect = null;
  const requests = [];
  const respond = (id) => (req, res) => {
    requests.push(req.url);
    res.setHeader("content-type", "application/json");
    if (req.url === "/health" && redirect) {
      res.writeHead(302, { location: redirect }).end();
    } else if (req.url === "/health") {
      res.end(JSON.stringify({ env: "dev", db: "ok", payments: "off", fcm: "not" }));
    } else if (req.url === "/health/ready") {
      res.writeHead(readyStatus).end(JSON.stringify({ db: ready ? "ok" : "fail", ready }));
    } else {
      res.end(JSON.stringify({ run_id: id, database, sms: "mock", external: "blocked" }));
    }
  };
  const origin = async (callback) => {
    const server = createServer(callback);
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    t.after(() => new Promise(resolve => server.close(resolve)));
    return `http://127.0.0.1:${server.address().port}`;
  };
  const api = await origin(respond(runId));
  const direct = await origin(respond(runId));
  let otherHits = 0;
  const other = await origin((req, res) => { ++otherHits; respond("b".repeat(32))(req, res); });
  const web = "http://127.0.0.1:4173";
  const preflight = (supplied = api, backend = direct, file = database) =>
    checkPwaStand(web, api, supplied, backend, file);
  assert.equal((await preflight()).runId, runId);
  const started = requests.length;
  await assert.rejects(preflight("https://example.invalid"), /explicit local origin/);
  await assert.rejects(preflight(""), /explicit local origin/);
  await assert.rejects(preflight(other), /does not match/);
  assert.equal(requests.length, started);
  await assert.rejects(preflight(api, other), /do not use the selected test database/);
  const wrongDb = join(directory, "wrong.sqlite");
  await writeFile(wrongDb, "");
  await assert.rejects(preflight(api, direct, wrongDb), /do not use the selected test database/);
  readyStatus = 503;
  await assert.rejects(preflight(), /did not return 200/);
  readyStatus = 200;
  ready = false;
  await assert.rejects(preflight(), /database is not ready/);
  const previousHits = otherHits;
  redirect = other + "/outside";
  await assert.rejects(preflight(), /fetch failed/);
  assert.equal(otherHits, previousHits);
});
