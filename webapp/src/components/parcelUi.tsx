// ================================================================
//  Общие кусочки UI посылок/курьера (волна 5): подписи размера/
//  статуса, карточка доступной заявки («Взять»), карточка «Везу»
//  (двигать статус + диалог кода вручения + «купи и привези»),
//  диалог ввода кода. Переиспользуют Canon-токены и i18n appText.
// ================================================================
import { useState } from "react";
import { Link } from "react-router-dom";
import { useLang } from "../i18n/lang";
import type { Parcel } from "../api/parcels";
import { isCarrying } from "../api/parcels";
import { dayMonthLong, rubLabel } from "../utils/format";
import { IconArrow, IconPhone, IconCheck, IconChat, IconBox, IconProfile } from "./Icons";
import ParcelPhoto from "./ParcelPhoto";
import ParcelPhotoStrip from "./ParcelPhotoStrip";
import { YuRoute } from "./BrandIcons";
import { openNavigator } from "../utils/navigator";
import {
  ParcelAddressBlock,
  ParcelDeadlineNote,
  ParcelReturnNotice,
  ParcelRouteRow,
  cargoTypeEmoji,
  cargoTypeLabel,
  sizeLabel,
} from "./parcelForm";
import { CourierDeliveryProgress } from "./TaxiTripProgress";

// ------------------------------- Подписи -------------------------------
export { sizeLabel } from "./parcelForm";

/** Бейдж статуса — Android ParcelStatusChip: радиус 14, поля 12×4, подпись 14 Bold;
 *  ждёт — CanonWarnBg/CanonWarn, в работе и доставлена — CanonMint/CanonGreen2, отмена — красная. */
export function StatusPillParcel({ status }: { status: string }) {
  const { appText } = useLang();
  const map: Record<string, { ru: string; ba: string; cls: string }> = {
    created: { ru: "Ждёт курьера", ba: "Курьерҙы көтә", cls: "parcel-status--wait" },
    accepted: { ru: "У курьера", ba: "Курьерҙа", cls: "parcel-status--mint" },
    in_transit: { ru: "В пути", ba: "Юлда", cls: "parcel-status--mint" },
    delivered: { ru: "Доставлена", ba: "Тапшырылды", cls: "parcel-status--mint" },
    canceled: { ru: "Отменена", ba: "Кире алынған", cls: "parcel-status--danger" },
    cancelled: { ru: "Отменена", ba: "Кире алынған", cls: "parcel-status--danger" },
    // Возврат (аудит сценариев 30.08, P0). Этих двух статусов в вебе не было, и человек,
    // чью коробку уже везут обратно, читал «Ищем курьера» — потому что незнакомый статус
    // молча падал в значение по умолчанию. Приложение показывает возврат давно.
    returning: { ru: "Везут обратно", ba: "Кире алып киләләр", cls: "parcel-status--wait" },
    returned: { ru: "Вернулась", ba: "Кире ҡайтты", cls: "parcel-status--wait" },
  };
  // Незнакомый статус НЕ выдаём за «ждёт курьера»: лучше показать сырое слово, чем
  // уверенно соврать. Так следующий новый статус на сервере будет видно сразу.
  const m = map[status];
  if (!m) return <span className="parcel-status">{status}</span>;
  // key — чтобы новая подпись въезжала с затуханием (AnimatedContent), а не подменялась кадром.
  return <span key={status} className={`parcel-status ${m.cls}`}>{appText(m.ru, m.ba)}</span>;
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
  // CourierOfferCard Android: плитка с коробкой, «Твой доход» + сумма 24 Bold (за вычетом комиссии), пилюля типа доставки.
  const paid = p.price_kop > 0;
  const netKop = Math.max(0, p.price_kop - (p.commission_kop ?? 0));
  const deliveryLabel =
    p.delivery_type === "buy_bring"
      ? appText("Купи и привези", "Һатып ал да килтер")
      : p.delivery_type === "courier"
        ? appText("Курьер", "Курьер")
        : appText("По пути", "Юл ыңғайы");
  return (
    <div className="parcel-card parcel-offer">
      <div className="parcel-offer__head">
        <span className="parcel-offer__tile" aria-hidden><IconBox size={22} /></span>
        <span className="parcel-offer__price">
          <small>{paid ? appText("Твой доход", "Һинең килем") : appText("Без оплаты", "Түләүһеҙ")}</small>
          <b>{paid ? `≈ ${rubLabel(netKop)}` : appText("По-соседски", "Күрше хаҡы")}</b>
        </span>
        <span className="parcel-offer__type">{deliveryLabel}</span>
      </div>
      <ParcelRoute p={p} />
      <div className="parcel-offer__tags">
        <span className="parcel-offer__tag">{appText("Кому", "Кемгә")}: {p.receiver_name || "—"}</span>
        <span className="parcel-offer__tag">{appText("Адрес — когда возьмёшь", "Алғас — адрес күренә")}</span>
      </div>
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
/**
 * Карточка доставки у курьера — зеркало Android CourierCarryingCard: маршрут и статус, рельса
 * «Забрать · В пути · Вручить», возврат и попытки, срок, теги груза, адреса с зелёной рамкой,
 * контакты мятной плашкой, доход, действия. Логика (статусы, код, «купи и привези») прежняя.
 */
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
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [goods, setGoods] = useState("");
  const buyBring = p.delivery_type === "buy_bring";
  const needGoods = buyBring && (p.settlement?.goods_actual_kop ?? 0) <= 0;
  const delivered = p.status === "delivered";
  const returning = p.status === "returning" || p.status === "returned";
  const showSender =
    (p.status === "accepted" || p.status === "in_transit" || returning) &&
    !!(p.sender_name?.trim() || p.sender_phone?.trim());
  const myIncome = Math.max(0, p.price_kop - (p.commission_kop ?? 0));

  return (
    <div className="parcel-card">
      <div className="parcel-card__head parcel-card__head--route">
        <div className="parcel-card__route">
          <ParcelRouteRow from={p.from_city} to={p.to_city} />
          <div className="dl-hint">
            {sizeLabel(p.size, ru)}
            {p.description ? `  ·  ${p.description}` : ""}
          </div>
        </div>
        <StatusPillParcel status={p.status} />
      </div>
      <CourierDeliveryProgress status={p.status} />
      <ParcelReturnNotice status={p.status} reason={p.return_reason} forCourier />
      {/* Сколько раз уже пытались вручить. Курьеру это меняет план: на третий заход
          он поедет не «как получится», а договорившись по телефону заранее. */}
      {(p.delivery_attempts ?? 0) > 0 && (
        <div className="parcel-card__warn parcel-card__warn--strong">
          {appText("Попыток вручения: ", "Тапшырыу маташыуы: ")}
          {p.delivery_attempts}
          {appText(". Отправителю сообщили — посылка остаётся у тебя.", ". Ебәреүсегә хәбәр ителде — бандероль һиндә ҡала.")}
        </div>
      )}
      <ParcelDeadlineNote deliverBy={p.deliver_by} overdue={p.overdue} status={p.status} forCourier />
      <CourierCargoRow p={p} />
      <ParcelAddressBlock from={p.from_address} to={p.to_address} prominent />
      {/* Маршрут в навигаторе — туда, где машина нужна ПРЯМО СЕЙЧАС: пока посылка не забрана —
          к отправителю, в пути — к получателю, на возврате — снова к отправителю. */}
      {(() => {
        const navActive = p.status === "accepted" || p.status === "in_transit" || p.status === "returning";
        const toPickup = p.status === "accepted" || p.status === "returning";
        const lat = toPickup ? p.from_lat : p.to_lat;
        const lng = toPickup ? p.from_lng : p.to_lng;
        if (!navActive || lat == null || lng == null) return null;
        return (
          <>
            <button type="button" className="btn-soft btn-soft--compact" onClick={() => openNavigator(lat, lng)}>
              <YuRoute size={18} /> {appText("Маршрут", "Юл")}
            </button>
            <p className="dl-hint">
              {toPickup ? appText("Откроет навигатор к точке забора.", "Алып китеү нөктәһенә навигаторҙы аса.") : appText("Откроет навигатор к точке вручения.", "Тапшырыу нөктәһенә навигаторҙы аса.")}
            </p>
          </>
        );
      })()}
      {/* Что курьер снял сам: подтверждение его же добросовестности, если начнётся спор. */}
      <ParcelPhotoStrip pickupUrl={p.pickup_photo_url} deliveryUrl={p.delivery_photo_url} />

      {/* Контакты: получатель всегда, отправитель — пока посылка в работе (и при возврате —
          это точка возврата). Телефон ОТПРАВИТЕЛЯ нужен ровно тогда, когда что-то пошло не так:
          получатель не открывает, адрес не тот, вещь не влезает в багажник. */}
      <div className="contact-block">
        <CourierContact label={appText("Получатель", "Алыусы")} name={p.receiver_name} phone={p.receiver_phone} />
        {showSender && (
          <>
            <span className="contact-block__rule" aria-hidden />
            <CourierContact
              label={returning ? appText("Отправитель · точка возврата", "Ебәреүсе · ҡайтарыу урыны") : appText("Отправитель · точка забора", "Ебәреүсе · алып китеү урыны")}
              name={p.sender_name}
              phone={p.sender_phone}
            />
            {/* Чат вместо звонка: за рулём написать проще, чем говорить. */}
            <Link className="btn-soft btn-soft--compact" to={`/parcel-chat/${p.id}`}>
              <IconChat size={18} /> {appText("Написать отправителю", "Ебәреүсегә яҙырға")}
            </Link>
          </>
        )}
      </div>

      {/* Расчёт с получателем (buy_bring) */}
      {buyBring &&
        (p.settlement && p.settlement.goods_actual_kop > 0 ? (
          <div className="parcel-card__settle">
            <span>{appText("Получатель платит", "Алыусы түләй")}</span>
            <b>{rubLabel(p.settlement.total_due_kop)}</b>
          </div>
        ) : (
          p.cod_amount_kop > 0 && (
            <p className="dl-hint dl-hint--warn">
              {appText("Выкуп товара: ", "Тауар выкупы: ")}
              {rubLabel(p.cod_amount_kop)}
            </p>
          )
        ))}

      {/* Деньги одной строкой: при возврате комиссии нет, после отмены — компенсация, иначе доход. */}
      {returning ? (
        <p className="dl-hint dl-hint--warn">
          {appText(
            "При возврате комиссию Юлдаша не берём. Расчёт по расходам — напрямую с отправителем.",
            "Ҡайтарғанда Юлдаш комиссия алмай. Сығымдар буйынса ебәреүсе менән туранан-тура иҫәпләш."
          )}
        </p>
      ) : (p.cancel_fee_kop ?? 0) > 0 ? (
        <p className="dl-hint dl-hint--warn">
          {appText("Компенсация от отправителя: ", "Ебәреүсенән компенсация: ")}
          {rubLabel(p.cancel_fee_kop ?? 0)}
        </p>
      ) : (
        <p className="dl-hint">
          {p.price_kop > 0
            ? appText(
                `Твой доход: ${rubLabel(myIncome)} (наш сбор ${rubLabel(p.commission_kop ?? 0)}${delivered ? "" : " ≈ ориентировочно"})`,
                `Һинең килем: ${rubLabel(myIncome)} (беҙҙең сбор ${rubLabel(p.commission_kop ?? 0)}${delivered ? "" : " ≈ самаға"})`
              )
            : appText("По-соседски, без оплаты", "Күрше хаҡы, түләүһеҙ")}
        </p>
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
              className="btn-primary btn-accent"
              style={{ flex: "none", padding: "0 18px", marginTop: 0 }}
              onClick={() => onGoodsCost(Number(goods) * 100)}
              disabled={busy || !goods}
            >
              {appText("Сохранить", "Һаҡлау")}
            </button>
          </div>
          <p className="dl-hint" style={{ marginTop: 6 }}>
            {appText(
              "Сначала укажи стоимость покупки — потом сможешь вручить заказ.",
              "Тәүҙә һатып алыу хаҡын күрһәт — шунан заказды тапшыра алырһың."
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
            <button type="button" className="btn-soft btn-soft--compact" onClick={onArrived} disabled={busy}>
              {appText("Я на месте", "Мин урында")}
            </button>
          )}
          {/* Возврат — тоже поездка. Курьер, который привёз коробку назад и снова стоит под
              дверью, должен видеть: время идёт ему, а не в никуда, и выход есть. */}
          {p.status === "returning" && (
            <p className="dl-hint">
              {appText(
                "Везёшь обратно. Отметь «Я на месте» у отправителя — ожидание оплачивается и здесь. Если и его нет дома, открой спор: коробку решит человек, а не приложение.",
                "Кире алып бараһың. Ебәреүсе янында «Мин урында» тип билдәлә — көтөү бында ла түләнә. Ул да өйҙә булмаһа, бәхәс ас: ҡумтаны кеше хәл итер, ҡушымта түгел."
              )}
            </p>
          )}
          <div className="parcel-card__actions-row">
            {p.status === "accepted" && (
              <button type="button" className="btn-soft" onClick={onDepart} disabled={busy}>
                {appText("В пути", "Юлда")}
              </button>
            )}
            <button type="button" className="btn-primary" onClick={onDeliver} disabled={busy}>
              <IconCheck size={18} /> {appText("Доставлено", "Тапшырылды")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/** Теги груза курьеру: вес, что внутри, «хрупкое» с обводкой — на них решается «беру / не беру». */
function CourierCargoRow({ p }: { p: Parcel }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const hasWeight = (p.weight_kg ?? 0) > 0;
  const hasType = !!p.cargo_type?.trim();
  if (!hasWeight && !hasType && !p.fragile) return null;
  return (
    <div className="cargo-tags">
      {hasWeight && <span className="cargo-tag">⚖️  {String(p.weight_kg).replace(".", ",")} кг</span>}
      {hasType && (
        <span className="cargo-tag">
          {cargoTypeEmoji(p.cargo_type ?? "")}  {cargoTypeLabel(p.cargo_type ?? "", ru)}
        </span>
      )}
      {p.fragile && <span className="cargo-tag cargo-tag--warn">⚠️  {appText("Хрупкое", "Ватыла торған")}</span>}
    </div>
  );
}

/** Контакт стороны: подпись, имя с иконкой, кнопка-строка «телефон · Позвонить». */
function CourierContact({ label, name, phone }: { label: string; name?: string; phone?: string }) {
  const { appText } = useLang();
  return (
    <div className="contact">
      <small className="contact__label">{label}</small>
      {name?.trim() && (
        <span className="contact__name">
          <IconProfile size={16} />
          <strong>{name}</strong>
        </span>
      )}
      {phone?.trim() && (
        <a className="contact__call" href={`tel:${phone}`}>
          <IconPhone size={18} />
          <b>{phone}</b>
          <span>{appText("Позвонить", "Шылтыратыу")}</span>
        </a>
      )}
    </div>
  );
}
