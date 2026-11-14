// ================================================================
//  «Мои подписки на маршрут» — карауль поездку (route_watch.py).
//  GET /route-watch (список), POST /route-watch (создать),
//  DELETE /route-watch/{id}. Подписка живёт 14 дней. RequireAuth.
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getAccountSession, isAccountSession } from "../api/client";
import {
  createRouteWatch,
  deleteRouteWatch,
  fetchRouteWatches,
  validWatchPlace,
  watchLocalDay,
  type RouteWatch,
  type WatchDirection,
  type WatchOwner,
} from "../api/routeWatch";
import { LoadingList, ErrorState } from "../components/States";
import CityField from "../components/CityField";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconBell, IconTrash } from "../components/Icons";
import { track } from "../analytics";
import { pushSupported } from "../push/webPush";
import { serverDate } from "../utils/serverTime";

type Status = "loading" | "error" | "ready";

export default function RouteWatchesScreen() {
  const [params] = useSearchParams();
  const owner = getAccountSession();
  return owner ? <RouteWatches key={JSON.stringify([owner.userId, owner.generation, params.toString()])}
    owner={owner} params={params} /> : <LoadingList count={2} />;
}

function RouteWatches({ owner, params }: { owner: WatchOwner; params: URLSearchParams }) {
  const { appText, lang } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [watches, setWatches] = useState<RouteWatch[]>([]);

  const [from, setFrom] = useState(params.get("from") ?? "");
  const [to, setTo] = useState(params.get("to") ?? "");
  const [day, setDay] = useState(params.get("date")?.match(/^\d{4}-\d{2}-\d{2}$/)?.[0] ?? "");
  const [direction, setDirection] = useState<WatchDirection>("forward");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const operation = useRef(false);
  const locale = lang === "ba" ? "ba-RU" : "ru-RU";
  const { generation, userId } = owner;

  const load = useCallback((signal?: AbortSignal) => {
    const session = { generation, userId };
    setStatus("loading");
    fetchRouteWatches(session, signal)
      .then((rows) => {
        if (signal?.aborted || !isAccountSession(session)) return;
        setWatches(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || !isAccountSession(session) || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setWatches([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, [generation, userId]);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function add() {
    if (!from.trim() || !to.trim() || operation.current || !isAccountSession(owner)) return;
    if (![from.trim(), to.trim()].every(validWatchPlace)) {
      setError(appText("В названии места нужны хотя бы две буквы, без цифр и до 120 знаков.",
        "Урын исемендә кәм тигәндә ике хәреф булһын, һандарһыҙ һәм 120 тамғаға тиклем."));
      return;
    }
    operation.current = true;
    setBusy(true);
    setError(null);
    try {
      const watch = await createRouteWatch(owner, {
        from_city: from.trim(),
        to_city: to.trim(),
        direction,
        watch_kind: "rides",
        watch_date: day ? `${day}T00:00:00` : null,
      });
      if (!isAccountSession(owner)) return;
      track("route_watch_create");
      setWatches((rows) => [watch, ...rows.filter((row) => row.id !== watch.id)]);
      setFrom("");
      setTo("");
      setDirection("forward");
      setDay("");
    } catch (e) {
      if (!isAccountSession(owner)) return;
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось подписаться. Попробуй снова.", "Яҙылып булманы. Ҡабат ҡара.")
      );
    } finally {
      operation.current = false;
      if (isAccountSession(owner)) setBusy(false);
    }
  }

  async function remove(id: number) {
    if (operation.current || !isAccountSession(owner)) return;
    operation.current = true;
    setBusy(true);
    setError(null);
    try {
      await deleteRouteWatch(owner, id);
      if (isAccountSession(owner)) setWatches((rows) => rows.filter((row) => row.id !== id));
    } catch (failure) {
      if (!isAccountSession(owner)) return;
      if (failure instanceof ApiError && failure.status === 404) {
        setWatches((rows) => rows.filter((row) => row.id !== id));
      } else {
        setError(appText("Не получилось удалить подписку. Попробуй снова.", "Яҙылыуҙы юйып булманы. Ҡабатлап ҡара."));
      }
    } finally {
      operation.current = false;
      if (isAccountSession(owner)) setBusy(false);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Подписки на маршрут", "Маршрут яҙылыуҙары")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && (
        <>
          {watches.length === 0 ? (
            <div className="state" style={{ paddingBottom: 20 }}>
              <div className="state__icon"><IconBell size={34} /></div>
              <h2>{appText("Нет подписок", "Яҙылыуҙар юҡ")}</h2>
              <p>
                {appText(
                  "Подпишись на маршрут — и мы сообщим, как только кто-то поедет.",
                  "Маршрутҡа яҙыл — кемдер юлға сыҡһа, хәбәр итәбеҙ."
                )}
              </p>
            </div>
          ) : (
            <div className="list">
              {watches.map((w) => {
                const watchDay = watchLocalDay(w.watch_date);
                return (
                <div key={w.id} className="list-row">
                  <span className="list-row__icon">
                    <IconBell size={22} />
                  </span>
                  <div className="list-row__main">
                    <div className="repeat-route">
                      <span>{w.from_city}</span>
                      <span className="repeat-route__arrow">
                        <IconArrow size={18} />
                      </span>
                      <span>{w.to_city}</span>
                    </div>
                    <div className="list-row__sub">
                      {w.watch_kind === "requests" ? appText("Заявки пассажиров", "Юлсылар ғаризалары") :
                        w.watch_kind === "both" ? appText("Поездки и заявки", "Сәфәрҙәр һәм ғаризалар") : appText("Поездки", "Сәфәрҙәр")}
                      {" · "}
                      {w.direction === "both"
                        ? appText("Туда и обратно", "Барыу-ҡайтыу")
                        : appText("В одну сторону", "Бер яҡҡа")}
                      {watchDay && ` · ${watchDay.split("-").reverse().join(".")}`}
                    </div>
                    <div className="list-row__sub">
                      {appText("Последнее сообщение", "Һуңғы хәбәр")}{" · "}
                      {serverDate(w.last_notified_at)?.toLocaleString(locale) ?? appText("Пока не было", "Әлегә булманы")}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="icon-btn"
                    disabled={busy}
                    onClick={() => remove(w.id)}
                    aria-label={appText("Удалить", "Юйыу")}
                  >
                    <IconTrash size={20} />
                  </button>
                </div>
                );
              })}
            </div>
          )}
          <WatchNotificationStatus watch={watches[0]} />

          <h2 className="section-title">{appText("Новая подписка", "Яңы яҙылыу")}</h2>
          <div className="form">
            <div className="field-row">
              <CityField label={appText("Откуда", "Ҡайҙан")} value={from} onChange={setFrom}
                onPick={(place) => setFrom(place.name_ru)} placeholder={appText("Сибай", "Сибай")} />
              <CityField label={appText("Куда", "Ҡайҙа")} value={to} onChange={setTo}
                onPick={(place) => setTo(place.name_ru)} placeholder={appText("Уфа", "Өфө")} />
            </div>
            <label className="field">
              <span className="field__label">{appText("Дата поездки (необязательно)", "Сәфәр көнө (мотлаҡ түгел)")}</span>
              <input type="date" className="field__input" value={day} onChange={(event) => setDay(event.target.value)} />
            </label>

            <div className="seg">
              <button
                type="button"
                className={"seg__item" + (direction === "forward" ? " is-active" : "")}
                onClick={() => setDirection("forward")}
              >
                {appText("В одну сторону", "Бер яҡҡа")}
              </button>
              <button
                type="button"
                className={"seg__item" + (direction === "both" ? " is-active" : "")}
                onClick={() => setDirection("both")}
              >
                {appText("Туда-обратно", "Барыу-ҡайтыу")}
              </button>
            </div>

            {error && <div className="auth__error" role="alert">{error}</div>}

            <button
              type="button"
              className="btn-primary submit-btn"
              onClick={add}
              disabled={!from.trim() || !to.trim() || busy}
            >
              {busy ? (
                appText("Подписываем…", "Яҙабыҙ…")
              ) : (
                <>
                  <IconBell size={18} /> {appText("Следить за маршрутом", "Маршрутты күҙәтергә")}
                </>
              )}
            </button>
          </div>
        </>
      )}
    </>
  );
}

function WatchNotificationStatus({ watch }: { watch?: RouteWatch }) {
  const { appText } = useLang();
  const [browser, setBrowser] = useState<"checking" | "unsupported" | "denied" | "not-enabled" | "subscribed" | "unknown">("checking");

  useEffect(() => {
    let active = true;
    let revision = 0;
    async function update() {
      const run = ++revision;
      if (!pushSupported()) { setBrowser("unsupported"); return; }
      if (Notification.permission === "denied") { setBrowser("denied"); return; }
      if (Notification.permission !== "granted") { setBrowser("not-enabled"); return; }
      try {
        const registration = await navigator.serviceWorker.getRegistration();
        const subscription = await registration?.pushManager.getSubscription();
        if (active && run === revision) setBrowser(subscription ? "subscribed" : "not-enabled");
      } catch {
        if (active && run === revision) setBrowser("unknown");
      }
    }
    void update();
    window.addEventListener("focus", update);
    return () => { active = false; window.removeEventListener("focus", update); };
  }, []);

  const channels = watch?.registered_push_channels;
  const from = watch?.quiet_from;
  const to = watch?.quiet_to;
  const hour = (value: number) => `${String(value).padStart(2, "0")}:00`;
  return (
    <div className="form">
      {channels?.app && <p>{appText("Канал приложения зарегистрирован", "Ҡушымта каналы теркәлгән")}</p>}
      {channels?.web && <p>{appText("Канал браузера зарегистрирован", "Браузер каналы теркәлгән")}</p>}
      {channels && !channels.app && !channels.web && <p>{appText("Каналы отправки на устройство не зарегистрированы.", "Ҡоролмаға ебәреү каналдары теркәлмәгән.")}</p>}
      {browser === "unsupported" && <p>{appText("Этот браузер не поддерживает уведомления на устройство.", "Был браузер ҡоролмаға хәбәр ебәреүҙе хупламай.")}</p>}
      {browser === "denied" && <p>{appText("Уведомления на этом устройстве запрещены браузером.", "Был ҡоролмала браузер хәбәрҙәрҙе тыйған.")}</p>}
      {browser === "not-enabled" && <p>{appText("Уведомления на этом устройстве ещё не настроены.", "Был ҡоролмала хәбәрҙәр әле көйләнмәгән.")}</p>}
      {browser === "subscribed" && <p>{appText("В этом браузере есть разрешение и подписка на уведомления.", "Был браузерҙа хәбәрҙәргә рөхсәт һәм яҙылыу бар.")}</p>}
      {browser === "unknown" && <p>{appText("Не получилось проверить разрешение и подписку браузера.", "Браузер рөхсәтен һәм яҙылыуын тикшереп булманы.")}</p>}
      {(browser === "denied" || browser === "not-enabled") && (
        <Link to="/settings">{appText("Настройки уведомлений", "Хәбәр көйләүҙәре")}</Link>
      )}
      {typeof from === "number" && typeof to === "number" && to > 0 && from !== to && (
        <p>{appText("Тихие часы по Уфе", "Өфө ваҡыты буйынса тыныс сәғәттәр")} · {hour(from)}–{hour(to)}</p>
      )}
      <Link to="/notifications">{appText("Сообщения остаются в «Уведомлениях»", "Хәбәрҙәр «Хәбәрҙәр» бүлегендә ҡала")}</Link>
    </div>
  );
}
