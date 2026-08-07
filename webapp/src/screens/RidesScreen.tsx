import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchRides, type Ride } from "../api/rides";
import ScreenHeader from "../components/ScreenHeader";
import RideCard from "../components/RideCard";
import { EmptyState, ErrorState, LoadingList } from "../components/States";
import { applyRideFilters, isFilterActive, loadFilters } from "../filterPrefs";
import { IconFilter, IconSearch } from "../components/Icons";
import { track } from "../analytics";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "ready"; rides: Ride[] };

/**
 * Первый живой экран — лента поездок.
 * Реальный публичный эндпоинт GET /rides (backend/app/routers/rides.py).
 * Все состояния: загрузка (скелетоны), пусто, ошибка + «Повторить».
 *
 * TODO(CORS): из dev-сервера (localhost) запрос к https://yulbash.ru может
 * упасть по CORS — бэкенду добавить origin `app.yulbash.ru` (и dev-origin)
 * в CORS_ORIGINS. В таком случае экран корректно покажет состояние ошибки.
 */
export default function RidesScreen() {
  const { t, appText } = useLang();
  const navigate = useNavigate();
  // Фильтры по умолчанию (локальные) — применяем к ленте клиентски.
  const prefs = useMemo(() => loadFilters(), []);
  const filterOn = isFilterActive(prefs);
  const [state, setState] = useState<State>({ kind: "loading" });

  const load = useCallback((signal?: AbortSignal) => {
    setState({ kind: "loading" });
    fetchRides(signal)
      .then((rides) => setState({ kind: "ready", rides }))
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setState({ kind: "error" });
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  // Просмотр ленты поездок — ключевой шаг воронки (нашёл, что ехать).
  useEffect(() => {
    track("view_rides", { filtered: filterOn });
  }, [filterOn]);

  return (
    <>
      <ScreenHeader title={t("ridesTitle")} subtitle={t("ridesSubtitle")} />

      <div className="chips">
        <button
          type="button"
          className={"chip" + (filterOn ? " chip--on" : "")}
          onClick={() => navigate("/filters")}
        >
          <IconFilter size={16} />{" "}
          {filterOn ? appText("Фильтры включены", "Фильтрҙар ҡабыҙылған") : appText("Фильтры", "Фильтрҙар")}
        </button>
      </div>

      {state.kind === "loading" && <LoadingList count={5} />}
      {state.kind === "error" && <ErrorState onRetry={() => load()} />}
      {state.kind === "ready" &&
        (() => {
          const rides = applyRideFilters(state.rides, prefs);
          if (rides.length === 0) {
            // Совсем пусто vs «фильтры всё скрыли» — разные подсказки.
            return filterOn && state.rides.length > 0 ? (
              <div className="state">
                <div className="state__icon"><IconSearch size={34} /></div>
                <h2>{appText("Ничего под фильтры", "Фильтргә тап килмәй")}</h2>
                <p>
                  {appText(
                    "Под твои фильтры сейчас нет поездок. Смягчи условия.",
                    "Фильтрҙарыңа тап килгән сәфәр юҡ. Шарттарҙы йомшарт."
                  )}
                </p>
                <button type="button" className="btn-primary" onClick={() => navigate("/filters")}>
                  {appText("Изменить фильтры", "Фильтрҙарҙы үҙгәртергә")}
                </button>
              </div>
            ) : (
              <EmptyState />
            );
          }
          return (
            <div>
              {rides.map((ride, i) => (
                <RideCard key={ride.id} ride={ride} index={i} />
              ))}
            </div>
          );
        })()}
    </>
  );
}
