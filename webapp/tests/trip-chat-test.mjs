// ================================================================
//  Чат поездки: логика, которую не видно глазами.
//
//  Откуда. Android вынес чат на отдельный экран (PR #131), и PWA должен вести себя так же:
//   • «непрочитанные» считаются локально — сервер «прочитано» для чата брони не хранит;
//   • метка привязана ко входу и поездке: другой аккаунт / другая бронь её не видят;
//   • первый показ — вся загруженная история уже прочитана (лучше без бейджа, чем «12 новых»
//     на переписку, которую человек давно прочёл);
//   • лента «внизу» — с запасом, иначе человека, который чуть оттолкнул ленту, дёргало бы;
//   • клавиатура определяется по visualViewport, а щипок-зум клавиатурой не считается.
// ================================================================
import assert from "node:assert/strict";
import {
  NO_MARK,
  NEAR_BOTTOM_PX,
  KEYBOARD_MIN_PX,
  clearSeen,
  getSeen,
  isNearBottom,
  lastVisible,
  newestId,
  previewKind,
  setSeen,
  unreadCount,
  viewportBox,
  visibleMessages,
} from "../src/utils/tripChat.js";

const ME = 7;
const PEER = 9;
const msg = (id, sender, text = "привет", extra = {}) => ({ id, sender_id: sender, text, ...extra });

// ---------- метка прочитанного ----------
clearSeen();
assert.equal(getSeen("g1", 42), NO_MARK);
console.log("✓ метки нет → NO_MARK (первый показ)");

assert.equal(setSeen("g1", 42, 10), 10);
assert.equal(setSeen("g1", 42, 4), 10, "метка идёт только вперёд");
assert.equal(setSeen("g1", 42, 12), 12);
console.log("✓ метка двигается только вперёд");

assert.equal(getSeen("g2", 42), NO_MARK, "другой вход не видит чужую метку");
assert.equal(getSeen("g1", 43), NO_MARK, "другая поездка не видит чужую метку");
console.log("✓ метка привязана ко входу и поездке");

// ---------- непрочитанные ----------
const list = [msg(1, PEER), msg(2, ME), msg(3, PEER), msg(4, PEER, "ещё")];
assert.equal(unreadCount(list, NO_MARK, ME), 0, "метки нет → всё прочитано");
assert.equal(unreadCount(list, 0, ME), 3, "считаем только сообщения собеседника");
assert.equal(unreadCount(list, 2, ME), 2);
assert.equal(unreadCount(list, 4, ME), 0);
console.log("✓ непрочитанные: только чужие и только после метки");

assert.equal(
  unreadCount([msg(5, PEER, "", { deleted: true }), msg(6, PEER, "   ")], 4, ME),
  0,
  "удалённое и пустое технические сообщения бейдж не растят"
);
assert.equal(unreadCount([msg(5, PEER, "", { voice_url: "/v/1.webm" })], 4, ME), 1, "голосовое считается");
console.log("✓ удалённое и пустое не считаются, голос — считается");

// ---------- видимое, последнее, превью ----------
assert.deepEqual(
  visibleMessages([msg(1, PEER, "  "), msg(2, PEER, "", { deleted: true }), msg(3, PEER, "", { voice_url: "x" }), msg(4, ME, "да")]).map((m) => m.id),
  [2, 3, 4]
);
assert.equal(newestId(list), 4);
assert.equal(newestId([]), 0);
assert.equal(lastVisible([]), null);
assert.equal(lastVisible([msg(1, PEER), msg(2, PEER, "  ")]).id, 1);
console.log("✓ видимое / последнее / самый свежий id");

assert.deepEqual(previewKind(null), { kind: "none", text: "" });
assert.deepEqual(previewKind(msg(1, PEER, "", { deleted: true })), { kind: "deleted", text: "" });
assert.deepEqual(previewKind(msg(1, PEER, "", { voice_url: "x" })), { kind: "voice", text: "" });
assert.deepEqual(previewKind(msg(1, PEER, "[img]https://yulbash.ru/p.jpg")), { kind: "photo", text: "" });
assert.deepEqual(previewKind(msg(1, PEER, "  Я на месте ")), { kind: "text", text: "Я на месте" });
console.log("✓ превью карточки: удалено / голос / фото / текст");

// ---------- лента внизу ----------
assert.equal(isNearBottom(900, 100, 1000), true, "ровно внизу");
assert.equal(isNearBottom(900 - NEAR_BOTTOM_PX, 100, 1000), true, "в пределах запаса — всё ещё внизу");
assert.equal(isNearBottom(900 - NEAR_BOTTOM_PX - 1, 100, 1000), false, "дальше запаса — человек читает старое");
assert.equal(isNearBottom(0, 600, 600), true, "короткая лента, прокрутки нет");
console.log("✓ «лента внизу» с запасом; короткая лента — всегда внизу");

// ---------- клавиатура ----------
{
  const closed = viewportBox(800, 800, 0);
  assert.deepEqual(closed, { top: 0, height: 800, covered: 0, keyboardOpen: false });
  const open = viewportBox(800, 480, 0); // клавиатура съела 320
  assert.equal(open.height, 480);
  assert.equal(open.covered, 320);
  assert.equal(open.keyboardOpen, true);
  const iosScrolled = viewportBox(800, 480, 40); // iOS подвинул видимую область вниз
  assert.equal(iosScrolled.top, 40);
  assert.equal(iosScrolled.covered, 280);
  assert.equal(iosScrolled.keyboardOpen, true);
  const urlBar = viewportBox(800, 800 - (KEYBOARD_MIN_PX - 1), 0); // адресная строка — не клавиатура
  assert.equal(urlBar.keyboardOpen, false);
  const odd = viewportBox(800, 900, -5); // отрицательные и «лишние» значения не ломают рамку
  assert.equal(odd.top, 0);
  assert.equal(odd.covered, 0);
  console.log("✓ клавиатура: закрыта / открыта / iOS со сдвигом / адресная строка не в счёт / мусор не ломает");
}

console.log("\nвсё сходится");
