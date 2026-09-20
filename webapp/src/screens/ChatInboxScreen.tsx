// ================================================================
//  Вкладка «Чат» — зеркало ChatScreen (RidesRequestsChatScreens.kt): три кнопки
//  «Активные / Заявки / Система» (последняя ведёт в уведомления и носит бейдж
//  непрочитанных), диалоги по броням (GET /conversations), мои заявки (карточкой
//  ChatCard → отклики), внизу карточка «Телефон открывается только после
//  подтверждения поездки». Все состояния честные: скелетон / ошибка / пусто.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { useAuth } from "../auth/AuthProvider";
import { ApiError } from "../api/client";
import { fetchConversations, type Conversation } from "../api/chat";
import { fetchMyRequests, type RideRequestRow } from "../api/requests";
import { fetchNotifUnread } from "../api/notifications";
import { EmptyStateCard, RideCardSkeleton } from "../components/States";
import ScreenHeader from "../components/ScreenHeader";
import { IconChat, IconRequest, IconSettings, IconShield } from "../components/Icons";
import { YuChat } from "../components/BrandIcons";
import { pluralRu } from "../utils/format";
import { formatDepart } from "../components/NearbyRideCard";

type Status = "loading" | "error" | "soon" | "ready" | "guest";
type Tab = "active" | "requests";

/** ChatCard: аватар 58, имя 19 Bold + галочка, подзаголовок 16 Bold, сообщение 14 muted, время справа. */
function ChatCard({
  initial,
  avatarUrl,
  name,
  subtitle,
  message,
  time,
  verified,
  onClick,
}: {
  initial: string;
  avatarUrl?: string;
  name: string;
  subtitle: string;
  message: string;
  time?: string;
  verified: boolean;
  onClick: () => void;
}) {
  const { appText } = useLang();
  return (
    <button type="button" className="inbox-row" onClick={onClick}>
      <span className="inbox-row__avatar" aria-hidden>
        {avatarUrl ? <img src={avatarUrl} alt="" /> : initial}
      </span>
      <span className="inbox-row__main">
        <span className="inbox-row__name">
          {name}
          {verified && (
            <span className="inbox-row__verified" aria-label={appText("Проверен", "Тикшерелгән")}>
              <IconShield size={18} />
            </span>
          )}
        </span>
        <span className="inbox-row__route">{subtitle}</span>
        {message && <span className="inbox-row__last">{message}</span>}
      </span>
      {time && <span className="inbox-row__when">{time}</span>}
    </button>
  );
}

export default function ChatInboxScreen() {
  const { appText, t } = useLang();
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const [tab, setTab] = useState<Tab>("active");
  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<Conversation[]>([]);
  // Заявки: отличаем «нет заявок» от «сеть упала», а пока грузим — скелетон, не ложное «пусто».
  const [requests, setRequests] = useState<RideRequestRow[]>([]);
  const [reqLoading, setReqLoading] = useState(true);
  const [reqError, setReqError] = useState(false);
  /** Бейдж непрочитанных на кнопке «Система». */
  const [notifUnread, setNotifUnread] = useState(0);

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
    setReqLoading(true);
    fetchMyRequests(signal)
      .then((list) => {
        setRequests(list);
        setReqError(false);
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // 401 → не вошёл (обычное «пусто»); иначе сеть упала → ошибка + «Повторить».
        setReqError(!(e instanceof ApiError && (e.status === 401 || e.status === 404)));
      })
      .finally(() => setReqLoading(false));
    fetchNotifUnread(signal)
      .then(setNotifUnread)
      .catch(() => {});
  }, [isAuthed]); // вошёл прямо отсюда — экран должен сам перезагрузиться

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const tabs: { key: Tab | "system"; label: string; icon: React.ReactNode }[] = [
    { key: "active", label: appText("Активные", "Актив"), icon: <IconChat size={18} /> },
    { key: "requests", label: appText("Заявки", "Заявкалар"), icon: <IconRequest size={18} /> },
    { key: "system", label: appText("Система", "Система"), icon: <IconSettings size={18} /> },
  ];

  function requestStatusLine(r: RideRequestRow): string {
    switch (r.status) {
      case "active":
        return appText("Смотреть отклики водителей", "Йөрөтөүсе яуаптарын ҡарау");
      case "matched":
        return appText("Водитель найден", "Йөрөтөүсе табылды");
      case "cancelled":
        return appText("Заявка отменена", "Ғариза кире алынған");
      default:
        return appText("Время вышло — откликов не будет", "Ваҡыт үтте — яуап булмаясаҡ");
    }
  }

  return (
    <>
      <ScreenHeader title={t("navChat")} subtitle={appText("Общайся по активным поездкам и заявкам", "Актив сәфәрҙәр һәм заявкалар буйынса аралаш")} />

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

      {status !== "guest" && (
        <div className="chat-inbox">
          {/* Три кнопки FilledTonal: выбранная — мята с зелёной иконкой; «Система» уводит в уведомления. */}
          <div className="chat-tabs" role="tablist">
            {tabs.map((tb) => {
              const on = tb.key === tab;
              return (
                <button
                  key={tb.key}
                  type="button"
                  role={tb.key === "system" ? undefined : "tab"}
                  aria-selected={tb.key === "system" ? undefined : on}
                  className={"chat-tab" + (on ? " is-on" : "")}
                  onClick={() => (tb.key === "system" ? navigate("/notifications") : setTab(tb.key))}
                >
                  <span className="chat-tab__icon" aria-hidden>{tb.icon}</span>
                  <span className="chat-tab__label">{tb.label}</span>
                  {tb.key === "system" && notifUnread > 0 && <span className="chat-tab__badge">{notifUnread > 99 ? "99+" : notifUnread}</span>}
                </button>
              );
            })}
          </div>

          {tab === "requests" ? (
            requests.length === 0 && reqLoading ? (
              <>
                <RideCardSkeleton />
                <RideCardSkeleton />
                <RideCardSkeleton />
              </>
            ) : requests.length === 0 && reqError ? (
              <EmptyStateCard
                title={appText("Не удалось загрузить заявки", "Заявкаларҙы йөкләп булманы")}
                text={appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}
                action={appText("Повторить", "Ҡабатлау")}
                onAction={() => load()}
              />
            ) : requests.length === 0 ? (
              <div className="info-card chat-inbox__info">
                <span className="info-card__icon" aria-hidden><IconRequest size={22} /></span>
                <span className="info-card__main">
                  <strong>{appText("Заявок пока нет", "Әлегә заявкалар юҡ")}</strong>
                  <small>{appText("Создай заявку на вкладке «Заявка» — водители откликнутся", "«Заявка» бүлегендә заявка яһа — йөрөтөүселәр яуап бирер")}</small>
                </span>
              </div>
            ) : (
              requests.map((r) => {
                // Закрытая заявка выглядела в точности как живая — подписываем честно.
                const closed = r.status !== "active";
                return (
                  <ChatCard
                    key={r.id}
                    initial={(r.from_city.charAt(0) || "?").toUpperCase()}
                    name={`${r.from_city} → ${r.to_city}`}
                    subtitle={
                      appText("Заявка", "Ғариза") +
                      " · " +
                      appText(`${r.seats} ${pluralRu(r.seats, "место", "места", "мест")}`, `${r.seats} урын`) +
                      (closed ? " · " + appText("закрыта", "ябыҡ") : "")
                    }
                    message={requestStatusLine(r)}
                    verified={false}
                    onClick={() => navigate(`/requests/${r.id}/responses`)}
                  />
                );
              })
            )
          ) : rows.length > 0 ? (
            rows.map((c) => (
              <ChatCard
                key={c.booking_id}
                initial={(c.peer_name || "?").trim().charAt(0).toUpperCase()}
                avatarUrl={c.peer_avatar || undefined}
                name={c.peer_name}
                subtitle={c.route}
                message={c.last_message ?? ""}
                time={c.depart_at ? formatDepart(c.depart_at) : ""}
                verified={!!c.peer_verified}
                onClick={() => navigate(`/trip/${c.booking_id}`)}
              />
            ))
          ) : status === "loading" ? (
            <>
              <RideCardSkeleton />
              <RideCardSkeleton />
              <RideCardSkeleton />
              <RideCardSkeleton />
            </>
          ) : status === "error" ? (
            <EmptyStateCard
              title={appText("Не удалось загрузить диалоги", "Диалогтарҙы йөкләп булманы")}
              text={appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}
              action={appText("Повторить", "Ҡабатлау")}
              onAction={() => load()}
            />
          ) : status === "soon" ? (
            <EmptyStateCard icon={<YuChat size={36} />} title={t("soon")} text={t("soonHint")} />
          ) : (
            /* ChatEmptyState: карточка 28 с рамкой, мятный круг с иконкой чата 38. */
            <div className="chat-empty">
              <span className="chat-empty__icon" aria-hidden><YuChat size={38} /></span>
              <strong>{appText("Здесь будут твои чаты", "Бында чаттарың булыр")}</strong>
              <span>
                {appText(
                  "Найди поездку и забронируй место — после брони откроется чат с водителем или пассажиром.",
                  "Сәфәр табып, урын бронла — бронынан һуң йөрөтөүсе йәки пассажир менән чат асыла."
                )}
              </span>
            </div>
          )}

          <div className="info-card chat-inbox__info">
            <span className="info-card__icon" aria-hidden><IconShield size={22} /></span>
            <span className="info-card__main">
              <strong>{appText("Телефон открывается только после подтверждения поездки", "Телефон сәфәр раҫланғандан һуң ғына асыла")}</strong>
              <small>{appText("Мы бережём твою безопасность", "Беҙ һинең хәүефһеҙлегеңде һаҡлайбыҙ")}</small>
            </span>
          </div>
        </div>
      )}
    </>
  );
}
