// ================================================================
//  Кирпичи формы доставки — зеркало Android ParcelsScreen.kt:
//  DeliverySectionTitle / DeliveryHint / DeliveryErrorCard, ParcelStepProgress,
//  DeliveryTypeCard, ParcelSizeCard, UrgencyChip, DeliveryWaitNote, ParcelDeadlinePicker,
//  EstimateCard, ParcelCargoTypePicker, ParcelFragileSwitch, RulesCheckbox,
//  ParcelRouteSummary, ParcelRouteRow, MobilityScreenIntro.
//
//  Размеры, цвета и радиусы — только со шкал Canon через var(--…) (см. ui.css,
//  раздел «Форма доставки»); каждая надпись — appText(ru, ba).
// ================================================================
import { useEffect, useRef, useState, type ReactNode } from "react";
import { useLang } from "../i18n/lang";
import type { CourierEstimate } from "../api/courier";
import type { ParcelSize } from "../api/parcels";
import { kopExactLabel } from "../utils/format";
import { IconBox, IconCar, IconCheck, IconClock, IconPin, IconWarn } from "./Icons";
import { sizeLabel } from "./parcelUi";

/** Потолок веса в форме: больше 100 кг — это уже грузоперевозка, а не посылка «между своими». */
export const PARCEL_MAX_WEIGHT_KG = 100;
/** Ориентир «где забрать / куда привезти» — как parcels.ParcelIn на сервере. */
export const PARCEL_ADDRESS_MAX_LEN = 200;
export const PARCEL_DESC_MAX = 2000;
/** Коды типов груза (те же, что понимает сервер) в порядке показа: по частоте в селе. */
export const PARCEL_CARGO_TYPES = ["documents", "medicine", "food", "clothes", "tech", "other"] as const;

export function cargoTypeLabel(code: string, ru: boolean): string {
  switch (code) {
    case "documents": return ru ? "Документы" : "Документтар";
    case "medicine": return ru ? "Лекарства" : "Дарыуҙар";
    case "food": return ru ? "Продукты" : "Аҙыҡ-түлек";
    case "clothes": return ru ? "Одежда" : "Кейем";
    case "tech": return ru ? "Техника" : "Техника";
    case "other": return ru ? "Другое" : "Башҡаһы";
    default: return code; // незнакомый код с сервера показываем как есть, а не прячем
  }
}

/** Значок типа груза: в ленте курьер узнаёт «лекарства» по картинке раньше, чем читает подпись. */
export function cargoTypeEmoji(code: string): string {
  switch (code) {
    case "documents": return "📄";
    case "medicine": return "💊";
    case "food": return "🥫";
    case "clothes": return "👕";
    case "tech": return "📱";
    default: return "📦";
  }
}

/** Короткий намёк на габарит размера. */
export function parcelSizeHint(size: string, ru: boolean): string {
  if (size === "small") return ru ? "документы, ключи, конверт" : "документтар, асҡыстар, конверт";
  if (size === "medium") return ru ? "небольшая коробка, книга" : "бәләкәй ҡумта, китап";
  if (size === "large") return ru ? "сумка, крупная коробка" : "һумка, ҙур ҡумта";
  return "";
}

/** Дата через [days] дней в формате сервера (2026-08-05), по местному времени телефона. */
export function deliveryDateAhead(days: number): string {
  const d = new Date();
  d.setDate(d.getDate() + days);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${mm}-${dd}`;
}

const MONTHS_RU = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"];
const MONTHS_BA = ["ғинуар", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь"];

/** «2026-08-05» → «5 августа». Нечитаемую строку отдаём как есть: врать датой нельзя. */
export function deliveryDateHuman(date: string, ru: boolean): string {
  const parts = date.split("-");
  if (parts.length !== 3) return date;
  const month = Number(parts[1]);
  const day = Number(parts[2]);
  if (!Number.isInteger(month) || !Number.isInteger(day) || month < 1 || month > 12) return date;
  return `${day} ${(ru ? MONTHS_RU : MONTHS_BA)[month - 1]}`;
}

// ───────────────────────────── Текстовые кирпичи ─────────────────────────────

/** Заголовок раздела формы — один вид на все экраны доставки (DeliveryTitle 19/25 Bold). */
export function DeliverySectionTitle({ children }: { children: ReactNode }) {
  return <h3 className="dl-title">{children}</h3>;
}

/** Пояснение под полем/блоком: спокойный мелкий текст; у лимитов — красный. */
export function DeliveryHint({ children, tone = "muted" }: { children: ReactNode; tone?: "muted" | "danger" }) {
  return <p className={"dl-hint" + (tone === "danger" ? " dl-hint--danger" : "")}>{children}</p>;
}

/** Ошибка формы: карточка с иконкой вместо голой красной строки. Пусто → ничего не занимает. */
export function DeliveryErrorCard({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="dl-error" role="alert">
      <span className="dl-error__icon" aria-hidden><IconWarn size={20} /></span>
      <span>{message}</span>
    </div>
  );
}

/** Подсказка под погасшей кнопкой: чего не хватает, чтобы идти дальше (CanonWarn, по центру). */
export function DeliveryBlockedHint({ children }: { children: ReactNode }) {
  return <p className="dl-blocked">{children}</p>;
}

/** Полоска прогресса мастера: сколько шагов пройдено и как называется текущий. */
export function ParcelStepProgress({ step, total, title }: { step: number; total: number; title: string }) {
  const { appText } = useLang();
  return (
    <div className="pstep" role="progressbar" aria-valuemin={1} aria-valuemax={total} aria-valuenow={step + 1} aria-valuetext={title}>
      <div className="pstep__bars" aria-hidden>
        {Array.from({ length: total }, (_, i) => (
          <span key={i} className={"pstep__bar" + (i <= step ? " is-passed" : "")} />
        ))}
      </div>
      <p className="pstep__label">
        {appText(`Шаг ${step + 1} из ${total} · ${title}`, `${step + 1}-се аҙым, барыһы ${total} · ${title}`)}
      </p>
    </div>
  );
}

// ───────────────────────────── Выборы ─────────────────────────────

/** Карточка выбора (тип доставки, размер): плитка с иконкой, заголовок, подпись, галочка. */
export function DeliveryTypeCard({
  selected,
  title,
  subtitle,
  icon,
  onClick,
}: {
  selected: boolean;
  title: string;
  subtitle: string;
  icon: ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className={"dtype-card" + (selected ? " is-selected" : "")}
      onClick={onClick}
    >
      <span className="dtype-card__icon" aria-hidden>{icon}</span>
      <span className="dtype-card__text">
        <strong>{title}</strong>
        <small>{subtitle}</small>
      </span>
      {selected && (
        <span className="dtype-card__check" aria-hidden>
          <IconCheck size={14} />
        </span>
      )}
    </button>
  );
}

export function ParcelSizeCard({ size, selected, onClick }: { size: ParcelSize; selected: boolean; onClick: () => void }) {
  const { lang } = useLang();
  const ru = lang !== "ba";
  return (
    <DeliveryTypeCard
      selected={selected}
      title={sizeLabel(size, ru)}
      subtitle={parcelSizeHint(size, ru)}
      icon={<IconBox size={20} />}
      onClick={onClick}
    />
  );
}

/** Чип срочности / дня: заголовок + подпись, зелёный когда выбран. Высота от содержимого —
 *  башкирские подписи длиннее, вторая строка не должна обрезаться. */
export function UrgencyChip({
  title,
  subtitle,
  selected,
  onClick,
}: {
  title: string;
  subtitle: string;
  selected: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      className={"urg-chip" + (selected ? " is-selected" : "")}
      onClick={onClick}
    >
      <strong>{title}</strong>
      <small>{subtitle}</small>
    </button>
  );
}

/** Способ выбран — сразу честно про ожидание: сегодня приедет или через неделю. */
export function DeliveryWaitNote({ type }: { type: "poputka" | "courier" | "buy_bring" }) {
  const { appText } = useLang();
  const text =
    type === "courier"
      ? appText("Курьер выезжает, как только возьмёт заказ.", "Курьер заказды алыу менән юлға сыға.")
      : type === "buy_bring"
        ? appText("Курьер сам купит и выедет, как только возьмёт заказ.", "Курьер үҙе һатып алыр һәм заказды алғас юлға сығыр.")
        : appText(
            "Везёт попутчик, который и так едет. Ждать можно день-другой — пока кто-нибудь не поедет в ту сторону.",
            "Юл ыңғайы бараған юлдаш илтә. Бер-ике көн көтөргә тура килеүе ихтимал — шул яҡҡа кемдер киткәнсе."
          );
  return (
    <div className="dl-wait">
      <span className="dl-wait__icon" aria-hidden><IconClock size={16} /></span>
      {/* Способ щёлкают туда-сюда: строка подменяется с затуханием, а не одним кадром. */}
      <p key={type} className="dl-hint dl-wait__text">{text}</p>
    </div>
  );
}

/**
 * Срок: к какому дню нужно. Не календарь-комбайн, а четыре ответа из жизни — «когда получится»,
 * «сегодня», «завтра», «выбрать день». По умолчанию «Не срочно» (пусто): навязанный дедлайн
 * отпугнёт курьеров, которым он не по пути.
 */
export function ParcelDeadlinePicker({ value, onValue }: { value: string; onValue: (v: string) => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const today = deliveryDateAhead(0);
  const tomorrow = deliveryDateAhead(1);
  // Черновик мог пролежать до ночи, и «сегодня» стало вчерашним — тихо возвращаемся к «не срочно».
  useEffect(() => {
    if (value && value < today) onValue("");
  }, [value, today, onValue]);
  const customDay = !!value && value !== today && value !== tomorrow;
  const nativeRef = useRef<HTMLInputElement>(null);
  const [inline, setInline] = useState(false);
  const openPicker = () => {
    const el = nativeRef.current;
    const withPicker = el as (HTMLInputElement & { showPicker?: () => void }) | null;
    if (withPicker && typeof withPicker.showPicker === "function") {
      try {
        withPicker.showPicker();
        return;
      } catch {
        /* браузер без системного календаря — показываем поле даты прямо в форме */
      }
    }
    setInline(true);
  };
  return (
    <div className="deadline">
      <div className="deadline__row">
        <UrgencyChip
          title={appText("Не срочно", "Ашығыс түгел")}
          subtitle={appText("когда получится", "ҡасан булһа ла")}
          selected={!value}
          onClick={() => onValue("")}
        />
        <UrgencyChip
          title={appText("Сегодня", "Бөгөн")}
          subtitle={deliveryDateHuman(today, ru)}
          selected={value === today}
          onClick={() => onValue(today)}
        />
      </div>
      <div className="deadline__row">
        <UrgencyChip
          title={appText("Завтра", "Иртәгә")}
          subtitle={deliveryDateHuman(tomorrow, ru)}
          selected={value === tomorrow}
          onClick={() => onValue(tomorrow)}
        />
        <UrgencyChip
          title={appText("Выбрать день", "Көн һайлау")}
          subtitle={customDay ? deliveryDateHuman(value, ru) : appText("другой день", "башҡа көн")}
          selected={customDay}
          onClick={openPicker}
        />
      </div>
      <input
        ref={nativeRef}
        type="date"
        className={inline ? "field__input" : "deadline__native"}
        min={today}
        value={customDay ? value : ""}
        onChange={(e) => onValue(e.target.value)}
        aria-label={appText("Выбрать день", "Көн һайлау")}
        tabIndex={inline ? 0 : -1}
      />
      <DeliveryHint>
        {value
          ? appText(
              "Курьер увидит этот день до того, как взяться, — и возьмётся, только если успевает.",
              "Курьер был көндө эшкә тотонғанға тиклем күрер һәм өлгөрһә генә алыр."
            )
          : appText(
              "Без срока берут охотнее: курьер подстроит доставку под свою дорогу.",
              "Ваҡытһыҙ теләберәк алалар: курьер илтеүҙе үҙ юлына яраҡлаштыра."
            )}
      </DeliveryHint>
    </div>
  );
}

/** Тип груза — одиночный выбор из шести чипов; повторное нажатие снимает выбор. */
export function ParcelCargoTypePicker({ value, onValue }: { value: string; onValue: (v: string) => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  return (
    <div className="cargo">
      <p className="cargo__title">{appText("Что внутри", "Эсендә нимә")}</p>
      <div className="cargo__chips" role="radiogroup" aria-label={appText("Что внутри", "Эсендә нимә")}>
        {PARCEL_CARGO_TYPES.map((code) => (
          <button
            key={code}
            type="button"
            role="radio"
            aria-checked={value === code}
            className={"cargo-chip" + (value === code ? " is-selected" : "")}
            onClick={() => onValue(value === code ? "" : code)}
          >
            {cargoTypeEmoji(code)}  {cargoTypeLabel(code, ru)}
          </button>
        ))}
      </div>
      <DeliveryHint>
        {appText("Необязательно. Нажми ещё раз, чтобы снять выбор.", "Мотлаҡ түгел. Һайлауҙы алып ташлар өсөн тағы бер тапҡыр баҫ.")}
      </DeliveryHint>
    </div>
  );
}

/** «Хрупкое» — переключатель с предупреждающей подложкой: пометка на коробке, а не галочка. */
export function ParcelFragileSwitch({ checked, onToggle }: { checked: boolean; onToggle: () => void }) {
  const { appText } = useLang();
  return (
    <button type="button" role="switch" aria-checked={checked} className={"fragile" + (checked ? " is-on" : "")} onClick={onToggle}>
      <span className="fragile__icon" aria-hidden><IconWarn size={20} /></span>
      <span className="fragile__text">
        <strong>{appText("Хрупкое", "Ватыла торған")}</strong>
        <small>{appText("Курьер положит отдельно и не поставит под низ", "Курьер айырым һалыр һәм аҫҡа ҡуймаҫ")}</small>
      </span>
      <span className={"switch" + (checked ? " on" : "")} aria-hidden />
    </button>
  );
}

/** Обязательное согласие с правилами: карточка-чекбокс, зелёная когда отмечена. */
export function RulesCheckbox({ checked, onToggle }: { checked: boolean; onToggle: () => void }) {
  const { appText } = useLang();
  return (
    <button type="button" role="checkbox" aria-checked={checked} className={"rules-card" + (checked ? " is-on" : "")} onClick={onToggle}>
      <span className="rules-card__box" aria-hidden>{checked && <IconCheck size={14} />}</span>
      <span className="rules-card__text">
        <strong>{appText("Подтверждаю правила доставки", "Илтеү ҡағиҙәләрен раҫлайым")}</strong>
        <small>
          {appText(
            "Не отправляю запрещённое: деньги, документы на предъявителя, лекарства без рецепта, скоропорт, оружие.",
            "Тыйылғанды ебәрмәйем: аҡса, күрһәтеүсегә документтар, рецептһыҙ дарыу, тиҙ боҙолған аҙыҡ, ҡорал."
          )}
        </small>
      </span>
    </button>
  );
}

// ───────────────────────────── Маршрут и цена ─────────────────────────────

/** Сводка «Откуда → Куда» на шагах 2–3 с кнопкой «изменить»: выбор всегда виден и правится. */
export function ParcelRouteSummary({ from, to, onEdit }: { from: string; to: string; onEdit: () => void }) {
  const { appText } = useLang();
  return (
    <div className="route-summary">
      <span className="route-summary__icon" aria-hidden><IconCar size={20} /></span>
      <b className="route-summary__text">{from} → {to}</b>
      <button type="button" className="route-summary__edit" onClick={onEdit}>
        {appText("изменить", "үҙгәртергә")}
      </button>
    </div>
  );
}

/** Строка маршрута: точка + «Откуда → Куда» жирным. */
export function ParcelRouteRow({ from, to }: { from: string; to: string }) {
  return (
    <div className="route-row">
      <span className="route-row__icon" aria-hidden><IconPin size={16} /></span>
      <b>{from || "—"}</b>
      <span className="route-row__arrow">→</span>
      <b>{to || "—"}</b>
    </div>
  );
}

/** Карточка оценки цены — честно показываем итог, наш сбор и из чего сложилась цена. */
export function EstimateCard({ est }: { est: CourierEstimate }) {
  const { appText } = useLang();
  const b = est.breakdown ?? {};
  const num = (k: string) => (typeof b[k] === "number" ? (b[k] as number) : Number(b[k] ?? 0) || 0);
  const commissionEstimated = est.commission_estimated ?? b.commission_estimated === true;
  const returnFee = est.return_fee_estimate_kop ?? num("return_fee_estimate_kop");
  const net = Math.max(0, est.price_kop - est.commission_kop);
  const urgencyKop = num("urgency_kop");
  return (
    <section className="estimate" aria-live="polite">
      <span className="estimate__label">{appText("Доставка", "Илтеү")}</span>
      <b className="estimate__price">≈ {kopExactLabel(est.price_kop)}</b>
      <p className="estimate__note">
        {appText("Курьер получит ", "Курьер аласаҡ ")}
        {kopExactLabel(net)}
        {appText(". Наша комиссия ", ". Беҙҙең комиссия ")}
        {kopExactLabel(est.commission_kop)}
        {commissionEstimated ? appText(" (ориентировочно)", " (самаға)") : ""}.
      </p>
      <div className="estimate__rows">
        <EstimateRow label={appText("Подача", "Килеү")} value={kopExactLabel(num("base_kop"))} />
        <EstimateRow
          label={`${appText("Расстояние", "Ара")} (${Math.round(est.distance_km)} км)`}
          value={kopExactLabel(num("distance_kop"))}
        />
        <EstimateRow label={appText("Размер", "Ҙурлыҡ")} value={kopExactLabel(num("size_kop"))} />
        {urgencyKop > 0 && <EstimateRow label={appText("Срочность", "Ашығыслыҡ")} value={kopExactLabel(urgencyKop)} />}
      </div>
      {/* «А если получателя не будет?» — ответ нужен ДО заказа, а не в чеке после. */}
      {returnFee > 0 && (
        <p className="estimate__note">
          {appText(
            `Если получателя не будет на месте — можно попросить курьера заехать ещё раз. Если и это не выйдет, возврат обойдётся примерно в ${kopExactLabel(returnFee)} — это дорога курьера, саму доставку и комиссию мы не берём.`,
            `Алыусы урынында булмаһа — курьерҙан ҡабат инеүҙе һорап була. Был да барып сыҡмаһа, кире ҡайтарыу яҡынса ${kopExactLabel(returnFee)} тора — был курьер юлы, илтеү хаҡын һәм комиссияны алмайбыҙ.`
          )}
        </p>
      )}
    </section>
  );
}

function EstimateRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="estimate__row">
      <span>{label}</span>
      <b>{value}</b>
    </div>
  );
}

// ───────────────────────────── Шапка экрана ─────────────────────────────

/** Вводная экрана мобильности: плитка с иконкой режима, заголовок с меткой, подпись. */
export function MobilityScreenIntro({
  mode,
  title,
  subtitle,
  badge,
}: {
  mode: "courier" | "taxi";
  title: string;
  subtitle: string;
  badge?: string;
}) {
  return (
    <div className={"screen-intro screen-intro--" + mode}>
      <span className="screen-intro__tile" aria-hidden>
        {mode === "taxi" ? <IconCar size={25} /> : <IconBox size={25} />}
      </span>
      <div className="screen-intro__text">
        <div className="screen-intro__row">
          <h2 className="screen-intro__title">{title}</h2>
          {badge && <span className="screen-intro__badge">{badge}</span>}
        </div>
        <p className="screen-intro__sub">{subtitle}</p>
      </div>
    </div>
  );
}
