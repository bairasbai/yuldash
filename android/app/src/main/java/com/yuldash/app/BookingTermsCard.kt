package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SafetyPolicyDto
import com.yuldash.app.data.SessionKeys
import com.yuldash.app.data.parseJwtAuthSessionId

@Composable
internal fun BookingTermsCard(hasDriverPayMethods: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf<SafetyPolicyDto?>(null) }
    var failed by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf(0) }
    LaunchedEffect(request) {
        failed = false
        val result = ApiClient.getSafetyPolicy(authenticated = false)
        policy = result.getOrNull()
        failed = result.isFailure
    }

    AppCard {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Как платить", "Нисек түләргә"), style = CanonBodyStrong, color = CanonText)
            Text(
                if (hasDriverPayMethods) appText("Оплата напрямую водителю.", "Түләү туранан-тура йөрөтөүсеһенә.")
                else appText(
                    "Договорись с водителем о наличных или переводе. Оплата напрямую водителю.",
                    "Йөрөтөүсе менән ҡулаҡса йәки күсереү тураһында килеш. Түләү туранан-тура йөрөтөүсеһенә.",
                ),
                style = CanonBody, color = CanonText,
            )
            Text(appText("Если планы поменяются", "Пландар үҙгәрһә"), style = CanonBodyStrong, color = CanonText)
            Text(
                appText(
                    "До посадки бронь можно отменить бесплатно. Поздняя отмена влияет на показатель «Надёжность» в профиле.",
                    "Ултырғанға тиклем бронде бушлай кире алырға була. Һуң кире алыу профилдәге «Ышаныслылыҡ» күрһәткесенә тәьҫир итә.",
                ),
                style = CanonBody, color = CanonText,
            )
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) appText("Свернуть", "Йыйырға")
                    else appText("Подробнее об отмене", "Кире алыу тураһында тулыраҡ"),
                    style = CanonBodyStrong, color = CanonGreen2,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(CanonMotion.NORMAL)) + fadeIn(tween(CanonMotion.QUICK)),
                exit = shrinkVertically(tween(CanonMotion.NORMAL)) + fadeOut(tween(CanonMotion.QUICK)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                    BookingCancellationTerms(policy?.lateCancelBeforeMin, policy?.noShowWaitMin)
                    if (failed) {
                        TextButton(onClick = { request++ }) {
                            Text(appText("Обновить сроки", "Ваҡыттарҙы яңыртырға"), style = CanonBodyStrong, color = CanonGreen2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookingCancellationTerms(late: Int?, noShow: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
        Text(
            if (late == null) appText(
                "Поздней может считаться отмена незадолго до выезда. Отмена после отметки водителя «Выехал» или «Подъезжаю» тоже считается поздней.",
                "Сығыу алдынан кире алыу һуң тип һаналырға мөмкин. Йөрөтөүсе «Юлға сыҡтым» йәки «Яҡынлашам» тип билдәләгәндән һуң кире алыу ҙа һуң тип һанала.",
            ) else appText(
                "Отмена считается поздней за $late мин до выезда и позже, а также после отметки водителя «Выехал» или «Подъезжаю».",
                "Сығыуға $late минут йәки унан әҙерәк ҡалһа, йә йөрөтөүсе «Юлға сыҡтым» йәки «Яҡынлашам» тип билдәләһә, кире алыу һуң тип һанала.",
            ),
            style = CanonBody, color = CanonText,
        )
        Text(
            appText(
                "При посадке вне начала маршрута водитель сначала отмечает «Подъезжаю».",
                "Маршрут башында ултырмаһаң, йөрөтөүсе башта «Яҡынлашам» тип билдәләй.",
            ),
            style = CanonBody, color = CanonText,
        )
        Text(
            if (noShow == null) appText(
                "Для неявки нужно подождать с учётом выезда, подъезда и выбранной остановки.",
                "Килмәүҙе билдәләр өсөн сығыу, яҡынлашыу һәм һайланған туҡталышты иҫәпкә алып көтөргә кәрәк.",
            ) else appText(
                "Неявку можно отметить не раньше чем через $noShow мин после последнего из событий: выезда, отметки «Подъезжаю» или расчётного прибытия на остановку.",
                "Килмәүҙе һуңғы ваҡиғанан $noShow минут үткәндән һуң ғына билдәләргә була: юлға сығыу, «Яҡынлашам» билдәһе йәки туҡталышҡа иҫәпләнгән килеп етеү.",
            ),
            style = CanonBody, color = CanonText,
        )
        Text(
            appText(
                "Неявка влияет на «Надёжность» после подтверждения по итогам разбора. Если возникла проблема, сообщи о ней в приложении.",
                "Килмәү тикшереү һөҙөмтәһендә раҫланһа, «Ышаныслылыҡ»ҡа тәьҫир итә. Проблема килеп сыҡһа, уны ҡушымтала хәбәр ит.",
            ),
            style = CanonBody, color = CanonText,
        )
    }
}

@Composable
internal fun BookingScamTip(guest: Boolean) {
    val context = LocalContext.current
    val generation by ApiClient.sessionChanges.collectAsState()
    val owner = if (guest) null else ApiClient.currentToken()?.let(::parseJwtAuthSessionId)
    val prefs = remember(context) { context.getSharedPreferences(SessionKeys.MAIN_PREFS, Context.MODE_PRIVATE) }
    var seen by rememberSaveable(generation, owner) {
        mutableStateOf(owner != null && prefs.getString(SessionKeys.BOOKING_SCAM_TIP_OWNER, null) == owner)
    }
    AnimatedVisibility(!seen) {
        AppCard {
            Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Text(appText("Береги себя и деньги", "Үҙеңде һәм аҡсаңды һаҡла"), style = CanonBodyStrong, color = CanonText)
                Text(appText(
                    "Не отправляй предоплату на чужой номер. Никому не сообщай коды из СМС.",
                    "Сит номерға алдан түләмә. СМС кодтарын бер кемгә лә әйтмә.",
                ), style = CanonBody, color = CanonText)
                AppButton(appText("Понятно", "Аңлашылды"), onClick = {
                    if (owner == null) seen = true
                    else ApiClient.runIfCurrentSession(generation) {
                        if (ApiClient.currentToken()?.let(::parseJwtAuthSessionId) == owner) {
                            prefs.edit().putString(SessionKeys.BOOKING_SCAM_TIP_OWNER, owner).apply()
                            seen = true
                        }
                    }
                }, style = AppButtonStyle.Secondary)
            }
        }
    }
}
