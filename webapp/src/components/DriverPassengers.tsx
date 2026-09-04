// ================================================================
//  «Мои пассажиры» — брони на поездки водителя.
//  Зеркало Android (ProfileScreen.kt, блок driverBookings)
//  + bookings.py: /driver/bookings, /confirm, /no-show, /rate.
//
//  Чего в вебе не было. Водитель не мог ни подтвердить бронь
//  (а до подтверждения пассажиру не открываются телефон и точка
//  сбора), ни отметить, что человек не вышел, ни поставить оценку.
//  То есть половина того, что делает попутку «между своими»,
//  работала только в приложении (сверка с Android, 2026-08-30).
//
//  Телефонов в этом списке нет: он про решения, а не про контакты.
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverBookings,
  markNoShow,
  rateBooking,
  type DriverBookingRow,
} from "../api/bookings";
import { LoadingList } from "./States";
import { IconProfile, IconStar } from "./Icons";

/** Бронь ещё ждёт слова водителя. */
const PENDING = "pending";
/** Живая бронь — по ней можно отметить неявку. */
const LIVE = ["confirmed", "onboard"];

export default function DriverPassengers() {
  const { appText } = useLang();
  const [boot, setBoot] = useState<"loading" | "ready" | "hidden">("loading");
  const [rows, setRows] = useState<DriverBookingRow[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [note, setNote] = useState("");

  const load = useCallback((signal?: AbortSignal) => {
    fetchDriverBookings(signal)
      .then((r) => {
        setRows(r);
        setBoot("ready");
      })
      .catch((e) => {
        if (signal?.aborted || e?.name === "AbortError") return;
        // Нет ручки или нет поездок — блок просто не появляется. Пустая карточка
        // «пассажиров нет» в кабинете, где и поездок ещё нет, только мешает.
        setBoot("hidden");
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  async function act(id: number, fn: () => Promise<unknown>, fail: { ru: string; ba: string }) {
    if (busyId) return;
    setBusyId(id);
    setNote("");
    try {
      await fn();
      load();
    } catch (e) {
      setNote(e instanceof ApiError && e.message ? e.message : appText(fail.ru, fail.ba));
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "hidden") return null;
  if (boot === "loading") return <LoadingList count={1} />;
  // Брони, ждущие ответа, показывает блок «Ждут твоего ответа» ВЫШЕ — он про срочное:
  // пока водитель молчит, человек не знает, поедет он или нет. Здесь остальные:
  // те, кого уже везут или отвезли, — чтобы отметить неявку и поставить оценку.
  // Показывать их в двух местах значило бы две кнопки «Подтвердить» на одном экране.
  const shown = rows.filter((b) => String(b.status) !== PENDING);
  if (shown.length === 0) return null;

  return (
    <>
      <h2 className="section-title">{appText("Мои пассажиры", "Юлаусыларым")}</h2>
      <div className="list">
        {shown.map((b) => {
          const st = String(b.status);
          return (
            <div key={b.booking_id} className="list-row list-row--stack">
              <div className="list-row__main">
                <div className="repeat-route">
                  <IconProfile size={18} />
                  <span>{b.passenger_name}</span>
                  {b.passenger_rating != null && (
                    <span className="taxi-driver__rating">
                      <IconStar size={14} /> {b.passenger_rating.toFixed(1)}
                    </span>
                  )}
                </div>
                <div className="list-row__sub">{b.route}</div>
              </div>

              {/* Не вышел. Отдельно от обычной отмены: это сигнал доверия, и водитель
                  не должен выбирать между «соврать, что отменил сам» и «промолчать». */}
              {LIVE.includes(st) && (
                <button
                  type="button"
                  className="btn-soft btn-soft--sm"
                  disabled={busyId === b.booking_id}
                  onClick={() =>
                    void act(b.booking_id, () => markNoShow(b.booking_id), {
                      ru: "Не получилось отметить. Проверь сеть.",
                      ba: "Билдәләп булманы. Селтәрҙе тикшер.",
                    })
                  }
                >
                  {appText("Не вышел", "Сыҡманы")}
                </button>
              )}

              {/* Оценка пассажира. Ноль звёзд = ещё не оценивал; оценку можно поменять. */}
              <div className="rate-stars rate-stars--sm">
                {[1, 2, 3, 4, 5].map((n) => (
                  <button
                    key={n}
                    type="button"
                    className={"rate-star" + (n <= b.my_stars ? " is-on" : "")}
                    aria-label={appText(`${n} звёзд`, `${n} йондоҙ`)}
                    disabled={busyId === b.booking_id}
                    onClick={() =>
                      void act(b.booking_id, () => rateBooking(b.booking_id, n), {
                        ru: "Оценка не ушла. Попробуй ещё раз.",
                        ba: "Баһа китмәне. Тағы ҡабатла.",
                      })
                    }
                  >
                    <IconStar size={20} />
                  </button>
                ))}
              </div>
            </div>
          );
        })}
      </div>
      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
