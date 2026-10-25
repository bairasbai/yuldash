// Pure acceptance predicates. They never create or modify trip data.
export function receiptExpectation(row, actorId, role, expectStops = true) {
  if (!row || row.status !== "done" || !Number.isSafeInteger(row.id) || !Number.isSafeInteger(actorId) ||
      !["driver", "passenger"].includes(role) || row[role === "driver" ? "driver_id" : "passenger_id"] !== actorId ||
      !row.from_city || !row.to_city || !row.driver_name || !Number.isSafeInteger(row.amount) || row.amount < 0) throw new Error("Receipt expectation is not an exact completed own booking");
  const labels = [`${row.from_city} → ${row.to_city}`, `${row.amount} ₽`, row.driver_name];
  if (expectStops) {
    if (typeof row.pickup_stop !== "string" || typeof row.dropoff_stop !== "string") throw new Error("Own receipt lacks explicit personal stops");
    labels.push(`${row.pickup_stop || row.from_city} → ${row.dropoff_stop || row.to_city}`);
  }
  return { bookingId: row.id, role, labels };
}
export function receiptContentMatches(values, expected) {
  const found = new Set(values.map(value => String(value).replace(/\s+/g, " ").trim()));
  return found.has("Квитанция") && found.has("Поездка завершена") && expected.labels.every(label => found.has(label));
}
export function privateStopLeak(text, pickup, dropoff) {
  const value = String(text).replace(/\s+/g, " ");
  const escape = word => word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  if (value.includes(`${pickup} → ${dropoff}`)) return true;
  return [pickup, dropoff].some(stop => new RegExp(`(?:Посадка|Высадка|Участок|Твой участок)\\s*[:·]?\\s*${escape(stop)}(?:\\s|$)`, "i").test(value));
}
export function finishedPublicStateMatches(state, firstName) {
  const keys = ["kind", "status", "outcome", "phase_text", "car", "passenger_first_name", "updated_at"];
  return state && Object.keys(state).length === keys.length && Object.keys(state).every(key => keys.includes(key)) &&
    state.kind === "ride" && state.status === "finished" && state.outcome === "done" && state.car === null &&
    state.passenger_first_name === firstName && typeof state.updated_at === "string" && Number.isFinite(Date.parse(state.updated_at)) &&
    state.phase_text && Object.keys(state.phase_text).sort().join("|") === "ba|ru" &&
    [state.phase_text.ru, state.phase_text.ba].every(value => typeof value === "string" && value.length > 0);
}
export function revokedPublicStateMatches(status, state) {
  return status === 404 && state && Object.keys(state).length === 1 && Object.hasOwn(state, "detail") &&
    state.detail && (typeof state.detail === "string" || (typeof state.detail === "object" &&
      Object.keys(state.detail).sort().join("|") === "ba|ru" && [state.detail.ru, state.detail.ba].every(value => typeof value === "string" && value.length > 0)));
}
