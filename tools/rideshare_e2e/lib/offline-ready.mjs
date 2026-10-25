// Instrumentation reads actual encrypted app data; it never injects a cache or clears a queue.
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { A, q1, userIdOf } from "./stand.mjs";
import { REPO } from "./common.mjs";
import { sha256, sha256File } from "./provenance.mjs";

const testPackage = "com.yuldash.app.test";
const testClass = "com.yuldash.app.data.OfflineTripSnapshotReadyProbeTest#savedOwnOnboardAndReadableEmptyQueue";
const runner = `${testPackage}/androidx.test.runner.AndroidJUnitRunner`;

// SQLite stores UTC without an offset. Preserve every fractional digit of the boarding epoch.
function dbUtc(value) {
  if (typeof value !== "string") throw new Error("Expected an explicit UTC time in the synthetic DB");
  const iso = value.replace(" ", "T");
  if (/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?$/.test(iso)) return iso + "Z";
  if (/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?(?:Z|\+00:00)$/.test(iso)) return iso.replace(/\+00:00$/, "Z");
  throw new Error("Unexpected synthetic UTC representation; do not guess its timezone");
}

export function expectedSavedTrip({ bookingId, actor, role, hasStops }) {
  if (!Number.isSafeInteger(bookingId) || bookingId <= 0 || !["driver", "passenger"].includes(role) || typeof hasStops !== "boolean") throw new Error("Explicit own booking/role/stop policy required");
  const row = q1("SELECT b.id,b.passenger_id,b.status,b.pickup_stop,b.dropoff_stop,b.onboard_at,r.driver_id,r.from_city,r.to_city,r.depart_at,r.waypoints FROM booking b JOIN ride r ON r.id=b.ride_id WHERE b.id=?", bookingId);
  const ownerId = userIdOf(actor);
  if (!Number.isSafeInteger(ownerId) || !row || (role === "driver" ? row.driver_id : row.passenger_id) !== ownerId || row.status !== "onboard") throw new Error("Probe requires the exact existing own onboard booking");
  if (!hasStops && /\p{L}/u.test(String(row.waypoints || ""))) throw new Error("No-stop fixture unexpectedly contains alphabetic waypoints");
  if (hasStops && !/\p{L}/u.test(String(row.waypoints || ""))) throw new Error("Stop fixture has no explicit waypoints");
  return { schema: 1, ownerId, bookingId, role, status: row.status, from: row.from_city, to: row.to_city,
    departAt: dbUtc(row.depart_at), pickup: hasStops ? row.pickup_stop : "", dropoff: row.dropoff_stop,
    hasStops, onboardAt: dbUtc(row.onboard_at) };
}

/** This runner may restart the app. Caller must relaunch and use ordinary own live navigation afterwards. */
export async function assertSavedOwnTripReady(sc, spec, label) {
  if (!/^[a-zA-Z0-9_-]{1,80}$/.test(label)) throw new Error("Unique plain readiness label required");
  const expected = expectedSavedTrip(spec);
  const apk = join(REPO, "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk");
  const built = sha256File(apk);
  const installedPath = A.sh(`pm path ${testPackage}`, { quiet: true }).split("\n").find(v => v.startsWith("package:"))?.slice(8).trim();
  if (!installedPath || !/^\/data\/app\/[A-Za-z0-9_+./=~-]+$/.test(installedPath)) throw new Error("Current instrumentation APK is not installed");
  const installed = A.sh(`sha256sum ${installedPath}`, { quiet: true }).trim().split(/\s+/)[0];
  sc.check(installed === built, "installed readiness instrumentation exactly matches this built test APK", { built_sha256: built });
  const arg = Buffer.from(JSON.stringify(expected), "utf8").toString("base64url");
  const output = A.adb(["shell", "am", "instrument", "-w", "-r", "-e", "class", testClass, "-e", "snapshotProbe", arg, runner], { quiet: true, timeout: 60000 });
  const artifact = join(sc.dir, `${label}-readiness-instrumentation.txt`);
  writeFileSync(artifact, output, { flag: "wx" });
  const status = key => new RegExp(`^INSTRUMENTATION_STATUS: ${key}=(.*)$`, "m").exec(output)?.[1].trim();
  sc.check(/OK \(1 test\)/.test(output) && !/FAILURES|INSTRUMENTATION_FAILED|Process crashed|shortMsg=/.test(output) &&
    status("snapshotReady") === "true" && status("bookingId") === String(expected.bookingId) &&
    status("role") === expected.role && status("status") === "onboard" &&
    status("outboxReadable") === "true" && status("outboxCount") === "0",
  "actual encrypted cold reopen contains the exact own onboard route/epoch/stops and a readable empty old queue", { booking_id: expected.bookingId, role: expected.role, artifact });
  return { booking_id: expected.bookingId, role: expected.role, state: "onboard", encrypted_reopen: true, readable_outbox_count: 0,
    expected_projection_sha256: sha256(JSON.stringify(expected)), instrumentation_class: testClass,
    instrumentation_apk_sha256: built, output: artifact, output_sha256: sha256(readFileSync(artifact)),
    process_boundary: "Instrumentation can restart the app; a normal own live navigation is required before the network cut" };
}
