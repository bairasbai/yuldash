// Only the task's explicitly registered root may have a same-file OS path alias.
import { lstatSync, realpathSync } from "node:fs";
import { resolve, relative, dirname, join, isAbsolute, sep } from "node:path";

const normalized = path => resolve(path).toLowerCase();
export function registeredPathContains(path, root) {
  const suffix = relative(resolve(root), resolve(path));
  return suffix !== ".." && !suffix.startsWith(".." + sep) && !isAbsolute(suffix);
}

export function assertRegisteredOrdinaryPath(path, registeredRoot, {
  lstat = value => lstatSync(value, { bigint: true }),
  realpath = value => realpathSync.native(value),
} = {}) {
  const raw = resolve(path), root = resolve(registeredRoot);
  if (!registeredPathContains(raw, root)) throw new Error("Path is outside the fixed registered root");
  let rawLeaf;
  for (let current = raw; ; current = dirname(current)) {
    const info = lstat(current);
    if (info.isSymbolicLink()) throw new Error("Registered raw path contains a link");
    if (current === raw) rawLeaf = info;
    if (dirname(current) === current) break;
  }
  const canonical = resolve(realpath(raw));
  const expected = resolve(join(realpath(root), relative(root, raw)));
  if (normalized(canonical) !== normalized(expected)) throw new Error("Registered child was redirected outside its expected alias mapping");
  let canonicalLeaf;
  for (let current = canonical; ; current = dirname(current)) {
    const info = lstat(current);
    if (info.isSymbolicLink() || normalized(realpath(current)) !== normalized(current)) throw new Error("Canonical registered path contains a link or redirect");
    if (current === canonical) canonicalLeaf = info;
    if (dirname(current) === current) break;
  }
  if (![rawLeaf.dev, rawLeaf.ino, canonicalLeaf.dev, canonicalLeaf.ino].every(value => typeof value === "bigint") ||
      rawLeaf.ino === 0n || canonicalLeaf.ino === 0n || rawLeaf.dev !== canonicalLeaf.dev || rawLeaf.ino !== canonicalLeaf.ino) {
    throw new Error("Registered raw and canonical paths are not the same identifiable file");
  }
  return canonical;
}
