// ================================================================
//  Сколько проверок сделал набор — по его выводу. Это не набор: его импортируют run.mjs и run-count-test.mjs.
//
//  Наборы писались разными руками и печатают успех по-разному:
//    «✓ имя»                       — по строке на проверку (больше всего);
//    «PASS имя»                    — то же, другим словом (update-banner, sos-*, roadside-*, session-*…);
//    «N passed», «N/N passed»      — только итог («Auth gate: 4 passed», «Parcel ride gates: 30/30 passed»);
//    «N cases, 0 failures»         — только итог («taxi-unavailable-gate»: «18 cases, 0 failures»);
//    «PASS: … (22 assertions)»     — одна строка с числом проверок внутри (commission-label).
//  Раньше run.mjs считал только «✓» в начале строки, и у 38 наборов, которые проверяли честно,
//  в отчёте стояло «0 проверок» (проверка сайта 23.10.2026). Теперь берём большее из двух:
//  строки-успехи и число из итоговой строки.
// ================================================================

/** Числа из итоговых строк: «7 passed», «30/30 passed», «18 cases», «(22 assertions)». */
function summaryNumbers(output) {
  const found = [];
  for (const line of String(output).split("\n")) {
    for (const m of line.matchAll(/(\d+)(?:\s*\/\s*\d+)?\s+passed\b/gi)) found.push(Number(m[1]));
    for (const m of line.matchAll(/\b(\d+)\s+cases?\b/gi)) found.push(Number(m[1]));
    for (const m of line.matchAll(/\(\s*(\d+)\s+assertions?\s*\)/gi)) found.push(Number(m[1]));
  }
  return found;
}

/** Строки-успехи: «✓ …» и «PASS …» / «PASS: …». */
function passLines(output) {
  return (String(output).match(/^\s*(?:✓|PASS\b)/gm) || []).length;
}

export function countChecks(output) {
  return Math.max(passLines(output), ...summaryNumbers(output), 0);
}
