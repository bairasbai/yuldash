// ================================================================
//  Нижняя шторка с деталями поездки + бронирование.
//  Открывается тапом по поездке на витрине (Home). Гость → на вход.
//  Бронь: POST /bookings → переход на активную поездку /trip/{id}.
// ================================================================
import { useEffect, useState } from "react";
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
  const [error, setError] = useState<string | null>(null);

  // Открыли карточку поездки — заинтересовался конкретной поездкой.
  useEffect(() => {
    track("open_ride", { category: ride.category ?? "regular" });
  }, [ride.category]);

  async function book() {
    if (!isAuthed) {
      navigate("/login", { state: { from: "/map" } });
      return;
    }
    setBusy(true);
    setError(null);
    track("booking_start");
    try {
      const b = await createBooking({ ride_id: ride.id, seats: 1 });
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
      setBusy(false);
    }
  }

  return (
    <div className="sheet-backdrop" onClick={onClose} role="presentation">
      <div
        className="sheet"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="sheet__grip" aria-hidden />
        <div className="ride-card__route" style={{ fontSize: 20 }}>
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
            aria-label={appText("Открыть профиль водителя", "Водитель профилен асыу")}
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
            "Телефон һәм осрашыу урыны водитель раҫлағас асыла."
          )}
        </p>
      </div>
    </div>
  );
}
