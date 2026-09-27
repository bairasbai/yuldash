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
import ModeSwitch from "../components/ModeSwitch";
import { fetchSeasonalEvents, type SeasonalEvent } from "../api/seasonal";
import RideSheet from "../components/RideSheet";
import YandexMap, { type MapMarker, type GeoPoint } from "../components/YandexMap";
import NearbyRideCard, { NearbySkeletonCard, NearbyMoreCard, NearbyEmptyCard } from "../components/NearbyRideCard";
import { NearbyChip } from "../components/adminUi";
import CommunityFeedStrip from "../components/CommunityFeedStrip";
import { fetchRidesNear, fetchRequestsNear, type NearRequest } from "../api/discovery";
import type { Ride } from "../api/rides";
import { applyRideFilters, clearFilters, loadFilters, saveFilters, type Amenity, type FilterPrefs } from "../filterPrefs";
import { captureOwner } from "../utils/ownedStorage";
import { IconRides, IconGift, IconPin, IconCalendar, IconClock, IconClose, IconHospital, IconChevron } from "../components/Icons";
import { YuModeTaxi, YuWomenOnly, YuChildSeat, YuPet, YuLuggage, YuAc, YuSmokeFree, YuQuiet } from "../components/BrandIcons";
import { PartnerAdSlot } from "../components/PartnerAd";
import { fetchPopularRoutes, type PopularRoute } from "../api/geo";
import { useVisibleInterval } from "../utils/useVisibleInterval";

type Status = "loading" | "error" | "ready";

/**
 * Сколько поездок показываем сразу. Остальное — по кнопке «Показать ещё».
 *
 * На сельском интернете длинный список это лишние секунды и лишний трафик,
 * а дальше третьего экрана всё равно почти никто не листает.
 */
const PAGE = 30;

export default function HomeScreen() {
  const [personalOwner] = useState(captureOwner);
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const { user } = useAuth();

  /**
   * Приветствие по времени суток. Мелочь, но именно с неё начинается разговор:
   * «Карта» — это про интерфейс, «Доброе утро, Азамат» — про человека.
   * Гостю имени нет — здороваемся без него, а не с пустым местом.
   */
  const greeting = useMemo(() => {
    const h = new Date().getHours();
    const name = (user?.name ?? "").trim().split(/\s+/)[0];
    const suffix = name ? `, ${name}` : "";
    if (h >= 5 && h < 12) return appText(`Доброе утро${suffix}`, `Хәйерле иртә${suffix}`);
    if (h >= 12 && h < 18) return appText(`Добрый день${suffix}`, `Хәйерле көн${suffix}`);
    if (h >= 18 && h < 23) return appText(`Добрый вечер${suffix}`, `Хәйерле кис${suffix}`);
    return appText(`Доброй ночи${suffix}`, `Тыныс төн${suffix}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.name, lang]);

  const [status, setStatus] = useState<Status>("loading");
  // Ближайшее сезонное событие (публичная ручка). null = ничего не идёт или ручки нет.
  const [season, setSeason] = useState<SeasonalEvent | null>(null);
  // Пресеты популярных маршрутов — чипы «Сибай → Уфа» в один тап.
  const [popular, setPopular] = useState<PopularRoute[]>([]);
  const [rides, setRides] = useState<Ride[]>([]);
  const [reqs, setReqs] = useState<NearRequest[]>([]);
  const [me, setMe] = useState<GeoPoint | null>(null);
  /** Место не дали — говорим об этом словами, иначе кнопка выглядит сломанной. */
  const [geoNote, setGeoNote] = useState("");
  const [nearOnly, setNearOnly] = useState(false);
  /**
   * «Когда едем»: пусто — все дни, иначе конкретный день (YYYY-MM-DD).
   *
   * Человек ищет не «когда-нибудь», а завтра утром. Без этого выбора список мешал
   * сегодняшние поездки с теми, что через неделю, и нужную приходилось искать глазами.
   */
  const [day, setDay] = useState<string | null>(null);
  /** Сколько поездок уже показали. Растёт кнопкой «Показать ещё». */
  const [limit, setLimit] = useState(PAGE);
  const [sheet, setSheet] = useState<Ride | null>(null);
  // Фильтры (локальные) — применяем к списку поездок рядом. Чипы переключают их на месте,
  // а экран «Фильтры» видит те же значения: храним в одном месте (localStorage).
  const [prefs, setPrefs] = useState<FilterPrefs>(() => loadFilters());
  const shownRides = useMemo(() => applyRideFilters(rides, prefs), [rides, prefs]);
  /** Сколько условий включено — для чипа «Сбросить · N». */
  const filterCount = prefs.amenities.length + (prefs.city.trim() ? 1 : 0) + (prefs.maxPrice != null ? 1 : 0) + (prefs.onlyTrusted ? 1 : 0);
  function toggleAmenity(a: Amenity) {
    setPrefs((cur) => {
      const next = { ...cur, amenities: cur.amenities.includes(a) ? cur.amenities.filter((x) => x !== a) : [...cur.amenities, a] };
      saveFilters(next, personalOwner);
      return next;
    });
  }
  function resetFilters() {
    clearFilters(personalOwner);
    setPrefs(loadFilters());
  }

  /**
   * День в местной зоне, сдвиг в днях: 0 — сегодня, 1 — завтра.
   *
   * Считаем ЛОКАЛЬНО, а не через UTC: в Уфе разница пять часов, и после семи вечера
   * «сегодня» по UTC — это уже завтра. Человек, ищущий вечернюю поездку, не должен
   * получать пустой список из-за часового пояса сервера.
   */
  function localDay(shift: number): string {
    const d = new Date();
    d.setDate(d.getDate() + shift);
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    return `${d.getFullYear()}-${m}-${day}`;
  }

  /** Тот же день второй раз — снимаем фильтр. Отдельная кнопка «все дни» была бы лишней. */
  function pickDay(value: string) {
    setLimit(PAGE); // сменили день — список начинается заново
    setDay((prev) => (prev === value ? null : value));
  }

  const load = useCallback(
    (signal?: AbortSignal, coords?: GeoPoint | null, quiet = false) => {
      // Тихое обновление — для авто-перезагрузки: карта не должна моргать
      // скелетоном каждые полминуты у человека на глазах.
      if (!quiet) setStatus("loading");
      const ridesP = fetchRidesNear(
        {
          ...(coords ? { lat: coords.lat, lng: coords.lng, radius_km: 200 } : {}),
          ...(day ? { date: day } : {}),
          limit,
        },
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
          // Тихая перезагрузка не должна стирать уже показанную карту: сеть моргнула —
          // человек продолжает видеть поездки, а не экран ошибки.
          if (quiet) return;
          setStatus("error");
        });
    },
    [isAuthed, day, limit]
  );

  useEffect(() => {
    const ac = new AbortController();
    fetchSeasonalEvents(21, ac.signal)
      .then((r) => {
        const active = r.items.find((e) => e.active) ?? r.items[0] ?? null;
        setSeason(active);
      })
      .catch(() => setSeason(null)); // 404 / нет сети → баннера просто нет
    fetchPopularRoutes(ac.signal)
      .then((r) => setPopular(r.slice(0, 6)))
      .catch(() => setPopular([])); // нет справочника → чипов просто нет
    return () => ac.abort();
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal, null);
    return () => ac.abort();
  }, [load]);

  // Карта — живой экран: поездки появляются и уезжают, пока человек смотрит.
  // Раньше сайт показывал снимок на момент открытия: уехавшие висели, новые не
  // приходили. Приложение обновляет пины раз в ~25 секунд и молчит в фоне.
  useVisibleInterval(25000, () => {
    if (status !== "loading") load(undefined, me, true);
  });

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
        setGeoNote("");
        load(undefined, c);
      },
      () => {
        // Отказ в гео — не падаем и НЕ молчим: человек нажал кнопку, она не
        // включилась, и без объяснения это выглядит поломкой, а не запретом.
        setNearOnly(false);
        setGeoNote(
          appText(
            "Не видим твоё место. Разреши доступ к геолокации в настройках браузера — покажем поездки рядом.",
            "Урыныңды күрмәйбеҙ. Браузер көйләүҙәрендә геолокацияға рөхсәт бир — яҡындағы сәфәрҙәрҙе күрһәтербеҙ."
          )
        );
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


  return (
    <>
      {/* Как в Android: ModeSwitchBar над всем, затем HomeHeader — приветствие мелким и «Куда поедем?». */}
      <ModeSwitch
        mode="pooling"
        onSelect={(m) => {
          if (m === "taxi") navigate("/taxi");
          else if (m === "courier") navigate("/parcels");
        }}
        onExplain={() => {
          const hint = document.querySelector<HTMLDetailsElement>(".modes-hint");
          if (hint) {
            hint.open = true;
            hint.scrollIntoView({ behavior: "smooth", block: "nearest" });
          }
        }}
      />
      <ScreenHeader eyebrow={greeting} title={appText("Куда поедем?", "Ҡайҙа барабыҙ?")} />

      {/* Сезон: сабантуй, курбан, начало учёбы — когда все едут в одну сторону.
          Ничего не навязываем: подсказка «сегодня будет много попутчиков». */}
      {season && (
        <div className="act-card act-card--mint">
          <div className="act-card__title">
            <span aria-hidden>{season.emoji}</span> {ru ? season.name_ru : season.name_ba}
          </div>
          <p className="act-card__text" style={{ marginBottom: 0 }}>
            {ru ? season.note_ru : season.note_ba}
          </p>
        </div>
      )}

      <div className="home-map">
        <YandexMap markers={markers} me={me} height={280} />
      </div>

      {/* NearbyFilterChip-ряд как в приложении: «когда едем» | условия. Видно, пока есть что
          фильтровать или включён хоть один фильтр — иначе снять его было бы нечем. */}
      {(rides.length > 0 || day != null || filterCount > 0 || nearOnly) && (
        <div className="nearby-filters">
          <div className="nearby-filters__row">
            <NearbyChip icon={<IconCalendar size={15} />} label={appText("Все дни", "Бөтә көндәр")} active={day == null} onClick={() => { setLimit(PAGE); setDay(null); }} />
            <NearbyChip icon={<IconClock size={15} />} label={appText("Сегодня", "Бөгөн")} active={day === localDay(0)} onClick={() => pickDay(localDay(0))} />
            <NearbyChip icon={<IconClock size={15} />} label={appText("Завтра", "Иртәгә")} active={day === localDay(1)} onClick={() => pickDay(localDay(1))} />
            <span className="nearby-filters__div" aria-hidden />
            {filterCount > 0 && (
              <NearbyChip icon={<IconClose size={15} />} label={appText(`Сбросить · ${filterCount}`, `Бушатырға · ${filterCount}`)} active={false} onClick={resetFilters} />
            )}
            <NearbyChip icon={<YuWomenOnly size={15} />} label={appText("Только женщины", "Тик ҡатын-ҡыҙ")} active={prefs.amenities.includes("women_only")} onClick={() => toggleAmenity("women_only")} />
            <NearbyChip icon={<YuChildSeat size={15} />} label={appText("Детское кресло", "Балалар ултырғысы")} active={prefs.amenities.includes("child_seat")} onClick={() => toggleAmenity("child_seat")} />
            <NearbyChip icon={<YuPet size={15} />} label={appText("С животным", "Хайуан менән")} active={prefs.amenities.includes("pets")} onClick={() => toggleAmenity("pets")} />
            <NearbyChip icon={<YuLuggage size={15} />} label={appText("Багаж", "Йөк")} active={prefs.amenities.includes("baggage")} onClick={() => toggleAmenity("baggage")} />
            <NearbyChip icon={<YuAc size={15} />} label={appText("Кондиционер", "Кондиционер")} active={prefs.amenities.includes("air_conditioner")} onClick={() => toggleAmenity("air_conditioner")} />
            <NearbyChip icon={<YuSmokeFree size={15} />} label={appText("Некурящий", "Тартмаусы")} active={prefs.amenities.includes("non_smoking")} onClick={() => toggleAmenity("non_smoking")} />
            <NearbyChip icon={<YuQuiet size={15} />} label={appText("Тихая поездка", "Тыныс сәфәр")} active={prefs.amenities.includes("quiet")} onClick={() => toggleAmenity("quiet")} />
            {/* Только в вебе: браузер не знает место без разрешения — чип просит его и сужает выдачу. */}
            <span className="nearby-filters__div" aria-hidden />
            <NearbyChip icon={<IconPin size={15} />} label={appText("Ближайшие", "Иң яҡындар")} active={nearOnly} onClick={toggleNear} />
          </div>
          {prefs.amenities.includes("women_only") && (
            <p className="nearby-filters__hint">
              <YuWomenOnly size={16} />
              {appText("Женщины за рулём и поездки «только для женщин».", "Рулдә ҡатын-ҡыҙҙар һәм «тик ҡатын-ҡыҙ өсөн» сәфәрҙәр.")}
            </p>
          )}
        </div>
      )}

      {geoNote && (
        <div className="notice" role="status">
          {geoNote}
        </div>
      )}

      {/* «Такси, попутка, курьер» — три слова, за которыми три разные цены
          и три разных ожидания. Человек, который путает их, платит не за то,
          что думал, поэтому объяснение лежит прямо под кнопками. */}
      <details className="modes-hint">
        <summary>{appText("Чем отличается?", "Айырмаһы нимәлә?")}</summary>
        <div className="modes-hint__body">
          <div className="modes-hint__row">
            <span className="modes-hint__ic modes-hint__ic--taxi" aria-hidden>
              <YuModeTaxi size={18} />
            </span>
            <div>
              <b>{appText("Такси", "Такси")}</b>
              <p>
                {appText(
                  "Быстро. Машина едет прямо за тобой. Чуть дороже.",
                  "Тиҙ. Машина тап һинең артыңдан килә. Бер аҙ ҡиммәтерәк."
                )}
              </p>
            </div>
          </div>
          <div className="modes-hint__row">
            <span className="modes-hint__ic modes-hint__ic--pool" aria-hidden>
              <IconRides size={18} />
            </span>
            <div>
              <b>{appText("Попутка", "Юлдаш")}</b>
              <p>
                {appText(
                  "Дешевле. Подсаживаешься к тому, кто и так едет туда.",
                  "Арзаныраҡ. Барыбер шунда барған кешегә ултыраһың."
                )}
              </p>
            </div>
          </div>
          <div className="modes-hint__row">
            <span className="modes-hint__ic modes-hint__ic--courier" aria-hidden>
              <IconGift size={18} />
            </span>
            <div>
              <b>{appText("Курьер", "Курьер")}</b>
              <p>
                {appText(
                  "Едешь не ты, а посылка. Отвезёт тот, кто и так в пути.",
                  "Һин түгел, бандеролең бара. Юлда булған кеше илтә."
                )}
              </p>
            </div>
          </div>
        </div>
      </details>

      {/* Куда чаще всего ездят. Один тап вместо набора двух названий руками —
          и написание сразу совпадает со справочником. */}
      {popular.length > 0 && (
        <div className="chips" style={{ marginTop: 4 }}>
          {popular.map((r) => {
            const a = ru ? r.from.name_ru : r.from.name_ba || r.from.name_ru;
            const b = ru ? r.to.name_ru : r.to.name_ba || r.to.name_ru;
            return (
              <button
                key={`${r.from.id}-${r.to.id}`}
                type="button"
                className="chip"
                onClick={() => navigate(`/rides/feed?from=${encodeURIComponent(a)}&to=${encodeURIComponent(b)}`)}
              >
                {a} → {b}
              </button>
            );
          })}
        </div>
      )}

      {/* Живая лента: сколько ездят на самом деле. В райцентре машин на карте может
          не быть прямо сейчас — и человек решает, что сервисом никто не пользуется.
          Числа настоящие, с сервера; пусто — полосы просто нет. */}
      <CommunityFeedStrip />

      <div className="nearby-header">
        <h2 className="section-title">{appText("Ближайшие поездки", "Яҡындағы сәфәрҙәр")}</h2>
        {rides.length > 0 && (
          <span className="nearby-header__count">{appText(`${shownRides.length} рядом`, `${shownRides.length} яҡында`)}</span>
        )}
      </div>

      {/* Состояния ленты: скелетоны → пусто/нет сети карточкой → фильтры всё срезали → карусель. */}
      {status === "loading" && rides.length === 0 && (
        <div className="nearby-row" aria-hidden>
          <NearbySkeletonCard />
          <NearbySkeletonCard />
        </div>
      )}
      {status === "error" && rides.length === 0 && <NearbyEmptyCard error onRetry={() => load(undefined, me)} />}
      {status === "ready" && rides.length === 0 && <NearbyEmptyCard error={false} onRetry={() => load(undefined, me)} />}
      {rides.length > 0 && shownRides.length === 0 && (
        <div className="nearby-filtered">
          <p>{appText("Нет поездок с такими условиями. Сними часть фильтров.", "Был шарттар менән сәфәр юҡ. Фильтрҙың бер өлөшөн ал.")}</p>
          {filterCount > 0 && (
            <button type="button" className="nearby-filtered__reset" onClick={resetFilters}>
              <IconClose size={20} /> {appText("Сбросить фильтры", "Фильтрҙы бушат")}
            </button>
          )}
        </div>
      )}
      {shownRides.length > 0 && (
        <div className="nearby-row">
          {shownRides.map((ride, i) => (
            <NearbyRideCard key={`${ride.id}#${i}`} ride={ride} soonest={i === 0} onOpen={() => setSheet(ride)} />
          ))}
          {/* «Показать ещё» — когда сервер отдал полную страницу: значит дальше ещё есть. */}
          {filterCount === 0 && rides.length >= limit && <NearbyMoreCard loading={status === "loading"} onMore={() => setLimit((n) => n + PAGE)} />}
        </div>
      )}

      {/* ClinicRidesEntryCard: кто-то уже едет к больнице — подсядь по пути. */}
      <button type="button" className="clinic-entry" onClick={() => navigate("/clinics")}>
        <span className="clinic-entry__icon" aria-hidden><IconHospital size={24} /></span>
        <span className="clinic-entry__text">
          <strong>{appText("Поездки к клинике", "Клиникаға сәфәрҙәр")}</strong>
          <small>{appText("Кто-то уже едет к больнице — подсядь по пути", "Кемдер клиникаға бара — юлда ҡушыл")}</small>
        </span>
        <span className="clinic-entry__chev" aria-hidden><IconChevron size={22} /></span>
      </button>

      {/* Партнёр рядом — тариф «Город». Город берём тот, где человек ищет поездку. */}
      <PartnerAdSlot placement="nearby" city={shownRides[0]?.from_city} />

      {sheet && <RideSheet ride={sheet} onClose={() => setSheet(null)} />}
    </>
  );
}
