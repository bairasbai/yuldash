package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Общая шторка такси: карта во весь экран, поверх неё панель с тремя положениями.
 *
 * Одна на все экраны такси — поездку и заказ. Заведена отдельно ровно поэтому: два похожих,
 * но самостоятельных «почти одинаковых» блока в одном приложении через месяц расходятся
 * по высоте, скруглению и поведению жеста, и человек начинает чувствовать, что экраны
 * собирали разные люди.
 *
 * Что здесь решено раз и навсегда (проверено живым прогоном на эмуляторе):
 *
 *  • **Высота идёт по содержимому.** Фиксированная высота положения оставляла под короткой
 *    карточкой пустую тёмную плиту в пол-экрана — читается как «что-то не загрузилось».
 *    Причина: прокручиваемый контейнер всегда занимает ровно то, что ему дают.
 *
 *  • **Тянут за всю шапку, не за полоску.** Свайп в двадцати пикселях выше полоски уходил
 *    в карту: шторка стояла, зато уезжала карта. Человек целится в «верх шторки», а не
 *    в нарисованное, — значит и хват должен быть размером с верх шторки.
 *
 *  • **Жест — шагами.** Вверх на положение выше, вниз на положение ниже. Промежуточных высот
 *    нет: полуоткрытая панель выглядит поломкой, а не состоянием.
 */
internal enum class TaxiSheetStop { Peek, Half, Full }

/** Насколько надо потянуть шапку, чтобы шторка переехала на соседнее положение. Пиксели жеста. */
private const val SHEET_SWIPE_THRESHOLD = 48f

@Composable
internal fun TaxiSheetScaffold(
    stop: TaxiSheetStop,
    onStopChange: (TaxiSheetStop) -> Unit,
    map: @Composable (Modifier) -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
    // Главное действие экрана. Живёт ВНЕ прокрутки и видно в любом положении: кнопка,
    // уехавшая за нижний край, — это экран, на котором нельзя сделать то, ради чего пришли.
    footer: @Composable ColumnScope.() -> Unit = {},
    // Есть ли что показывать в подвале. Слот сам про это сказать не может: пустая лямбда
    // рисует ноль высоты, но её обёртка со своими отступами и запасом под системную панель
    // остаётся — и под содержимым висит полоса пустоты в полсотни точек.
    hasFooter: Boolean = true,
    // Стоит ли шторка последней на экране. Если под ней есть своя нижняя панель (экран такси
    // встроен в хаб), запас под системную навигацию берёт она — и шторке добавлять его нельзя,
    // иначе между содержимым и панелью висит полоса пустоты в палец шириной.
    underSystemBar: Boolean = true,
    // На мастер-экране заказа карта важнее длинной формы. Остальные состояния такси сохраняют
    // прежнюю высоту; параметр меняет только явно выбранный экран.
    halfBodyFraction: Float = 0.38f,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CanonBg)) {
        val screenH = maxHeight
        // Копим жест до отпускания: решение принимаем один раз, а не на каждом кадре.
        var swipe by remember { mutableStateOf(0f) }

        map(Modifier.fillMaxSize())

        // Кант в дополнение к тени. Тень мягкая по природе: на тёмной карте она держит край,
        // а на светлой (бежевый фон Яндекс-карты почти одного веса с белой шторкой) растворяется,
        // и верхняя грань пропадает — шторка будто вытекает из карты. Кант даёт грань при любом
        // фоне, тень — объём. Одного без другого мало.
        val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Surface(
            color = CanonSurface,
            shape = sheetShape,
            shadowElevation = CanonDepth.sheet,
            border = BorderStroke(1.dp, CanonBorder),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .animateContentSize(tween(CanonMotion.SLOW)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Шапка = зона хвата. Всё, что в ней, видно в любом положении.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> swipe += delta },
                            onDragStopped = {
                                val step = when {
                                    swipe < -SHEET_SWIPE_THRESHOLD -> 1
                                    swipe > SHEET_SWIPE_THRESHOLD -> -1
                                    else -> 0
                                }
                                if (step != 0) {
                                    val next = (stop.ordinal + step)
                                        .coerceIn(0, TaxiSheetStop.entries.lastIndex)
                                    onStopChange(TaxiSheetStop.entries[next])
                                }
                                swipe = 0f
                            },
                        )
                        .padding(horizontal = CanonSpace.lg),
                ) {
                    TaxiSheetHandle()
                    header()
                    androidx.compose.foundation.layout.Spacer(Modifier.height(CanonSpace.md))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        // Потолок и прокрутка ВСЕГДА, а не только в полном положении.
                        // Без потолка содержимое росло свободно, упиралось в край экрана
                        // и обрезалось молча — вместе с кнопкой «Вызвать».
                        //
                        // Доли подобраны на эмуляторе: карта должна оставаться видимой полосой,
                        // иначе шторка съедает экран целиком и «карта во весь рост» превращается
                        // в полоску в сантиметр. Содержимое сверх потолка прокручивается внутри.
                        // Полное положение — 0.56, а не 0.70: при 0.70 шапка, содержимое и закреплённый
                        // низ вместе перерастали экран, и кнопка «Вызвать» уезжала за нижний край.
                        // Считать надо не «сколько занять», а «сколько остаётся низу».
                        .heightIn(max = screenH * (if (stop == TaxiSheetStop.Full) 0.56f else halfBodyFraction))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = CanonSpace.lg)
                        // Воздух снизу: без него прокрутка обрывала карточку ровно посередине,
                        // впритык к закреплённой кнопке — читалось как обрезанный экран,
                        // а не как «дальше есть ещё».
                        .padding(bottom = CanonSpace.md),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.lg),
                ) {
                    AnimatedVisibility(
                        visible = stop != TaxiSheetStop.Peek,
                        enter = fadeIn(tween(CanonMotion.NORMAL)),
                        exit = fadeOut(tween(CanonMotion.QUICK)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.lg)) { body() }
                    }
                    AnimatedVisibility(
                        visible = stop == TaxiSheetStop.Full,
                        enter = fadeIn(tween(CanonMotion.NORMAL)),
                        exit = fadeOut(tween(CanonMotion.QUICK)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.lg)) { extra() }
                    }
                }
                // Закреплённый низ: прокрутка его не уносит. Пока показывать нечего — не
                // рисуем и обёртку, иначе её отступы читаются как дыра в конце шторки.
                if (hasFooter) Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CanonSpace.lg)
                        .padding(bottom = if (underSystemBar) CanonSpace.lg else CanonSpace.sm)
                        .then(if (underSystemBar) Modifier.navigationBarsPadding() else Modifier),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    footer()
                }
            }
        }

        overlay()
    }
}

/** Полоска-хват. Нарисована мелко, но нажимается вместе со всей шапкой — см. комментарий выше. */
@Composable
private fun TaxiSheetHandle() {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .padding(vertical = CanonSpace.sm),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                // Плотнее канта: полоска — приглашение к жесту, а не декоративная черта.
                // На CanonBorder её было почти не видно, и шторку тянули наугад.
                .background(CanonMutedStrong, RoundedCornerShape(8.dp)),
        )
    }
}
