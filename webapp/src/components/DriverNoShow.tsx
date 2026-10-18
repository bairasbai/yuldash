// ================================================================
//  «Не вышел» — неявка пассажира в «Едут с тобой» (решение Александра 18.10.2026).
//  Зеркало Android RideshareNoShow.kt / RideshareBoarding.kt (ConfirmedPassengerControls);
//  сервер — bookings.py: `NO_SHOW_WAIT`, отказы `no_show_too_early` и `no_show_needs_arriving`.
//
//  Образец — BlaBlaCar: водитель ждёт 15 минут, прежде чем отметить неявку. Раньше кнопка работала
//  в любой момент — хоть за неделю до поездки — одним касанием, и вернуть было нельзя: промахнулся,
//  и бронь человека, который стоит у дороги, снята, а место ушло в продажу.
//
//  Срок знает только сервер: строка брони несёт момент `no_show_at` (поздний из «выезд» и первого
//  «Подъезжаю» + 15 минут), сайт 15 минут у себя не держит.
//   • до этого момента кнопка на своём месте, но неактивна, под ней — подпись «можно отметить с …»;
//     место занято заранее: «Сел» рядом не сдвигается, когда срок наступает (приёмка PR182);
//   • после — кнопка оживает, а нажатие сперва спрашивает «Точно не вышел?» и ничего не отправляет;
//   • «Да, не вышел» зовёт родителя (он шлёт запрос, показывает отказ сервера его словами и
//     перечитывает список), «Подождать» закрывает окно, бронь не тронута.
//  Поездка с остановками (решение 18.10.2026, `no_show_needs_arriving`): пассажира на остановке
//  сажают позже выезда, и неявки нет, пока он не получил «Водитель подъезжает». Тогда на месте
//  «Не вышел» — «Подъезжаю» (родитель шлёт тот же `driver-status`, что экран поездки, и перечитывает
//  список — придёт новый срок), под ней — «Сначала нажми «Подъезжаю» — пассажир получит уведомление».
//  Нет `no_show_at` (старый сервер) — кнопка есть, а последнее слово за сервером (409).
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import AlertDialog from "./AlertDialog";
import { IconCar, IconClock } from "./Icons";
import { dayMonthClock } from "../utils/format";
import { serverMs } from "../utils/serverTime";

export default function DriverNoShow({
  noShowAt,
  needsArriving = false,
  busy,
  onConfirm,
  onArriving,
}: {
  /** ISO UTC из `/driver/bookings`: с какого момента неявку можно отметить; нет — срок неизвестен. */
  noShowAt?: string | null;
  /** Поездка с остановками ждёт «Подъезжаю» по этой брони (`no_show_needs_arriving`). */
  needsArriving?: boolean;
  /** Запрос уже идёт (родитель держит блокировку на всю карточку). */
  busy: boolean;
  /** Водитель ответил «Да, не вышел»: отправить неявку. */
  onConfirm: () => void;
  /** «Подъезжаю» с карточки: отправить статус и перечитать список. */
  onArriving: () => void;
}) {
  const { appText } = useLang();
  const [now, setNow] = useState(() => Date.now());
  const [asking, setAsking] = useState(false);

  const opensAt = serverMs(noShowAt);
  // Срок неизвестен (старый сервер, выезд не задан, кривая строка) — не прячем: решает сервер.
  const open = Number.isNaN(opensAt) || now >= opensAt;
  const slot = needsArriving ? "arriving" : open ? "ready" : "waiting";

  // Пока срок впереди, перечитываем часы раз в секунду — кнопка сама оживёт в нужную секунду.
  // Срок наступил, его нет или ждём «Подъезжаю» — таймера нет. Таймер вкладки в фоне браузер
  // замедляет, но следующее срабатывание берёт время заново.
  useEffect(() => {
    if (slot !== "waiting") return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [slot]);

  if (slot === "arriving") {
    return (
      <>
        <button type="button" className="btn-soft btn-soft--sm noshow-slot" disabled={busy} onClick={onArriving}>
          <IconCar size={16} /> {appText("Подъезжаю", "Килеп етәм")}
        </button>
        <div key="arriving" className="list-row__sub noshow-wait" role="note">
          <IconCar size={16} />
          <span>
            {appText(
              "Сначала нажми «Подъезжаю» — пассажир получит уведомление",
              "Башта «Килеп етәм»гә баҫ — пассажир хәбәр алыр"
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
        {appText("Не вышел", "Сыҡманы")}
      </button>
      {slot === "waiting" && (
        <div key="waiting" className="list-row__sub noshow-wait" role="note">
          <IconClock size={16} />
          <span>{appText(`Не вышел — можно отметить с ${from}`, `Сыҡманы — ${from} үткәс кенә билдәләп була`)}</span>
        </div>
      )}
      {asking && (
        <AlertDialog
          title={appText("Точно не вышел?", "Ысынлап та сыҡманымы?")}
          text={appText(
            "Бронь снимется, место вернётся в продажу. Если пассажир уже идёт — лучше подожди.",
            "Бронь алына, урын кире һатыуға ҡайта. Пассажир килеп барһа — әҙ генә көт."
          )}
          onClose={() => setAsking(false)}
          confirm={{
            label: appText("Да, не вышел", "Эйе, сыҡманы"),
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
