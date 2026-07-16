// ================================================================
//  «Повтор маршрута» — быстрые/недавние маршруты пользователя.
//  Источник: GET /my-routes (из истории броней); фолбэк — недавние
//  точки GET /places/recent. Тап → создаём заявку POST /requests
//  и ведём к откликам. RequireAuth. Все состояния.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchMyRoutes, type PopularRoute } from "../api/discovery";
import { fetchRecentPlaces } from "../api/places";
import { createRequest } from "../api/requests";
import { LoadingList, ErrorState } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconArrow, IconRoute, IconClock } from "../components/Icons";

type Status = "loading" | "error" | "ready";
interface RouteRow {
  from_city: string;
  to_city: string;
  count?: number;
}

export default function RepeatTripScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [routes, setRoutes] = useState<RouteRow[]>([]);
  const [recentOnly, setRecentOnly] = useState(false);
  const [creating, setCreating] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    setError(null);
    fetchMyRoutes(signal)
      .then((rows: PopularRoute[]) => {
        if (rows.length > 0) {
          setRoutes(rows);
          setRecentOnly(false);
          setStatus("ready");
        } else {
          // Нет частых маршрутов → пробуем недавние точки как запасной вариант.
          return fetchRecentPlaces(signal).then((recent) => {
            setRoutes(
              recent
                .filter((r) => r.address)
                .map((r) => ({ from_city: r.address, to_city: "" }))
            );
            setRecentOnly(true);
            setStatus("ready");
          });
        }
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // /my-routes 404 (до деплоя) → мягко пробуем недавние.
        if (e instanceof ApiError && e.status === 404) {
          fetchRecentPlaces(signal)
            .then((recent) => {
              setRoutes(
                recent
                  .filter((r) => r.address)
                  .map((r) => ({ from_city: r.address, to_city: "" }))
              );
              setRecentOnly(true);
              setStatus("ready");
            })
            .catch((e2) => {
              if (e2 instanceof ApiError && e2.status === 404) {
                setRoutes([]);
                setRecentOnly(true);
                setStatus("ready");
              } else setStatus("error");
            });
        } else setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function repeat(r: RouteRow) {
    if (creating) return;
    const key = `${r.from_city}→${r.to_city}`;
    // Недавняя точка без пары городов — ведём в форму заявки (не гадаем маршрут).
    if (!r.to_city) {
      navigate("/request");
      return;
    }
    setCreating(key);
    setError(null);
    try {
      const row = await createRequest({
        from_city: r.from_city,
        to_city: r.to_city,
      });
      navigate(`/requests/${row.id}/responses`);
    } catch (e) {
      setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось создать заявку. Попробуй снова.", "Заявка яһап булманы. Ҡабат ҡара.")
      );
      setCreating(null);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Повтор маршрута", "Маршрутты ҡабатлау")}
        subtitle={appText("Поедешь как в прошлый раз — в один тап", "Үткәндәге кеүек — бер баҫыуҙа")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" &&
        (routes.length === 0 ? (
          <div className="state">
            <div className="state__emoji"><IconRoute size={34} /></div>
            <h2>{appText("Пока нет маршрутов", "Әле маршруттар юҡ")}</h2>
            <p>
              {appText(
                "Как только съездишь — маршрут появится здесь для быстрого повтора.",
                "Сәфәр ҡылғас — маршрут тиҙ ҡабатлау өсөн бында күренәсәк."
              )}
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate("/request")}>
              {appText("Создать заявку", "Заявка ҡалдыр")}
            </button>
          </div>
        ) : (
          <div className="list">
            {routes.map((r) => {
              const key = `${r.from_city}→${r.to_city}`;
              return (
                <button
                  key={key}
                  type="button"
                  className="list-row list-row--link"
                  onClick={() => repeat(r)}
                  disabled={creating != null}
                >
                  <span className="list-row__icon">
                    {recentOnly ? <IconClock size={22} /> : <IconRoute size={22} />}
                  </span>
                  <div className="list-row__main">
                    <div className="repeat-route">
                      <span>{r.from_city}</span>
                      {r.to_city && (
                        <>
                          <span className="repeat-route__arrow">
                            <IconArrow size={18} />
                          </span>
                          <span>{r.to_city}</span>
                        </>
                      )}
                    </div>
                    <div className="list-row__sub">
                      {creating === key
                        ? appText("Создаём заявку…", "Заявка яһайбыҙ…")
                        : r.count && r.count > 1
                        ? appText(`Ездил ${r.count} раз`, `${r.count} тапҡыр барҙың`)
                        : appText("Повторить поездку", "Сәфәрҙе ҡабатларға")}
                    </div>
                  </div>
                </button>
              );
            })}
            {error && <div className="auth__error">{error}</div>}
          </div>
        ))}
    </>
  );
}
