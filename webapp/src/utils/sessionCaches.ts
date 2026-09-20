/** Keep only public build files; even a precache may contain old private entries. */
export async function clearSessionCaches(isCurrent: () => boolean): Promise<void> {
  if (typeof caches === "undefined") return;
  const names = await caches.keys();
  if (!isCurrent()) return;
  const scope = new URL(import.meta.env.BASE_URL, window.location.origin);
  for (const name of names) {
    if (!isCurrent()) return;
    if (name !== `workbox-precache-v2-${scope.href}`) {
      await caches.delete(name);
      continue;
    }
    const cache = await caches.open(name);
    const requests = await cache.keys();
    for (const request of requests) {
      if (!isCurrent()) return;
      const url = new URL(request.url);
      const relative = url.pathname.startsWith(scope.pathname)
        ? url.pathname.slice(scope.pathname.length) : "";
      const publicFile = relative === "index.html" ||
        /^assets\/[^/]+\.(js|css|woff2|png|webp|svg|ico)$/.test(relative);
      const publicQuery = [...url.searchParams.keys()].every(key => key === "__WB_REVISION__");
      if (url.origin !== scope.origin || !publicFile || !publicQuery ||
          request.method !== "GET" || request.headers.has("Authorization")) {
        await cache.delete(request);
      }
    }
  }
}
