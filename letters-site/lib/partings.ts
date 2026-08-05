import { db } from "./db";
import { CONFIG } from "./config";
import { LETTERS as SEED_LETTERS } from "@/data/letters";
import { daysBetween, humanDate, todayInHerCity } from "./time";

/**
 * Разлуки.
 *
 * Раньше отсчёт был один и жил в конфиге. Теперь их сколько угодно:
 * прилетел двадцать первого, улетел двадцать третьего — заводится
 * следующая, со своими датами и своими письмами.
 *
 * Пока разлука идёт, сайт живёт отсчётом. Когда вы вместе — он
 * становится архивом всего, что уже написано.
 */

export type Parting = {
  id: number;
  title: string;
  start_date: string;
  meet_date: string;
};

export type StoredLetter = {
  id: number;
  parting_id: number;
  n: number;
  topic: string;
  body: string;
  ba_text: string;
  ba_ru: string;
  ready: boolean;
};

/* ── Периоды ───────────────────────────────────────────────────── */

export async function listPartings(): Promise<Parting[]> {
  const sql = await db();
  if (!sql) return [];
  await seedIfEmpty();
  const rows = await sql<Parting[]>`
    SELECT id, title,
           to_char(start_date, 'YYYY-MM-DD') AS start_date,
           to_char(meet_date,  'YYYY-MM-DD') AS meet_date
    FROM partings ORDER BY start_date DESC`;
  return rows;
}

/** Разлука, которая идёт прямо сейчас. null — значит вы вместе. */
export async function activeParting(
  today: string = todayInHerCity(),
): Promise<Parting | null> {
  const sql = await db();
  if (!sql) return null;
  await seedIfEmpty();
  const [row] = await sql<Parting[]>`
    SELECT id, title,
           to_char(start_date, 'YYYY-MM-DD') AS start_date,
           to_char(meet_date,  'YYYY-MM-DD') AS meet_date
    FROM partings
    WHERE start_date <= ${today} AND meet_date >= ${today}
    ORDER BY start_date DESC LIMIT 1`;
  return row ?? null;
}

/** Ближайшая будущая разлука — когда даты уже назначены, но ещё не начались. */
export async function upcomingParting(
  today: string = todayInHerCity(),
): Promise<Parting | null> {
  const sql = await db();
  if (!sql) return null;
  const [row] = await sql<Parting[]>`
    SELECT id, title,
           to_char(start_date, 'YYYY-MM-DD') AS start_date,
           to_char(meet_date,  'YYYY-MM-DD') AS meet_date
    FROM partings WHERE start_date > ${today}
    ORDER BY start_date ASC LIMIT 1`;
  return row ?? null;
}

export async function partingById(id: number): Promise<Parting | null> {
  const sql = await db();
  if (!sql) return null;
  const [row] = await sql<Parting[]>`
    SELECT id, title,
           to_char(start_date, 'YYYY-MM-DD') AS start_date,
           to_char(meet_date,  'YYYY-MM-DD') AS meet_date
    FROM partings WHERE id = ${id}`;
  return row ?? null;
}

/**
 * Завести новую разлуку. Письма создаются сразу — пустые болванки
 * по числу дней, чтобы их оставалось только заполнить.
 */
export async function createParting(
  title: string,
  startDate: string,
  meetDate: string,
): Promise<number | null> {
  const sql = await db();
  if (!sql) return null;

  const [row] = await sql<{ id: number }[]>`
    INSERT INTO partings (title, start_date, meet_date)
    VALUES (${title}, ${startDate}, ${meetDate})
    RETURNING id`;

  const total = daysBetween(startDate, meetDate) + 1;
  for (let n = 1; n <= total; n++) {
    await sql`
      INSERT INTO letters (parting_id, n) VALUES (${row.id}, ${n})
      ON CONFLICT (parting_id, n) DO NOTHING`;
  }
  return row.id;
}

export async function deleteParting(id: number): Promise<void> {
  const sql = await db();
  if (!sql) return;
  await sql`DELETE FROM partings WHERE id = ${id}`;
}

/* ── Письма разлуки ────────────────────────────────────────────── */

export async function lettersOf(partingId: number): Promise<StoredLetter[]> {
  const sql = await db();
  if (!sql) return [];
  return sql<StoredLetter[]>`
    SELECT id, parting_id, n, topic, body, ba_text, ba_ru, ready
    FROM letters WHERE parting_id = ${partingId} ORDER BY n ASC`;
}

export async function letterOf(
  partingId: number,
  n: number,
): Promise<StoredLetter | null> {
  const sql = await db();
  if (!sql) return null;
  const [row] = await sql<StoredLetter[]>`
    SELECT id, parting_id, n, topic, body, ba_text, ba_ru, ready
    FROM letters WHERE parting_id = ${partingId} AND n = ${n}`;
  return row ?? null;
}

export async function saveLetter(
  partingId: number,
  n: number,
  fields: {
    topic?: string;
    body?: string;
    baText?: string;
    baRu?: string;
    ready?: boolean;
  },
): Promise<void> {
  const sql = await db();
  if (!sql) return;
  await sql`
    INSERT INTO letters (parting_id, n, topic, body, ba_text, ba_ru, ready)
    VALUES (
      ${partingId}, ${n},
      ${fields.topic ?? ""}, ${fields.body ?? ""},
      ${fields.baText ?? ""}, ${fields.baRu ?? ""},
      ${fields.ready ?? false}
    )
    ON CONFLICT (parting_id, n) DO UPDATE SET
      topic   = COALESCE(${fields.topic ?? null}, letters.topic),
      body    = COALESCE(${fields.body ?? null}, letters.body),
      ba_text = COALESCE(${fields.baText ?? null}, letters.ba_text),
      ba_ru   = COALESCE(${fields.baRu ?? null}, letters.ba_ru),
      ready   = COALESCE(${fields.ready ?? null}, letters.ready)`;
}

/* ── Первый запуск ─────────────────────────────────────────────── */

let seeded = false;

/**
 * При первом обращении переносим в базу то, что уже написано в коде:
 * первую разлуку до двадцать первого августа и семнадцать писем.
 * Дальше всё живёт в базе и правится прямо на сайте.
 */
export async function seedIfEmpty(): Promise<void> {
  if (seeded) return;
  const sql = await db();
  if (!sql) return;

  const [{ count }] = await sql<{ count: string }[]>`
    SELECT COUNT(*)::text AS count FROM partings`;
  if (Number(count) > 0) {
    seeded = true;
    return;
  }

  const [row] = await sql<{ id: number }[]>`
    INSERT INTO partings (title, start_date, meet_date)
    VALUES (${"До Уфы"}, ${CONFIG.startDate}, ${CONFIG.meetDate})
    RETURNING id`;

  for (const l of SEED_LETTERS) {
    await sql`
      INSERT INTO letters (parting_id, n, topic, body, ba_text, ba_ru, ready)
      VALUES (
        ${row.id}, ${l.n}, ${l.topic},
        ${l.body.join("\n\n")},
        ${l.ba?.text ?? ""}, ${l.ba?.ru ?? ""},
        ${l.ready && l.body.length > 0}
      )
      ON CONFLICT (parting_id, n) DO NOTHING`;
  }
  seeded = true;
}

/* ── Счёт дней внутри разлуки ──────────────────────────────────── */

/** Сколько писем в этой разлуке — по числу дней. */
export function totalOf(p: Parting): number {
  return daysBetween(p.start_date, p.meet_date) + 1;
}

/** Дата письма №n этой разлуки. */
export function dateOfLetterIn(p: Parting, n: number): string {
  const start = Date.parse(`${p.start_date}T00:00:00Z`);
  return new Date(start + (n - 1) * 86_400_000).toISOString().slice(0, 10);
}

/** Номер сегодняшнего письма внутри разлуки. */
export function letterNumberIn(p: Parting, today: string): number {
  return daysBetween(p.start_date, today) + 1;
}

export function daysUntilMeetIn(p: Parting, today: string): number {
  return Math.max(0, daysBetween(today, p.meet_date));
}

/** Насколько рассвело внутри этой разлуки: 0 — первый день, 1 — встреча. */
export function progressOf(p: Parting, today: string): number {
  const total = daysBetween(p.start_date, p.meet_date);
  if (total <= 0) return 1;
  const passed = daysBetween(p.start_date, today);
  return Math.min(1, Math.max(0, passed / total));
}

/* ── Подписи ───────────────────────────────────────────────────── */

export function partingLabel(p: Parting): string {
  return p.title || `${humanDate(p.start_date)} — ${humanDate(p.meet_date)}`;
}
