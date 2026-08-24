// ================================================================
//  Чек за поездку на такси (GET /instant/orders/{id}/receipt).
//  Зеркало Android TaxiReceiptScreen.kt.
//
//  Три вещи, которых раньше не было ни у кого:
//   • документ о поездке («мне на работе нужен чек»);
//   • «Наличные получил» — водитель закрывает оплату сам, если
//     пассажир вышел и закрыл приложение;
//   • «Я забыл вещь в машине» — открывает чат заказа ещё на 48 ч.
//
//  Телефонов в чеке нет — только факт, маршрут, сумма и оплата.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchTaxiReceipt,
  markCashReceived,
  reportLostItem,
  type TaxiReceipt,
} from "../api/instant";
import { thankOrder, fetchOrderTip, type TipInfo } from "../api/family";
import { LoadingList, ErrorState } from "../components/States";
import SbpPay from "../components/SbpPay";
import PayTripCard from "../components/PayTripCard";
import { SubHeader } from "./ConsentsScreen";
import {
  IconArrow,
  IconCar,
  IconChat,
  IconCheck,
  IconClock,
  IconHeart,
  IconReceipt,
  IconRoute,
  IconShare,
  IconWallet,
} from "../components/Icons";
import { formatWhen, payMethodLabel, priceLabel, rubLabel } from "../utils/format";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "soft"; reason: "notyet" | "pending" }
  | { kind: "ready"; r: TaxiReceipt };

export default function TaxiReceiptScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { orderId } = useParams();
  const id = Number(orderId);

  const [state, setState] = useState<State>({ kind: "loading" });
  const [thanked, setThanked] = useState(false);
  const [busy, setBusy] = useState<"cash" | "lost" | "thanks" | null>(null);
  const [lostOpened, setLostOpened] = useState(false);
  const [note, setNote] = useState<string>("");
  // Чем поблагодарить: тёплое «рәхмәт» всегда, деньги — только если водитель
  // сам их включил. null = сервер ещё не ответил или чаевые выключены.
  const [tip, setTip] = useState<TipInfo | null>(null);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!id) {
        setState({ kind: "error" });
        return;
      }
      setState({ kind: "loading" });
      fetchTaxiReceipt(id, signal)
        .then((r) => setState({ kind: "ready", r }))
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          if (e instanceof ApiError) {
            if (e.status === 409) return setState({ kind: "soft", reason: "pending" });
            if (e.status === 404) return setState({ kind: "soft", reason: "notyet" });
          }
          setState({ kind: "error" });
        });
    },
    [id]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Чаевые — отдельным запросом: их отсутствие не должно ломать чек.
  useEffect(() => {
    if (!id) return;
    const ac = new AbortController();
    fetchOrderTip(id, ac.signal)
      .then((t) => {
        setTip(t);
        if (t.already_thanked) setThanked(true);
      })
      .catch(() => setTip(null));
    return () => ac.abort();
  }, [id]);

  /**
   * «Рәхмәт» — это НЕ оценка. Раньше кнопка ставила пятёрку: она подменяла
   * мнение человека (сказать спасибо можно и после тройки) и портила рейтинг
   * водителя чужими баллами. У сервера для этого своя ручка — тёплый жест
   * без цифр, идемпотентный.
   */
  async function sayThanks() {
    if (busy) return;
    setBusy("thanks");
    try {
      await thankOrder(id);
      setThanked(true);
    } catch {
      setNote(appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла."));
    } finally {
      setBusy(null);
    }
  }

  async function cashReceived() {
    if (busy) return;
    setBusy("cash");
    try {
      await markCashReceived(id);
      load(); // перечитываем — статус оплаты меняет весь блок
    } catch {
      setNote(appText("Не получилось отметить оплату.", "Түләүҙе билдәләп булманы."));
    } finally {
      setBusy(null);
    }
  }

  async function lostItem() {
    if (busy) return;
    setBusy("lost");
    try {
      await reportLostItem(id);
      setLostOpened(true);
    } catch {
      setNote(appText("Не получилось открыть чат.", "Чатты асып булманы."));
    } finally {
      setBusy(null);
    }
  }

  function shareReceipt(r: TaxiReceipt) {
    const text = [
      appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы"),
      `${r.from_text} → ${r.to_text}`,
      formatWhen(r.done_at, ru),
      `${appText("Сумма", "Сумма")}: ${priceLabel(r.amount, ru)}`,
      `${appText("Водитель", "Йөрөтөүсе")}: ${r.driver_name}`,
    ].join("\n");
    if (navigator.share) {
      navigator.share({ title: appText("Чек за поездку", "Сәфәр чегы"), text }).catch(() => {});
    } else {
      navigator.clipboard?.writeText(text).then(
        () => setNote(appText("Чек скопирован", "Чек күсерелде")),
        () => {}
      );
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Чек за поездку", "Сәфәр чегы")}
        onBack={() => navigate(-1)}
      />

      {state.kind === "loading" && <LoadingList count={2} />}
      {state.kind === "error" && <ErrorState onRetry={() => load()} />}

      {state.kind === "soft" && (
        <div className="state" style={{ paddingTop: 40 }}>
          <div className="state__icon">
            <IconReceipt size={34} />
          </div>
          <h2>
            {state.reason === "pending"
              ? appText("Чек ещё не готов", "Чек әҙер түгел")
              : appText("Чек скоро появится", "Чек тиҙҙән күренәсәк")}
          </h2>
          <p>
            {state.reason === "pending"
              ? appText(
                  "Он появится после завершения поездки. Хорошей дороги!",
                  "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!"
                )
              : appText(
                  "Мы включим чеки за такси с ближайшим обновлением сервиса.",
                  "Такси чектарын яҡын яңыртыуҙа тоташтырабыҙ."
                )}
          </p>
        </div>
      )}

      {state.kind === "ready" && (
        <>
          <div className="receipt">
            <div className="receipt__brand">
              <IconHeart size={16} /> {appText("Юлдаш", "Юлдаш")}
              <span className="receipt__num">
                {appText(`Заказ № ${state.r.order_id}`, `Заказ № ${state.r.order_id}`)}
              </span>
            </div>

            <div className="receipt__route">
              <span>{state.r.from_text || appText("Точка А", "А нөктә")}</span>
              <span className="ride-card__arrow">
                <IconArrow size={18} />
              </span>
              <span>{state.r.to_text || appText("Точка Б", "Б нөктә")}</span>
            </div>
            <div className="receipt__date">{formatWhen(state.r.done_at, ru)}</div>

            <div className="receipt__rows">
              <div className="info-row">
                <span className="info-row__k">
                  <IconClock size={15} /> {appText("Дата и время", "Көн һәм ваҡыт")}
                </span>
                <span className="info-row__v">{formatWhen(state.r.done_at, ru)}</span>
              </div>
              {state.r.distance_km != null && state.r.distance_km > 0 && (
                <div className="info-row">
                  <span className="info-row__k">
                    <IconRoute size={15} /> {appText("Расстояние", "Ара")}
                  </span>
                  <span className="info-row__v">
                    {state.r.distance_km.toFixed(1)} {appText("км", "км")}
                  </span>
                </div>
              )}
              <div className="info-row">
                <span className="info-row__k">
                  <IconCar size={15} /> {appText("Водитель", "Йөрөтөүсе")}
                </span>
                <span className="info-row__v">
                  {state.r.driver_name}
                  {state.r.driver_verified && (
                    <span className="badge badge--mint" style={{ marginLeft: 6 }}>
                      <IconCheck size={12} />
                    </span>
                  )}
                </span>
              </div>
              <div className="info-row">
                <span className="info-row__k">
                  <IconWallet size={15} /> {appText("Способ оплаты", "Түләү ысулы")}
                </span>
                <span className="info-row__v">{payMethodLabel(state.r.payment_method, ru)}</span>
              </div>
              <div className="info-row">
                <span className="info-row__k">{appText("Статус оплаты", "Түләү хәле")}</span>
                <span className="info-row__v">
                  {state.r.paid ? (
                    <span className="badge badge--mint">
                      <IconCheck size={12} /> {appText("Оплачено", "Түләнгән")}
                    </span>
                  ) : (
                    appText("Не отмечена", "Билдәләнмәгән")
                  )}
                </span>
              </div>
              {state.r.promo_discount_kop > 0 && (
                <div className="info-row">
                  <span className="info-row__k">{appText("Скидка по промокоду", "Промокод буйынса ташлама")}</span>
                  <span className="info-row__v">−{rubLabel(state.r.promo_discount_kop)}</span>
                </div>
              )}
            </div>

            {/* Из чего сложилась сумма. Каждая строка — причина, по которой цена такая. */}
            {(state.r.ride_price ?? 0) > 0 && (
              <div className="info-list">
                <div className="info-row">
                  <span className="info-row__k">{appText("Поездка", "Сәфәр")}</span>
                  <span className="info-row__v">
                    {(state.r.ride_base_price || state.r.ride_price) ?? 0} ₽
                  </span>
                </div>
                {(state.r.surge_rub ?? 0) > 0 && (
                  <div className="info-row">
                    <span className="info-row__k">{appText("Наценка за спрос", "Ихтыяж өҫтәмәһе")}</span>
                    <span className="info-row__v">+{state.r.surge_rub} ₽</span>
                  </div>
                )}
                {(state.r.pickup_fee_kop ?? 0) > 0 && (
                  <div className="info-row">
                    <span className="info-row__k">
                      {appText(
                        `Дорога водителя к тебе, ~${Math.round(state.r.pickup_km ?? 0)} км`,
                        `Водителдең һиңә тиклем юлы, ~${Math.round(state.r.pickup_km ?? 0)} км`
                      )}
                    </span>
                    <span className="info-row__v">+{rubLabel(state.r.pickup_fee_kop ?? 0)}</span>
                  </div>
                )}
                {(state.r.weather_fee_kop ?? 0) > 0 && (
                  <div className="info-row">
                    <span className="info-row__k">
                      {state.r.weather_kind === "ice"
                        ? appText("Гололёд на дороге", "Юлда быҙлауыҡ")
                        : state.r.weather_kind === "blizzard"
                          ? appText("Метель по пути", "Юлда буран")
                          : state.r.weather_kind === "frost"
                            ? appText("Сильный мороз", "Ҡаты һыуыҡ")
                            : appText("Тяжёлая дорога", "Ауыр юл")}
                    </span>
                    <span className="info-row__v">+{rubLabel(state.r.weather_fee_kop ?? 0)}</span>
                  </div>
                )}
                {(state.r.options_fee_kop ?? 0) > 0 && (
                  <div className="info-row">
                    <span className="info-row__k">{appText("Кресло и опции", "Ултырғыс һәм өҫтәмәләр")}</span>
                    <span className="info-row__v">+{rubLabel(state.r.options_fee_kop ?? 0)}</span>
                  </div>
                )}
                {state.r.role === "driver" && (state.r.driver_gross_kop ?? 0) > 0 && (
                  <>
                    <div className="info-row">
                      <span className="info-row__k">{appText("Всего от пассажира", "Пассажирҙан барлығы")}</span>
                      <span className="info-row__v">{rubLabel(state.r.driver_gross_kop ?? 0)}</span>
                    </div>
                    <div className="info-row">
                      <span className="info-row__k">
                        {appText(
                          `Комиссия Юлдаша ${state.r.driver_fee_percent ?? 0}%`,
                          `Юлдаш комиссияһы ${state.r.driver_fee_percent ?? 0}%`
                        )}
                      </span>
                      <span className="info-row__v">−{rubLabel(state.r.driver_fee_kop ?? 0)}</span>
                    </div>
                    <div className="info-row">
                      <span className="info-row__k">{appText("Чистыми тебе", "Һиңә таҙа килем")}</span>
                      <span className="info-row__v">{rubLabel(state.r.driver_net_kop ?? 0)}</span>
                    </div>
                  </>
                )}
              </div>
            )}

            <div className="receipt__total">
              <span>{appText("Итого", "Барлығы")}</span>
              <b>{priceLabel(state.r.amount, ru)}</b>
            </div>

            {state.r.waiting_fee_kop > 0 && (
              <p className="receipt__foot">
                {appText("В том числе ожидание: ", "Шул иҫәптән көтөү: ")}
                {rubLabel(state.r.waiting_fee_kop)}
              </p>
            )}

            <button
              type="button"
              className="btn-soft"
              style={{ width: "100%", marginTop: 14 }}
              onClick={() => shareReceipt(state.r)}
            >
              <IconShare size={18} /> {appText("Поделиться", "Бүлешеү")}
            </button>
          </div>

          {/* Пассажир, поездка не оплачена — способ рассчитаться */}
          {state.r.role === "passenger" && !state.r.paid && (
            <PayTripCard
              kind="order"
              id={id}
              amountLabel={priceLabel(state.r.amount, ru)}
              onPaid={() => load()}
            />
          )}

          {/* Пассажир: тёплое спасибо водителю — без денег */}
          {state.r.role === "passenger" && (
            <div className="act-card" style={{ marginTop: 14 }}>
              <div className="act-card__title">
                <IconHeart size={18} />{" "}
                {thanked
                  ? appText("Рәхмәт сказан 💚", "Рәхмәт әйтелде 💚")
                  : appText("Сказать рәхмәт", "Рәхмәт әйтеү")}
              </div>
              <p className="act-card__text">
                {appText("Тёплое спасибо водителю — без денег.", "Йөрөтөүсегә йылы рәхмәт — аҡсаһыҙ.")}
              </p>
              {!thanked && (
                <button
                  type="button"
                  className="btn-primary"
                  style={{ width: "100%" }}
                  onClick={sayThanks}
                  disabled={busy === "thanks"}
                >
                  {busy === "thanks"
                    ? appText("Отправляем…", "Ебәрәбеҙ…")
                    : appText("Сказать рәхмәт", "Рәхмәт әйтеү")}
                </button>
              )}

              {/* Деньгами — только если водитель сам включил чаевые и оставил номер.
                  Его телефон до этого наружу не идёт вовсе. Кнопки «дать чаевые»
                  по умолчанию нет: у нас скидываются на бензин, а не доплачивают
                  сверху, и превращать спасибо в обязанность мы не хотим. */}
              {tip?.money && (
                <div className="tip-money">
                  <div className="tip-money__label">
                    {appText("Можно и деньгами — по желанию", "Аҡса менән дә була — теләк буйынса")}
                  </div>
                  <SbpPay phone={tip.money.sbp} name={tip.money.name} />
                </div>
              )}
            </div>
          )}

          {/* Водитель: пассажир ушёл, не отметив оплату — закрываем сами */}
          {state.r.role === "driver" && !state.r.paid && (
            <div className="act-card act-card--warn" style={{ marginTop: 14 }}>
              <div className="act-card__title">
                <IconWallet size={18} /> {appText("Оплата не отмечена", "Түләү билдәләнмәгән")}
              </div>
              <p className="act-card__text">
                {appText(
                  "Пассажир мог выйти и закрыть приложение. Если деньги у тебя — отметь сам.",
                  "Пассажир сығып, ҡушымтаны ябыуы мөмкин. Аҡса һиндә булһа — үҙең билдәлә."
                )}
              </p>
              <button
                type="button"
                className="btn-primary"
                style={{ width: "100%" }}
                onClick={cashReceived}
                disabled={busy === "cash"}
              >
                {busy === "cash"
                  ? appText("Отмечаем…", "Билдәләйбеҙ…")
                  : appText("Наличные получил", "Аҡсаны алдым")}
              </button>
            </div>
          )}

          {/* Забытая вещь — доступно обеим сторонам */}
          <div className="act-card" style={{ marginTop: 14 }}>
            <div className="act-card__title">
              <IconChat size={18} /> {appText("Забыли вещь?", "Әйбер онотолдомо?")}
            </div>
            <p className="act-card__text">
              {lostOpened
                ? appText(
                    "Чат снова открыт на 48 часов — напиши, что искать.",
                    "Чат 48 сәғәткә кире асыҡ — нимә эҙләргә, яҙ."
                  )
                : appText(
                    "Откроем чат этой поездки на 48 часов, чтобы вы связались.",
                    "Бәйләнешер өсөн был сәфәр чатын 48 сәғәткә асабыҙ."
                  )}
            </p>
            {lostOpened ? (
              <button
                type="button"
                className="btn-primary"
                style={{ width: "100%" }}
                onClick={() => navigate(`/taxi-chat/${state.r.order_id}`)}
              >
                {appText("Открыть чат поездки", "Сәфәр чатын асыу")}
              </button>
            ) : (
              <button
                type="button"
                className="btn-soft"
                style={{ width: "100%" }}
                onClick={lostItem}
                disabled={busy === "lost"}
              >
                {busy === "lost"
                  ? appText("Открываем…", "Асабыҙ…")
                  : appText("Я забыл вещь в машине", "Машинала әйбер ҡалдырҙым")}
              </button>
            )}
          </div>

          {note && <p className="taxi-note">{note}</p>}
        </>
      )}
    </>
  );
}
