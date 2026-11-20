package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SafetyPolicyDto

@Composable
internal fun BookingTermsCard() {
    var expanded by remember { mutableStateOf(false) }
    var policy by remember { mutableStateOf<SafetyPolicyDto?>(null) }
    LaunchedEffect(Unit) {
        policy = ApiClient.getSafetyPolicy(authenticated = false).getOrNull()
    }

    AppCard {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Как платить", "Нисек түләргә"), style = CanonBodyStrong, color = CanonText)
            Text(
                appText(
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
                BookingCancellationTerms(policy?.lateCancelBeforeMin, policy?.noShowWaitMin)
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
                "Неявка влияет на «Надёжность» после подтверждения по итогам разбора.",
                "Килмәү тикшереү һөҙөмтәһендә раҫланһа, «Ышаныслылыҡ»ҡа тәьҫир итә.",
            ),
            style = CanonBody, color = CanonText,
        )
    }
}
