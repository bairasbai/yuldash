// ================================================================
//  Локальные флаги первого запуска и предпочтений (localStorage).
//  Не персональные данные — только UX-состояние приложения на устройстве.
//  Согласия 152-ФЗ здесь тоже локальные: у бэкенда пока нет эндпоинта согласий,
//  поэтому отметку храним на устройстве (честно, без выдуманного API).
// ================================================================

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

  role: (): Role => (localStorage.getItem(K.role) === "driver" ? "driver" : "passenger"),
  setRole: (r: Role) => localStorage.setItem(K.role, r),

  simpleMode: () => readBool(K.simpleMode),
  setSimpleMode: (v: boolean) => writeBool(K.simpleMode, v),

  consents(): Consents {
    try {
      const raw = localStorage.getItem(K.consents);
      const o = raw ? (JSON.parse(raw) as Partial<Consents>) : {};
      return { offer: !!o.offer, privacy: !!o.privacy, geo: !!o.geo };
    } catch {
      return { offer: false, privacy: false, geo: false };
    }
  },
  setConsent(kind: ConsentKind, value: boolean): Consents {
    const next = { ...this.consents(), [kind]: value };
    localStorage.setItem(K.consents, JSON.stringify(next));
    return next;
  },
};
