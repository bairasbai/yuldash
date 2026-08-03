# Compose-паттерны Юлдаша

Практические приёмы под наш проект. Каждый — из реальной ошибки, которую уже ловили (источник: `docs/lessons.md`). Не абстрактные советы: код, который можно вставить.

---

## 1. Типовой экран со списком с сервера

```kotlin
@Composable
internal fun MyScreen(vm: YuldashViewModel) {
    var reload by remember { mutableStateOf(0) }
    val state by vm.myState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(CanonBg)
            .systemBarsPadding()          // не лезем под статус-бар и навигацию
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(
            title = appText("Мои поездки", "Минең сәфәрҙәр"),
            subtitle = appText("Активные и прошлые", "Актив һәм үткәндәр"),
        )

        AppStateContainer(
            loading = state.loading,
            error = state.error,
            items = state.items,
            onRetry = { reload++ },
            emptyTitle = appText("Поездок пока нет", "Сәфәрҙәр әлегә юҡ"),
            emptyText = appText("Появятся — покажем здесь", "Барлыҡҡа килһә — күрһәтәбеҙ"),
            loadingContent = { Column { repeat(3) { SkeletonCard() } } },
        ) { items ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                    RideCard(item, Modifier.appearIn(i))   // каскадное появление
                }
            }
        }
    }
}
```

Что здесь важно: `key` в списке, `systemBarsPadding`, каскад через `appearIn(index)`, все состояния одним контейнером, обе строки заглушки двуязычные.

---

## 2. `appText()` нельзя звать внутри `LazyColumn`

Тело `LazyColumn` — не `@Composable`-контекст. Ошибка сборки: «@Composable invocations can only happen from the context of a @Composable function».

```kotlin
// ❌ не соберётся
LazyColumn {
    items(faq) { item ->
        Text(appText(item.ru, item.ba))     // appText внутри items — ок
    }
    item { Text(appText("Заголовок", "Баш")) }  // тоже ок
}

// ❌ вот это НЕ соберётся — список строится в теле LazyColumn
LazyColumn {
    val faq = listOf(appText("Вопрос", "Һорау"))   // appText в теле — ошибка
    items(faq) { Text(it) }
}

// ✅ правильно — готовим выше
@Composable
fun FaqScreen() {
    val faq = listOf(
        appText("Вопрос 1", "Һорау 1"),
        appText("Вопрос 2", "Һорау 2"),
    )
    LazyColumn { items(faq) { Text(it) } }
}
```

---

## 3. Нахлёст карточки на шапку — не через `offset`

`Modifier.offset` сдвигает **только отрисовку**, высота в разметке остаётся прежней → внизу экрана появляется пустая полоса при прокрутке.

```kotlin
// ❌ даст зазор снизу
Card(Modifier.offset(y = (-118).dp)) { ... }

// ✅ сдвигает И ужимает высоту
fun Modifier.overlapUp(overlap: Dp) = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val dy = -overlap.roundToPx()
    layout(p.width, (p.height + dy).coerceAtLeast(0)) { p.place(0, dy) }
}
```

`offset` и `graphicsLayer.translationY` годятся только для чисто визуального сдвига, который не влияет на соседей и прокрутку.

---

## 4. Поле ввода не должно прятаться за клавиатурой

```kotlin
Column(
    Modifier
        .fillMaxSize()
        .imePadding()               // поднимаем контент над клавиатурой
        .verticalScroll(rememberScrollState())
) { ... }
```

---

## 5. Тяжёлый `Canvas` ломает собственную анимацию

Compose-анимации и `LaunchedEffect` крутятся на главном потоке. Тяжёлая отрисовка каждый кадр забивает поток → анимация замирает на первом кадре и потом прыгает в финал.

Правила:
- Растровую картинку рисуй через `Image` + `graphicsLayer`, а не `drawImage` в `Canvas`.
- `Canvas` оставляй только под дешёвую векторную графику.
- Не гони долгую непрерывную анимацию, которая перерисовывает весь `Canvas`.

Диагностика: `adb logcat | grep "Skipped .* frames"`. Симптом «застряло, потом резко финал» — это блокировка потока, а не баг логики.

---

## 6. Не разрывай пару `@Composable` ↔ `fun`

```kotlin
// ❌ аннотация «прилипнет» к классу → каскад ошибок сборки
@Composable
data class CheckLine(val text: String)
private fun VoiceParsedCard() { ... }

// ✅ новый тип — ПЕРЕД аннотацией
data class CheckLine(val text: String)

@Composable
private fun VoiceParsedCard() { ... }
```

---

## 7. Тумблер обязан что-то делать

```kotlin
// ❌ «тумблер-театр»: сбросится при уходе с экрана, ни на что не влияет
var notify by remember { mutableStateOf(true) }

// ✅ персист + реальный эффект
val notify by vm.notifyEnabled.collectAsStateWithLifecycle()
Switch(checked = notify, onCheckedChange = { vm.setNotify(it) })
```

То же про кнопки: `onClick = {}` по умолчанию компилируется и выглядит живым. Проверяй, что каждая интерактивная строка реально что-то зовёт.

---

## 8. Сетевая ошибка не должна выглядеть как успех

```kotlin
// ❌ молчаливое проглатывание — пользователь думает, что сохранилось
result.onFailure { }

// ✅ откат + сообщение
result.onFailure {
    name = previousName                       // откатываем оптимистичное состояние
    toast(appText("Не удалось сохранить. Повторить?", "Һаҡлап булманы. Ҡабатларғамы?"))
}
```

И отдельно: **401 ≠ нет сети.**

```kotlin
// не вошёл → дружелюбное «пусто», а не «проверь интернет»
convError = (e as? ApiException)?.status != 401
```

---

## 9. Тёмная тема — проверять, а не надеяться

Взял цвет из `Canon*` — тёмная тема работает сама. Но проверять всё равно надо: тумблер день/ночь в шапке (`ThemePrefs.darkOverride`).

Частая ошибка — белый текст на `CanonGreen2` в тёмной теме: там зелёный светлеет, текст пропадает. Для таких мест — фиксированные `CanonGreenInk`/`CanonGreenInkDark`.

---

## 10. Дата и число — через общий хелпер

Сырой ISO `2026-07-03T12:44:...` не показывать никогда. Только через `formatDepart()`.

При сборке `Ride` из `Booking` легко забыть — грепни `= .*departAt` без `formatDepart`.

---

## 11. Векторная иконка: только `<path>`

`VectorDrawable` не понимает `<circle>`, `<ellipse>`, `<rect>` — они молча не нарисуются.

Кружок радиуса `r` в точке `(cx, cy)`:

```
M{cx-r},{cy} a{r},{r} 0 1 0 {2r},0 a{r},{r} 0 1 0 -{2r},0 Z
```

Несколько кружков можно сложить в один `path`, но прозрачность (`fillAlpha`) тогда общая — группируй по нужной прозрачности.

Рабочий порядок: рисуешь и проверяешь как SVG в браузере → переносишь path один в один в `VectorDrawable`. Сборка ловит только синтаксис, не внешний вид.

---

## 12. Экран, открываемый из нескольких мест, помнит источник возврата

Если на экран заходят из двух разных вкладок — сохраняй, откуда пришли, и возвращай туда же. Иначе человек проваливается в чужую вкладку и теряется.
