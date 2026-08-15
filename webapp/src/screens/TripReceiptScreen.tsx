// ================================================================
//  Квитанция завершённой поездки (GET /trips/{booking_id}/receipt).
//  Эндпоинт появится на проде после мержа release-2026-07 → мягкая
//  деградация: 404 (нет эндпоинта) / 409 (поездка не завершена) →
//  аккуратное состояние, без краша.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchReceipt, type TripReceipt } from "../api/bookings";
import { thankBooking, fetchBookingTip, type TipInfo } from "../api/family";
import SbpPay from "../components/SbpPay";
import PayTripCard from "../components/PayTripCard";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconReceipt, IconHeart, IconCheck, IconShare } from "../components/Icons";
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

  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!bookingId) {
        setState({ kind: "error" });
        return;
      }
      setState({ kind: "loading" });
      fetchReceipt(bookingId, signal)
        .then((r) => setState({ kind: "ready", r }))
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          if (e instanceof ApiError) {
            // 404 — эндпоинта ещё нет на проде (до деплоя release-2026-07).
            if (e.status === 404) return setState({ kind: "soft", reason: "notyet" });
            // 409 — поездка ещё не завершена.
            if (e.status === 409) return setState({ kind: "soft", reason: "pending" });
          }
          setState({ kind: "error" });
        });
    },
    [bookingId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
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

  useEffect(() => {
    if (!bookingId) return;
    const ac = new AbortController();
    fetchBookingTip(bookingId, ac.signal)
      .then((t) => {
        setTip(t);
        if (t.already_thanked) setThanked(true);
      })
      .catch(() => setTip(null));
    return () => ac.abort();
  }, [bookingId]);

  async function sayThanks() {
    if (thanksBusy) return;
    setThanksBusy(true);
    try {
      await thankBooking(bookingId);
      setThanked(true);
    } catch {
      /* не отправилось — кнопка остаётся, человек повторит */
    } finally {
      setThanksBusy(false);
    }
  }

  async function shareReceipt() {
    if (state.kind !== "ready") return;
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
    try {
      await navigator.clipboard.writeText(text);
      setShared(true);
      window.setTimeout(() => setShared(false), 1600);
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
          <div className="receipt__route">
            <span>{state.r.from_city}</span>
            <span className="ride-card__arrow">
              <IconArrow size={18} />
            </span>
            <span>{state.r.to_city}</span>
          </div>
          <div className="receipt__date">{formatWhen(state.r.depart_at, ru)}</div>

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
          {!state.r.paid && (
            <PayTripCard
              kind="booking"
              id={bookingId}
              amountLabel={priceLabel(state.r.amount, ru)}
              onPaid={() => load()}
            />
          )}

          {/* «Рәхмәт» и придумали для попуток: сосед подвёз бесплатно и заслуживает
              спасибо не меньше таксиста. Кнопка была только в чеке такси, а обе
              серверные ручки годами никто не звал. */}
          <div className="act-card" style={{ marginTop: 14 }}>
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
                <SbpPay phone={tip.money.sbp} name={tip.money.name} />
              </div>
            )}
          </div>

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
