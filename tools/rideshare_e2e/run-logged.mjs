// Run one command, keep its full output, real exit code, timing, git HEAD and public env.
// Usage: node run-logged.mjs <name> <cwd> [--env K=V ...] -- <cmd> [args...]
import { spawn, execFileSync } from "node:child_process";
import { writeFileSync, createWriteStream, mkdirSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import os from "node:os";
import { redact, redactText } from "./lib/redact.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..", "..");
const evid = resolve(process.env.RSACC_EVID || join(repo, "test-results", "release"));
const argv = process.argv.slice(2);
const name = argv.shift();
if (!/^[a-zA-Z0-9_-]+$/.test(name || "")) throw new Error("Use a plain unique command label");
const cwd = resolve(argv.shift());
const env = { ...process.env };
const publicEnv = {};
while (argv[0] === "--env") {
  argv.shift();
  const kv = argv.shift();
  const i = kv.indexOf("=");
  env[kv.slice(0, i)] = kv.slice(i + 1);
  publicEnv[kv.slice(0, i)] = kv.slice(i + 1);
}
if (argv.shift() !== "--") throw new Error("expected --");
const [cmd, ...args] = argv;
const head = execFileSync("git", ["-C", repo, "rev-parse", "HEAD"]).toString().trim();
const dirty = execFileSync("git", ["-C", repo, "status", "--porcelain", "--untracked-files=no"]).toString();
const logPath = join(evid, "logs", `${name}.log`);
mkdirSync(join(evid, "logs"), { recursive: true });
const out = createWriteStream(logPath);
const started = new Date();
out.write(redactText(`# command: ${cmd} ${args.join(" ")}\n# cwd: ${cwd}\n# head: ${head}\n# tracked_dirty: ${dirty ? "yes" : "no"}\n# env: ${JSON.stringify(redact(publicEnv))}\n# started: ${started.toISOString()}\n`));
const child = spawn(cmd, args, { cwd, env, shell: process.platform === "win32", windowsHide: true });
// Buffer whole lines so a phone/link split across stream chunks is still scrubbed.
function safeStream(stream) {
  let pending = "";
  stream.setEncoding("utf8");
  stream.on("data", (chunk) => { pending += chunk; const lines = pending.split(/\n/); pending = lines.pop(); for (const line of lines) out.write(redactText(line) + "\n"); });
  stream.on("end", () => { if (pending) out.write(redactText(pending)); });
}
safeStream(child.stdout); safeStream(child.stderr);
child.on("close", (code, signal) => {
  const finished = new Date();
  out.write(`\n# exit_code: ${code}\n# signal: ${signal}\n# finished: ${finished.toISOString()}\n`);
  out.end(() => {
    const record = {
      name, command: [cmd, ...args].join(" "), cwd, head, tracked_dirty: !!dirty,
      env: publicEnv, host: os.hostname(), started_at: started.toISOString(),
      finished_at: finished.toISOString(), exit_code: code, signal, log: `test-results/release/logs/${name}.log`,
    };
    writeFileSync(join(evid, "logs", `${name}.record.json`), JSON.stringify(redact(record), null, 2));
    console.log(JSON.stringify({ name, exit_code: code, seconds: (finished - started) / 1000 }));
    process.exit(code ?? 1);
  });
});
