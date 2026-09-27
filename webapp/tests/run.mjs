// ================================================================
//  Проверки логики, которую нельзя увидеть глазами.
//
//  Запуск:  npm run check
//
//  Здесь не «тесты ради тестов»: каждый набор появился из настоящей поломки,
//  которая доходила до человека и которую не ловили ни сборка, ни типы.
//  Время сервера, очередь без сети, черновики форм, чистка при выходе,
//  возврат из банка, тексты ошибок, язык дат, повтор по видимости экрана.
//
//  Модули собираются тем же esbuild, что и сайт. Router-набор исполняет React
//  через react-test-renderer; browser History, localStorage и document — fixtures.
//  Эти наборы не заменяют настоящий браузер или установленную PWA.
// ================================================================
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { build } from "esbuild";

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, ".bundles");

/** набор → что для него собрать (модуль сайта → имя файла сборки). */
const SUITES = [
  { test: "router-migration-test", src: null, out: null, bundle: false },
  { test: "active-trip-owner-write-test", src: null, out: null, bundle: false },
  { test: "terminal-session-owner-test", src: null, out: null, bundle: false },
  { test: "session-storage-atomic-test", src: null, out: null, bundle: false },
  { test: "login-flow-test", src: null, out: null, bundle: false },
  { test: "onboarding-flow-test", src: null, out: null, bundle: false },
  { test: "intro-motion-test", src: null, out: null, bundle: false },
  { test: "intro-behavior-test", src: null, out: null, bundle: false },
  { test: "splash-navigation-test", src: null, out: null, bundle: false },
  { test: "session-caches-test", src: null, out: null, bundle: false },
  { test: "taxi-rating-recovery-test", src: null, out: null, bundle: false },
  { test: "parcel-rating-recovery-test", src: null, out: null, bundle: false },
  { test: "courier-completion-test", src: null, out: null, bundle: false },
  { test: "taxi-bootstrap-test", src: null, out: null, bundle: false },
  { test: "courier-goods-error-test", src: null, out: null, bundle: false },
  { test: "tab-personal-storage-test", src: null, out: null, bundle: false },
  { test: "outbox-session-boundary-test", src: null, out: null, bundle: false },
  { test: "outbox-cross-tab-test", src: null, out: null, bundle: false },
  { test: "outbox-id-test", src: null, out: null, bundle: false },
  { test: "outbox-idb-test", src: null, out: null, bundle: false },
  { test: "outbox-save-ui-test", src: null, out: null, bundle: false },
  { test: "outbox-delivery-key-test", src: null, out: null, bundle: false },
  { test: "outbox-legacy-collision-test", src: null, out: null, bundle: false },
  { test: "outbox-transient-error-test", src: null, out: null, bundle: false },
  { test: "refresh-outage-test", src: null, out: null, bundle: false },
  { test: "refresh-replay-test", src: null, out: null, bundle: false },
  { test: "auth-unavailable-gate-test", src: null, out: null, bundle: false },
  { test: "draft-owner-test", src: null, out: null, bundle: false },
  { test: "consent-mirror-owner-test", src: null, out: null, bundle: false },
  { test: "sos-account-boundary-test", src: null, out: null, bundle: false },
  { test: "consents-account-boundary-test", src: null, out: null, bundle: false },
  { test: "auth-provider-cross-tab-test", src: null, out: null, bundle: false },
  { test: "cross-tab-session-test", src: null, out: null, bundle: false },
  { test: "web-push-session-test", src: null, out: null, bundle: false },
  { test: "auth-provider-session-test", src: null, out: null, bundle: false },
  { test: "session-boundary-test", src: null, out: null, bundle: false },
  { test: "trip-rating-submit-test", src: null, out: null, bundle: false },
  { test: "notification-destinations-test", src: null, out: null, bundle: false },
  { test: "taxi-wait-cancel-test", src: null, out: null, bundle: false },
  { test: "taxi-origin-picker-test", src: null, out: null, bundle: false },
  { test: "ride-booking-path-test", src: null, out: null, bundle: false },
  { test: "commission-label-test", src: null, out: null, bundle: false },
  { test: "map-lifecycle-test", src: null, out: null, bundle: false },
  { test: "logout-without-worker-test", src: null, out: null, bundle: false },
  { test: "st-test", src: "src/utils/serverTime.ts", out: "st-bundle.mjs", bundle: true },
  { test: "outbox-test", src: "src/utils/outbox.ts", out: "outbox-bundle.mjs", bundle: true },
  { test: "draft-test", src: "src/utils/formDraft.ts", out: "draft-bundle.mjs", bundle: true },
  { test: "privacy-test", src: "src/utils/privacy.ts", out: "privacy-bundle.mjs", bundle: true },
  { test: "pay-test", src: "src/utils/pendingPayment.ts", out: "pay-bundle.mjs", bundle: true },
  { test: "client-test", src: "src/api/client.ts", out: "client-bundle.mjs", bundle: true },
  { test: "fmt-test", src: "src/utils/format.ts", out: "fmt-bundle.mjs", bundle: true },
  // Хук трогает react — оставляем импорт нетронутым, набор подменяет его сам.
  { test: "vis-test", src: "src/utils/useVisibleInterval.ts", out: "vis-bundle.mjs", bundle: false },
  // Словарь читает исходники напрямую — собирать нечего.
  { test: "glossary-test", src: null, out: null, bundle: false },
  // Правила оформления читаются из CSS напрямую.
  { test: "ui-test", src: null, out: null, bundle: false },
  // Вес того, что скачивает человек: смотрим файлы, не сборку.
  { test: "budget-test", src: null, out: null, bundle: false },
  // Сверка описаний ответа с моделями сервера (если сервер лежит рядом).
  { test: "contract-test", src: null, out: null, bundle: false },
  // Сторож трёх поломок, которые уже случались и легко возвращаются.
  { test: "guard-test", src: null, out: null, bundle: false },
  // Правила, защищающие человека: чистка при выходе, уход на оплату, отказы.
  { test: "privacy-guard-test", src: null, out: null, bundle: false },
  // Состояния экрана: пустое ведёт, у загрузки есть ошибка, у ошибки — повтор.
  { test: "states-test", src: null, out: null, bundle: false },
  // Движение: приложение, PWA и сайт на одной шкале — числа сверяются, сырых нет.
  { test: "motion-test", src: null, out: null, bundle: false },
  // Цена нового адреса принадлежит только последнему выбранному адресу.
  { test: "destination-preview-test", src: null, out: null, bundle: false },
  // Уже отправленный зимний вопрос восстанавливается после reload до подтверждения.
  { test: "winter-check-reload-test", src: null, out: null, bundle: false },
  // Отправитель открывает спор из своей карточки, когда назначен курьер.
  { test: "parcel-sender-dispute-test", src: null, out: null, bundle: false },
  // Лёгкий live-статус обновляет приватные details только по значимому событию/редко.
  { test: "active-trip-details-sync-test", src: null, out: null, bundle: false },
  { test: "payment-draft-sync-test", src: null, out: null, bundle: false },
  // Отмена пассажиром освобождает экран водителя и не затирает следующий заказ.
  { test: "driver-cancelled-order-test", src: null, out: null, bundle: false },
  // Медленный accept не сопровождается противоречивым decline от таймера.
  { test: "driver-offer-accept-race-test", src: null, out: null, bundle: false },
  // Экран Android без маршрута PWA теперь ломает проверку в том же коммите.
  { test: "parity-test", src: null, out: null, bundle: false },
  // Токены сайта читаются из CanonTokens.kt: цвет, радиус, отступ, кегль разошлись — набор красный.
  { test: "canon-sync-test", src: null, out: null, bundle: false },
];

// Значения, которые Vite подставляет при сборке сайта.
const ENV = '{"VITE_API_BASE":"https://yulbash.ru","DEV":false}';

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

let failed = 0;

/** Запустить набор и напечатать итог. */
function runSuite(s) {
  try {
    const out = execFileSync(process.execPath, [join(HERE, `${s.test}.mjs`)], {
      cwd: HERE,
      stdio: "pipe",
      env: { ...process.env },
    }).toString();
    const passed = (out.match(/^(?:✓|PASS )/gm) || []).length;
    console.log(`  ✓ ${s.test} — ${passed} проверок`);
  } catch (e) {
    console.log(`  ✗ ${s.test} — ПРОВАЛ`);
    // Показываем, ЧТО именно не сошлось, а не только факт провала.
    const detail = String(e.stdout || "") + String(e.stderr || "");
    console.log(
      detail
        .split("\n")
        .filter((l) => l.trim())
        .slice(-12)
        .map((l) => "      " + l)
        .join("\n")
    );
    failed++;
  }
}

for (const s of SUITES) {
  // Набор без сборки (читает исходники сам) — сразу к запуску.
  if (!s.src) {
    runSuite(s);
    continue;
  }

  // Собираем тем же esbuild, что стоит за Vite — как модуль, а не как команду:
  // на Windows запуск .cmd из Node требует оболочки, а та съедает кавычки в --define.
  try {
    await build({
      absWorkingDir: join(HERE, ".."),
      entryPoints: [s.src],
      bundle: s.bundle,
      format: "esm",
      define: { "import.meta.env": ENV },
      outfile: join(OUT, s.out),
      logLevel: "silent",
    });
  } catch (e) {
    console.log(`  ✗ ${s.test} — не собрался`);
    console.log(String(e.message).trim().split("\n").slice(0, 5).join("\n"));
    failed++;
    continue;
  }

  runSuite(s);
}

console.log("");
if (failed) {
  console.log(`ПРОВАЛЕНО НАБОРОВ: ${failed}`);
  process.exit(1);
}
console.log("ВСЕ НАБОРЫ ПРОШЛИ");
