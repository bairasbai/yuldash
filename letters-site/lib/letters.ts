import { humanDate, todayInHerCity } from "./time";
import {
  dateOfLetterIn,
  letterNumberIn,
  letterOf,
  lettersOf,
  totalOf,
  type Parting,
  type StoredLetter,
} from "./partings";

/**
 * Шлюз между письмами и страницами.
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

/** Текст письма хранится одной строкой, абзацы разделены пустой строкой. */
function toParagraphs(body: string): string[] {
  return body
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter(Boolean);
}

function toOpen(p: Parting, l: StoredLetter): OpenLetter {
  const date = dateOfLetterIn(p, l.n);
  return {
    n: l.n,
    date,
    dateLabel: humanDate(date),
    body: toParagraphs(l.body),
    ba: l.ba_text ? { text: l.ba_text, ru: l.ba_ru } : undefined,
    locked: false,
  };
}

function isUnlocked(p: Parting, n: number, today: string): boolean {
  return n >= 1 && n <= letterNumberIn(p, today);
}

/** Письмо для чтения. null — не наступило или текст ещё не написан. */
export async function readLetter(
  parting: Parting,
  n: number,
  today: string = todayInHerCity(),
): Promise<OpenLetter | null> {
  if (!isUnlocked(parting, n, today)) return null;

  const letter = await letterOf(parting.id, n);
  if (!letter || !letter.ready || !letter.body.trim()) return null;

  return toOpen(parting, letter);
}

/** Сегодняшнее письмо этой разлуки. */
export async function todaysLetter(
  parting: Parting,
  today: string = todayInHerCity(),
): Promise<OpenLetter | null> {
  const n = letterNumberIn(parting, today);
  if (n < 1 || n > totalOf(parting)) return null;
  return readLetter(parting, n, today);
}

/**
 * Архив разлуки. У запертых писем отдаём только номер и дату —
 * ни строчки текста.
 */
export async function archive(
  parting: Parting,
  today: string = todayInHerCity(),
): Promise<ArchiveItem[]> {
  const stored = await lettersOf(parting.id);

  return stored.map((l) => {
    const date = dateOfLetterIn(parting, l.n);
    const dateLabel = humanDate(date);

    if (isUnlocked(parting, l.n, today) && l.ready && l.body.trim()) {
      return toOpen(parting, l);
    }
    return { n: l.n, date, dateLabel, locked: true } as LockedLetter;
  });
}

/** Сколько писем уже открыто — для подписи в архиве. */
export async function openedCount(
  parting: Parting,
  today: string = todayInHerCity(),
): Promise<number> {
  return (await archive(parting, today)).filter((l) => !l.locked).length;
}

/**
 * Предпросмотр без оглядки на дату. Только для страницы Байраса —
 * вызывать строго после проверки, что сессия его.
 */
export async function previewLetter(
  parting: Parting,
  n: number,
): Promise<OpenLetter | null> {
  const letter = await letterOf(parting.id, n);
  if (!letter || !letter.body.trim()) return null;
  return toOpen(parting, letter);
}

/** Что написано, а что нет — сводка для автора. */
export async function writingStatus(parting: Parting): Promise<
  {
    n: number;
    date: string;
    dateLabel: string;
    topic: string;
    body: string;
    baText: string;
    baRu: string;
    ready: boolean;
  }[]
> {
  const stored = await lettersOf(parting.id);
  return stored.map((l) => {
    const date = dateOfLetterIn(parting, l.n);
    return {
      n: l.n,
      date,
      dateLabel: humanDate(date),
      topic: l.topic,
      body: l.body,
      baText: l.ba_text,
      baRu: l.ba_ru,
      ready: l.ready && Boolean(l.body.trim()),
    };
  });
}
