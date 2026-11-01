// Shared by the Node drivers and PowerShell launchers; never depend on their current directory.
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const file = fileURLToPath(import.meta.url);
export const REPO = resolve(dirname(file), "..", "..", "..");
export const EVID = join(REPO, "test-results", "release");
// A short, checkout-specific directory also avoids Windows Chromium MAX_PATH problems.
const checkout = createHash("sha256").update(REPO).digest("hex").slice(0, 12);
export const SCRATCH = process.env.RSACC_SCRATCH
  ? resolve(REPO, process.env.RSACC_SCRATCH)
  : join(tmpdir(), "yuldash-e2e", checkout);
export const PROFILE_ROOT = join(SCRATCH, "pw");

// PowerShell reads the same values instead of duplicating machine-specific defaults.
if (process.argv[1] && resolve(process.argv[1]) === file) {
  console.log(JSON.stringify({ repo: REPO, evidence: EVID, scratch: SCRATCH, profiles: PROFILE_ROOT }));
}
