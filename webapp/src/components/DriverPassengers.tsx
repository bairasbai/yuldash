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
//
//  Посадку отмечает ВОДИТЕЛЬ (решение 17.10.2026): у подтверждённого пассажира «Сел» рядом
//  с «Не вышел», «в пути» — «Отменить» в первые 5 минут, позже — разбор (DriverBoarding.tsx).
//
//  «Не вышел» — через 15 минут после выезда и с вопросом «Точно?» (решение 18.10.2026, образец
//  BlaBlaCar): срок приходит от сервера (`no_show_at`), экран — DriverNoShow.tsx. На поездке с
//  остановками — только после «Подъезжаю» по этой брони: она тут же, на месте «Не вышел».
// ================================================================
import { useCallback, useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import {
  fetchDriverBookings,
  markArriving,
  markNoShow,
  rateBooking,
  type DriverBookingRow,
} from "../api/bookings";
import { LoadingList } from "./States";
import { IconProfile, IconStar } from "./Icons";
import FileIncidentCard from "./FileIncidentCard";
import DriverBoarding from "./DriverBoarding";
import DriverNoShow from "./DriverNoShow";
import { SectionHeader } from "./cabinetUi";
import { pluralRu } from "../utils/format";

/** Подтверждённая бронь — по ней можно отметить посадку («Сел») или неявку. */
const CONFIRMED = "confirmed";
/** Посадка отмечена («Сел», с 17.10.2026 — водителем). Неявку сервер не примет (409, решение
 *  14.10.2026): промах — «Отменить» в первые минуты, позже спор решает разбор, а не кнопка. */
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
  /** Оценка в ДВА шага, как в Android (ProfileScreen.kt): выбрал звёзды → «Отправить оценку».
   *  Раньше первое касание сразу уходило на сервер: промахнулся по первой звезде — человеку
   *  упала единица, и вернуть было нечем. Здесь — выбранные, но ещё не отправленные звёзды
   *  по брони (0 — ничего не выбрано). Бронь без оценки редактируется сразу, с оценкой —
   *  после «Изменить». */
  const [picked, setPicked] = useState<Record<number, number>>({});
  /** Оценки, которые уходят прямо сейчас: блокировка у каждой карточки своя, как в Android. */
  const [sendingIds, setSendingIds] = useState<number[]>([]);
  /** «Спасибо» или причина отказа — у самой карточки, а не внизу блока (там её не видно). */
  const [rateNote, setRateNote] = useState<{ id: number; text: string } | null>(null);
  /** Куда вернуть фокус клавиатуры, когда нажатая кнопка исчезла. */
  const [focusAfter, setFocusAfter] = useState<{ id: number; to: "stars" | "edit" } | null>(null);
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
    if (!focusAfter || typeof document === "undefined") return;
    const card = document.querySelector(`[data-rate-card="${focusAfter.id}"]`);
    // Только если фокус был в этой карточке или потерян: человек уже в соседней — не уводим его
    // и не прокручиваем страницу (ответ по первой оценке пришёл, пока он выбирал звёзды во второй).
    const active = document.activeElement;
    if (active && active !== document.body && !card?.contains(active)) {
      setFocusAfter(null);
      return;
    }
    // На выбранную звезду (её стрелками можно сразу поменять), иначе на первую; после правки — на «Изменить».
    const el =
      focusAfter.to === "stars"
        ? (card?.querySelector<HTMLElement>('.rate-star[aria-pressed="true"]') ?? card?.querySelector<HTMLElement>(".rate-star:not(:disabled)"))
        : card?.querySelector<HTMLElement>(".rate-edit");
    el?.focus({ preventScroll: true });
    setFocusAfter(null);
  }, [focusAfter]);

  useEffect(() => {
    const ac = new AbortController();
    load(ac.signal);
    return () => ac.abort();
  }, [load]);

  /** После «Сел» / «Отменить» — перечитать список (без аргументов: ответ действия не сигнал отмены). */
  const reload = useCallback(() => load(), [load]);

  async function act(id: number, fn: () => Promise<unknown>, fail: { ru: string; ba: string }) {
    if (busyId) return;
    setBusyId(id);
    setNote("");
    try {
      await fn();
      load();
    } catch (e) {
      setNote(e instanceof ApiError && e.message ? e.message : appText(fail.ru, fail.ba));
      // 409 — состояние брони ушло вперёд (посадку отметили с другого телефона, пока список
      // висел): перечитываем, и вместо «Не вышел» появляется «Пассажир в машине».
      if (e instanceof ApiError && e.status === 409) load();
    } finally {
      setBusyId(null);
    }
  }

  async function sendRating(b: DriverBookingRow, n: number) {
    const id = b.booking_id;
    if (sendingIds.includes(id)) return;
    setSendingIds((ids) => [...ids, id]);
    setRateNote(null);
    try {
      await rateBooking(id, n);
      // Сразу показываем поставленную (сервер вернёт её же как my_stars при перечитывании).
      setRows((rs) => rs.map((r) => (r.booking_id === id ? { ...r, my_stars: n } : r)));
      setPicked(({ [id]: _sent, ...rest }) => rest);
      setRateNote({ id, text: appText("Спасибо за оценку", "Баһа өсөн рәхмәт") });
      setFocusAfter({ id, to: "edit" });
      load();
    } catch (e) {
      // Не ушло — возвращаем как было: на экране не должна остаться «поставленная» оценка,
      // которой нет на сервере.
      setPicked((p) => ({ ...p, [id]: b.my_stars }));
      setFocusAfter({ id, to: "stars" });   // нажатая «Отправить» исчезла — фокус не улетает в начало страницы
      setRateNote({
        id,
        text: e instanceof ApiError && e.message ? e.message : appText("Не получилось оценить", "Баһалап булманы"),
      });
      if (e instanceof ApiError && e.status === 409) load();
    } finally {
      setSendingIds((ids) => ids.filter((x) => x !== id));
    }
  }

  /** «4 звезды» / «4 йондоҙ» — как `starsText` в Android. */
  const starsText = (n: number) => appText(`${n} ${pluralRu(n, "звезда", "звезды", "звёзд")}`, `${n} йондоҙ`);

  if (boot === "hidden") return null;
  if (boot === "loading") return <LoadingList count={1} />;
  // Брони, ждущие ответа, показывает блок «Ждут твоего ответа» ВЫШЕ — он про срочное:
  // пока водитель молчит, человек не знает, поедет он или нет. Здесь остальные.
  // Показывать их в двух местах значило бы две кнопки «Подтвердить» на одном экране.
  // Едут прямо сейчас: подтверждённые и уже в пути. Оценивать их нельзя (поездка не
  // состоялась — сервер вернёт 409), а вот «Сел» и «пассажир не вышел» нужны именно тут.
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

                  {/* Посадку отмечает водитель: «Сел» рядом с «Не вышел» (решение 17.10.2026). */}
                  {st === CONFIRMED && (
                    <DriverBoarding bookingId={b.booking_id} status={st} onChanged={reload} onError={setNote} />
                  )}

                  {/* Не вышел. Отдельно от обычной отмены: это сигнал доверия, и водитель
                      не должен выбирать между «соврать, что отменил сам» и «промолчать».
                      Не раньше срока от сервера (`no_show_at`: выезд или «Подъезжаю» + 15 минут, решения
                      18.10.2026): до него — неактивная кнопка и подпись «можно отметить с …», после —
                      кнопка и вопрос «Точно?»; на поездке с остановками сначала «Подъезжаю». */}
                  {st === CONFIRMED && (
                    <DriverNoShow
                      noShowAt={b.no_show_at}
                      needsArriving={b.no_show_needs_arriving === true}
                      busy={busyId === b.booking_id}
                      onConfirm={() =>
                        void act(b.booking_id, () => markNoShow(b.booking_id), {
                          ru: "Не получилось отметить. Проверь сеть.",
                          ba: "Билдәләп булманы. Селтәрҙе тикшер.",
                        })
                      }
                      onArriving={() =>
                        void act(b.booking_id, () => markArriving(b.booking_id), {
                          ru: "Не удалось сохранить статус. Проверь сеть.",
                          ba: "Хәлде һаҡлап булманы. Селтәрҙе тикшер.",
                        })
                      }
                    />
                  )}

                  {/* В пути: «Пассажир в машине» и «Отменить» в первые 5 минут — промахнулся
                      карточкой. Дальше неявку не ставим: если посадки не было, водитель открывает
                      двусторонний разбор, решает админ. */}
                  {st === ONBOARD && (
                    <DriverBoarding
                      bookingId={b.booking_id}
                      status={st}
                      undoSec={b.boarding_undo_sec ?? 0}
                      onChanged={reload}
                      onError={setNote}
                    >
                      <div className="list-row__sub" role="note">
                        {appText(
                          "Если пассажир на самом деле не садился — сообщи о проблеме, разберёмся.",
                          "Пассажир ысынлап ултырмаған булһа — проблема тураһында хәбәр ит, тикшерербеҙ."
                        )}
                      </div>
                      {filedIds.includes(b.booking_id) && (
                        <div className="notice" role="status">
                          {appText(
                            "Разбор открыт. Позовём пассажира объясниться, а решение объясним обеим сторонам.",
                            "Ҡарау асылды. Юлсыны аңлатырға саҡырабыҙ, ә ҡарарҙы ике яҡҡа ла аңлатабыҙ."
                          )}
                        </div>
                      )}
                      {!filedIds.includes(b.booking_id) && b.passenger_id != null && (
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
                    </DriverBoarding>
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
            {ratable.map((b) => {
              const id = b.booking_id;
              const editing = b.my_stars === 0 || id in picked;
              const stars = editing ? (picked[id] ?? 0) : b.my_stars;
              const sending = sendingIds.includes(id);
              return (
                <div key={id} className="list-row list-row--stack" data-rate-card={id}>
                  {passengerMain(b)}
                  {/* Сервер разрешает оценку изменить — значит, и здесь это не тупик. */}
                  <div className="rate-row">
                    <div className="rate-stars rate-stars--sm" role="group" aria-label={appText("Оценка пассажира", "Пассажир баһаһы")}>
                      {[1, 2, 3, 4, 5].map((n) => (
                        <button
                          key={n}
                          type="button"
                          className={"rate-star" + (n <= stars ? " is-on" : "")}
                          aria-label={starsText(n)}
                          aria-pressed={editing ? n === stars : undefined}
                          disabled={!editing || sending}
                          onClick={() => setPicked((p) => ({ ...p, [id]: n }))}
                        >
                          <IconStar size={20} />
                        </button>
                      ))}
                    </div>
                    {!editing && (
                      <button
                        type="button"
                        className="btn-soft btn-soft--sm rate-edit"
                        onClick={() => {
                          setPicked((p) => ({ ...p, [id]: b.my_stars }));
                          setRateNote(null);
                          setFocusAfter({ id, to: "stars" });
                        }}
                      >
                        {appText("Изменить", "Үҙгәртеү")}
                      </button>
                    )}
                  </div>
                  {/* Подпись под звёздами — состояние словами; читалке экрана она же читает оценку. */}
                  <div className="list-row__sub" aria-live="polite">
                    {!editing
                      ? appText(`Твоя оценка: ${starsText(stars)}`, `Һинең баһаң: ${starsText(stars)}`)
                      : stars === 0
                        ? appText("Выбери оценку", "Баһа һайла")
                        : appText(`Выбрано ${starsText(stars)} — подтверди`, `${starsText(stars)} һайланды — раҫла`)}
                  </div>
                  {editing && stars > 0 && (
                    <div className="rate-confirm">
                      <button type="button" className="btn-primary" disabled={sending} onClick={() => void sendRating(b, stars)}>
                        {sending ? appText("Отправляем…", "Ебәрәбеҙ…") : appText("Отправить оценку", "Баһаны ебәреү")}
                      </button>
                      {/* «Отмена» — только когда есть что вернуть: уже поставленную раньше оценку. */}
                      {b.my_stars > 0 && (
                        <button
                          type="button"
                          className="btn-soft"
                          disabled={sending}
                          onClick={() => {
                            setPicked(({ [id]: _dropped, ...rest }) => rest);
                            setFocusAfter({ id, to: "edit" });
                          }}
                        >
                          {appText("Отмена", "Кире алыу")}
                        </button>
                      )}
                    </div>
                  )}
                  {rateNote?.id === id && (
                    <div className="notice" role="status">
                      {rateNote.text}
                    </div>
                  )}
                </div>
              );
            })}
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
