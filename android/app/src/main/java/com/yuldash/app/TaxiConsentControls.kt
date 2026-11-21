package com.yuldash.app

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.data.TaxiConsentController
import com.yuldash.app.data.TaxiConsentDetails
import com.yuldash.app.data.TaxiConsentProblem
import com.yuldash.app.data.TaxiConsentQuote
import com.yuldash.app.data.TaxiConsentRoute
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
internal fun TaxiConsentControls(
    generation: Long, route: TaxiConsentRoute?, details: TaxiConsentDetails,
    onCreated: (InstantOrderDto) -> Unit, onLockChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = TaxiQuoteConsentApi.owner(generation)
    val callbacks = rememberUpdatedState(onCreated to onLockChange)
    val controller = remember(owner) {
        owner?.let { TaxiConsentController(it, TaxiQuoteConsentApi, TaxiQuoteConsentApi.markers(context)) {
            TaxiConsentTime(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        } }
    }
    DisposableEffect(controller) {
        onDispose { controller?.close(); callbacks.value.second(false) }
    }
    if (controller == null) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Подтверждение суммы сейчас недоступно.", "Сумманы раҫлау әлегә мөмкин түгел."), style = CanonCaption, color = CanonMuted)
            AppButton(appText("Обновить сумму", "Сумманы яңыртыу"), onClick = {}, enabled = false)
        }
        return
    }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller, route) { controller.refresh(route) }
    LaunchedEffect(controller, state.review, state.sending, state.unknown) {
        callbacks.value.second(state.review || state.sending || state.unknown)
    }
    LaunchedEffect(controller, state.receipt) {
        state.receipt?.let { receipt -> if (TaxiQuoteConsentApi.current(owner!!)) callbacks.value.first(receipt.order) }
    }
    RepeatWhileVisible(controller) {
        controller.checkTime() // Includes elapsedRealtime across deep sleep; never sends a request.
        while (isActive) { delay(CanonMotion.TICK.toLong()); controller.checkTime() }
    }
    // Also fence the frame BEFORE the effect handles a changed input.
    val quote = state.quote.takeIf { state.unknown || state.sending || state.quotedFor == route }
    val copy = quote?.let(::taxiConsentMoneyCopy)
    val problem = when (state.problem) {
        TaxiConsentProblem.PRICE_PENDING -> appText("Стоимость подачи ещё неизвестна. Повтори расчёт, когда появится водитель. Заказ не отправляем.",
            "Машина килеү хаҡы әлегә билдәһеҙ. Йөрөтөүсе күренгәс, иҫәпте ҡабатла. Заказды ебәрмәйбеҙ.")
        TaxiConsentProblem.REFRESH -> appText("Подтверждение не принято. Обнови сумму и проверь поездки.", "Раҫлау ҡабул ителмәне. Сумманы яңырт һәм сәфәрҙәрҙе тикшер.")
        TaxiConsentProblem.STORAGE -> appText("Не удалось сохранить состояние. Запрос не отправляем.", "Хәлде һаҡлап булманы. Һорауҙы ебәрмәйбеҙ.")
        TaxiConsentProblem.UNAVAILABLE -> appText("Подтверждение суммы сейчас недоступно.", "Сумманы раҫлау әлегә мөмкин түгел.")
        TaxiConsentProblem.QUOTE -> appText("Не удалось получить сумму. Повтори расчёт.", "Сумманы алып булманы. Иҫәпте ҡабатла.")
        null -> ""
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        val note = when {
            state.unknown -> appText("Результат неизвестен. Повторяется только прежний запрос, с прежними адресом и параметрами.",
                "Һөҙөмтә билдәһеҙ. Тик элекке һорау, элекке адрес һәм параметрҙар менән ҡабатлана.")
            state.blocked -> appText("Результат прошлого подтверждения неизвестен. Не оформляй заново; проверь поездки.",
                "Элекке раҫлауҙың һөҙөмтәһе билдәһеҙ. Яңынан заказ бирмә; сәфәрҙәрҙе тикшер.")
            state.expired -> appText("Срок расчёта истёк. Сначала обнови сумму.", "Иҫәп ваҡыты үтте. Тәүҙә сумманы яңырт.")
            else -> ""
        }
        if (note.isNotEmpty()) Text(note, style = CanonCaption, color = CanonMuted,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (problem.isNotEmpty()) Text(problem, style = CanonCaption, color = CanonRed)
        if (copy != null) Text(if (state.unknown) appText("Прежнее подтверждение: ${copy.total}", "Элекке раҫлау: ${copy.total}")
            else appText("Итого ${copy.total}", "Бөтәһе ${copy.total}"), style = CanonBodyStrong, color = CanonText)
        AppButton(text = when {
            state.sending -> appText("Подтверждаем…", "Раҫлайбыҙ…")
            state.unknown -> appText("Повторить ${copy?.total.orEmpty()}", "${copy?.total.orEmpty()} ҡабатлау")
            state.loading -> appText("Получаем сумму…", "Сумманы алабыҙ…")
            copy != null && !state.expired -> appText("Заказать за ${copy.total}", "${copy.total} — заказ итеү")
            else -> appText("Обновить сумму", "Сумманы яңыртыу")
        }, onClick = { scope.launch { controller.primary(route) } }, loading = state.sending || state.loading,
            enabled = (route != null || state.unknown) && !state.blocked && !state.loading && !state.sending && state.receipt == null)
    }
    if (state.review && quote != null && copy != null) {
        AlertDialog(
            onDismissRequest = { controller.dismissReview() },
            containerColor = CanonSurface,
            title = { Text(when {
                state.unknown -> appText("Повторить подтверждение ${copy.total}", "${copy.total} раҫлауҙы ҡабатлау")
                state.updated -> appText("Сумма обновлена", "Сумма яңыртылды")
                else -> appText("Подтверди итог", "Йомғаҡты раҫла")
            }, style = CanonHeading, color = CanonText) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                if (state.unknown) Text(appText("Результат прежнего запроса неизвестен. Повторяем те же адрес и параметры.",
                    "Элекке һорауҙың һөҙөмтәһе билдәһеҙ. Шул уҡ адресты һәм параметрҙарҙы ҡабатлайбыҙ."), style = CanonBody, color = CanonText)
                Text(appText(copy.ru, copy.ba), style = CanonBody, color = CanonText)
                if (state.expired && !state.unknown) Text(appText("Сначала обновим сумму. Заказ ещё не отправляем.",
                    "Тәүҙә сумманы яңыртабыҙ. Заказды әлегә ебәрмәйбеҙ."), style = CanonCaption, color = CanonMuted)
            } },
            dismissButton = { TextButton(onClick = { controller.dismissReview() }, enabled = !state.sending) {
                Text(appText("Назад", "Артҡа"), style = CanonButton, color = CanonGreen2)
            } },
            confirmButton = { AppButton(text = if (state.expired && !state.unknown) appText("Обновить сумму", "Сумманы яңыртыу")
                else appText("Подтвердить ${copy.total}", "${copy.total} раҫлау"), fillWidth = false,
                loading = state.sending, enabled = !state.sending, onClick = {
                    callbacks.value.second(true)
                    scope.launch { controller.confirm(details, route) }
                }) },
            properties = DialogProperties(dismissOnBackPress = !state.sending, dismissOnClickOutside = !state.sending),
        )
    }
}
