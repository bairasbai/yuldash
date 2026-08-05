import { db } from "./db";

/**
 * Всё, что сайт помнит про вас двоих.
 *
 * Каждая функция переживает отсутствие базы: возвращает пустоту
 * вместо ошибки. Разделы тогда выглядят пустыми, но ничего не ломается.
 */

export type Who = "her" | "him";

/* ── Что сделаем в Уфе ─────────────────────────────────────────── */

export type Wish = {
  id: number;
  text: string;
  author: Who;
  done: boolean;
};

export async function listWishes(): Promise<Wish[]> {
  const sql = await db();
  if (!sql) return [];
  const rows = await sql<Wish[]>`
    SELECT id, text, author, done FROM wishes
    ORDER BY done ASC, created_at DESC`;
  return rows.map((r) => ({ ...r, author: r.author as Who }));
}

export async function addWish(text: string, author: Who): Promise<boolean> {
  const sql = await db();
  if (!sql) return false;
  await sql`INSERT INTO wishes (text, author) VALUES (${text}, ${author})`;
  return true;
}

export async function setWishDone(id: number, done: boolean): Promise<void> {
  const sql = await db();
  if (!sql) return;
  await sql`UPDATE wishes SET done = ${done} WHERE id = ${id}`;
}

export async function removeWish(id: number): Promise<void> {
  const sql = await db();
  if (!sql) return;
  await sql`DELETE FROM wishes WHERE id = ${id}`;
}

/* ── Настроение дня ────────────────────────────────────────────── */

export type Mood = { day: string; author: Who; value: string; note: string };

export async function setMood(
  day: string,
  author: Who,
  value: string,
  note: string,
): Promise<boolean> {
  const sql = await db();
  if (!sql) return false;
  await sql`
    INSERT INTO moods (day, author, value, note)
    VALUES (${day}, ${author}, ${value}, ${note})
    ON CONFLICT (day, author)
    DO UPDATE SET value = EXCLUDED.value, note = EXCLUDED.note`;
  return true;
}

export async function moodOn(day: string, author: Who): Promise<Mood | null> {
  const sql = await db();
  if (!sql) return null;
  const [row] = await sql<Mood[]>`
    SELECT to_char(day, 'YYYY-MM-DD') AS day, author, value, note
    FROM moods WHERE day = ${day} AND author = ${author}`;
  return row ?? null;
}

/** История настроений — для полоски за месяц. */
export async function moodHistory(author: Who, limit = 40): Promise<Mood[]> {
  const sql = await db();
  if (!sql) return [];
  const rows = await sql<Mood[]>`
    SELECT to_char(day, 'YYYY-MM-DD') AS day, author, value, note
    FROM moods WHERE author = ${author}
    ORDER BY day DESC LIMIT ${limit}`;
  return rows.reverse();
}

/* ── Вопрос дня ────────────────────────────────────────────────── */

export type DayAnswer = { author: Who; text: string };

export async function answersOn(day: string): Promise<DayAnswer[]> {
  const sql = await db();
  if (!sql) return [];
  const rows = await sql<DayAnswer[]>`
    SELECT author, text FROM day_answers WHERE day = ${day}`;
  return rows.map((r) => ({ ...r, author: r.author as Who }));
}

export async function answerDay(
  day: string,
  author: Who,
  text: string,
): Promise<boolean> {
  const sql = await db();
  if (!sql) return false;
  await sql`
    INSERT INTO day_answers (day, author, text)
    VALUES (${day}, ${author}, ${text})
    ON CONFLICT (day, author) DO UPDATE SET text = EXCLUDED.text`;
  return true;
}

/* ── Ответы на письма ──────────────────────────────────────────── */

export type Reply = {
  id: number;
  letter_n: number;
  author: Who;
  text: string;
  created_at: string;
};

export async function saveReply(
  partingId: number,
  letterN: number,
  author: Who,
  text: string,
): Promise<void> {
  const sql = await db();
  if (!sql) return;
  await sql`
    INSERT INTO letter_replies (parting_id, letter_n, author, text)
    VALUES (${partingId}, ${letterN}, ${author}, ${text})`;
}

/** Ответы на письмо. Номера писем повторяются в каждой разлуке,
 *  поэтому спрашиваем всегда вместе с её номером. */
export async function repliesFor(
  partingId: number,
  letterN: number,
): Promise<Reply[]> {
  const sql = await db();
  if (!sql) return [];
  const rows = await sql<Reply[]>`
    SELECT id, letter_n, author, text,
           to_char(created_at, 'DD.MM HH24:MI') AS created_at
    FROM letter_replies
    WHERE letter_n = ${letterN} AND parting_id = ${partingId}
    ORDER BY created_at ASC`;
  return rows.map((r) => ({ ...r, author: r.author as Who }));
}

export async function latestReplies(limit = 20): Promise<Reply[]> {
  const sql = await db();
  if (!sql) return [];
  const rows = await sql<Reply[]>`
    SELECT id, letter_n, author, text,
           to_char(created_at, 'DD.MM HH24:MI') AS created_at
    FROM letter_replies ORDER BY created_at DESC LIMIT ${limit}`;
  return rows.map((r) => ({ ...r, author: r.author as Who }));
}

/* ── Капсула времени ───────────────────────────────────────────── */

export type Capsule = { author: Who; text: string; open_at: string };

export async function getCapsule(author: Who): Promise<Capsule | null> {
  const sql = await db();
  if (!sql) return null;
  const [row] = await sql<Capsule[]>`
    SELECT author, text, to_char(open_at, 'YYYY-MM-DD') AS open_at
    FROM capsule WHERE author = ${author}`;
  return row ?? null;
}

export async function sealCapsule(
  author: Who,
  text: string,
  openAt: string,
): Promise<boolean> {
  const sql = await db();
  if (!sql) return false;
  // Запечатанное не переписывается: в этом весь смысл капсулы
  await sql`
    INSERT INTO capsule (author, text, open_at)
    VALUES (${author}, ${text}, ${openAt})
    ON CONFLICT (author) DO NOTHING`;
  return true;
}
