// ================================================================
//  Отклики на мою заявку (GET /requests/{id}/responses).
//  Пассажир видит предложения водителей и принимает одно
//  (POST /responses/{id}/accept → booking_id) → активная поездка.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchRequestResponses,
  acceptResponse,
  type ResponseItem,
} from "../api/requests";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconStar, IconCheck } from "../components/Icons";
import { priceLabel } from "../utils/format";

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

  return (
    <>
      <SubHeader
        title={appText("Отклики водителей", "Водителдәр яуабы")}
        subtitle={appText("Выбери, с кем поедешь", "Кем менән барырыңды һайла")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "ready" &&
        (items.length === 0 ? (
          <div className="state">
            <div className="state__emoji">⏳</div>
            <h2>{appText("Пока нет откликов", "Әле яуап юҡ")}</h2>
            <p>{appText("Водители ещё думают. Мы сообщим, как только кто-то предложит поездку.", "Водителдәр уйлай әле. Кемдер тәҡдим итһә, хәбәр итәбеҙ.")}</p>
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
                    <div className="ride-card__price">{priceLabel(r.price, ru)}</div>
                  </div>
                  {r.comment && <p className="offer-card__comment">{r.comment}</p>}
                  {isAccepted ? (
                    <div className="offer-card__accepted">
                      <IconCheck size={18} /> {appText("Принят", "Ҡабул ителгән")}
                    </div>
                  ) : (
                    <button
                      type="button"
                      className="btn-primary"
                      style={{ width: "100%", marginTop: 12 }}
                      onClick={() => accept(r.id)}
                      disabled={accepting != null}
                    >
                      {accepting === r.id
                        ? appText("Принимаем…", "Ҡабул итәбеҙ…")
                        : appText("Поехать с ним", "Уның менән барырға")}
                    </button>
                  )}
                </div>
              );
            })}
            {error && <div className="auth__error">{error}</div>}
          </div>
        ))}
    </>
  );
}
