import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchMyBookings, type MyBooking } from "../api/bookings";
import { fetchDriverRides } from "../api/driver";
import { useAuth } from "../auth/AuthProvider";
import ScreenHeader from "../components/ScreenHeader";
import MyTripCard, { bookingStatusAllowsActiveTrip } from "../components/MyTripCard";
import { LoadingList } from "../components/States";
import PartnerAdCard, { usePartnerAds } from "../components/PartnerAd";
import { IconCar, IconRoute, IconShield, IconSignal } from "../components/Icons";

type Segment = "active" | "history" | "all";
type ViewState = "loading" | "error" | "ready";

const ACTIVE = new Set(["pending", "confirmed", "onboard"]);
const HISTORY = new Set(["done", "cancelled"]);

/**
 * Вкладка «Поездки» — зеркало Android RidesScreen («Мои поездки»).
 *
 * Здесь только БРОНИ — то, куда человек едет сам. Опубликованные им маршруты живут в кабинете
 * водителя, и пустой экран обязан уметь туда отправить. Лента попуток рядом — на вкладке
 * «Карта», как в приложении; по прямой ссылке она осталась на /rides/feed.
 */
export default function MyRidesScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();
  const { status: authStatus } = useAuth();
  const [segment, setSegment] = useState<Segment>("active");
  const [state, setState] = useState<ViewState>("loading");
  const [bookings, setBookings] = useState<MyBooking[]>([]);
  const [myDriverRides, setMyDriverRides] = useState(0);
  const ads = usePartnerAds("ridesList");
  const inlineAd = ads[0];

  const load = useCallback((signal?: AbortSignal) => {
    setState("loading");
    fetchMyBookings(signal)
      .then((rows) => {
        setBookings(rows);
        setState("ready");
      })
      .catch((error) => {
        if (signal?.aborted || error?.name === "AbortError") return;
        // 401 (не вошёл) — не ошибка сети: просто пусто, как в Android.
        if (error instanceof ApiError && error.status === 401) {
          setBookings([]);
          setState("ready");
          return;
        }
        setState("error");
      });
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    return () => controller.abort();
  }, [load]);

  // Сколько у человека своих маршрутов — спрашиваем только когда броней нет: это единственное
  // место, где ответ на что-то влияет.
  useEffect(() => {
    if (state !== "ready" || bookings.length > 0 || authStatus !== "authed") return;
    const controller = new AbortController();
    fetchDriverRides("active", controller.signal)
      .then((rides) => setMyDriverRides(rides.length))
      .catch(() => setMyDriverRides(0));
    return () => controller.abort();
  }, [state, bookings.length, authStatus]);

  const segments: { key: Segment; label: string }[] = [
    { key: "active", label: appText("Активные", "Актив") },
    { key: "history", label: appText("История", "Тарих") },
    { key: "all", label: appText("Все", "Бөтәһе") },
  ];
  const visible = bookings.filter((b) =>
    segment === "history" ? HISTORY.has(b.status) : segment === "all" ? true : ACTIVE.has(b.status)
  );

  return (
    <>
      <ScreenHeader title={appText("Мои поездки", "Минең сәфәрҙәр")} />

      <div className="segmented" role="tablist" aria-label={appText("Какие поездки показать", "Ниндәй сәфәрҙәрҙе күрһәтергә")}>
        {segments.map((s) => (
          <button
            key={s.key}
            type="button"
            role="tab"
            aria-selected={segment === s.key}
            className={"segmented__item" + (segment === s.key ? " is-selected" : "")}
            onClick={() => setSegment(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {state === "loading" && <LoadingList count={3} />}

      {state === "error" && (
        <div className="state" role="alert">
          <div className="state__icon state__icon--danger"><IconSignal size={30} /></div>
          <h2>{appText("Не удалось загрузить поездки", "Сәфәрҙәрҙе йөкләп булманы")}</h2>
          <p>{appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла")}</p>
          <button type="button" className="btn-primary my-rides__action" onClick={() => load()}>
            {appText("Повторить", "Ҡабатлау")}
          </button>
        </div>
      )}

      {state === "ready" && visible.length === 0 && (
        <>
          {/* Здесь только брони — говорим правду и, если маршруты есть, отправляем туда, где они лежат. */}
          <div className="state">
            <div className="state__icon"><IconRoute size={30} /></div>
            <h2>{appText("Ты пока никуда не едешь", "Әлегә бер ҡайҙа ла бармайһың")}</h2>
            <p>
              {appText(
                "Здесь появятся поездки, на которые ты забронировал место.",
                "Бында һин урын алған сәфәрҙәр күренәсәк."
              )}
            </p>
            <button type="button" className="btn-primary my-rides__action" onClick={() => navigate("/request")}>
              {appText("Создать заявку", "Заявка булдырыу")}
            </button>
          </div>
          {myDriverRides > 0 && (
            <div className="state">
              <div className="state__icon"><IconCar size={30} /></div>
              <h2>{appText("Твои маршруты — в кабинете водителя", "Һинең маршруттар — йөрөтөүсе кабинетында")}</h2>
              <p>
                {appText(
                  `Опубликованных маршрутов: ${myDriverRides}. Там же брони пассажиров и «поднять».`,
                  `Баҫтырылған маршруттар: ${myDriverRides}. Пассажир брондары ла, «күтәреү» ҙә шунда.`
                )}
              </p>
              <button type="button" className="btn-primary my-rides__action" onClick={() => navigate("/driver")}>
                {appText("Кабинет водителя", "Йөрөтөүсе кабинеты")}
              </button>
            </div>
          )}
        </>
      )}

      {state === "ready" && visible.length > 0 && (
        <div className="my-rides-list">
          {visible.map((b, i) => {
            const history = HISTORY.has(b.status);
            const opensActiveTrip = bookingStatusAllowsActiveTrip(b.status);
            return (
              <MyTripCard
                key={b.id}
                booking={b}
                index={i}
                primaryAction={
                  history
                    ? appText("Повторить маршрут", "Маршрутты ҡабатлау")
                    : opensActiveTrip
                      ? appText("Открыть поездку", "Сәфәрҙе асыу")
                      : appText("Подробнее", "Ентекле")
                }
                secondaryAction={history ? "" : opensActiveTrip ? appText("Чат", "Чат") : appText("Написать", "Яҙыу")}
                onPrimary={() => {
                  if (history) navigate("/request");
                  else if (opensActiveTrip) navigate(`/trip/${b.id}`);
                  else navigate(`/booking/${b.id}`);
                }}
                onSecondary={() => (opensActiveTrip ? navigate(`/trip/${b.id}`) : navigate("/chat"))}
              />
            );
          })}
          {inlineAd && <PartnerAdCard ad={inlineAd} label={appText("Совет партнёра", "Партнёр кәңәше")} />}
        </div>
      )}

      {/* InfoCard Android: «Поездки защищены системой Юлдаш» — внизу вкладки, всегда. */}
      <div className="info-card">
        <span className="info-card__icon" aria-hidden><IconShield size={22} /></span>
        <span className="info-card__main">
          <strong>{appText("Поездки защищены системой Юлдаш", "Сәфәрҙәр Юлдаш системаһы менән һаҡлана")}</strong>
          <small>{appText("Мы бережём твою безопасность", "Беҙ һинең хәүефһеҙлегеңде һаҡлайбыҙ")}</small>
        </span>
      </div>
    </>
  );
}
