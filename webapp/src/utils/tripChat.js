// ================================================================
//  Чат поездки — логика без React и без сети, чтобы её можно было проверить.
//
//  Зеркало Android: TripChatState.kt (непрочитанные), TripChatScreen.kt (лента внизу,
//  «к новым», клавиатура). Сервер «прочитано» для чата брони не хранит, поэтому
//  считаем честно и локально: сколько сообщений собеседника пришло после того, как
//  человек последний раз смотрел в ленту.
// ================================================================

/** Метки ещё нет — первый показ чата. */
export const NO_MARK = -1;

/** Лента считается «внизу», если до конца не больше стольких пикселей. */
export const NEAR_BOTTOM_PX = 64;

/** Клавиатура открыта, если она съела больше стольких пикселей (адресная строка меньше). */
export const KEYBOARD_MIN_PX = 120;

/** Метки прочитанного: ключ «вход:поездка» → id последнего увиденного сообщения. */
const seenMarks = new Map();

const seenKey = (generation, bookingId) => `${generation}:${bookingId}`;

/** Метка для этой поездки в этом входе. Другой аккаунт её не увидит. */
export function getSeen(generation, bookingId) {
  const v = seenMarks.get(seenKey(generation, bookingId));
  return v === undefined ? NO_MARK : v;
}

/** Поставить метку. Только вперёд: старое сообщение не делает новое «непрочитанным». */
export function setSeen(generation, bookingId, id) {
  const key = seenKey(generation, bookingId);
  const cur = seenMarks.get(key);
  if (cur === undefined || id > cur) seenMarks.set(key, id);
  return seenMarks.get(key);
}

/** Для тестов и выхода из аккаунта. */
export function clearSeen() {
  seenMarks.clear();
}

/** Видимое в ленте: удалённое (след), голос и непустой текст. Пустые технические — нет. */
export function visibleMessages(list) {
  return (list || []).filter((m) => m.deleted || !!m.voice_url || (m.text || "").trim() !== "");
}

/** Самый свежий id в ленте (0 — пусто). */
export function newestId(list) {
  let top = 0;
  for (const m of list || []) if (m.id > top) top = m.id;
  return top;
}

/** Сколько сообщений собеседника пришло после метки. Метки нет — всё уже прочитано. */
export function unreadCount(list, seenId, myId) {
  if (seenId === NO_MARK) return 0;
  return visibleMessages(list).filter((m) => m.id > seenId && m.sender_id !== myId && !m.deleted).length;
}

/** Последнее видимое сообщение (для карточки на странице поездки). */
export function lastVisible(list) {
  const v = visibleMessages(list);
  return v.length ? v[v.length - 1] : null;
}

/** Что показать в превью: вид и текст. Язык подставляет экран. */
export function previewKind(m, imgPrefix = "[img]") {
  if (!m) return { kind: "none", text: "" };
  if (m.deleted) return { kind: "deleted", text: "" };
  if (m.voice_url) return { kind: "voice", text: "" };
  if ((m.text || "").startsWith(imgPrefix)) return { kind: "photo", text: "" };
  return { kind: "text", text: (m.text || "").trim() };
}

/** Лента у нижнего края? Нужно, чтобы не дёргать человека, который листает старое. */
export function isNearBottom(scrollTop, clientHeight, scrollHeight, threshold = NEAR_BOTTOM_PX) {
  return scrollHeight - (scrollTop + clientHeight) <= threshold;
}

/**
 * Область, которую реально видно человеку, когда открыта экранная клавиатура.
 * `innerHeight` — высота окна страницы, `vvHeight`/`vvTop` — размеры visualViewport.
 * Возвращает высоту и сдвиг сверху для рамки чата и признак «клавиатура открыта».
 */
export function viewportBox(innerHeight, vvHeight, vvTop) {
  const height = Math.max(0, Math.round(vvHeight));
  const top = Math.max(0, Math.round(vvTop));
  const covered = Math.max(0, Math.round(innerHeight - vvHeight - vvTop));
  return { top, height, covered, keyboardOpen: covered >= KEYBOARD_MIN_PX };
}
