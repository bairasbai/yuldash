// Shared stand helpers: paths, step log, API, read-only SQLite, Telegram-bot login imitation.
import { appendFileSync, mkdirSync, existsSync, readFileSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";

const here = dirname(fileURLToPath(import.meta.url));
// tools/rideshare_e2e/lib → repo root; evidence stays in the git-ignored test-results/release.
export const REPO = resolve(here, "..", "..", "..");
export const EVID = join(REPO, "test-results", "release");
// Local, machine-specific stand data (synthetic DB) — never evidence, never git.
export const SCRATCH = process.env.RSACC_SCRATCH || "C:/Users/Bayra/AppData/Local/Temp/claude/C--Users-Bayra-Yuldash--claude-worktrees-yuldash-tili-project-0e3eaf/c21faf18-50fb-48f8-8abb-0af213908b73/scratchpad/rsacc-20261024";
const CUR = join(EVID, "current-db.txt");
export const DB_PATH = process.env.STAND_DB || (existsSync(CUR) ? readFileSync(CUR, "utf8").trim() : join(SCRATCH, "stand", "stand.db"));
export const API = "http://127.0.0.1:8010"; // harness/bot talk to the API directly, clients go via the wire proxy :8000
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let runLog = null;
export function setRunLog(path) { mkdirSync(dirname(path), { recursive: true }); runLog = path; }
const SECRET_KEYS = /token|code|phone|authorization|lat|lng/i;
function redact(v) {
  if (v == null || typeof v !== "object") return v;
  if (Array.isArray(v)) return v.map(redact);
  const o = {};
  for (const [k, x] of Object.entries(v)) o[k] = SECRET_KEYS.test(k) && typeof x !== "object" && k !== "exit_code" ? "<redacted>" : redact(x);
  return o;
}
export function log(step, data = {}) {
  const line = JSON.stringify({ at: new Date().toISOString(), step, ...redact(data) });
  if (runLog) appendFileSync(runLog, line + "\n");
  if (!process.env.QUIET) console.log(line);
}

export async function api(path, { method = "GET", body, token, headers = {} } = {}) {
  const h = { "Content-Type": "application/json", ...headers };
  if (token) h.Authorization = "Bearer " + token;
  const r = await fetch(API + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await r.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: r.status, json, text };
}

export function db() { return new DatabaseSync(DB_PATH, { readOnly: true }); }
export function q(sql, ...params) { const d = db(); try { return d.prepare(sql).all(...params); } finally { d.close(); } }
export function q1(sql, ...params) { return q(sql, ...params)[0]; }

/** Wait for a NEW TgAuth row created by the client (after `afterId`), imitate the bot. */
export async function tgBotLogin({ telegramId, firstName, phone, afterId = 0, timeout = 30000 }) {
  const t0 = Date.now();
  let row;
  while (Date.now() - t0 < timeout) {
    row = q1("SELECT id, request_id, status FROM tgauth WHERE id > ? AND status='waiting' ORDER BY id DESC LIMIT 1", afterId);
    if (row) break;
    await sleep(300);
  }
  if (!row) throw new Error("client did not start a Telegram login (no new tgauth row)");
  const chat = { id: telegramId, type: "private" };
  const from = { id: telegramId, first_name: firstName, username: "" };
  const start = await api("/telegram/webhook", { method: "POST", body: { update_id: Date.now(), message: { text: `/start ${row.request_id}`, from, chat } } });
  const m = /(\d{6})/.exec(start.json?.text || "");
  if (start.status !== 200 || !m) throw new Error("bot did not issue a code: " + start.status);
  const contact = await api("/telegram/webhook", { method: "POST", body: { update_id: Date.now() + 1, message: { contact: { user_id: telegramId, phone_number: phone, first_name: firstName }, from, chat } } });
  if (contact.status !== 200 || !/Номер сохранён/.test(contact.json?.text || "")) throw new Error("bot did not accept own contact");
  log("tg.bot", { tgauth_id: row.id, start_status: start.status, contact_status: contact.status });
  return { code: m[1], tgauthId: row.id };
}
export function lastTgAuthId() { return q1("SELECT COALESCE(MAX(id),0) AS m FROM tgauth").m; }
