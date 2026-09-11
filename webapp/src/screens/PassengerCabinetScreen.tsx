// ================================================================
//  Кабинет пассажира — «Мои поездки» + быстрый доступ к разделам.
//  GET /bookings/mine: ближайшая активная бронь (pending→Booking,
//  confirmed/onboard→ActiveTrip), история → квитанция/повтор.
//  Плюс карточки: Мои адреса, Повтор, Мой Юлдаш, Подписки,
//  Кошелёк, Квитанции. RequireAuth.
// ================================================================
import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { fetchMyBookings, type MyBooking } from "../api/bookings";
import { LoadingList, ErrorState } from "../components/States";
import { StatusPill } from "../components/StatusPill";
import ScreenHeader from "../components/ScreenHeader";
import { formatWhen, priceLabel } from "../utils/format";
import {
  IconArrow,
  IconHome,
  IconRoute,
  IconTrend,
  IconBell,
  IconWallet,
  IconReceipt,
  IconCar,
} from "../components/Icons";

type Status = "loading" | "error" | "ready";

const ACTIVE = new Set(["pending", "confirmed", "onboard"]);

export default function PassengerCabinetScreen() {
  const { appText, lang } = useLang();
  const ru = lang !== "ba";
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [bookings, setBookings] = useState<MyBooking[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMyBookings(signal)
      .then((rows) => {
        setBookings(rows);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus("error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  const active = useMemo(
    () => bookings.filter((b) => ACTIVE.has(b.status)),
    [bookings]
  );
  const history = useMemo(
    () => bookings.filter((b) => !ACTIVE.has(b.status)).slice(0, 4),
    [bookings]
  );
  const latestDone = useMemo(
    () => bookings.find((b) => b.status === "done") ?? null,
    [bookings]
  );

  // Куда ведёт бронь по статусу: подтверждение → детали, активная → поездка.
  function openBooking(b: MyBooking) {
    if (b.status === "pending") navigate(`/booking/${b.id}`);
    else if (b.status === "done" || b.status === "cancelled") navigate(`/receipt/${b.id}`);
    else navigate(`/trip/${b.id}`);
  }

  const tiles: {
    key: string;
    icon: JSX.Element;
    title: string;
    onClick: () => void;
  }[] = [
    {
      key: "places",
      icon: <IconHome size={22} />,
      title: appText("Мои адреса", "Адрестарым"),
      onClick: () => navigate("/places"),
    },
    {
      key: "repeat",
      icon: <IconRoute size={22} />,
      title: appText("Повторить маршрут", "Маршрутты ҡабатлау"),
      onClick: () => navigate("/repeat"),
    },
    {
      key: "stats",
      icon: <IconTrend size={22} />,
      title: appText("Мой Юлдаш", "Минең Юлдаш"),
      onClick: () => navigate("/stats"),
    },
    {
      key: "watch",
      icon: <IconBell size={22} />,
      title: appText("Подписки", "Яҙылыуҙар"),
      onClick: () => navigate("/route-watches"),
    },
    {
      key: "receipts",
      icon: <IconReceipt size={22} />,
      title: appText("Квитанции", "Квитанциялар"),
      onClick: () => {
        if (latestDone) navigate(`/receipt/${latestDone.id}`);
      },
    },
    {
      // Чек за такси раньше терялся: он открывался только сразу после поездки.
      key: "mytaxi",
      icon: <IconCar size={22} />,
      title: appText("Поездки на такси", "Такси сәфәрҙәре"),
      onClick: () => navigate("/my-taxi"),
    },
    {
      key: "wallet",
      icon: <IconWallet size={22} />,
      title: appText("Кошелёк", "Янсыҡ"),
      onClick: () => navigate("/wallet"),
    },
  ];

  return (
    <>
      <ScreenHeader
        title={appText("Кабинет пассажира", "Юлаусы кабинеты")}
        subtitle={appText("Поездки и всё нужное под рукой", "Сәфәрҙәр һәм кәрәклеһе ҡул аҫтында")}
      />

      {status === "loading" && <LoadingList count={2} />}
      {status === "error" && <ErrorState onRetry={() => load()} />}

      {status === "ready" && (
        <>
          {/* Активная поездка — крупная карточка */}
          {active.length > 0 && (
            <>
              <h2 className="section-title">{appText("Активная поездка", "Әүҙем сәфәр")}</h2>
              {active.map((b) => (
                <button
                  key={b.id}
                  type="button"
                  className="ride-card-btn"
                  onClick={() => openBooking(b)}
                >
                  <article className="trip__routecard">
                    <div className="ride-card__route">
                      <span>{b.from_city || appText("Маршрут", "Юл")}</span>
                      {b.to_city && (
                        <>
                          <span className="ride-card__arrow">
                            <IconArrow size={20} />
                          </span>
                          <span>{b.to_city}</span>
                        </>
                      )}
                    </div>
                    <div className="trip__meta">
                      <span>{formatWhen(b.depart_at, ru)}</span>
                      <StatusPill status={b.status} />
                      <span style={{ marginLeft: "auto", fontWeight: 800 }}>
                        {priceLabel(b.price, ru)}
                      </span>
                    </div>
                  </article>
                </button>
              ))}
            </>
          )}

          {/* Быстрый доступ */}
          <h2 className="section-title">{appText("Быстрый доступ", "Тиҙ инеү")}</h2>
          <div className="cabinet-grid">
            {tiles.map((t) => (
              <button
                key={t.key}
                type="button"
                className="cabinet-tile"
                onClick={t.onClick}
              >
                <span className="cabinet-tile__icon">{t.icon}</span>
                <span className="cabinet-tile__title">{t.title}</span>
              </button>
            ))}
          </div>

          {/* История поездок */}
          {history.length > 0 && (
            <>
              <h2 className="section-title">{appText("Мои поездки", "Сәфәрҙәрем")}</h2>
              <div className="list">
                {history.map((b) => (
                  <button
                    key={b.id}
                    type="button"
                    className="list-row list-row--link"
                    onClick={() => openBooking(b)}
                  >
                    <div className="list-row__main">
                      <div className="repeat-route">
                        <span>{b.from_city || appText("Маршрут", "Юл")}</span>
                        {b.to_city && (
                          <>
                            <span className="repeat-route__arrow">
                              <IconArrow size={18} />
                            </span>
                            <span>{b.to_city}</span>
                          </>
                        )}
                      </div>
                      <div className="list-row__sub">
                        {formatWhen(b.depart_at, ru)} · {priceLabel(b.price, ru)}
                      </div>
                    </div>
                    <StatusPill status={b.status} />
                  </button>
                ))}
              </div>
              <button
                type="button"
                className="btn-soft"
                style={{ marginTop: 12 }}
                onClick={() => navigate("/repeat")}
              >
                <IconRoute size={18} /> {appText("Повторить маршрут", "Маршрутты ҡабатлау")}
              </button>
            </>
          )}

          {/* Пусто совсем */}
          {active.length === 0 && history.length === 0 && (
            <div className="state" style={{ paddingTop: 32 }}>
              <div className="state__icon">
                <IconRoute size={34} />
              </div>
              <h2>{appText("Пока нет поездок", "Әле сәфәрҙәр юҡ")}</h2>
              <p>
                {appText(
                  "Найди попутку на карте или оставь заявку — водители откликнутся.",
                  "Картанан юлдаш тап йәки заявка ҡалдыр — йөрөтөүселәр яуап бирер."
                )}
              </p>
              <button type="button" className="btn-primary" onClick={() => navigate("/map")}>
                {appText("На карту", "Картаға")}
              </button>
            </div>
          )}
        </>
      )}
    </>
  );
}
