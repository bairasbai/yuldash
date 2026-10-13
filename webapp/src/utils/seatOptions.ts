// ================================================================
//  Опции мест в машине (2026-10-14) — как у BlaBlaCar, без схемы кресел.
//  Зеркало backend/app/routers/rides.py::seat_cap и Android SeatOptions.kt.
//  Правду держит сервер; здесь — чтобы форма заранее показала то же число.
// ================================================================

/** Обычная легковая: 1 спереди + 3 сзади (решение Александра; раньше по умолчанию было 3). */
export const SEATS_DEFAULT = 4;
/** Больше мест в попутку не берут (минивэн). */
export const SEATS_MAX = 8;

/**
 * Сколько пассажирских мест можно предложить при опциях.
 * «Сзади максимум двое» → 2 сзади + 1 спереди = 3; спереди свой человек → только 2.
 */
export function seatCap(backMaxTwo: boolean, frontSeatAvailable: boolean): number {
  if (!backMaxTwo) return SEATS_MAX;
  return 2 + (frontSeatAvailable ? 1 : 0);
}

/** Число мест, урезанное под опции и общий диапазон 1…8. */
export function clampSeats(seats: number, backMaxTwo: boolean, frontSeatAvailable: boolean): number {
  const n = Number.isFinite(seats) ? Math.round(seats) : SEATS_DEFAULT;
  return Math.max(1, Math.min(n, seatCap(backMaxTwo, frontSeatAvailable)));
}

/** Подсказка под переключателями — пара (ru, ba). Тексты те же, что в Android. */
export function seatHint(backMaxTwo: boolean, frontSeatAvailable: boolean): [string, string] {
  if (!backMaxTwo && frontSeatAvailable) {
    return [
      "Обычно 4 места: 1 спереди и 3 сзади. Включи, если сзади поедут только двое — будет 3 места",
      "Ғәҙәттә 4 урын: 1 алда һәм 3 артта. Артта икәү генә барһа, ҡабыҙ — 3 урын булыр",
    ];
  }
  if (!backMaxTwo) {
    return [
      "Спереди едет свой человек — пассажиры сядут сзади",
      "Алда үҙ кешең бара — юлсылар артҡа ултырыр",
    ];
  }
  if (frontSeatAvailable) {
    return [
      "Сзади поедут двое и один спереди — мест не больше 3",
      "Артта икәү, алда бер кеше барыр — 3 урындан артыҡ түгел",
    ];
  }
  return [
    "Сзади поедут двое, спереди — свой человек: мест не больше 2",
    "Артта икәү барыр, алда — үҙ кешең: 2 урындан артыҡ түгел",
  ];
}

/** Что показать про переднее место в карточке поездки: свободно / занято / не предлагается. */
export function frontSeatState(ride: {
  front_seat_available?: boolean;
  front_seat_taken?: boolean;
}): "free" | "taken" | "none" {
  // Старый сервер полей не присылает — переднее тогда свободно, как было всегда.
  if (ride.front_seat_available === false) return "none";
  return ride.front_seat_taken ? "taken" : "free";
}
