import { realpathSync } from "node:fs";

const featuresPath = "/config/features";

function localOrigin(value, label) {
  let url;
  try { url = new URL(value); } catch { throw new Error(`${label} must be an explicit local origin`); }
  if (url.protocol !== "http:" || url.hostname !== "127.0.0.1" || !url.port ||
      url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error(`${label} must be an explicit local origin`);
  }
  return url.origin;
}

export async function checkPwaStand(site, expectedApi, suppliedApi, backend, database) {
  const web = localOrigin(site, "PWA");
  const api = localOrigin(suppliedApi, "VITE_API_BASE");
  if (api !== localOrigin(expectedApi, "Stand proxy") || api === web) {
    throw new Error("VITE_API_BASE does not match the stand proxy");
  }
  const direct = localOrigin(backend, "Stand backend");
  if (!database) throw new Error("The test SQLite file must be specified");
  const expectedDatabase = realpathSync(database);
  const get = async (path, origin = api) => {
    const response = await fetch(origin + path, { redirect: "error", signal: AbortSignal.timeout(5000) });
    if (response.status !== 200) throw new Error(`Stand ${path} did not return 200`);
    return response.json();
  };
  const health = await get("/health");
  if (health?.env !== "dev" || health.db !== "ok" || health.payments !== "off" || health.fcm !== "not") {
    throw new Error("Stand is not a ready development server without live providers");
  }
  const ready = await get("/health/ready");
  if (ready?.ready !== true || ready.db !== "ok") throw new Error("Stand database is not ready");
  const proof = await get("/__qa/stand");
  const upstream = await get("/__qa/stand", direct);
  if (!/^[a-f0-9]{32}$/.test(proof?.run_id ?? "") || proof.run_id !== upstream?.run_id ||
      proof.sms !== "mock" || upstream.sms !== "mock" || proof.external !== "blocked" || upstream.external !== "blocked" ||
      realpathSync(proof.database) !== expectedDatabase || realpathSync(upstream.database) !== expectedDatabase) {
    throw new Error("The browser proxy and the test runner do not use the selected test database");
  }
  return {
    web, api, ws: api.replace(/^http:/, "ws:"), runId: proof.run_id,
    telegramMock: proof.telegram_mock === true && upstream.telegram_mock === true,
  };
}

// Register both policies before creating the probe page. Service workers must be blocked
// in the context, because requests handled by a worker do not reach HTTP routing.
export async function verifyPwaClient(ctx, { web, api, ws, runId }, external) {
  let authorized = false;
  await ctx.routeWebSocket((url) => ["ws:", "wss:"].includes(url.protocol), (socket) => {
    const origin = new URL(socket.url()).origin;
    if (authorized && origin === ws) socket.connectToServer();
    else {
      external.push(origin);
      return socket.close();
    }
  });
  await ctx.route((url) => ["http:", "https:"].includes(url.protocol), async (route) => {
    const request = route.request();
    const { origin, pathname } = new URL(request.url());
    const read = request.method() === "GET" || request.method() === "HEAD";
    if (origin !== web && origin !== api) {
      external.push(origin);
      return route.abort();
    }
    if (!authorized && !(origin === web && read) &&
        !(origin === api && pathname === featuresPath && request.method() === "GET")) return route.abort();
    if (!authorized && origin === api && request.headers().authorization) return route.abort();

    // Playwright does not route every hop of an HTTP redirect. Fetch exactly one
    // hop and never deliver a network redirect that could leave the allowed origins.
    try {
      const response = await route.fetch({ maxRedirects: 0, maxRetries: 0, timeout: 15000 });
      if (response.status() >= 300 && response.status() < 400 && response.status() !== 304) return route.abort();
      await route.fulfill({ response });
    } catch {
      return route.abort().catch(() => {});
    }
  });

  const page = await ctx.newPage();
  try {
    const probe = await ctx.newCDPSession(page);
    await probe.send("Network.setBypassServiceWorker", { bypass: true });
    await page.addInitScript(() => {
      try { localStorage.removeItem("yuldash.features"); } catch {}
    });
    const result = page.waitForResponse(
      (response) => new URL(response.url()).pathname === featuresPath &&
        response.request().method() === "GET", { timeout: 12000 },
    );
    const [navigation, response] = await Promise.all([
      page.goto(web, { waitUntil: "domcontentloaded", timeout: 12000 }), result,
    ]);
    const cors = await response.headerValue("access-control-allow-origin");
    const source = await response.headerValue("x-yuldash-qa-run");
    const features = await response.json();
    if (navigation?.status() !== 200 || new URL(response.url()).origin !== api || response.status() !== 200 ||
        source !== runId || (cors !== web && cors !== "*") ||
        features === null || typeof features !== "object" || Array.isArray(features)) {
      throw new Error("Built PWA did not query the selected stand");
    }
    authorized = true;
  } finally {
    await page.close();
  }
}
