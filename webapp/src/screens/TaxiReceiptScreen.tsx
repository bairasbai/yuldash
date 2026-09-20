// ================================================================
//  Чек за поездку на такси (GET /instant/orders/{id}/receipt).
//  Зеркало Android TaxiReceiptScreen.kt: шапка «Детали поездки» с «Поделиться»,
//  документ (печать «Поездка завершена», сумма крупно, способ оплаты, разбивка,
//  «Итого», маршрут, дата · км, водитель + «Заказ №»), звёзды оценки, «Ещё можно»:
//  «рәхмәт» (пассажир), «Наличные получил» (водитель), поделиться, забытая вещь,
//  «Проблема с поездкой» → сообщить о нарушении или открыть разбор.
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
  rateInstantOrder,
  reportLostItem,
  type TaxiReceipt,
} from "../api/instant";
import { thankOrder, fetchOrderTip, type TipInfo } from "../api/family";
import FileIncidentCard from "../components/FileIncidentCard";
import { RideCardSkeleton, ErrorState } from "../components/States";
import SbpPay from "../components/SbpPay";
import PayTripCard from "../components/PayTripCard";
import RouteTimeline from "../components/RouteTimeline";
import {
  IconChat,
  IconCheck,
  IconChevron,
  IconClock,
  IconFlag,
  IconHeart,
  IconSearch,
  IconShare,
  IconShield,
  IconStar,
  IconWallet,
  IconWarn,
} from "../components/Icons";
import { formatWhen, kopExactLabel, payMethodLabel } from "../utils/format";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "soft"; reason: "notyet" | "pending" }
  | { kind: "ready"; r: TaxiReceipt };

/** Строка счёта: слева за что (muted), справа сколько (Bold, цвет причины); ниже — подсказка. */
function Line({ label, value, tone = "text", hint }: { label: string; value: string; tone?: "text" | "warn" | "green" | "red"; hint?: string }) {
  return (
    <div className="rcpt__line">
      <div className="rcpt__line-row">
        <span>{label}</span>
        <b className={"rcpt__val rcpt__val--" + tone}>{value}</b>
      </div>
      {hint && <small>{hint}</small>}
    </div>
  );
}

/** TaxiReceiptActionRow: карточка 68 с мятным кругом, заголовок semibold, подпись, шеврон. */
function ActionRow({
  icon,
  title,
  text,
  onClick,
  busy = false,
  enabled = true,
}: {
  icon: JSX.Element;
  title: string;
  text: string;
  onClick: () => void;
  busy?: boolean;
  enabled?: boolean;
}) {
  const { appText } = useLang();
  return (
    <button type="button" className={"rcpt-act" + (enabled ? "" : " is-off")} onClick={onClick} disabled={!enabled || busy}>
      <span className="rcpt-act__icon" aria-hidden>{icon}</span>
      <span className="rcpt-act__text">
        <strong>{title}</strong>
        <small>{busy ? appText("Подожди…", "Көт…") : text}</small>
      </span>
      <span className="rcpt-act__chev" aria-hidden><IconChevron size={20} /></span>
    </button>
  );
}

export default function TaxiReceiptScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { orderId } = useParams();
  const id = Number(orderId);

  const [state, setState] = useState<State>({ kind: "loading" });
  const [thanked, setThanked] = useState(false);
  const [busy, setBusy] = useState<"cash" | "lost" | "thanks" | "dispute" | null>(null);
  const [lostOpened, setLostOpened] = useState(false);
  const [errText, setErrText] = useState("");
  const [successText, setSuccessText] = useState("");
  const [problem, setProblem] = useState<"none" | "choice" | "dispute">("none");
  const [disputeFiled, setDisputeFiled] = useState(false);
  // Чем поблагодарить: тёплое «рәхмәт» всегда, деньги — только если водитель
  // сам их включил. null = сервер ещё не ответил или чаевые выключены.
  const [tip, setTip] = useState<TipInfo | null>(null);
  // Оценка второй стороны прямо из чека (TaxiReceiptCompactRating).
  const [stars, setStars] = useState(0);
  const [pendingStar, setPendingStar] = useState(0);
  const [rateBusy, setRateBusy] = useState(false);
  const [rateErr, setRateErr] = useState("");

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!id) {
        setState({ kind: "error" });
        return;
      }
      setState({ kind: "loading" });
      fetchTaxiReceipt(id, signal)
        .then((r) => {
          setState({ kind: "ready", r });
          setStars(r.my_stars ?? 0);
        })
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

  // Чаевые — отдельным запросом и только пассажиру (водителю сервер отвечает 403):
  // их отсутствие не должно ломать чек.
  const role = state.kind === "ready" ? state.r.role : null;
  useEffect(() => {
    if (!id || role !== "passenger") return;
    const ac = new AbortController();
    fetchOrderTip(id, ac.signal)
      .then((t) => {
        setTip(t);
        if (t.already_thanked) setThanked(true);
      })
      .catch(() => setTip(null));
    return () => ac.abort();
  }, [id, role]);

  const fail = (e: unknown, fallback: string) => setErrText(e instanceof ApiError && e.message ? e.message : fallback);
  const netFail = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.");

  /**
   * «Рәхмәт» — это НЕ оценка. Раньше кнопка ставила пятёрку: она подменяла
   * мнение человека (сказать спасибо можно и после тройки) и портила рейтинг
   * водителя чужими баллами. У сервера для этого своя ручка — тёплый жест
   * без цифр, идемпотентный.
   */
  async function sayThanks() {
    if (busy || thanked) return;
    setBusy("thanks");
    try {
      await thankOrder(id);
      setThanked(true);
      setErrText("");
      setSuccessText(appText("Спасибо передано водителю", "Рәхмәт йөрөтөүсегә тапшырылды"));
    } catch (e) {
      fail(e, netFail);
    } finally {
      setBusy(null);
    }
  }

  async function cashReceived() {
    if (busy) return;
    setBusy("cash");
    try {
      await markCashReceived(id);
      setErrText("");
      load(); // перечитываем — статус оплаты меняет весь блок
    } catch (e) {
      fail(e, netFail);
    } finally {
      setBusy(null);
    }
  }

  async function lostItem(r: TaxiReceipt) {
    if (lostOpened) {
      navigate(`/taxi-chat/${r.order_id}`);
      return;
    }
    if (busy) return;
    setBusy("lost");
    try {
      await reportLostItem(id);
      setLostOpened(true);
      setErrText("");
      navigate(`/taxi-chat/${r.order_id}`);
    } catch (e) {
      fail(e, netFail);
    } finally {
      setBusy(null);
    }
  }

  async function rate(r: TaxiReceipt, value: number) {
    if (rateBusy) return;
    setPendingStar(value);
    setRateBusy(true);
    try {
      await rateInstantOrder(r.order_id, value);
      setStars(value);
      setRateErr("");
    } catch (e) {
      setRateErr(e instanceof ApiError && e.message ? e.message : appText("Не получилось сохранить оценку.", "Баһаны һаҡлап булманы."));
    } finally {
      setPendingStar(0);
      setRateBusy(false);
    }
  }

  function shareText(r: TaxiReceipt): string {
    return [
      appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы"),
      appText(`Заказ № ${r.order_id}`, `Заказ № ${r.order_id}`),
      `${r.from_text || "—"} → ${r.to_text || "—"}`,
      formatWhen(r.done_at, ru),
      `${appText("Сумма", "Сумма")}: ${kopExactLabel(r.amount_kop)} · ${payMethodLabel(r.payment_method, ru)}`,
      ...(r.driver_name ? [`${appText("Водитель", "Йөрөтөүсе")}: ${r.driver_name}`] : []),
    ].join("\n");
  }

  function shareReceipt(r: TaxiReceipt) {
    const text = shareText(r);
    if (navigator.share) {
      navigator.share({ title: appText("Юлдаш · Чек за поездку", "Юлдаш · Сәфәр чегы"), text }).catch(() => {});
    } else {
      navigator.clipboard?.writeText(text).then(
        () => setSuccessText(appText("Чек скопирован", "Чек күсерелде")),
        () => {}
      );
    }
  }

  const r = state.kind === "ready" ? state.r : null;
  const isDriver = r?.role === "driver";
  const distanceText = r && r.distance_km != null && r.distance_km > 0 ? `${r.distance_km.toFixed(1).replace(".", ",")} ${appText("км", "км")}` : "";
  const meta = r ? [r.done_at ? formatWhen(r.done_at, ru) : "", distanceText].filter(Boolean).join("  ·  ") : "";
  const feeLabel = r?.driver_fee_percent != null ? (Number.isInteger(r.driver_fee_percent) ? String(r.driver_fee_percent) : r.driver_fee_percent.toFixed(1)) : "";
  const weatherLabel =
    r?.weather_kind === "ice"
      ? appText("Гололёд на дороге", "Юлда быҙлауыҡ")
      : r?.weather_kind === "blizzard"
        ? appText("Метель по пути", "Юлда буран")
        : r?.weather_kind === "snow"
          ? appText("Сильный снегопад", "Көслө ҡар яуа")
          : r?.weather_kind === "frost"
            ? appText("Сильный мороз", "Ҡаты һыуыҡ")
            : appText("Тяжёлая дорога", "Ауыр юл");
  const shownStars = pendingStar > 0 ? pendingStar : stars;

  return (
    <>
      {/* Шапка: «Детали поездки» по центру, справа «Поделиться» (гаснет, пока чека нет). */}
      <header className="screen-header screen-header--sub rcpt-head">
        <div className="screen-header__row">
          <button type="button" className="subheader__back" onClick={() => navigate(-1)} aria-label={appText("Назад", "Артҡа")}>
            <span style={{ display: "inline-flex", transform: "rotate(180deg)" }}>
              <IconChevron size={22} />
            </span>
          </button>
          <div style={{ flex: 1 }}>
            <h1>{appText("Детали поездки", "Сәфәр ентеклектәре")}</h1>
          </div>
          <button type="button" className="subheader__back" onClick={() => r && shareReceipt(r)} disabled={!r} aria-label={appText("Поделиться чеком", "Чек менән бүлешеү")}>
            <IconShare size={22} />
          </button>
        </div>
      </header>

      <div className="alist">
        {state.kind === "loading" && (
          <>
            <div className="rcpt-skel skeleton" aria-hidden />
            <RideCardSkeleton />
            <RideCardSkeleton />
          </>
        )}
        {state.kind === "error" && <ErrorState onRetry={() => load()} />}

        {state.kind === "soft" && (
          /* 409: поездка ещё не завершена — спокойный текст без тревоги. */
          <div className="rcpt-pending">
            <span className="rcpt-pending__icon" aria-hidden><IconClock size={28} /></span>
            <strong>{state.reason === "pending" ? appText("Чек ещё не готов", "Чек әҙер түгел") : appText("Чек скоро появится", "Чек тиҙҙән күренәсәк")}</strong>
            <span>
              {state.reason === "pending"
                ? appText("Он появится после завершения поездки. Хорошей дороги!", "Ул сәфәр тамамланғас барлыҡҡа килер. Юлың уң булһын!")
                : appText("Мы включим чеки за такси с ближайшим обновлением сервиса.", "Такси чектарын яҡын яңыртыуҙа тоташтырабыҙ.")}
            </span>
          </div>
        )}

        {r && (
          <>
            {/* ─── Документ ─── */}
            <section className="rcpt">
              <span className="rcpt__stamp">
                <IconCheck size={18} /> {appText("Поездка завершена", "Сәфәр тамамланды")}
              </span>
              <b className="rcpt__amount">{kopExactLabel(r.amount_kop)}</b>
              <span className="rcpt__pay">
                {payMethodLabel(r.payment_method, ru)}
                {r.paid ? appText(" · Оплачено", " · Түләнгән") : ""}
              </span>

              <hr className="rcpt__hair" />
              {/* Из чего сложилась сумма. Пассажиру комиссию НЕ показываем — он платит водителю напрямую. */}
              {(r.ride_price ?? 0) > 0 && (
                <div className="rcpt__lines">
                  <Line label={appText("Поездка", "Сәфәр")} value={`${(r.ride_base_price || r.ride_price) ?? 0} ₽`} />
                  {(r.surge_rub ?? 0) > 0 && <Line label={appText("Наценка за спрос", "Ихтыяж өҫтәмәһе")} value={`+${r.surge_rub} ₽`} tone="warn" />}
                  {(r.pickup_fee_kop ?? 0) > 0 && (
                    <Line
                      label={appText(`Дорога водителя к тебе, ~${Math.round(r.pickup_km ?? 0)} км`, `Йөрөтөүсенең һиңә тиклем юлы, ~${Math.round(r.pickup_km ?? 0)} км`)}
                      value={"+" + kopExactLabel(r.pickup_fee_kop ?? 0)}
                      tone="green"
                      hint={r.pickup_enroute ? appText("Ему было по пути — вдвое дешевле", "Уға юл ыңғайы ине — ике тапҡыр арзаныраҡ") : appText("Уходит водителю целиком", "Тулыһынса йөрөтөүсегә бара")}
                    />
                  )}
                  {(r.weather_fee_kop ?? 0) > 0 && (
                    <Line label={weatherLabel} value={"+" + kopExactLabel(r.weather_fee_kop ?? 0)} tone="green" hint={appText("Уходит водителю целиком", "Тулыһынса йөрөтөүсегә бара")} />
                  )}
                  {(r.options_fee_kop ?? 0) > 0 && (
                    <Line label={appText("Кресло и опции", "Ултырғыс һәм өҫтәмәләр")} value={"+" + kopExactLabel(r.options_fee_kop ?? 0)} tone="green" hint={appText("Уходит водителю целиком", "Тулыһынса йөрөтөүсегә бара")} />
                  )}
                  {r.promo_discount_kop > 0 && (
                    <Line
                      label={appText("Скидка Юлдаша", "Юлдаш ташламаһы")}
                      value={"−" + kopExactLabel(r.promo_discount_kop)}
                      tone="green"
                      hint={appText("Водитель получил полную сумму — скидку оплатил Юлдаш", "Йөрөтөүсе тулы сумманы алды — ташламаны Юлдаш түләне")}
                    />
                  )}
                  {isDriver && (r.driver_gross_kop ?? 0) > 0 && (
                    <>
                      <hr className="rcpt__sep" />
                      <Line label={appText("Всего от пассажира", "Пассажирҙан барлығы")} value={kopExactLabel(r.driver_gross_kop ?? 0)} />
                      <Line
                        label={appText(`Комиссия Юлдаша ${feeLabel}%`, `Юлдаш комиссияһы ${feeLabel}%`)}
                        value={"−" + kopExactLabel(r.driver_fee_kop ?? 0)}
                        tone="red"
                        hint={
                          (r.commission_free_kop ?? 0) > 0
                            ? appText(
                                `С ${kopExactLabel(r.commission_free_kop ?? 0)} комиссию не берём — это твой бензин и кресло`,
                                `${kopExactLabel(r.commission_free_kop ?? 0)} суммаһынан комиссия алмайбыҙ — был һинең бензин һәм ултырғыс`
                              )
                            : undefined
                        }
                      />
                      <Line label={appText("Чистыми тебе", "Һиңә таҙа килем")} value={kopExactLabel(r.driver_net_kop ?? 0)} tone="green" />
                    </>
                  )}
                </div>
              )}
              {r.waiting_fee_kop > 0 && <Line label={appText("Ожидание", "Көтөү")} value={kopExactLabel(r.waiting_fee_kop)} tone="warn" />}
              <div className="rcpt__total">
                <span>{appText("Итого", "Бөтәһе")}</span>
                <b>{kopExactLabel(r.amount_kop)}</b>
              </div>

              <hr className="rcpt__hair" />
              <RouteTimeline from={r.from_text || appText("Точка отправления", "Китеү нөктәһе")} to={r.to_text || appText("Точка назначения", "Барыу нөктәһе")} compact />
              <hr className="rcpt__hair" />

              {meta && <span className="rcpt__meta">{meta}</span>}
              {r.driver_name && (
                <div className="rcpt__driver">
                  <strong>{r.driver_name}</strong>
                  {r.driver_verified && <span className="rcpt__verified" aria-label={appText("Водитель проверен", "Йөрөтөүсе тикшерелгән")}><IconCheck size={17} /></span>}
                  <span className="acard__spacer" />
                  <small>{appText(`Заказ № ${r.order_id}`, `Заказ № ${r.order_id}`)}</small>
                </div>
              )}
              <small className="rcpt__note">{appText("Деньги идут напрямую водителю — Юлдаш их не держит.", "Аҡса туранан-тура йөрөтөүсегә бара — Юлдаш уны тотмай.")}</small>
            </section>

            {/* ─── Оценка второй стороны ─── */}
            <div className="rcpt-rate">
              <span>{isDriver ? appText("Оценить пассажира", "Пассажирҙы баһалау") : appText("Оценить водителя", "Йөрөтөүсене баһалау")}</span>
              <div className="rcpt-rate__stars" role="radiogroup">
                {[1, 2, 3, 4, 5].map((v) => (
                  <button
                    key={v}
                    type="button"
                    role="radio"
                    aria-checked={shownStars === v}
                    aria-label={String(v)}
                    className={"rcpt-rate__star" + (v <= shownStars ? " is-on" : "")}
                    disabled={rateBusy}
                    onClick={() => rate(r, v)}
                  >
                    <IconStar size={34} />
                  </button>
                ))}
              </div>
              {stars > 0 && !rateErr && <small className="rcpt-rate__ok">{appText("Твоя оценка сохранена", "Һинең баһаң һаҡланды")}</small>}
              {rateErr && <small className="rcpt-rate__err">{rateErr}</small>}
            </div>

            {/* ─── Ещё можно ─── */}
            <strong className="rcpt-more">{appText("Ещё можно", "Тағы мөмкин")}</strong>

            {!isDriver && (
              <ActionRow
                icon={thanked ? <IconCheck size={20} /> : <IconHeart size={20} />}
                title={thanked ? appText("«Рәхмәт» сказан", "Рәхмәт әйтелде") : appText("Сказать «рәхмәт»", "Рәхмәт әйтеү")}
                text={thanked ? appText("Водитель получил твоё спасибо", "Йөрөтөүсе һинең рәхмәтеңде алды") : appText("Тёплое спасибо водителю — без денег", "Йөрөтөүсегә йылы рәхмәт — аҡсаһыҙ")}
                busy={busy === "thanks"}
                enabled={!thanked}
                onClick={sayThanks}
              />
            )}
            {/* Есть только в вебе: деньгами — если водитель сам включил чаевые и оставил номер. */}
            {!isDriver && tip?.money && (
              <div className="pcard">
                <strong className="pcard__title">{appText("Можно и деньгами — по желанию", "Аҡса менән дә була — теләк буйынса")}</strong>
                <SbpPay phone={tip.money.sbp} name={tip.money.name} />
              </div>
            )}

            {isDriver && !r.paid && (
              /* Без этой кнопки заказ навсегда «не оплачен», если пассажир вышел и закрыл приложение. */
              <div className="pcard">
                <div className="rcpt-cash__head">
                  <span className="rcpt-cash__icon" aria-hidden><IconWallet size={20} /></span>
                  <span className="acard__stack acard__grow">
                    <strong className="pcab__name">{appText("Оплата не отмечена", "Түләү билдәләнмәгән")}</strong>
                    <span className="acard__sub">
                      {appText(
                        "Если деньги на руках — отметь. Так поездка закроется честно, а в отчёте не будет дыры.",
                        "Аҡса ҡулда булһа — билдәлә. Шунда сәфәр намыҫлы ябыла, отчётта тишек ҡалмай."
                      )}
                    </span>
                  </span>
                </div>
                <button type="button" className="btn-primary pform__submit" onClick={cashReceived} disabled={busy === "cash"}>
                  <IconWallet size={20} /> {busy === "cash" ? appText("Отмечаем…", "Билдәләйбеҙ…") : appText("Наличные получил", "Аҡсаны алдым")}
                </button>
              </div>
            )}

            <ActionRow icon={<IconShare size={20} />} title={appText("Поделиться чеком", "Чек менән бүлешеү")} text={appText("Отправить маршрут и сумму поездки", "Сәфәр юлын һәм суммаһын ебәреү")} onClick={() => shareReceipt(r)} />

            {/* Забытая вещь — обеим сторонам: чат снова открыт на 48 часов. */}
            <ActionRow
              icon={lostOpened ? <IconChat size={20} /> : <IconSearch size={20} />}
              title={lostOpened ? appText("Открыть чат поездки", "Сәфәр чатын асыу") : appText("Забыл вещь?", "Әйбер оноттоңмо?")}
              text={lostOpened ? appText("Чат открыт на 48 часов", "Чат 48 сәғәткә асылды") : appText("Связаться по этой поездке", "Был сәфәр буйынса бәйләнешеү")}
              busy={busy === "lost"}
              onClick={() => void lostItem(r)}
            />

            <ActionRow
              icon={<IconWarn size={20} />}
              title={appText("Проблема с поездкой", "Сәфәр менән проблема")}
              text={disputeFiled ? appText("Разбор уже открыт", "Ҡарау асылған") : appText("Сообщить или открыть разбор", "Хәбәр итеү йәки ҡарау асыу")}
              onClick={() => setProblem("choice")}
            />

            {problem === "choice" && (
              /* «Что случилось?» — две дороги: анонимная жалоба или двусторонний разбор. */
              <div className="settings-confirm settings-confirm--card settings-confirm--plain">
                <strong>{appText("Что случилось?", "Нимә булды?")}</strong>
                <ActionRow
                  icon={<IconFlag size={20} />}
                  title={appText("Сообщить о нарушении", "Боҙоу тураһында хәбәр итеү")}
                  text={appText("Анонимно, проверит человек", "Аноним, кеше тикшерәсәк")}
                  onClick={() => navigate(`/report?order=${r.order_id}`)}
                />
                {(r.counterparty_id ?? 0) > 0 && (
                  <ActionRow
                    icon={<IconShield size={20} />}
                    title={appText("Открыть разбор", "Ҡарауҙы асыу")}
                    text={appText("Выслушаем обе стороны", "Ике яҡты ла тыңлаясаҡбыҙ")}
                    onClick={() => setProblem("dispute")}
                  />
                )}
                <div className="settings-confirm__row">
                  <button type="button" className="btn-ghost" onClick={() => setProblem("none")}>
                    {appText("Закрыть", "Ябыу")}
                  </button>
                </div>
              </div>
            )}

            {problem === "dispute" && (r.counterparty_id ?? 0) > 0 && (
              <FileIncidentCard
                respondentId={r.counterparty_id ?? 0}
                respondentName={r.counterparty_name ?? ""}
                orderId={r.order_id}
                onCancel={() => setProblem("none")}
                onFiled={() => {
                  setDisputeFiled(true);
                  setProblem("none");
                  setErrText("");
                  setSuccessText(appText("Разбор открыт. Мы сообщим о решении.", "Ҡарау асылды. Ҡарар тураһында хәбәр итербеҙ."));
                }}
                onError={(text) => setErrText(text)}
              />
            )}

            {/* Пассажир может завершить оплату прямо из чека, если закрыл финальный экран. */}
            {!isDriver && !r.paid && <PayTripCard kind="order" id={id} amountLabel={kopExactLabel(r.amount_kop)} onPaid={() => load()} />}

            {successText && <p className="rcpt-msg rcpt-msg--ok">{successText}</p>}
            {errText && <p className="rcpt-msg rcpt-msg--err">{errText}</p>}
          </>
        )}
      </div>
    </>
  );
}
