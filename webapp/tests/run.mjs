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
//  Без новых зависимостей: модуль собирается тем же esbuild, что и сайт,
//  браузерные вещи (localStorage, document) подделываются в самом наборе.
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
  { test: "storage-boundary-test", src: null, out: null, bundle: false },
  { test: "update-banner-test", src: null, out: null, bundle: false },
  { test: "full-offline-trip-test", src: null, out: null, bundle: false },
  { test: "boarding-code-contrast-test", src: null, out: null, bundle: false },
  { test: "rideshare-local-regression-test", src: null, out: null, bundle: false },
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
  { test: "chat-ws-ack-test", src: null, out: null, bundle: false },
  { test: "chat-intent-ui-test", src: null, out: null, bundle: false },
  { test: "chat-ws-outbox-test", src: null, out: null, bundle: false },
  { test: "outbox-delivery-key-test", src: null, out: null, bundle: false },
  { test: "outbox-legacy-collision-test", src: null, out: null, bundle: false },
  { test: "outbox-transient-error-test", src: null, out: null, bundle: false },
  { test: "refresh-outage-test", src: null, out: null, bundle: false },
  { test: "refresh-replay-test", src: null, out: null, bundle: false },
  { test: "auth-unavailable-gate-test", src: null, out: null, bundle: false },
  { test: "taxi-unavailable-gate-test", src: null, out: null, bundle: false },
  { test: "offline-banner-measure-test", src: null, out: null, bundle: false },
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
  { test: "own-front-seat-test", src: null, out: null, bundle: false },
  { test: "own-bookings-provider-test", src: null, out: null, bundle: false },
  { test: "driver-noshow-onboard-test", src: null, out: null, bundle: false },
  { test: "commission-label-test", src: null, out: null, bundle: false },
  // На «долг уже оплачен» (already: true) админка не рисует обычное «подтверждено».
  { test: "debt-already-test", src: null, out: null, bundle: false },
  { test: "map-lifecycle-test", src: null, out: null, bundle: false },
  { test: "logout-without-worker-test", src: null, out: null, bundle: false },
  { test: "st-test", src: "src/utils/serverTime.ts", out: "st-bundle.mjs", bundle: true },
  { test: "outbox-test", src: "src/utils/outbox.ts", out: "outbox-bundle.mjs", bundle: true },
  { test: "draft-test", src: "src/utils/formDraft.ts", out: "draft-bundle.mjs", bundle: true },
  { test: "privacy-test", src: "src/utils/privacy.ts", out: "privacy-bundle.mjs", bundle: true },
  { test: "pay-test", src: "src/utils/pendingPayment.ts", out: "pay-bundle.mjs", bundle: true },
  { test: "client-test", src: "src/api/client.ts", out: "client-bundle.mjs", bundle: true },
  { test: "fmt-test", src: "src/utils/format.ts", out: "fmt-bundle.mjs", bundle: true },
  { test: "seat-options-test", src: "src/utils/seatOptions.ts", out: "seat-options-bundle.mjs", bundle: true },
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
  { test: "contract-root-regression-test", src: null, out: null, bundle: false },
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
  // Чат поездки — отдельная страница: непрочитанные, «лента внизу», клавиатура (логика)…
  { test: "trip-chat-test", src: null, out: null, bundle: false },
  // …и структура: строка ввода прибита к низу экрана, входы из поездки и списка чатов.
  { test: "trip-chat-page-test", src: null, out: null, bundle: false },
  // Экран Android без маршрута PWA теперь ломает проверку в том же коммите.
  { test: "parity-test", src: null, out: null, bundle: false },
  // Токены сайта читаются из CanonTokens.kt: цвет, радиус, отступ, кегль разошлись — набор красный.
  { test: "canon-sync-test", src: null, out: null, bundle: false },
  // Запуск волнами: неизвестное = выключено, запомненное стареет, закрытые входы не открыты в обход.
  { test: "features-test", src: "src/featureRules.ts", out: "features-bundle.mjs", bundle: true },
  { test: "features-benefits-test", src: null, out: null, bundle: false },
  { test: "features-parcel-rides-test", src: null, out: null, bundle: false },
  // Паритет с Android, P0 (16.10.2026): безопасность и честные обещания.
  // Экраны SOS, контактов, «Поделиться» и помощи не обещают SMS близким (зеркало SosNoSmsPromiseGuardTest).
  { test: "sos-no-sms-promise-test", src: null, out: null, bundle: false },
  // Лента поездок идёт с токеном: заблокированных прячет сервер, «только для своих» видят свои.
  { test: "feed-with-token-test", src: null, out: null, bundle: false },
  // «Сбросить фильтры» в ленте снимает условия и города, но не «Только проверенные»; семь условий
  // приложения; сохранённое прежней версией (город, цена, старые имена) не ломает загрузку.
  { test: "filter-reset-keeps-trusted-test", src: null, out: null, bundle: false },
  // SOS из поездки передаёт бронь дежурному; после отправки показан ответ сервера (hint).
  { test: "sos-booking-link-test", src: null, out: null, bundle: false },
  // «Поделиться с близким»: ссылка на экране, «Скопировать» / «Отправить», без «отправлена» и «получили».
  { test: "share-trip-link-test", src: null, out: null, bundle: false },
  // Водитель по ссылке на бронь уходит в кабинет / на экран поездки, а не на пассажирскую «Бронь».
  { test: "booking-link-driver-test", src: null, out: null, bundle: false },
  // Паритет с Android, шаг 2 (16.10.2026): лента «Ближайшие поездки» как в приложении (PR #147/#148).
  // Что уходит на сервер (условия, проверенные, города, день, радиус), «Показать ещё» по count, состояния
  // ленты и сброс, «Показать N поездок» с плюралом, подписи чипов, подстраховка на старый сервер.
  { test: "nearby-feed-test", src: null, out: null, bundle: false },
  // «Откуда»/«Куда»: разбор /rides/cities, перечитывание не чаще раза в 2 минуты, подпись «Откуда · X +N».
  { test: "nearby-cities-test", src: null, out: null, bundle: false },
  // Ряд из четырёх чипов и шторки: роли для читалки, диалог с Esc, семь условий, стили только со шкалы, цель ≥ 48.
  { test: "nearby-filters-ui-test", src: null, out: null, bundle: false },
  // …и настоящая разметка этих компонентов (react-dom/server): строки-флажки, aria-checked, подписи чипов на двух языках.
  { test: "nearby-filters-render-test", src: null, out: null, bundle: false },
  // Паритет с Android, шаги 3–4 (17.10.2026). Наборы исполняют настоящие экраны на общем стенде
  // shallow-harness.mjs (экран поездки — active-trip-fixture.mjs).
  // «Едут с тобой» → «Открыть поездку»; звёзды только у завершённых; отменённых в блоке нет.
  { test: "driver-passengers-open-trip-test", src: null, out: null, bundle: false },
  // Отмена: на «Брони» — только ждущая и своим окном; подтверждённая — на экране поездки с причиной {reason}; «в пути» — никак.
  { test: "booking-cancel-reason-test", src: null, out: null, bundle: false },
  // Точка водителя с сайта в канал /ws/trip/{id}/location: только в пути, без журнала и аналитики, отказ не роняет экран.
  { test: "driver-location-share-test", src: null, out: null, bundle: false },
  // Такси выключено (/config/features) — такси-пунктов нет в профиле и обоих кабинетах.
  { test: "taxi-flag-hides-test", src: null, out: null, bundle: false },
  // «Позвать помощь?» и «Завершить поездку?» — окно перед необратимым, как в Android.
  { test: "confirm-before-action-test", src: null, out: null, bundle: false },
  // Одно действие — одно событие аналитики (было: publish_ride, revoke_share, courier_online дважды).
  { test: "analytics-no-double-test", src: null, out: null, bundle: false },
];

// Значения, которые Vite подставляет при сборке сайта.
const ENV = '{"VITE_API_BASE":"https://yulbash.ru","DEV":false}';

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

let failed = 0;

/** Запустить набор и напечатать итог. */
function runSuite(s) {
  try {
    const out = execFileSync("node", [join(HERE, `${s.test}.mjs`)], {
      cwd: HERE,
      stdio: "pipe",
      env: { ...process.env },
    }).toString();
    const passed = (out.match(/^✓/gm) || []).length;
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
