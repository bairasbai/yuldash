// ================================================================
//  «Не вышел» — неявка пассажира в «Едут с тобой» (решение Александра 18.10.2026).
//  Зеркало Android RideshareNoShow.kt; сервер — bookings.py: `NO_SHOW_WAIT`, отказ `no_show_too_early`.
//
//  Образец — BlaBlaCar: водитель ждёт 15 минут после времени выезда, прежде чем отметить неявку.
//  Раньше кнопка работала в любой момент — хоть за неделю до поездки — одним касанием, и вернуть
//  было нельзя: промахнулся, и бронь человека, который стоит у дороги, снята, а место ушло в продажу.
//
//  Срок знает только сервер: строка брони несёт момент `no_show_at`, сайт 15 минут у себя не держит.
//   • до этого момента вместо кнопки — спокойная подпись «можно отметить с 18.10, 14:35»;
//   • после — кнопка, а нажатие сперва спрашивает «Точно не вышел?» и ничего не отправляет;
//   • «Да, не вышел» зовёт родителя (он шлёт запрос, показывает отказ сервера его словами и
//     перечитывает список), «Подождать» закрывает окно, бронь не тронута.
//  Нет `no_show_at` (старый сервер) — кнопка есть, а последнее слово за сервером (409).
// ================================================================
import { useEffect, useState } from "react";
import { useLang } from "../i18n/lang";
import AlertDialog from "./AlertDialog";
import { IconClock } from "./Icons";
import { dayMonthClock } from "../utils/format";
import { serverMs } from "../utils/serverTime";

export default function DriverNoShow({
  noShowAt,
  busy,
  onConfirm,
}: {
  /** ISO UTC из `/driver/bookings`: с какого момента неявку можно отметить; нет — срок неизвестен. */
  noShowAt?: string | null;
  /** Запрос уже идёт (родитель держит блокировку на всю карточку). */
  busy: boolean;
  /** Водитель ответил «Да, не вышел»: отправить неявку. */
  onConfirm: () => void;
}) {
  const { appText } = useLang();
  const [now, setNow] = useState(() => Date.now());
  const [asking, setAsking] = useState(false);

  const opensAt = serverMs(noShowAt);
  // Срок неизвестен (старый сервер, выезд не задан, кривая строка) — не прячем: решает сервер.
  const open = Number.isNaN(opensAt) || now >= opensAt;

  // До срока перечитываем часы раз в секунду — подпись сама сменится кнопкой в нужную минуту.
  // Таймер вкладки в фоне браузер замедляет, но следующее срабатывание берёт время заново.
  useEffect(() => {
    if (open) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [open]);

  if (!open) {
    const from = dayMonthClock(noShowAt);
    return (
      <div className="list-row__sub noshow-wait" role="note">
        <IconClock size={16} />
        <span>{appText(`Не вышел — можно отметить с ${from}`, `Сыҡманы — ${from} үткәс кенә билдәләп була`)}</span>
      </div>
    );
  }

  return (
    <>
      <button type="button" className="btn-soft btn-soft--sm" disabled={busy} onClick={() => setAsking(true)}>
        {appText("Не вышел", "Сыҡманы")}
      </button>
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
