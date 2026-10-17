"""HUNT ONLY (disposable branch): generate renamed copies of suspect test classes.

Copies run in ONE test JVM, like the real CI suite, so a rare interleaving gets many chances.
usage: python3 hunt/gen.py <variant> <copies> [amplifiers]
  A  : TripChatAcknowledgementTest copies (+ N tests that only connect(), so cleanup races N more times)
  B  : InstantShareOwnerBoundaryTest copies
  BD : only currentRevokeErrorPreservesLinkAndCanRetry, successful DELETE reply delayed 500 ms
  C  : BoardingCodeVisibilityTest copies
  CD : BoardingCodeVisibilityTest copies, /role reply delayed 600 ms
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "test", "java", "com", "yuldash", "app")
OUT = os.path.join(ROOT, "hunt")
variant, copies = sys.argv[1], int(sys.argv[2])
amplifiers = int(sys.argv[3]) if len(sys.argv) > 3 else 0
os.makedirs(OUT, exist_ok=True)


def read(name):
    with open(os.path.join(ROOT, name), encoding="utf-8") as f:
        return f.read()


def write(cls, text):
    with open(os.path.join(OUT, cls + ".kt"), "w", encoding="utf-8") as f:
        f.write(text)


if variant in ("A", "AW"):
    src = read("TripChatAcknowledgementTest.kt")
    if variant == "AW":
        # HUNT: the app's socket pauses 20 ms before writing its close frame (see HuntSlowClose).
        hook = "        ChatSocket.huntEvents = { clientSocketEvents += \"${us()}us $it\" }\n"
        assert src.count(hook) == 1
        src = src.replace(hook, hook + "        ChatSocket.huntClient = HuntSlowClose.client(20)\n")
        reset = "        ChatSocket.huntEvents = null\n"
        assert src.count(reset) == 1
        src = src.replace(reset, reset + "        ChatSocket.huntClient = null\n")
    end = src.rstrip().rfind("}")
    extra = "".join(f"\n    @Test fun cleanupRace{j}() {{ connect() }}" for j in range(amplifiers))
    for i in range(copies):
        cls = f"Hunt{variant}{i:03d}Test"
        text = src[:end] + extra + "\n}\n"
        write(cls, text.replace("class TripChatAcknowledgementTest {", f"class {cls} {{", 1))
elif variant in ("B", "BD", "BS", "BX"):
    src = read("InstantShareOwnerBoundaryTest.kt")
    header = src[: src.index("/** Synthetic local HTTP")]
    body_start = src.rindex("@RunWith(RobolectricTestRunner::class)", 0, src.index("class InstantShareOwnerBoundaryTest"))
    body = src[body_start:]
    target = next(l for l in src.splitlines() if "fun currentRevokeErrorPreservesLinkAndCanRetry" in l)
    for i in range(copies):
        if variant == "B":
            cls = f"HuntB{i:03d}Test"
            write(cls, header + body.replace("class InstantShareOwnerBoundaryTest :", f"class {cls} :", 1))
        elif variant == "BX":
            # HUNT: the pre-fix check (no pump after the toast), plus what the tree showed and whether one
            # pump brings the link back — to tell "stale frame" from "link really gone".
            fixed = ('await { ShadowToast.getTextOfLatestToast() != null }; pump(); assertTrue("HUNT first assert: link kept after failed revoke", '
                     'compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty());')
            assert target.count(fixed) == 1
            diag = ('await { ShadowToast.getTextOfLatestToast() != null }; '
                    'val huntTexts = { compose.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().mapNotNull { n -> '
                    'n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() } }; '
                    'val huntSeen = compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty(); val huntBefore = huntTexts(); '
                    'pump(); val huntAfter = compose.onAllNodesWithText("Скопировать").fetchSemanticsNodes().isNotEmpty(); '
                    'assertTrue("HUNT BX stale: linkAfterPump=$huntAfter toast=${ShadowToast.getTextOfLatestToast()} '
                    'textsBeforePump=$huntBefore textsAfterPump=${huntTexts()}", huntSeen);')
            cls = f"HuntBX{i:03d}Test"
            write(cls, header + f"""@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class {cls} : ShareHelpBoundaryHarness() {{
{target.replace(fixed, diag)}
}}
""")
        elif variant == "BS":
            # HUNT: look for the toast in 1 ms clock steps, so the commit that shows it is (almost) never
            # followed by a frame before the check — the interleaving that the 100 ms await steps hit rarely.
            wait = "await { ShadowToast.getTextOfLatestToast() != null }"
            assert target.count(wait) == 1
            fine = ("compose.waitUntil(15000) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(1); "
                    "Shadows.shadowOf(Looper.getMainLooper()).idle(); ShadowToast.getTextOfLatestToast() != null }")
            cls = f"HuntBS{i:03d}Test"
            write(cls, header + f"""@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class {cls} : ShareHelpBoundaryHarness() {{
{target.replace(wait, fine)}
}}
""")
        else:
            cls = f"HuntBD{i:03d}Test"
            write(cls, header + f"""@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class {cls} : ShareHelpBoundaryHarness() {{
    // HUNT: the successful revoke reply arrives 500 ms after the server has counted the request.
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? =
        if (request.method == "DELETE" && !failMutation) MockResponse().setHeader("Content-Type", "application/json")
            .setBody("{{}}").setHeadersDelay(500, TimeUnit.MILLISECONDS) else null
{target}
}}
""")
elif variant in ("C", "CD"):
    src = read("BoardingCodeVisibilityTest.kt")
    if variant == "CD":
        role = '"/bookings/42/role" -> json("""{"role":"$role","status":"$bookingStatus","driver_phase":""}""")'
        assert src.count(role) == 1
        src = src.replace(role, role + ".setHeadersDelay(600, java.util.concurrent.TimeUnit.MILLISECONDS)")
    for i in range(copies):
        cls = f"Hunt{variant}{i:03d}Test"
        write(cls, src.replace("class BoardingCodeVisibilityTest {", f"class {cls} {{", 1))
elif variant in ("K", "KH", "KW", "KF", "KFH"):
    # HUNT round 5: CouponsScreenWalkTest.lateResponseForStaleAllCitiesRequest… (CI PR172, 17.10).
    # Suspicion: the screen reads cityFilter INSIDE the launched reload coroutine, so a fast /me reply
    # can set the city before that coroutine starts — then the all-cities request is never sent.
    #   K   : as on main, plus the order of requests the server saw
    #   KH  : K, but one request goes out before the screen (warm connection + IO threads, like mid-suite)
    #   KW  : me() already cached before the screen — the mechanism, deterministically
    #   KF/KFH : the fix — in the race test /me replies only after the all-cities request arrived
    src = read(os.path.join("walk", "l1_5", "CouponsScreenWalkTest.kt"))
    field = "    private val couponsListAttempts = AtomicInteger(0)\n"
    assert src.count(field) == 1
    src = src.replace(field, field + "    private val huntSeen = CopyOnWriteArrayList<String>()\n")
    rec = '                    val city = request.requestUrl!!.queryParameter("city")\n'
    assert src.count(rec) == 1
    src = src.replace(rec, rec + '                    huntSeen += "${System.nanoTime() / 1000}us ${request.method} ${request.path}"\n')
    check = ('        assertTrue(\n            "stale all-cities request must actually have been sent",\n'
             '            allCitiesRequestReceived.await(10, TimeUnit.SECONDS),\n        )\n')
    assert src.count(check) == 1
    src = src.replace(check, '        val huntSent = allCitiesRequestReceived.await(10, TimeUnit.SECONDS)\n'
                             '        assertTrue("stale all-cities request must actually have been sent; HUNT seen=$huntSeen", huntSent)\n')
    start = '        meCity = "Белорецк"\n'
    assert src.count(start) == 1
    if variant in ("KH", "KFH"):
        src = src.replace(start, start + "        kotlinx.coroutines.runBlocking { ApiClient.getCoupon(501) }  // HUNT warm-up\n")
    if variant == "KW":
        src = src.replace(start, start + "        kotlinx.coroutines.runBlocking { ApiClient.me() }  // HUNT: profile already cached\n")
    if variant in ("KF", "KFH"):
        me = '                        path == "/me" -> json(meCity?.let { """{"city":"$it"}""" } ?: "{}")\n'
        assert src.count(me) == 1
        src = src.replace(me, '                        path == "/me" -> {\n'
                              '                            if (meCity != null) allCitiesRequestReceived.await(5, TimeUnit.SECONDS)\n'
                              '                            json(meCity?.let { """{"city":"$it"}""" } ?: "{}")\n'
                              '                        }\n')
    for i in range(copies):
        cls = f"Hunt{variant}{i:03d}Test"
        write(cls, src.replace("class CouponsScreenWalkTest {", f"class {cls} {{", 1))
else:
    raise SystemExit("unknown variant " + variant)
print(f"generated {copies} {variant} copies in {OUT}")
