import assert from "node:assert/strict";
import { createLoginFlow, filterLoginCode, loginCodeValid, loginPhoneValid, nextNeedPhone } from "../src/utils/loginFlow.js";

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};
const pair = { access_token: "synthetic-access", refresh_token: "synthetic-refresh", user: { id: 1 } };
function fixture(overrides = {}) {
  const events = { start: [], verify: [], smsRequest: [], smsVerify: [], opened: [], errors: [], logins: [], tracks: [], nav: 0, tgIds: [], smsStarted: 0 };
  let generation = "guest";
  const deps = {
    getGeneration: () => generation,
    botAvailable: () => true,
    tgStart: async () => { const id = `id-${events.start.length + 1}`; events.start.push(id); return { request_id: id }; },
    tgVerify: async (id, code) => { events.verify.push([id, code]); return pair; },
    smsRequest: async (phone) => { events.smsRequest.push(phone); },
    smsVerify: async (phone, code, name) => { events.smsVerify.push([phone, code, name]); return pair; },
    login: (access, refresh) => { events.logins.push([access, refresh]); generation = `session-${events.logins.length}`; },
    updateName: async () => {},
    getStatus: (e) => e?.status || 0,
    telegramStartUrl: (id) => `https://t.me/test?start=${id}`,
    telegramChatUrl: () => "https://t.me/test",
    openTelegram: (url) => events.opened.push(url),
    track: (event) => events.tracks.push(event),
    navigate: () => { events.nav++; },
    onBusy: () => {},
    onError: (kind) => events.errors.push(kind),
    onTgStarted: (id) => events.tgIds.push(id),
    onSmsStarted: () => { events.smsStarted++; },
    ...overrides,
  };
  return { events, flow: createLoginFlow(deps), setGeneration: (next) => { generation = next; } };
}

assert.equal(loginPhoneValid("1234"), false);
assert.equal(loginPhoneValid(" 12345 "), true);
assert.equal(loginCodeValid("12345"), false);
assert.equal(loginCodeValid("123456"), true);
assert.equal(loginCodeValid("1234567"), false);
assert.equal(filterLoginCode("a12b345678"), "123456");
console.log("✓ Android phone/code boundaries and six-digit filter");

{
  const start = deferred();
  let starts = 0;
  const t = fixture({ tgStart: () => ++starts === 1 ? start.promise : Promise.resolve({ request_id: "fresh-2" }) });
  const a = t.flow.start();
  await t.flow.start();
  assert.equal(t.flow.busy, true);
  start.resolve({ request_id: "fresh-1" });
  await a;
  await t.flow.openTelegramAgain(false);
  assert.equal(starts, 2);
  assert.deepEqual(t.events.tgIds, ["fresh-1", "fresh-2"]);
  assert.deepEqual(t.events.opened, ["https://t.me/test?start=fresh-1", "https://t.me/test?start=fresh-2"]);
  t.flow.openTelegramAgain(true);
  assert.equal(t.events.opened.at(-1), "https://t.me/test");
  console.log("✓ shared busy, fresh Telegram start, phone-required chat");
}

{
  const start = deferred();
  const t = fixture({ tgStart: () => start.promise });
  const pending = t.flow.start();
  t.flow.invalidate();
  start.resolve({ request_id: "obsolete" });
  await pending;
  assert.equal(t.events.tgIds.length, 0);
  const verify = deferred();
  const v = fixture({ tgVerify: () => verify.promise });
  const waiting = v.flow.verify("id", "123456", "");
  v.setGeneration("other-account");
  verify.resolve(pair);
  await waiting;
  assert.equal(v.events.logins.length, 0);
  assert.equal(v.events.nav, 0);
  v.flow.dispose();
  console.log("✓ late start and cross-account verify cannot commit");
}

{
  const pendingStart = deferred();
  let calls = 0;
  const t = fixture({ tgStart: () => ++calls === 1 ? pendingStart.promise : Promise.resolve({ request_id: "retry" }) });
  const waiting = t.flow.start();
  pendingStart.reject({ status: 503 });
  await waiting;
  assert.equal(t.events.errors.at(-1), "start");
  await t.flow.start();
  assert.deepEqual(t.events.tgIds, ["retry"]);
  const v = deferred();
  const u = fixture({ tgVerify: () => v.promise });
  const late = u.flow.verify("id", "123456", "");
  u.flow.dispose();
  v.resolve(pair);
  await late;
  assert.equal(u.events.logins.length, 0);
  console.log("✓ failed start retries; unmounted verify cannot commit");
}

for (const [status, expected] of [[400, "badTgCode"], [403, "phoneRequired"], [409, "notYet"], [410, "expired"], [429, "tooMany"], [503, "verify"], [0, "verify"]]) {
  const t = fixture({ tgVerify: async () => { throw { status }; } });
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), expected);
}
console.log("✓ distinct Telegram HTTP and offline errors");

{
  let needPhone = false;
  const t = fixture({ onError: (kind) => { needPhone = nextNeedPhone(needPhone, kind); }, tgVerify: async () => { throw { status: 403 }; } });
  await t.flow.verify("id", "123456", "");
  assert.equal(needPhone, true);
  await t.flow.verify("id", "12345", "");
  assert.equal(needPhone, true);
  t.flow.openTelegramAgain(needPhone);
  assert.deepEqual(t.events.opened, ["https://t.me/test"]);
  assert.equal(t.events.tgIds.length, 0);
  console.log("✓ 403 then invalid code preserves phone-required chat without new start");
}

{
  let attempts = 0;
  const t = fixture({ tgVerify: async () => {
    if (++attempts === 1) throw { status: 503 };
    return pair;
  } });
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), "verify");
  await t.flow.verify("id", "123456", "");
  assert.equal(attempts, 2);
  assert.equal(t.events.logins.length, 1);
  assert.equal(t.events.nav, 1);
  console.log("✓ server failure then retry commits once");
}

{
  const t = fixture();
  await t.flow.verify("id", "12345", "");
  assert.equal(t.events.verify.length, 0);
  await t.flow.verify("id", "123456", "Name");
  assert.equal(t.events.nav, 1);
  assert.equal(t.events.tracks.filter((name) => name === "login").length, 1);
  console.log("✓ valid login commits and emits one login event");
}

{
  const update = deferred();
  const t = fixture({ updateName: () => update.promise });
  const waiting = t.flow.verify("id", "123456", "Name");
  await Promise.resolve();
  t.setGeneration("next-account");
  update.resolve();
  await waiting;
  assert.equal(t.events.nav, 0);
  console.log("✓ delayed name update cannot navigate another account");
}

{
  const t = fixture({ login: () => { throw Error("synthetic storage failure"); } });
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), "save");
  assert.equal(t.events.nav, 0);
  assert.equal(t.events.tracks.includes("login"), false);
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), "save");
  await t.flow.start();
  assert.equal(t.events.tgIds.length, 1);
  console.log("✓ failed local save requires fresh code and skips success effects");
}

{
  const t = fixture({ getGeneration: () => { throw Error("synthetic storage read failure"); } });
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), "save");
  assert.equal(t.events.logins.length, 0);
}
{
  let reads = 0;
  const t = fixture({ getGeneration: () => ++reads === 1 ? "guest" : (() => { throw Error("synthetic read failure after response"); })() });
  await t.flow.verify("id", "123456", "");
  assert.equal(t.events.errors.at(-1), "save");
  assert.equal(t.events.logins.length, 0);
}
console.log("✓ generation read failures before and after response fail closed");

{
  let attempts = 0;
  const t = fixture({
    login: () => { attempts++; if (attempts === 1) throw Error("synthetic SMS storage failure"); },
  });
  await t.flow.smsVerify("12345", "123456", "");
  assert.equal(t.events.errors.at(-1), "save");
  await t.flow.smsVerify("12345", "123456", "");
  assert.equal(attempts, 1);
  await t.flow.smsRequest("12345");
  await t.flow.smsVerify("12345", "123456", "");
  assert.equal(attempts, 2);
  console.log("✓ SMS consumed code cannot repeat until a fresh request succeeds");
}

{
  const t = fixture();
  await t.flow.smsRequest("1234");
  assert.equal(t.events.smsRequest.length, 0);
  await t.flow.smsRequest("12345");
  assert.equal(t.events.smsStarted, 1);
  await t.flow.smsVerify("12345", "12345", "n");
  await t.flow.smsVerify("12345", "1234567", "n");
  assert.equal(t.events.smsVerify.length, 0);
  await t.flow.smsVerify("12345", "123456", "n".repeat(121));
  assert.equal(t.events.smsVerify[0][2].length, 120);
  console.log("✓ SMS validation, capped name and shared operation");
}

{
  const request = deferred();
  const t = fixture({ smsRequest: () => request.promise });
  const waiting = t.flow.smsRequest("12345");
  await t.flow.start();
  t.flow.invalidate();
  request.resolve();
  await waiting;
  assert.equal(t.events.smsStarted, 0);
  const verify = deferred();
  const u = fixture({ smsVerify: () => verify.promise });
  const late = u.flow.smsVerify("12345", "123456", "");
  u.flow.invalidate();
  verify.resolve(pair);
  await late;
  assert.equal(u.events.logins.length, 0);
  console.log("✓ SMS shares busy and ignores changed-phone late responses");
}
