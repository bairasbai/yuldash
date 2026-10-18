// ================================================================
//  Детали брони (GET /bookings/{id}/details). Требует вход (участник).
//  Телефон и точная точка встречи открываются только после
//  подтверждения водителем (contact_unlocked). Карта концов маршрута.
//
//  Экран — для ПАССАЖИРА. Водитель этой поездки, открывший ссылку на бронь (пуш «новая
//  бронь»), уходит туда, где с ней можно что-то сделать: кабинет водителя («Подтвердить»)
//  или экран поездки — см. utils/bookingLink.ts (зеркало Android BookingLinkDestination.kt).
//
//  Отмена — как в Android (BookingActiveTripScreen.kt): здесь отменяют только бронь, которая
//  ЖДЁТ ответа водителя, и только пассажир — через своё окно на двух языках, а не системное
//  окно браузера. Подтверждённую поездку отменяют на экране поездки, с причиной; после
//  «Я сел» отмены нет вовсе (паритет 16.10.2026).
// ================================================================
import { useCallback, useEffect, useRef, useState } from "react";
import { Navigate, useNavigate, useParams } from "react-router-dom";
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
import AlertDialog from "../components/AlertDialog";

import { ApiError } from "../api/client";
import { track } from "../analytics";
import { useRefreshOwnBookings } from "../booking/OwnBookingsProvider";
import { bookingLinkPath } from "../utils/bookingLink";
import { PickupStopNote } from "../components/PickupStopChoice";
import { useVisibleInterval } from "../utils/useVisibleInterval";
export default function BookingScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();
  const { id } = useParams();
  const bookingId = Number(id);
  const refreshOwnBookings = useRefreshOwnBookings();

  const [status, setStatus] = useState<"loading" | "error" | "ready">("loading");
  const [d, setD] = useState<BookingDetails | null>(null);
  const [cancelling, setCancelling] = useState(false);
  /** Открыто окно «Отменить бронь?». */
  const [confirmCancel, setConfirmCancel] = useState(false);
  /** Отмена не прошла — сказать обязательно: иначе место так и числится занятым. */
  const [cancelNote, setCancelNote] = useState("");
  const requestVersion = useRef(0);
  const activeRequest = useRef<AbortController | null>(null);
  const latest = useRef({ bookingId, status, d, cancelling });
  latest.current = { bookingId, status, d, cancelling };

  const abortRequest = useCallback(() => {
    requestVersion.current++;
    activeRequest.current?.abort();
    activeRequest.current = null;
  }, []);

  const load = useCallback(
    (signal?: AbortSignal) => {
      if (!bookingId) {
        setStatus("error");
        return;
      }
      abortRequest();
      const version = requestVersion.current;
      setStatus("loading");
      setD(null);
      fetchBookingDetails(bookingId, signal)
        .then((res) => {
          if (signal?.aborted || version !== requestVersion.current) return;
          setD(res);
          setStatus("ready");
        })
        .catch((e) => {
          if (signal?.aborted || version !== requestVersion.current || e?.name === "AbortError") return;
          setStatus("error");
        });
    },
    [bookingId, abortRequest]
  );

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => { ac.abort(); abortRequest(); };
  }, [load]);

  const pending = status === "ready" && d?.status === "pending" && d.booking_id === bookingId && !cancelling;
  const refreshPending = useCallback(() => {
    const current = latest.current;
    if (document.visibilityState !== "visible" || current.bookingId !== bookingId || current.status !== "ready"
      || current.d?.status !== "pending" || current.cancelling || activeRequest.current) return;
    const controller = new AbortController();
    activeRequest.current = controller;
    const version = ++requestVersion.current;
    fetchBookingDetails(bookingId, controller.signal).then(res => {
      if (controller.signal.aborted || version !== requestVersion.current) return;
      setD(res);
      if (res.status !== "pending") refreshOwnBookings();
    }).catch(() => { /* A quiet retry must not hide already loaded details. */ }).finally(() => {
      if (activeRequest.current === controller) activeRequest.current = null;
    });
  }, [bookingId, refreshOwnBookings]);
  useVisibleInterval(10000, refreshPending, pending);
  useEffect(() => {
    if (!pending) return;
    const onVisibility = () => { if (document.visibilityState !== "visible") abortRequest(); };
    document.addEventListener("visibilitychange", onVisibility);
    return () => { document.removeEventListener("visibilitychange", onVisibility); abortRequest(); };
  }, [bookingId, pending, abortRequest]);

  async function onCancel() {
    if (!bookingId || cancelling) return;
    setConfirmCancel(false);
    abortRequest();
    setCancelling(true);
    setCancelNote("");
    try {
      await cancelBooking(bookingId);
      refreshOwnBookings();
      track("booking_cancel");
      load();
    } catch (e) {
      // Раньше тут было «покажем при перезагрузке» — но перезагрузка идёт только
      // при успехе. Человек видел ту же бронь и не понимал: отменилась или нет.
      // А водитель тем временем держит для него место.
      setCancelNote(
        e instanceof ApiError && e.message
          ? e.message
          : appText(
              "Не получилось отменить бронь. Проверь связь и попробуй ещё раз.",
              "Бронде кире алып булманы. Бәйләнеште тикшереп ҡабатла."
            )
      );
    } finally {
      setCancelling(false);
    }
  }

  // Водитель по ссылке на бронь — не на пассажирскую «Бронь» с «Отменить бронь».
  const driverPath = status === "ready" && d ? bookingLinkPath(d.role, d.status, bookingId) : null;
  if (driverPath) return <Navigate to={driverPath} replace />;

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
              <span className="info-row__k">{appText("Водитель", "Йөрөтөүсе")}</span>
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
            {/* Переднее место, если просили: пассажир помнит, водитель видит в той же брони. */}
            {(d.pickup_stop != null || d.ride_has_stops) && <div className="info-row">
              <PickupStopNote pickupStop={d.pickup_stop} rideHasStops={d.ride_has_stops} from={d.from_city} />
            </div>}
            {d.front_seat && (
              <div className="info-row">
                <span className="info-row__k">{appText("Место", "Урын")}</span>
                <span className="info-row__v">
                  {d.role === "driver"
                    ? appText("Пассажир просит переднее место", "Юлсы алғы урынды һорай")
                    : appText("Переднее место", "Алғы урын")}
                </span>
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
                  <IconPhone size={18} /> {appText("Позвонить водителю", "Йөрөтөүсегә шылтыратыу")}
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
                "Телефон һәм осрашыу урыны йөрөтөүсе сәфәрҙе раҫлағас асыла."
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
              {/* Пока водитель решает — бронь можно снять. Подтверждённую отменяют на экране
                  поездки (с причиной), а «в пути» не отменяют вовсе. */}
              {d.role === "passenger" && d.status === "pending" && (
                <button
                  type="button"
                  className="logout-btn"
                  onClick={() => setConfirmCancel(true)}
                  disabled={cancelling}
                >
                  {cancelling ? appText("Отменяем…", "Кире алабыҙ…") : appText("Отменить бронь", "Бронде кире алырға")}
                </button>
              )}
              {cancelNote && (
                <div className="notice" role="status">
                  {cancelNote}
                </div>
              )}
            </>
          )}

          {confirmCancel && (
            <AlertDialog
              title={appText("Отменить бронь?", "Бронде кире алырғамы?")}
              text={appText(
                "Место освободится, а водитель получит уведомление.",
                "Урын бушаясаҡ, ә йөрөтөүсе хәбәр аласаҡ."
              )}
              onClose={() => setConfirmCancel(false)}
              confirm={{ label: appText("Да, отменить", "Эйе, кире алырға"), tone: "danger", onClick: () => void onCancel() }}
              dismiss={{ label: appText("Оставить бронь", "Бронде ҡалдырырға"), onClick: () => setConfirmCancel(false) }}
            />
          )}

          <PartnerAdSlot placement="tripDetails" />
        </div>
      )}
    </>
  );
}
