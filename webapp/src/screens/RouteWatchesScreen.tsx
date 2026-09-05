// ================================================================
//  «Мои подписки на маршрут» — карауль поездку (route_watch.py).
//  GET /route-watch (список), POST /route-watch (создать),
//  DELETE /route-watch/{id}. Как только водитель опубликует поездку
//  по маршруту — придёт push. Подписка живёт 14 дней. RequireAuth.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  createRouteWatch,
  deleteRouteWatch,
  fetchRouteWatches,
  type RouteWatch,
  type WatchDirection,
} from "../api/routeWatch";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconBell, IconTrash } from "../components/Icons";
import { track } from "../analytics";

type Status = "loading" | "error" | "ready";

export default function RouteWatchesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [watches, setWatches] = useState<RouteWatch[]>([]);

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [direction, setDirection] = useState<WatchDirection>("forward");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchRouteWatches(signal)
      .then((rows) => {
        setWatches(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        if (e instanceof ApiError && e.status === 404) {
          setWatches([]);
          setStatus("ready");
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function add() {
    if (!from.trim() || !to.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await createRouteWatch({
        from_city: from.trim(),
        to_city: to.trim(),
        direction,
      });
      track("route_watch_create");
      setFrom("");
      setTo("");
      setDirection("forward");
      load();
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось подписаться. Попробуй снова.", "Яҙылып булманы. Ҡабат ҡара.")
      );
    } finally {
      setBusy(false);
    }
  }

  async function remove(id: number) {
    setWatches((w) => w.filter((x) => x.id !== id));
    try {
      await deleteRouteWatch(id);
    } catch {
      load();
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Подписки на маршрут", "Маршрут яҙылыуҙары")}
        subtitle={appText("Появится попутка — сразу пришлём", "Юлдаш сыҡһа — шунда уҡ хәбәр итәбеҙ")}
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
              {watches.map((w) => (
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
                      {w.direction === "both"
                        ? appText("Туда и обратно", "Барыу-ҡайтыу")
                        : appText("В одну сторону", "Бер яҡҡа")}
                    </div>
                  </div>
                  <button
                    type="button"
                    className="icon-btn"
                    onClick={() => remove(w.id)}
                    aria-label={appText("Удалить", "Юйыу")}
                  >
                    <IconTrash size={20} />
                  </button>
                </div>
              ))}
            </div>
          )}

          <h2 className="section-title">{appText("Новая подписка", "Яңы яҙылыу")}</h2>
          <div className="form">
            <div className="field-row">
              <label className="field" style={{ flex: 1 }}>
                <span className="field__label">{appText("Откуда", "Ҡайҙан")}</span>
                <input
                  className="field__input"
                  value={from}
                  onChange={(e) => setFrom(e.target.value)}
                  placeholder={appText("Сибай", "Сибай")}
                  autoComplete="off"
                />
              </label>
              <label className="field" style={{ flex: 1 }}>
                <span className="field__label">{appText("Куда", "Ҡайҙа")}</span>
                <input
                  className="field__input"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                  placeholder={appText("Уфа", "Өфө")}
                  autoComplete="off"
                />
              </label>
            </div>

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

            {error && <div className="auth__error">{error}</div>}

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
