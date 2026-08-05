import { test } from "node:test";
import assert from "node:assert/strict";

import { CONFIG } from "../lib/config";
import {
  dateOfLetter,
  dawnProgress,
  daysUntilMeeting,
  letterNumberToday,
  plural,
  totalLetters,
} from "../lib/time";
import { LETTERS } from "../data/letters";

/**
 * Сторож дат.
 *
 * Всё на сайте считается от двух строк в config: startDate и meetDate.
 * Сдвинешь дату встречи — здесь сразу видно, сошлось ли число писем
 * и не осталось ли какой-то день без текста.
 *
 * Запуск: npm run check
 */

test("число писем совпадает с числом дней ожидания", () => {
  const total = totalLetters();
  assert.equal(
    LETTERS.length,
    total,
    `В отсчёте ${total} дней, а писем написано ${LETTERS.length}. ` +
      `Добавь или убери письма в data/letters.ts.`,
  );
});

test("номера писем идут подряд с первого", () => {
  LETTERS.forEach((l, i) => {
    assert.equal(l.n, i + 1, `Письмо на месте ${i + 1} имеет номер ${l.n}`);
  });
});

test("у каждого письма есть текст", () => {
  const empty = LETTERS.filter((l) => !l.ready || l.body.length === 0);
  assert.equal(
    empty.length,
    0,
    `Без текста остались письма: ${empty.map((l) => l.n).join(", ")}. ` +
      `В эти утра Илизе не уйдёт ничего.`,
  );
});

test("первое письмо приходит в день старта, последнее — в день встречи", () => {
  assert.equal(dateOfLetter(1), CONFIG.startDate);
  assert.equal(dateOfLetter(totalLetters()), CONFIG.meetDate);
});

test("в день встречи отсчёт равен нулю и небо рассвело", () => {
  assert.equal(daysUntilMeeting(CONFIG.meetDate), 0);
  assert.equal(dawnProgress(CONFIG.meetDate), 1);
});

test("в день старта небо ещё ночное, а письмо — первое", () => {
  assert.equal(dawnProgress(CONFIG.startDate), 0);
  assert.equal(letterNumberToday(CONFIG.startDate), 1);
});

test("после встречи отсчёт не уходит в минус", () => {
  assert.equal(daysUntilMeeting("2026-12-31"), 0);
  assert.equal(dawnProgress("2026-12-31"), 1);
});

test("до старта номер письма нулевой или отрицательный", () => {
  assert.ok(letterNumberToday("2026-08-01") < 1);
});

test("склонение дней", () => {
  assert.equal(plural(1, "день", "дня", "дней"), "день");
  assert.equal(plural(2, "день", "дня", "дней"), "дня");
  assert.equal(plural(5, "день", "дня", "дней"), "дней");
  assert.equal(plural(11, "день", "дня", "дней"), "дней");
  assert.equal(plural(21, "день", "дня", "дней"), "день");
});
