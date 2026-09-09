// ================================================================
//  Нижняя шторка с деталями поездки + бронирование.
//  Открывается тапом по поездке на витрине (Home). Гость → на вход.
//  Бронь: POST /bookings → переход на активную поездку /trip/{id}.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthProvider";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { createBooking } from "../api/bookings";
import { track } from "../analytics";
import type { Ride } from "../api/rides";
import { formatWhen, priceLabel } from "../utils/format";
import { IconArrow, IconCheck } from "./Icons";
import { YuStar, YuWomenOnly, YuLuggage, YuChildSeat } from "./BrandIcons";

export default function RideSheet({
  ride,
  onClose,
}: {
  ride: Ride;
  onClose: () => void;
}) {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { isAuthed } = useAuth();
  const [busy, setBusy] = useState(false);
  const bookingInFlight = useRef(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialogRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    return () => previous?.focus();
  }, []);

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

  async function book() {
    if (bookingInFlight.current) return;
    if (!isAuthed) {
      navigate("/login", { state: { from: `/rides/${ride.id}` } });
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
    try {
      const b = await createBooking({
        ride_id: ride.id,
        seats: 1,
        minor_passenger: minor || undefined,
        minor_guardian_name: minor ? guardName.trim() : undefined,
        minor_guardian_phone: minor ? guardPhone.trim() : undefined,
      });
      track("booking_done");
      onClose();
      navigate(`/trip/${b.id}`);
    } catch (e) {
      const msg =
        e instanceof ApiError && e.message
          ? e.message
          : appText("Не получилось забронировать. Попробуй снова.", "Бронларға булманы. Ҡабат ҡара."); // DRAFT
      setError(msg);
    } finally {
      bookingInFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <div className="sheet-backdrop" onClick={() => { if (!bookingInFlight.current) onClose(); }} role="presentation">
      <div
        ref={dialogRef}
        className="sheet"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={appText("Поездка", "Сәфәр")}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !bookingInFlight.current) onClose();
          if (event.key !== "Tab") return;
          const controls = dialogRef.current?.querySelectorAll<HTMLElement>("button:not(:disabled), input:not(:disabled), a[href]");
          if (!controls?.length) return;
          const first = controls[0], last = controls[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
          else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
        }}
      >
        <div className="sheet__grip" aria-hidden />
        <button type="button" className="btn-ghost" onClick={onClose} disabled={busy}>
          {appText("Закрыть", "Ябырға")}
        </button>
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
            <b>{ride.seats_left}</b> {appText("мест", "урын")}
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
        </div>

        {ride.comment && <p className="sheet__comment">{ride.comment}</p>}

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
                <YuStar size={13} className="star" /> {ride.driver_rating?.toFixed(1) ?? "—"}
                {ride.driver_car ? ` · ${ride.driver_car}` : ""}
                <span className="sheet__driver-more">
                  {" · "}
                  {appText("профиль", "профиль")}
                </span>
              </div>
            </div>
          </button>
          <div className="ride-card__price">{priceLabel(ride.price, ru)}</div>
        </div>

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
          disabled={busy}
        >
          {busy
            ? appText("Бронируем…", "Бронлайбыҙ…")
            : isAuthed
              ? appText("Забронировать место", "Урын бронларға")
              : appText("Войти и забронировать", "Инеп бронларға")}
        </button>
        <p className="sheet__note">
          {appText(
            "Телефон и точное место встречи откроются после подтверждения водителем.",
            "Телефон һәм осрашыу урыны йөрөтөүсе раҫлағас асыла."
          )}
        </p>
      </div>
    </div>
  );
}
