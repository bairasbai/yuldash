# Карточка: `android/app/src/main/java/com/yuldash/app/PriceSpeech.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

Озвучка цены такси вслух через системный `TextToSpeech` — для тех, кто мелкий шрифт разбора
цены не читает (бабушка, заказывающая такси в клинику). Используется ОДНИМ местом:
`InstantOrderScreen.kt::TaxiPriceAloudButton` (вне зоны листа, только чтение подтвердило
корректную передачу аргументов — см. «Связи»).

Три части:
- `PriceSpeaker` — тонкая обёртка над `android.speech.tts.TextToSpeech`: асинхронная
  инициализация, выбор языка (башкирский только если голос РЕАЛЬНО есть на телефоне, иначе
  русский), `QUEUE_FLUSH` (второе нажатие перебивает первое, а не копит очередь).
- `rememberPriceSpeaker()` — Compose-обёртка, гасит движок через `DisposableEffect`, когда
  экран уходит (иначе процесс TTS утекает).
- `rublesAloud` / `priceAloudRu` / `priceAloudBa` — чистые функции сборки фразы: три числа
  (поездка, дорога водителя при наличии, итого), без копеек (только целые рубли, уже
  округлённые сервером).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `PriceSpeaker.ready`/`bashkirAvailable` | 35–45 | Флаги готовности синтезатора | П2 (ревью Opus) ИСПРАВЛЕНО: были обычными Kotlin `var`, не состоянием Compose — `TaxiPriceAloudButton` (InstantOrderScreen.kt:474) читает `ready` прямо в теле `@Composable`, но колбэк TTS асинхронный и приходит ПОСЛЕ первой отрисовки; Compose об изменении обычного поля не узнаёт. Кнопка появлялась только случайно — если что-то ДРУГОЕ на экране перерисовывало его уже после готовности. Теперь оба поля — `by mutableStateOf(false)` | ок (после правки), R4 |
| `PriceSpeaker.init{}` | 47–57 | Асинхронно поднимает `TextToSpeech`, выбирает язык | Статус ≠ SUCCESS или движок null → `ready` остаётся false, кнопки не будет | ок |
| `PriceSpeaker.speak` | 53–58 | Произносит фразу | `!ready` или пустой текст → тихо ничего не делает (не кидает и не играет тишину); `QUEUE_FLUSH` — второй вызов обрывает первый | ок, R3 |
| `PriceSpeaker.shutdown` | 60–65 | Останавливает и освобождает движок | Безопасен к повторному вызову (`engine = null` после) | ок |
| `rememberPriceSpeaker` | 69–75 | Создаёт `PriceSpeaker` на время жизни экрана | `DisposableEffect` гасит движок на `onDispose` — системный ресурс не переживает экран | ок |
| `rublesAloud` | 78–79 | Число → «N рубль/рубля/рублей» | Склонение через общий `pluralRu` (MainActivity.kt, вне зоны, не трогал) | ок, R1 |
| `priceAloudRu` | 88–92 | Собирает русскую фразу из трёх чисел | `pickupFee == 0` → клаузу «дорога водителя» пропускает; `total` читается КАК ПРИШЁЛ (не пересчитывается), поэтому скидка промокода не расходится с озвучкой | ок, R2 |
| `priceAloudBa` | 95–99 | То же по-башкирски | Та же логика пропуска подачи; слово «һум» вместо «рубль» | ок, R3 |

## Связи

- Единственный вызывающий: `InstantOrderScreen.kt::TaxiPriceAloudButton` (вне зоны). Прочитан
  для проверки единиц: `estimate.ridePrice`/`estimate.pickupFee`/`estimate.priceToPay` —
  ВСЕ в рублях (поле без суффикса `Kop`, по соглашению проекта, подтверждено в
  `data/ApiClient.kt:5424-5425,5475`), то есть передаются в `rublesAloud`/`priceAloud*`
  корректно — исторического бага «копейки/рубли» здесь нет.
- `pluralRu` — общий хелпер из `MainActivity.kt` (вне зоны, не трогал).
- Системный `android.speech.tts.TextToSpeech` — не мокается, в unit-тестах не вызывается
  напрямую (тестируются только чистые строковые функции).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Русское число согласовано по падежу (1 рубль / 2 рубля / 5 рублей), включая ловушки 11 и 21 | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::rublesAloud_declinesByRussianPluralRules` | да — M12 |
| R2 | Без подачи — короче: поездка+итог, без лишней клаузы «дорога водителя 0 рублей» (RU) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::priceAloudRu_withoutPickupFee_skipsDriverRoadClause`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::priceAloudRu_withPickupFee_readsThreeNumbersInOrder`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::priceAloudRu_doesNotRecomputeTotal_readsItVerbatim` | да — M13 |
| R3 | То же для башкирской фразы | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::priceAloudBa_withoutPickupFee_skipsDriverRoadClause`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeechTest.kt::priceAloudBa_withPickupFee_readsThreeNumbersInOrder` | да — M14 |
| R4 | `ready` — настоящее состояние Compose: кнопка появляется САМА, как только синтезатор готов, без лишних перерисовок экрана | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PriceSpeakerReadyStateTest.kt::readyFlipsAfterAsyncCallback_recomposesWithoutAnyOtherTrigger`, `::noLanguageAvailable_staysNotReady_noCrashOnFailureStatus` | да — M41 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E6 (P2, ревью Opus) | Кнопка «Прочитать цену вслух» могла не появиться вовсе. `ready`/`bashkirAvailable` были обычными Kotlin `var`, а не состоянием Compose. Колбэк `TextToSpeech.OnInitListener` асинхронный и приходит ПОСЛЕ первой отрисовки экрана с ценой — обычное поле меняется мимо системы перерисовки. Кнопка появлялась СЛУЧАЙНО: только если что-то ДРУГОЕ на экране (новая оценка цены, смена темы и т.п.) заново вызывало перерисовку уже ПОСЛЕ готовности синтезатора. | `Robolectric ShadowTextToSpeech`: сконструировать `PriceSpeaker` в составе composable, вызвать `shadowOf(tts).onInitListener.onInit(SUCCESS)` ПОСЛЕ первой отрисовки, без единого другого триггера перерисовки | `ready`/`bashkirAvailable` переведены на `by mutableStateOf(false)` | `PriceSpeakerReadyStateTest::readyFlipsAfterAsyncCallback_recomposesWithoutAnyOtherTrigger` — до правки кнопка так и не появилась бы (обычное поле не доходит до Compose); после — появляется сама сразу после `onInit(SUCCESS)` |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M12 | Местами переставлены «рубля»/«рублей» в `pluralRu(...)` | `rublesAloud_declinesByRussianPluralRules` | KILLED |
| M13 | `pickupFee > 0` → `pickupFee >= 0` в `priceAloudRu` (клауза подачи всегда читается) | `priceAloudRu_withoutPickupFee_skipsDriverRoadClause` | KILLED |
| M14 | То же для `priceAloudBa` | `priceAloudBa_withoutPickupFee_skipsDriverRoadClause` | KILLED |
| M41 | `ready` снова обычное Kotlin-поле, не состояние Compose | `PriceSpeakerReadyStateTest::readyFlipsAfterAsyncCallback_recomposesWithoutAnyOtherTrigger` | KILLED |

## Остаток и ограничения

Настоящий `TextToSpeech` (наличие башкирского голоса на реальном устройстве, реальное
произношение) не проверялся — это требует эмулятора/устройства с установленным голосом,
чего нет на этой машине. Логика ВЫБОРА голоса (`bashkirAvailable`, фолбэк на русский текст)
прочитана и соответствует комментариям; поведение самого синтезатора — вне доступного
инструментария unit-тестов.

**P3, не блокирует (из отчёта ведущему):** язык синтезатора (`tts.language`) задаётся ОДИН раз
в `init{}`, не перед каждой фразой — если на телефоне есть башкирский голос, русский текст при
русском интерфейсе может прочитаться башкирским голосом. Решение за Александром.
