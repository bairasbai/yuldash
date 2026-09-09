const NEEDS_ANSWER = new Set(["check_sent", "waiting", "no_share", "escalated"]);

/** Сервер уже спросил и ещё не получил подтверждение безопасности. */
export function winterCheckNeedsAnswer(state) {
  return NEEDS_ANSWER.has(String(state));
}

/** После reload уже отправленный вопрос сверяем с сервером сразу. */
export function winterCheckDelay(asked, dueAt, now = Date.now()) {
  return asked ? 0 : Math.max(0, dueAt - now);
}

export function wasWinterCheckAsked(key) {
  try {
    return sessionStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

export function rememberWinterCheck(key) {
  try {
    sessionStorage.setItem(key, "1");
  } catch {
    /* Приватный режим: сервер всё равно остаётся источником истины. */
  }
}

export function forgetWinterCheck(key) {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* Нечего чистить в недоступном хранилище. */
  }
}
