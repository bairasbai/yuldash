import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import * as sync from "../src/utils/tripDetailsSync.js";

function check(label, assertion) {
  assertion(); // An assertion failure escapes with a nonzero exit; never count it as passed.
  console.log(`✓ ${label}`);
}
check("production draft reconciliation exists", () => assert.equal(typeof sync.reconcilePaymentDraft, "function"));
const server = { pay_method: "cash", pay_amount: 500 };
const draft = { bookingId: 1, payMethod: "sbp", payAmount: "750", dirty: true };
check("background refresh preserves edits", () => assert.deepEqual(sync.reconcilePaymentDraft(draft, 1, server), draft));
check("clean draft receives server value", () => assert.equal(sync.reconcilePaymentDraft({ ...draft, dirty: false }, 1, server).payAmount, "500"));
check("another booking never inherits old draft", () => assert.deepEqual(sync.reconcilePaymentDraft(draft, 2, server), {
  bookingId: 2, payMethod: "cash", payAmount: "500", dirty: false,
}));
check("saved draft becomes clean", () => assert.equal(sync.acceptPaymentDraft(draft, draft, server).dirty, false));
const newer = { ...draft, payAmount: "800" };
check("typing during save survives response", () => assert.deepEqual(sync.acceptPaymentDraft(newer, draft, server), newer));
const screen = readFileSync(new URL("../src/screens/ActiveTripScreen.tsx", import.meta.url), "utf8");
check("screen reconciles server details", () => assert.ok(screen.includes("reconcilePaymentDraft(current, bookingId, details)")));
check("screen acknowledges submitted draft", () => assert.ok(screen.includes("acceptPaymentDraft(current, submittedDraft, agreement)")));
check("method editing marks draft dirty", () => assert.ok(screen.includes("payMethod: m, dirty: true")));
check("amount editing captures event before updater", () => assert.ok(screen.includes("const value = e.target.value;") && screen.includes("payAmount: value, dirty: true")));
console.log("Payment draft: 10 checks passed");
