// ================================================================
//  Пассажиры водителя: «Едут с тобой» и «Пассажиры — оцените после поездки».
//  Зеркало Android (ProfileScreen.kt, блок driverBookings: ridingBookings / ratableBookings)
//  + bookings.py: /driver/bookings, /no-show, /rate.
//
//  Паритет 16.10.2026: раньше здесь был один список «Мои пассажиры», где звёзды висели и у тех,
//  кто ещё едет (сервер такую оценку отклоняет — человек жал звезду и читал «Оценка не ушла»),
//  а дойти из кабинета до экрана поездки было нечем. Теперь как в приложении: едущим —
//  «Открыть поездку», звёзды — только после завершения.
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
import FileIncidentCard from "./FileIncidentCard";
import { SectionHeader } from "./cabinetUi";

/** Подтверждённая бронь — по ней можно отметить неявку. */
const CONFIRMED = "confirmed";
/** Пассажир сам нажал «Я сел». Неявку сервер не примет (409, решение 14.10.2026):
 *  слово против слова решает разбор, а не кнопка водителя. */
const ONBOARD = "onboard";
/** Поездка завершена — только её и можно оценить (иначе рейтинг накручивается без поездки). */
const DONE = "done";

/** @param onOpenTrip «Открыть поездку» — экран поездки водителя по этой брони (Android onOpenTrip). */
export default function DriverPassengers({ onOpenTrip }: { onOpenTrip: (bookingId: number) => void }) {
  const { appText } = useLang();
  const [boot, setBoot] = useState<"loading" | "ready" | "hidden">("loading");
  const [rows, setRows] = useState<DriverBookingRow[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [note, setNote] = useState("");
  /** По какой брони открыт «Сообщить о проблеме» и по каким разбор уже открыт. */
  const [disputeId, setDisputeId] = useState<number | null>(null);
  const [filedIds, setFiledIds] = useState<number[]>([]);

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
      // 409 — состояние брони ушло вперёд (пассажир нажал «Я сел», пока список висел):
      // перечитываем, и вместо «Не вышел» появляется «Сообщить о проблеме».
      if (e instanceof ApiError && e.status === 409) load();
    } finally {
      setBusyId(null);
    }
  }

  if (boot === "hidden") return null;
  if (boot === "loading") return <LoadingList count={1} />;
  // Брони, ждущие ответа, показывает блок «Ждут твоего ответа» ВЫШЕ — он про срочное:
  // пока водитель молчит, человек не знает, поедет он или нет. Здесь остальные.
  // Показывать их в двух местах значило бы две кнопки «Подтвердить» на одном экране.
  // Едут прямо сейчас: подтверждённые и уже в пути. Оценивать их нельзя (поездка не
  // состоялась — сервер вернёт 409), а вот «пассажир не вышел» нужно именно тут.
  const riding = rows.filter((b) => String(b.status) === CONFIRMED || String(b.status) === ONBOARD);
  // Оценивать можно ТОЛЬКО завершённые поездки. Отменённые не показываем вовсе: ни открыть,
  // ни оценить их нельзя.
  const ratable = rows.filter((b) => String(b.status) === DONE);
  if (riding.length === 0 && ratable.length === 0) return null;

  /** Имя, рейтинг и маршрут — одинаково в обоих разделах (Android PassengerRow). */
  const passengerMain = (b: DriverBookingRow) => (
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
  );

  return (
    <>
      {riding.length > 0 && (
        <>
          <SectionHeader
            title={appText("Едут с тобой", "Һинең менән баралар")}
            subtitle={appText(
              "Поездка ещё не закончилась. Оценить сможешь, когда завершишь рейс.",
              "Сәфәр әле тамамланманы. Рейсты тамамлағас баһалай алаһың."
            )}
          />
          <div className="list">
            {riding.map((b) => {
              const st = String(b.status);
              return (
                <div key={b.booking_id} className="list-row list-row--stack">
                  {passengerMain(b)}

                  {/* Экран поездки водителя: статусы «Выехал / Подъезжаю», чат, точка для пассажира. */}
                  <button type="button" className="btn-primary trip-btn" onClick={() => onOpenTrip(b.booking_id)}>
                    {appText("Открыть поездку", "Сәфәрҙе асыу")}
                  </button>

                  {/* Не вышел. Отдельно от обычной отмены: это сигнал доверия, и водитель
                      не должен выбирать между «соврать, что отменил сам» и «промолчать». */}
                  {st === CONFIRMED && (
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

                  {/* В пути: пассажир сам отметил «Я сел». Неявку не ставим — если посадки
                      не было, водитель открывает двусторонний разбор, решает админ. */}
                  {st === ONBOARD && (
                    <div className="list-row__sub" role="note">
                      {appText(
                        "Пассажир отметил «Я сел». Если он не садился — сообщи о проблеме, разберёмся.",
                        "Юлсы «Ултырҙым» тип билдәләне. Ултырмаһа — проблема тураһында хәбәр ит, тикшерербеҙ."
                      )}
                    </div>
                  )}
                  {st === ONBOARD && filedIds.includes(b.booking_id) && (
                    <div className="notice" role="status">
                      {appText(
                        "Разбор открыт. Позовём пассажира объясниться, а решение объясним обеим сторонам.",
                        "Ҡарау асылды. Юлсыны аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ."
                      )}
                    </div>
                  )}
                  {st === ONBOARD && !filedIds.includes(b.booking_id) && b.passenger_id != null && (
                    disputeId === b.booking_id ? (
                      <FileIncidentCard
                        respondentId={b.passenger_id}
                        respondentName={b.passenger_name}
                        bookingId={b.booking_id}
                        initialType="passenger_no_show"
                        onCancel={() => setDisputeId(null)}
                        onFiled={() => {
                          setDisputeId(null);
                          setFiledIds((ids) => [...ids, b.booking_id]);
                        }}
                        onError={setNote}
                      />
                    ) : (
                      <button
                        type="button"
                        className="btn-soft btn-soft--sm"
                        onClick={() => {
                          setNote("");
                          setDisputeId(b.booking_id);
                        }}
                      >
                        {appText("Сообщить о проблеме", "Проблема тураһында хәбәр итергә")}
                      </button>
                    )
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {ratable.length > 0 && (
        <>
          <SectionHeader
            title={appText("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ")}
            subtitle={appText(
              "Честные оценки берегут круг «своих» — их видят другие водители.",
              "Ғәҙел баһалар «үҙебеҙҙекеләр» түңәрәген һаҡлай — башҡа йөрөтөүселәр ҙә күрә."
            )}
          />
          <div className="list">
            {ratable.map((b) => (
              <div key={b.booking_id} className="list-row list-row--stack">
                {passengerMain(b)}
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
            ))}
          </div>
        </>
      )}

      {note && (
        <div className="notice" role="status">
          {note}
        </div>
      )}
    </>
  );
}
