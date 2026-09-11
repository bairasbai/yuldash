// ================================================================
//  Чем рассчитаемся с водителем — выбор ДО заказа.
//  Зеркало Android PaymentMethodsScreen.kt (instant_service:
//  PAY_METHODS_OPEN / PAY_METHODS_SOON).
//
//  Это НЕ кошелёк и не платёжный экран: деньги через приложение не
//  идут, комиссию с них мы не берём. Пассажир платит водителю сам —
//  наличными или переводом, — а здесь записывается договорённость,
//  о которой узнают ОБЕ стороны. Спор «я думал, ты переводом»
//  случается ровно потому, что до высадки об этом никто не говорил.
//
//  Карты и корпоративный счёт стоят строкой «скоро» намеренно.
//  Принимать деньги без договора с банком, кассы и чеков нельзя,
//  а немой вопрос «а картой можно?» человек задаёт себе каждый заказ:
//  честнее ответить на него сразу, чем делать вид, что его нет.
//
//  В вебе выбора не было вовсе — заказ молча уезжал с «договоримся»
//  (сверка с Android, 2026-08-30).
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import type { PaymentMethod } from "../api/instant";
import { IconWallet } from "./Icons";
import { track } from "../analytics";

/** Где лежит последний выбор. Человек платит одинаково почти всегда — спрашивать заново незачем. */
const PREF_KEY = "yuldash.payMethod";

export const PAY_METHODS_OPEN: {
  code: PaymentMethod;
  ru: string;
  ba: string;
  shortRu: string;
  shortBa: string;
}[] = [
  { code: "cash", ru: "Наличными", ba: "Наличный менән", shortRu: "Наличные", shortBa: "Наличный" },
  { code: "sbp", ru: "Переводом по СБП", ba: "СБП аша күсереү", shortRu: "СБП", shortBa: "СБП" },
  {
    code: "negotiate",
    ru: "Договоримся на месте",
    ba: "Урында килешәбеҙ",
    shortRu: "Договоримся",
    shortBa: "Килешәбеҙ",
  },
];

/** Заведены, но выключены: без договора с банком принимать деньги нельзя. */
export const PAY_METHODS_SOON: { code: "card" | "corporate"; ru: string; ba: string }[] = [
  { code: "card", ru: "Картой в приложении", ba: "Ҡушымтала карта менән" },
  { code: "corporate", ru: "Корпоративный счёт", ba: "Корпоратив иҫәп" },
];

/** Последний выбор человека. Ничего не выбирал — наличные, как в Android. */
export function rememberedPayMethod(): PaymentMethod {
  try {
    const v = localStorage.getItem(PREF_KEY);
    if (v === "cash" || v === "sbp" || v === "negotiate") return v;
  } catch {
    /* приватный режим — просто умолчание */
  }
  return "cash";
}

/** Запомнить выбор для следующего заказа — тот же ключ использует отдельный экран. */
export function rememberPayMethod(method: PaymentMethod): void {
  try {
    localStorage.setItem(PREF_KEY, method);
  } catch {
    /* Приватный режим: текущий заказ всё равно получит выбор напрямую. */
  }
}

export default function PayMethodPicker({
  value,
  onChange,
}: {
  value: PaymentMethod;
  onChange: (m: PaymentMethod) => void;
}) {
  const { appText } = useLang();
  const [open, setOpen] = useState(false);
  const cur = PAY_METHODS_OPEN.find((m) => m.code === value) ?? PAY_METHODS_OPEN[2];

  function pick(m: PaymentMethod) {
    onChange(m);
    rememberPayMethod(m);
    setOpen(false);
  }

  if (!open) {
    return (
      <button type="button" className="btn-soft trip-btn" onClick={() => setOpen(true)}>
        <IconWallet size={18} /> {appText(cur.shortRu, cur.shortBa)}
      </button>
    );
  }

  return (
    <div className="sheet-backdrop" onClick={() => setOpen(false)}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h2 className="sheet__title">{appText("Чем рассчитаемся", "Нимә менән түләйбеҙ")}</h2>
        <p className="sheet__comment">
          {appText(
            "Деньги идут напрямую водителю — приложение их не касается. Мы только записываем договорённость, чтобы на высадке не было неожиданностей.",
            "Аҡса тура йөрөтөүсегә бара — ҡушымта уға ҡағылмай. Беҙ килешеүҙе генә яҙабыҙ, төшкәндә көтөлмәгәнлек булмаһын."
          )}
        </p>
        {PAY_METHODS_OPEN.map((m) => (
          <button
            key={m.code}
            type="button"
            className={m.code === value ? "btn-primary" : "btn-soft"}
            style={{ width: "100%", marginBottom: 8 }}
            onClick={() => pick(m.code)}
          >
            {appText(m.ru, m.ba)}
          </button>
        ))}

        {/* Выключенные способы. Показываем, но не притворяемся, что они работают. */}
        <span className="field__label" style={{ display: "block", marginTop: 12 }}>
          {appText("Скоро", "Тиҙҙән")}
        </span>
        {PAY_METHODS_SOON.map((m) => (
          // Нажатие считается заявкой: по нему и решим, когда включать эквайринг.
          // Немой вопрос «а картой можно?» человек задаёт себе каждый заказ — пусть
          // этот вопрос хотя бы доходит до нас.
          <button
            key={m.code}
            type="button"
            className="list-row list-row--muted"
            onClick={() => track(m.code === "card" ? "pay_wanted_card" : "pay_wanted_corporate")}
          >
            <div className="list-row__main">
              <div className="list-row__title">{appText(m.ru, m.ba)}</div>
            </div>
            <span className="badge badge--muted">{appText("скоро", "тиҙҙән")}</span>
          </button>
        ))}
        <p className="trip-hint">
          {appText(
            "Чтобы принимать карты, нужны договор с банком и чеки. Пока их нет — не обещаем.",
            "Карта ҡабул итер өсөн банк менән килешеү һәм чектар кәрәк. Улар булмағанда вәғәҙә бирмәйбеҙ."
          )}
        </p>

        <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>
          {appText("Закрыть", "Ябыу")}
        </button>
      </div>
    </div>
  );
}
