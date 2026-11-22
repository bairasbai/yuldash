import assert from "node:assert/strict";
import { canOpenScheduledActivation } from "./.bundles/scheduled-activation-bundle.mjs";

for (const status of ["created", "searching", "offered", "accepted", "arriving", "onboard", "expired"]) {
  assert.equal(canOpenScheduledActivation(17, 17, status), true, status);
  console.log(`✓ confirmed scheduled activation: ${status}`);
}
for (const status of ["scheduled", "done", "cancelled", "future-status", ""]) {
  assert.equal(canOpenScheduledActivation(17, 17, status), false, status);
  console.log(`✓ unconfirmed activation stays on scheduled: ${status}`);
}
assert.equal(canOpenScheduledActivation(17, 18, "searching"), false);
console.log("✓ another order cannot replace the requested activation");
assert.equal(canOpenScheduledActivation(0, 0, "searching"), false);
console.log("✓ an invalid order cannot be activated");
