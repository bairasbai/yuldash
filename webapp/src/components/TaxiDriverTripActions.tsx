// ================================================================
//  Что ВОДИТЕЛЬ делает во время поездки на такси.
//  Зеркало Android (TaxiTripScreen.kt + InstantOrderScreen.kt).
//
//  Три вещи, которых в вебе не было совсем:
//  · пассажир сменил адрес → «Понял» / согласиться / не смогу;
//  · «Стоим» на остановке и «Поехали», когда тронулись;
//  · «Застрял на трассе» — помощь, которая мягче красной кнопки SOS.
//  (сверка с Android, 2026-08-30)
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  acceptDestination,
  ackPaymentMethod,
  ackDestination,
  declineDestination,
  toggleStop,
  type DeclineDestinationReason,
  type InstantOrder,
} from "../api/instant";
import { IconCar, IconCheck, IconPin, IconClock, IconWallet } from "./Icons";
import { RoadsideButton } from "./TaxiTripActions";
import { PAY_METHODS_OPEN } from "./PayMethodPicker";

/** Почему не могу ехать дальше. Причина нужна пассажиру, а не отчётности. */
const DECLINE_REASONS: { code: DeclineDestinationReason; ru: string; ba: string }[] = [
  { code: "shift_end", ru: "Смена заканчивается", ba: "Сменам бөтә" },
  { code: "out_of_zone", ru: "Далеко от моей зоны", ba: "Минең зонанан алыҫ" },
  { code: "no_fuel", ru: "Не хватит топлива", ba: "Яғыулыҡ етмәй" },
  { code: "other", ru: "Другая причина", ba: "Башҡа сәбәп" },
];

/**
 * Пассажир поменял адрес.
 *
 * Два разных случая, и путать их нельзя:
 * · обычная смена — адрес УЖЕ поменялся, от водителя нужно только «понял, вижу».
 *   Пока он не отметил, пассажиру через минуту говорят «он ещё не видел, позвони»;
 * · крупная смена (межгород или цена втрое) — адрес ещё НЕ поменялся, и водитель
 *   решает сам: пять часов за руль и ночёвка в чужом городе это другая работа.
 *
 * Отказ от крупной смены НЕ рвёт поездку: едем по старому адресу дальше. А отказ
 * посреди пути завершает поездку там, где стоит машина, — километры проеханы,
 * деньги за них причитаются. Это не отмена, и водителя за неё не наказывают.
 */
function DestinationChanged({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const [askWhy, setAskWhy] = useState(false);

  const pending = order.pending_destination;
  const needAck = !pending && (order.destination_changes ?? 0) > 0 && !order.destination_ack;

  async function run(fn: () => Promise<unknown>, fail: { ru: string; ba: string }) {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      await fn();
      setAskWhy(false);
      onDone();
    } catch (e) {
      setNote(e instanceof ApiError && e.message ? e.message : appText(fail.ru, fail.ba));
    } finally {
      setBusy(false);
    }
  }

  if (!pending && !needAck) return null;

  // --- Крупная смена: слово за водителем ---
  if (pending) {
    return (
      <div className="act-card act-card--warn">
        <div className="act-card__title">
          <IconPin size={18} /> {appText("Пассажир просит другой адрес", "Юлаусы башҡа адрес һорай")}
        </div>
        <div className="info-list" style={{ marginTop: 0 }}>
          <div className="info-row">
            <span className="info-row__k">{appText("Куда", "Ҡайҙа")}</span>
            <span className="info-row__v">{pending.to_text}</span>
          </div>
          <div className="info-row">
            <span className="info-row__k">{appText("Станет", "Буласаҡ")}</span>
            <span className="info-row__v">
              <b>{ru ? `${pending.price} ₽` : `${pending.price} һум`}</b>
            </span>
          </div>
        </div>
        <p className="act-card__text">
          {pending.reason === "intercity"
            ? appText(
                "Это другой город. Решай сам: откажешься — едем по прежнему адресу, поездка не прервётся.",
                "Был башҡа ҡала. Үҙең хәл ит: баш тартһаң — элекке адрес буйынса барабыҙ, сәфәр өҙөлмәй."
              )
            : appText(
                "Цена сильно выросла. Откажешься — едем по прежнему адресу, поездка не прервётся.",
                "Хаҡ ныҡ артты. Баш тартһаң — элекке адрес буйынса барабыҙ, сәфәр өҙөлмәй."
              )}
        </p>
        <div className="act-card__actions">
          <button
            type="button"
            className="btn-primary"
            disabled={busy}
            onClick={() =>
              void run(() => acceptDestination(order.id), {
                ru: "Не получилось согласиться. Попробуй ещё раз.",
                ba: "Риза булып булманы. Тағы ҡабатла.",
              })
            }
          >
            {appText("Согласен, едем", "Риза, киттек")}
          </button>
          <button
            type="button"
            className="btn-ghost"
            disabled={busy}
            onClick={() =>
              void run(() => declineDestination(order.id, "out_of_zone"), {
                ru: "Не получилось отказаться. Попробуй ещё раз.",
                ba: "Баш тартып булманы. Тағы ҡабатла.",
              })
            }
          >
            {appText("Не поеду туда", "Унда бармайым")}
          </button>
        </div>
        {note && (
          <div className="notice" role="status">
            {note}
          </div>
        )}
      </div>
    );
  }

  // --- Обычная смена: адрес уже новый, нужно «понял» ---
  return (
    <div className="act-card act-card--warn">
      <div className="act-card__title">
        <IconPin size={18} /> {appText("Новый адрес", "Яңы адрес")}
      </div>
      <p className="act-card__text">
        {appText(
          `Пассажир изменил, куда едем: ${order.to_text}. Цена пересчитана.`,
          `Юлаусы барыр урынды үҙгәртте: ${order.to_text}. Хаҡ ҡабат иҫәпләнде.`
        )}
      </p>
      <div className="act-card__actions">
        <button
          type="button"
          className="btn-primary"
          disabled={busy}
          onClick={() =>
            void run(() => ackDestination(order.id), {
              ru: "Не отметилось. Попробуй ещё раз.",
              ba: "Билдәләнмәне. Тағы ҡабатла.",
            })
          }
        >
          <IconCheck size={18} /> {appText("Понял, вижу", "Аңланым, күрәм")}
        </button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={() => setAskWhy(true)}>
          {appText("Не смогу ехать", "Бара алмайым")}
        </button>
      </div>

      {askWhy && (
        <div className="sheet-backdrop" onClick={() => setAskWhy(false)}>
          <div className="sheet" onClick={(e) => e.stopPropagation()}>
            <h2 className="sheet__title">{appText("Почему не сможешь?", "Ниңә бара алмайһың?")}</h2>
            <p className="sheet__comment">
              {appText(
                "Поездка завершится там, где стоит машина. Ты получишь деньги за проеденное — это не отмена, работа сделана. Пассажир увидит причину словами.",
                "Сәфәр машина торған урында тамамлана. Үтелгән юл өсөн аҡса алаһың — был кире алыу түгел, эш башҡарылған. Юлаусы сәбәпте һүҙ менән күрә."
              )}
            </p>
            {DECLINE_REASONS.map((r) => (
              <button
                key={r.code}
                type="button"
                className="btn-soft"
                style={{ width: "100%", marginBottom: 8 }}
                disabled={busy}
                onClick={() =>
                  void run(() => declineDestination(order.id, r.code), {
                    ru: "Не получилось. Попробуй ещё раз.",
                    ba: "Килеп сыҡманы. Тағы ҡабатла.",
                  })
                }
              >
                {appText(r.ru, r.ba)}
              </button>
            ))}
            <button type="button" className="btn-ghost" onClick={() => setAskWhy(false)} disabled={busy}>
              {appText("Всё-таки поеду", "Барам барыбер")}
            </button>
          </div>
        </div>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </div>
  );
}

/** Способ расчёта всегда виден водителю; сменившийся требует явного «Понял». */
function DriverPaymentMethod({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText, lang } = useLang();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const method = PAY_METHODS_OPEN.find((item) => item.code === order.payment_method) ?? PAY_METHODS_OPEN[2];
  const changed = Boolean(order.payment_changed);

  async function acknowledge() {
    if (busy) return;
    setBusy(true);
    setNote("");
    try {
      await ackPaymentMethod(order.id);
      onDone();
    } catch (error) {
      setNote(
        error instanceof ApiError && error.message
          ? error.message
          : appText("Не отметилось. Попробуй ещё раз.", "Билдәләнмәне. Тағы ҡабатла.")
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={"act-card" + (changed ? " act-card--warn" : "")}>
      <div className="act-card__title">
        <IconWallet size={18} />
        {changed
          ? appText("Способ расчёта изменился", "Түләү ысулы үҙгәрҙе")
          : appText("Рассчитаются", "Иҫәпләшәләр")}
      </div>
      <p className="act-card__text">{lang === "ba" ? method.ba : method.ru}</p>
      {changed && (
        <button type="button" className="btn-primary btn-taxi trip-btn" disabled={busy} onClick={() => void acknowledge()}>
          <IconCheck size={18} /> {appText("Понял", "Аңланым")}
        </button>
      )}
      {note && <div className="notice" role="status">{note}</div>}
    </div>
  );
}

/**
 * «Стоим» на остановке и «Поехали», когда тронулись.
 *
 * Кнопкой, а не автоматом по координатам: машина, застрявшая в пробке у светофора
 * рядом с остановкой, начала бы «зарабатывать» сама, а разбираться пришлось бы
 * пассажиру. Нажал — оба видят, что счётчик пошёл.
 */
function StandingToggle({ order, onDone }: { order: InstantOrder; onDone: () => void }) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const standing = !!order.standing;

  return (
    <button
      type="button"
      className={standing ? "btn-primary trip-btn" : "btn-soft trip-btn"}
      disabled={busy}
      onClick={() => {
        setBusy(true);
        toggleStop(order.id)
          .then(onDone)
          .catch(() => {
            /* статус сменился или нет сети — поллинг подтянет правду */
          })
          .finally(() => setBusy(false));
      }}
    >
      <IconClock size={18} />{" "}
      {standing ? appText("Поехали", "Киттек") : appText("Стоим на остановке", "Туҡталышта торабыҙ")}
    </button>
  );
}

/**
 * Всё, что водителю нужно в пути, одним блоком.
 *
 * Порядок не случайный: сверху то, что требует ответа прямо сейчас (смена адреса),
 * ниже — обычные кнопки. Помощь на трассе только в пути: до посадки водитель ещё
 * едет один и у него есть обычный SOS.
 */
export default function TaxiDriverTripActions({
  order,
  onChanged,
}: {
  order: InstantOrder;
  onChanged: () => void;
}) {
  const { appText } = useLang();
  const s = order.status;
  if (s !== "accepted" && s !== "arriving" && s !== "onboard") return null;

  const stops = (order.stops ?? []).filter((x) => !x.done);

  return (
    <div className="trip-actions">
      <DestinationChanged order={order} onDone={onChanged} />
      <DriverPaymentMethod order={order} onDone={onChanged} />

      {/* Куда заезжаем по пути. Водитель должен видеть это списком, а не узнавать голосом. */}
      {stops.length > 0 && (
        <div className="act-card">
          <div className="act-card__title">
            <IconCar size={18} /> {appText("Заезды по пути", "Юл ыңғайы туҡтауҙар")}
          </div>
          <ul className="trip-stops">
            {stops.map((x, i) => (
              <li key={i} className="trip-stops__row">
                <IconPin size={16} />
                <span>{x.text || appText("Точка на карте", "Картала нөктә")}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {s === "onboard" && <StandingToggle order={order} onDone={onChanged} />}
      {s === "onboard" && <RoadsideButton orderId={order.id} />}
    </div>
  );
}
