// ================================================================
//  «Сел» — посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026).
//  Зеркало Android RideshareBoarding.kt; сервер — bookings.py: driver-status «boarded»
//  и /bookings/{id}/boarded/undo.
//
//  Раньше пассажир сам жал «Я сел»: нажать можно было из дома, чтобы водитель уже не
//  отметил «Не пришёл», а нажатое по ошибке было не вернуть. Теперь «Сел» жмёт водитель —
//  на карточке подтверждённого пассажира («Едут с тобой») и на экране поездки.
//
//  Промахнулся карточкой — «Отменить» в первые 5 минут, бронь снова подтверждена. Сколько
//  осталось, говорит сервер (`boarding_undo_sec`): часы телефона врут на минуты, а обратный
//  отсчёт от сервера — нет. Отсчёт кончился — вместо «Отменить» показываем то, что передал
//  родитель (`children`): в кабинете это разбор («Сообщить о проблеме»).
// ================================================================
import { useEffect, useState, type ReactNode } from "react";
import { useLang } from "../i18n/lang";
import { ApiError } from "../api/client";
import { markBoarded, undoBoarded, type BoardingResult, type BookingStatus } from "../api/bookings";
import { IconCheck } from "./Icons";

/** Секунды → «4:59» — сколько ещё можно отменить отметку. */
export function undoClock(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export default function DriverBoarding({
  bookingId,
  status,
  undoSec = 0,
  variant = "card",
  onChanged,
  onError,
  children,
}: {
  bookingId: number;
  status: BookingStatus | string;
  /** Сколько секунд ещё можно отменить отметку — из /driver/bookings или /bookings/{id}/role. */
  undoSec?: number;
  /** «card» — карточка пассажира в кабинете («Сел»); «chip» — ряд статусов на экране поездки. */
  variant?: "card" | "chip";
  /** Отметка или отмена прошла (или сервер отказал 409) — перечитать список / состояние поездки. */
  onChanged: (result?: BoardingResult) => void | Promise<void>;
  /** Отказ сервера или сети — показывает родитель, рядом с остальными сообщениями. */
  onError: (text: string) => void;
  /** Что показать «в пути», когда отменить уже нельзя (разбор в кабинете водителя). */
  children?: ReactNode;
}) {
  const { appText } = useLang();
  const [busy, setBusy] = useState(false);
  const [left, setLeft] = useState(undoSec);

  // Свежий ответ сервера сильнее локального отсчёта.
  useEffect(() => {
    setLeft(undoSec);
  }, [undoSec, status]);

  // Секундный отсчёт «Отменить · 4:59», пока отметку ещё можно вернуть.
  useEffect(() => {
    if (status !== "onboard" || left <= 0) return;
    const t = setTimeout(() => setLeft((v) => v - 1), 1000);
    return () => clearTimeout(t);
  }, [status, left]);

  async function run(action: () => Promise<BoardingResult>, fail: { ru: string; ba: string }) {
    if (busy) return;
    setBusy(true);
    try {
      const result = await action();
      await onChanged(result);
    } catch (e) {
      onError(e instanceof ApiError && e.message ? e.message : appText(fail.ru, fail.ba));
      // 409 — бронь ушла вперёд (отменили, завершили, окно вышло): перечитываем правду.
      if (e instanceof ApiError && e.status === 409) await onChanged();
    } finally {
      setBusy(false);
    }
  }

  const chip = variant === "chip";
  if (status === "confirmed") {
    return (
      <button
        type="button"
        className={chip ? "chip" : "btn-soft btn-soft--sm"}
        disabled={busy}
        onClick={() =>
          void run(() => markBoarded(bookingId), {
            ru: "Не получилось отметить. Проверь сеть.",
            ba: "Билдәләп булманы. Селтәрҙе тикшер.",
          })
        }
      >
        <IconCheck size={16} /> {chip ? appText("Пассажир сел", "Пассажир ултырҙы") : appText("Сел", "Ултырҙы")}
      </button>
    );
  }
  if (status !== "onboard") return null;
  const clock = undoClock(left);
  return (
    <>
      <span className={chip ? "chip chip--on" : "notice"} role="status">
        <IconCheck size={16} /> {appText("Пассажир в машине", "Пассажир машинала")}
      </span>
      {left > 0 && (
        <button
          type="button"
          className={chip ? "chip" : "btn-soft btn-soft--sm"}
          disabled={busy}
          aria-label={appText(`Отменить отметку посадки, осталось ${clock}`, `Ултырыу билдәһен кире алырға, ${clock} ҡалды`)}
          onClick={() =>
            void run(() => undoBoarded(bookingId), {
              ru: "Не получилось отменить. Проверь сеть.",
              ba: "Кире алып булманы. Селтәрҙе тикшер.",
            })
          }
        >
          {appText(`Отменить · ${clock}`, `Кире алырға · ${clock}`)}
        </button>
      )}
      {left <= 0 && children}
    </>
  );
}
