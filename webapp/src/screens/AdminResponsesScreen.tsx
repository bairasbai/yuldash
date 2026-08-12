// ================================================================
//  Принять отклик за юзера → /admin/responses (RequireAdmin).
//  Админ вводит номер заявки → видит отклики водителей
//  (GET /requests/{id}/responses, админ видит любые) → принимает один
//  ЗА пользователя (POST /responses/{id}/accept) — создаётся поездка+бронь.
// ================================================================
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { adminRequestResponses, adminAcceptResponse } from "../api/admin";
import type { ResponseItem } from "../api/requests";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";
import { priceLabel } from "../utils/format";
import { IconStar, IconCheck, IconChat, IconShield } from "../components/Icons";

type State = "idle" | "loading" | "error" | "ready";

export default function AdminResponsesScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [reqId, setReqId] = useState("");
  const [state, setState] = useState<State>("idle");
  const [error, setError] = useState<string | null>(null);
  const [items, setItems] = useState<ResponseItem[]>([]);
  const [loadedId, setLoadedId] = useState<number | null>(null);

  const [acceptingId, setAcceptingId] = useState<number | null>(null);
  const [accepted, setAccepted] = useState<{ responseId: number; bookingId: number } | null>(null);

  async function load() {
    const id = parseInt(reqId.trim(), 10);
    if (!id || state === "loading") return;
    setState("loading");
    setError(null);
    setAccepted(null);
    try {
      const list = await adminRequestResponses(id);
      setItems(list);
      setLoadedId(id);
      setState("ready");
    } catch (e) {
      setError(
        e instanceof ApiError && e.status === 404
          ? appText("Заявка не найдена.", "Заявка табылманы.") // DRAFT
          : e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось загрузить отклики.", "Яуаптарҙы йөкләп булманы.") // DRAFT
      );
      setState("error");
    }
  }

  async function accept(responseId: number) {
    if (acceptingId) return;
    setAcceptingId(responseId);
    setError(null);
    try {
      const r = await adminAcceptResponse(responseId);
      setAccepted({ responseId, bookingId: r.booking_id });
      // Обновим статусы локально: принятый → accepted.
      setItems((prev) =>
        prev.map((it) => (it.id === responseId ? { ...it, status: "accepted" } : it))
      );
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось принять отклик.", "Яуапты ҡабул итеп булманы.") // DRAFT
      );
    } finally {
      setAcceptingId(null);
    }
  }

  const alreadyAccepted = items.some((it) => it.status === "accepted");

  return (
    <>
      <SubHeader
        title={appText("Принять отклик за юзера", "Юзер өсөн яуап")}
        subtitle={appText("Выбор водителя по заявке", "Заявка буйынса водитель")}
        onBack={() => navigate(-1)}
      />

      <div className="field-row" style={{ alignItems: "flex-end" }}>
        <label className="field" style={{ flex: 1 }}>
          <span className="field__label">{appText("Номер заявки", "Заявка номеры")}</span>
          <input
            className="field__input"
            type="number"
            inputMode="numeric"
            value={reqId}
            onChange={(e) => setReqId(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && load()}
            placeholder="123"
          />
        </label>
        <button
          type="button"
          className="btn-primary"
          style={{ height: 48 }}
          onClick={load}
          disabled={!reqId.trim() || state === "loading"}
        >
          {state === "loading" ? appText("Ищем…", "Эҙләйбеҙ…") : appText("Показать", "Күрһәт")}
        </button>
      </div>

      {error && <div className="auth__error" style={{ marginTop: 12 }}>{error}</div>}

      {state === "loading" && <LoadingList count={2} />}

      {accepted && (
        <div className="safe-note" style={{ marginTop: 12 }}>
          <div className="safe-note__emoji" aria-hidden><IconCheck size={22} /></div>
          <p>
            {appText(
              `Отклик принят. Поездка и бронь #${accepted.bookingId} созданы за пользователя.`,
              `Яуап ҡабул ителде. Сәфәр һәм бронь #${accepted.bookingId} юзер өсөн булдырылды.`
            )}
          </p>
        </div>
      )}

      {state === "ready" && items.length === 0 && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconShield size={34} /></div>
          <h2>{appText("Откликов пока нет", "Яуаптар әле юҡ")}</h2>
          <p>
            {appText(
              `На заявку #${loadedId} водители ещё не откликнулись.`,
              `#${loadedId} заявкаға водителдәр әле яуап бирмәне.`
            )}
          </p>
        </div>
      )}

      {state === "ready" && items.length > 0 && (
        <div className="list" style={{ marginTop: 12 }}>
          {items.map((it) => {
            const isAccepted = it.status === "accepted";
            return (
              <div key={it.id} className="list-row">
                <div className="list-row__main">
                  <div className="list-row__title" style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    {it.driver_name}
                    {it.driver_rating != null && (
                      <span className="profile-card__rating" style={{ fontSize: "var(--font-caption)" }}>
                        <IconStar size={13} /> {it.driver_rating.toFixed(1)}
                      </span>
                    )}
                  </div>
                  <div className="list-row__sub">
                    {priceLabel(it.price, ru)}
                    {it.comment ? ` · ${it.comment}` : ""}
                  </div>
                </div>
                {isAccepted ? (
                  <span className="badge badge--mint">{appText("Принят", "Ҡабул ителде")}</span>
                ) : (
                  <button
                    type="button"
                    className="btn-primary btn-soft--sm"
                    onClick={() => accept(it.id)}
                    disabled={acceptingId !== null || alreadyAccepted}
                  >
                    {acceptingId === it.id ? (
                      appText("Принимаем…", "Ҡабул итәбеҙ…")
                    ) : (
                      <><IconCheck size={16} /> {appText("Принять", "Ҡабул итеү")}</>
                    )}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {state === "idle" && (
        <div className="state" style={{ paddingTop: 24 }}>
          <div className="state__icon"><IconChat size={34} /></div>
          <h2>{appText("Введи номер заявки", "Заявка номерын индер")}</h2>
          <p>
            {appText(
              "Найди заявку по номеру и прими подходящий отклик за пользователя.",
              "Заявканы номеры буйынса тап һәм тап килгән яуапты юзер өсөн ҡабул ит."
            )}
          </p>
        </div>
      )}
    </>
  );
}
