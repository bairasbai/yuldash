// ================================================================
//  Отклики на мою заявку (GET /requests/{id}/responses).
//  Пассажир видит предложения водителей и принимает одно
//  (POST /responses/{id}/accept → booking_id) → активная поездка.
//  Плюс мэтчинг: «Подходящие поездки» (GET /match/rides?request_id=)
//  — готовые поездки по маршруту заявки, тап → бронь (RideSheet).
//  Плюс «Редактировать» → /requests/{id}/edit (форма с префиллом).
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import {
  fetchRequestResponses,
  fetchMatchRides,
  acceptResponse,
  counterOffer,
  declineResponse,
  parseBargainHistory,
  type ResponseItem,
} from "../api/requests";
import type { Ride } from "../api/rides";
import RideCard from "../components/RideCard";
import RideSheet from "../components/RideSheet";
import BargainTrail from "../components/BargainTrail";
import ResponseRouteNote from "../components/ResponseRouteNote";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconStar, IconCheck, IconClock, IconPencil, IconBolt } from "../components/Icons";
import { priceLabel } from "../utils/format";
import { track } from "../analytics";
import PartialFeedNotice from "../components/PartialFeedNotice";

export default function RequestResponsesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const requestId = Number(id);
  const generation = getSessionGeneration();
  const ownerKey = `${generation}:${requestId}`;
  const owner = useRef(ownerKey); owner.current = ownerKey;
  const mounted = useRef(true);
  const loading = useRef<AbortController | null>(null);
  const acting = useRef<AbortController | null>(null);
  const actionBusy = useRef(false);
  const current = () => mounted.current && owner.current === ownerKey && generation === getSessionGeneration();

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [items, setItems] = useState<ResponseItem[]>([]);
  const [accepting, setAccepting] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Мэтчинг: null = блок скрыт (эндпоинта ещё нет / нет доступа), [] = «пока нет совпадений».
  const [matches, setMatches] = useState<Ride[] | null>(null);
  const [matchesPartial, setMatchesPartial] = useState(false);
  const [openedRide, setOpenedRide] = useState<Ride | null>(null);
  // Торг: у какого отклика открыт ввод своей цены и что набрано.
  const [counterFor, setCounterFor] = useState<number | null>(null);
  const [counterPrice, setCounterPrice] = useState("");
  const [bargaining, setBargaining] = useState(false);

  useEffect(() => {
    mounted.current = true;
    setItems([]); setMatches(null); setMatchesPartial(false); setOpenedRide(null); setCounterFor(null); setCounterPrice(""); setError(null); setAccepting(null); setBargaining(false);
    return () => { mounted.current = false; loading.current?.abort(); acting.current?.abort(); actionBusy.current = false; };
  }, [ownerKey]);

  const load = useCallback(
    () => {
      loading.current?.abort();
      const ac = new AbortController(); loading.current = ac;
      const signal = ac.signal;
      if (!requestId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchRequestResponses(requestId, signal)
        .then((list) => {
          if (!current() || signal.aborted) return;
          setItems(list);
          setStatus("ready");
        })
        .catch((e) => {
          if (!current() || signal.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [requestId, generation]
  );

  useEffect(() => {
    load();
    return () => loading.current?.abort();
  }, [load]);

  // Мэтчинг — параллельно откликам. Любая ошибка (404 до деплоя, 403) → блок скрыт.
  useEffect(() => {
    if (!requestId) return;
    const ac = new AbortController();
    fetchMatchRides(requestId, ac.signal)
      .then((rides) => {
        if (!current() || ac.signal.aborted) return;
        setMatches(rides.items);
        setMatchesPartial(rides.partial);
        if (rides.items.length > 0) track("match_shown", { count: rides.items.length });
      })
      .catch(() => { if (current() && !ac.signal.aborted) { setMatches(null); setMatchesPartial(false); } });
    return () => ac.abort();
  }, [requestId, generation]);

  async function accept(responseId: number) {
    if (!current() || actionBusy.current) return;
    actionBusy.current = true;
    const ac = new AbortController(); acting.current = ac;
    setAccepting(responseId);
    setError(null);
    try {
      const res = await acceptResponse(responseId, ac.signal);
      if (!current() || ac.signal.aborted) return;
      navigate(`/trip/${res.booking_id}`);
    } catch (e) {
      if (!current()) return;
      // 409 бывает по разным причинам (водитель отозвал, торг закрыт, ход другой стороны, пассажир на паузе…) —
      // человеку нужно слово сервера, а не наша догадка про «рейс изменился». Своя фраза — только если слов нет.
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : e instanceof ApiError && e.status === 409
          ? appText("Рейс изменился или больше не подходит. Обнови отклики и выбери снова.", "Сәфәр үҙгәргән йәки бүтән тап килмәй. Яуаптарҙы яңыртып ҡабат һайла.")
          : appText("Не получилось принять. Попробуй снова.", "Ҡабул итеп булманы. Ҡабат ҡара.") // DRAFT
      );
      setAccepting(null);
      if (e instanceof ApiError && e.status === 409) load();
    } finally { if (acting.current === ac) actionBusy.current = false; }
  }

  /** Встречная цена пассажира: «а за 400 поедешь?». Ходят по очереди — сервер решает, чей ход. */
  async function sendCounter(r: ResponseItem) {
    const value = Number(counterPrice);
    if (!Number.isFinite(value) || value <= 0 || !current() || actionBusy.current) return;
    actionBusy.current = true;
    const ac = new AbortController(); acting.current = ac;
    setBargaining(true);
    setError(null);
    try {
      const updated = await counterOffer(r.id, Math.round(value), "", ac.signal);
      if (!current() || ac.signal.aborted) return;
      setItems((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
      setCounterFor(null);
      setCounterPrice("");
    } catch (e) {
      if (!current()) return;
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось предложить цену.", "Хаҡ тәҡдим итеп булманы.")
      );
      load();
    } finally {
      if (current()) setBargaining(false);
      if (acting.current === ac) actionBusy.current = false;
    }
  }

  /** Отказ от отклика — торг закрывается, водитель получит уведомление. */
  async function refuse(r: ResponseItem) {
    if (!current() || actionBusy.current) return;
    actionBusy.current = true;
    const ac = new AbortController(); acting.current = ac;
    const prev = items;
    setItems(items.filter((x) => x.id !== r.id)); // оптимистично
    try {
      await declineResponse(r.id, ac.signal);
    } catch {
      if (!current() || ac.signal.aborted) return;
      setItems(prev);
      setError(appText("Не получилось отказаться.", "Баш тартып булманы."));
    } finally { if (acting.current === ac) actionBusy.current = false; }
  }

  return (
    <>
      <SubHeader
        title={appText("Отклики водителей", "Йөрөтөүселәр яуабы")}
        subtitle={appText("Выбери, с кем поедешь", "Кем менән барырыңды һайла")}
        onBack={() => navigate(-1)}
      />

      <button
        type="button"
        className="btn-soft"
        style={{ marginTop: 10 }}
        onClick={() => navigate(`/requests/${requestId}/edit`)}
      >
        <IconPencil size={17} /> {appText("Редактировать заявку", "Заявканы үҙгәртеү")}
      </button>

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "ready" &&
        (items.length === 0 ? (
          <div className="state">
            <div className="state__icon"><IconClock size={34} /></div>
            <h2>{appText("Пока нет откликов", "Әле яуап юҡ")}</h2>
            <p>{appText("Водители ещё думают. Мы сообщим, как только кто-то предложит поездку.", "Йөрөтөүселәр уйлай әле. Кемдер тәҡдим итһә, хәбәр итәбеҙ.")}</p>
          </div>
        ) : (
          <div className="list">
            {items.map((r) => {
              const isAccepted = r.status === "accepted";
              const isOffered = r.status === "offered";
              return (
                <div key={r.id} className="offer-card">
                  <div className="offer-card__top">
                    <span className="ride-card__avatar" aria-hidden>
                      {(r.driver_name || "?").trim().charAt(0).toUpperCase()}
                    </span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="ride-card__driver-name">{r.driver_name}</div>
                      <div className="ride-card__driver-sub">
                        <IconStar size={13} />{" "}
                        {r.driver_rating != null
                          ? r.driver_rating.toFixed(1)
                          : appText("новый", "яңы")}
                      </div>
                    </div>
                    <div className="ride-card__price">
                      {priceLabel(r.current_price || r.price, ru)}
                    </div>
                  </div>
                  {r.comment && <p className="offer-card__comment">{r.comment}</p>}

                  {/* Торг: дорожка ходов + чей сейчас ход. Один ход — дорожка не рисуется. */}
                  <ResponseRouteNote response={r} />
                  <BargainTrail
                    history={parseBargainHistory(r.bargain_history)}
                    mine="passenger"
                    rounds={isOffered ? r.bargain_rounds : undefined}
                  />
                  {isOffered && (
                    <span
                      className={"bargain-turn" + (r.can_accept || r.can_counter ? " bargain-turn--mine" : "")}
                    >
                      {r.can_accept || r.can_counter ? (
                        <>
                          <IconBolt size={14} /> {appText("Твой ход", "Һинең сират")}
                        </>
                      ) : (
                        appText("Ход водителя — ждём ответа", "Йөрөтөүсе сираты — яуап көтәбеҙ")
                      )}
                    </span>
                  )}

                  {isAccepted ? (
                    <div className="offer-card__accepted">
                      <IconCheck size={18} /> {appText("Принят", "Ҡабул ителгән")}
                    </div>
                  ) : !isOffered ? (
                    <p className="bargain-turn">
                      {r.status === "declined"
                        ? appText("Торг закрыт: по цене не сошлись", "Һатыулашыу ябыҡ: хаҡ буйынса килешмәнек")
                        : r.status === "withdrawn"
                        ? appText("Отклик отозван", "Яуап кире алынды")
                        : r.status === "expired"
                        ? appText("Заявка закрылась — человек уже уехал", "Заявка ябылған — кеше киткән инде")
                        : appText("Отклик закрыт", "Яуап ябылған")}
                    </p>
                  ) : counterFor === r.id ? (
                    <div style={{ marginTop: 12 }}>
                      <label className="field">
                        <span className="field__label">{appText("Твоя цена, ₽", "Һинең хаҡ, һ")}</span>
                        <input
                          className="field__input"
                          type="number"
                          inputMode="numeric"
                          min={0}
                          value={counterPrice}
                          onChange={(e) => setCounterPrice(e.target.value)}
                          placeholder={String(r.current_price || r.price)}
                        />
                      </label>
                      <div className="act-card__actions" style={{ marginTop: 10 }}>
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={() => sendCounter(r)}
                          disabled={bargaining || !counterPrice}
                        >
                          {bargaining
                            ? appText("Отправляем…", "Ебәрәбеҙ…")
                            : appText("Предложить", "Тәҡдим итеү")}
                        </button>
                        <button
                          type="button"
                          className="btn-ghost"
                          onClick={() => {
                            setCounterFor(null);
                            setCounterPrice("");
                          }}
                        >
                          {appText("Отмена", "Баш тартыу")}
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div className="act-card__actions" style={{ marginTop: 12 }}>
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => accept(r.id)}
                        disabled={accepting != null || !r.can_accept}
                      >
                        {accepting === r.id
                          ? appText("Принимаем…", "Ҡабул итәбеҙ…")
                          : appText("Поехать с ним", "Уның менән барырға")}
                      </button>
                      {r.can_counter && (
                        <button
                          type="button"
                          className="btn-soft"
                          onClick={() => {
                            setCounterFor(r.id);
                            setCounterPrice(String(r.current_price || r.price));
                            setError(null);
                          }}
                        >
                          {appText("Своя цена", "Үҙ хаҡым")}
                        </button>
                      )}
                    </div>
                  )}

                  {isOffered && counterFor !== r.id && (
                    <button
                      type="button"
                      className="link-btn"
                      style={{ marginTop: 8 }}
                      onClick={() => refuse(r)}
                    >
                      {appText("Отказаться", "Баш тартыу")}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        ))}

      {error && <div className="auth__error" role="alert">{error}</div>}

      {/* Мэтчинг: готовые поездки по маршруту заявки. Эндпоинта нет/ошибка → блок скрыт. */}
      {status === "ready" && matches !== null && (
        <>
          <h2 className="section-title">
            {appText("Подходящие поездки", "Тап килгән сәфәрҙәр")}
          </h2>
          {matches.length === 0 ? (
            <div className="state" style={{ paddingTop: 8 }}>
              <div className="state__icon">
                <IconClock size={30} />
              </div>
              <p>
                {appText(
                  "Пока нет совпадений — как появятся, покажем.",
                  "Әле тап килгәндәр юҡ — булыу менән күрһәтәбеҙ."
                )}
              </p>
            </div>
          ) : (
            <div className="list">
              {matches.map((r, i) => (
                <button
                  key={r.id}
                  type="button"
                  className="ride-card-btn"
                  onClick={() => setOpenedRide(r)}
                >
                  <RideCard ride={r} index={i} />
                </button>
              ))}
            </div>
          )}
          {matchesPartial && <PartialFeedNotice />}
        </>
      )}

      {/* Шторка поездки — тап по совпадению → бронь (как на витрине). */}
      {openedRide && <RideSheet ride={openedRide} onClose={() => setOpenedRide(null)} />}
    </>
  );
}
