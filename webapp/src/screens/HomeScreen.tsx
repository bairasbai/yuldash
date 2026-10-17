// ================================================================
//  Home (вкладка «Карта») — витрина попуток. Публично (гость видит).
//  Карта Яндекс + пины ближайших заявок пассажиров (для вошедших),
//  ряд фильтров «Откуда · Куда · Когда · Фильтры», лента «Ближайшие поездки».
//  Тап по поездке → шторка с бронированием.
//
//  Лента — зеркало MapScreen.kt: условия, города и день режет СЕРВЕР (до подсчёта `count`),
//  «Показать ещё» держится на `count`, а браузерный фильтр остался только подстраховкой
//  на старый сервер, который про условия не знает.
// ================================================================
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import ScreenHeader from "../components/ScreenHeader";
import ModeSwitch from "../components/ModeSwitch";
import { fetchSeasonalEvents, type SeasonalEvent } from "../api/seasonal";
import RideSheet from "../components/RideSheet";
import YandexMap, { type MapMarker, type GeoPoint } from "../components/YandexMap";
import NearbyRideCard, { NearbySkeletonCard, NearbyMoreCard, NearbyEmptyCard, NearbyUnverifiedCard } from "../components/NearbyRideCard";
import { NearbyChip } from "../components/adminUi";
import { NearbyCityChips, NearbyConditionsChip, NearbyWhenChip } from "../components/NearbyFilters";
import CommunityFeedStrip from "../components/CommunityFeedStrip";
import { fetchRidesNear, fetchRequestsNear, type NearRequest, type NearRide } from "../api/discovery";
import type { Ride } from "../api/rides";
import { loadFilters } from "../filterPrefs";
import {
  canShowMore,
  filtersRowVisible,
  matchesNearbyConditions,
  NEARBY_MAX,
  NEARBY_PAGE,
  NEARBY_RADIUS_KM,
  nearbyContextKey,
  nearbyFailure,
  nearbyFeedState,
  nearbyQuery,
  nearbyVerifiedOnly,
  type NearbyCondition,
  type NearbyFilters,
} from "../nearbyFeed";
import { useNearbyCityFilter } from "../nearbyCityFilter";
import { IconRides, IconGift, IconLocate, IconClose, IconHospital, IconChevron } from "../components/Icons";
import { YuModeTaxi } from "../components/BrandIcons";
import { PartnerAdSlot } from "../components/PartnerAd";
import { fetchPopularRoutes, type PopularRoute } from "../api/geo";
import { useVisibleInterval } from "../utils/useVisibleInterval";
import ComingSoon from "../components/ComingSoon";
import { LoadingList } from "../components/States";
import { useFeatures } from "../features";
import { isOpen } from "../featureRules";

type Status = "loading" | "error" | "ready";

export default function HomeScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed } = useAuth();

  const { user } = useAuth();

  // Запуск волнами: пока доставка выключена, вкладка «Курьер» остаётся на главной и говорит
  // «скоро» на месте, а не ведёт в экран посылок, где всё упирается в отказ сервера.
  const features = useFeatures();
  const deliveryOpen = isOpen("delivery", features);
  const [deliveryTab, setDeliveryTab] = useState(false);
  useEffect(() => {
    // Доставку включили, пока человек смотрел на «скоро», — пускаем его туда, куда он шёл.
    if (deliveryTab && deliveryOpen) {
      setDeliveryTab(false);
      navigate("/parcels");
    }
  }, [deliveryTab, deliveryOpen, navigate]);

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
  const [rides, setRides] = useState<NearRide[]>([]);
  /** Сколько поездок подходит всего (`count` сервера, с учётом условий) — для «Показать ещё» и кнопки шторки. */
  const [total, setTotal] = useState(0);
  const [reqs, setReqs] = useState<NearRequest[]>([]);
  const [me, setMe] = useState<GeoPoint | null>(null);
  /** Место не дали — говорим об этом словами, иначе кнопка выглядит сломанной. */
  const [geoNote, setGeoNote] = useState("");
  const nearOnly = me != null;
  /**
   * «Когда едем»: пусто — все дни, иначе конкретный день (YYYY-MM-DD).
   *
   * Человек ищет не «когда-нибудь», а завтра утром. Без этого выбора список мешал
   * сегодняшние поездки с теми, что через неделю, и нужную приходилось искать глазами.
   */
  const [day, setDay] = useState<string | null>(null);
  const [sheet, setSheet] = useState<Ride | null>(null);
  /**
   * Условия ленты. Стартуют из «Фильтров по умолчанию» и дальше живут на экране — как в приложении
   * (`prefFilter` в MapScreen.kt): поменял на карте — это только для этого поиска, а то, что
   * «по умолчанию», меняют на экране «Фильтры».
   */
  const [conditions, setConditions] = useState<NearbyCondition[]>(() => loadFilters().conditions);
  /**
   * «Проверенные водители на карте» из раздела «Безопасность»: здесь её не переключают, только читают.
   * С 17.10.2026 включена по умолчанию у всех, кто не выбирал (решение Александра), — значит, и у гостя:
   * он тоже не выбирал. Тупика нет: пустая лента проверенных предлагает «Показать» поездки непроверенных
   * водителей для этого поиска, а войдёт — переключатель в «Безопасности» под рукой.
   */
  const [storedVerifiedOnly] = useState(() => loadFilters().onlyTrusted);
  /** Для какого поиска нажали «Показать» под «Есть N поездок непроверенных водителей»; null — не нажимали. */
  const [revealedFor, setRevealedFor] = useState<string | null>(null);
  /** Сколько поездок этого поиска спрятал «только проверенные» (`hidden_unverified` сервера). */
  const [hiddenUnverified, setHiddenUnverified] = useState(0);
  /** Растёт на каждое обновление ленты: по нему же проверяется список городов (не чаще раза в 2 минуты). */
  const [reloadTick, setReloadTick] = useState(0);
  /** «Откуда» и «Куда»: выбранные города и из чего выбирать. */
  const cityFilter = useNearbyCityFilter(reloadTick);

  /** Поиск с сохранённой настройкой: по его ключу «Показать» действует только здесь (`nearbyVerifiedOnly`). */
  const savedFilters = useMemo<NearbyFilters>(
    () => ({ coords: me, day, conditions, verifiedOnly: storedVerifiedOnly, fromCities: cityFilter.from, toCities: cityFilter.to }),
    [me, day, conditions, storedVerifiedOnly, cityFilter.from, cityFilter.to]
  );
  const savedKey = nearbyContextKey(savedFilters);
  const verifiedOnly = nearbyVerifiedOnly(storedVerifiedOnly, revealedFor, savedKey);
  const filters = useMemo<NearbyFilters>(() => ({ ...savedFilters, verifiedOnly }), [savedFilters, verifiedOnly]);
  /**
   * Сколько поездок просим. Растёт кнопкой «Показать ещё» и сам возвращается к первой порции, когда
   * поменялся день, место, условие или город: лимит помнит, для какого поиска он набран.
   */
  const contextKey = nearbyContextKey(filters);
  const [pageState, setPageState] = useState({ key: contextKey, limit: NEARBY_PAGE });
  // Другой поиск — снова первая порция. Меняем прямо при отрисовке (а не в эффекте), чтобы лента
  // не просила сервер дважды: сначала со старым лимитом, потом с новым.
  if (pageState.key !== contextKey) setPageState({ key: contextKey, limit: NEARBY_PAGE });
  const limit = pageState.key === contextKey ? pageState.limit : NEARBY_PAGE;

  /** Условия режет сервер; здесь — подстраховка на старый сервер, где их не знают (`shownNearby` в MapScreen.kt). */
  const shownRides = useMemo(
    () => (conditions.length === 0 && !verifiedOnly ? rides : rides.filter((r) => matchesNearbyConditions(r, conditions, verifiedOnly))),
    [rides, conditions, verifiedOnly]
  );

  /** Сброс из пустой ленты: снимает условия и города. «Только проверенные» — настройка «Безопасности», её не трогаем. */
  function resetFeedFilters() {
    setConditions([]);
    cityFilter.clear();
    setDay(null);
  }

  const feedState = nearbyFeedState({
    loading: status === "loading",
    error: status === "error",
    fetched: rides.length,
    shown: shownRides.length,
    conditionsOn: conditions.length > 0,
    cityActive: cityFilter.active,
    dayOn: day != null,
    verifiedOnly,
    hiddenUnverified,
  });

  // Последний запрос ленты: новый отменяет прежний, и запоздавший ответ не затрёт свежий.
  const inflight = useRef<AbortController | null>(null);
  /** Для какого поиска (`nearbyContextKey`) пришёл список на экране; null — списка нет. */
  const shownKey = useRef<string | null>(null);
  const load = useCallback(
    (quiet = false) => {
      inflight.current?.abort();
      const ac = new AbortController();
      inflight.current = ac;
      const key = nearbyContextKey(filters);
      // Тихое обновление — для авто-перезагрузки: карта не должна моргать
      // скелетоном каждые полминуты у человека на глазах.
      if (!quiet) setStatus("loading");
      fetchRidesNear(nearbyQuery(filters, limit), ac.signal)
        .then((r) => {
          if (ac.signal.aborted) return;
          shownKey.current = key;
          setRides(r.items);
          setTotal(r.count);
          setHiddenUnverified(r.hidden_unverified ?? 0);
          setStatus("ready");
        })
        .catch((e) => {
          if (ac.signal.aborted || e?.name === "AbortError") return;
          // Тихая перезагрузка ТОГО ЖЕ поиска не стирает показанную карту: сеть моргнула — человек
          // продолжает видеть поездки, а не экран ошибки. А сменил фильтр, день, город или место —
          // старый список под новым чипом неправда: убираем его, лента скажет «нет сети» с «Повторить».
          const failure = nearbyFailure(key, shownKey.current, quiet);
          if (failure === "keep") return;
          if (failure === "drop") {
            shownKey.current = null;
            setRides([]);
            setTotal(0);
            setHiddenUnverified(0);
          }
          setStatus("error");
        });
    },
    [filters, limit]
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

  // Поездки: заново при смене дня, места, условий, городов и «Показать ещё».
  useEffect(() => {
    load();
    return () => inflight.current?.abort();
  }, [load]);

  // Заявки пассажиров рядом → пины на карте. Требуют вход — гостю не грузим. От условий ленты
  // не зависят, поэтому свой запрос: смена фильтра не тянет их заново.
  useEffect(() => {
    if (!isAuthed) {
      setReqs([]);
      return;
    }
    const ac = new AbortController();
    fetchRequestsNear(me ? { lat: me.lat, lng: me.lng, radius_km: NEARBY_RADIUS_KM } : {}, ac.signal)
      .then((r) => setReqs(r.items))
      .catch(() => {
        if (!ac.signal.aborted) setReqs([]); // нет сети — пинов просто нет, лента живёт
      });
    return () => ac.abort();
  }, [isAuthed, me, reloadTick]);

  // Карта — живой экран: поездки появляются и уезжают, пока человек смотрит.
  // Раньше сайт показывал снимок на момент открытия: уехавшие висели, новые не
  // приходили. Приложение обновляет пины раз в ~25 секунд и молчит в фоне.
  useVisibleInterval(25000, () => {
    if (status === "loading") return;
    setReloadTick((n) => n + 1);
    load(true);
  });

  /** «Повторить» / «Обновить» в пустой ленте: заново и поездки, и список городов (если прошлый раз не вышло). */
  function retry() {
    setReloadTick((n) => n + 1);
    load();
  }

  // Фильтр «Ближайшие» — просим геолокацию; координаты сами перегружают ленту.
  function toggleNear() {
    if (nearOnly) {
      setMe(null);
      setGeoNote("");
      return;
    }
    if (!navigator.geolocation) return;
    navigator.geolocation.getCurrentPosition(
      (p) => {
        setMe({ lat: p.coords.latitude, lng: p.coords.longitude });
        setGeoNote("");
      },
      () => {
        // Отказ в гео — не падаем и НЕ молчим: человек нажал кнопку, она не
        // включилась, и без объяснения это выглядит поломкой, а не запретом.
        setMe(null);
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


  const selectMode = (m: "pooling" | "taxi" | "courier") => {
    if (m === "taxi") navigate("/taxi");
    else if (m === "courier") {
      if (deliveryOpen) navigate("/parcels");
      else setDeliveryTab(true);
    } else setDeliveryTab(false);
  };

  // Доставка закрыта: вместо карты — «Скоро в Юлдаше». Переключатель режимов остаётся,
  // чтобы вернуться на попутку одним касанием.
  if (deliveryTab) {
    return (
      <>
        <ModeSwitch mode="courier" onSelect={selectMode} showExplain={false} />
        {features.ready ? (
          <ComingSoon kind="delivery" variant="inline" onFindRide={() => setDeliveryTab(false)} />
        ) : (
          <LoadingList count={2} />
        )}
      </>
    );
  }

  return (
    <>
      {/* Как в Android: ModeSwitchBar над всем, затем HomeHeader — приветствие мелким и «Куда поедем?». */}
      <ModeSwitch
        mode="pooling"
        onSelect={selectMode}
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

      {/* Ряд фильтров как в приложении: четыре вопроса вместо двенадцати чипов — где, куда, когда,
          какие условия (NearbyFiltersPanel.kt). Выбирают в шторках, выбранное видно на чипе.
          Ряд виден, пока есть что фильтровать, или включён хоть один фильтр (путь назад), или на
          сервере есть города с поездками — «Откуда» ведёт к ним, даже если рядом пусто.
          «Когда» видно ВСЕГДА: это путь назад со «Сегодня» на «Все дни». */}
      {filtersRowVisible({ fetched: rides.length, day, conditionsOn: conditions.length > 0, citiesOffered: cityFilter.offered, nearOnly }) && (
        <div className="nearby-filters">
          <div className="nearby-filters__row" role="group" aria-label={appText("Фильтры ленты", "Таҫма фильтрҙары")}>
            <NearbyCityChips filter={cityFilter} />
            <NearbyWhenChip day={day} onChange={setDay} />
            <NearbyConditionsChip chosen={conditions} onChange={setConditions} verifiedOnly={verifiedOnly} total={total} />
            {/* Только в вебе: браузер не знает место без разрешения — чип просит его и сужает выдачу. */}
            <span className="nearby-filters__div" aria-hidden />
            <NearbyChip icon={<IconLocate size={15} />} label={appText("Ближайшие", "Иң яҡындар")} active={nearOnly} onClick={toggleNear} />
          </div>
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

      {/* Состояния ленты: скелетоны → пусто/нет сети карточкой → условия или города всё срезали → карусель. */}
      {feedState === "loading" && (
        <div className="nearby-row" aria-hidden>
          <NearbySkeletonCard />
          <NearbySkeletonCard />
        </div>
      )}
      {feedState === "error" && <NearbyEmptyCard error onRetry={retry} />}
      {feedState === "empty" && <NearbyEmptyCard error={false} onRetry={retry} />}
      {/* Проверенных нет, а непроверенные на этот поиск есть: «Показать» — только для этого поиска,
          настройка «Безопасности» не меняется (NearbyUnverifiedCard в NearbyVerifiedOnly.kt). */}
      {feedState === "unverified" && (
        <NearbyUnverifiedCard
          hidden={hiddenUnverified}
          onShow={() => setRevealedFor(savedKey)}
          onReset={conditions.length > 0 || cityFilter.active || day != null ? resetFeedFilters : undefined}
        />
      )}
      {feedState === "filtered" && (
        <div className="nearby-filtered">
          <p role="status">{appText("Нет поездок с такими условиями. Сними часть фильтров.", "Был шарттар менән сәфәр юҡ. Фильтрҙың бер өлөшөн ал.")}</p>
          {(conditions.length > 0 || cityFilter.active || day != null) && (
            <button type="button" className="nearby-filtered__reset" onClick={resetFeedFilters}>
              <IconClose size={20} /> {appText("Сбросить фильтры", "Фильтрҙы бушат")}
            </button>
          )}
          {/* «Только проверенные» сбросом не снимается — если именно она всё срезала (старый сервер),
              говорим, где её выключить. */}
          {conditions.length === 0 && !cityFilter.active && day == null && verifiedOnly && (
            <Link className="nearby-filtered__reset" to="/safety">
              {appText("Включено «Проверенные водители на карте». Выключить можно в «Безопасности»", "«Картала раҫланған йөрөтөүселәр» ҡабыҙылған. Һүндерергә «Хәүефһеҙлек» бүлегендә була")}
            </Link>
          )}
        </div>
      )}
      {feedState === "list" && (
        <div className="nearby-row">
          {shownRides.map((ride, i) => (
            <NearbyRideCard key={`${ride.id}#${i}`} ride={ride} soonest={i === 0} onOpen={() => setSheet(ride)} />
          ))}
          {/* «Показать ещё» — когда сервер сообщил, что подходящих больше, чем пришло (`count` уже учитывает условия). */}
          {canShowMore(rides.length, total) && (
            <NearbyMoreCard
              loading={status === "loading"}
              onMore={() => setPageState({ key: contextKey, limit: Math.min(limit + NEARBY_PAGE, NEARBY_MAX) })}
            />
          )}
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
