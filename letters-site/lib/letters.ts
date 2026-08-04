import { LETTERS, letterByNumber } from "@/data/letters";
import {
  dateOfLetter,
  humanDate,
  letterNumberToday,
  todayInHerCity,
  totalLetters,
} from "./time";

/**
 * Шлюз между текстами и страницами.
 *
 * Единственное место, где решается, можно ли показать письмо.
 * Всё, что ещё не наступило, наружу не выходит вообще — ни телом,
 * ни темой. В браузере запертого письма просто нет.
 */

export type OpenLetter = {
  n: number;
  date: string;
  dateLabel: string;
  body: string[];
  ba?: { text: string; ru: string };
  locked: false;
};

export type LockedLetter = {
  n: number;
  date: string;
  dateLabel: string;
  locked: true;
};

export type ArchiveItem = OpenLetter | LockedLetter;

function isUnlocked(n: number, today: string): boolean {
  return n <= letterNumberToday(today) && n >= 1;
}

/** Письмо для чтения. null — либо не наступило, либо текст ещё не написан. */
export function readLetter(
  n: number,
  today: string = todayInHerCity(),
): OpenLetter | null {
  if (!isUnlocked(n, today)) return null;

  const letter = letterByNumber(n);
  if (!letter || !letter.ready || letter.body.length === 0) return null;

  const date = dateOfLetter(n);
  return {
    n,
    date,
    dateLabel: humanDate(date),
    body: letter.body,
    ba: letter.ba,
    locked: false,
  };
}

/** Сегодняшнее письмо. null — игра ещё не началась или уже кончилась. */
export function todaysLetter(today: string = todayInHerCity()): OpenLetter | null {
  const n = letterNumberToday(today);
  if (n < 1 || n > totalLetters()) return null;
  return readLetter(n, today);
}

/**
 * Архив. У запертых писем отдаём только номер и дату — ни строчки текста.
 * Открытые отдаём целиком: их всё равно уже можно прочесть.
 */
export function archive(today: string = todayInHerCity()): ArchiveItem[] {
  return LETTERS.map((l) => {
    const date = dateOfLetter(l.n);
    const dateLabel = humanDate(date);

    const open = readLetter(l.n, today);
    if (open) return open;

    return { n: l.n, date, dateLabel, locked: true } as LockedLetter;
  });
}

/**
 * Предпросмотр без оглядки на дату. Только для страницы Байраса —
 * вызывать строго после проверки, что сессия его.
 */
export function previewLetter(n: number): OpenLetter | null {
  const letter = letterByNumber(n);
  if (!letter || letter.body.length === 0) return null;

  const date = dateOfLetter(n);
  return {
    n,
    date,
    dateLabel: humanDate(date),
    body: letter.body,
    ba: letter.ba,
    locked: false,
  };
}

/** Что готово, а что нет — сводка для автора. */
export function writingStatus(): {
  n: number;
  date: string;
  dateLabel: string;
  topic: string;
  ready: boolean;
}[] {
  return LETTERS.map((l) => {
    const date = dateOfLetter(l.n);
    return {
      n: l.n,
      date,
      dateLabel: humanDate(date),
      topic: l.topic,
      ready: l.ready && l.body.length > 0,
    };
  });
}

/** Сколько писем она уже прочла — для подписи в архиве. */
export function openedCount(today: string = todayInHerCity()): number {
  return archive(today).filter((l) => !l.locked).length;
}
