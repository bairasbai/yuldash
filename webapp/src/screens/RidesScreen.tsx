import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchRides, type Ride } from "../api/rides";
import ScreenHeader from "../components/ScreenHeader";
import RideCard from "../components/RideCard";
import { EmptyState, ErrorState, LoadingList } from "../components/States";
import { applyRideFilters, isFilterActive, loadFilters } from "../filterPrefs";
import { IconFilter, IconSearch } from "../components/Icons";
import { track } from "../analytics";
import InviteDriverCallout from "../components/InviteDriverCallout";
import PartnerAdCard, { usePartnerAds } from "../components/PartnerAd";
import { useScrollMemory } from "../utils/useScrollMemory";

type State =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "ready"; rides: Ride[] };

/** По сколько карточек добавляем за раз («Показать ещё»). */
const PAGE = 30;

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
  /** Сколько карточек показано сейчас — растёт по кнопке «Показать ещё». */
  const [shown, setShown] = useState(PAGE);

  // Пролистал ленту, открыл поездку, вернулся — список должен остаться там же,
  // а не отматываться в начало.
  useScrollMemory("rides", state.kind === "ready");

  // Реклама партнёра в ленте. Маршрутная (тариф «Маршрут») важнее общей:
  // за неё платят дороже, и она ближе к тому, куда человек едет.
  const routeAds = usePartnerAds("route");
  const listAds = usePartnerAds("ridesList");
  const inlineAd = routeAds[0] ?? listAds[0] ?? null;
  const adLabel = routeAds[0]
    ? appText("Партнёр по маршруту", "Маршрут партнёры")
    : appText("Совет партнёра", "Партнёр кәңәше");

  // Маршрут из адреса (?from=&to=) — по нему пришли с чипа популярного маршрута.
  const [params, setParams] = useSearchParams();
  const qFrom = params.get("from") ?? "";
  const qTo = params.get("to") ?? "";
  const routeOn = Boolean(qFrom || qTo);

  const load = useCallback(
    (signal?: AbortSignal) => {
      setState({ kind: "loading" });
      // Фильтр по маршруту считает сервер: тянуть всю ленту ради двух городов
      // — лишний трафик на телефоне в селе.
      fetchRides(signal, routeOn ? { from_city: qFrom || undefined, to_city: qTo || undefined } : undefined)
        .then((rides) => setState({ kind: "ready", rides }))
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setState({ kind: "error" });
        });
    },
    [qFrom, qTo, routeOn]
  );

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
        {/* Пришли с чипа маршрута — видно, что лента сужена, и можно снять одним тапом */}
        {routeOn && (
          <button type="button" className="chip chip--on" onClick={() => setParams({})}>
            {qFrom || "…"} → {qTo || "…"} ✕
          </button>
        )}
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
              // Пусто по-настоящему — тупик. Единственное, что человек может
              // сделать сейчас: позвать за руль знакомого.
              <>
                <EmptyState />
                <InviteDriverCallout />
              </>
            );
          }
          // Сервер отдаёт до 200 поездок за раз. Рисовать все сразу — значит
          // подвесить дешёвый телефон на первом же открытии ленты: 200 карточек
          // в разметке он строит секундами. Показываем частями.
          const visible = rides.slice(0, shown);
          return (
            <div>
              {visible.map((ride, i) => (
                <div key={ride.id}>
                  <RideCard ride={ride} index={i} to={`/rides/${ride.id}`} />
                  {/* Реклама после третьей карточки: видно, но не в лицо с первого экрана */}
                  {inlineAd && i === 2 && <PartnerAdCard ad={inlineAd} label={adLabel} />}
                </div>
              ))}
              {/* Список короче трёх — рекламу показываем в конце, иначе партнёр не получит показ */}
              {inlineAd && rides.length <= 2 && <PartnerAdCard ad={inlineAd} label={adLabel} />}

              {rides.length > visible.length && (
                <button
                  type="button"
                  className="btn-soft show-more"
                  onClick={() => setShown((n) => n + PAGE)}
                >
                  {appText(
                    `Показать ещё · осталось ${rides.length - visible.length}`,
                    `Тағы күрһәтергә · ${rides.length - visible.length} ҡалды`
                  )}
                </button>
              )}
            </div>
          );
        })()}
    </>
  );
}
