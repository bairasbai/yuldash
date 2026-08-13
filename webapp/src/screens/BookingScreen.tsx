// ================================================================
//  Детали брони (GET /bookings/{id}/details). Требует вход (участник).
//  Телефон и точная точка встречи открываются только после
//  подтверждения водителем (contact_unlocked). Карта концов маршрута.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useLang } from "../i18n/lang";
import {
  fetchBookingDetails,
  cancelBooking,
  type BookingDetails,
} from "../api/bookings";
import { LoadingList, ErrorState } from "../components/States";
import YandexMap from "../components/YandexMap";
import { SubHeader } from "./ConsentsScreen";
import { StatusPill } from "../components/StatusPill";
import { IconArrow, IconPhone, IconPin, IconCheck, IconLock } from "../components/Icons";
import { formatWhen, priceLabel, payMethodLabel } from "../utils/format";
import { PartnerAdSlot } from "../components/PartnerAd";

export default function BookingScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const bookingId = Number(id);

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [d, setD] = useState<BookingDetails | null>(null);
  const [cancelling, setCancelling] = useState(false);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!bookingId) {
        setStatus("error");
        return;
      }
      setStatus("loading");
      fetchBookingDetails(bookingId, signal)
        .then((res) => {
          setD(res);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [bookingId]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function onCancel() {
    if (!bookingId || cancelling) return;
    if (!window.confirm(appText("Отменить бронь?", "Бронды кире алырғамы?"))) return;
    setCancelling(true);
    try {
      await cancelBooking(bookingId);
      load();
    } catch {
      /* покажем при перезагрузке */
    } finally {
      setCancelling(false);
    }
  }

  return (
    <>
      <SubHeader
        title={appText("Детали брони", "Бронь тәфсиләте")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}
      {status === "ready" && d && (
        <div className="trip">
          <div className="trip__routecard">
            <div className="ride-card__route">
              <span>{d.from_city}</span>
              <span className="ride-card__arrow">
                <IconArrow size={20} />
              </span>
              <span>{d.to_city}</span>
            </div>
            <div className="trip__meta">
              <span>{formatWhen(d.depart_at, ru)}</span>
              <span>
                <b>{d.seats}</b> {appText("мест", "урын")}
              </span>
              <StatusPill status={d.status} />
            </div>
          </div>

          {d.from_lat != null && d.to_lat != null && (
            <div className="home-map" style={{ marginTop: 12 }}>
              <YandexMap
                from={{ lat: d.from_lat, lng: d.from_lng ?? 0 }}
                to={{ lat: d.to_lat, lng: d.to_lng ?? 0 }}
                route
                height={200}
              />
            </div>
          )}

          <div className="info-list">
            <div className="info-row">
              <span className="info-row__k">{appText("Водитель", "Водитель")}</span>
              <span className="info-row__v">
                {d.driver_name}
                {d.driver_verified && (
                  <span className="badge badge--mint" style={{ marginLeft: 6 }}>
                    <IconCheck size={14} /> {appText("Проверен", "Тикшерелгән")}
                  </span>
                )}
              </span>
            </div>
            {(d.driver_car || d.driver_car_color) && (
              <div className="info-row">
                <span className="info-row__k">{appText("Машина", "Машина")}</span>
                <span className="info-row__v">
                  {[d.driver_car_color, d.driver_car].filter(Boolean).join(" ")}
                </span>
              </div>
            )}
            {/* Госномер отдельной строкой: его сверяют глазами у машины, а не вычитывают
                из описания. Приходит только после подтверждения брони. */}
            {d.driver_plate && (
              <div className="info-row">
                <span className="info-row__k">{appText("Госномер", "Дәүләт номеры")}</span>
                <span className="info-row__v">{d.driver_plate}</span>
              </div>
            )}
            <div className="info-row">
              <span className="info-row__k">{appText("Оплата", "Түләү")}</span>
              <span className="info-row__v">
                {payMethodLabel(d.pay_method, ru)}
                {d.pay_amount != null ? ` · ${priceLabel(d.pay_amount, ru)}` : ` · ${priceLabel(d.price, ru)}`}
              </span>
            </div>
          </div>

          {d.contact_unlocked ? (
            <div className="unlock-card">
              {d.driver_phone && (
                <a className="btn-primary" href={`tel:${d.driver_phone}`} style={{ display: "flex", width: "100%", gap: 8 }}>
                  <IconPhone size={18} /> {appText("Позвонить водителю", "Водителгә шылтыратырға")}
                </a>
              )}
              {d.pickup && (
                <p className="unlock-card__point" style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <IconPin size={18} /> {appText("Место встречи:", "Осрашыу урыны:")} {d.pickup}
                </p>
              )}
            </div>
          ) : (
            <div className="auth__note" style={{ marginTop: 14 }}>
              <IconLock size={15} />{" "}
              {appText(
                "Телефон и точное место встречи откроются, как только водитель подтвердит поездку.",
                "Телефон һәм осрашыу урыны водитель сәфәрҙе раҫлағас асыла."
              )}
            </div>
          )}

          {d.status !== "cancelled" && d.status !== "done" && (
            <>
              <button
                type="button"
                className="btn-primary submit-btn"
                onClick={() => navigate(`/trip/${bookingId}`)}
              >
                {appText("Открыть поездку", "Сәфәрҙе асырға")}
              </button>
              <button
                type="button"
                className="logout-btn"
                onClick={onCancel}
                disabled={cancelling}
              >
                {cancelling ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить бронь", "Бронды кире алырға")}
              </button>
            </>
          )}

          <PartnerAdSlot placement="tripDetails" />
        </div>
      )}
    </>
  );
}
