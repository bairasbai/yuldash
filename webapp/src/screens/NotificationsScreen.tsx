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
import {
  IconChat,
  IconCar,
  IconRoute,
  IconBell,
  IconWarn,
} from "../components/Icons";

type Tab = "all" | "trips" | "delivery" | "messages" | "system";
type Load = "loading" | "ok" | "error";

/**
 * Куда ведёт уведомление.
 *
 * Карточка пружинит под пальцем — то есть обещает переход. Если по половине событий
 * тап не ведёт никуда, человек читает «Курьер забрал посылку», жмёт и остаётся на том же
 * месте: приложение соврало. Поэтому обработаны ВСЕ виды, которые шлёт сервер
 * (`ref_kind` в backend/app), а не только те, под которые нашёлся экран за минуту.
 *
 * Часть видов ведёт не на «свою» страницу, а туда, где событие видно целиком: посылка —
 * в список доставок, такси-заказ — на экран заказа (он сам подхватит активный), долг —
 * в кабинет водителя. Это осознанно: отдельной страницы у них нет, а привести человека
 * к нужному месту важнее, чем к точному адресу.
 */
function deepLink(n: AppNotification): string | null {
  switch (n.ref_kind) {
    // --- события с собственной страницей ---
    case "booking":
      return n.ref_id ? `/booking/${n.ref_id}` : null;
    case "request":
      return n.ref_id ? `/requests/${n.ref_id}/responses` : null;
    case "support":
      return n.ref_id ? `/support/${n.ref_id}` : null;
    // Разбор — самое тяжёлое, что бывает с аккаунтом: человеку надо видеть,
    // за что именно и на какой срок.
    case "incident":
      return n.ref_id ? `/incidents/${n.ref_id}` : null;

    // --- события без своей страницы: ведём туда, где они видны ---
    case "parcel":
      return "/parcels";
    case "instant":
      return "/taxi";
    // Поездка, которую человек опубликовал сам: живёт в кабинете водителя.
    case "ride":
      return "/driver";
    // Деньги и допуск к работе: долг, списание комиссии, пауза такси.
    case "debt":
      return "/driver";
    case "taxi_apply":
      return "/taxi-onboarding";
    case "courier_apply":
      return "/courier-onboarding";
    // Деньги бизнеса: человек заплатил и ждёт ответа. Сказать «одобрено»
    // и никуда не привести — половина дела.
    case "partner":
      return "/partner";
    case "ad":
      return "/ads";
    default:
      return null;
  }
}

function matchesTab(n: AppNotification, tab: Tab): boolean {
  switch (tab) {
    // «Поездки» — это ВСЁ, чем человек куда-то ехал: попутка и такси.
    // Без такси половина событий не находилась ни на одной вкладке, кроме «Все».
    case "trips":
      return n.type === "booking" || n.type === "ride" || n.type === "taxi" || n.type === "instant";
    case "delivery":
      return n.type === "parcel";
    case "messages":
      return n.type === "message";
    // «Система» — всё, что не про конкретную поездку: документы, деньги, безопасность,
    // реклама, приглашения. Раньше сюда попадал только один тип из шестнадцати.
    case "system":
      return !["booking", "ride", "taxi", "instant", "parcel", "message"].includes(n.type);
    default:
      return true;
  }
}

/** Линиевая иконка-маркер типа (спокойная, без цветовой перегрузки). */
function TypeIcon({ n }: { n: AppNotification }) {
  if (n.ref_kind === "support") return <IconChat size={20} />;
  switch (n.type) {
    case "booking":
      return <IconCar size={20} />;
    case "ride":
      return <IconRoute size={20} />;
    case "message":
      return <IconBell size={20} />;
    default:
      return <IconBell size={20} />;
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
    { key: "delivery", label: appText("Доставка", "Илтеү") },
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
    // Помечаем сразу — так экран отзывчивее. Но если сервер не принял, надо
    // вернуть как было: иначе счётчик показывает ноль, а непрочитанные остаются,
    // и человек находит их снова при следующем заходе.
    const prevItems = items;
    const prevUnread = unread;
    setItems((prev) => prev.map((x) => ({ ...x, read: true })));
    setUnread(0);
    try {
      await markAllRead();
    } catch {
      setItems(prevItems);
      setUnread(prevUnread);
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
          <div className="state__icon state__icon--warn"><IconWarn size={34} /></div>
          <h2>{appText("Не удалось загрузить", "Йөкләп булманы")}</h2>
          <p>{appText("Проверь соединение и попробуй снова.", "Бәйләнеште тикшереп, ҡабат ҡара.")}</p>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {state === "ok" && shown.length === 0 && (
        <div className="state">
          <div className="state__icon"><IconBell size={34} /></div>
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
                <span className="notif-row__ico" aria-hidden><TypeIcon n={n} /></span>
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
                        {appText("Открыть", "Асыу")} ›
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
