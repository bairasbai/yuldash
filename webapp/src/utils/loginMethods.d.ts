export type LoginMethod = "sms" | "telegram" | "vk" | "whatsapp";
export const LOGIN_METHODS_CACHE_KEY: string;
export const LOGIN_METHOD_ORDER: readonly LoginMethod[];
export function normalizeLoginMethods(value: unknown): LoginMethod[] | null;
export function cachedLoginMethods(storage: Pick<Storage, "getItem">): LoginMethod[];
export function cacheLoginMethods(storage: Pick<Storage, "setItem">, methods: readonly LoginMethod[]): void;
