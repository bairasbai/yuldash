// Opt-in QA only. By default test real HTTP; --browser additionally requires
// a pinned build, installed Playwright/browser, and the Chromium sandbox.
import http from "node:http";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { mkdtemp, readFile, readdir, realpath, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { extname, isAbsolute, join, sep } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = fileURLToPath(new URL("../../", import.meta.url));
const host = "127.0.0.1", fixturePort = 8000, port = 4175;
const origin = `http://${host}:${port}`, api = "/__qa_api";
const quote = "/instant/quotes/v1", confirm = "/instant/orders/confirmed/v1";
const result = "/instant/orders/result/v1", history = "/instant/orders/mine?limit=5";
const post = new Set([quote, confirm, result, "/instant/estimate", "/instant/orders",
  "/instant/orders/close-attempt/v1", "/places/recent"]);
const types = { ".js": "text/javascript", ".css": "text/css", ".woff2": "font/woff2",
  ".webp": "image/webp", ".svg": "image/svg+xml", ".png": "image/png",
  ".ico": "image/x-icon", ".html": "text/html" };
const csp = "default-src 'self'; connect-src 'self'; img-src 'self' data: blob:; " +
  "style-src 'self' 'unsafe-inline'; worker-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'";
const expect = (v) => { if (!v) throw new Error("QA assertion failed"); };
const keep = (headers, fields) => Object.fromEntries(
  fields.filter((k) => headers[k] !== undefined).map((k) => [k, headers[k]]));
let stage = "preflight";
let browser, server, child, exited, scratch, closingBrowser;
let signalCode;
const stopBrowser = () => browser && (closingBrowser ??= browser.close());
const snapshot = async (name = "state") => JSON.parse(await readFile(join(scratch, name), "utf8"));
const cleanEnv = () => ({ PATH: "/usr/bin:/bin", HOME: scratch, TMPDIR: scratch, LANG: "C.UTF-8" });

function relay(req, res, target) {
  const path = target.pathname.slice(api.length);
  const allowedPost = post.has(path) || /^\/places\/saved\/[1-9][0-9]*\/used$/.test(path);
  if (!(req.method === "GET" || (req.method === "POST" && allowedPost))) {
    return res.writeHead(405).end();
  }
  const len = Number(req.headers["content-length"] ?? "0");
  if (req.headers["transfer-encoding"] || !Number.isSafeInteger(len) || len < 0 || len > 65536) {
    return res.writeHead(413).end();
  }
  // Keep confirmation transport loss as transport loss, never a response/retry.
  const hop = http.request({ host, port: fixturePort, method: req.method, path: path + target.search,
    headers: keep(req.headers, ["authorization", "content-type", "content-length"]), agent: false }, (r) => {
    if (r.statusCode >= 300 && r.statusCode < 400) { r.resume(); res.destroy(); return; }
    res.writeHead(r.statusCode, keep(r.headers, ["content-type", "content-length", "cache-control"]));
    r.on("error", () => res.destroy());
    r.pipe(res);
  });
  hop.once("error", () => res.destroy());
  hop.setTimeout(8000, () => hop.destroy());
  req.once("error", () => hop.destroy());
  res.once("close", () => hop.destroy());
  req.pipe(hop);
}

async function respond(req, res, dist) {
  const raw = req.url || "";
  const rawPath = raw.split("?")[0];
  if (req.headers.host !== `${host}:${port}` || rawPath.startsWith("//") ||
      !/^\/[a-zA-Z0-9_./-]*$/.test(rawPath) || raw.includes("#")) return res.writeHead(400).end();
  const target = new URL(raw, origin);
  if (target.pathname !== rawPath) return res.writeHead(400).end();
  if (rawPath.startsWith(api + "/")) return relay(req, res, target);
  if (req.method !== "GET") return res.writeHead(405).end();
  const headers = { "content-security-policy": csp, "cache-control": "no-store" };
  if (rawPath === "/__qa_session") {
    return res.writeHead(200, { ...headers, "content-type": "text/html" }).end("<!doctype html><title>QA</title>");
  }
  const pathname = rawPath === "/taxi" ? "/index.html" : rawPath;
  if (!dist || !types[extname(pathname)]) return res.writeHead(404).end();
  const file = await realpath(join(dist, pathname)).catch(() => "");
  if (!file.startsWith(dist + sep)) return res.writeHead(404).end();
  const body = await readFile(file);
  res.writeHead(200, { ...headers, "content-type": types[extname(pathname)] }).end(body);
}

function validateOrder(receipt, card, id) {
  expect(receipt.state === "committed" && receipt.next === "fetch_order" &&
    receipt.initial_payable_kop === 45000 && receipt.order_id === id);
  expect(card.id === id && card.status === "expired" && card.passenger_price_kop === 45000);
}

function validateCounts(s, id, lookup) {
  expect(s.order_id === id && s.orders === 1 && s.confirm === 1 && s.result === lookup &&
    s.close === 0 && s.legacy === 0 && s.attempts.length === 1 && s.attempts[0] === "committed");
}

async function httpCase(session) {
  const request = (path, body) => fetch(origin + api + path, {
    redirect: "error", signal: AbortSignal.timeout(12000), method: body ? "POST" : "GET",
    headers: { authorization: "Bearer " + session.access, ...(body ? { "content-type": "application/json" } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const json = async (...args) => {
    const r = await request(...args);
    expect(r.status === 200);
    return r.json();
  };
  const body = { from_lat: 52.591, from_lng: 58.317, to_lat: 52.716, to_lng: 58.664,
    from_text: "QA Origin", to_text: "QA Destination" };
  stage = "HTTP quote";
  expect((await json("/me")).id === session.uid);
  const q = await json(quote, body);
  expect(q.price_kop === 60000 && q.promo_discount_kop === 15000 && q.payable_kop === 45000);
  const before = await snapshot();
  expect(before.quote === 1 && before.confirm === 0 && before.orders === 0 && before.legacy === 0);
  stage = "HTTP lost response";
  const dropped = await request(confirm, { ...body, quote_id: q.quote_id, request_key: q.request_key })
    .then(() => false, (e) => e?.cause?.code === "UND_ERR_SOCKET");
  expect(dropped);
  const committed = await snapshot(), id = committed.order_id;
  expect(Number.isSafeInteger(id) && id > 0 && committed.order_status === "offered");
  validateCounts(committed, id, 0);
  stage = "HTTP explicit result and history";
  const receipt = await json(result, { request_key: q.request_key });
  const card = await json("/instant/orders/" + id), mine = await json(history);
  validateOrder(receipt, card, id);
  expect(mine.length === 1 && mine[0].id === id && mine[0].passenger_price_kop === 45000);
  const after = await snapshot();
  validateCounts(after, id, 1);
  expect(after.card === 1);
}

async function browserCase(session) {
  const context = await browser.newContext({
    locale: "ru-RU", colorScheme: "light", viewport: { width: 390, height: 844 },
    geolocation: { latitude: 52.591, longitude: 58.317 }, permissions: ["geolocation"], serviceWorkers: "block",
  });
  let blocked = 0;
  await context.route("**/*", (route) => {
    if (new URL(route.request().url()).origin === origin) return route.continue();
    blocked++;
    return route.abort("blockedbyclient");
  });
  await context.routeWebSocket("**/*", (ws) => { blocked++; ws.close(); });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  stage = "browser synthetic session";
  await page.goto(origin + "/__qa_session");
  await page.evaluate(({ generation, access, refresh }) => {
    localStorage.setItem("yuldash.lang", "ru");
    localStorage.setItem("yuldash.session", generation);
    localStorage.setItem("yuldash.token", access);
    localStorage.setItem("yuldash.refresh", refresh);
  }, { generation: randomUUID(), access: session.access, refresh: session.refresh });

  stage = "browser protected fare";
  await page.goto(origin + "/taxi");
  await page.getByRole("textbox", { name: "Куда едем", exact: true }).click();
  await page.getByRole("button", { name: "QA Destination", exact: true }).click();
  const summary = page.locator(".taxi-consent-summary");
  for (const fare of [/^Поездка 600\s*₽$/u, /^Скидка −150\s*₽$/u, /^Итого 450\s*₽$/u]) {
    await summary.getByText(fare).waitFor();
  }
  const initial = await snapshot();
  expect(initial.quote > 0 && initial.confirm === 0 && initial.legacy === 0 && initial.orders === 0);
  await page.getByRole("button", { name: /^Заказать за 450\s*₽$/u }).click();

  stage = "browser lost response";
  const matches = (req, method, path) => req.method() === method && req.url() === origin + api + path;
  const review = page.getByRole("alertdialog", { name: "Подтверди итог" });
  await Promise.all([
    page.waitForEvent("requestfailed", { predicate: (r) => matches(r, "POST", confirm) }),
    review.getByRole("button", { name: /^Подтвердить 450\s*₽$/u }).click(),
  ]);
  const lookup = page.getByRole("button", { name: "Проверить результат заказа", exact: true });
  await lookup.waitFor();
  const preReload = await snapshot(), id = preReload.order_id;
  expect(Number.isSafeInteger(id) && id > 0 && preReload.order_status === "offered");
  validateCounts(preReload, id, 0);
  stage = "browser reload and explicit lookup";
  await page.reload(); // Do not re-seed session or marker.
  await lookup.waitFor();
  validateCounts(await snapshot(), id, 0);
  const [receipt, card] = await Promise.all([
    page.waitForResponse((r) => matches(r.request(), "POST", result)),
    page.waitForResponse((r) => matches(r.request(), "GET", "/instant/orders/" + id)),
    lookup.click(),
  ]);
  expect(receipt.status() === 200 && card.status() === 200);
  validateOrder(await receipt.json(), await card.json(), id);
  await page.getByRole("heading", { name: "Рядом пока никого" }).waitFor();
  const line = page.locator(".taxi-fare-line");
  await line.getByText("Сумма в заказе", { exact: true }).waitFor();
  await line.getByText(/^450\s*₽$/u).waitFor();
  const recovered = await snapshot();
  validateCounts(recovered, id, 1);
  expect(recovered.last_card_status === "expired" && blocked === 0);
}

async function launchBrowser() {
  expect(typeof process.getuid === "function" && process.getuid() !== 0);
  stage = "pinned browser build preflight";
  const { QA_PLAYWRIGHT_MODULE: mod, QA_CHROMIUM: exe } = process.env;
  expect(mod && exe && isAbsolute(mod) && isAbsolute(exe));
  const dist = await realpath(join(root, "webapp", "dist"));
  const dir = join(dist, "assets");
  const js = (await readdir(dir, { withFileTypes: true })).filter((x) => x.isFile() && x.name.endsWith(".js"));
  let pinned = false;
  for (const x of js) if ((await readFile(join(dir, x.name), "utf8")).includes(origin + api)) pinned = true;
  expect(pinned);
  stage = "sandboxed Chromium startup";
  const { chromium } = await import(pathToFileURL(mod).href);
  browser = await chromium.launch({ headless: true, chromiumSandbox: true, executablePath: exe,
    handleSIGINT: false, handleSIGTERM: false, timeout: 12000, env: cleanEnv(), args: ["--disable-background-networking", "--no-proxy-server"] });
  return dist;
}

async function mount() {
  const interfaces = (await readFile("/proc/net/dev", "utf8")).split("\n")
    .filter((line) => line.includes(":")).map((line) => line.split(":")[0].trim());
  // Host/CSP/request interception is defense in depth, not network isolation.
  expect(interfaces.length === 1 && interfaces[0] === "lo");
  const python = process.env.QA_PYTHON;
  expect(python && isAbsolute(python));
  const withBrowser = process.argv.length === 3 && process.argv[2] === "--browser";
  expect(withBrowser || process.argv.length === 2);
  scratch = await mkdtemp(join(tmpdir(), "taxi-recovery-qa-"));
  process.env.TMPDIR = scratch;
  const dist = withBrowser ? await launchBrowser() : null;
  expect(!signalCode);
  stage = "private fixture startup";
  server = http.createServer((req, res) => { void respond(req, res, dist).catch(() => res.destroy()); });
  server.listen({ host, port, exclusive: true });
  await once(server, "listening");
  expect(!signalCode);
  const fixture = join(root, "tools/taxi_consent_emulator/fixture.py");
  child = spawn(python, [fixture, "--case", "committed", "--session-file", join(scratch, "session"),
    "--state-file", join(scratch, "state")], {
    cwd: scratch, stdio: "ignore",
    env: { ...cleanEnv(), PYTHONDONTWRITEBYTECODE: "1", TAXI_EXTERNAL_PRICING_ENABLED: "false" },
  });
  let ended = false;
  exited = new Promise((done) => {
    const exit = () => { ended = true; done(); };
    child.once("error", exit);
    child.once("close", exit);
  });
  for (let i = 0; i < 200 && !ended && !signalCode; i++) {
    const session = await snapshot("session").catch((e) => { if (e?.code !== "ENOENT") throw e; });
    if (session) {
      await (withBrowser ? browserCase(session) : httpCase(session));
      return withBrowser;
    }
    await sleep(100);
  }
  throw new Error("fixture did not start");
}

async function release() {
  try { await stopBrowser(); } finally {
    try {
      if (server?.listening) {
        const stop = new Promise((done) => server.close(done));
        server.closeAllConnections();
        await stop;
      }
    } finally {
      try {
        if (child) {
          child.kill("SIGTERM");
          if (!(await Promise.race([exited.then(() => true), sleep(2500).then(() => false)]))) {
            child.kill("SIGKILL");
            await exited;
            throw new Error("fixture cleanup timed out");
          }
        }
      } finally { if (scratch) await rm(scratch, { recursive: true, force: true }); }
    }
  }
}

for (const [signal, code] of [["SIGINT", 130], ["SIGTERM", 143]]) {
  process.once(signal, () => {
    signalCode = code;
    child?.kill("SIGTERM");
    stopBrowser()?.catch(() => {});
  });
}

let passed;
try { passed = await mount(); }
catch {
  // Never log exception payloads, routes, tokens, browser traces or DB rows.
  console.error("FAIL: " + stage);
  process.exitCode = 1;
} finally {
  try { await release(); }
  catch { console.error("FAIL: private resource cleanup"); process.exitCode = 1; }
}
if (signalCode) process.exitCode = signalCode;
if (!process.exitCode) console.log(passed
  ? "PASS RU Chromium: protected fare, lost response, reload/manual result, same order, no legacy/duplicate"
  : "PASS HTTP only: 60000-15000=45000; response lost after commit; manual result/card/history same order; no legacy/duplicate");
