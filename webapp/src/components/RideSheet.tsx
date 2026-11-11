import { rideDriverRating } from "../utils/rideDriverRating";
// ================================================================
//  Нижняя шторка с деталями поездки + бронирование.
//  Открывается тапом по поездке на витрине (Home). Гость → на вход.
//  Бронь: сохранённая команда → pending открывает /booking/{id}, иначе /trip/{id}.
//  «Закрыть», затемнение и Esc уводят шторку вниз (useSheetExit); бронь и «Открыть поездку»
//  закрывают её сразу — их движение — переход на экран поездки, ждать нечего.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { getFeatures, useFeatures } from "../features";
import ComingSoon from "./ComingSoon";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError, getSessionGeneration } from "../api/client";
import { useOwnBooking, useRefreshOwnBookings } from "../booking/OwnBookingsProvider";
import type { BookingRow, MeetingPointInput } from "../api/bookings";
import { BookingCommandProblem, bookingCommandOwner, createBooking, readBookingCommand,
  reconcileBookingCommand, type BookingCommand } from "../api/bookingCommands";
import { bookingRefusalMessage } from "../utils/bookingCommandLabels";
import { rememberGuestBookingDraft, type GuestBookingSelection } from "../utils/guestBookingDraft";
import { track } from "../analytics";
import type { Ride } from "../api/rides";
import { fetchRide } from "../api/rides";
import { formatWhen, pluralRu, priceLabel } from "../utils/format";
import { IconArrow, IconCheck } from "./Icons";
import { YuStar, YuWomenOnly, YuLuggage, YuChildSeat } from "./BrandIcons";
import { FrontSeatChoice, SeatBadges } from "./SeatOptions";
import { useSheetExit } from "./useSheetExit";
import { initialSegment, keepDropoffAfterPickup, segmentReady, withoutRideMatch, seatsOnSpan, segmentPriceRub } from "../utils/pickupStops";
import { DropoffStopChoice, PickupStopChoice, BookingSegmentNote } from "./PickupStopChoice";
import { MeetingPointPicker } from "./MeetingPointPicker";

export default function RideSheet({
  ride,
  onClose,
  initialSelection,
}: {
  ride: Ride;
  onClose: () => void;
  initialSelection?: GuestBookingSelection | null;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed, status: authStatus, user } = useAuth();
  const features = useFeatures();
  const parcelCategory = ride.category === "parcel" || ride.category === "cargo";
  const ownBooking = useOwnBooking(ride.id);
  const refreshOwnBookings = useRefreshOwnBookings();
  const ownerGeneration = getSessionGeneration();
  const [busy, setBusy] = useState(false);
  const bookingInFlight = useRef(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  // Фокус — на саму шторку (useSheetExit): читалка называет «Поездка», Esc и Tab работают в ней. Раньше фокус
  // ставился на «Закрыть», и тот же Enter, которым открыли поездку с карточки, сразу её закрывал.
  // Пока бронь в пути, Esc шторку не трогает.
  const exit = useSheetExit(onClose, dialogRef, { canClose: () => !bookingInFlight.current });
  const [error, setError] = useState<string | null>(null);
  const [command, setCommand] = useState<BookingCommand>();
  const [recoveredBooking, setRecoveredBooking] = useState<BookingRow | null>(null);
  const [restoringCommand, setRestoringCommand] = useState(false);
  const owner = isAuthed ? bookingCommandOwner(user, ownerGeneration) : null;
  const unresolved = command?.state === "unknown";
  const canRebook = recoveredBooking?.status === "cancelled" && !ownBooking;

  // Открыли карточку поездки — заинтересовался конкретной поездкой.
  useEffect(() => {
    track("open_ride", { category: ride.category ?? "regular" });
  }, [ride.category]);

  /**
   * Едет пассажир младше 18.
   *
   * Взрослый обязателен и назван поимённо: без имени и телефона за ребёнка в дороге
   * не отвечает никто, а водитель узнаёт о подростке, только когда тот сядет в машину.
   * Правило держит сервер — мы просто спрашиваем заранее, чтобы бронь не отлетела.
   */
  const [minor, setMinor] = useState(false);
  const [guardName, setGuardName] = useState("");
  const [guardPhone, setGuardPhone] = useState("");
  /** «Хочу переднее место»: одно на машину, гонку двух нажатий разводит сервер. */
  const [frontSeat, setFrontSeat] = useState(false);
  const acceptedSelection = initialSelection && segmentReady(ride, initialSelection.pickupStop, initialSelection.dropoffStop) ? initialSelection : null;
  const [seats, setSeats] = useState(acceptedSelection?.seats ?? 1);
  const [pickupStop, setPickupStop] = useState<string | null>(() => acceptedSelection?.pickupStop ?? initialSegment(ride).pickup);
  const [dropoffStop, setDropoffStop] = useState<string | null>(() => acceptedSelection?.dropoffStop ?? initialSegment(ride).dropoff);
  // RS-03 А: точное место встречи/высадки по желанию — сервер проверяет радиус от места.
  const [meetPickup, setMeetPickup] = useState<MeetingPointInput | null>(acceptedSelection?.meeting ?? null);
  const [meetDropoff, setMeetDropoff] = useState<MeetingPointInput | null>(acceptedSelection?.alighting ?? null);
  const selectionOwner = useRef(`${ownerGeneration}:${ride.id}`);
  const mounted = useRef(true);
  const bookingAbort = useRef<AbortController | null>(null);
  const pickupReady = segmentReady(ride, pickupStop, dropoffStop);
  // RS-02 А/Б: условия обновились после 409 (цена/места изменились) — показываем и шлём свежие.
  const [freshRide, setFreshRide] = useState<Ride | null>(null);
  const eff = freshRide ?? ride;
  const available = seatsOnSpan(eff, pickupStop, dropoffStop) ?? eff.seats_left;
  const perSeat = segmentPriceRub(eff, pickupStop, dropoffStop) ?? eff.price;
  useEffect(() => {
    mounted.current = true;
    const key = `${ownerGeneration}:${ride.id}`;
    if (selectionOwner.current !== key) {
      selectionOwner.current = key;
      const segment = initialSegment(withoutRideMatch(ride));
      setPickupStop(segment.pickup); setDropoffStop(segment.dropoff);
      bookingInFlight.current = false; setBusy(false);
      setMinor(false); setGuardName(""); setGuardPhone(""); setFrontSeat(false); setError(null);
      setSeats(1); setMeetPickup(null); setMeetDropoff(null); setFreshRide(null);
      setCommand(undefined); setRecoveredBooking(null); setRestoringCommand(false);
    }
    return () => { mounted.current = false; bookingAbort.current?.abort(); };
  }, [ownerGeneration, ride.id]);
  useEffect(() => {
    if (!owner) return;
    const ac = new AbortController();
    const currentOwner = () => !ac.signal.aborted && mounted.current && ownerGeneration === getSessionGeneration();
    const active = () => currentOwner() && !bookingInFlight.current;
    setRestoringCommand(true);
    void readBookingCommand(owner, ride.id).then(async value => {
      if (!active() || !value) return;
      setCommand(current => current ?? value);
      if (value.state !== "unknown" && value.state !== "applied") return;
      const result = await reconcileBookingCommand(owner, value, false, ac.signal);
      if (!active() || result.state === "unseen") return;
      const state = result.state;
      setCommand(current => current?.key === value.key ? { ...value, state } : current);
      if (result.booking) setRecoveredBooking(result.booking);
      else if (result.state === "applied") setError(commandMessage("gone"));
      else if (result.state === "rejected") {
        setError(bookingRefusalMessage(appText, result.code));
        updateRideTerms(result.code, ac.signal);
      }
    }).catch(() => {}).finally(() => { if (currentOwner()) setRestoringCommand(false); });
    return () => ac.abort();
  }, [owner?.namespace, ownerGeneration, ride.id]);
  // Свободных мест стало меньше выбранного (смена участка, поездку обновили) — НЕ срезаем молча
  // (RS-02 В): отправка блокируется, человек сам жмёт «Уменьшить до K» или выбирает другую поездку.

  function openBooking(booking: BookingRow) {
    refreshOwnBookings();
    onClose();
    navigate(`${booking.status === "pending" ? "/booking" : "/trip"}/${booking.id}`);
  }

  function updateRideTerms(code?: string | null, signal?: AbortSignal) {
    if (code !== "price_changed" && code !== "ride_terms_changed") return;
    void fetchRide(ride.id, signal).then(fresh => {
      if (mounted.current && !signal?.aborted && ownerGeneration === getSessionGeneration() &&
          selectionOwner.current === `${ownerGeneration}:${ride.id}`) setFreshRide(fresh);
    }).catch(() => {});
  }

  function commandMessage(kind: BookingCommandProblem["kind"]) {
    switch (kind) {
      case "storage": return appText("Не удалось сохранить запрос на этом устройстве. Попробуй снова.",
        "Был ҡоролмала һорауҙы һаҡлап булманы. Ҡабатлап ҡара.");
      case "full": return appText("Слишком много незавершённых запросов. Сначала проверь предыдущие.",
        "Тамамланмаған һорауҙар күп. Тәүҙә алдағыларын тикшер.");
      case "changed": return appText("Данные изменились. Сначала проверь или закрой предыдущий запрос.",
        "Мәғлүмәт үҙгәрҙе. Тәүҙә алдағы һорауҙы тикшер йәки яп.");
      case "expired": return appText("Срок повтора истёк или время на устройстве изменилось. Проверь или закрой запрос.",
        "Ҡабатлау ваҡыты үтте йәки ҡоролмала ваҡыт үҙгәрҙе. Һорауҙы тикшер йәки яп.");
      case "gone": return appText("Результат бронирования больше недоступен.", "Бронлау һөҙөмтәһе бүтән асыҡ түгел.");
      case "closed": return appText("Запрос закрыт. Бронь по нему не создана.", "Һорау ябылды. Уның буйынса бронь яһалманы.");
      case "rejected": return bookingRefusalMessage(appText);
      default: return appText("Не удалось подтвердить результат. Проверим, что сохранилось.",
        "Һөҙөмтәне раҫлап булманы. Нимә һаҡланғанын тикшерәйек.");
    }
  }

  async function checkCommand(close: boolean) {
    if (!owner || !command || !unresolved || bookingInFlight.current || exit.leaving) return;
    const ac = new AbortController();
    bookingAbort.current = ac;
    bookingInFlight.current = true;
    setBusy(true);
    setError(null);
    const active = () => mounted.current && !ac.signal.aborted && ownerGeneration === getSessionGeneration();
    try {
      const result = await reconcileBookingCommand(owner, command, close, ac.signal);
      if (!active()) return;
      if (result.state === "unseen") {
        setError(appText("Сервер пока не видит результата. Можно проверить снова или закрыть запрос.",
          "Сервер һөҙөмтәне әлегә күрмәй. Ҡабат тикшерергә йәки һорауҙы ябырға була."));
        return;
      }
      const state = result.state;
      setCommand(current => current?.key === command.key ? { ...command, state } : current);
      if (result.booking) openBooking(result.booking);
      else if (result.state === "rejected") {
        setError(bookingRefusalMessage(appText, result.code));
        updateRideTerms(result.code, ac.signal);
      } else setError(commandMessage(result.state === "applied" ? "gone" : "closed"));
    } catch {
      if (active()) setError(commandMessage("unknown"));
    } finally {
      if (bookingAbort.current === ac) bookingInFlight.current = false;
      if (active()) setBusy(false);
    }
  }

  async function book(retry = false) {
    if (exit.leaving) return; // шторка уже уходит (Esc, затемнение) — бронь из неё не отправляем (приёмка PR154)
    if (bookingInFlight.current || !mounted.current || ownerGeneration !== getSessionGeneration() ||
        selectionOwner.current !== `${ownerGeneration}:${ride.id}` || (restoringCommand && !ownBooking)) return;
    if (!isAuthed) {
      const guestBookingNonce = authStatus === "guest"
        ? rememberGuestBookingDraft(ride.id, { seats, pickupStop, dropoffStop, meeting: meetPickup, alighting: meetDropoff }, ownerGeneration)
        : undefined;
      // All guest choices travel only inside the bounded, one-use draft, never independently in history.
      navigate("/login", { state: { from: "/rides/" + ride.id, guestBooking: true,
        ...(guestBookingNonce ? { guestBookingNonce } : {}) } });
      return;
    }
    if (ownBooking) {
      onClose();
      navigate(`${ownBooking.status === "pending" ? "/booking" : "/trip"}/${ownBooking.id}`);
      return;
    }
    if (recoveredBooking && !canRebook) { openBooking(recoveredBooking); return; }
    if (parcelCategory && !getFeatures().parcels) return;
    if (!pickupReady) {
      setError(appText("Выбери места посадки и высадки, чтобы забронировать.", "Бронлау өсөн ултырыу һәм төшөү урындарын һайла."));
      return;
    }
    if (!Number.isInteger(seats) || seats < 1 || seats > available) {
      setError(available < 1
        ? appText("Мест больше нет. Посмотри другие поездки.", "Буш урындар ҡалманы. Башҡа сәфәрҙәрҙе ҡара.")
        : appText(`Осталось мест на твоём участке: ${available}. Выбери меньше.`, `Һинең участкаңда ҡалған урындар: ${available}. Әҙерәк һайла.`));
      return;
    }
    if (minor && (!guardName.trim() || !guardPhone.trim())) {
      setError(
        appText(
          "Укажи взрослого: имя и телефон. Он отвечает за поездку, и водителю есть кому позвонить.",
          "Оло кешене күрһәт: исеме һәм телефоны. Ул сәфәр өсөн яуаплы, йөрөтөүсегә шылтыратырға кем булыр."
        )
      );
      return;
    }
    bookingInFlight.current = true;
    setBusy(true);
    setError(null);
    track("booking_start");
    const ac = new AbortController();
    bookingAbort.current = ac;
    try {
      const b = await createBooking({
        ride_id: ride.id,
        seats,
        minor_passenger: minor || undefined,
        minor_guardian_name: minor ? guardName.trim() : undefined,
        minor_guardian_phone: minor ? guardPhone.trim() : undefined,
        // Только когда просили: обычная бронь уходит тем же телом, что и раньше.
        ...(frontSeat ? { front_seat: true } : {}),
        pickup_stop: pickupStop!,
        dropoff_stop: dropoffStop!,
        // RS-03 А: точки встречи/высадки по желанию; clear не шлём вовсе при создании.
        ...(meetPickup && !meetPickup.clear ? { meeting: meetPickup } : {}),
        ...(meetDropoff && !meetDropoff.clear ? { alighting: meetDropoff } : {}),
        // RS-02 А/Б: цена участка и версия условий, которые человек видел на экране.
        expected_price: perSeat,
        expected_terms_version: eff.terms_version,
      }, ac.signal, owner, command, retry);
      if (!mounted.current || ac.signal.aborted || ownerGeneration !== getSessionGeneration()) return;
      track("booking_done");
      openBooking(b);
    } catch (failure) {
      if (!mounted.current || ac.signal.aborted || ownerGeneration !== getSessionGeneration()) return;
      if (failure instanceof BookingCommandProblem && failure.command) setCommand(failure.command);
      const e = failure instanceof BookingCommandProblem ? failure.original : failure;
      // 409 «у тебя уже есть бронь» (другое число мест, остановка, подросток): сервер ответил, сеть есть —
      // перечитываем свои брони, и кнопка сама станет «Открыть поездку» (приёмка #189).
      if (e instanceof ApiError && e.status === 409) refreshOwnBookings();
      // Условия уехали (цена/места/маршрут): перечитываем поездку — следующее нажатие уже
      // подтверждает НОВЫЕ условия, а серверный текст объясняет, что именно поменялось (RS-02 В).
      const rejectionCode = failure instanceof BookingCommandProblem ? failure.code : e instanceof ApiError ? e.code : null;
      updateRideTerms(rejectionCode, ac.signal);
      const msg =
        e instanceof ApiError && e.message ? e.message
          : failure instanceof BookingCommandProblem && failure.kind === "rejected" ? bookingRefusalMessage(appText, rejectionCode)
          : failure instanceof BookingCommandProblem ? commandMessage(failure.kind)
          : appText("Не получилось забронировать. Попробуй снова.", "Бронларға булманы. Ҡабат ҡара."); // DRAFT
      setError(msg);
    } finally {
      if (bookingAbort.current === ac) bookingInFlight.current = false;
      if (mounted.current && !ac.signal.aborted && ownerGeneration === getSessionGeneration()) setBusy(false);
    }
  }

  return exit.portal(
    <div className={exit.backdropClass} onClick={() => { if (!bookingInFlight.current) exit.close(); }} role="presentation">
      <div
        ref={dialogRef}
        className="sheet"
        onClick={(e) => e.stopPropagation()}
        onAnimationEnd={exit.onAnimationEnd}
        {...exit.dialogProps}
        aria-label={appText("Поездка", "Сәфәр")}
      >
        <div className="sheet__grip" aria-hidden />
        <button type="button" className="btn-ghost" onClick={exit.close} disabled={busy}>
          {appText("Закрыть", "Ябырға")}
        </button>
        {parcelCategory && !features.parcels && !ownBooking && !unresolved && !bookingInFlight.current ? <ComingSoon kind="delivery" variant="inline" /> : <>
        <p className="list-row__sub">{appText("Рейс водителя", "Йөрөтөүсенең сәфәре")}</p>
        <div className="ride-card__route" style={{ fontSize: "var(--font-heading)" }}>
          <span>{ride.from_city}</span>
          <span className="ride-card__arrow">
            <IconArrow size={20} />
          </span>
          <span>{ride.to_city}</span>
        </div>

        <div className="ride-card__meta" style={{ marginTop: 12 }}>
          <span>{formatWhen(ride.depart_at, ru)}</span>
          <span>
            <b>{available}</b> {appText("мест", "урын")}
          </span>
          {ride.women_only && (
            <span className="badge badge--woman">
              <YuWomenOnly size={13} className="amenity-ic" />
              {appText("Только для женщин", "Тик ҡатын-ҡыҙ өсөн")}
            </span>
          )}
          {ride.baggage && (
            <span className="badge badge--mint">
              <YuLuggage size={13} className="amenity-ic" />
              {appText("Багаж", "Багаж")}
            </span>
          )}
          {ride.child_seat && (
            <span className="badge badge--mint">
              <YuChildSeat size={13} className="amenity-ic" />
              {appText("Детское кресло", "Бала урыны")}
            </span>
          )}
          <SeatBadges ride={ride} ownBooking={ownBooking} />
        </div>

        {ride.comment && <p className="sheet__comment">{ride.comment}</p>}
        {ownBooking
          ? <BookingSegmentNote pickupStop={ownBooking.pickup_stop} dropoffStop={ownBooking.dropoff_stop} rideHasStops={ownBooking.ride_has_stops} from={ride.from_city} to={ride.to_city} />
          : <>
            <PickupStopChoice ride={ride} picked={pickupStop} disabled={busy} onPick={place => { setPickupStop(place); setDropoffStop(current => keepDropoffAfterPickup(ride, place, current)); setError(null); }} />
            <DropoffStopChoice ride={ride} pickup={pickupStop} picked={dropoffStop} disabled={busy} onPick={place => { setDropoffStop(place); setError(null); }} />
            {/* RS-03 А: точное место по желанию — водитель увидит после подтверждения. */}
            <MeetingPointPicker
              city={pickupStop || ride.from_city}
              title={appText("Где тебя забрать?", "Һине ҡайҙан алырға?")}
              value={meetPickup} disabled={busy} onChange={setMeetPickup} />
            <MeetingPointPicker
              city={dropoffStop || ride.to_city}
              title={appText("Где тебя высадить?", "Һине ҡайҙа төшөрөргә?")}
              value={meetDropoff} disabled={busy} onChange={setMeetDropoff} />
            <p className="sheet__note">{appText("Водитель увидит, где тебя забрать и высадить.", "Йөрөтөүсе һине ҡайҙан алырға һәм ҡайҙа төшөрөргә икәнен күрер.")}</p>
          </>}

        <div className="sheet__driver">
          <button
            type="button"
            className="sheet__driver-link"
            onClick={() => navigate(`/drivers/${ride.driver_id}`)}
            aria-label={appText("Открыть профиль водителя", "Йөрөтөүсе профилен асыу")}
          >
            <span className="ride-card__avatar" aria-hidden>
              {(ride.driver_name || "?").trim().charAt(0).toUpperCase()}
            </span>
            <div style={{ minWidth: 0, flex: 1 }}>
              <div className="ride-card__driver-name">
                {ride.driver_name}
                {ride.driver_verified && (
                  <span className="badge badge--mint" style={{ marginLeft: 6 }}>
                    <IconCheck size={14} /> {appText("Проверен", "Тикшерелгән")}
                  </span>
                )}
              </div>
              <div className="ride-card__driver-sub">
                <YuStar size={13} className="star" /> {rideDriverRating(ride)?.toFixed(1) ?? appText("Нет оценок", "Баһалар юҡ")}
                {ride.driver_car ? ` · ${ride.driver_car}` : ""}
                <span className="sheet__driver-more">
                  {" · "}
                  {appText("профиль", "профиль")}
                </span>
              </div>
            </div>
          </button>
          <div className="ride-card__price">
            {priceLabel(perSeat, ru)}
            {(eff.stop_prices?.length ?? 0) > 0 && (
              <div className="sheet__note">{appText("за место на твоём участке", "бер урын — һинең участкаңда")}</div>
            )}
          </div>
        </div>

        {!ownBooking && <>
          {seats > available && available >= 1 ? (
            // RS-02 В: мест стало меньше выбранного — отправка заблокирована (не clamp молча);
            // человек сам решает: уменьшить или посмотреть другие поездки.
            <div className="sheet__note" role="alert">
              {appText(`Свободных мест осталось ${available} — уменьшите число или посмотрите другие поездки.`,
                       `Буш урындар ${available} ҡалды — һанын кәмет йәки башҡа сәфәрҙәрҙе ҡара.`)}
              <button type="button" className="btn-ghost" style={{ minHeight: 48 }} onClick={() => setSeats(available)}>
                {appText(`Уменьшить до ${available}`, `${available} тиклем кәметеү`)}
              </button>
            </div>
          ) : (
            <label className="field sheet__seats">
              <span className="field__label">{appText("Количество мест", "Урындар һаны")}</span>
              <select className="field__input" style={{ minHeight: 48 }} value={seats} disabled={busy || available < 1}
                onChange={e => { setSeats(Number(e.target.value)); setError(null); }}>
                {Array.from({ length: Math.max(0, available) }, (_, index) => index + 1).map(count => <option key={count} value={count}>{count}</option>)}
              </select>
            </label>
          )}
          <p className="sheet__note" aria-live="polite">
            {available > 0
              ? appText("Итого: ", "Бөтәһе: ") + priceLabel(perSeat * seats, ru)
              : appText("На твоём участке мест нет. Посмотри другие поездки.", "Һинең участкаңда урын юҡ. Башҡа сәфәрҙәрҙе ҡара.")}
          </p>
        </>}

        <FrontSeatChoice
          ride={ride}
          ownBooking={ownBooking}
          checked={frontSeat}
          onChange={(v) => {
            setFrontSeat(v);
            setError(null);
          }}
        />

        {/* Подросток за рулём чужой машины — отдельный разговор, а не галочка мелким шрифтом.
            Водитель, который таких не берёт, сказал это заранее: тогда мы не предлагаем
            отметку вовсе и объясняем почему — иначе бронь отлетит уже после нажатия. */}
        {ride.no_minors ? (
          <p className="sheet__note">
            {appText(
              "Водитель не берёт пассажиров младше 18 без взрослого рядом.",
              "Йөрөтөүсе оло кешеһеҙ 18-ҙән кесе юлсыларҙы алмай."
            )}
          </p>
        ) : (
          <>
            <label className="list-row list-row--check" style={{ marginTop: 8 }}>
              <div className="list-row__main">
                <div className="list-row__title">
                  {appText("Поедет пассажир младше 18", "18-ҙән кесе юлсы бара")}
                </div>
                <div className="list-row__sub">
                  {appText(
                    "Нужен взрослый на связи: водителю есть кому позвонить, если что-то пойдёт не так.",
                    "Бәйләнештә оло кеше кәрәк: берәй хәл булһа, йөрөтөүсегә шылтыратырға кем булыр."
                  )}
                </div>
              </div>
              <input
                type="checkbox"
                className="checkbox"
                checked={minor}
                onChange={(e) => {
                  setMinor(e.target.checked);
                  setError(null);
                }}
                aria-label={appText("Пассажир младше 18", "Юлсы 18-ҙән кесе")}
              />
            </label>
            {minor && (
              <>
                <label className="field">
                  <span className="field__label">{appText("Взрослый: имя", "Оло кеше: исеме")}</span>
                  <input
                    className="field__input"
                    value={guardName}
                    onChange={(e) => setGuardName(e.target.value.slice(0, 120))}
                    placeholder={appText("Мама, Гульнара", "Әсәһе, Гөлнара")}
                    autoComplete="name"
                  />
                </label>
                <label className="field">
                  <span className="field__label">{appText("Его телефон", "Уның телефоны")}</span>
                  <input
                    className="field__input"
                    inputMode="tel"
                    value={guardPhone}
                    onChange={(e) => setGuardPhone(e.target.value.slice(0, 32))}
                    placeholder="+7 …"
                    autoComplete="tel"
                  />
                  <span className="field__hint">
                    {appText(
                      "Номер увидит только этот водитель и только пока бронь жива.",
                      "Номерҙы тик ошо йөрөтөүсе һәм бронь йәшәгәндә генә күрә."
                    )}
                  </span>
                </label>
              </>
            )}
          </>
        )}

        {error && <div className="auth__error">{error}</div>}
        {canRebook && recoveredBooking && (
          <button type="button" className="btn-ghost" onClick={() => openBooking(recoveredBooking)}>
            {appText("Открыть отменённую бронь", "Кире алынған бронде асыу")}
          </button>
        )}
        {unresolved && (
          <div className="sheet__note">
            <p role="status">{commandMessage("unknown")}</p>
            <p>{appText("Если бронь уже создана, покажем её.", "Бронь яһалған булһа, уны күрһәтербеҙ.")}</p>
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => checkCommand(false)}>
              {appText("Проверить запрос", "Һорауҙы тикшереү")}
            </button>
            <button type="button" className="btn-ghost" disabled={busy} onClick={() => checkCommand(true)}>
              {appText("Закрыть запрос", "Һорауҙы ябыу")}
            </button>
          </div>
        )}

        <button
          type="button"
          className="btn-primary sheet__cta"
          onClick={() => book(unresolved && !ownBooking)}
          disabled={busy || (isAuthed && !ownBooking && (restoringCommand ||
            ((!recoveredBooking || canRebook) && (!pickupReady || seats < 1 || seats > available))))}
        >
          {(busy || (restoringCommand && !ownBooking))
            ? (unresolved || restoringCommand) ? appText("Проверяем запрос…", "Һорауҙы тикшерәбеҙ…") : appText("Бронируем…", "Бронлайбыҙ…")
            : isAuthed
              ? ownBooking || (recoveredBooking && !canRebook)
                ? appText("Открыть поездку", "Сәфәрҙе асыу")
                : unresolved
                  ? appText("Повторить тот же запрос", "Шул уҡ һорауҙы ҡабатлау")
                  : canRebook
                    ? appText("Забронировать снова", "Яңынан бронларға")
                : seats > 1
                  ? appText(`Забронировать ${seats} ${pluralRu(seats, "место", "места", "мест")}`, `${seats} урын бронларға`)
                  : appText("Забронировать место", "Урын бронларға")
              : appText("Войти и забронировать", "Инеп бронларға")}
        </button>
        <p className="sheet__note">
          {appText(
            "Телефон и точное место встречи откроются после подтверждения водителем.",
            "Телефон һәм осрашыу урыны йөрөтөүсе раҫлағас асыла."
          )}
        </p>
        </>}
      </div>
    </div>
  );
}
