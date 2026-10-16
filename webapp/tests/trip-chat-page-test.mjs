// ================================================================
//  Чат поездки — отдельная страница, строка ввода внизу экрана.
//
//  Жалоба: «строка ввода стояла посередине, а должна быть внизу, как в Telegram». Причина —
//  чат был куском длинной страницы поездки. Эта проверка читает исходники и держит три вещи:
//   1. страница /trip/:id/chat существует, маршрут зарегистрирован, чат ушёл со страницы поездки;
//   2. строка ввода ВСЕГДА у нижнего края: рамка на весь видимый экран, лента растёт,
//      низ не сжимается, клавиатура учитывается через visualViewport, отступ снизу один;
//   3. входы: карточка и «Написать» на странице поездки, список чатов, «Мои поездки».
//  Каждая надпись — на двух языках (appText с двумя строками).
// ================================================================
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "..", "src");
const read = (p) => readFileSync(join(SRC, p), "utf8");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "\n    " + extra : ""}`);
};

/** Без комментариев — объяснения прошлых ошибок не должны сходить за код. */
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/[^\n]*/g, "$1");

const app = read("App.tsx");
const screen = read("screens/TripChatScreen.tsx");
const screenCode = strip(screen);
const card = read("components/TripChatCard.tsx");
const trip = read("screens/ActiveTripScreen.tsx");
const tripCode = strip(trip);
const inbox = read("screens/ChatInboxScreen.tsx");
const myRides = read("screens/MyRidesScreen.tsx");
const css = read("ui.css");

// ---------- 1. Страница и маршрут ----------
check(screen.includes("export default function TripChatScreen"), "страница чата поездки существует");
check(
  /lazyScreen\(\(\) => import\("\.\/screens\/TripChatScreen"\)\)/.test(app),
  "страница подгружается отдельным куском (lazyScreen)"
);
{
  const m = app.match(/path="\/trip\/:id\/chat"\s+element=\{\s*<RequireAuth>\s*<TripChatScreen \/>\s*<\/RequireAuth>/);
  check(!!m, "маршрут /trip/:id/chat зарегистрирован и закрыт входом (RequireAuth)");
}
check(
  app.indexOf('path="/trip/:id"') !== -1 && app.indexOf('path="/trip/:id/chat"') !== -1,
  "маршрут поездки /trip/:id остался рядом"
);

// ---------- Чат ушёл со страницы поездки ----------
check(
  !/openBookingChat|sendMessageRest|chat__input|ChatVoiceButton|ChatPhotoButton|function TripChat\b/.test(tripCode),
  "на странице поездки больше нет встроенного чата (сокет, поле ввода, голос, фото)"
);
check(tripCode.includes("<TripChatCard"), "на странице поездки вместо чата — карточка «Чат по поездке»");
check(
  /navigate\(`\/trip\/\$\{bookingId\}\/chat`/.test(tripCode),
  "карточка открывает /trip/:id/chat"
);

// ---------- Логика чата перенесена, а не переписана ----------
for (const [label, re] of [
  ["живой WebSocket", /openBookingChat\(/],
  ["фолбэк на REST", /sendMessageRest\(/],
  ["очередь без сети (outbox)", /enqueue\(bookingId, "message"/],
  ["досылка при возврате сети", /watchOutbox\(/],
  ["поллинг, когда сокета нет", /window\.setInterval/],
  ["правка сообщения", /editMessage\(/],
  ["удаление у всех / у себя", /deleteMessage\(/],
  ["голосовое", /<ChatVoiceButton/],
  ["фото", /<ChatPhotoButton/],
  ["быстрые фразы", /<QuickReplies/],
  ["дисклеймер безопасности", /<ChatSafetyDisclaimer/],
  ["плашка-предупреждение под сообщением", /<ChatFlagPlate/],
  ["защита от смены входа (getSessionGeneration)", /getSessionGeneration\(\)/],
]) {
  check(re.test(screenCode), `логика на месте: ${label}`);
}

// ---------- Состояния ----------
for (const [label, re] of [
  ["загрузка (скелетон)", /chat__loading/],
  ["пусто", /Напиши первым/],
  ["ошибка с «Повторить»", /Не удалось загрузить чат[\s\S]*Повторить/],
  ["«соединение восстанавливается»", /Соединение восстанавливается/],
  ["«отправим сами, когда будет сеть»", /отправим сами/],
]) {
  check(re.test(screenCode), `состояние: ${label}`);
}

// ---------- Шапка ----------
{
  const head = screenCode.slice(screenCode.indexOf('<header className="tchat__head">'), screenCode.indexOf("</header>"));
  check(head.includes('appText("Назад"') && head.includes("onClick={onBack}"), "шапка: «Назад»");
  check(/Открыть поездку/.test(head) && head.includes("onClick={onOpenTrip}"), "шапка: «Открыть поездку»");
  check(
    head.includes("onClick={onSos}") && /Экстренный вызов/.test(head) && /onSos=\{\(\) => navigate\(sosPath\(bookingId\)\)\}/.test(screenCode),
    "шапка: SOS ведёт на /sos?booking=<id> (бронь уходит дежурному)"
  );
  check(/<h1>\{peer/.test(head) && /\{route && <p>\{route\}<\/p>\}/.test(head), "шапка: собеседник и маршрут");
}

// ---------- 2. Строка ввода внизу ----------
{
  const iHead = screenCode.indexOf('<header className="tchat__head">');
  const iMain = screenCode.indexOf('<div className="tchat__main">');
  const iFoot = screenCode.indexOf('<div className="tchat__foot">');
  const iInput = screenCode.indexOf('className="chat__input tchat__input"');
  check(iHead > 0 && iMain > iHead && iFoot > iMain && iInput > iFoot, "порядок: шапка → лента → низ со строкой ввода (поле — последнее)");
  check(!screenCode.slice(iMain, iFoot).includes("chat__input"), "строки ввода нет внутри ленты");
}
{
  const rule = (sel) => {
    const re = new RegExp(`(?:^|\\n)${sel.replace(/[.[\]="]/g, "\\$&")}\\s*\\{([^}]*)\\}`);
    return css.match(re)?.[1] ?? "";
  };
  const frame = rule(".tchat");
  check(/position:\s*fixed/.test(frame) && /left:\s*0/.test(frame) && /right:\s*0/.test(frame), "рамка чата прибита к окну (fixed, left/right 0)");
  check(/top:\s*var\(--tchat-top,\s*0px\)/.test(frame), "верх рамки — из visualViewport (--tchat-top)");
  check(/height:\s*var\(--tchat-h,\s*100dvh\)/.test(frame), "высота рамки — видимая часть экрана (--tchat-h, иначе 100dvh)");
  check(/flex-direction:\s*column/.test(frame), "рамка — колонка: шапка, лента, низ");

  const main = rule(".tchat__main");
  const feed = rule(".tchat__feed");
  const list = rule(".tchat__list");
  check(/flex:\s*1/.test(main) && /min-height:\s*0/.test(main), "лента занимает всю свободную высоту (flex 1, min-height 0)");
  check(/overflow-y:\s*auto/.test(feed), "лента листается сама, не страница");
  check(/margin:\s*auto auto 0/.test(list), "короткая переписка прижата к низу ленты");

  const foot = rule(".tchat__foot");
  check(/flex:\s*none/.test(foot), "низ не сжимается и не уезжает (flex none)");
  check(/padding-bottom:\s*env\(safe-area-inset-bottom\)/.test(foot), "отступ под «домой»-полоску — safe-area-inset-bottom");
  check(
    /\.tchat\[data-kb="1"\] \.tchat__foot\s*\{[^}]*padding-bottom:\s*0/.test(css),
    "клавиатура открыта → safe-area снимается (без двойного отступа)"
  );
  const composer = rule(".tchat__foot .chat__input");
  check(!/safe-area/.test(composer), "у самой строки ввода своего safe-area нет — он один, у низа");
}
check(
  /window\.visualViewport/.test(screenCode) &&
    /addEventListener\("resize"/.test(screenCode) &&
    /viewportBox\(/.test(screenCode) &&
    /--tchat-h/.test(screenCode) &&
    /dataset\.kb/.test(screenCode),
  "клавиатура: рамка садится на visualViewport, data-kb ставится из него"
);
check(/vv\.scale > 1\.01/.test(screenCode), "щипок-зум не принимается за клавиатуру");

// ---------- Лента: открывается на последнем, не дёргает, «к новым» ----------
check(/firstScrollDone/.test(screenCode) && /scrollToEnd\(false\)/.test(screenCode), "открывается сразу на последнем сообщении");
check(/stickRef\.current \|\| \(last && last\.sender_id === myId\)/.test(screenCode), "чужое сообщение не дёргает ленту, пока читаешь старое; своё — показываем");
check(/isNearBottom\(/.test(screenCode) && /onScroll=\{onFeedScroll\}/.test(screenCode), "понимает, что человек листает старое");
check(/showJump/.test(screenCode) && /tchat__jump/.test(screenCode) && /tchat__badge/.test(screenCode), "кнопка «к новым» с числом");
check(/ResizeObserver/.test(screenCode), "клавиатура/фото поменяли высоту — внизу остаёмся внизу");

// ---------- Два языка ----------
{
  const files = { "TripChatScreen.tsx": screen, "TripChatCard.tsx": card };
  for (const [name, src] of Object.entries(files)) {
    const code = strip(src);
    // appText( ... ) с ОДНИМ строковым аргументом — одноязычная надпись.
    const single = [...code.matchAll(/appText\(\s*(?:"[^"]*"|`[^`]*`)\s*\)/g)].map((m) => m[0]);
    check(single.length === 0, `${name}: нет одноязычных надписей`, single.join(" | "));
    const count = (code.match(/appText\(/g) || []).length;
    check(count >= 10, `${name}: надписи идут через appText`, `appText: ${count}`);
  }
  const aria = [...strip(screen).matchAll(/aria-label=\{?"([^"]+)"/g)].map((m) => m[1]);
  check(aria.length === 0, "aria-label в чате — только через appText (на двух языках)", aria.join(" | "));
}

// ---------- Стиль: токены, тач-цели, без сырых цветов ----------
{
  const start = css.indexOf("Чат поездки на отдельной странице (/trip/:id/chat)");
  const block = css.slice(start);
  check(start > 0 && block.length > 500, "стили чата поездки найдены");
  const raw = [...block.matchAll(/#[0-9a-fA-F]{3,8}\b|rgba?\(/g)].map((m) => m[0]);
  check(raw.length === 0, "в стилях чата нет сырых цветов — только токены", raw.join(" "));
  const fonts = [...block.matchAll(/font-size:\s*[\d.]+px/g)].map((m) => m[0]);
  check(fonts.length === 0, "кегль только токенами, не в px", fonts.join(" "));
  const radius = [...block.matchAll(/border-radius:\s*\d+px/g)].map((m) => m[0]);
  check(radius.length === 0, "радиусы только токенами", radius.join(" "));
  for (const sel of [".tchat__iconbtn", ".tchat__jump", ".tchat__foot .chat__input button", ".tchat-card__icon"]) {
    const body = block.match(new RegExp(`${sel.replace(/[.[\]]/g, "\\$&")}\\s*\\{([^}]*)\\}`))?.[1] ?? "";
    check(/width:\s*48px/.test(body) && /height:\s*48px/.test(body), `тач-цель ≥ 48px: ${sel}`);
  }
  check(
    /\.tchat__foot \.chat__input input\s*\{[^}]*min-height:\s*48px/.test(block),
    "тач-цель ≥ 48px: поле ввода"
  );
  check(/@media \(max-width: 389px\)/.test(block), "есть правка под узкий экран (320–389px)");
  check(
    /inTripChat/.test(read("App.tsx")) && /<InstallPrompt hidden=\{inTripChat\}/.test(read("App.tsx")),
    "подсказка «Установи на экран» не лежит поверх строки ввода"
  );
  check(/tchat-card__write\s*\{[^}]*min-height:\s*48px/.test(block), "тач-цель ≥ 48px: «Написать»");
}

// ---------- 3. Входы ----------
check(
  /navigate\(`\/trip\/\$\{c\.booking_id\}\/chat`\)/.test(inbox),
  "список чатов открывает сразу страницу чата"
);
check(
  /opensActiveTrip \? navigate\(`\/trip\/\$\{b\.id\}\/chat`\)/.test(myRides),
  "«Чат» в «Моих поездках» открывает страницу чата"
);
{
  const cardCode = strip(card);
  check(/onClick=\{onOpen\}/.test(cardCode) && (cardCode.match(/onClick=\{onOpen\}/g) || []).length === 2, "карточка и «Написать» открывают чат (два входа)");
  check(/Написать/.test(cardCode) && /Яҙыу/.test(cardCode), "«Написать» на двух языках");
  check(/tchat__badge/.test(cardCode) && /unreadCount\(/.test(cardCode), "бейдж новых на карточке");
  check(/useVisibleInterval\(/.test(cardCode), "превью обновляется редким опросом, пока экран виден");
}
check(
  /fromTrip/.test(screenCode) && /navigate\(-1\)/.test(screenCode) && /location\.key !== "default"/.test(screenCode),
  "«Назад»: из поездки — к поездке, по ссылке без истории — на страницу поездки"
);

console.log(bad ? `\n✗ провалено проверок: ${bad}` : "\nвсё сходится");
process.exit(bad ? 1 : 0);
