// ================================================================
//  Доверие, согласия и круг «своих»
//  (зеркало backend/app/routers/trust.py).
//
//  Почему появился этот файл. Экраны «Доверие» и «Согласия» в вебе
//  были, но данные для них ПРИДУМЫВАЛИСЬ на клиенте: уровень L0–L3
//  считался по профилю, а согласия жили в localStorage. Человек
//  видел не свой настоящий уровень, а нашу догадку о нём — и не мог
//  доказать, что согласие вообще давал (152-ФЗ требует таймстамп на
//  сервере). Ручки при этом были с самого начала, ими пользовалось
//  приложение (сверка с Android, 2026-08-30).
//
//  Приватность: обе ручки отдают ТОЛЬКО про себя. Чужой уровень
//  доверия и чужие согласия не показываются никому.
// ================================================================
import { apiGet, apiPost } from "./client";

/** Двуязычная строка с сервера. Клиент их не сочиняет — берёт как есть. */
export interface TrustPhrase {
  ru: string;
  ba: string;
}

/** Что даёт следующий уровень и как до него дойти. null у «Своего» — выше некуда. */
export interface TrustNext {
  level: number;
  title: TrustPhrase;
  how: TrustPhrase;
  benefits: TrustPhrase[];
}

/**
 * Мой уровень доверия (GET /me/trust).
 *
 * L0 Новичок → L1 Знакомый → L2 Проверен → L3 Свой. Тексты приходят с сервера
 * на двух языках: лестница доверия — часть продукта, и она должна звучать одинаково
 * в приложении, в вебе и в пуше.
 */
export interface TrustSummary {
  level: number;
  title: TrustPhrase;
  benefits: TrustPhrase[];
  /** L3: человек в кругу своих — его пригласили. */
  is_insider: boolean;
  /** Кто пригласил (id). null = пришёл сам. */
  invited_by: number | null;
  /** L2+ может звать своих. */
  can_invite: boolean;
  next: TrustNext | null;
}

export function fetchMyTrust(signal?: AbortSignal): Promise<TrustSummary> {
  return apiGet<TrustSummary>("/me/trust", { signal });
}

// ----------------------------- Согласия (152-ФЗ) -----------------------------
/** Виды согласий, которые сервер принимает. Остальное он отклонит. */
export type ConsentKind = "offer" | "privacy" | "geo" | "age18";

/** Зафиксированное согласие: вид и когда дано. Время первого согласия не переписывается. */
export interface Consent {
  kind: ConsentKind | string;
  granted_at: string;
}

/** Мои согласия. Пустой список = ничего ещё не подтверждал. */
export function fetchMyConsents(signal?: AbortSignal): Promise<Consent[]> {
  return apiGet<Consent[]>("/me/consents", { signal });
}

/**
 * Зафиксировать согласие с отметкой времени на сервере.
 *
 * Идемпотентно: повторный вызов вернёт уже сохранённую запись и НЕ сдвинет дату —
 * это доказательство, а не настройка.
 */
export function grantConsent(kind: ConsentKind): Promise<Consent> {
  return apiPost<Consent>("/me/consents", { kind });
}

// ----------------------------- Круг своих -----------------------------
/** Пригласительный код: сам код, сколько активаций осталось, когда создан. */
export interface Invite {
  code: string;
  uses_left: number;
  created_at: string;
}

/** Мои коды. Приватность: сервер отдаёт только коды владельца. */
export function fetchMyInvites(signal?: AbortSignal): Promise<Invite[]> {
  return apiGet<Invite[]>("/invites/mine", { signal });
}

/**
 * Создать код приглашения. Может только проверенный участник (L2+),
 * запас кодов на человека ограничен — иначе «круг своих» перестал бы что-то значить.
 */
export function createInvite(): Promise<Invite> {
  return apiPost<Invite>("/invites");
}

/**
 * Активировать чужой код → стать «своим» (L3) и запомнить, кто пригласил.
 *
 * Свой код активировать нельзя, дважды «своим» не станешь — это держит сервер.
 */
export function redeemInvite(code: string): Promise<{ ok?: boolean; level?: number }> {
  return apiPost(`/invites/redeem`, { code: code.trim().toUpperCase().slice(0, 12) });
}
