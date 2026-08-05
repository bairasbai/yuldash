import postgres from "postgres";

/**
 * Хранилище: желания, настроения, ответы, капсула.
 *
 * Базы может не быть — тогда сайт не падает, а просто показывает
 * разделы пустыми. Письма и отсчёт от неё не зависят вообще:
 * они лежат в коде, и утренняя доставка работает без базы.
 */

let sql: ReturnType<typeof postgres> | null = null;
let schemaReady: Promise<void> | null = null;

export function hasDatabase(): boolean {
  return Boolean(process.env.DATABASE_URL);
}

function client() {
  if (!process.env.DATABASE_URL) return null;
  if (!sql) {
    sql = postgres(process.env.DATABASE_URL, {
      // Serverless: соединений мало, живут недолго
      max: 3,
      idle_timeout: 20,
      connect_timeout: 10,
    });
  }
  return sql;
}

/**
 * Таблицы создаются при первом обращении. Для сайта на двоих это
 * честнее отдельного механизма миграций: одна операция, идемпотентная.
 */
async function ensureSchema(db: ReturnType<typeof postgres>): Promise<void> {
  if (!schemaReady) {
    schemaReady = (async () => {
      await db`
        CREATE TABLE IF NOT EXISTS wishes (
          id          SERIAL PRIMARY KEY,
          text        TEXT NOT NULL,
          author      TEXT NOT NULL,
          done        BOOLEAN NOT NULL DEFAULT FALSE,
          created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )`;
      await db`
        CREATE TABLE IF NOT EXISTS moods (
          day        DATE NOT NULL,
          author     TEXT NOT NULL,
          value      TEXT NOT NULL,
          note       TEXT NOT NULL DEFAULT '',
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY (day, author)
        )`;
      await db`
        CREATE TABLE IF NOT EXISTS day_answers (
          day        DATE NOT NULL,
          author     TEXT NOT NULL,
          text       TEXT NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY (day, author)
        )`;
      /*
        Разлука — период между «улетел» и «встретились». Их может быть
        сколько угодно: прилетел 21 августа, улетел 23-го — начинается
        следующая. У каждой свой отсчёт и свои письма.
      */
      await db`
        CREATE TABLE IF NOT EXISTS partings (
          id         SERIAL PRIMARY KEY,
          title      TEXT NOT NULL DEFAULT '',
          start_date DATE NOT NULL,
          meet_date  DATE NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )`;
      await db`
        CREATE TABLE IF NOT EXISTS letters (
          id         SERIAL PRIMARY KEY,
          parting_id INTEGER NOT NULL REFERENCES partings(id) ON DELETE CASCADE,
          n          INTEGER NOT NULL,
          topic      TEXT NOT NULL DEFAULT '',
          body       TEXT NOT NULL DEFAULT '',
          ba_text    TEXT NOT NULL DEFAULT '',
          ba_ru      TEXT NOT NULL DEFAULT '',
          ready      BOOLEAN NOT NULL DEFAULT FALSE,
          UNIQUE (parting_id, n)
        )`;

      await db`
        CREATE TABLE IF NOT EXISTS letter_replies (
          id         SERIAL PRIMARY KEY,
          letter_n   INTEGER NOT NULL,
          author     TEXT NOT NULL,
          text       TEXT NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )`;
      // Ответы тоже привязаны к разлуке: номера писем повторяются
      // в каждой новой, иначе ответы смешаются между периодами
      await db`
        ALTER TABLE letter_replies
        ADD COLUMN IF NOT EXISTS parting_id INTEGER`;
      await db`
        CREATE TABLE IF NOT EXISTS capsule (
          author     TEXT PRIMARY KEY,
          text       TEXT NOT NULL,
          open_at    DATE NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )`;
    })().catch((e) => {
      // Не даём одной неудаче навсегда «застрять» в кеше промиса
      schemaReady = null;
      throw e;
    });
  }
  return schemaReady;
}

/**
 * Единая точка входа: даёт готовое подключение или null.
 * Вызывающий код обязан уметь работать с null — это и есть
 * поведение сайта без базы.
 */
export async function db(): Promise<ReturnType<typeof postgres> | null> {
  const c = client();
  if (!c) return null;
  try {
    await ensureSchema(c);
    return c;
  } catch {
    // База указана, но недоступна — ведём себя как будто её нет
    return null;
  }
}
