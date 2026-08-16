package com.yuldash.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

// ============================ Разрешения по-человечески (аудит такси, P0-3) ============================
// До этого по всему проекту был только голый launcher.launch(...). После двух отказов Android
// отвечает «нет» мгновенно и молча: системный диалог больше не появляется, человек видит
// короткий Toast — и упирается в тупик, потому что включить разрешение из приложения уже нельзя,
// а куда идти руками — никто не сказал.
//
// Здесь единый путь для всех экранов:
//   1. объясняем ЗАЧЕМ — до системного диалога, чтобы человек понимал, что подтверждает;
//   2. просим систему;
//   3. система больше не спрашивает → «Открыть настройки» и переход прямо в карточку приложения.
// Все надписи — на двух языках (CLAUDE.md §3).

/** Что показываем прямо сейчас: ничего, объяснение перед запросом или тупик «иди в настройки». */
private enum class PermissionStep { Idle, Explain, Blocked }

/**
 * Общий гейт разрешения. Возвращает функцию «попросить»: зови её из onClick.
 * Диалоги функция рисует сама — AlertDialog живёт в отдельном окне, поэтому место вызова
 * в разметке значения не имеет (можно звать рядом с остальными `remember`-ами вверху экрана).
 *
 * @param permission строка `Manifest.permission.*`
 * @param titleRu заголовок объяснения (ru), @param titleBa он же на башкирском
 * @param whyRu    зачем нужно разрешение (ru), @param whyBa то же на башкирском
 * @param onGranted вызывается, когда разрешение уже есть или его только что дали
 * @param onDenied  вызывается на отказ — экран сам решает, что показать вместо результата
 */
@Composable
internal fun rememberPermissionGate(
    permission: String,
    titleRu: String,
    titleBa: String,
    whyRu: String,
    whyBa: String,
    onGranted: () -> Unit = {},
    onDenied: () -> Unit = {},
): () -> Unit {
    val ctx = LocalContext.current
    val activity = ctx.findActivityCompat()
    var step by remember { mutableStateOf(PermissionStep.Idle) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            step = PermissionStep.Idle
            onGranted()
        } else {
            // shouldShowRequestPermissionRationale после ПЕРВОГО отказа = true.
            // false здесь означает «система больше не спросит» — остаётся только вручную
            // в настройках приложения, и об этом надо сказать вслух, а не молчать.
            val canAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            step = if (canAskAgain) PermissionStep.Idle else PermissionStep.Blocked
            onDenied()
        }
    }

    val title = appText(titleRu, titleBa)
    val why = appText(whyRu, whyBa)
    val blockedText = why + appText(
        "\n\nAndroid больше не спрашивает — включи разрешение в настройках приложения.",
        "\n\nAndroid башҡа һорамай — рөхсәтте ҡушымта көйләүҙәрендә ҡабыҙ.",
    )
    when (step) {
        PermissionStep.Explain -> AlertDialog(
            onDismissRequest = { step = PermissionStep.Idle },
            containerColor = CanonSurface,
            title = { Text(title, color = CanonText, fontWeight = FontWeight.Bold) },
            text = { Text(why, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    step = PermissionStep.Idle
                    launcher.launch(permission)
                }) { Text(appText("Разрешить", "Рөхсәт итеү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { step = PermissionStep.Idle; onDenied() }) {
                    Text(appText("Не сейчас", "Хәҙер түгел"), color = CanonMuted)
                }
            },
        )
        PermissionStep.Blocked -> AlertDialog(
            onDismissRequest = { step = PermissionStep.Idle },
            containerColor = CanonSurface,
            title = { Text(title, color = CanonText, fontWeight = FontWeight.Bold) },
            text = { Text(blockedText, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = {
                    step = PermissionStep.Idle
                    openAppSettings(ctx)
                }) { Text(appText("Открыть настройки", "Көйләүҙәрҙе асыу"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { step = PermissionStep.Idle }) {
                    Text(appText("Закрыть", "Ябыу"), color = CanonMuted)
                }
            },
        )
        PermissionStep.Idle -> Unit
    }

    return {
        if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED) onGranted()
        else step = PermissionStep.Explain
    }
}

/** Карточка приложения в системных настройках: единственный путь, когда система уже не спрашивает. */
internal fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Экран уведомлений приложения (Android 8+); ниже — общая карточка приложения. */
internal fun openNotificationSettings(ctx: Context) {
    val ok = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!ok) openAppSettings(ctx)
}

/**
 * Настройка «показывать поверх всего» для входящего заказа (Android 14+). Ниже 14 такого
 * экрана нет — уводим в карточку приложения, чтобы кнопка никогда не вела в пустоту.
 */
internal fun openFullScreenIntentSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { openAppSettings(ctx); return }
    val ok = runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!ok) openAppSettings(ctx)
}

/**
 * Дойдёт ли уведомление вообще. Одна проверка на ВСЕ выключатели, которых три:
 * отказ в POST_NOTIFICATIONS (Android 13+), выключенные уведомления приложения в системных
 * настройках и наш собственный тумблер «Уведомления» в настройках Юлдаша.
 *
 * Третий забыли (аудит 2026-08-08, волна 117), и вышло вот что: водитель выключил тумблер,
 * а экран линии всё равно писал зелёным «Ты на линии — заказы придут сюда, экран можно
 * погасить». Заказы приходили и молча выбрасывались. Человек стоял вечер на трассе впустую
 * и уходил из приложения, решив, что заказов в районе просто нет.
 */
internal fun notificationsAllowed(ctx: Context): Boolean =
    AppPrefs.notifications(ctx) &&
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(true)

/** Выключен ли ИМЕННО наш тумблер (системное разрешение при этом на месте). */
internal fun notificationsOffInApp(ctx: Context): Boolean =
    !AppPrefs.notifications(ctx) &&
        runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(true)

/**
 * Можно ли поднять карточку заказа поверх погасшего экрана. С Android 14 это отдельное
 * разрешение, и по умолчанию оно есть только у звонилок и будильников: без проверки мы бы
 * обещали водителю то, чего не будет.
 */
internal fun fullScreenOfferAllowed(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(false)
}
