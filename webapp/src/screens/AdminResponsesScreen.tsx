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
import { AdminIntro, ListedEmpty, ListedLoading, SmallAvatar } from "../components/adminUi";
import { priceLabel } from "../utils/format";

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
      <SubHeader title={appText("Отклики по заявке", "Заявка буйынса яуаптар")} onBack={() => navigate(-1)} />
      <div className="alist">
        <AdminIntro>
          {appText(
            "Из Telegram-уведомления возьми № заявки. Открой отклики и прими за пользователя после звонка.",
            "Telegram хәбәренән заявка № ал. Шылтыратҡас яуаптарҙы ас, ҡулланыусы өсөн ҡабул ит."
          )}
        </AdminIntro>

        {/* Поле № заявки и «Открыть» в один ряд, как в приложении. */}
        <div className="alookup">
          <label className="field">
            <span className="field__label">{appText("№ заявки", "Заявка №")}</span>
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              value={reqId}
              onChange={(e) => setReqId(e.target.value.replace(/\D/g, "").slice(0, 8))}
              onKeyDown={(e) => e.key === "Enter" && load()}
              placeholder="123"
            />
          </label>
          <button type="button" className="abtn" onClick={load} disabled={!reqId.trim() || state === "loading"}>
            {state === "loading" ? appText("…", "…") : appText("Открыть", "Асыу")}
          </button>
        </div>

        {error && <div className="auth__error">{error}</div>}

        {state === "loading" && <ListedLoading />}

        {accepted && (
          <p className="dl-hint">
            {appText(
              `Поездка создана (бронь #${accepted.bookingId}). Перезвони пассажиру и водителю.`,
              `Сәфәр булдырылды (бронь #${accepted.bookingId}). Пассажирға һәм йөрөтөүсегә шылтырат.`
            )}
          </p>
        )}

        {state === "ready" && items.length === 0 && (
          <ListedEmpty
            title={appText("Откликов нет или заявка не найдена", "Яуап юҡ йәки заявка табылманы")}
            subtitle={appText(
              `На заявку #${loadedId} водители ещё не откликнулись.`,
              `#${loadedId} заявкаға йөрөтөүселәр әле яуап бирмәне.`
            )}
          />
        )}

        {state === "ready" &&
          items.map((it) => {
            const isAccepted = it.status === "accepted";
            return (
              <article key={it.id} className="acard">
                <div className="acard__row">
                  <SmallAvatar src={it.driver_avatar} name={it.driver_name} size={42} />
                  <strong className="acard__title">{it.driver_name}</strong>
                  {it.driver_rating != null && <span className="acard__sub">★ {it.driver_rating}</span>}
                  <span className="acard__spacer" />
                  {it.price > 0 && <b className="acard__amount acard__amount--green">{priceLabel(it.price, ru)}</b>}
                </div>
                {it.comment && <span className="acard__sub">{it.comment}</span>}
                {isAccepted ? (
                  <span className="atext atext--green">{appText("Принято", "Ҡабул ителде")}</span>
                ) : (
                  <button
                    type="button"
                    className="abtn"
                    onClick={() => accept(it.id)}
                    disabled={acceptingId !== null || alreadyAccepted}
                  >
                    {acceptingId === it.id
                      ? appText("Принимаем…", "Ҡабул итәбеҙ…")
                      : appText("Принять за пользователя", "Ҡулланыусы өсөн ҡабул итеү")}
                  </button>
                )}
              </article>
            );
          })}
      </div>
    </>
  );
}
