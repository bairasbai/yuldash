// ================================================================
//  Остальные нижние шторки сайта уходят вниз, как шторки ленты и поездки (useSheetExit, 17.10.2026):
//  такси (причина отмены заказа, «почему не сможешь» у водителя, жалоба на цену), посылки (код
//  вручения), оплата (способ расчёта), заявки (отклик водителя, отмена заявки). Тап по затемнению и
//  кнопка «закрыть» — шторка уезжает, затемнение гаснет, и только потом её убирают; без движения —
//  сразу; во время ухода отправить из шторки ничего нельзя (она inert, а кнопки заперты).
//
//  Исполняем настоящие компоненты шторок и настоящий useSheetExit; React — крошечная заглушка
//  (fake-react.mjs), всё соседнее (сеть, иконки, экраны) подменено.
//  Сторож в начале набора: шторку с голым className="sheet-backdrop" (мгновенное закрытие) в сайт
//  больше не добавить — исключения названы поимённо и с причиной.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { browser, finish, flat, JSX, LANG, load, mount, REACT, root, settle, test, text } from "./fake-react.mjs";

const h = (globalThis.__restExit = { calls: [], reply: null });

// ---------------------------------------------------------------- сторож: шторка без ухода

/** Файлы сайта, где голый `className="sheet-backdrop"` допустим, — и почему. */
const NOT_A_BOTTOM_SHEET = {
  "screens/MyDataScreen.tsx":
    "«Удалить документы?» — окно по центру (.my-data-dialog, свой fadeUp), а не нижняя шторка: sheetDown ему не к лицу",
};

function sourceFiles(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = dir + "/" + name;
    return statSync(path).isDirectory() ? sourceFiles(path) : path.endsWith(".tsx") ? [path] : [];
  });
}

await test("сторож: каждая нижняя шторка сайта уходит через useSheetExit, голого className=\"sheet-backdrop\" нет", async () => {
  const bare = sourceFiles(root + "src")
    .filter((f) => /className="sheet-backdrop"/.test(readFileSync(f, "utf8")))
    .map((f) => f.slice((root + "src/").length))
    .filter((f) => !(f in NOT_A_BOTTOM_SHEET));
  assert.deepEqual(bare, [], "шторка закрывается мгновенно — подключи useSheetExit (backdropClass, onAnimationEnd, ref)");
  for (const [file, why] of Object.entries(NOT_A_BOTTOM_SHEET)) {
    assert.ok(/className="sheet-backdrop"/.test(readFileSync(root + "src/" + file, "utf8")), `исключение ${file} устарело (${why}) — убери из списка`);
  }
});

const CONVERTED = [
  "components/PayMethodPicker.tsx",
  "components/PriceComplaint.tsx",
  "components/TaxiDriverTripActions.tsx",
  "components/parcelUi.tsx",
  "screens/InstantOrderScreen.tsx",
  "screens/MyRequestsScreen.tsx",
  "screens/RequestsFeedScreen.tsx",
];

await test("сторож: у каждой из семи шторок затемнение, движение и ref — от хука", async () => {
  for (const file of CONVERTED) {
    const src = readFileSync(root + "src/" + file, "utf8");
    assert.match(src, /import \{ useSheetExit \} from "[./]+(components\/)?useSheetExit"/, `${file}: хук подключён`);
    assert.match(src, /className=\{exit\.backdropClass\}/, `${file}: затемнение — exit.backdropClass`);
    assert.match(src, /onAnimationEnd=\{exit\.onAnimationEnd\}/, `${file}: конец движения слышит сама шторка`);
    assert.match(src, /ref=\{(sheetRef|dialogRef)\}/, `${file}: хук держит шторку по ref`);
    assert.match(src, /exit\.leaving/, `${file}: уходящая шторка ничего не отправляет`);
  }
});

// ---------------------------------------------------------------- подмена соседей

/** Подмена всего, что файл импортирует, кроме `real`: компонент — строка-имя, функция — свой ответ из over. */
function autoStubs(file, over = [], real = []) {
  const src = readFileSync(root + "src/" + file, "utf8");
  const list = [[/^react$/, REACT], [/^react\/jsx-runtime$/, JSX], [/\/i18n\/lang$/, LANG], ...over];
  const escape = (x) => x.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&");
  const seen = new Set();
  for (const m of src.matchAll(/(?:^|\n)\s*(?:import|export)\s+(type\s+)?([^;]*?)\s+from\s+"([^"]+)"/g)) {
    const [, isType, clause, path] = m;
    if (isType || seen.has(path) || list.some(([re]) => re.test(path)) || real.some((re) => re.test(path))) continue;
    seen.add(path);
    const named = (clause.match(/\{([\s\S]*?)\}/)?.[1] ?? "")
      .split(",")
      .map((x) => x.trim())
      .filter((x) => x && !x.startsWith("type "))
      .map((x) => x.split(/\s+as\s+/)[0].trim());
    const def = clause.replace(/\{[\s\S]*?\}/, "").replace(/,/g, "").trim();
    list.push([
      new RegExp("^" + escape(path) + "$"),
      (def && !def.startsWith("*") && !m[0].includes("export") ? `export default "${def}";` : "") +
        (named.length ? `export const ${named.map((n) => `${n} = "${n}"`).join(", ")};` : ""),
    ]);
  }
  return list;
}
const SHEET_EXIT = [/useSheetExit$/];

// ---------------------------------------------------------------- стенд шторки

const ESC = { key: "Escape", stopPropagation() {}, preventDefault() {} };
const ended = (el) => el.props.onAnimationEnd({ target: el, currentTarget: el, animationName: "sheetDown" });
const hasClass = (n, cls) => (n.props?.className ?? "").split(" ").includes(cls);

/** Открытая шторка: рисуем настоящий компонент; закрытия считаем; события — как от пальца. */
function open(Sheet, props) {
  const closes = [];
  const s = mount(Sheet, { ...props, onClose: () => (closes.push(1), props.onClose?.()) });
  const x = {
    s,
    closes,
    backdrop: () => s.tree,
    sheet: () => flat(s.tree).find((n) => n.props?.onAnimationEnd),
    leaving: () => /\bis-leaving\b/.test(s.tree.props.className),
    button: (match) =>
      flat(s.tree).find((n) => n.type === "button" && (typeof match === "string" ? text(n).includes(match) || hasClass(n, match) : match(n))),
    /** Тап по затемнению. */
    tapBackdrop: () => s.tree.props.onClick({ stopPropagation() {} }),
    finish: () => ended(x.sheet()),
    node: () => x.sheet().props.ref?.current,
  };
  return x;
}

// ---------------------------------------------------------------- 1. способ расчёта (оплата)

const Pay = await load("src/components/PayMethodPicker.tsx", autoStubs("components/PayMethodPicker.tsx", [[/\/analytics$/, `export const track = (n) => globalThis.__restExit.calls.push(["track", n]);`]], SHEET_EXIT));

// ---------------------------------------------------------------- 2. жалоба на цену (такси)

const Complaint = await load(
  "src/components/PriceComplaint.tsx",
  autoStubs("components/PriceComplaint.tsx", [[/\/api\/instant$/, `export const sendPriceComplaint = (body) => globalThis.__restExit.send(body);`]], SHEET_EXIT)
);

// ---------------------------------------------------------------- 3. «почему не сможешь» (такси, водитель)

const Driver = await load(
  "src/components/TaxiDriverTripActions.tsx",
  autoStubs(
    "components/TaxiDriverTripActions.tsx",
    [
      [/\/api\/client$/, `export class ApiError extends Error {}`],
      [/\/api\/instant$/, `const call = (n) => (...a) => globalThis.__restExit.api(n, ...a); export const acceptDestination = call("accept"), ackPaymentMethod = call("ackPay"), ackDestination = call("ack"), declineDestination = call("decline"), toggleStop = call("stop");`],
      [/\/PayMethodPicker$/, `export const PAY_METHODS_OPEN = [{ code: "cash", ru: "Наличные", ba: "Наличный" }, { code: "sbp", ru: "СБП", ba: "СБП" }, { code: "negotiate", ru: "Договоримся", ba: "Килешәбеҙ" }];`],
    ],
    SHEET_EXIT
  )
);

// ---------------------------------------------------------------- 4. причина отмены заказа (такси)

const Taxi = await load(
  "src/screens/InstantOrderScreen.tsx",
  autoStubs("screens/InstantOrderScreen.tsx", [[/\/api\/client$/, `export class ApiError extends Error {}`]], SHEET_EXIT)
);

// ---------------------------------------------------------------- 5. код вручения (посылки)

const Parcel = await load("src/components/parcelUi.tsx", autoStubs("components/parcelUi.tsx", [], SHEET_EXIT));

// ---------------------------------------------------------------- 6. отклик на заявку · 7. отмена заявки

const Respond = await load(
  "src/screens/RequestsFeedScreen.tsx",
  autoStubs(
    "screens/RequestsFeedScreen.tsx",
    [
      [/\/api\/client$/, `export class ApiError extends Error { constructor(m) { super(m); } }`],
      [/\/api\/requests$/, `export const fetchRequestsFeed = () => new Promise(() => {}); export const respondToRequest = (...a) => globalThis.__restExit.api("respond", ...a);`],
      [/\/analytics$/, `export const track = (n) => globalThis.__restExit.calls.push(["track", n]);`],
    ],
    SHEET_EXIT
  )
);
const MyRequests = await load(
  "src/screens/MyRequestsScreen.tsx",
  autoStubs("screens/MyRequestsScreen.tsx", [[/\/api\/requests$/, `export const cancelRequest = (...a) => globalThis.__restExit.api("cancelRequest", ...a); export const fetchMatchRides = () => new Promise(() => {}); export const fetchMyRequests = () => new Promise(() => {});`]], SHEET_EXIT)
);

/** Ответы «сети» подменяются в каждой проверке; вызовы пишем. */
h.api = (name, ...args) => (h.calls.push([name, ...args]), h.reply(name, ...args));
h.send = (body) => (h.calls.push(["complaint", body]), h.reply("complaint", body));
const reset = (reply = async () => ({ id: 1 })) => {
  h.calls = [];
  h.reply = reply;
  browser.reduced = false;
};
const called = (name) => h.calls.filter((c) => c[0] === name);

// ---------------------------------------------------------------- общие проверки всех семи

const reqRow = { id: 5, from_city: "Уфа", to_city: "Сибай", desired_at: "2030-01-01T10:00:00", seats: 1, status: "active", comment: "", max_price: 0 };
const feedItem = { id: 9, from_city: "Уфа", to_city: "Сибай", desired_at: "2030-01-01T10:00:00", seats: 1, prefs: [], passenger_name: "Айгуль", responded: false };

/**
 * Что нужно знать о каждой шторке: как открыть, чем закрыть без отправки, чем «отправить» и что
 * при этом считается вызовом (сколько раз ушло на сервер / было выбрано).
 */
const SHEETS = [
  {
    name: "способ расчёта (оплата)",
    open: () => open(Pay.PayMethodSheet, { value: "cash", onPick: (m) => h.calls.push(["pick", m]) }),
    dismiss: (x) => x.button("Закрыть").props.onClick(),
    submit: (x) => x.button("СБП").props.onClick(),
    sent: () => called("pick").length,
  },
  {
    name: "жалоба на цену (такси)",
    open: () => open(Complaint.PriceComplaintSheet, { busy: false, comment: "", onComment() {}, onSend: async (r) => void h.calls.push(["complaint", r]) }),
    dismiss: (x) => x.button("Закрыть").props.onClick(),
    submit: (x) => x.button("Дорого для такого расстояния").props.onClick(),
    sent: () => called("complaint").length,
    submitDisabled: (x) => x.button("Дорого для такого расстояния").props.disabled,
  },
  {
    name: "«почему не сможешь» (такси, водитель)",
    open: () => open(Driver.DeclineWhySheet, { busy: false, onDecline: async (c) => (h.calls.push(["decline", c]), true) }),
    dismiss: (x) => x.button("Всё-таки поеду").props.onClick(),
    submit: (x) => x.button("Смена заканчивается").props.onClick(),
    sent: () => called("decline").length,
    submitDisabled: (x) => x.button("Смена заканчивается").props.disabled,
  },
  {
    name: "причина отмены заказа (такси)",
    open: () => open(Taxi.CancelSheet, { feeRub: 0, busy: false, reason: null, onPick() {}, onConfirm: () => h.calls.push(["confirm"]) }),
    dismiss: (x) => x.button("Не отменять").props.onClick(),
    submit: (x) => x.button("btn-danger").props.onClick(),
    sent: () => called("confirm").length,
    submitDisabled: (x) => x.button("btn-danger").props.disabled,
  },
  {
    name: "код вручения (посылки)",
    open: () => {
      const x = open(Parcel.CodeDialog, { busy: false, error: null, onSubmit: (code) => h.calls.push(["code", code]) });
      flat(x.s.tree).find((n) => n.type === "input").props.onChange({ target: { value: "abc123" } });
      x.s.render();
      return x;
    },
    dismiss: (x) => x.button("Отмена").props.onClick(),
    submit: (x) => x.button("submit-btn").props.onClick(),
    sent: () => called("code").length,
    submitDisabled: (x) => x.button("submit-btn").props.disabled,
  },
  {
    name: "отклик на заявку (заявки)",
    open: () => open(Respond.RespondSheet, { item: feedItem, onDone: (id) => h.calls.push(["done", id]) }),
    dismiss: null,
    submit: (x) => x.button("sheet__cta").props.onClick(),
    sent: () => called("respond").length,
    submitDisabled: (x) => x.button("sheet__cta").props.disabled,
  },
  {
    name: "отмена заявки (заявки)",
    open: () => open(MyRequests.CancelRequestSheet, { request: reqRow, onCancelled: (u) => h.calls.push(["cancelled", u.id]) }),
    dismiss: (x) => x.button("Оставить").props.onClick(),
    submit: (x) => x.button("btn-danger").props.onClick(),
    sent: () => called("cancelRequest").length,
    submitDisabled: (x) => x.button("btn-danger").props.disabled,
  },
];

for (const sheet of SHEETS) {
  const t = (what) => `${sheet.name}: ${what}`;

  await test(t("тап по затемнению — уезжает вниз, затемнение гаснет, потом пропадает"), async () => {
    reset();
    const x = sheet.open();
    assert.equal(x.backdrop().props.className, "sheet-backdrop", "открытая шторка — без ухода");
    x.tapBackdrop();
    x.s.render();
    assert.equal(x.closes.length, 0, "не пропала в тот же кадр");
    assert.ok(x.leaving(), "включилось движение ухода (is-leaving)");
    assert.equal(x.node().inert, true, "уходящая шторка inert: ни нажать, ни Tab");
    x.finish();
    assert.equal(x.closes.length, 1, "доехала — убрана");
    x.tapBackdrop();
    x.finish();
    assert.equal(x.closes.length, 1, "повторное нажатие во время ухода не закрывает второй раз");
  });

  if (sheet.dismiss) {
    await test(t("кнопка «закрыть» уводит шторку так же"), async () => {
      reset();
      const x = sheet.open();
      sheet.dismiss(x);
      x.s.render();
      assert.ok(x.leaving());
      assert.equal(x.closes.length, 0);
      x.finish();
      assert.equal(x.closes.length, 1);
    });
  }

  await test(t("«Уменьшить движение» — закрывается сразу"), async () => {
    reset();
    browser.reduced = true;
    try {
      const x = sheet.open();
      x.tapBackdrop();
      x.s.render();
      assert.equal(x.closes.length, 1);
      assert.ok(!x.leaving());
    } finally {
      browser.reduced = false;
    }
  });

  await test(t("пока шторка уходит, отправить из неё нельзя"), async () => {
    reset();
    const control = sheet.open();
    sheet.submit(control);
    await settle(control.s);
    assert.equal(sheet.sent(), 1, "до ухода отправка работает");

    reset();
    const x = sheet.open();
    x.tapBackdrop();
    x.s.render();
    assert.ok(x.leaving());
    if (sheet.submitDisabled) assert.equal(sheet.submitDisabled(x), true, "кнопка заперта");
    sheet.submit(x); // даже если нажатие дошло
    await settle(x.s);
    assert.equal(sheet.sent(), 0, "из уходящей шторки ничего не ушло");
  });

  await test(t("в разметке: ref на шторке, конец движения слушает сама шторка"), async () => {
    reset();
    const x = sheet.open();
    assert.ok(x.sheet().props.ref, "хук держит шторку по ref");
    x.tapBackdrop();
    x.s.render();
    // всплывшее движение чего-то внутри шторки её не закрывает
    x.sheet().props.onAnimationEnd({ target: {}, currentTarget: x.sheet(), animationName: "sheetDown" });
    x.sheet().props.onAnimationEnd({ target: x.sheet(), currentTarget: x.sheet(), animationName: "other" });
    assert.equal(x.closes.length, 0);
  });
}

// ---------------------------------------------------------------- свои пути каждой шторки

await test("оплата: выбор запоминается сразу и шторка уходит; повторный выбор во время ухода не принимается", async () => {
  reset();
  const x = open(Pay.PayMethodSheet, { value: "cash", onPick: (m) => h.calls.push(["pick", m]) });
  x.button("СБП").props.onClick();
  x.s.render();
  assert.deepEqual(called("pick"), [["pick", "sbp"]]);
  assert.ok(x.leaving(), "выбрал — шторка уезжает");
  assert.equal(x.closes.length, 0);
  x.button("Договоримся").props.onClick();
  assert.equal(called("pick").length, 1, "второй тап по пути вниз ничего не меняет");
  x.finish();
  assert.equal(x.closes.length, 1);
});

await test("оплата: «Скоро» — не выбор, шторка остаётся", async () => {
  reset();
  const x = open(Pay.PayMethodSheet, { value: "cash", onPick: (m) => h.calls.push(["pick", m]) });
  x.button("Картой в приложении").props.onClick();
  x.s.render();
  assert.deepEqual(called("track"), [["track", "pay_wanted_card"]], "интерес к карте записан");
  assert.ok(!x.leaving());
  assert.equal(called("pick").length, 0);
});

await test("оплата: PayMethodPicker — кнопка → шторка → выбор → шторка ушла, кнопка снова на месте", async () => {
  reset();
  const picks = [];
  const picker = mount(Pay.default, { value: "cash", onChange: (m) => picks.push(m) });
  const cta = () => flat(picker.tree).find((n) => n.type === "button");
  assert.equal(flat(picker.tree).some((n) => n.type?.name?.startsWith("PayMethodSheet")), false, "закрыта");
  cta().props.onClick();
  picker.render();
  const sheetEl = picker.tree;
  assert.match(sheetEl.type.name, /^PayMethodSheet\d*$/, "открыта отдельным компонентом");
  const x = open(sheetEl.type, sheetEl.props);
  x.button("СБП").props.onClick();
  assert.deepEqual(picks, ["sbp"], "выбор дошёл до заказа");
  x.s.render();
  assert.ok(x.leaving(), "шторка уезжает");
  picker.render();
  assert.match(picker.tree.type.name, /^PayMethodSheet\d*$/, "пока едет — ещё на странице");
  x.finish();
  picker.render();
  assert.equal(cta().type, "button", "шторка убрана, кнопка снова на месте");
});

await test("жалоба: сервер принял — «Спасибо» стоит под уходящей шторкой, шторка уезжает и пропадает", async () => {
  reset(async () => ({}));
  const c = mount(Complaint.default, { price: 500 });
  flat(c.tree).find((n) => n.type === "button").props.onClick();
  c.render();
  const sheetEl = flat(c.tree).find((n) => /^PriceComplaintSheet\d*$/.test(n.type?.name ?? ""));
  assert.ok(sheetEl, "шторка открыта");
  assert.ok(!flat(c.tree).some((n) => n.type === "button" && /Что-то не так/.test(text(n))), "пока шторка открыта, кнопки жалобы нет");
  const x = open(sheetEl.type, sheetEl.props);
  x.button("Дорого для такого расстояния").props.onClick();
  await settle(c, x.s);
  assert.equal(called("complaint").length, 1);
  assert.ok(x.leaving(), "после ответа сервера шторка уходит");
  assert.match(text(c.tree), /Спасибо/, "благодарность на месте");
  assert.equal(x.closes.length, 0, "ещё не доехала");
  x.finish();
  sheetEl.props.onClose();
  c.render();
  assert.ok(!flat(c.tree).some((n) => /^PriceComplaintSheet\d*$/.test(n.type?.name ?? "")), "шторка убрана");
  assert.match(text(c.tree), /Спасибо/);
});

await test("жалоба: сервер не принял — «Спасибо» нет, шторка всё равно уходит, кнопка жалобы возвращается", async () => {
  reset(async () => {
    throw new Error("нет связи");
  });
  const c = mount(Complaint.default, { price: 500 });
  flat(c.tree).find((n) => n.type === "button").props.onClick();
  c.render();
  const sheetEl = flat(c.tree).find((n) => /^PriceComplaintSheet\d*$/.test(n.type?.name ?? ""));
  const x = open(sheetEl.type, sheetEl.props);
  x.button("Другое").props.onClick();
  await settle(c, x.s);
  assert.ok(x.leaving());
  assert.ok(!/Спасибо/.test(text(c.tree)), "не обманываем: жалоба не ушла");
  sheetEl.props.onClose();
  c.render();
  assert.ok(flat(c.tree).some((n) => n.type === "button" && /Что-то не так/.test(text(n))), "можно пожаловаться ещё раз");
});

await test("жалоба: во время отправки вторая жалоба не уходит", async () => {
  let release;
  reset(() => new Promise((r) => (release = r)));
  const c = mount(Complaint.default, { price: 500 });
  flat(c.tree).find((n) => n.type === "button").props.onClick();
  c.render();
  const sheetEl = flat(c.tree).find((n) => /^PriceComplaintSheet\d*$/.test(n.type?.name ?? ""));
  const x = open(sheetEl.type, sheetEl.props);
  x.button("Другое").props.onClick();
  c.render();
  const again = flat(c.tree).find((n) => /^PriceComplaintSheet\d*$/.test(n.type?.name ?? ""));
  again.props.onSend("other");
  assert.equal(called("complaint").length, 1);
  release({});
  await settle(c, x.s);
});

await test("«почему не сможешь»: сервер принял — шторка уходит; отказал — остаётся, можно выбрать другую причину", async () => {
  reset();
  let ok = false;
  const x = open(Driver.DeclineWhySheet, { busy: false, onDecline: async (c) => (h.calls.push(["decline", c]), ok) });
  x.button("Смена заканчивается").props.onClick();
  await settle(x.s);
  assert.ok(!x.leaving(), "отказ сервера — шторка на месте");
  ok = true;
  x.s.render();
  x.button("Не хватит топлива").props.onClick();
  await settle(x.s);
  assert.deepEqual(called("decline").map((c) => c[1]), ["shift_end", "no_fuel"]);
  assert.ok(x.leaving(), "принял — шторка уходит");
  assert.equal(x.closes.length, 0);
  x.finish();
  assert.equal(x.closes.length, 1);
});

await test("«почему не сможешь»: в экране водителя шторка настоящая — отказ идёт через declineDestination с причиной", async () => {
  reset(async () => ({}));
  const order = { id: 77, status: "onboard", destination_changes: 1, destination_ack: false, to_text: "Сибай", payment_method: "cash" };
  const dest = mount(Driver.default, { order, onChanged: () => h.calls.push(["changed"]) });
  const card = dest.tree.props.children;
  const host = flat(dest.tree).find((n) => /^DestinationChanged\d*$/.test(n.type?.name ?? ""));
  const d = mount(host.type, host.props);
  flat(d.tree).find((n) => n.type === "button" && /Не смогу ехать/.test(text(n))).props.onClick();
  d.render();
  const sheetEl = flat(d.tree).find((n) => /^DeclineWhySheet\d*$/.test(n.type?.name ?? ""));
  assert.ok(sheetEl && card, "по «Не смогу ехать» открылась шторка");
  const x = open(sheetEl.type, sheetEl.props);
  x.button("Смена заканчивается").props.onClick();
  await settle(d, x.s);
  assert.deepEqual(called("decline"), [["decline", 77, "shift_end"]]);
  assert.deepEqual(called("changed"), [["changed"]], "экран водителя обновлён");
  assert.ok(x.leaving());
});

await test("причина отмены заказа: «Не отменять» уводит шторку, а «Отменить» зовёт отмену один раз", async () => {
  reset();
  const x = open(Taxi.CancelSheet, { feeRub: 120, busy: false, reason: "plans", onPick() {}, onConfirm: () => h.calls.push(["confirm"]) });
  assert.ok(x.button("Я выхожу"), "при платной отмене кнопка выхода — «Я выхожу»");
  x.button("Всё равно отменить").props.onClick();
  assert.equal(called("confirm").length, 1);
  assert.ok(!x.leaving(), "отмена не ждёт движения: экран меняется сам");
});

await test("код вручения: верный код уходит один раз; «Отмена» уводит шторку, не отправляя код", async () => {
  reset();
  const x = open(Parcel.CodeDialog, { busy: false, error: null, onSubmit: (code) => h.calls.push(["code", code]) });
  flat(x.s.tree).find((n) => n.type === "input").props.onChange({ target: { value: "ab-12 cd" } });
  x.s.render();
  assert.equal(x.button("submit-btn").props.disabled, false, "из «AB12CD» уже можно отправить");
  x.button("submit-btn").props.onClick();
  assert.deepEqual(called("code"), [["code", "AB12CD"]], "код очищен до букв и цифр");
  x.button("Отмена").props.onClick();
  x.s.render();
  assert.ok(x.leaving());
  assert.equal(called("code").length, 1);
});

await test("отклик: сервер принял — лента обновлена, кнопка остаётся «Отправляем…», шторка уходит и пропадает", async () => {
  reset(async () => ({ id: 31 }));
  const x = open(Respond.RespondSheet, { item: feedItem, onDone: (id) => h.calls.push(["done", id]) });
  flat(x.s.tree).find((n) => n.type === "input").props.onChange({ target: { value: "450" } });
  x.s.render();
  x.button("sheet__cta").props.onClick();
  await settle(x.s);
  assert.deepEqual(called("respond"), [["respond", 9, 450, ""]], "цена и пустой комментарий ушли в заявку 9");
  assert.deepEqual(called("done"), [["done", 31]]);
  assert.ok(x.leaving(), "шторка уходит");
  assert.equal(x.closes.length, 0, "а не пропала в тот же кадр");
  assert.equal(text(x.button("sheet__cta")), "Отправляем…", "кнопка не мигает обратно в «Отправить»");
  assert.equal(x.button("sheet__cta").props.disabled, true);
  x.finish();
  assert.equal(x.closes.length, 1);
});

await test("отклик: сервер отказал — шторка на месте с понятной ошибкой, можно отправить ещё раз", async () => {
  reset(async () => {
    throw new Error("500");
  });
  const x = open(Respond.RespondSheet, { item: feedItem, onDone: (id) => h.calls.push(["done", id]) });
  x.button("sheet__cta").props.onClick();
  await settle(x.s);
  assert.ok(!x.leaving());
  assert.equal(called("done").length, 0);
  assert.match(text(x.s.tree), /Не получилось\. Попробуй снова\./);
  assert.equal(x.button("sheet__cta").props.disabled, false);
});

await test("отклик: закрыли во время отправки — отклик всё равно дошёл до ленты, шторка один раз убрана", async () => {
  let release;
  reset(() => new Promise((r) => (release = r)));
  const x = open(Respond.RespondSheet, { item: feedItem, onDone: (id) => h.calls.push(["done", id]) });
  x.button("sheet__cta").props.onClick();
  x.tapBackdrop();
  x.s.render();
  assert.ok(x.leaving());
  release({ id: 8 });
  await settle(x.s);
  assert.deepEqual(called("done"), [["done", 8]]);
  x.finish();
  assert.equal(x.closes.length, 1);
});

await test("отмена заявки: сервер принял — список обновлён, кнопка остаётся «Отменяем…», шторка уходит и пропадает", async () => {
  reset(async (n, id) => ({ ...reqRow, id, status: "cancelled" }));
  const x = open(MyRequests.CancelRequestSheet, { request: reqRow, onCancelled: (u) => h.calls.push(["cancelled", u.id, u.status]) });
  x.button("btn-danger").props.onClick();
  await settle(x.s);
  assert.deepEqual(called("cancelRequest"), [["cancelRequest", 5]]);
  assert.deepEqual(called("cancelled"), [["cancelled", 5, "cancelled"]]);
  assert.ok(x.leaving());
  assert.equal(x.closes.length, 0);
  assert.equal(text(x.button("btn-danger")), "Отменяем…");
  assert.equal(x.button("btn-danger").props.disabled, true);
  x.finish();
  assert.equal(x.closes.length, 1);
});

await test("отмена заявки: связь пропала — шторка на месте с ошибкой, кнопки снова нажимаются", async () => {
  reset(async () => {
    throw new Error("offline");
  });
  const x = open(MyRequests.CancelRequestSheet, { request: reqRow, onCancelled: (u) => h.calls.push(["cancelled", u.id]) });
  x.button("btn-danger").props.onClick();
  await settle(x.s);
  assert.ok(!x.leaving());
  assert.equal(called("cancelled").length, 0);
  assert.match(text(x.s.tree), /Не получилось отменить\./);
  assert.equal(x.button("btn-danger").props.disabled, false);
});

await test("отмена заявки: пока идёт отмена, затемнение и Esc шторку не трогают; фокус — на «Оставить», Esc уводит вниз", async () => {
  let release;
  reset(() => new Promise((r) => (release = r)));
  const x = open(MyRequests.CancelRequestSheet, { request: reqRow, onCancelled: () => {} });
  assert.equal(globalThis.document.activeElement, x.button("Оставить").props.ref.current, "открыли — фокус на безопасной кнопке");
  x.button("btn-danger").props.onClick();
  x.s.render(); // кнопка «Отменяем…» перерисована — как после нажатия на экране
  x.tapBackdrop();
  x.sheet().props.onKeyDown(ESC);
  x.s.render();
  assert.ok(!x.leaving(), "отмена не обрывается уходом шторки");
  release({ ...reqRow, status: "cancelled" });
  await settle(x.s);
  assert.ok(x.leaving());

  reset();
  const y = open(MyRequests.CancelRequestSheet, { request: reqRow, onCancelled: () => {} });
  y.sheet().props.onKeyDown(ESC);
  y.s.render();
  assert.ok(y.leaving(), "Esc уводит шторку вниз");
  y.finish();
  assert.equal(y.closes.length, 1);
});

finish();
