import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { existsSync, lstatSync, readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";

export const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
export const sha256File = (path) => sha256(readFileSync(path));
const forbidden = /^(local\.properties|google-services\.json|keystore\.properties|\.env(?:\..*)?)$|\.(?:jks|keystore)$/i;
export function treeFiles(root, directory) {
  const files = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir).sort()) {
      if (forbidden.test(name) || ["node_modules", "__pycache__", ".gradle", "build"].includes(name)) continue;
      const path = join(dir, name), stat = lstatSync(path);
      if (stat.isSymbolicLink()) throw new Error("Source snapshot refuses links: " + relative(root, path));
      if (stat.isDirectory()) walk(path);
      else if (stat.isFile()) files.push({ path: relative(root, path).replace(/\\/g, "/"), sha256: sha256File(path) });
    }
  };
  walk(directory);
  return files;
}
export function sourceSnapshot(repo) {
  const files = ["android/app/src", "backend/app", "webapp/src"].flatMap((path) => treeFiles(repo, join(repo, path)));
  for (const path of ["android/build.gradle.kts", "android/settings.gradle.kts", "android/gradle/libs.versions.toml", "android/app/build.gradle.kts", "webapp/package.json", "webapp/package-lock.json", "webapp/vite.config.ts", "backend/requirements.txt", "backend/pyproject.toml"]) {
    if (existsSync(join(repo, path))) files.push({ path, sha256: sha256File(join(repo, path)) });
  }
  files.sort((a, b) => a.path.localeCompare(b.path));
  const git = (...args) => execFileSync("git", ["-C", repo, ...args], { windowsHide: true }).toString().trim();
  const dirty = git("status", "--porcelain=v1", "--untracked-files=normal", "--", "android", "backend/app", "webapp/src", "webapp/package.json", "webapp/package-lock.json", "webapp/vite.config.ts");
  return { head: git("rev-parse", "HEAD"), files, source_sha256: sha256(JSON.stringify(files)), dirty_snapshot: dirty, dirty_sha256: sha256(dirty) };
}
export function distHash(repo) { return sha256(JSON.stringify(treeFiles(repo, join(repo, "webapp", "dist")))); }
export function assertBinding(binding, snapshot, builtApk, pwaDist, installedApk = builtApk) {
  if (binding.source.source_sha256 !== snapshot.source_sha256) throw new Error("Source changed since the recorded build");
  if (binding.source.head !== snapshot.head || binding.source.dirty_sha256 !== snapshot.dirty_sha256) throw new Error("Git/dirty snapshot changed since the recorded build");
  if (!installedApk || installedApk !== builtApk || binding.apk_sha256 !== builtApk) throw new Error("Installed APK does not match the recorded build");
  if (binding.pwa_dist_sha256 !== pwaDist) throw new Error("PWA assets do not match the recorded build");
}
