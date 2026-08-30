// ================================================================
//  Общие кусочки UI посылок/курьера (волна 5): подписи размера/
//  статуса, карточка доступной заявки («Взять»), карточка «Везу»
//  (двигать статус + диалог кода вручения + «купи и привези»),
//  диалог ввода кода. Переиспользуют Canon-токены и i18n appText.
// ================================================================
import { useState } from "react";
import { Link } from "react-router-dom";
import { useLang } from "../i18n/lang";
import type { Parcel, ParcelSize } from "../api/parcels";
import { isCarrying } from "../api/parcels";
import { dayMonthLong, rubLabel } from "../utils/format";
import { IconArrow, IconPhone, IconCheck, IconChat } from "./Icons";
import ParcelPhoto from "./ParcelPhoto";

// ------------------------------- Подписи -------------------------------
export function sizeLabel(size: ParcelSize | string, ru: boolean): string {
  if (size === "small") return ru ? "Маленькая" : "Бәләкәй";
  if (size === "medium") return ru ? "Средняя" : "Уртаса";
  if (size === "large") return ru ? "Большая" : "Ҙур";
  return ru ? "Посылка" : "Бандероль";
}

export function StatusPillParcel({ status }: { status: string }) {
  const { appText } = useLang();
  const map: Record<string, { ru: string; ba: string; cls: string }> = {
    created: { ru: "Ищем курьера", ba: "Курьер эҙләйбеҙ", cls: "badge--gold" },
    accepted: { ru: "Курьер найден", ba: "Курьер табылды", cls: "badge--mint" },
    in_transit: { ru: "В пути", ba: "Юлда", cls: "badge--mint" },
    delivered: { ru: "Доставлено", ba: "Тапшырылды", cls: "badge--mint" },
    canceled: { ru: "Отменена", ba: "Кире алынды", cls: "badge--danger" },
    // Возврат (аудит сценариев 30.08, P0). Этих двух статусов в вебе не было, и человек,
    // чью коробку уже везут обратно, читал «Ищем курьера» — потому что незнакомый статус
    // молча падал в значение по умолчанию. Приложение показывает возврат давно.
    returning: { ru: "Везут обратно", ba: "Кире алып ҡайталар", cls: "badge--gold" },
    returned: { ru: "Вернули отправителю", ba: "Ебәреүсегә ҡайтарылды", cls: "badge--danger" },
  };
  // Незнакомый статус НЕ выдаём за «ищем курьера»: лучше показать сырое слово, чем
  // уверенно соврать. Так следующий новый статус на сервере будет видно сразу.
  const m = map[status];
  if (!m) return <span className="badge">{status}</span>;
  return <span className={`badge ${m.cls}`}>{appText(m.ru, m.ba)}</span>;
}

// ------------------------------- Маршрут + мета -------------------------------
export function ParcelRoute({ p }: { p: Parcel }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const price = p.price_kop > 0 ? p.price_kop : p.fee_kop;
  return (
    <div className="parcel-card__body">
      <div className="repeat-route">
        <span>{p.from_city || appText("Откуда", "Ҡайҙан")}</span>
        <span className="repeat-route__arrow"><IconArrow size={18} /></span>
        <span>{p.to_city || appText("Куда", "Ҡайҙа")}</span>
      </div>
      <div className="parcel-card__meta">
        <span className="parcel-card__size">{sizeLabel(p.size, ru)}</span>
        {p.delivery_type === "buy_bring" && (
          <span className="badge badge--gold">{appText("Купи и привези", "Һатып ал да килтер")}</span>
        )}
        {p.urgency === "now" && (
          <span className="badge badge--gold">{appText("Срочно", "Ашығыс")}</span>
        )}
        {p.fragile && (
          <span className="badge badge--muted">{appText("Хрупкое", "Ватыла торған")}</span>
        )}
        {p.weight_kg != null && p.weight_kg > 0 && (
          <span className="badge badge--muted">
            {appText(`${p.weight_kg} кг`, `${p.weight_kg} кг`)}
          </span>
        )}
        {price > 0 && <span className="parcel-card__price">{rubLabel(price)}</span>}
      </div>
      {/* Срок отдельной строкой: по нему курьер решает, успевает ли он вообще */}
      {p.deliver_by && (
        <div className="parcel-card__desc">
          {appText("Нужно не позже: ", "Ошо көндән һуң түгел: ")}
          {dayMonthLong(new Date(p.deliver_by + "T00:00:00"), ru)}
        </div>
      )}
      {p.description && <div className="parcel-card__desc">{p.description}</div>}
    </div>
  );
}

// ------------------------------- Доступная заявка («Взять») -------------------------------
export function AvailableParcelCard({
  p,
  busy,
  onTake,
  photo,
  onPhoto,
}: {
  p: Parcel;
  busy: boolean;
  onTake: () => void;
  /** Снимок «взял целой» — необязателен, но в споре его отсутствие говорит само. */
  photo?: string | null;
  onPhoto?: (url: string) => void;
}) {
  const { appText } = useLang();
  return (
    <div className="parcel-card">
      <div className="parcel-card__head">
        <div className="parcel-card__to">{appText("Кому", "Кемгә")}: {p.receiver_name || "—"}</div>
        <StatusPillParcel status={p.status} />
      </div>
      <ParcelRoute p={p} />
      {onPhoto && <ParcelPhoto kind="pickup" url={photo ?? null} onReady={onPhoto} />}
      <button type="button" className="btn-primary" style={{ marginTop: 12 }} onClick={onTake} disabled={busy}>
        {busy ? appText("Берём…", "Алабыҙ…") : appText("Взять доставку", "Доставканы алыу")}
      </button>
    </div>
  );
}

// ------------------------------- Диалог кода вручения -------------------------------
export function CodeDialog({
  busy,
  error,
  onSubmit,
  onClose,
}: {
  busy: boolean;
  error: string | null;
  onSubmit: (code: string) => void;
  onClose: () => void;
}) {
  const { appText } = useLang();
  const [code, setCode] = useState("");
  return (
    <div className="sheet-backdrop" onClick={onClose} role="dialog" aria-modal="true">
      <div className="sheet code-sheet" onClick={(e) => e.stopPropagation()}>
        <div className="sheet__grip" aria-hidden />
        <h2 className="section-title" style={{ marginTop: 4 }}>
          {appText("Код вручения", "Тапшырыу коды")}
        </h2>
        <p className="parcel-card__desc" style={{ marginTop: 2 }}>
          {appText(
            "Получатель называет код при передаче — введи его, чтобы завершить доставку.",
            "Алыусы тапшырғанда кодты әйтә — доставканы тамамлар өсөн уны индер."
          )}
        </p>
        <input
          className="auth__code"
          value={code}
          onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 8))}
          placeholder="ABC123"
          autoFocus
          inputMode="text"
          autoComplete="off"
          aria-label={appText("Код вручения", "Тапшырыу коды")}
        />
        {error && <div className="auth__error">{error}</div>}
        <button
          type="button"
          className="btn-primary submit-btn"
          style={{ marginTop: 12 }}
          onClick={() => onSubmit(code)}
          disabled={busy || code.length < 4}
        >
          <IconCheck size={18} /> {busy ? appText("Проверяем…", "Тикшерәбеҙ…") : appText("Доставлено", "Тапшырылды")}
        </button>
        <button type="button" className="btn-ghost" style={{ marginTop: 8 }} onClick={onClose}>
          {appText("Отмена", "Баш тартыу")}
        </button>
      </div>
    </div>
  );
}

// ------------------------------- «Везу» — карточка с управлением -------------------------------
export function CarryParcelCard({
  p,
  busy,
  onDepart,
  onArrived,
  onDeliver,
  onGoodsCost,
}: {
  p: Parcel;
  busy: boolean;
  onDepart: () => void;
  /**
   * «Я на месте» — с этой минуты идёт платное ожидание (на обоих концах).
   * Не передан — кнопки нет: у доставки «по пути» тарифа, а значит и ожидания, не существует.
   */
  onArrived?: () => void;
  onDeliver: () => void;
  onGoodsCost?: (kop: number) => void; // buy_bring: ввод фактической стоимости товара
}) {
  const { appText } = useLang();
  const [goods, setGoods] = useState("");
  const needGoods =
    p.delivery_type === "buy_bring" && (p.settlement?.goods_actual_kop ?? 0) <= 0;

  return (
    <div className="parcel-card">
      <div className="parcel-card__head">
        <div className="parcel-card__to">{appText("Кому", "Кемгә")}: {p.receiver_name || "—"}</div>
        <StatusPillParcel status={p.status} />
      </div>
      <ParcelRoute p={p} />

      {/* Телефон получателя — виден только принявшему курьеру */}
      {p.receiver_phone && (
        <a className="parcel-card__phone" href={`tel:${p.receiver_phone}`}>
          <IconPhone size={18} /> {p.receiver_phone}
        </a>
      )}

      {/* Расчёт с получателем (buy_bring) */}
      {p.settlement && (p.settlement.goods_actual_kop > 0) && (
        <div className="parcel-card__settle">
          <span>{appText("Получатель платит", "Алыусы түләй")}</span>
          <b>{rubLabel(p.settlement.total_due_kop)}</b>
        </div>
      )}

      {/* buy_bring: сначала фактическая стоимость товара */}
      {isCarrying(p.status) && needGoods && onGoodsCost ? (
        <div className="parcel-card__goods">
          <span className="field__label">{appText("Сколько потратил на товар, ₽", "Тауарға күпме тотондоң, ₽")}</span>
          <div className="field-row" style={{ marginTop: 6 }}>
            <input
              className="field__input"
              value={goods}
              onChange={(e) => setGoods(e.target.value.replace(/\D/g, "").slice(0, 7))}
              inputMode="numeric"
              placeholder="450"
              style={{ flex: 1 }}
            />
            <button
              type="button"
              className="btn-primary"
              style={{ flex: "none", padding: "0 18px" }}
              onClick={() => onGoodsCost(Number(goods) * 100)}
              disabled={busy || !goods}
            >
              {appText("Сохранить", "Һаҡлау")}
            </button>
          </div>
          <p className="parcel-card__desc" style={{ marginTop: 6 }}>
            {appText(
              "Получатель вернёт эту сумму + доставку при вручении.",
              "Алыусы тапшырғанда был сумманы + доставканы ҡайтара."
            )}
          </p>
        </div>
      ) : (
        <div className="parcel-card__actions">
          {/* «Я на месте» — та же кнопка, что у таксиста, и работает на ВСЕХ трёх концах:
              у отправителя, когда забираешь, у получателя, когда привёз, и снова у
              отправителя, когда везёшь коробку назад. У доставки «по пути» её нет:
              там нет тарифа, а значит и платного ожидания. */}
          {onArrived && (
            <button type="button" className="btn-soft" onClick={onArrived} disabled={busy}>
              {appText("Я на месте", "Мин урында")}
            </button>
          )}
          {p.status === "accepted" && (
            <button type="button" className="btn-soft" onClick={onDepart} disabled={busy}>
              {appText("В пути", "Юлда")}
            </button>
          )}
          {/* Возврат — тоже поездка. Курьер, который привёз коробку назад и снова стоит под
              дверью, должен видеть: время идёт ему, а не в никуда, и выход есть. */}
          {p.status === "returning" && (
            <p className="parcel-card__hint">
              {appText(
                "Везёшь обратно. Отметь «Я на месте» у отправителя — ожидание оплачивается и здесь. Если и его нет дома, открой спор: коробку решит человек, а не приложение.",
                "Кире алып бараһың. Ебәреүсе янында «Мин урында» тип билдәлә — көтөү бында ла түләнә. Ул да өйҙә булмаһа, бәхәс ас: ҡумтаны кеше хәл итер, ҡушымта түгел."
              )}
            </p>
          )}
          {/* Чат вместо звонка: за рулём написать проще, чем говорить. */}
          <Link className="btn-soft" to={`/parcel-chat/${p.id}`}>
            <IconChat size={18} /> {appText("Чат", "Чат")}
          </Link>
          <button type="button" className="btn-primary" onClick={onDeliver} disabled={busy}>
            <IconCheck size={18} /> {appText("Доставлено", "Тапшырылды")}
          </button>
        </div>
      )}
    </div>
  );
}
