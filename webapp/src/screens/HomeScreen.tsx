// ================================================================
//  Home (вкладка «Карта») — витрина попуток. Публично (гость видит).
//  Карта Яндекс + пины ближайших заявок пассажиров (для вошедших),
//  карусель быстрых действий, фильтр «Ближайшие» (по геолокации),
//  список поездок рядом. Тап по поездке → шторка с бронированием.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import ScreenHeader from "../components/ScreenHeader";
import RideCard from "../components/RideCard";
import RideSheet from "../components/RideSheet";
import YandexMap, { type MapMarker, type GeoPoint } from "../components/YandexMap";
import { LoadingList, ErrorState } from "../components/States";
import { fetchRidesNear, fetchRequestsNear, type NearRequest } from "../api/discovery";
import type { Ride } from "../api/rides";
import { applyRideFilters, isFilterActive, loadFilters } from "../filterPrefs";
import { IconRequest, IconRides, IconShield, IconGift, IconFilter, IconPin, IconCar } from "../components/Icons";
import { YuModeTaxi } from "../components/BrandIcons";

type Status = "loading" | "error" | "ready";

export default function HomeScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const [status, setStatus] = useState<Status>("loading");
  const [rides, setRides] = useState<Ride[]>([]);
  const [reqs, setReqs] = useState<NearRequest[]>([]);
  const [me, setMe] = useState<GeoPoint | null>(null);
  const [nearOnly, setNearOnly] = useState(false);
  const [sheet, setSheet] = useState<Ride | null>(null);
  // Фильтры по умолчанию (локальные) — применяем к списку поездок рядом.
  const prefs = useMemo(() => loadFilters(), []);
  const filterOn = isFilterActive(prefs);
  const shownRides = useMemo(() => applyRideFilters(rides, prefs), [rides, prefs]);

  const load = useCallback(
    (signal?: AbortSignal, coords?: GeoPoint | null) => {
      setStatus("loading");
      const ridesP = fetchRidesNear(
        coords ? { lat: coords.lat, lng: coords.lng, radius_km: 200 } : {},
        signal
      );
      // Заявки рядом требуют вход — гостю не грузим (мягко).
      const reqsP = isAuthed
        ? fetchRequestsNear(
            coords ? { lat: coords.lat, lng: coords.lng, radius_km: 200 } : {},
            signal
          ).catch(() => ({ count: 0, items: [] as NearRequest[] }))
        : Promise.resolve({ count: 0, items: [] as NearRequest[] });

      Promise.all([ridesP, reqsP])
        .then(([r, q]) => {
          setRides(r.items);
          setReqs(q.items);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [isAuthed]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal, null);
    return () => ac.abort();
  }, [load]);

  // Фильтр «Ближайшие» — просим геолокацию и перегружаем по координатам.
  function toggleNear() {
    if (nearOnly) {
      setNearOnly(false);
      setMe(null);
      load(undefined, null);
      return;
    }
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (p) => {
        const c = { lat: p.coords.latitude, lng: p.coords.longitude };
        setMe(c);
        setNearOnly(true);
        load(undefined, c);
      },
      () => {
        // Отказ в гео — не падаем, просто оставляем общий список.
        setNearOnly(false);
      },
      { timeout: 8000, maximumAge: 60000 }
    );
  }

  const markers = useMemo<MapMarker[]>(() => {
    const list: MapMarker[] = [];
    reqs.forEach((q) => {
      if (q.from_lat != null && q.from_lng != null)
        list.push({
          id: `q${q.id}`,
          lat: q.from_lat,
          lng: q.from_lng,
          kind: "regular",
          label: `${q.from_city} → ${q.to_city}`,
        });
    });
    return list;
  }, [reqs]);

  const quick = [
    {
      key: "taxi",
      icon: <YuModeTaxi size={22} />,
      title: appText("Вызвать такси", "Такси саҡырыу"),
      onClick: () => navigate("/taxi"),
    },
    {
      key: "create",
      icon: <IconRequest size={22} />,
      title: appText("Создать заявку", "Заявка ҡалдыр"),
      onClick: () => navigate("/request"),
    },
    {
      key: "feed",
      icon: <IconRides size={22} />,
      title: appText("Заявки рядом", "Яҡындағы заявкалар"),
      onClick: () => navigate("/requests-feed"),
    },
    {
      key: "trust",
      icon: <IconShield size={22} />,
      title: appText("Доверие", "Ышаныс"),
      onClick: () => navigate("/trust"),
    },
    {
      key: "invite",
      icon: <IconGift size={22} />,
      title: appText("Позови своих", "Үҙеңдекеләрҙе саҡыр"),
      onClick: () => navigate("/invites"),
    },
  ];

  return (
    <>
      <ScreenHeader
        title={appText("Карта", "Карта")}
        subtitle={appText("Попутки между своими рядом", "Яҡында үҙебеҙ араһында юлдаштар")}
      />

      <div className="home-map">
        <YandexMap markers={markers} me={me} height={280} />
      </div>

      <div className="chips">
        <button
          type="button"
          className={"chip" + (nearOnly ? " chip--on" : "")}
          onClick={toggleNear}
        >
          <IconPin size={16} /> {appText("Ближайшие", "Иң яҡындар")}
        </button>
        <button
          type="button"
          className={"chip" + (filterOn ? " chip--on" : "")}
          onClick={() => navigate("/filters")}
        >
          <IconFilter size={16} /> {appText("Фильтры", "Фильтрҙар")}
        </button>
      </div>

      <div className="quick-row" role="list">
        {quick.map((q) => (
          <button
            key={q.key}
            type="button"
            className="quick-tile"
            role="listitem"
            onClick={q.onClick}
          >
            <span className="quick-tile__icon">{q.icon}</span>
            <span className="quick-tile__title">{q.title}</span>
          </button>
        ))}
      </div>

      <h2 className="section-title">
        {appText("Поездки рядом", "Яҡындағы сәфәрҙәр")}
      </h2>

      {status === "loading" && <LoadingList count={3} />}
      {status === "error" && <ErrorState onRetry={() => load(undefined, me)} />}
      {status === "ready" &&
        (shownRides.length === 0 ? (
          <div className="state">
            <div className="state__icon"><IconCar size={34} /></div>
            <h2>
              {filterOn && rides.length > 0
                ? appText("Ничего под фильтры", "Фильтргә тап килмәй")
                : appText("Пока никто не едет рядом", "Яҡында әле бер кем бармай")}
            </h2>
            <p>
              {filterOn && rides.length > 0
                ? appText(
                    "Под твои фильтры сейчас нет поездок. Смягчи условия.",
                    "Фильтрҙарыңа тап килгән сәфәр юҡ. Шарттарҙы йомшарт."
                  )
                : appText(
                    "Оставь заявку — водители увидят её и откликнутся.",
                    "Заявка ҡалдыр — водителдәр күреп яуап бирер."
                  )}
            </p>
            <button
              type="button"
              className="btn-primary"
              onClick={() => navigate(filterOn && rides.length > 0 ? "/filters" : "/request")}
            >
              {filterOn && rides.length > 0
                ? appText("Изменить фильтры", "Фильтрҙарҙы үҙгәртергә")
                : appText("Создать заявку", "Заявка ҡалдыр")}
            </button>
          </div>
        ) : (
          <div>
            {shownRides.map((ride, i) => (
              <button
                key={ride.id}
                type="button"
                className="ride-card-btn"
                onClick={() => setSheet(ride)}
              >
                <RideCard ride={ride} index={i} />
              </button>
            ))}
          </div>
        ))}

      {sheet && <RideSheet ride={sheet} onClose={() => setSheet(null)} />}
    </>
  );
}
