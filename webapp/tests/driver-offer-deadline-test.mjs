import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { driverOfferDeadline, driverOfferRemaining } from "./.bundles/driver-offer-deadline.mjs";

const now = Date.UTC(2026, 10, 25, 10);
for (const bogus of [undefined, null, "", "in 20 seconds", "2026-11-25", "2026-02-30T10:00:00", "2026-11-25T24:00:00", "2025-02-29T10:00:00Z"]) {
  const deadline = driverOfferDeadline(bogus);
  assert.equal(deadline, null);
  assert.equal(driverOfferRemaining(deadline, now), 0);
  assert.equal(driverOfferRemaining(deadline, now + 2_000), 0);
}
console.log("✓ missing and malformed deadlines remain expired across ticks");

for (const input of ["2026-11-25T10:00:20", "2026-11-25T10:00:20Z", "2026-11-25T13:00:20+03:00"]) {
  const deadline = driverOfferDeadline(input);
  assert.equal(driverOfferRemaining(deadline, now), 20_000);
  assert.equal(driverOfferRemaining(deadline, now + 19_999), 1);
  assert.equal(driverOfferRemaining(deadline, now + 20_000), 0);
  assert.equal(driverOfferRemaining(deadline, now + 21_000), 0);
}
console.log("✓ naive UTC, explicit UTC and offsets count down to the exact same instant");

assert.equal(driverOfferDeadline("2024-02-29T10:11:12.123456"), Date.UTC(2024, 1, 29, 10, 11, 12, 123));
console.log("✓ leap date and server microseconds stay supported");

const screen = readFileSync(new URL("../src/screens/InstantDriverTripScreen.tsx", import.meta.url), "utf8");
assert.match(screen, /driverOfferDeadline\(order.offer_expires_at\)/);
assert.match(screen, /driverOfferRemaining\(expiresAt, now\)/);
assert.match(screen, /driverOfferRemaining\(expiresAt, Date.now\(\)\) <= 0\) return;/);
assert.doesNotMatch(screen, /Date\.now\(\) \+ 20000/);
console.log("✓ the real timer and accept button share the same server deadline");
