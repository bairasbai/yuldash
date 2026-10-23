// ================================================================
//  Квитанция завершённой поездки (GET /trips/{booking_id}/receipt).
//  Эндпоинт появится на проде после мержа release-2026-07 → мягкая
//  деградация: 404 (нет эндпоинта) / 409 (поездка не завершена) →
//  аккуратное состояние, без краша.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { fetchReceipt, reportBookingLostItem, type TripReceipt } from "../api/bookings";
import { thankBooking, fetchBookingTip, type TipInfo } from "../api/family";
import SbpPay from "../components/SbpPay";
import PayTripCard from "../components/PayTripCard";
import ReceiptRateDriver from "../components/ReceiptRateDriver";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconReceipt, IconHeart, IconCheck, IconShare, IconSearch } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "soft"; reason: "notyet" | "pending" } // мягкая деградация
  | { kind: "ready"; r: TripReceipt };

export default function TripReceiptScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const bookingId = Number(id);
  const generation = getSessionGeneration();
  const ownerKey = `${generation}:${bookingId}`;
  const ownerRef = useRef(ownerKey);
  ownerRef.current = ownerKey;
  const mounted = useRef(true);
  const receiptRequest = useRef<AbortController | null>(null);
  const tipRequest = useRef<AbortController | null>(null);
  const readyReceipt = useRef<TripReceipt | null>(null);
  const thanksRequest = useRef<object | null>(null);
  const lostRequest = useRef<object | null>(null);
  const shareTimer = useRef<number | null>(null);
  const [ownedState, setOwnedState] = useState<{ owner: string; value: State }>({ owner: ownerKey, value: { kind: "loading" } });
  const state: State = ownedState.owner === ownerKey ? ownedState.value : { kind: "loading" };
  if (ownedState.owner !== ownerKey) readyReceipt.current = null;
  const current = () => mounted.current && ownerRef.current === ownerKey && generation === getSessionGeneration();
  const currentReceipt = (r: TripReceipt) => current() && readyReceipt.current === r;
  const setState = (value: State) => { if (current()) setOwnedState({ owner: ownerKey, value }); };

  const load = useCallback(
    () => {
      if (!current()) return;
      receiptRequest.current?.abort(); tipRequest.current?.abort();
      const ac = new AbortController(); receiptRequest.current = ac;
      const signal = ac.signal;
      readyReceipt.current = null; thanksRequest.current = null; lostRequest.current = null;
      setTip(null); setThanked(false); setThanksBusy(false); setShared(false);
      setLostOpened(false); setLostBusy(false); setLostError(""); setRatedStars(0);
      if (shareTimer.current !== null) window.clearTimeout(shareTimer.current);
      if (!Number.isSafeInteger(bookingId) || bookingId <= 0) {
        setState({ kind: "error" });
        return;
      }
      setState({ kind: "loading" });
      fetchReceipt(bookingId, signal)
        .then((r) => {
          if (!current() || signal.aborted || receiptRequest.current !== ac) return;
          if (r.booking_id !== bookingId) { setState({ kind: "error" }); return; }
          readyReceipt.current = r;
          setState({ kind: "ready", r });
        })
        .catch((e) => {
          if (!current() || signal.aborted || receiptRequest.current !== ac || e?.name === "AbortError") return;
          if (e instanceof ApiError) {
            // 404 — эндпоинта ещё нет на проде (до деплоя release-2026-07).
            if (e.status === 404) return setState({ kind: "soft", reason: "notyet" });
            // 409 — поездка ещё не завершена.
            if (e.status === 409) return setState({ kind: "soft", reason: "pending" });
          }
          setState({ kind: "error" });
        });
    },
    [bookingId, generation]
  );

  useEffect(() => {
    mounted.current = true;
    load();
    return () => {
      mounted.current = false; readyReceipt.current = null;
      receiptRequest.current?.abort(); tipRequest.current?.abort(); thanksRequest.current = null; lostRequest.current = null;
      if (shareTimer.current !== null) window.clearTimeout(shareTimer.current);
    };
  }, [load]);

  /**
   * Текст квитанции — маршрут, дата, сумма. Ни телефона, ни точной точки:
   * человек часто пересылает её в рабочий чат, а там лишние глаза.
   */
  const [shared, setShared] = useState(false);
  // Чем поблагодарить водителя попутки. null = чаевые выключены или ручки нет.
  const [tip, setTip] = useState<TipInfo | null>(null);
  const [thanked, setThanked] = useState(false);
  const [thanksBusy, setThanksBusy] = useState(false);
  const [lostOpened, setLostOpened] = useState(false);
  const [lostBusy, setLostBusy] = useState(false);
  const [lostError, setLostError] = useState("");
  // Оценка позже — прямо в квитанции (приёмка 18.10, п.7); ушла — строка «Твоя оценка» сразу на месте.
  const [ratedStars, setRatedStars] = useState(0);

  const passengerReceipt = state.kind === "ready" && state.r.role === "passenger";
  const driverReceipt = state.kind === "ready" && state.r.role === "driver";
  const r = state.kind === "ready" ? state.r : null;
  const pickup = r?.pickup_stop === "" ? r.from_city : r?.pickup_stop;
  const dropoff = r?.dropoff_stop === "" ? r.to_city : r?.dropoff_stop;
  const hasSegment = typeof pickup === "string" && !!pickup.trim() && typeof dropoff === "string" && !!dropoff.trim();
  const ownStars = ratedStars > 0 ? ratedStars
    : Number.isInteger(r?.my_stars) && r!.my_stars! >= 1 && r!.my_stars! <= 5 ? r!.my_stars : null;

  useEffect(() => {
    if (!r || !currentReceipt(r) || !passengerReceipt) {
      setTip(null);
      setThanked(false);
      return;
    }
    const ac = new AbortController(); tipRequest.current = ac;
    fetchBookingTip(bookingId, ac.signal)
      .then((t) => {
        if (!currentReceipt(r) || ac.signal.aborted || tipRequest.current !== ac) return;
        setTip(t);
        setThanked(t.already_thanked);
      })
      .catch(() => { if (currentReceipt(r) && !ac.signal.aborted && tipRequest.current === ac) setTip(null); });
    return () => ac.abort();
  }, [bookingId, generation, r, passengerReceipt]);

  async function sayThanks() {
    if (!r || !currentReceipt(r) || thanksBusy || thanksRequest.current || !passengerReceipt) return;
    const operation = {}; thanksRequest.current = operation;
    setThanksBusy(true);
    try {
      await thankBooking(bookingId);
      if (!currentReceipt(r) || thanksRequest.current !== operation) return;
      setThanked(true);
    } catch {
      /* не отправилось — кнопка остаётся, человек повторит */
    } finally {
      if (currentReceipt(r) && thanksRequest.current === operation) { thanksRequest.current = null; setThanksBusy(false); }
    }
  }

  async function reopenLostItemChat() {
    if (!r || !currentReceipt(r) || lostRequest.current || lostOpened) return;
    const operation = {}; lostRequest.current = operation;
    setLostBusy(true); setLostError("");
    let confirmed = false;
    try {
      const result = await reportBookingLostItem(bookingId);
      if (!currentReceipt(r) || lostRequest.current !== operation) return;
      if (result.ok !== true || typeof result.chat_open_until !== "string" || !Number.isFinite(Date.parse(result.chat_open_until))) {
        throw new Error("Invalid lost-item acknowledgement");
      }
      confirmed = true;
      setLostOpened(true);
    } catch (e) {
      if (!currentReceipt(r) || lostRequest.current !== operation) return;
      setLostError(
        e instanceof ApiError && e.status >= 400 && e.status < 500 && e.message
          ? e.message
          : appText("Не удалось подтвердить открытие чата. Проверь связь и повтори.",
              "Чаттың асылыуын раҫлап булманы. Бәйләнеште тикшереп ҡабатла.")
      );
    } finally {
      if (currentReceipt(r) && lostRequest.current === operation) {
        // Keep the successful operation fenced against an already-retained button callback.
        if (!confirmed) lostRequest.current = null;
        setLostBusy(false);
      }
    }
  }

  async function shareReceipt() {
    if (state.kind !== "ready" || !currentReceipt(state.r)) return;
    const r = state.r;
    const text = appText(
      `Юлдаш · Квитанция поездки
${r.from_city} → ${r.to_city}
${formatWhen(r.depart_at, true)}
${priceLabel(r.amount, true)}`,
      `Юлдаш · Сәфәр квитанцияһы
${r.from_city} → ${r.to_city}
${formatWhen(r.depart_at, false)}
${priceLabel(r.amount, false)}`
    );
    if (navigator.share) {
      try {
        await navigator.share({ title: "Юлдаш", text });
        return;
      } catch {
        /* отменил — не ошибка */
      }
    }
    if (!currentReceipt(r)) return;
    try {
      await navigator.clipboard.writeText(text);
      if (!currentReceipt(r)) return;
      setShared(true);
      if (shareTimer.current !== null) window.clearTimeout(shareTimer.current);
      shareTimer.current = window.setTimeout(() => { if (currentReceipt(r)) setShared(false); }, 1600);
    } catch {
      /* буфер недоступен — цифры на экране, их видно */
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Квитанция", "Квитанция")}
        onBack={() => navigate(-1)}
      />

      {state.kind === "loading" && <LoadingList count={2} />}
      {state.kind === "error" && <ErrorState onRetry={() => load()} />}

      {state.kind === "soft" && (
        <div className="state">
          <div className="state__icon"><IconReceipt size={34} /></div>
          <h2>
            {state.reason === "pending"
              ? appText("Поездка ещё не завершена", "Сәфәр әле тамамланмаған")
              : appText("Квитанция скоро появится", "Квитанция тиҙҙән күренәсәк")}
          </h2>
          <p>
            {state.reason === "pending"
              ? appText(
                  "Квитанция будет доступна сразу после завершения поездки.",
                  "Квитанция сәфәр тамамланғас уҡ асыла."
                )
              : appText(
                  "Мы включим квитанции с ближайшим обновлением сервиса.",
                  "Квитанцияларҙы яҡын яңыртыуҙа тоташтырабыҙ."
                )}
          </p>
        </div>
      )}

      {state.kind === "ready" && (
        <div className="receipt">
          <div className="receipt__brand"><IconHeart size={16} /> {appText("Юлдаш", "Юлдаш")}</div>
          <p className="list-row__sub">{appText("Рейс водителя", "Йөрөтөүсенең сәфәре")}</p>
          <div className="receipt__route">
            <span>{state.r.from_city}</span>
            <span className="ride-card__arrow">
              <IconArrow size={18} />
            </span>
            <span>{state.r.to_city}</span>
          </div>
          <div className="receipt__date">{formatWhen(state.r.depart_at, ru)}</div>
          {hasSegment && <section style={{ marginTop: "var(--space-md)", overflowWrap: "anywhere" }}>
            <p className="list-row__title">{appText("Участок пассажира", "Юлсының юл өлөшө")}</p>
            <div className="receipt__route" style={{ fontSize: "var(--font-heading)" }}>
              <span>{pickup}</span><span className="ride-card__arrow"><IconArrow size={18} /></span><span>{dropoff}</span>
            </div>
          </section>}

          <div className="receipt__rows">
            <div className="info-row">
              <span className="info-row__k">{appText("Водитель", "Йөрөтөүсе")}</span>
              <span className="info-row__v">
                {state.r.driver_name}
                {state.r.driver_verified && (
                  <span className="badge badge--mint" style={{ marginLeft: 6 }}><IconCheck size={12} /></span>
                )}
              </span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Мест", "Урын")}</span>
              <span className="info-row__v">{state.r.seats}</span>
            </div>
            {ownStars !== null && <div className="info-row">
              <span className="info-row__k">{appText("Твоя оценка", "Һинең баһаң")}</span>
              <span className="info-row__v">{ownStars} / 5</span>
            </div>}
            <div className="info-row">
              <span className="info-row__k">{appText("Способ оплаты", "Түләү ысулы")}</span>
              <span className="info-row__v">{payMethodLabel(state.r.pay_method, ru)}</span>
            </div>
            <div className="info-row">
              <span className="info-row__k">{appText("Статус оплаты", "Түләү хәле")}</span>
              <span className="info-row__v">
                {state.r.paid
                  ? appText("Оплачено", "Түләнгән")
                  : appText("По договорённости", "Килешеү буйынса")}
              </span>
            </div>
          </div>

          <div className="receipt__total">
            <span>{appText("Итого", "Барлығы")}</span>
            <b>{priceLabel(state.r.amount, ru)}</b>
          </div>

          {r && passengerReceipt && r.can_rate && ownStars === null && (
            <ReceiptRateDriver bookingId={r.booking_id} canAct={() => currentReceipt(r)} onRated={setRatedStars} />
          )}

          {/* Квитанцию просят на работе и в бухгалтерии — пусть уходит одной кнопкой,
              а не переписыванием цифр с экрана. Телефонов в ней нет. */}
          <button
            type="button"
            className="btn-soft"
            style={{ marginTop: 12 }}
            onClick={() => void shareReceipt()}
          >
            <IconShare size={18} />{" "}
            {shared
              ? appText("Скопировано", "Күсерелде")
              : appText("Поделиться квитанцией", "Квитанция менән бүлешеү")}
          </button>

          {/* Неоплаченная поездка — способ рассчитаться. Оплачено: блока нет. */}
          {passengerReceipt && !state.r.paid && (
            <PayTripCard
              kind="booking"
              id={bookingId}
              amountLabel={priceLabel(state.r.amount, ru)}
              onPaid={() => load()}
              canAct={() => currentReceipt(state.r)}
            />
          )}

          {/* Обе стороны могут искать оставленную вещь. Открытие подтверждает только сервер.
              Слова — каждой стороне свои: водитель не «забыл вещь в своей машине», а нашёл чужую. */}
          <div className="act-card" style={{ marginTop: "var(--space-md)" }} aria-busy={lostBusy}>
            <div className="act-card__title">
              <IconSearch size={18} />{" "}{driverReceipt
                ? appText("Пассажир забыл вещь?", "Юлсы әйбер оноттомо?") // DRAFT
                : appText("Забыл вещь?", "Әйбер оноттоңмо?")}
            </div>
            <p className="act-card__text" role={lostOpened ? "status" : undefined}>
              {lostOpened
                ? driverReceipt
                  ? appText("Чат открыт — напиши пассажиру, что осталось в машине.", "Чат асыҡ — юлсыға машинала нимә ҡалғанын яҙ.") // DRAFT
                  : appText("Чат открыт — напиши, что искать.", "Чат асыҡ — нимә эҙләргә, яҙ.")
                : appText("Откроем чат этой поездки на 48 часов, чтобы вы связались.",
                    "Бәйләнешер өсөн был сәфәр чатын 48 сәғәткә асабыҙ.")}
            </p>
            {lostError && <div className="notice" role="alert">{lostError}</div>}
            {lostOpened ? (
              <button type="button" className="btn-soft" style={{ width: "100%" }}
                onClick={() => { if (currentReceipt(state.r)) navigate(`/trip/${bookingId}/chat`); }}>
                {appText("Открыть чат поездки", "Сәфәр чатын асыу")}
              </button>
            ) : (
              <button type="button" className="btn-soft" style={{ width: "100%" }}
                disabled={lostBusy} onClick={() => void reopenLostItemChat()}>
                {lostBusy
                  ? appText("Открываем…", "Асабыҙ…")
                  : driverReceipt
                    ? appText("Пассажир оставил вещь в машине", "Юлсы машинала әйбер ҡалдырҙы") // DRAFT
                    : appText("Я забыл вещь в машине", "Машинала әйбер ҡалдырҙым")}
              </button>
            )}
          </div>

          {/* «Рәхмәт» и придумали для попуток: сосед подвёз бесплатно и заслуживает
              спасибо не меньше таксиста. Кнопка была только в чеке такси, а обе
              серверные ручки годами никто не звал. */}
          {passengerReceipt && <div className="act-card" style={{ marginTop: 14 }}>
            <div className="act-card__title">
              <IconHeart size={18} />{" "}
              {thanked
                ? appText("Рәхмәт сказан 💚", "Рәхмәт әйтелде 💚")
                : appText("Сказать рәхмәт", "Рәхмәт әйтеү")}
            </div>
            <p className="act-card__text">
              {appText(
                "Тёплое спасибо водителю — без денег и без оценок.",
                "Йөрөтөүсегә йылы рәхмәт — аҡсаһыҙ һәм баһаһыҙ."
              )}
            </p>
            {!thanked && (
              <button
                type="button"
                className="btn-primary"
                style={{ width: "100%" }}
                onClick={() => void sayThanks()}
                disabled={thanksBusy}
              >
                {thanksBusy
                  ? appText("Отправляем…", "Ебәрәбеҙ…")
                  : appText("Сказать рәхмәт", "Рәхмәт әйтеү")}
              </button>
            )}

            {/* Деньгами — только если водитель сам включил и оставил номер СБП */}
            {tip?.money && (
              <div className="tip-money">
                <div className="tip-money__label">
                  {appText("Можно и деньгами — по желанию", "Аҡса менән дә була — теләк буйынса")}
                </div>
                <SbpPay phone={tip.money.sbp} name={tip.money.name} canAct={() => r !== null && currentReceipt(r)} />
              </div>
            )}
          </div>}

          <p className="receipt__foot">
            {appText(
              "Это запись о поездке, как вы договорились. Оплата — напрямую между вами.",
              "Был — килешеү буйынса сәфәр яҙмаһы. Түләү — тура үҙ-ара."
            )}
          </p>
        </div>
      )}
    </>
  );
}
