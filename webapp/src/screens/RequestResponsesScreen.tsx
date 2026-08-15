// ================================================================
//  Отклики на мою заявку (GET /requests/{id}/responses).
//  Пассажир видит предложения водителей и принимает одно
//  (POST /responses/{id}/accept → booking_id) → активная поездка.
//  Плюс мэтчинг: «Подходящие поездки» (GET /match/rides?request_id=)
//  — готовые поездки по маршруту заявки, тап → бронь (RideSheet).
//  Плюс «Редактировать» → /requests/{id}/edit (форма с префиллом).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
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
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconStar, IconCheck, IconClock, IconPencil, IconBolt } from "../components/Icons";
import { priceLabel } from "../utils/format";
import { track } from "../analytics";

export default function RequestResponsesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const requestId = Number(id);

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [items, setItems] = useState<ResponseItem[]>([]);
  const [accepting, setAccepting] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Мэтчинг: null = блок скрыт (эндпоинта ещё нет / нет доступа), [] = «пока нет совпадений».
  const [matches, setMatches] = useState<Ride[] | null>(null);
  const [openedRide, setOpenedRide] = useState<Ride | null>(null);
  // Торг: у какого отклика открыт ввод своей цены и что набрано.
  const [counterFor, setCounterFor] = useState<number | null>(null);
  const [counterPrice, setCounterPrice] = useState("");
  const [bargaining, setBargaining] = useState(false);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!requestId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchRequestResponses(requestId, signal)
        .then((list) => {
          setItems(list);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [requestId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Мэтчинг — параллельно откликам. Любая ошибка (404 до деплоя, 403) → блок скрыт.
  useEffect(() => {
    if (!requestId) return;
    const ac = new AbortController();
    fetchMatchRides(requestId, ac.signal)
      .then((rides) => {
        setMatches(rides);
        if (rides.length > 0) track("match_shown", { count: rides.length });
      })
      .catch(() => setMatches(null));
    return () => ac.abort();
  }, [requestId]);

  async function accept(responseId: number) {
    setAccepting(responseId);
    setError(null);
    try {
      const res = await acceptResponse(responseId);
      navigate(`/trip/${res.booking_id}`);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось принять. Попробуй снова.", "Ҡабул итеп булманы. Ҡабат ҡара.") // DRAFT
      );
      setAccepting(null);
    }
  }

  /** Встречная цена пассажира: «а за 400 поедешь?». Ходят по очереди — сервер решает, чей ход. */
  async function sendCounter(r: ResponseItem) {
    const value = Number(counterPrice);
    if (!Number.isFinite(value) || value <= 0) return;
    setBargaining(true);
    setError(null);
    try {
      const updated = await counterOffer(r.id, Math.round(value));
      setItems((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
      setCounterFor(null);
      setCounterPrice("");
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось предложить цену.", "Хаҡ тәҡдим итеп булманы.")
      );
      load();
    } finally {
      setBargaining(false);
    }
  }

  /** Отказ от отклика — торг закрывается, водитель получит уведомление. */
  async function refuse(r: ResponseItem) {
    const prev = items;
    setItems(items.filter((x) => x.id !== r.id)); // оптимистично
    try {
      await declineResponse(r.id);
    } catch {
      setItems(prev);
      setError(appText("Не получилось отказаться.", "Баш тартып булманы."));
    }
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
                  <BargainTrail
                    history={parseBargainHistory(r.bargain_history)}
                    mine="passenger"
                    rounds={r.bargain_rounds}
                  />
                  {!isAccepted && r.status === "offered" && (
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
                        disabled={accepting != null || (r.status === "offered" && !r.can_accept)}
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

                  {!isAccepted && r.status === "offered" && counterFor !== r.id && (
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
            {error && <div className="auth__error">{error}</div>}
          </div>
        ))}

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
        </>
      )}

      {/* Шторка поездки — тап по совпадению → бронь (как на витрине). */}
      {openedRide && <RideSheet ride={openedRide} onClose={() => setOpenedRide(null)} />}
    </>
  );
}
