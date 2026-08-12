// ================================================================
//  Вкладка «Чат» — инбокс диалогов (GET /conversations).
//  Раньше здесь была заглушка «Скоро здесь»: человек, которому
//  написал водитель, не имел ни одного способа найти переписку,
//  кроме как заново открыть поездку.
//
//  Строка = собеседник + маршрут + последнее сообщение → тап
//  открывает чат брони (/trip/{id}).
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchConversations, type Conversation } from "../api/chat";
import { LoadingList } from "../components/States";
import ScreenHeader from "../components/ScreenHeader";
import { IconCheck, IconChevron } from "../components/Icons";
import { YuChat } from "../components/BrandIcons";
import { formatWhen } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

export default function ChatInboxScreen() {
  const { appText, lang, t } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<Conversation[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchConversations(signal)
      .then((list) => {
        setRows(list);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 401 разберёт слой авторизации; 404 = эндпоинта ещё нет → мягко «скоро».
        setStatus(e instanceof ApiError && e.status === 404 ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <ScreenHeader
        title={t("navChat")}
        subtitle={appText("Переписка по поездкам", "Сәфәрҙәр буйынса яҙышыу")}
      />

      {status === "loading" && <LoadingList count={3} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <YuChat size={36} />
          </div>
          <h2>{t("soon")}</h2>
          <p>{t("soonHint")}</p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <YuChat size={36} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {status === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 32 }}>
            <div className="state__icon">
              <YuChat size={36} />
            </div>
            <h2>{appText("Переписок пока нет", "Әлегә яҙышыу юҡ")}</h2>
            <p>
              {appText(
                "Забронируй поездку — и здесь появится чат с водителем.",
                "Сәфәр бронла — бында водитель менән чат күренәсәк."
              )}
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate("/rides")}>
              {appText("Найти поездку", "Сәфәр табыу")}
            </button>
          </div>
        ) : (
          <div className="list">
            {rows.map((c) => (
              <button
                key={c.booking_id}
                type="button"
                className="inbox-row"
                onClick={() => navigate(`/trip/${c.booking_id}`)}
              >
                <span className="ride-card__avatar" aria-hidden>
                  {(c.peer_name || "?").trim().charAt(0).toUpperCase()}
                </span>
                <span className="inbox-row__main">
                  <span className="inbox-row__top">
                    <span className="inbox-row__name">
                      {c.peer_name}
                      {c.peer_verified && (
                        <span className="badge badge--mint" style={{ marginLeft: 6 }}>
                          <IconCheck size={12} />
                        </span>
                      )}
                    </span>
                    {c.depart_at && (
                      <span className="inbox-row__when">{formatWhen(c.depart_at, ru)}</span>
                    )}
                  </span>
                  <span className="inbox-row__route">{c.route}</span>
                  {c.last_message && (
                    <span className="inbox-row__last">{c.last_message}</span>
                  )}
                </span>
                <IconChevron size={18} />
              </button>
            ))}
          </div>
        ))}
    </>
  );
}
