export function nextNeedPhone(current, error) {
  if (error === "phoneRequired") return true;
  if (["badTgCode", "notYet", "expired", "tooMany", "verify", "save"].includes(error)) return false;
  return current;
}

export const loginPhoneValid = (phone) => phone.trim().length >= 5;
export const loginCodeValid = (code) => /^\d{6}$/.test(code.trim());
export const filterLoginCode = (value) => value.replace(/\D/g, "").slice(0, 6);

/** One operation owns both login methods and every result is bound to its starting session. */
export function createLoginFlow(deps) {
  let serial = 0;
  let busy = false;
  let disposed = false;
  let freshCodeRequired = false;
  const generation = () => {
    try { return deps.getGeneration(); } catch { return null; }
  };
  const begin = () => {
    if (busy || disposed) return null;
    const startedGeneration = generation();
    if (startedGeneration === null) { deps.onError("save"); return null; }
    busy = true;
    return { id: ++serial, generation: startedGeneration };
  };
  const current = (op) => {
    const now = generation();
    if (now === null && !disposed && op.id === serial) {
      freshCodeRequired = true;
      deps.onError("save");
    }
    return !disposed && now !== null && op.id === serial && op.generation === now;
  };
  const finish = (op) => {
    if (op.id === serial) {
      busy = false;
      if (!disposed) deps.onBusy(false);
    }
  };
  const invalidate = () => {
    serial++;
    busy = false;
    if (!disposed) deps.onBusy(false);
  };
  const saveAndContinue = async (op, pair, method, name = "") => {
    if (!current(op)) return;
    try {
      deps.login(pair.access_token, pair.refresh_token, pair.user);
    } catch {
      if (!disposed && op.id === serial) {
        freshCodeRequired = true;
        deps.onError("save");
      }
      return;
    }
    op.generation = generation();
    if (op.generation === null) {
      freshCodeRequired = true;
      if (!disposed && op.id === serial) deps.onError("save");
      return;
    }
    if (!current(op)) return;
    deps.track("login_success", { method });
    deps.track("login", { method });
    if (method === "telegram" && name.trim()) {
      try { await deps.updateName(name.trim()); } catch { /* Optional profile update. */ }
    }
    if (current(op)) deps.navigate();
  };
  return {
    get busy() { return busy; },
    invalidate,
    revive() { disposed = false; serial++; busy = false; },
    dispose() { disposed = true; serial++; busy = false; },
    async start() {
      if (!deps.botAvailable()) { deps.onError("botMissing"); return; }
      const op = begin();
      if (!op) return;
      deps.onBusy(true);
      deps.onError(null);
      deps.track("login_start", { method: "telegram" });
      try {
        const result = await deps.tgStart();
        if (!current(op)) return;
        freshCodeRequired = false;
        deps.onTgStarted(result.request_id);
        deps.openTelegram(deps.telegramStartUrl(result.request_id));
      } catch {
        if (current(op)) deps.onError("start");
      } finally { finish(op); }
    },
    openTelegramAgain(needPhone) {
      if (busy || disposed) return;
      if (needPhone) deps.openTelegram(deps.telegramChatUrl());
      else return this.start();
    },
    async verify(requestId, code, name) {
      if (busy || disposed) return;
      if (freshCodeRequired) { deps.onError("save"); return; }
      if (!loginCodeValid(code)) { deps.onError("enterTgCode"); return; }
      const op = begin();
      if (!op) return;
      deps.onBusy(true);
      deps.onError(null);
      try {
        const pair = await deps.tgVerify(requestId, code.trim());
        await saveAndContinue(op, pair, "telegram", name);
      } catch (error) {
        if (current(op)) {
          const status = deps.getStatus(error);
          deps.onError(({ 400: "badTgCode", 403: "phoneRequired", 409: "notYet", 410: "expired", 429: "tooMany" })[status] || "verify");
        }
      } finally { finish(op); }
    },
    async smsRequest(phone) {
      if (busy || disposed) return;
      if (!loginPhoneValid(phone)) { deps.onError("enterPhone"); return; }
      const op = begin();
      if (!op) return;
      deps.onBusy(true);
      deps.onError(null);
      deps.track("login_start", { method: "sms" });
      try {
        await deps.smsRequest(phone.trim());
        if (current(op)) {
          freshCodeRequired = false;
          deps.onSmsStarted();
        }
      } catch (error) {
        if (current(op)) deps.onError(deps.getStatus(error) === 429 ? "smsTooMany" : "sendFail");
      } finally { finish(op); }
    },
    async smsVerify(phone, code, name) {
      if (busy || disposed) return;
      if (freshCodeRequired) { deps.onError("save"); return; }
      if (!loginCodeValid(code)) { deps.onError("enterSmsCode"); return; }
      const op = begin();
      if (!op) return;
      deps.onBusy(true);
      deps.onError(null);
      try {
        const pair = await deps.smsVerify(phone.trim(), code.trim(), name.trim().slice(0, 120));
        await saveAndContinue(op, pair, "sms");
      } catch (error) {
        if (current(op)) deps.onError(deps.getStatus(error) === 400 ? "badSmsCode" : "verify");
      } finally { finish(op); }
    },
  };
}
