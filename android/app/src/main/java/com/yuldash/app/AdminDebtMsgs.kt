package com.yuldash.app

import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * «Долг уже закрыт» в админке долгов за такси — «Заявки на оплату» (`SecondaryScreens.kt`).
 *
 * Сервер отвечает `already: true`, когда долг закрыли другим путём, пока админ смотрел на строку:
 * банк подтвердил оплату картой, долг закрыт кошельком или разбором жалобы (лист 1.2c). Раньше
 * приложение рисовало на это обычное «Оплата подтверждена», и лишний перевод по СБП админ видел
 * только в журнале сервера. Тексты — те же, что в веб-админке (`webapp/src/utils/debtAlready.ts`).
 * BA — черновик, на проверке у Александра (docs/tasks.md, «Переводы на проверку»).
 */
internal data class AdminDebtMsgs(
    val forgiven: String,
    val alreadyConfirm: String,
    val alreadyReject: String,
    val alreadyForgive: String,
    val gotIt: String,
)

internal fun adminDebtMsgs(language: AppLanguage) = AdminDebtMsgs(
    forgiven = appTextFor(language, "Долг списан", "Бурыс һүндерелде"),
    alreadyConfirm = appTextFor(
        language,
        "Долг уже закрыт — подтверждать нечего. Если водитель ещё и перевёл по СБП, верни ему этот перевод.",
        "Бурыс инде ябылған — раҫларға бер нәмә лә юҡ. Йөрөтөүсе СБП аша ла күсергән булһа, был күсереүҙе уға кире ҡайтар.",
    ),
    alreadyReject = appTextFor(language, "Долг уже закрыт — отклонять нечего.", "Бурыс инде ябылған — кире ҡағырға бер нәмә лә юҡ."),
    alreadyForgive = appTextFor(language, "Долг уже закрыт — списывать нечего.", "Бурыс инде ябылған — һүндерергә бер нәмә лә юҡ."),
    gotIt = appTextFor(language, "Понятно", "Аңлашылды"),
)

/**
 * Итог нажатия по долгу. Обычный — тостом, как остальные действия экрана. «Уже оплачено» — окном
 * до «Понятно»: на Android 12+ тост режется до двух строк, и главное («верни лишний перевод по
 * СБП») не влезало бы, а пропустить его — значит оставить у платформы чужие деньги.
 */
@Stable
internal class AdminDebtNotice(val msgs: AdminDebtMsgs) {
    /** Текст открытого окна «уже оплачено»; `null` — окна нет. */
    var text by mutableStateOf<String?>(null)
        private set

    fun after(ctx: Context, already: Boolean, alreadyMsg: String, doneMsg: String) {
        if (already) text = alreadyMsg else Toast.makeText(ctx, doneMsg, Toast.LENGTH_SHORT).show()
    }

    fun dismiss() {
        text = null
    }
}

@Composable
internal fun rememberAdminDebtNotice(): AdminDebtNotice {
    val language = LocalAppLanguage.current
    return remember(language) { AdminDebtNotice(adminDebtMsgs(language)) }
}

@Composable
internal fun AdminDebtAlreadyDialog(notice: AdminDebtNotice) {
    val text = notice.text ?: return
    AlertDialog(
        onDismissRequest = notice::dismiss,
        text = { Text(text, color = CanonText, fontSize = 16.sp, lineHeight = 23.sp) },
        confirmButton = {
            TextButton(onClick = notice::dismiss) { Text(notice.msgs.gotIt, fontWeight = FontWeight.Bold) }
        },
    )
}
