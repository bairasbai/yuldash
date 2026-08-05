import Link from "next/link";

/**
 * Своя страница «не найдено».
 *
 * Сюда попадают не только по опечатке в адресе: если открыть письмо,
 * до которого ещё не дошёл срок, сервер отвечает именно этим. Значит
 * текст должен быть частью игры, а не системной ошибкой.
 */
export default function NotFound() {
  return (
    <main className="relative flex min-h-dvh flex-col items-center justify-center px-6 text-center">
      <p className="rise font-serif text-[clamp(1.5rem,6vw,2.2rem)] leading-snug">
        Этого письма ещё нет
      </p>
      <p className="rise mt-4 max-w-xs font-serif text-lg italic leading-relaxed text-sky-ink-soft">
        Или ты забрела туда, где ничего не лежит. Бывает.
      </p>

      <div className="hairline mx-auto mt-8 w-24" />

      <Link
        href="/"
        className="mt-8 font-sans text-[0.66rem] uppercase tracking-[0.24em] text-sky-ink-soft transition-colors hover:text-sky-ink"
      >
        вернуться к отсчёту
      </Link>
    </main>
  );
}
