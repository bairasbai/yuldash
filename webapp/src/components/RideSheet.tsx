import { rideDriverRating } from "../utils/rideDriverRating";
// ================================================================
//  Нижняя шторка с деталями поездки + бронирование.
//  Открывается тапом по поездке на витрине (Home). Гость → на вход.
//  Бронь: POST /bookings → pending открывает /booking/{id}, иначе /trip/{id}.
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
import { createBooking, type MeetingPointInput } from "../api/bookings";
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
  const { isAuthed, status: authStatus } = useAuth();
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
    }
    return () => { mounted.current = false; bookingAbort.current?.abort(); };
  }, [ownerGeneration, ride.id]);
  // Свободных мест стало меньше выбранного (смена участка, поездку обновили) — НЕ срезаем молча
  // (RS-02 В): отправка блокируется, человек сам жмёт «Уменьшить до K» или выбирает другую поездку.

  async function book() {
    if (exit.leaving) return; // шторка уже уходит (Esc, затемнение) — бронь из неё не отправляем (приёмка PR154)
    if (bookingInFlight.current || !mounted.current || ownerGeneration !== getSessionGeneration() || selectionOwner.current !== `${ownerGeneration}:${ride.id}`) return;
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
      }, ac.signal);
      if (!mounted.current || ac.signal.aborted || ownerGeneration !== getSessionGeneration()) return;
      refreshOwnBookings();
      track("booking_done");
      onClose();
      navigate(`${b.status === "pending" ? "/booking" : "/trip"}/${b.id}`);
    } catch (e) {
      if (!mounted.current || ac.signal.aborted || ownerGeneration !== getSessionGeneration()) return;
      // 409 «у тебя уже есть бронь» (другое число мест, остановка, подросток): сервер ответил, сеть есть —
      // перечитываем свои брони, и кнопка сама станет «Открыть поездку» (приёмка #189).
      if (e instanceof ApiError && e.status === 409) refreshOwnBookings();
      // Условия уехали (цена/места/маршрут): перечитываем поездку — следующее нажатие уже
      // подтверждает НОВЫЕ условия, а серверный текст объясняет, что именно поменялось (RS-02 В).
      if (e instanceof ApiError && (e.code === "price_changed" || e.code === "ride_terms_changed")) {
        fetchRide(ride.id).then((fresh) => { if (mounted.current) setFreshRide(fresh); }).catch(() => {});
      }
      const msg =
        e instanceof ApiError && e.message
          ? e.message
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
        {parcelCategory && !features.parcels && !ownBooking && !bookingInFlight.current ? <ComingSoon kind="delivery" variant="inline" /> : <>
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

        <button
          type="button"
          className="btn-primary sheet__cta"
          onClick={book}
          disabled={busy || (isAuthed && !ownBooking && (!pickupReady || seats < 1 || seats > available))}
        >
          {busy
            ? appText("Бронируем…", "Бронлайбыҙ…")
            : isAuthed
              ? ownBooking
                ? appText("Открыть поездку", "Сәфәрҙе асыу")
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
