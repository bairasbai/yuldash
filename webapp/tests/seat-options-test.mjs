// ================================================================
//  Опции мест (2026-10-14): потолок мест при «сзади максимум двое»,
//  подсказка на двух языках и что показать про переднее место.
//  Числа обязаны совпадать с сервером (rides.py::seat_cap) и Android.
// ================================================================
const m = await import("./.bundles/seat-options-bundle.mjs");
let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

check(m.SEATS_DEFAULT === 4, "по умолчанию 4 места: 1 спереди + 3 сзади", String(m.SEATS_DEFAULT));
check(m.seatCap(false, true) === 8 && m.seatCap(false, false) === 8, "без «сзади двое» потолок 8 (минивэн)");
check(m.seatCap(true, true) === 3, "«сзади двое» → не больше 3 (1 + 2)");
check(m.seatCap(true, false) === 2, "«сзади двое» и спереди свой человек → не больше 2");
check(m.clampSeats(4, true, true) === 3, "4 места при «сзади двое» режутся до 3");
check(m.clampSeats(6, true, false) === 2, "6 мест при «сзади двое» без переднего → 2");
check(m.clampSeats(0, false, true) === 1 && m.clampSeats(12, false, true) === 8, "общий диапазон 1…8");
check(m.clampSeats(NaN, false, true) === 4, "мусор → значение по умолчанию");

const states = [[false, true], [false, false], [true, true], [true, false]];
const hints = states.map(([b, f]) => m.seatHint(b, f));
check(
  hints[0][0] === "Обычно 4 места: 1 спереди и 3 сзади. Включи, если сзади поедут только двое — будет 3 места",
  "подсказка по умолчанию — слово в слово, как попросил Александр"
);
check(hints.every(([ru, ba]) => ru && ba && ru !== ba), "каждая подсказка на двух языках, башкирский не копия русского");
check(new Set(hints.map(([ru]) => ru)).size === 4, "у каждого сочетания своя подсказка");

check(m.frontSeatState({}) === "free", "старый сервер без полей → переднее свободно");
check(m.frontSeatState({ front_seat_available: true, front_seat_taken: true }) === "taken", "занято другим → «занято»");
check(m.frontSeatState({ front_seat_available: false, front_seat_taken: false }) === "none", "водитель не предлагает → не показываем как свободное");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
