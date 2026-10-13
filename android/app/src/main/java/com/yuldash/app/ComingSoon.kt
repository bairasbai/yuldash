package com.yuldash.app

// «Скоро в Юлдаше» — что видит человек, когда направление ещё не включено (запуск волнами,
// 13.10.2026; флаги — FeatureFlags.kt).
//
// Не ошибка и не пустой экран: спокойная карточка, которая говорит правду («скоро»),
// объясняет, что работает уже сейчас (попутки), и даёт выход — найти попутку. У доставки
// есть вторая дверь: стать курьером заранее, набор открыт и до запуска.
//
// Два вида: встроенный (вкладка режима на главной — шапка уже есть у хаба) и экран (вход из
// профиля, пуша или старой ссылки — со своей шапкой и «назад»).

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.TaxiAvailabilityDto

/** О каком направлении «скоро». Доставка — одна на посылки «по пути» и курьеров. */
internal enum class ComingSoonKind { Delivery, Ads, Business }

/** Название направления — шапка экрана и метка над заголовком. */
@Composable
internal fun comingSoonName(kind: ComingSoonKind): String = when (kind) {
    ComingSoonKind.Delivery -> appText("Доставка", "Илтеү")
    ComingSoonKind.Ads -> appText("Реклама", "Реклама")
    ComingSoonKind.Business -> appText("Скидки по пути", "Юл буйынса ташламалар")
}

@Composable
private fun comingSoonBody(kind: ComingSoonKind): String = when (kind) {
    ComingSoonKind.Delivery -> appText(
        "Посылки и курьеры скоро появятся в Юлдаше. Пока Юлдаш — это попутки: найди машину по пути или предложи свою.",
        "Бандеролдәр һәм курьерҙар тиҙҙән Юлдашта булыр. Әлегә Юлдаш — юлдаш менән сәфәр: юл ыңғайындағы машина тап йәки үҙ сәфәреңде тәҡдим ит.",
    )
    ComingSoonKind.Ads -> appText(
        "Реклама в Юлдаше скоро откроется. Готовим честную маркировку, как требует закон.",
        "Юлдашта реклама тиҙҙән асыла. Закон талап иткәнсә, намыҫлы билдәләмә әҙерләйбеҙ.",
    )
    ComingSoonKind.Business -> appText(
        "«Скидки по пути» скоро откроются: местные кафе, магазины и мастерские со скидками для попутчиков.",
        "«Юл буйынса ташламалар» тиҙҙән асыла: ерле кафелар, кибеттәр һәм оҫтаханалар юлдаштарға ташлама бирәсәк.",
    )
}

@Composable
private fun comingSoonIcon(kind: ComingSoonKind): Painter = when (kind) {
    ComingSoonKind.Delivery -> painterResource(R.drawable.yu_mode_courier)
    ComingSoonKind.Ads -> rememberVectorPainter(Icons.Default.Campaign)
    ComingSoonKind.Business -> rememberVectorPainter(Icons.Default.LocalOffer)
}

/** Подпись второй кнопки доставки — общая для вкладки режима и экрана «Режим курьера». */
@Composable
internal fun becomeCourierEarlyLabel(): String = appText("Стать курьером заранее", "Алдан курьер булыу")

/**
 * Содержимое «Скоро в Юлдаше» без шапки. Прокручивается: при крупном системном шрифте
 * и длинном башкирском тексте кнопки не должны уезжать за край.
 */
@Composable
internal fun ComingSoonContent(
    kind: ComingSoonKind,
    onFindRide: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val accent: Color
    val accentBg: Color
    if (kind == ComingSoonKind.Delivery) {
        accent = CanonCourier
        accentBg = CanonCourierBg
    } else {
        accent = CanonGreen2
        accentBg = CanonMint
    }
    val title = appText("Скоро в Юлдаше", "Тиҙҙән Юлдашта")
    val name = comingSoonName(kind)
    val body = comingSoonBody(kind)
    val findRide = appText("Найти попутку", "Юлдаш табыу")
    val icon = comingSoonIcon(kind)
    Box(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = CanonSpace.lg, vertical = CanonSpace.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Знак направления в мягком круге — тот же язык, что у заглушки «Такси скоро».
            Surface(shape = CircleShape, color = accentBg, modifier = Modifier.appearIn(0)) {
                Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(CanonSpace.xl))
            Column(
                Modifier.appearIn(1),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                Surface(shape = CanonTinyShape, color = accentBg) {
                    Text(
                        name, style = CanonMicro, color = accent,
                        modifier = Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs),
                    )
                }
                Text(
                    title, style = CanonTitle, color = CanonText, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(body, style = CanonBody, color = CanonMuted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(CanonSpace.xxl))
            Column(
                Modifier.fillMaxWidth().appearIn(2),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
            ) {
                AppButton(text = findRide, onClick = onFindRide)
                if (secondaryLabel != null && onSecondary != null) {
                    AppButton(text = secondaryLabel, onClick = onSecondary, style = AppButtonStyle.Secondary)
                }
            }
        }
    }
}

/** «Скоро в Юлдаше» отдельным экраном: своя шапка с названием направления и «назад». */
@Composable
internal fun ComingSoonScreen(
    kind: ComingSoonKind,
    onBack: () -> Unit,
    onFindRide: () -> Unit,
    title: String = comingSoonName(kind),
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(title, onBack) }) { padding ->
        ComingSoonContent(
            kind = kind,
            onFindRide = onFindRide,
            modifier = Modifier.padding(padding),
            secondaryLabel = secondaryLabel,
            onSecondary = onSecondary,
        )
    }
}

/** Что показать на месте закрытого направления: само направление, загрузку или «скоро». */
internal enum class GatePhase { Open, Checking, Soon }

/** Решение гейта. Чистая функция — её проверяют тесты. Загрузка — только пока сервер ни разу
 *  не ответил и первая попытка ещё не кончилась; дальше неизвестное — «скоро», а не вечный скелет. */
internal fun gatePhase(set: FeatureSet, settled: Boolean, open: (FeatureSet) -> Boolean): GatePhase = when {
    open(set) -> GatePhase.Open
    !set.known && !settled -> GatePhase.Checking
    else -> GatePhase.Soon
}

/**
 * Пропускает к направлению, только если сервер его включил. Иначе — «Скоро в Юлдаше».
 * Включили, пока человек смотрит на заглушку, — содержимое плавно приходит на её место.
 *
 * [embedded] — внутри вкладки (без своей шапки), иначе — отдельный экран с «назад».
 */
@Composable
internal fun FeatureGate(
    open: (FeatureSet) -> Boolean,
    kind: ComingSoonKind,
    onBack: () -> Unit,
    onFindRide: () -> Unit,
    embedded: Boolean = false,
    title: String? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val features = rememberFeatures()
    val settled by FeatureFlags.settled.collectAsState()
    val phase = gatePhase(features, settled, open)
    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn(tween(CanonMotion.NORMAL)) togetherWith fadeOut(tween(CanonMotion.QUICK)) },
        label = "featureGate",
    ) { p ->
        when (p) {
            GatePhase.Open -> content()
            GatePhase.Checking -> if (embedded) {
                GateSkeleton(Modifier)
            } else {
                Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(title ?: comingSoonName(kind), onBack) }) { padding ->
                    GateSkeleton(Modifier.padding(padding))
                }
            }
            GatePhase.Soon -> if (embedded) {
                ComingSoonContent(kind, onFindRide, secondaryLabel = secondaryLabel, onSecondary = onSecondary)
            } else {
                ComingSoonScreen(
                    kind = kind, onBack = onBack, onFindRide = onFindRide,
                    title = title ?: comingSoonName(kind),
                    secondaryLabel = secondaryLabel, onSecondary = onSecondary,
                )
            }
        }
    }
}

/**
 * Отдельный экран закрываемого направления в навигации (`YuldashApp`): вход из профиля, пуша
 * или старой ссылки. Закрыто → «Скоро в Юлдаше» со своей шапкой; [title] — имя экрана в шапке.
 */
@Composable
internal fun ScreenGate(
    feature: Feature,
    kind: ComingSoonKind,
    onBack: () -> Unit,
    onFindRide: () -> Unit,
    title: String? = null,
    content: @Composable () -> Unit,
) = FeatureGate(open = { it.isOn(feature) }, kind = kind, onBack = onBack, onFindRide = onFindRide, title = title, content = content)

/** Первый запуск, сервер ещё не ответил: скелет вместо «скоро», чтобы не мигнуть неправдой. */
@Composable
private fun GateSkeleton(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(CanonSpace.lg),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.lg),
    ) {
        SkeletonCard(lines = 2)
        SkeletonCard(lines = 3)
    }
}

/** Такси выключено общим флагом, а точного ответа о доступности нет (гость, сбой сети). */
private val TaxiOffByFlag = TaxiAvailabilityDto(enabled = false, reason = "global_off", messageRu = "", messageBa = "")

/**
 * Запасной ответ о такси для `InstantOrderScreen`, когда `/instant/availability` молчит: у гостя
 * (ручка только для вошедших) и при сбое сети. Флаг выключен или неизвестен → «Такси скоро»
 * с листом ожидания; включён → null, и экран ведёт себя как раньше (сервер всё равно проверит).
 */
@Composable
internal fun taxiOffByFlag(): TaxiAvailabilityDto? = if (rememberFeatures().isOn(Feature.Taxi)) null else TaxiOffByFlag
