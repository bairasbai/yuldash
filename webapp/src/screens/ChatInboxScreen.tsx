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
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../api/client";
import { fetchConversations, type Conversation } from "../api/chat";
import { LoadingList } from "../components/States";
import ScreenHeader from "../components/ScreenHeader";
import { IconShield } from "../components/Icons";
import { YuChat } from "../components/BrandIcons";
import { formatWhen } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready" | "guest";

export default function ChatInboxScreen() {
  const { appText, lang, t } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<Conversation[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    // Вкладка «Чаты» видна и гостю. Раньше он получал от сервера отказ и читал
    // «Ошибка» — как будто сайт сломался. «Сначала войди» — совсем другое дело.
    if (!isAuthed) {
      setStatus("guest");
      return;
    }
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
  }, [isAuthed]); // вошёл прямо отсюда — экран должен сам перезагрузиться

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  return (
    <>
      <ScreenHeader
        title={t("navChat")}
        subtitle={appText("Общайся по активным поездкам и заявкам", "Актив сәфәрҙәр һәм заявкалар буйынса аралаш")}
      />

      {status === "loading" && <LoadingList count={3} />}

      {status === "guest" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <YuChat size={36} />
          </div>
          <h2>{appText("Войди, чтобы переписываться", "Яҙышыр өсөн ин")}</h2>
          <p>
            {appText(
              "Здесь появятся переписки с попутчиками — по каждой поездке своя.",
              "Бында юлдаштар менән яҙышыу күренәсәк — һәр сәфәр буйынса үҙенеке."
            )}
          </p>
          <button type="button" className="btn-primary" onClick={() => navigate("/login")}>
            {appText("Войти", "Инеү")}
          </button>
        </div>
      )}

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
            {appText("Повторить", "Ҡабатлау")}
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
                "Сәфәр бронла — бында йөрөтөүсе менән чат күренәсәк."
              )}
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate("/map")}>
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
                {/* ChatCard Android: аватар 58, имя 19 Bold + галочка, маршрут 16 Bold, последнее 14 muted, время справа. */}
                <span className="inbox-row__avatar" aria-hidden>
                  {c.peer_avatar ? (
                    <img src={c.peer_avatar} alt="" />
                  ) : (
                    (c.peer_name || "?").trim().charAt(0).toUpperCase()
                  )}
                </span>
                <span className="inbox-row__main">
                  <span className="inbox-row__name">
                    {c.peer_name}
                    {c.peer_verified && (
                      <span className="inbox-row__verified" aria-label={appText("Проверен", "Тикшерелгән")}>
                        <IconShield size={18} />
                      </span>
                    )}
                  </span>
                  <span className="inbox-row__route">{c.route}</span>
                  {c.last_message && <span className="inbox-row__last">{c.last_message}</span>}
                </span>
                {c.depart_at && <span className="inbox-row__when">{formatWhen(c.depart_at, ru)}</span>}
              </button>
            ))}
          </div>
        ))}
    </>
  );
}
