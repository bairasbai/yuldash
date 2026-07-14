import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { fetchRides, type Ride } from "../api/rides";
import ScreenHeader from "../components/ScreenHeader";
import RideCard from "../components/RideCard";
import { EmptyState, ErrorState, LoadingList } from "../components/States";

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
  const { t } = useLang();
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

  return (
    <>
      <ScreenHeader title={t("ridesTitle")} subtitle={t("ridesSubtitle")} />

      {state.kind === "loading" && <LoadingList count={5} />}
      {state.kind === "error" && <ErrorState onRetry={() => load()} />}
      {state.kind === "ready" &&
        (state.rides.length === 0 ? (
          <EmptyState />
        ) : (
          <div>
            {state.rides.map((ride, i) => (
              <RideCard key={ride.id} ride={ride} index={i} />
            ))}
          </div>
        ))}
    </>
  );
}
