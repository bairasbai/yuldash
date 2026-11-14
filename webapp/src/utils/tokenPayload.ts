export function tokenPayload(token: string | null): { sub?: unknown; sid?: unknown } | undefined {
  if (!token) return;
  try {
    const payload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(payload));
  } catch { return undefined; }
}
