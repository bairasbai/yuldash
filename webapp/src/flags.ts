// ================================================================
//  Локальные флаги первого запуска и предпочтений (localStorage).
//  Согласия — зеркало серверных записей только для текущей сессии.
//  Старые отметки без владельца восстанавливаются через сервер, не наследуются.
// ================================================================
import { getSessionGeneration } from "./api/client";
import { ownedStorage, captureOwner } from "./utils/ownedStorage";

export type Role = "passenger" | "driver";
export type ConsentKind = "offer" | "privacy" | "geo";
export type Consents = Record<ConsentKind, boolean>;

const K = {
  introSeen: "yuldash.introSeen",
  onboarded: "yuldash.onboarded",
  role: "yuldash.role",
  simpleMode: "yuldash.simpleMode",
  consents: "yuldash.consents",
} as const;

function readBool(key: string): boolean {
  return localStorage.getItem(key) === "1";
}
function writeBool(key: string, v: boolean): void {
  if (v) localStorage.setItem(key, "1");
  else localStorage.removeItem(key);
}

export const flags = {
  introSeen: () => readBool(K.introSeen),
  setIntroSeen: () => writeBool(K.introSeen, true),

  onboarded: () => readBool(K.onboarded),
  setOnboarded: () => writeBool(K.onboarded, true),

  role: (): Role => (ownedStorage(captureOwner()).getItem(K.role) === "driver" ? "driver" : "passenger"),
  setRole: (r: Role, owner = getSessionGeneration()) => ownedStorage(owner).setItem(K.role, r),

  simpleMode: () => readBool(K.simpleMode),
  setSimpleMode: (v: boolean) => writeBool(K.simpleMode, v),

  consents(owner = getSessionGeneration()): Consents {
    try {
      const raw = ownedStorage(owner).getItem(K.consents) ?? (owner ? localStorage.getItem(K.consents) : null);
      const o = raw ? (JSON.parse(raw) as Partial<Consents> & { session?: string }) : {};
      if (o.session !== owner || owner !== getSessionGeneration()) return { offer: false, privacy: false, geo: false };
      return { offer: !!o.offer, privacy: !!o.privacy, geo: !!o.geo };
    } catch {
      return { offer: false, privacy: false, geo: false };
    }
  },
  setConsent(kind: ConsentKind, value: boolean, owner = getSessionGeneration()): Consents {
    const next = { ...this.consents(owner), [kind]: value };
    ownedStorage(owner).setItem(K.consents, JSON.stringify({ ...next, session: owner }));
    return next;
  },
};
