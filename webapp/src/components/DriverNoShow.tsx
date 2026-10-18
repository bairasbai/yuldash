// ================================================================
//  «Не пришёл» — неявка пассажира в «Едут с тобой» (решение Александра 18.10.2026).
//  Зеркало Android RideshareNoShow.kt / RideshareBoarding.kt (ConfirmedPassengerControls);
//  сервер — bookings.py: `NO_SHOW_WAIT`, отказы `no_show_too_early` и `no_show_needs_arriving`.
//
//  Последнее решение 18.10: срок ожидания задаёт сервер (пять минут). Раньше кнопка работала
//  в любой момент — хоть за неделю до поездки — одним касанием, и вернуть было нельзя: промахнулся,
//  и бронь человека, который стоит у дороги, снята, а место ушло в продажу.
//
//  Срок знает только сервер: строка брони несёт момент `no_show_at` (поздний из «выезд» и первого
//  «Подъезжаю» + срок ожидания), сайт своего срока не держит.
//   • до этого момента кнопка на своём месте, но неактивна, под ней — подпись «можно отметить с …»;
//     место занято заранее: «Сел» рядом не сдвигается, когда срок наступает (приёмка PR182);
//   • после — кнопка оживает, а нажатие сперва спрашивает «Точно не пришёл?» и ничего не отправляет;
//   • «Да, не пришёл» зовёт родителя (он шлёт запрос, показывает отказ сервера его словами и
//     перечитывает список), «Подождать» закрывает окно, бронь не тронута.
//  Поездка с остановками (решение 18.10.2026, `no_show_needs_arriving`): пассажира на остановке
//  сажают позже выезда, и неявки нет, пока он не получил «Водитель подъезжает». Тогда на месте
//  «Не пришёл» — «Подъезжаю» (родитель шлёт тот же `driver-status`, что экран поездки, и перечитывает
//  список — придёт новый срок), под ней — «Сначала нажми «Подъезжаю» — пассажир получит уведомление».
//  «Подъезжаю» — не раньше чем за 30 минут до выезда (приёмка PR184, `arriving_opens_at`): до этого
//  она на месте, но неактивна, под ней — «можно нажать с …». Сроки — от сервера: момент и секунды
//  (`*_wait_sec`) — часы устройства врут на минуты, поэтому ждём до позднего из двух.
//  Нет `no_show_at` (старый сервер) — кнопка есть, а последнее слово за сервером (409).
// ================================================================
import { useEffect, useMemo, useState } from "react";
import { useLang } from "../i18n/lang";
import AlertDialog from "./AlertDialog";
import { IconCar, IconClock } from "./Icons";
import { dayMonthClock } from "../utils/format";
import { serverMs } from "../utils/serverTime";

/** Поздний из двух моментов; NaN — момента нет. */
function later(a: number, b: number): number {
  if (Number.isNaN(a)) return b;
  if (Number.isNaN(b)) return a;
  return Math.max(a, b);
}

export default function DriverNoShow({
  noShowAt,
  noShowWaitSec,
  needsArriving = false,
  arrivingOpensAt,
  arrivingWaitSec,
  busy,
  onConfirm,
  onArriving,
}: {
  /** ISO UTC из `/driver/bookings`: с какого момента неявку можно отметить; нет — срок неизвестен. */
  noShowAt?: string | null;
  /** Сколько секунд до `noShowAt` от ответа сервера (часы устройства могут спешить). */
  noShowWaitSec?: number;
  /** Поездка с остановками ждёт «Подъезжаю» по этой брони (`no_show_needs_arriving`). */
  needsArriving?: boolean;
  /** ISO UTC: с какого момента можно нажать «Подъезжаю» (за 30 минут до выезда); нет — старый сервер. */
  arrivingOpensAt?: string | null;
  /** Сколько секунд до `arrivingOpensAt` от ответа сервера. */
  arrivingWaitSec?: number;
  /** Запрос уже идёт (родитель держит блокировку на всю карточку). */
  busy: boolean;
  /** Водитель ответил «Да, не пришёл»: отправить неявку. */
  onConfirm: () => void;
  /** «Подъезжаю» с карточки: отправить статус и перечитать список. */
  onArriving: () => void;
}) {
  const { appText } = useLang();
  const [now, setNow] = useState(() => Date.now());
  const [asking, setAsking] = useState(false);

  // Секунды сервера → момент по часам устройства, в ту минуту, когда пришла строка списка.
  const noShowUntil = useMemo(
    () => (noShowWaitSec && noShowWaitSec > 0 ? Date.now() + noShowWaitSec * 1000 : NaN),
    [noShowAt, noShowWaitSec]
  );
  const arrivingUntil = useMemo(
    () => (arrivingWaitSec && arrivingWaitSec > 0 ? Date.now() + arrivingWaitSec * 1000 : NaN),
    [arrivingOpensAt, arrivingWaitSec]
  );
  const opensAt = later(serverMs(noShowAt), noShowUntil);
  const arrivingAt = later(serverMs(arrivingOpensAt), arrivingUntil);
  // Срок неизвестен (старый сервер, выезд не задан, кривая строка) — не прячем: решает сервер.
  const open = Number.isNaN(opensAt) || now >= opensAt;
  const arrivingOpen = Number.isNaN(arrivingAt) || now >= arrivingAt;
  const slot = needsArriving ? (arrivingOpen ? "arriving" : "arriving-wait") : open ? "ready" : "waiting";

  // Пока срок впереди, перечитываем часы раз в секунду — кнопка сама оживёт в нужную секунду.
  // Срок наступил или его нет — таймера нет. Таймер вкладки в фоне браузер замедляет, но
  // следующее срабатывание берёт время заново.
  useEffect(() => {
    if (slot !== "waiting" && slot !== "arriving-wait") return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [slot]);

  if (slot === "arriving" || slot === "arriving-wait") {
    const locked = slot === "arriving-wait";
    const from = dayMonthClock(arrivingOpensAt);
    return (
      <>
        <button
          type="button"
          className="btn-soft btn-soft--sm noshow-slot"
          disabled={busy || locked}
          onClick={onArriving}
        >
          <IconCar size={16} /> {appText("Подъезжаю", "Яҡынлашам")}
        </button>
        <div key={slot} className="list-row__sub noshow-wait" role="note">
          {locked ? <IconClock size={16} /> : <IconCar size={16} />}
          <span>
            {locked
              ? appText(`«Подъезжаю» можно нажать с ${from}`, `«Яҡынлашам»ға ${from} үткәс кенә баҫып була`)
              : appText(
                  "Сначала нажми «Подъезжаю» — пассажир получит уведомление",
                  "Башта «Яҡынлашам»ға баҫ — пассажир хәбәр алыр"
                )}
          </span>
        </div>
      </>
    );
  }

  const from = dayMonthClock(noShowAt);
  return (
    <>
      <button
        type="button"
        className="btn-soft btn-soft--sm noshow-slot"
        disabled={busy || slot === "waiting"}
        onClick={() => setAsking(true)}
      >
        {appText("Не пришёл", "Килмәне")}
      </button>
      {slot === "waiting" && (
        <div key="waiting" className="list-row__sub noshow-wait" role="note">
          <IconClock size={16} />
          <span>{appText(`Не пришёл — можно отметить с ${from}`, `Килмәне — ${from} үткәс кенә билдәләп була`)}</span>
        </div>
      )}
      {asking && (
        <AlertDialog
          title={appText("Точно не пришёл?", "Ысынлап та килмәнемы?")}
          text={appText(
            "Бронь снимется, место вернётся в продажу. Если пассажир уже идёт — лучше подожди.",
            "Бронь алына, урын кире һатыуға ҡайта. Пассажир килеп барһа — әҙ генә көт."
          )}
          onClose={() => setAsking(false)}
          confirm={{
            label: appText("Да, не пришёл", "Эйе, килмәне"),
            tone: "danger",
            onClick: () => {
              setAsking(false);
              onConfirm();
            },
          }}
          dismiss={{ label: appText("Подождать", "Көтөргә"), onClick: () => setAsking(false) }}
        />
      )}
    </>
  );
}
