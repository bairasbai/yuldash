// ================================================================
//  Публикация поездки водителем (POST /rides/commands/v1). RequireAuth.
//  Маршрут (откуда/куда, тип — в т.ч. «В больницу» с выбором клиники),
//  дата/время, места, цена, удобства (тумблеры), регулярность.
//  Тип «В больницу» → GET /medical-partners (выбор клиники-назначения).
// ================================================================
import { useEffect, useMemo, useRef, useState } from "react";
import { getFeatures, useFeatures } from "../features";
import ComingSoon from "../components/ComingSoon";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { fetchPriceHint, type RideCreateInput, type PriceHint } from "../api/driver";
import { useAuth } from "../auth/AuthProvider";
import { checkRidePublication, publicationDraftHash, PublicationProblem, publishRide } from "../api/ridePublication";
import {
  bookingCommandOwner, isCurrentBookingOwner, releaseRidePublication, type BookingCommandOwner,
} from "../utils/bookingCommandStore";
import { useSavedPublication } from "../utils/useSavedPublication";
import { track } from "../analytics";
import { fetchMedicalPartners, type MedicalPartner } from "../api/medical";
import type { RideCategory } from "../api/rides";
import { SubHeader } from "./ConsentsScreen";
import WeatherWarningCard, { useRouteWeather } from "../components/WeatherWarningCard";
import { IconCheck, IconBolt, IconBox, IconHospital, IconUsers, IconTrend } from "../components/Icons";
import { YuModeRideshare } from "../components/BrandIcons";
import { AmenityIcon } from "../components/amenityIcons";
import CityField from "../components/CityField";
import StopSuggestField, { MAX_RIDE_STOPS, resolveRideStops, RideStopError, rideStopErrorMessage } from "../components/StopSuggestField";
import PickupChips from "../components/PickupChips";
import { priceLabel, rubLabel } from "../utils/format";
import { legPriceAmounts, splitPriceByLegs } from "../utils/pickupStops";
import { useDraftSync, clearDraft, readDraft } from "../utils/formDraft";
import { minDateTimeNow } from "../utils/dateInput";
import { SEATS_DEFAULT, clampSeats, seatCap } from "../utils/seatOptions";
import { SeatOptionsFields } from "../components/SeatOptions";

type Amenity =
  | "baggage"
  | "child_seat"
  | "pets_allowed"
  | "women_only"
  | "non_smoking"
  | "air_conditioner"
  | "quiet"
  | "no_minors";

type Recur = "none" | "daily" | "weekdays" | "weekly";

/** Ключ черновика формы «Опубликовать поездку». */
const RIDE_DRAFT = "create-ride";
/** Строка с правилом остановок; поля остановок ссылаются на неё через aria-describedby. */
const STOPS_RULE_ID = "create-ride-stops-rule";

export default function CreateRideScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const features = useFeatures();
  const { status, user } = useAuth();
  const userId = user?.id;
  const accountCreated = user?.created_at;
  const generation = getSessionGeneration();
  const owner = useMemo(() => status === "authed" && userId
    ? bookingCommandOwner({ id: userId, created_at: accountCreated }, generation) : null,
    [status, userId, accountCreated, generation]);
  const operation = useRef<AbortController | null>(null);
  const { publication, setSavedPublication, reloadPublication } = useSavedPublication(owner, operation);

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  // Погода по названиям городов: человек печатает «Баймак → Сибай», геокодит сервер.
  const weather = useRouteWeather({ fromCity: from.trim(), toCity: to.trim() });

  // Ориентир цены по маршруту (GET /rides/price_hint). Ждём паузу в наборе: город печатают
  // буквами, иначе на «Сибай» ушло бы пять запросов, четыре из них по несуществующим городам.
  const [hint, setHint] = useState<PriceHint | null>(null);
  useEffect(() => {
    const f = from.trim();
    const t = to.trim();
    if (!f || !t) {
      setHint(null);
      return;
    }
    const ac = new AbortController();
    const timer = window.setTimeout(() => {
      fetchPriceHint(f, t, ac.signal)
        .then(setHint)
        .catch(() => setHint(null)); // 404 / нет сети → просто не показываем
    }, 700);
    return () => {
      window.clearTimeout(timer);
      ac.abort();
    };
  }, [from, to]);
  const [when, setWhen] = useState(""); // datetime-local
  // Обычная легковая — 4 места: 1 спереди + 3 сзади (решение Александра, 2026-10-14).
  const [seats, setSeats] = useState(SEATS_DEFAULT);
  // Опции мест: «сзади максимум двое» режет число мест до 3 (до 2, если спереди свой человек).
  const [backMaxTwo, setBackMaxTwo] = useState(false);
  const [frontSeatAvailable, setFrontSeatAvailable] = useState(true);
  const maxSeats = seatCap(backMaxTwo, frontSeatAvailable);
  const [price, setPrice] = useState("");
  const [category, setCategory] = useState<RideCategory>("regular");
  /** Только для «возьму посылку»: кому отдать и что за груз. */
  const [receiverName, setReceiverName] = useState("");
  const [parcelSize, setParcelSize] = useState("");
  /**
   * Остановки по пути: A → точки → B. Не «удобство», а способ быть найденным:
   * человек из Темясово ищет поездку из Темясово, а не «Сибай → Уфа», и без этих
   * названий объявление проезжает мимо него.
   */
  const [waypoints, setWaypoints] = useState<string[]>([]);
  const [partnerId, setPartnerId] = useState<number | null>(null);
  // RS-02 Б: цена до каждой остановки (выключена — одна цена за весь маршрут, как раньше).
  const [stopPricesOn, setStopPricesOn] = useState(false);
  const [stopPriceValues, setStopPriceValues] = useState<string[]>([]);
  const [splitBusy, setSplitBusy] = useState(false);
  const [splitFailed, setSplitFailed] = useState(false);
  const stopNames = waypoints.map((w) => w.trim()).filter(Boolean);

  const [amen, setAmen] = useState<Record<Amenity, boolean>>({
    baggage: false,
    child_seat: false,
    pets_allowed: false,
    women_only: false,
    non_smoking: false,
    air_conditioner: false,
    quiet: false,
    no_minors: false,
  });
  const [onlyTrusted, setOnlyTrusted] = useState(false);
  const [recurrence, setRecurrence] = useState<Recur>("none");
  const [comment, setComment] = useState("");
  // Ориентир встречи из справочника города — «у мечети», «автовокзал».
  const [pickup, setPickup] = useState("");
  const [pickupPointId, setPickupPointId] = useState<number | null>(null);

  const [showMore, setShowMore] = useState(false);

  // Клиники — грузим лениво, когда выбран тип «В больницу» (мягко: нет эндпоинта → просто без списка).
  const [clinics, setClinics] = useState<MedicalPartner[]>([]);
  const [clinicsLoaded, setClinicsLoaded] = useState(false);

  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [publicationStale, setPublicationStale] = useState(false);

  // Форма длинная: маршрут, время, цена, удобства, комментарий. Отвлёкся на звонок,
  // айфон выгрузил вкладку — и всё заново. Черновик держит заполненное до публикации.
  const draft = { from, to, when, seats, price, category, amen, onlyTrusted, recurrence, comment, pickup, pickupPointId,
    receiverName, parcelSize, waypoints, partnerId, backMaxTwo, frontSeatAvailable, stopPricesOn, stopPriceValues };
  const draftSnapshot = JSON.stringify(draft);
  const latestDraft = useRef(draftSnapshot);
  latestDraft.current = draftSnapshot;
  useDraftSync(
    RIDE_DRAFT, draft,
    (d) => {
      if (d.from) setFrom(d.from);
      if (d.to) setTo(d.to);
      if (d.when) setWhen(d.when);
      if (d.seats) setSeats(d.seats);
      if (d.price) setPrice(d.price);
      if (d.category) setCategory(d.category);
      if (typeof d.partnerId === "number") setPartnerId(d.partnerId);
      if (d.receiverName) setReceiverName(String(d.receiverName));
      if (d.parcelSize) setParcelSize(String(d.parcelSize));
      if (Array.isArray(d.waypoints)) setWaypoints(d.waypoints.map(String));
      if (d.amen) setAmen((prev) => ({ ...prev, ...d.amen }));
      if (d.onlyTrusted) setOnlyTrusted(d.onlyTrusted);
      if (d.recurrence) setRecurrence(d.recurrence);
      if (d.comment) setComment(d.comment);
      if (d.pickup) setPickup(d.pickup);
      if (typeof d.pickupPointId === "number") setPickupPointId(d.pickupPointId);
      if (typeof d.backMaxTwo === "boolean") setBackMaxTwo(d.backMaxTwo);
      if (typeof d.frontSeatAvailable === "boolean") setFrontSeatAvailable(d.frontSeatAvailable);
      // RS-02 Б: вписанные цены остановок тоже переживают выгрузку вкладки.
      if (typeof d.stopPricesOn === "boolean") setStopPricesOn(d.stopPricesOn);
      if (Array.isArray(d.stopPriceValues)) setStopPriceValues(d.stopPriceValues.map(String));
    }
  );
  const [completed, setDone] = useState<{ owner: BookingCommandOwner; id: number; recurrence: Recur }>();
  const done = completed?.owner === owner ? completed : undefined;

  useEffect(() => {
    if (category !== "hospital" || clinicsLoaded) return;
    const ac = new AbortController();
    fetchMedicalPartners(undefined, ac.signal)
      .then((rows) => {
        setClinics(rows.filter((c) => c.active));
        setClinicsLoaded(true);
      })
      .catch(() => setClinicsLoaded(true)); // 404/скоро → тихо, тип всё равно можно выбрать
    return () => ac.abort();
  }, [category, clinicsLoaded]);

  const allCats: { key: RideCategory; label: string; icon: JSX.Element }[] = [
    { key: "regular", label: appText("Обычная", "Ябай"), icon: <YuModeRideshare size={20} /> },
    { key: "urgent", label: appText("Срочно", "Ашығыс"), icon: <IconBolt size={20} /> },
    { key: "hospital", label: appText("В больницу", "Дауаханаға"), icon: <IconHospital size={20} /> },
    // «Везу посылку» — половина попутного смысла: человек всё равно едет, а коробка
    // едет с ним. Без этой категории объявление «могу взять посылку» в вебе создать
    // было нельзя вообще.
    { key: "parcel", label: appText("Возьму посылку", "Аҫылма алам"), icon: <IconBox size={20} /> },
  ];
  // Пока доставка закрыта, «Возьму посылку» не предлагаем — вела в тупик «Скоро» (приёмка 18.10, п.16).
  const cats = allCats.filter((c) => c.key !== "parcel" || features.parcels);

  const amenList: { key: Amenity; label: string }[] = [
    { key: "baggage", label: appText("Багаж", "Багаж") },
    { key: "child_seat", label: appText("Детское кресло", "Бала урыны") },
    { key: "air_conditioner", label: appText("Кондиционер", "Кондиционер") },
    { key: "pets_allowed", label: appText("Можно ехать с питомцем", "Хайуан менән") },
    { key: "non_smoking", label: appText("Без курения", "Тартмайынса") },
    { key: "quiet", label: appText("Тихая поездка", "Тыныс сәфәр") },
    { key: "women_only", label: appText("Только женщины", "Тик ҡатын-ҡыҙ") },
    // Не «против детей», а честное предупреждение: везти подростка одного — другая
    // ответственность, и решать это надо ДО брони, а не у машины.
    { key: "no_minors", label: appText("Без пассажиров младше 18", "18-ҙән кесе юлсыларһыҙ") },
  ];

  const recurList: { key: Recur; label: string }[] = [
    { key: "none", label: appText("Разово", "Бер тапҡыр") },
    { key: "daily", label: appText("Каждый день", "Һәр көн") },
    { key: "weekdays", label: appText("По будням", "Эш көндәре") },
    { key: "weekly", label: appText("Раз в неделю", "Аҙнаға бер") },
  ];

  const canSubmit =
    from.trim().length > 0 && to.trim().length > 0 && when.length > 0 && !busy &&
    !!publication && !publication.storageUnavailable && !publicationStale;
  const command = publication?.command;
  const result = publication?.result;
  const knownState = result && result.state !== "unseen" ? result.state : command?.state;
  const terminal = knownState === "applied" || knownState === "rejected" || knownState === "closed_no_effect";
  const canReplay = command?.state === "unknown" && (!result || result.state === "unseen");
  const confirmedDraftHash = knownState === "applied" ? command?.draftHash : undefined;

  useEffect(() => {
    if (!owner || !confirmedDraftHash) return;
    const saved = readDraft(RIDE_DRAFT);
    if (!saved) return;
    const serialized = JSON.stringify(saved);
    let active = true;
    void publicationDraftHash(saved).then(hash => {
      if (active && hash === confirmedDraftHash && isCurrentBookingOwner(owner) &&
          JSON.stringify(readDraft(RIDE_DRAFT)) === serialized) clearDraft(RIDE_DRAFT);
    }).catch(() => {});
    return () => { active = false; };
  }, [owner, confirmedDraftHash]);

  async function submit() {
    if (!owner || !publication || (command && !canReplay) || submitting.current || !canSubmit ||
        ((category === "parcel" || category === "cargo") && !getFeatures().parcels)) return;
    const request = new AbortController();
    operation.current = request;
    submitting.current = true;
    setBusy(true);
    setError(null);
    try {
    const stopLanguage = command?.stopLanguage ?? (lang === "ba" ? "ba" : "ru");
    const wallTime = Date.parse(when + "Z");
    const utcOffsetMinutes = command?.utcOffsetMinutes ?? (new Date(when).getTime() - wallTime) / 60_000;
    const selectedStops = await resolveRideStops(waypoints, from.trim(), to.trim(), stopLanguage === "ba");
    if (request.signal.aborted || !isCurrentBookingOwner(owner) ||
        ((category === "parcel" || category === "cargo") && !getFeatures().parcels)) return;
    const body: RideCreateInput = {
      from_city: from.trim(),
      to_city: to.trim(),
      depart_at: new Date(wallTime + utcOffsetMinutes * 60_000).toISOString(),
      seats_total: clampSeats(seats, backMaxTwo, frontSeatAvailable),
      back_max_two: backMaxTwo,
      front_seat_available: frontSeatAvailable,
      price: price ? Math.max(0, parseInt(price, 10) || 0) : 0,
      category,
      comment: comment.trim(),
      pickup: pickup.trim() || undefined,
      pickup_point_id: pickupPointId,
      only_trusted: onlyTrusted,
      recurrence,
      baggage: amen.baggage,
      child_seat: amen.child_seat,
      pets_allowed: amen.pets_allowed,
      women_only: amen.women_only,
      air_conditioner: amen.air_conditioner,
      quiet: amen.quiet,
      no_minors: amen.no_minors,
      // Бэк хранит «курение», а тумблер — «без курения»: инвертируем.
      smoking: amen.non_smoking ? false : undefined,
      partner_id: category === "hospital" ? partnerId : null,
      // Поля посылки шлём только там, где они значат хоть что-то.
      receiver_name: category === "parcel" ? receiverName.trim() || undefined : undefined,
      parcel_size: category === "parcel" ? parcelSize.trim() || undefined : undefined,
      // Сервер хранит остановки одной строкой через « | » — тем же разделителем,
      // что и приложение, иначе одна и та же поездка выглядела бы по-разному.
      waypoints: selectedStops.join(" | ") || undefined,
    };
    // RS-02 Б: цены остановок (включены) — проверка до сети, сервер перепроверяет.
    if (stopPricesOn && selectedStops.length) {
      const total = body.price ?? 0;
      const nums = stopPriceValues.filter((v) => v.trim()).map((v) => parseInt(v, 10));
      const ok = nums.length === selectedStops.length && nums.every((v, i) => Number.isInteger(v) && v > (i ? nums[i - 1] : 0) && v < total);
      if (!ok) {
        setError(appText("Проверь цены остановок: каждая больше предыдущей и меньше цены маршрута.",
                         "Туҡталыш хаҡтарын тикшер: һәр береһе алдағыһынан ҙур һәм юл хаҡынан бәләкәй."));
        return;
      }
      body.stop_prices = nums;
    }
      const draftHash = await publicationDraftHash(draft);
      if (request.signal.aborted || !isCurrentBookingOwner(owner)) return;
      const saved = await publishRide(body, owner, request.signal, {
        draftHash, revision: publication.revision, stopLanguage, utcOffsetMinutes, retry: command,
      });
      if (request.signal.aborted || !isCurrentBookingOwner(owner)) return;
      setSavedPublication({ owner, revision: saved.command.key, ...saved });
      if (saved.result.state !== "applied" || saved.result.ride?.status !== "active") return;
      const storedDraft = readDraft<typeof draft>(RIDE_DRAFT);
      if (draftHash === saved.command.draftHash && latestDraft.current === draftSnapshot &&
          (!storedDraft || JSON.stringify(storedDraft) === draftSnapshot)) {
        clearDraft(RIDE_DRAFT);
      }
      track("publish_ride", { category });
      setDone({ owner, id: saved.result.ride.id, recurrence });
    } catch (e) {
      if (request.signal.aborted || !isCurrentBookingOwner(owner)) return;
      if (e instanceof PublicationProblem) {
        if (e.kind === "stale") { setPublicationStale(true); return; }
        if (e.command) setSavedPublication({ owner, revision: e.command.key, command: e.command });
        else if (e.kind === "unknown") reloadPublication();
        const messages = {
          changed: appText("Поля изменились. Сначала проверь прошлую публикацию.", "Яландар үҙгәрҙе. Тәүҙә алдағы баҫтырыуҙы тикшер."),
          expired: appText("Время повтора вышло. Проверь прошлую публикацию.", "Ҡабатлау ваҡыты үтте. Алдағы баҫтырыуҙы тикшер."),
          storage: appText("Не получилось сохранить запрос. Попробуй снова.", "Һорауҙы һаҡлап булманы. Тағы ҡабатлап ҡара."),
          unknown: appText("Ответ не пришёл. Проверь, появилась ли поездка.", "Яуап килмәне. Сәфәр барлыҡҡа килдеме, тикшер."),
        };
        setError(e.original?.message || messages[e.kind]);
        return;
      }
      setError(
        e instanceof RideStopError ? rideStopErrorMessage(e.problem, appText) : e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось опубликовать. Попробуй снова.", "Баҫтырырға булманы. Ҡабат ҡара.") // DRAFT
      );
    } finally {
      if (operation.current === request) {
        submitting.current = false;
        setBusy(false);
      }
    }
  }

  async function recover(mode: "check" | "close" | "release") {
    if (!owner || !command || submitting.current) return;
    const request = new AbortController();
    operation.current = request;
    submitting.current = true;
    setBusy(true);
    setError(null);
    try {
      if (mode === "release") {
        const matchesForm = knownState === "applied" && await publicationDraftHash(draft) === command.draftHash;
        if (request.signal.aborted || !isCurrentBookingOwner(owner)) return;
        const released = await releaseRidePublication(owner, command);
        if (!request.signal.aborted && isCurrentBookingOwner(owner)) {
          if (!released) { setPublicationStale(true); return; }
          if (matchesForm && latestDraft.current === draftSnapshot) {
            const stored = readDraft(RIDE_DRAFT);
            if (JSON.stringify(stored) === draftSnapshot) clearDraft(RIDE_DRAFT);
            navigate("/create-ride", { replace: true });
          } else reloadPublication();
        }
      } else {
        const checked = await checkRidePublication(owner, command, mode === "close", request.signal);
        if (!request.signal.aborted && isCurrentBookingOwner(owner)) {
          setSavedPublication({ owner, revision: command.key, command, result: checked });
        }
      }
    } catch (failure) {
      if (!request.signal.aborted && isCurrentBookingOwner(owner)) {
        if (failure instanceof PublicationProblem && failure.kind === "stale") { setPublicationStale(true); return; }
        setError(appText("Не удалось проверить публикацию. Повтори, когда появится сеть.",
          "Баҫтырыуҙы тикшереп булманы. Бәйләнеш ҡайтҡас, ҡабатлап ҡара."));
      }
    } finally {
      if (operation.current === request) {
        submitting.current = false;
        setBusy(false);
      }
    }
  }

  const recoveredRide = result?.state === "applied" ? result.ride : null;
  const recoveryPanel = publicationStale ? (
    <div className="state">
      <p role="status">{appText("Публикация изменилась в другой вкладке.", "Баҫтырыу башҡа ҡушымтала үҙгәрҙе.")}</p>
      <button type="button" className="btn-soft" onClick={() => navigate("/create-ride", { replace: true })}>
        {appText("Открыть свежий черновик", "Яңыртылған ҡараламаны асырға")}
      </button>
    </div>
  ) : owner && !publication ? (
    <p role="status">{appText("Проверяем прошлые публикации…", "Алдағы баҫтырыуҙарҙы тикшерәбеҙ…")}</p>
  ) : status === "authed" && !owner ? (
    <p role="status">{appText("Для публикации войди в аккаунт заново.", "Баҫтырыр өсөн аккаунтҡа яңынан ин.")}</p>
  ) : publication?.storageUnavailable ? (
    <div className="state">
      <p role="status">{appText("Не получилось открыть хранилище публикаций.", "Баҫтырылғандар һаҡлағысын асып булманы.")}</p>
      <button type="button" className="btn-soft" onClick={reloadPublication}>
        {appText("Повторить", "Ҡабатларға")}
      </button>
    </div>
  ) : command ? (
    <div className="state">
      <p role="status">{result?.state === "applied"
        ? recoveredRide?.status === "active" ? appText("Поездка уже опубликована.", "Сәфәр инде баҫтырылған.")
          : appText("Прежняя поездка больше не активна.", "Алдағы сәфәр инде әүҙем түгел.")
        : knownState === "applied"
          ? appText("Публикация обработана. Подключись к сети, чтобы увидеть поездку.",
            "Баҫтырыу эшкәртелде. Сәфәрҙе күрер өсөн бәйләнешкә тоташ.")
        : knownState === "rejected" || knownState === "closed_no_effect"
          ? appText("Прошлая публикация не создала поездку.", "Алдағы баҫтырыу сәфәрҙе булдырманы.")
          : appText("Проверь, появилась ли поездка, прежде чем публиковать снова.",
            "Яңынан баҫтырғансы, сәфәр барлыҡҡа килдеме, тикшер.")}</p>
      {recoveredRide?.status === "active" && (
        <button type="button" className="btn-soft" onClick={() => navigate("/driver")}>
          {appText("В кабинет водителя", "Йөрөтөүсе кабинетына")}
        </button>
      )}
      {terminal && (
        <button type="button" className="btn-soft" disabled={busy} onClick={() => void recover("release")}>
          {appText("Начать новую публикацию", "Яңы баҫтырыуҙы башларға")}
        </button>
      )}
      {(!terminal || (knownState === "applied" && !result)) && (
        <>
          <button type="button" className="btn-soft" disabled={busy} onClick={() => void recover("check")}>
            {appText("Проверить публикацию", "Баҫтырыуҙы тикшерергә")}
          </button>
          {canReplay && (
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => void recover("close")}>
              {appText("Закрыть прошлый запрос", "Алдағы һорауҙы ябырға")}
            </button>
          )}
        </>
      )}
    </div>
  ) : null;

  if (done) {
    return (
      <>
        <SubHeader
          title={appText("Поездка опубликована", "Сәфәр баҫтырылды")}
          onBack={() => navigate("/driver")}
        />
        <div className="state">
          <div className="state__icon"><IconCheck size={34} /></div>
          <h2>{appText("Готово! Пассажиры увидят поездку", "Әҙер! Юлаусылар күрер")}</h2>
          <p>
            {appText(
              done.recurrence === "none"
                ? "Твоя поездка теперь в ленте и на карте. Придёт бронь — пришлём уведомление."
                : "Создали серию ближайших рейсов. Все — в ленте и на карте.",
              done.recurrence === "none"
                ? "Сәфәрең хәҙер таҫмала һәм картала. Бронь килһә — хәбәр итәбеҙ."
                : "Яҡын рейстар серияһын төҙөнөк. Барыһы ла таҫмала һәм картала."
            )}
          </p>
          <button
            type="button"
            className="btn-primary"
            onClick={() => navigate("/boost", { state: { rideId: done.id } })}
          >
            {appText("Поднять в ленте", "Таҫмала күтәреү")}
          </button>
          <button
            type="button"
            className="btn-ghost"
            style={{ marginTop: 10 }}
            onClick={() => navigate("/driver")}
          >
            {appText("В кабинет водителя", "Йөрөтөүсе кабинетына")}
          </button>
        </div>
      </>
    );
  }

  if ((category === "parcel" || category === "cargo") && !features.parcels && !busy) return <>
    {recoveryPanel}
    <ComingSoon kind="delivery" />
    <button type="button" className="btn-soft" onClick={() => setCategory("regular")}>{appText("Обычная", "Ябай")}</button>
  </>;

  return (
    <>
      <SubHeader
        title={appText("Опубликовать поездку", "Сәфәр баҫтырырға")}
        subtitle={appText("Расскажи маршрут — пассажиры найдут тебя", "Маршрутты яҙ — юлаусылар табыр")}
        onBack={() => navigate(-1)}
      />

      <div className="form">
        {recoveryPanel}
        {/* Подсказки из справочника: одно написание на всех. «Темясово»
            человек напишет пятью способами, и заявка не совпадёт ни с одной поездкой. */}
        <CityField
          label={appText("Откуда", "Ҡайҙан")}
          value={from}
          onChange={setFrom}
          placeholder={appText("Город или село", "Ҡала йәки ауыл")}
        />

        <CityField
          label={appText("Куда", "Ҡайҙа")}
          value={to}
          onChange={setTo}
          placeholder={appText("Город или село", "Ҡала йәки ауыл")}
        />

        {/* Ориентиры этого города: «у мечети», «автовокзал» — вместо тыка в карту */}
        <PickupChips city={from} value={pickup} onPick={setPickup} onPickPoint={setPickupPointId} />

        {/* ❄️ Погода на маршруте — пусто, когда сказать нечего */}
        <WeatherWarningCard weather={weather} />

        <label className="field">
          <span className="field__label">{appText("Когда выезжаешь", "Ҡасан сығаһың")}</span>
          <input
            className="field__input"
            type="datetime-local"
            min={minDateTimeNow()}
            value={when}
            onChange={(e) => setWhen(e.target.value)}
          />
        </label>

        <div className="field-row">
          <div className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Свободных мест", "Буш урын")}</span>
            <div className="stepper">
              <button
                type="button"
                onClick={() => setSeats((s) => Math.max(1, s - 1))}
                aria-label={appText("Меньше", "Кәм")}
              >
                −
              </button>
              <b>{seats}</b>
              <button
                type="button"
                onClick={() => setSeats((s) => Math.min(maxSeats, s + 1))}
                disabled={seats >= maxSeats}
                aria-label={appText("Больше", "Күберәк")}
              >
                +
              </button>
            </div>
          </div>
          <label className="field" style={{ flex: 1 }}>
            <span className="field__label">{appText("Цена с места, ₽", "Урын хаҡы, ₽")}</span>
            {/* Границы те же, что у сервера (0…100 000 ₽): лучше не дать ввести
                лишний ноль, чем показать отказ после отправки формы. */}
            <input
              className="field__input"
              type="number"
              inputMode="numeric"
              min={0}
              max={100000}
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              placeholder={appText("0 — договорная", "0 — килешеү")}
            />
          </label>
        </div>

        {/* Опции мест — сразу под числом мест: тумблер меняет это число, и связь видна глазами. */}
        <SeatOptionsFields
          backMaxTwo={backMaxTwo}
          frontSeatAvailable={frontSeatAvailable}
          onBackMaxTwo={(v) => {
            setBackMaxTwo(v);
            setSeats((s) => clampSeats(s, v, frontSeatAvailable));
          }}
          onFrontSeatAvailable={(v) => {
            setFrontSeatAvailable(v);
            setSeats((s) => clampSeats(s, backMaxTwo, v));
          }}
        />

        {/* Ориентир цены: сколько в среднем берут по этому маршруту и сколько уйдёт бензина.
            Подсказка, а не правило — цену решает водитель. */}
        {hint && (hint.avg > 0 || hint.fuel_estimate_kop != null) && (
          <div className="act-card act-card--mint" style={{ marginTop: 0 }}>
            <div className="act-card__title">
              <IconTrend size={18} /> {appText("Сколько обычно берут", "Ғәҙәттә күпме алалар")}
            </div>
            <div className="info-list" style={{ marginTop: 8 }}>
              {hint.avg > 0 && (
                <div className="info-row">
                  <span className="info-row__k">
                    {appText("Средняя цена по маршруту", "Маршрут буйынса урта хаҡ")}
                  </span>
                  <span className="info-row__v">{priceLabel(hint.avg, ru)}</span>
                </div>
              )}
              {hint.distance_km != null && (
                <div className="info-row">
                  <span className="info-row__k">{appText("Расстояние", "Ара")}</span>
                  <span className="info-row__v">
                    {Math.round(hint.distance_km)} {appText("км", "км")}
                  </span>
                </div>
              )}
              {hint.fuel_estimate_kop != null && (
                <div className="info-row">
                  <span className="info-row__k">{appText("Бензин на дорогу", "Юлға бензин")}</span>
                  <span className="info-row__v">{rubLabel(hint.fuel_estimate_kop)}</span>
                </div>
              )}
            </div>
            <p className="act-card__text" style={{ margin: "8px 0 0" }}>
              {appText(
                "Это ориентир по прошлым поездкам — цену решаешь ты.",
                "Был үткән сәфәрҙәр буйынса ориентир — хаҡты үҙең хәл итәһең."
              )}
            </p>
          </div>
        )}

        <span className="field__label" style={{ marginTop: 4 }}>
          {appText("Тип поездки", "Сәфәр төрө")}
        </span>
        <div className="seg">
          {cats.map((c) => (
            <button
              key={c.key}
              type="button"
              className={"seg__item" + (category === c.key ? " is-active" : "")}
              onClick={() => setCategory(c.key)}
            >
              <span>{c.icon}</span>
              {c.label}
            </button>
          ))}
        </div>

        {/* Клиника-назначение (только для «В больницу») */}
        {category === "hospital" && clinics.length > 0 && (
          <div className="more-body" style={{ marginTop: 12 }}>
            <span className="field__label">{appText("Клиника (куда именно)", "Клиника (ҡайҙа тап)")}</span>
            <div className="opt-grid">
              {clinics.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  className={"opt-chip" + (partnerId === c.id ? " is-active" : "")}
                  onClick={() => setPartnerId((p) => (p === c.id ? null : c.id))}
                >
                  {partnerId === c.id && <IconCheck size={16} />}
                  {c.name}
                </button>
              ))}
            </div>
            <p className="sheet__note" style={{ marginTop: 8 }}>
              {appText(
                "Это только точка назначения. Никаких медицинских данных.",
                "Был тик барыу нөктәһе. Бер ниндәй медицина мәғлүмәте юҡ."
              )}
            </p>
          </div>
        )}

        {/* Остановки по пути. Их пишут словами, а не точками на карте: попутчик ищет
            «Темясово», и найтись должно именно это слово. */}
        <div className="more-body" style={{ marginTop: 12 }}>
          <span className="field__label">{appText("Остановки по пути", "Юл буйындағы туҡталыштар")}</span>
          <p className="sheet__note" style={{ marginTop: 4 }}>
            {appText(
              "Куда заезжаешь по дороге — так тебя найдут попутчики с этих мест.",
              "Юлда ҡайҙа туҡтайһың — шул урындарҙан юлдаштар һине табыр."
            )}
          </p>
          {/* Правило — до ввода, а не только ошибкой после «Опубликовать» (те же границы,
              что resolveRideStops и сервер). Как в приложении — с первой остановкой. */}
          {waypoints.length > 0 && (
            <p id={STOPS_RULE_ID} className="sheet__note" style={{ marginTop: "var(--space-xs)" }}>
              {appText(
                "Только сёла и города из подсказок — без адреса и цифр. До четырёх остановок.",
                "Тәҡдимдәрҙән ауылдар һәм ҡалалар ғына — адресһеҙ һәм һандарһыҙ. Иң күбе — дүрт туҡталыш."
              )}
            </p>
          )}
          {waypoints.map((w, i) => (
            <div key={i} className="trip-stops__row" style={{ background: "transparent", padding: 0, marginTop: 8 }}>
              <StopSuggestField
                value={w}
                onChange={(value) =>
                  setWaypoints((prev) => prev.map((x, j) => (j === i ? value : x)))
                }
                endpoints={[from, to]}
                describedBy={STOPS_RULE_ID}
              />
              <button
                type="button"
                className="trip-stops__del"
                style={{ minHeight: 48, minWidth: 48 }}
                onClick={() => setWaypoints((prev) => prev.filter((_, j) => j !== i))}
              >
                {appText("Убрать", "Алып ташлау")}
              </button>
            </div>
          ))}
          {waypoints.length < MAX_RIDE_STOPS && (
            <button
              type="button"
              className="btn-soft btn-soft--sm"
              style={{ marginTop: "var(--space-sm)", minHeight: 48 }}
              onClick={() => setWaypoints((prev) => [...prev, ""])}
            >
              {appText("Добавить остановку", "Туҡталыш өҫтәү")}
            </button>
          )}
        </div>

        {/* RS-02 Б: цена до каждой остановки — попутчик платит только за свой путь. */}
        {stopNames.length > 0 && (
          <div className="trip-stops" style={{ marginTop: 12 }}>
            <label style={{ display: "flex", alignItems: "center", gap: 8, minHeight: 48 }}>
              <input
                type="checkbox"
                className="checkbox"
                checked={stopPricesOn}
                onChange={(e) => { setStopPricesOn(e.target.checked); setSplitFailed(false); }}
              />
              <span>
                <b>{appText("Цена до каждой остановки", "Һәр туҡталышҡа тиклем хаҡ")}</b>
                <div className="sheet__note">{appText("Попутчик платит только за свой путь.", "Юлдаш тик үҙ юлы өсөн түләй.")}</div>
              </span>
            </label>
            {stopPricesOn && (
              <>
                {stopNames.map((stop, i) => (
                  <label className="field" key={`sp-${i}`} style={{ marginTop: 8 }}>
                    <span className="field__label">{appText(`до ${stop}, ₽`, `${stop} тиклем, ₽`)}</span>
                    <input
                      className="field__input"
                      inputMode="numeric"
                      style={{ minHeight: 48 }}
                      value={stopPriceValues[i] ?? ""}
                      onChange={(e) => {
                        const v = e.target.value.replace(/\D/g, "").slice(0, 5);
                        setStopPriceValues((prev) => {
                          const next = [...prev];
                          while (next.length <= i) next.push("");
                          next[i] = v;
                          return next;
                        });
                      }}
                    />
                  </label>
                ))}
                {(() => {
                  const total = parseInt(price, 10) || 0;
                  const cum = stopPriceValues.map((v) => parseInt(v, 10) || 0);
                  if (cum.length === stopNames.length && cum.every((v) => v > 0) && total > 0) {
                    const legs = legPriceAmounts(cum, total);
                    const points = [from.trim() || appText("Начало", "Башы"), ...stopNames, to.trim() || appText("Конец", "Аҙағы")];
                    return (
                      <p className="sheet__note" style={{ marginTop: 8 }}>
                        {legs.map((amount, i) => `${points[i]} → ${points[i + 1]}: ${amount} ₽`).join(" · ")}
                      </p>
                    );
                  }
                  return null;
                })()}
                <button
                  type="button"
                  className="btn-soft btn-soft--sm"
                  style={{ marginTop: 8, minHeight: 48 }}
                  disabled={splitBusy || !(parseInt(price, 10) > 0)}
                  onClick={async () => {
                    // «Поделить по расстоянию»: км звеньев — свой справочник сервера;
                    // последнее звено — остаток прямой начало→конец.
                    const total = parseInt(price, 10) || 0;
                    if (!total || splitBusy) return;
                    setSplitBusy(true);
                    setSplitFailed(false);
                    try {
                      const hint = await fetchPriceHint(from.trim(), to.trim(), undefined, stopNames);
                      const legs = stopNames.map((s) => hint.stops?.find((h) => h.stop === s)?.distance_km ?? null);
                      const known = legs.filter((d): d is number => d !== null).reduce((s, d) => s + d, 0);
                      const lastLeg = hint.distance_km !== null && hint.distance_km - known > 0 ? hint.distance_km - known : null;
                      const split = splitPriceByLegs(total, [...legs, lastLeg]);
                      if (split === null) {
                        setStopPriceValues([]);
                        setSplitFailed(true);
                      } else {
                        setStopPriceValues(split.map(String));
                      }
                    } catch {
                      setSplitFailed(true);
                    } finally {
                      setSplitBusy(false);
                    }
                  }}
                >
                  {appText("Поделить по расстоянию", "Алыҫлыҡҡа ҡарап бүлеү")}
                </button>
                {splitFailed && (
                  <p className="sheet__note" style={{ marginTop: 4 }}>
                    {appText("Не получилось поделить — впиши цены сам", "Бүлеп булманы — хаҡты үҙең язы")}
                  </p>
                )}
              </>
            )}
          </div>
        )}

        {/* Поездка «возьму посылку»: кому отдать и что за груз. Без этих строк
            объявление бесполезно — отправитель не знает, влезет ли коробка,
            а водитель не знает, кому её вручить на том конце. */}
        {category === "parcel" && (
          <div className="more-body" style={{ marginTop: 12 }}>
            <label className="field">
              <span className="field__label">{appText("Кому передать (имя)", "Кемгә тапшырырға (исем)")}</span>
              <input
                className="field__input"
                value={receiverName}
                onChange={(e) => setReceiverName(e.target.value.slice(0, 120))}
                placeholder={appText(
                  "Напр.: Айгуль, заберёт на автовокзале",
                  "Мәҫәлән: Айгүл, автовокзалда алыр"
                )}
              />
            </label>
            <label className="field">
              <span className="field__label">{appText("Габарит / вес", "Үлсәм / ауырлыҡ")}</span>
              <input
                className="field__input"
                value={parcelSize}
                onChange={(e) => setParcelSize(e.target.value.slice(0, 80))}
                placeholder={appText("Напр.: до 5 кг, коробка 40×30", "Мәҫәлән: 5 кг ҡәҙәр, ҡумта 40×30")}
              />
            </label>
          </div>
        )}

        <button
          type="button"
          className="more-toggle"
          onClick={() => setShowMore((v) => !v)}
          aria-expanded={showMore}
        >
          {appText("Удобства и повтор", "Уңайлыҡтар һәм ҡабатлау")}
          <span className={"more-toggle__chev" + (showMore ? " open" : "")}>⌄</span>
        </button>

        {showMore && (
          <div className="more-body">
            <div className="opt-grid">
              {amenList.map((o) => (
                <button
                  key={o.key}
                  type="button"
                  className={"opt-chip" + (amen[o.key] ? " is-active" : "")}
                  onClick={() => setAmen((p) => ({ ...p, [o.key]: !p[o.key] }))}
                >
                  {amen[o.key] ? <IconCheck size={16} /> : <AmenityIcon amenity={o.key} size={16} />}
                  {o.label}
                </button>
              ))}
            </div>

            <span className="field__label" style={{ marginTop: 14 }}>
              {appText("Регулярность", "Даималыҡ")}
            </span>
            <div className="opt-grid">
              {recurList.map((r) => (
                <button
                  key={r.key}
                  type="button"
                  className={"opt-chip" + (recurrence === r.key ? " is-active" : "")}
                  onClick={() => setRecurrence(r.key)}
                >
                  {recurrence === r.key && <IconCheck size={16} />}
                  {r.label}
                </button>
              ))}
            </div>

            <button
              type="button"
              className={"onb__simple" + (onlyTrusted ? " is-active" : "")}
              onClick={() => setOnlyTrusted((v) => !v)}
              style={{ marginTop: 14 }}
            >
              <span className="onb__simple-emoji"><IconUsers size={24} /></span>
              <span className="onb__simple-text">
                <b>{appText("Только для своих", "Тик үҙебеҙ өсөн")}</b>
                <span>
                  {appText(
                    "Поездку увидят только проверенные соседи",
                    "Сәфәрҙе тик тикшерелгән күршеләр күрер"
                  )}
                </span>
              </span>
              <span className={"switch" + (onlyTrusted ? " on" : "")} />
            </button>

            <label className="field" style={{ marginTop: 12 }}>
              <span className="field__label">{appText("Комментарий", "Аңлатма")}</span>
              <textarea
                className="field__input field__area"
                value={comment}
                maxLength={2000}
                onChange={(e) => setComment(e.target.value)}
                placeholder={appText(
                  "Например: выезжаю от автовокзала, есть место для сумки",
                  "Мәҫәлән: автовокзалдан сығам, сумкаға урын бар"
                )}
              />
            </label>
          </div>
        )}

        {error && <div className="auth__error">{error}</div>}

        <button
          type="button"
          className="btn-primary submit-btn"
          onClick={submit}
          disabled={!canSubmit || (!!command && !canReplay)}
        >
          {busy ? appText("Публикуем…", "Баҫтырабыҙ…")
            : canReplay ? appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")
              : appText("Опубликовать", "Баҫтырырға")}
        </button>
      </div>
    </>
  );
}
