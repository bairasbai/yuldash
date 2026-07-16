// ================================================================
//  Центр уведомлений → /notifications (RequireAuth).
//  Вкладки Все/Поездки/Сообщения/Система, непрочитанные сверху
//  (порядок с бэка), относительное время. Тап → mark-read + deep-link
//  (booking→/booking/:id, request→отклики, support→/support/:id).
//  «Прочитать всё». Все состояния: загрузка / пусто / ошибка.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import {
  fetchNotifications,
  markAllRead,
  markRead,
  type AppNotification,
} from "../api/notifications";
import { formatRelative } from "../utils/format";
import { SubHeader } from "./ConsentsScreen";
import { LoadingList } from "../components/States";

type Tab = "all" | "trips" | "messages" | "system";
type Load = "loading" | "ok" | "error";

/** Куда ведёт уведомление по ref_kind/ref_id (или никуда). */
function deepLink(n: AppNotification): string | null {
  if (!n.ref_id) return null;
  switch (n.ref_kind) {
    case "booking":
      return `/booking/${n.ref_id}`;
    case "request":
      return `/requests/${n.ref_id}/responses`;
    case "support":
      return `/support/${n.ref_id}`;
    default:
      return null;
  }
}

function matchesTab(n: AppNotification, tab: Tab): boolean {
  switch (tab) {
    case "trips":
      return n.type === "booking" || n.type === "ride";
    case "messages":
      return n.type === "message";
    case "system":
      return n.type === "system";
    default:
      return true;
  }
}

/** Эмодзи-маркер типа (спокойный, без цветовой перегрузки). */
function typeEmoji(n: AppNotification): string {
  if (n.ref_kind === "support") return "💬";
  switch (n.type) {
    case "booking":
      return "🚗";
    case "ride":
      return "🧭";
    case "message":
      return "✉️";
    default:
      return "🔔";
  }
}

export default function NotificationsScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [items, setItems] = useState<AppNotification[]>([]);
  const [unread, setUnread] = useState(0);
  const [state, setState] = useState<Load>("loading");
  const [tab, setTab] = useState<Tab>("all");

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchNotifications(signal)
      .then((r) => {
        setItems(r.items);
        setUnread(r.unread);
        setState("ok");
      })
      .catch(() => {
        if (signal?.aborted) return; // размонтирование — не показываем ошибку
        setState("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const tabs: { key: Tab; label: string }[] = [
    { key: "all", label: appText("Все", "Барыһы") },
    { key: "trips", label: appText("Поездки", "Сәфәрҙәр") },
    { key: "messages", label: appText("Сообщения", "Хәбәрҙәр") },
    { key: "system", label: appText("Система", "Система") },
  ];

  const shown = useMemo(
    () => items.filter((n) => matchesTab(n, tab)),
    [items, tab]
  );

  async function open(n: AppNotification) {
    // Оптимистично гасим непрочитанное, чтобы переход был мгновенным.
    if (!n.read) {
      setItems((prev) =>
        prev.map((x) => (x.id === n.id ? { ...x, read: true } : x))
      );
      setUnread((u) => Math.max(0, u - 1));
      markRead(n.id).catch(() => {});
    }
    const to = deepLink(n);
    if (to) navigate(to);
  }

  async function readAll() {
    setItems((prev) => prev.map((x) => ({ ...x, read: true })));
    setUnread(0);
    try {
      await markAllRead();
    } catch {
      /* мягко: счётчик обновится при следующей загрузке */
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Уведомления", "Хәбәрҙәр")}
        subtitle={
          unread > 0
            ? appText(`${unread} новых`, `${unread} яңы`)
            : appText("Всё прочитано", "Барыһы уҡылған")
        }
        onBack={() => navigate(-1)}
      />

      <div className="chips" role="tablist" aria-label={appText("Фильтр уведомлений", "Хәбәр фильтры")}>
        {tabs.map((tb) => (
          <button
            key={tb.key}
            type="button"
            role="tab"
            aria-selected={tab === tb.key}
            className={"chip" + (tab === tb.key ? " chip--on" : "")}
            onClick={() => setTab(tb.key)}
          >
            {tb.label}
          </button>
        ))}
      </div>

      {unread > 0 && state === "ok" && (
        <button type="button" className="link-btn notif-readall" onClick={readAll}>
          {appText("Прочитать всё", "Барыһын уҡылған итергә")}
        </button>
      )}

      {state === "loading" && <LoadingList count={5} />}

      {state === "error" && (
        <div className="state">
          <div className="state__emoji">📡</div>
          <h2>{appText("Не удалось загрузить", "Йөкләп булманы")}</h2>
          <p>{appText("Проверь соединение и попробуй снова.", "Бәйләнеште тикшереп, ҡабат ҡара.")}</p>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {state === "ok" && shown.length === 0 && (
        <div className="state">
          <div className="state__emoji">🔔</div>
          <h2>{appText("Пока пусто", "Әлегә буш")}</h2>
          <p>
            {appText(
              "Здесь появятся отклики на поездки, сообщения и новости Юлдаша.",
              "Бында сәфәр яуаптары, хәбәрҙәр һәм Юлдаш яңылыҡтары күренәсәк."
            )}
          </p>
        </div>
      )}

      {state === "ok" && shown.length > 0 && (
        <div className="notif-list">
          {shown.map((n) => {
            const linked = deepLink(n) !== null;
            return (
              <button
                key={n.id}
                type="button"
                className={"notif-row" + (n.read ? "" : " notif-row--unread")}
                onClick={() => open(n)}
              >
                <span className="notif-row__ico" aria-hidden>{typeEmoji(n)}</span>
                <div className="notif-row__main">
                  <div className="notif-row__title">
                    {appText(n.title_ru, n.title_ba)}
                    {!n.read && <span className="notif-dot" aria-hidden />}
                  </div>
                  {(n.body_ru || n.body_ba) && (
                    <div className="notif-row__body">{appText(n.body_ru, n.body_ba)}</div>
                  )}
                  <div className="notif-row__meta">
                    <span>{formatRelative(n.created_at, ru)}</span>
                    {linked && (
                      <span className="notif-row__open">
                        {appText("Открыть", "Асырға")} ›
                      </span>
                    )}
                  </div>
                </div>
              </button>
            );
          })}
        </div>
      )}
    </>
  );
}
