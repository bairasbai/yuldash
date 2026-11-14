import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, getAccountSession, isAccountSession } from "../api/client";
import {
  createRouteWatch, deleteRouteWatch, fetchRouteWatches, matchingRouteWatch, routeWatchPath,
  type RouteWatch, type WatchOwner, type WatchRoute,
} from "../api/routeWatch";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";

export default function WatchRouteButton({ route }: { route: WatchRoute }) {
  const { status } = useAuth();
  const { appText } = useLang();
  const navigate = useNavigate();
  const path = routeWatchPath(route);
  const owner = status === "authed" ? getAccountSession() : null;

  if (owner && route.from.trim() && route.to.trim()) {
    return <SignedInWatchButton key={JSON.stringify([owner.userId, owner.generation, path])} owner={owner} route={route} />;
  }

  return (
    <button type="button" className="btn-ghost" disabled={status !== "guest" && !owner}
      onClick={() => status === "guest" ? navigate("/login", { state: { from: path } }) : navigate(path)}>
      {appText("Следить за маршрутом", "Маршрутты күҙәтергә")}
    </button>
  );
}

function SignedInWatchButton({ owner, route }: { owner: WatchOwner; route: WatchRoute }) {
  const { appText } = useLang();
  const [phase, setPhase] = useState<"loading" | "load-error" | "ready">("loading");
  const [reload, setReload] = useState(0);
  const [watch, setWatch] = useState<RouteWatch>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const mutation = useRef(false);
  const { from, to, day } = route;
  const { generation, userId } = owner;

  useEffect(() => {
    const controller = new AbortController();
    const session = { generation, userId };
    fetchRouteWatches(session, controller.signal).then((rows) => {
      if (controller.signal.aborted || !isAccountSession(session)) return;
      setWatch(matchingRouteWatch(rows, { from, to, day }));
      setPhase("ready");
    }).catch(() => {
      if (!controller.signal.aborted && isAccountSession(session)) setPhase("load-error");
    });
    return () => controller.abort();
  }, [from, to, day, generation, userId, reload]);

  async function toggle() {
    if (mutation.current || !isAccountSession(owner)) return;
    mutation.current = true;
    setBusy(true);
    setError("");
    try {
      if (watch) {
        await deleteRouteWatch(owner, watch.id);
        if (isAccountSession(owner)) setWatch(undefined);
      } else {
        const created = await createRouteWatch(owner, {
          from_city: from.trim(), to_city: to.trim(), direction: "forward", watch_kind: "rides",
          watch_date: day ? `${day}T00:00:00` : null,
        });
        if (!matchingRouteWatch([created], route)) throw new Error("Unexpected route watch identity");
        if (isAccountSession(owner)) setWatch(created);
      }
    } catch (failure) {
      if (isAccountSession(owner)) {
        if (watch && failure instanceof ApiError && failure.status === 404) {
          setWatch(undefined);
          setPhase("loading");
          setReload((n) => n + 1);
        } else {
          setError(failure instanceof ApiError ? failure.message :
            appText("Не получилось изменить подписку. Попробуй ещё раз.", "Яҙылыуҙы үҙгәртеп булманы. Тағы бер тапҡыр ҡара."));
        }
      }
    } finally {
      mutation.current = false;
      if (isAccountSession(owner)) setBusy(false);
    }
  }

  return (
    <div>
      {phase === "loading" && <span role="status">{appText("Проверяем подписку…", "Яҙылыуҙы тикшерәбеҙ…")}</span>}
      {phase === "load-error" && (
        <>
          <span role="status">{appText("Не получилось проверить подписку.", "Яҙылыуҙы тикшереп булманы.")}</span>
          <button type="button" className="btn-ghost" onClick={() => { setPhase("loading"); setReload((n) => n + 1); }}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </>
      )}
      {phase === "ready" && (
        <>
          {watch && <span role="status">{appText("Следим", "Күҙәтәбеҙ")} · {watch.from_city} → {watch.to_city}</span>}
          <button type="button" className="btn-ghost" disabled={busy} onClick={toggle}>
            {busy ? appText("Сохраняем…", "Һаҡлайбыҙ…") : watch ? appText("Не следить", "Күҙәтмәҫкә") :
              appText("Следить за маршрутом", "Маршрутты күҙәтергә")}
          </button>
        </>
      )}
      {error && <p role="alert">{error}</p>}
    </div>
  );
}
