// Physical namespaces, not read-then-delete owner checks: cleanup of A can never
// remove B's replacement of the same logical key. Capture owner BEFORE async work.
import { getSessionGeneration } from "../api/client";
export const OWNED_PREFIX = "yuldash.owner.";
export function ownerPrefix(owner: string): string { return OWNED_PREFIX + encodeURIComponent(owner) + ":"; }
export function captureOwner(): string | null {
  try { return getSessionGeneration(); } catch { return null; }
}

export function ownedStorage(owner: string | null): Pick<Storage, "getItem" | "setItem" | "removeItem"> {
  if (owner === null) return { getItem: () => null, setItem: () => {}, removeItem: () => {} };
  const prefix = ownerPrefix(owner);
  return {
    getItem: key => {
      if (owner !== getSessionGeneration()) return null;
      const value = localStorage.getItem(prefix + key);
      return owner === getSessionGeneration() ? value : null;
    },
    setItem: (key, value) => {
      if (owner !== getSessionGeneration()) return;
      localStorage.setItem(prefix + key, value);
      // A can become inactive during the individual storage write. Its physical
      // key is disjoint from B, so a best-effort late cleanup is safe.
      if (owner !== getSessionGeneration()) localStorage.removeItem(prefix + key);
    },
    removeItem: key => localStorage.removeItem(prefix + key),
  };
}

export function clearOwnedStorage(owner: string, logicalPrefix = ""): void {
  const prefix = ownerPrefix(owner) + logicalPrefix;
  const keys: string[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (key?.startsWith(prefix)) keys.push(key);
  }
  for (const key of keys) localStorage.removeItem(key);
}
