// ================================================================
//  «Оценить водителя» в квитанции попутки (приёмка 18.10, п.7; Android: ReceiptRateDriver.kt).
//
//  Было: пассажир пропустил экран «Поездка завершена» — и оценить позже негде. В истории
//  «Повторить маршрут» и «Квитанция», а квитанция показывала оценку, только если она уже стоит.
//  Стало: пять звёзд прямо в квитанции; касание сразу отправляет оценку, как в квитанции такси.
//  Экран показывает блок, пока сервер разрешает оценку (`can_rate`) и своей оценки ещё нет.
// ================================================================
import { useState } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { rateBooking } from "../api/bookings";
import { IconStar } from "./Icons";

export default function ReceiptRateDriver({
  bookingId,
  canAct,
  onRated,
  hint,
}: {
  bookingId: number;
  /** Квитанция ещё та же (тот же вход и та же бронь) — поздний ответ не трогает чужой экран. */
  canAct: () => boolean;
  onRated: (stars: number) => void;
  /** Своя подпись: тот же блок — на карточке «Поездку отменили в пути» (решение 24.10.2026). */
  hint?: string;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function rate(stars: number) {
    if (busy || !canAct()) return;
    setBusy(true);
    setError("");
    try {
      await rateBooking(bookingId, stars);
      if (canAct()) onRated(stars);
    } catch (e) {
      if (canAct()) setError(
        e instanceof ApiError && e.message
          ? e.message
          : appText("Оценка не ушла. Проверь сеть и попробуй ещё раз.", "Баһа ебәрелмәне. Селтәрҙе тикшереп, тағы бер тапҡыр ҡара.")
      );
    } finally {
      if (canAct()) setBusy(false);
    }
  }

  return (
    <div className="rcpt-rate">
      <span>{appText("Оценить водителя", "Йөрөтөүсене баһалау")}</span>
      <small className="rcpt-rate__hint">
        {hint ??
          appText(
            "Не успел оценить после поездки? Пара звёзд поможет другим выбрать.",
            "Сәфәрҙән һуң баһаларға өлгөрмәнеңме? Бер нисә йондоҙ башҡаларға һайларға ярҙам итер."
          )}
      </small>
      <div className="rcpt-rate__stars" role="group" aria-label={appText("Оценить водителя", "Йөрөтөүсене баһалау")}>
        {[1, 2, 3, 4, 5].map((v) => (
          <button
            key={v}
            type="button"
            aria-label={appText(`${v} из 5`, `5-тән ${v}`)}
            className="rcpt-rate__star"
            disabled={busy}
            onClick={() => void rate(v)}
          >
            <IconStar size={34} />
          </button>
        ))}
      </div>
      {error && <small className="rcpt-rate__err">{error}</small>}
    </div>
  );
}
