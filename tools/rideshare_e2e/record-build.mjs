// Before the two builds: node record-build.mjs before
// After successful assembleDebug + PWA build: node record-build.mjs after
// No shared process starts here. The source snapshot and output timestamps must agree.
import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, extname, join, resolve } from "node:path";
import { EVID, REPO } from "./lib/common.mjs";
import { sourceSnapshot, sha256File, distHash } from "./lib/provenance.mjs";
const mode = process.argv[2];
if (!["before", "after"].includes(mode)) throw new Error("Expected before|after");
mkdirSync(EVID, { recursive: true });
const path = resolve(process.env.RSACC_BUILD_INPUTS || join(EVID, "build-inputs.json"));
const bindingPath = resolve(process.env.RSACC_BUILD_BINDING || join(EVID, "build-binding.json"));
for (const output of [path, bindingPath]) {
  if (dirname(output).toLowerCase() !== resolve(EVID).toLowerCase() || extname(output) !== ".json") throw new Error("Build records must be JSON directly in this evidence directory");
}
if (path.toLowerCase() === bindingPath.toLowerCase()) throw new Error("Input and binding records need distinct paths");
if (mode === "before") {
  writeFileSync(path, JSON.stringify({ at: new Date().toISOString(), source: sourceSnapshot(REPO) }, null, 2), { flag: "wx" });
} else {
  const before = JSON.parse(readFileSync(path, "utf8")), source = sourceSnapshot(REPO);
  if (JSON.stringify(before.source) !== JSON.stringify(source)) throw new Error("Source/Git changed during build; record a new before snapshot and rebuild");
  const apk = join(REPO, "android/app/build/outputs/apk/debug/app-debug.apk");
  const index = join(REPO, "webapp/dist/index.html");
  const started = Date.parse(before.at);
  // Gradle may reuse an unchanged APK; in that case require an explicit root-owned build receipt.
  const receipts = [process.env.RSACC_ANDROID_BUILD_RECEIPT, process.env.RSACC_PWA_BUILD_RECEIPT];
  if (receipts.some((p) => !p)) throw new Error("Set RSACC_ANDROID_BUILD_RECEIPT and RSACC_PWA_BUILD_RECEIPT to successful command record JSON files");
  const records = receipts.map((p) => {
    const record = JSON.parse(readFileSync(p, "utf8"));
    return { ...record, started_at: record.started_at ?? record.created_at, command: Array.isArray(record.command) ? record.command.join(" ") : record.command };
  });
  for (const record of records) {
    if (record.exit_code !== 0 || Date.parse(record.started_at) < started || !record.finished_at) throw new Error("Build receipt does not prove a successful build after the input snapshot");
  }
  if (!/:app:assembleDebug\b/.test(records[0].command || "") || !/\bbuild\b/.test(records[1].command || "")) throw new Error("Receipts must be the Android assembleDebug and PWA build commands");
  if (records[0].cwd.replace(/\\/g, "/").toLowerCase() !== join(REPO, "android").replace(/\\/g, "/").toLowerCase() || records[1].cwd.replace(/\\/g, "/").toLowerCase() !== join(REPO, "webapp").replace(/\\/g, "/").toLowerCase()) throw new Error("Build receipts belong to another checkout");
  if (statSync(index).mtimeMs < started) throw new Error("PWA output predates this build");
  const binding = { source, started_at: before.at, finished_at: new Date().toISOString(), apk_sha256: sha256File(apk), pwa_dist_sha256: distHash(REPO), build_receipts: receipts.map((p) => ({ path: p, sha256: sha256File(p) })) };
  writeFileSync(bindingPath, JSON.stringify(binding, null, 2), { flag: "wx" });
}
console.log("Build " + mode + " recorded");
