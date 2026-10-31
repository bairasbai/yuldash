/** Порядок и кеш публичных способов входа. Здесь нет данных аккаунта. */
export const LOGIN_METHODS_CACHE_KEY = "yuldash.login-methods.v1";
export const LOGIN_METHOD_ORDER = ["sms", "telegram", "vk", "whatsapp"];

export function normalizeLoginMethods(value) {
  if (!Array.isArray(value)) return null;
  return LOGIN_METHOD_ORDER.filter((method) => value.includes(method));
}

export function cachedLoginMethods(storage) {
  try {
    const cached = normalizeLoginMethods(JSON.parse(storage.getItem(LOGIN_METHODS_CACHE_KEY)));
    if (cached !== null) return cached;
  } catch { /* Недоступное хранилище не закрывает вход. */ }
  return ["telegram"];
}

export function cacheLoginMethods(storage, methods) {
  try { storage.setItem(LOGIN_METHODS_CACHE_KEY, JSON.stringify(methods)); } catch { /* Вход работает и без кеша. */ }
}
