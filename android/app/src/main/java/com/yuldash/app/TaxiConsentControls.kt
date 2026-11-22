package com.yuldash.app

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TaxiConsentController
import com.yuldash.app.data.TaxiConsentDetails
import com.yuldash.app.data.TaxiConsentOwner
import com.yuldash.app.data.TaxiConsentProblem
import com.yuldash.app.data.TaxiConsentQuote
import com.yuldash.app.data.TaxiConsentRecovery
import com.yuldash.app.data.TaxiConsentRoute
import com.yuldash.app.data.TaxiConsentState
import com.yuldash.app.data.TaxiConsentTime
import com.yuldash.app.data.TaxiQuoteConsentApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class TaxiConsentMoneyCopy(val total: String, val ru: String, val ba: String)

internal fun taxiConsentMoneyCopy(q: TaxiConsentQuote): TaxiConsentMoneyCopy {
    val total = formatTaxiKop(q.payableKop)
    return TaxiConsentMoneyCopy(total,
        "${formatTaxiKop(q.priceKop)} − скидка ${formatTaxiKop(q.promoKop)}. Итого к подтверждению: $total.",
        "${formatTaxiKop(q.priceKop)} − ташлама ${formatTaxiKop(q.promoKop)}. Раҫларға бөтәһе: $total.")
}

@Composable
internal fun rememberTaxiConsentController(owner: TaxiConsentOwner?): TaxiConsentController? {
    val context = LocalContext.current
    val controller = remember(owner) {
        owner?.let {
            TaxiConsentController(it, TaxiQuoteConsentApi, TaxiQuoteConsentApi.markers(context)) {
                TaxiConsentTime(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            }
        }
    }
    DisposableEffect(controller) { onDispose { controller?.close() } }
    return controller
}

@Composable
private fun consentNotice(state: TaxiConsentState): String = when (state.recovery) {
    TaxiConsentRecovery.RETIRED -> appText(
        "Заказ был создан. Его карточка больше недоступна. По этой попытке второй заказ не появится.",
        "Заказ булдырылған. Уның карточкаһы хәҙер асылмай. Был ынтылыш буйынса икенсе заказ булмаясаҡ.")
    TaxiConsentRecovery.CARD_UNAVAILABLE -> appText(
        "Заказ был создан, но карточку загрузить не удалось. Повтори проверку.",
        "Заказ булдырылған, әммә уның карточкаһын асып булманы. Тағы тикшереп ҡара.")
    TaxiConsentRecovery.UNVERIFIED -> appText(
        "Сервер закрыл возможность позднего заказа по этой попытке. Неизвестно, был ли заказ создан раньше.",
        "Сервер был ынтылыш буйынса һуңынан заказ биреү мөмкинлеген япты. Заказ элегерәк булдырылғанмы — билдәһеҙ.")
    TaxiConsentRecovery.CLOSED -> appText(
        "Попытка завершена. Заказ не был создан.", "Ынтылыш тамамланды. Заказ булдырылманы.")
    TaxiConsentRecovery.LOOKUP_UNKNOWN -> appText(
        "Сервер пока не подтвердил заказ. Можно проверить ещё раз или завершить попытку.",
        "Сервер заказды әлегә раҫламаны. Тағы тикшерергә йәки ынтылышты тамамларға мөмкин.")
    TaxiConsentRecovery.UNAVAILABLE -> appText(
        "Не удалось узнать результат. Проверь связь и попробуй ещё раз.",
        "Һөҙөмтәне белеп булманы. Бәйләнеште тикшер ҙә ҡабатлап ҡара.")
    null -> when {
        state.closeRequested && state.recoverable -> appText(
            "Завершение попытки требует проверки. Новый заказ пока недоступен.",
            "Ынтылыштың тамамланыуын тикшерергә кәрәк. Әлегә яңы заказ биреп булмай.")
        state.recoverable -> appText(
            "Результат подтверждения неизвестен. Проверь заказ или заверши попытку.",
            "Раҫлау һөҙөмтәһе билдәһеҙ. Заказды тикшер йәки ынтылышты тамамла.")
        state.blocked && state.problem == null -> appText(
            "У прошлой попытки нет серверного ключа. Проверь историю поездок.",
            "Элекке ынтылыштың сервер асҡысы юҡ. Сәфәрҙәр тарихын тикшер.")
        state.expired -> appText("Срок расчёта истёк. Обнови сумму.", "Иҫәп ваҡыты үтте. Сумманы яңырт.")
        else -> ""
    }
}

@Composable
internal fun TaxiConsentControls(
    controller: TaxiConsentController?,
    route: TaxiConsentRoute?,
    details: TaxiConsentDetails,
    onCreated: (InstantOrderDto) -> Unit,
    modifier: Modifier = Modifier,
    onClosedContinue: (() -> Unit)? = null,
    onCalculate: () -> Unit = {},
    isCurrentScreen: () -> Boolean = { true },
) {
    if (controller == null) {
        Text(appText("Подтверждение суммы сейчас недоступно.", "Сумманы раҫлау әлегә мөмкин түгел."),
            modifier = modifier, style = CanonBody, color = CanonText)
        return
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val publish by rememberUpdatedState(onCreated)
    val currentScreen by rememberUpdatedState(isCurrentScreen)
    var closeReview by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, route) { controller.routeChanged(route) }
    val order = state.recoveredOrder ?: state.receipt?.order
    LaunchedEffect(controller, order) {
        if (order != null) controller.deliverOrder(isCurrentScreen = { currentScreen() }, publish = { publish(it) })
    }
    RepeatWhileVisible(controller) {
        while (isActive) { controller.checkTime(); delay(CanonMotion.TICK.toLong()) }
    }
    if (order != null) return

    val busy = state.loading || state.sending || state.recovering
    val locked = state.unknown || state.blocked
    val quote = state.quote?.takeIf { state.canReplay || state.sending || (!locked && route != null && state.quotedFor == route) }
    val copy = quote?.let(::taxiConsentMoneyCopy)
    val notice = consentNotice(state)
    val problem = when (state.problem) {
        TaxiConsentProblem.PRICE_PENDING -> appText(
            "Стоимость подачи ещё неизвестна. Повтори расчёт, когда появится водитель.",
            "Машина килеү хаҡы әлегә билдәһеҙ. Йөрөтөүсе күренгәс, иҫәпте ҡабатла.")
        TaxiConsentProblem.REFRESH -> appText("Подтверждение не принято. Обнови сумму и проверь поездки.",
            "Раҫлау ҡабул ителмәне. Сумманы яңырт һәм сәфәрҙәрҙе тикшер.")
        TaxiConsentProblem.STORAGE -> appText("Не удалось прочитать или сохранить состояние попытки. Проверь поездки.",
            "Ынтылыштың хәлен уҡып йәки һаҡлап булманы. Сәфәрҙәрҙе тикшер.")
        TaxiConsentProblem.UNAVAILABLE -> appText("Подтверждение суммы сейчас недоступно.", "Сумманы раҫлау әлегә мөмкин түгел.")
        TaxiConsentProblem.QUOTE -> appText("Не удалось получить сумму. Повтори расчёт.", "Сумманы алып булманы. Иҫәпте ҡабатла.")
        null -> ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        if (notice.isNotEmpty()) Text(notice, style = CanonBody, color = CanonText,
            modifier = Modifier.testTag("taxiConsentNotice").semantics { liveRegion = LiveRegionMode.Polite })
        if (problem.isNotEmpty()) Text(problem, style = CanonCaption, color = CanonRed,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.recoverable) {
            AppButton(text = appText("Проверить результат заказа", "Заказ һөҙөмтәһен тикшереү"),
                onClick = { if (isCurrentScreen()) scope.launch { controller.checkResult() } }, enabled = !busy,
                modifier = Modifier.testTag("taxiConsentCheckResult"))
            AppButton(text = appText("Завершить попытку", "Ынтылышты тамамлау"), style = AppButtonStyle.Secondary,
                onClick = { if (isCurrentScreen()) closeReview = true }, enabled = !busy, modifier = Modifier.testTag("taxiConsentClose"))
            if (state.recovering) Text(appText("Ждём ответ сервера…", "Серверҙың яуабын көтәбеҙ…"), style = CanonCaption,
                color = CanonMuted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (!locked && copy != null && !state.expired) {
            Text(appText("Итого ${copy.total}", "Бөтәһе ${copy.total}"), style = CanonBodyStrong, color = CanonText,
                modifier = Modifier.testTag("taxiConsentTotal"))
        }
        if (!locked || state.canReplay) {
            AppButton(text = when {
                state.sending -> appText("Подтверждаем…", "Раҫлайбыҙ…")
                state.loading -> appText("Получаем сумму…", "Сумманы алабыҙ…")
                state.canReplay -> appText("Повторить подтверждение", "Раҫлауҙы ҡабатлау")
                state.recovery == TaxiConsentRecovery.CLOSED && onClosedContinue != null ->
                    appText("Выбрать маршрут", "Маршрут һайлау")
                state.recovery == TaxiConsentRecovery.CLOSED || copy == null -> appText("Рассчитать цену", "Хаҡты иҫәпләү")
                state.expired -> appText("Обновить сумму", "Сумманы яңыртыу")
                else -> appText("Заказать за ${copy.total}", "${copy.total} — заказ итеү")
            }, onClick = action@{
                if (!isCurrentScreen()) return@action
                if (state.recovery == TaxiConsentRecovery.CLOSED && onClosedContinue != null) onClosedContinue()
                else {
                    if (!locked && route != null && (copy == null || state.expired)) onCalculate()
                    scope.launch { controller.primary(route) }
                }
            }, loading = state.sending || state.loading, enabled = !busy &&
                (route != null || state.canReplay || (state.recovery == TaxiConsentRecovery.CLOSED && onClosedContinue != null)),
                modifier = Modifier.testTag("taxiConsentPrimary"))
        }
    }

    if (closeReview && state.recoverable && !state.review) {
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { closeReview = false },
            containerColor = CanonSurface,
            title = { Text(appText("Завершить попытку заказа?", "Заказ биреү ынтылышын тамамларғамы?"),
                style = CanonHeading, color = CanonText, modifier = Modifier.semantics { heading() }) },
            text = { Text(appText(
                "Если заказ уже создан, откроется его карточка. Если заказ ещё не создан, эта попытка больше не вызовет машину.",
                "Заказ булдырылған булһа, уның карточкаһы асыласаҡ. Заказ әлегә булдырылмаған булһа, был ынтылыш буйынса машина саҡырылмаясаҡ."),
                style = CanonBody, color = CanonText, modifier = Modifier.verticalScroll(rememberScrollState())) },
            dismissButton = {
                TextButton(onClick = { closeReview = false },
                    modifier = Modifier.testTag("taxiConsentCloseCancel").focusRequester(cancelFocus)) {
                    Text(appText("Назад", "Артҡа"), style = CanonButton, color = CanonGreen2)
                }
                LaunchedEffect(Unit) { cancelFocus.requestFocus() }
            },
            confirmButton = {
                AppButton(text = appText("Завершить попытку", "Ынтылышты тамамлау"), fillWidth = false,
                    style = AppButtonStyle.Danger, enabled = !busy, modifier = Modifier.testTag("taxiConsentCloseConfirm"),
                    onClick = {
                        closeReview = false
                        if (isCurrentScreen()) scope.launch { controller.closeAttempt() }
                    })
            },
        )
    }
    if (state.review && copy != null) {
        AlertDialog(
            onDismissRequest = { controller.dismissReview() },
            containerColor = CanonSurface,
            title = { Text(when {
                state.canReplay -> appText("Повторить подтверждение ${copy.total}?", "${copy.total} раҫлауҙы ҡабатларғамы?")
                state.updated -> appText("Сумма обновлена", "Сумма яңыртылды")
                else -> appText("Подтверди итог", "Йомғаҡты раҫла")
            }, style = CanonHeading, color = CanonText) },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                if (state.canReplay) Text(appText("Повторяем тот же запрос с прежними адресами и параметрами.",
                    "Элекке адрес һәм параметрҙар менән шул уҡ һорауҙы ҡабатлайбыҙ."), style = CanonBody, color = CanonText)
                Text(appText(copy.ru, copy.ba), style = CanonBody, color = CanonText)
                if (state.expired && !state.canReplay) Text(appText("Сначала обновим сумму. Заказ ещё не отправляем.",
                    "Тәүҙә сумманы яңыртабыҙ. Заказды әлегә ебәрмәйбеҙ."), style = CanonCaption, color = CanonMuted)
            } },
            dismissButton = { TextButton(onClick = { controller.dismissReview() }, enabled = !busy,
                modifier = Modifier.testTag("taxiConsentReviewCancel")) {
                Text(appText("Назад", "Артҡа"), style = CanonButton, color = CanonGreen2)
            } },
            confirmButton = {
                AppButton(text = if (state.expired && !state.canReplay) appText("Обновить сумму", "Сумманы яңыртыу")
                    else appText("Подтвердить ${copy.total}", "${copy.total} раҫлау"), fillWidth = false,
                    loading = state.sending, enabled = !busy, modifier = Modifier.testTag("taxiConsentConfirm"),
                    onClick = { if (isCurrentScreen()) scope.launch { controller.confirm(details, route) } })
            },
            properties = DialogProperties(dismissOnBackPress = !state.sending, dismissOnClickOutside = !state.sending),
        )
    }
}
