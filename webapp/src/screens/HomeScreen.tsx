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
import { pluralRu } from "../utils/format";
import { fetchSeasonalEvents, type SeasonalEvent } from "../api/seasonal";
import RideCard from "../components/RideCard";
import RideSheet from "../components/RideSheet";
import YandexMap, { type MapMarker, type GeoPoint } from "../components/YandexMap";
import { LoadingList, ErrorState } from "../components/States";
import CommunityFeedStrip from "../components/CommunityFeedStrip";
import { fetchRidesNear, fetchRequestsNear, type NearRequest } from "../api/discovery";
import type { Ride } from "../api/rides";
import { applyRideFilters, isFilterActive, loadFilters } from "../filterPrefs";
import { IconRides, IconGift, IconFilter, IconPin, IconCar } from "../components/Icons";
import { YuModeTaxi } from "../components/BrandIcons";
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
  // Фильтры по умолчанию (локальные) — применяем к списку поездок рядом.
  const prefs = useMemo(() => loadFilters(), []);
  const filterOn = isFilterActive(prefs);
  const shownRides = useMemo(() => applyRideFilters(rides, prefs), [rides, prefs]);

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

      <div className="chips">
        <button
          type="button"
          className={"chip" + (nearOnly ? " chip--on" : "")}
          onClick={toggleNear}
        >
          <IconPin size={16} /> {appText("Ближайшие", "Иң яҡындар")}
        </button>
        {/* «Когда едем». Человек ищет не «когда-нибудь», а завтра утром: без выбора дня
            список мешает сегодняшние поездки с теми, что через неделю. Нажатие на
            выбранный день снимает фильтр — отдельной кнопки «все дни» не нужно. */}
        <button
          type="button"
          className={"chip" + (day === localDay(0) ? " chip--on" : "")}
          aria-pressed={day === localDay(0)}
          onClick={() => pickDay(localDay(0))}
        >
          {appText("Сегодня", "Бөгөн")}
        </button>
        <button
          type="button"
          className={"chip" + (day === localDay(1) ? " chip--on" : "")}
          aria-pressed={day === localDay(1)}
          onClick={() => pickDay(localDay(1))}
        >
          {appText("Завтра", "Иртәгә")}
        </button>
        <button
          type="button"
          className={"chip" + (filterOn ? " chip--on" : "")}
          onClick={() => navigate("/filters")}
        >
          <IconFilter size={16} /> {appText("Фильтры", "Фильтрҙар")}
        </button>
      </div>

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
        {status === "ready" && shownRides.length > 0 && (
          <span className="nearby-header__count">
            {appText(`${shownRides.length} ${pluralRu(shownRides.length, "поездка", "поездки", "поездок")}`, `${shownRides.length} сәфәр`)}
          </span>
        )}
      </div>

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
                    "Заявка ҡалдыр — йөрөтөүселәр күреп яуап бирер."
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
            {/* «Показать ещё». Кнопка появляется, только когда сервер отдал полную
                страницу: значит есть что показывать дальше. Иначе список молча
                обрывался, и человек не знал — это всё или дальше не загрузилось. */}
            {rides.length >= limit && (
              <button
                type="button"
                className="btn-soft"
                style={{ width: "100%", marginTop: 8 }}
                onClick={() => setLimit((n) => n + PAGE)}
              >
                {appText("Показать ещё", "Тағы күрһәтергә")}
              </button>
            )}
          </div>
        ))}

      {/* Партнёр рядом — тариф «Город». Город берём тот, где человек ищет поездку. */}
      <PartnerAdSlot placement="nearby" city={shownRides[0]?.from_city} />

      {sheet && <RideSheet ride={sheet} onClose={() => setSheet(null)} />}
    </>
  );
}
