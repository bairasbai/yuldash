// ================================================================
//  «Мои поездки на такси» (пассажир) — GET /instant/orders/mine.
//  Зеркало Android MyTaxiTripsScreen.kt.
//
//  До этого экрана чек за такси было НЕ НАЙТИ: он открывался только
//  сразу после поездки, и закрытое приложение теряло его навсегда.
//  Тап по завершённой поездке → чек. RequireAuth.
//
//  Сумма — passenger_price_kop (что реально заплатил, со скидкой),
//  и БЕЗ округления: «188 ₽» в списке против «188,50 ₽» в чеке —
//  первый повод усомниться в приложении.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { fetchMyOrders, type InstantOrder } from "../api/instant";
import { LoadingList } from "../components/States";
import { SubHeader } from "./ConsentsScreen";
import { IconCar, IconChevron, IconReceipt } from "../components/Icons";
import { kopExactLabel } from "../utils/format";

type Status = "loading" | "error" | "soon" | "ready";

/** ISO (UTC) → «03.08.2026, 23:10» в часах человека. Резать строку нельзя: поездка в 23:10
 *  показалась бы вчерашним днём, и человек не нашёл бы её там, где ищет. */
function tripDayLabel(iso: string | null, fallback: string): string {
  if (!iso) return fallback;
  const d = new Date(iso);
  if (isNaN(d.getTime())) return fallback;
  const date = d.toLocaleDateString("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
  });
  const time = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" });
  return `${date}, ${time}`;
}

export default function MyTaxiTripsScreen() {
  const { appText } = useLang();
  const navigate = useNavigate();

  const [status, setStatus] = useState<Status>("loading");
  const [rows, setRows] = useState<InstantOrder[]>([]);

  const load = useCallback((signal?: AbortSignal) => {
    setStatus("loading");
    fetchMyOrders(50, signal)
      .then((r) => {
        setRows(r);
        setStatus("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        setStatus(e instanceof ApiError && e.status === 404 ? "soon" : "error");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  function statusLabel(s: string): string {
    if (s === "done") return appText("Поездка завершена", "Сәфәр тамамланды");
    if (s === "cancelled") return appText("Отменена", "Кире алынған");
    if (s === "expired") return appText("Машину не нашли", "Машина табылманы");
    return appText("В работе", "Эштә");
  }

  return (
    <>
      <SubHeader
        title={appText("Мои поездки на такси", "Такситағы сәфәрҙәрем")}
        subtitle={appText("История и чеки", "Тарих һәм чектар")}
        onBack={() => navigate(-1)}
      />

      {status === "loading" && <LoadingList count={3} />}

      {status === "soon" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon">
            <IconCar size={34} />
          </div>
          <h2>{appText("Скоро здесь", "Тиҙҙән бында")}</h2>
          <p>
            {appText(
              "История поездок на такси включится с ближайшим обновлением.",
              "Такси сәфәрҙәре тарихы яҡын яңыртыуҙа тоташа."
            )}
          </p>
        </div>
      )}

      {status === "error" && (
        <div className="state" style={{ paddingTop: 32 }}>
          <div className="state__icon state__icon--warn">
            <IconCar size={34} />
          </div>
          <h2>{appText("Не получилось загрузить", "Йөкләргә булманы")}</h2>
          <button type="button" className="btn-primary" onClick={() => load()}>
            {appText("Повторить", "Ҡабатларға")}
          </button>
        </div>
      )}

      {status === "ready" &&
        (rows.length === 0 ? (
          <div className="state" style={{ paddingTop: 32 }}>
            <div className="state__icon">
              <IconCar size={34} />
            </div>
            <h2>{appText("Поездок пока нет", "Сәфәрҙәр әлегә юҡ")}</h2>
            <p>
              {appText(
                "Здесь появятся все твои поездки на такси — с чеком за каждую.",
                "Бында бөтә такси сәфәрҙәрең күренәсәк — һәрбереһенә чек менән."
              )}
            </p>
            <button type="button" className="btn-primary" onClick={() => navigate("/taxi")}>
              <IconCar size={18} /> {appText("Заказать такси", "Такси заказ итеү")}
            </button>
          </div>
        ) : (
          <div className="list" style={{ marginTop: 12 }}>
            {rows.map((o) => {
              const done = o.status === "done";
              const payKop = o.passenger_price_kop || 0;
              const card = (
                <>
                  <div className="money-row__head">
                    <span className="money-row__route">
                      {tripDayLabel(o.created_at, appText("Поездка", "Сәфәр"))}
                    </span>
                    {done && payKop > 0 && (
                      <span className="money-row__net">{kopExactLabel(payKop)}</span>
                    )}
                  </div>
                  <div className="money-row__date">{statusLabel(o.status)}</div>

                  <div className="info-list" style={{ marginTop: 10 }}>
                    {o.from_text && (
                      <div className="info-row">
                        <span className="info-row__k">{appText("Откуда", "Ҡайҙан")}</span>
                        <span className="info-row__v">{o.from_text}</span>
                      </div>
                    )}
                    {o.to_text && (
                      <div className="info-row">
                        <span className="info-row__k">{appText("Куда", "Ҡайҙа")}</span>
                        <span className="info-row__v">{o.to_text}</span>
                      </div>
                    )}
                  </div>

                  {done && (
                    <div className="money-row__foot">
                      <span className="badge badge--mint">
                        <IconReceipt size={12} /> {appText("Чек", "Чек")}
                      </span>
                      <span className="money-row__op" style={{ fontSize: "var(--font-micro)" }}>
                        {appText("Нажми, чтобы открыть чек", "Чекты асыр өсөн баҫ")}
                      </span>
                      <span style={{ marginLeft: "auto", display: "inline-flex" }}>
                        <IconChevron size={18} />
                      </span>
                    </div>
                  )}
                </>
              );

              return done ? (
                <button
                  key={o.id}
                  type="button"
                  className="money-row"
                  style={{ width: "100%", textAlign: "left" }}
                  onClick={() => navigate(`/taxi-receipt/${o.id}`)}
                >
                  {card}
                </button>
              ) : (
                <div key={o.id} className="money-row">
                  {card}
                </div>
              );
            })}
          </div>
        ))}
    </>
  );
}
