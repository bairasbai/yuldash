package com.yuldash.app

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Дизайн-токены: адаптивная палитра Canon* (светлый/тёмный) + формы карточек.
// Вынесено из MainActivity.kt (Фаза 0 разрезки). Тот же пакет com.yuldash.app → импортов не нужно.
// Один и тот же CanonX отдаёт цвет по теме — меняется только определение, использования не трогаем.

/** Тема приложения: null = как в системе, true = тёмная, false = светлая (тумблер день/ночь в шапке). */
internal object ThemePrefs {
    var darkOverride by mutableStateOf<Boolean?>(null)
}

/**
 * Ночная поездка (волна 160): на время поездки экран и карта приглушаются сами, даже если
 * в телефоне светлая тема. Такси нужнее всего ночью, и белый экран в лицо в половине первого —
 * это то, за что Яндексу и достаётся.
 *
 * Слой, а не подмена [ThemePrefs]: светлую тему человек мог выбрать осознанно, и возвращать
 * её потом пришлось бы вручную — а если приложение убьют посреди поездки, она осталась бы
 * перекрученной навсегда. Здесь же значение живёт ровно столько, сколько открыт экран.
 *
 * null = слоя нет, решает обычная тема.
 */
internal val LocalNightRide = compositionLocalOf<Boolean?> { null }

@Composable
internal fun appIsDark(): Boolean =
    LocalNightRide.current ?: ThemePrefs.darkOverride ?: isSystemInDarkTheme()

/**
 * Крупный шрифт — глобальный тумблер размера ВСЕГО текста (для пожилых и слабовидящих).
 * [multiplier] умножается на системный fontScale в корне композиции (см. MainActivity.setContent),
 * поэтому масштабируется весь sp-текст разом, без правки экранов, и системная настройка тоже уважается.
 */
internal enum class FontScaleOption(val multiplier: Float) {
    Normal(1.0f),
    Large(1.15f),
    ExtraLarge(1.30f),
}

/** Единая точка правды выбранного размера текста. Читается в корне (Density) и в настройках/простом режиме. */
internal object FontScalePrefs {
    private const val PREFS = "yuldash_prefs"
    private const val KEY = "font_scale"

    var option by mutableStateOf(FontScaleOption.Normal)
        private set

    /** Восстановить сохранённый выбор при старте (зовётся из MainActivity.onCreate). */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        option = runCatching { FontScaleOption.valueOf(saved) }.getOrDefault(FontScaleOption.Normal)
    }

    /** Сменить размер: применяется сразу (state вверху) и сохраняется на диск. */
    fun set(context: Context, value: FontScaleOption) {
        option = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
    }
}

internal val CanonGreen: Color @Composable get() = if (appIsDark()) Color(0xFF7FE3AB) else Color(0xFF073F25)
internal val CanonGreen2: Color @Composable get() = if (appIsDark()) Color(0xFF27A463) else Color(0xFF0B6B3A)  // тёмный затемнён под WCAG (белый текст на кнопке ≥3:1)
// Фиксированные тёмно-зелёные (НЕ адаптивные) — для шапок-градиентов с белым текстом.
// CanonGreen/CanonGreen2 в тёмной теме инвертируются в светлую мяту → белый текст на них нечитаем.
internal val CanonGreenInk: Color = Color(0xFF0B6B3A)      // верх градиента шапки
internal val CanonGreenInkDark: Color = Color(0xFF073F25)  // низ градиента шапки
internal val CanonMint: Color @Composable get() = if (appIsDark()) Color(0xFF0F2419) else Color(0xFFE7F5EC)  // тёмная мята темнее под контраст зелёного текста
internal val CanonYellow: Color @Composable get() = if (appIsDark()) Color(0xFF4A3A14) else Color(0xFFFFE3A1)
// Брендовое золото (как дорога на карте/лого) — заливка акцентной кнопки. Золотое в обеих темах → текст фиксированно тёмный.
internal val CanonGold: Color @Composable get() = if (appIsDark()) Color(0xFFE8C36B) else Color(0xFFF5B301)
// Фирменный компас остаётся одинаковым в обеих темах: он всегда лежит на собственной
// светлой платформе и не должен менять узнаваемые цвета при переключении темы.
internal val CanonCompassSurface: Color = Color(0xFFFAFAF6)
internal val CanonCompassGold: Color = Color(0xFFF5B301)
internal val CanonCompassGoldSoft: Color = Color(0xFFFFE3A1)
// ТОЛЬКО поверх CanonGold. Цвет плоский (одинаков в обеих темах): на тёмной поверхности
// контраст падает до 1.3 — текст исчезает. Ровно на этом уже обожглись с CanonTaxiInk.
internal val CanonGoldInk: Color = Color(0xFF0B3D20)
internal val CanonBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F1613) else Color(0xFFFAFAF6)
internal val CanonText: Color @Composable get() = if (appIsDark()) Color(0xFFEAF2EC) else Color(0xFF0B1F14)
internal val CanonMuted: Color @Composable get() = if (appIsDark()) Color(0xFF9BA49D) else Color(0xFF686F66)
// Более контрастный вариант приглушённого текста (неактивные подписи нав-меню и т.п.): темнее/светлее CanonMuted, но не активный цвет. Контраст с фоном ≥4.5:1.
internal val CanonMutedStrong: Color @Composable get() = if (appIsDark()) Color(0xFFC2CBC3) else Color(0xFF4C534B)
internal val CanonBorder: Color @Composable get() = if (appIsDark()) Color(0x24FFFFFF) else Color(0x1F000000)
internal val CanonRed: Color @Composable get() = if (appIsDark()) Color(0xFFF25A4D) else Color(0xFFCC2A20)  // WCAG: белый на красной кнопке ≥3:1, красный текст на фоне/danger-bg ≥4.5:1
// Поверхность карточек: была хардкод Color.White — теперь адаптивная.
internal val CanonSurface: Color @Composable get() = if (appIsDark()) Color(0xFF192420) else Color(0xFFFFFFFF)
// Подложка опасности/ошибки (SOS, ошибки) — адаптивная (светло-розовая / тёмно-красная).
internal val CanonDangerBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A1B18) else Color(0xFFFDECEA)
// Предупреждение/в процессе (pending, черновик): подложка + текст — адаптивные.
internal val CanonWarnBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A2E12) else Color(0xFFFFF2D6)
internal val CanonWarn: Color @Composable get() = if (appIsDark()) Color(0xFFE8B86A) else Color(0xFF9A6200)  // светлый затемнён под WCAG (текст на жёлтом фоне ≥4.5:1)
// F9 «Женщинам — водитель-женщина»: деликатный сигнал «женщина за рулём» (opt-in).
// Мягкий сливово-розовый, отличается от зелёного «проверен»/«на линии», но остаётся спокойным.
internal val CanonWomanBg: Color @Composable get() = if (appIsDark()) Color(0xFF2E1A27) else Color(0xFFF7E9F1)
internal val CanonWoman: Color @Composable get() = if (appIsDark()) Color(0xFFE39BC4) else Color(0xFF8E3B6B)  // текст/иконка на CanonWomanBg ≥4.5:1
// Золото рейтинга (звёзды). Адаптивное: яркое золото на белой карточке давало контраст 2.08 —
// ниже порога 3:1 для значащей графики (в выборе оценки звёзды И ЕСТЬ информация, а не украшение).
// В светлой теме глубокая охра (3.43), в тёмной прежнее яркое золото (7.68). Проверяется
// расчётом: tools/contrast.py. Все 19 использований — в @Composable (tint/color), Canvas нет.
internal val CanonStar: Color @Composable get() = if (appIsDark()) Color(0xFFE7A921) else Color(0xFFBE7D00)
// Режимы поездки (переключатель пассажира): Такси = ЖЁЛТЫЙ (привычный цвет такси), Попутка = ЗЕЛЁНЫЙ (бренд «свои»).
// Акцент окрашивает активный сегмент, кнопку действия и индикатор-полоску. Оба варианта — светлый/тёмный.
internal val CanonTaxi: Color @Composable get() = if (appIsDark()) Color(0xFFF2C14E) else Color(0xFFE8A200)       // акцент такси (кнопка/полоска/иконка)
internal val CanonTaxiBg: Color @Composable get() = if (appIsDark()) Color(0xFF3A2E12) else Color(0xFFFFEFC2)      // мягкая подложка активного сегмента «Такси»
internal val CanonTaxiInk: Color = Color(0xFF3A2A00)                                                               // тёмный текст НА ЖЁЛТОМ (CanonTaxi жёлтый в обеих темах — ink подходит всегда)
// Текст на ПОДЛОЖКЕ такси (CanonTaxiBg), а это разные вещи: подложка в тёмной теме
// тёмно-коричневая, и тёмный CanonTaxiInk на ней давал контраст 1.05 — то есть госномер
// машины в бейдже был не виден вообще. А смысл бейджа именно в том, чтобы пассажир сверил
// номер и не сел в чужую машину. Светлая тема 12.16, тёмная 10.61 (tools/contrast.py).
internal val CanonTaxiText: Color @Composable get() = if (appIsDark()) Color(0xFFFFE3A1) else Color(0xFF3A2A00)
internal val CanonPooling: Color @Composable get() = if (appIsDark()) Color(0xFF27A463) else Color(0xFF0B6B3A)     // акцент попутки (бренд-зелёный)
internal val CanonPoolingBg: Color @Composable get() = if (appIsDark()) Color(0xFF0F2419) else Color(0xFFE7F5EC)   // мягкая подложка активного сегмента «Попутка»
// Курьер = СИНИЙ. Третий режим хаба: зелёный (попутка) и жёлтый (такси) уже заняты, а синий —
// привычный «логистический» цвет и хорошо различим с ними даже при цветовой слепоте
// (зелёный/жёлтый путаются между собой чаще, чем с синим).
internal val CanonCourier: Color @Composable get() = if (appIsDark()) Color(0xFF6FB4F0) else Color(0xFF1B5E9E)     // акцент курьера (текст на CanonCourierBg ≥4.5:1)
internal val CanonCourierBg: Color @Composable get() = if (appIsDark()) Color(0xFF12283A) else Color(0xFFE4F0FA)   // мягкая подложка активного сегмента «Курьер»
// Тонкая зелёная разделительная линия (border карточек) — была хардкод 0x1A0B6B3A в нескольких экранах.
// Теперь адаптивна: тёмный зелёный почти невидим на тёмном фоне → в тёмной теме светлая мятная линия.
internal val CanonHairlineGreen: Color @Composable get() = if (appIsDark()) Color(0x2E7FE3AB) else Color(0x1A0B6B3A)
// Тонкая красная рамка danger-карточек (SOS) — была хардкод 0x33D93025 в SafetyScreen.
// Адаптивна: в тёмной теме чуть светлее и заметнее на тёмном danger-bg.
internal val CanonDangerBorder: Color @Composable get() = if (appIsDark()) Color(0x55F25A4D) else Color(0x33D93025)
// Текст и иконки ПОВЕРХ фиксированно-тёмных акцентных подложек (CanonGreenInk/CanonGreenInkDark,
// CanonRed, тёмные градиенты шапок). Раньше в таких местах писали голый Color.White — 15+ раз
// по экранам такси и кошелька. Токен плоский (одинаков в обеих темах) сознательно: подложка
// под ним тоже не меняется по теме, и «адаптивный» цвет тут сделал бы текст невидимым —
// ровно та ошибка, на которой уже обожглись с CanonTaxiInk (см. комментарий выше).
internal val CanonOnAccent: Color = Color(0xFFFFFFFF)

/**
 * Читаемая НАДПИСЬ на тонированном чипе того же акцента — `Surface(color = accent.copy(alpha = .14f))`.
 *
 * Приём «чип цвета акцента + подпись тем же акцентом» выглядит аккуратно и честно работает для
 * зелёного (10:1) и синего (5.4:1). Но для жёлтых он разваливается: в светлой теме подпись
 * «Такси» на своей же жёлтой подложке давала **1.97:1**, а золотой ярлык у рекламы — **1.63:1**.
 * Это не «бледновато», это нечитаемо: буквы того же тона, что и фон под ними.
 *
 * Родня уже известной ошибки с `CanonTaxiInk` (см. выше): жёлтый цвет нельзя брать и как заливку,
 * и как текст поверх этой заливки. Для жёлтых берём тёмно-коричневый `CanonTaxiText` — 12:1
 * в светлой, 9+ в тёмной. Остальные акценты возвращаем как есть, они читаются.
 *
 * Держит `ContrastGuardTest`.
 */
@Composable
internal fun canonChipInk(accent: Color): Color =
    if (accent == CanonTaxi || accent == CanonGold || accent == CanonStar) CanonTaxiText else accent

/**
 * Надпись и иконка на ЗАЛИВНОЙ кнопке — той, чья подложка сама меняется по теме
 * (`CanonGreen2` у главной кнопки, `CanonRed` у опасной).
 *
 * Почему не белый. В светлой теме зелёный тёмный, и белым по нему — 6.6:1, хорошо. Но в тёмной
 * теме зелёный СВЕТЛЕЕТ (иначе кнопка сливается с тёмным фоном), и белая надпись на нём даёт
 * **3.19:1**, а красная кнопка — **3.31:1**. Норма для надписи 16sp — 4.5:1. То есть «Заказать»
 * и «Отменить поездку» ночью читались хуже нормы на КАЖДОМ экране приложения.
 *
 * Отличие от [CanonOnAccent]. Тот — для подложек, которые тёмные ВСЕГДА (градиент шапки), и там
 * адаптивный цвет сделал бы текст невидимым. Здесь наоборот: подложка адаптивная, значит и
 * чернила обязаны быть адаптивными. Берём цвет фона страницы — почти белый в светлой теме,
 * почти чёрный в тёмной: 6.31 / 5.75 на зелёной кнопке и 5.12 / 5.54 на красной.
 *
 * Тот же приём уже применён вручную на зелёной карточке «Быстрый заказ» — теперь он общий.
 * Держит `ContrastGuardTest`.
 */
internal val CanonOnFilled: Color @Composable get() = CanonBg
// Затемнение под полноэкранным просмотром фото. Плоское и в обеих темах одинаковое: подложка
// нужна, чтобы снимок читался, а не чтобы следовать теме — на светлом фоне фото «поплывёт».
internal val CanonScrim: Color = Color(0xE6000000)
// Кнопки ПОВЕРХ фотографии (закрыть, стрелки листания в просмотрщике улик). «Стекло»: тёмный
// кружок под иконкой и приглушённый белый для недоступной стрелки. Тоже плоские — они лежат
// на снимке, а не на поверхности приложения, и следовать теме им не за чем.
internal val CanonGlassDark: Color = Color(0x66000000)
internal val CanonGlassOnPhoto: Color = Color(0x66FFFFFF)
// Цвета линий на карте Яндекса. MapKit принимает ARGB-число, а не Compose-цвет, поэтому это
// не `Color`, а Int — но объявлены здесь, чтобы маршрут не красили числом прямо в экране:
// зелёный основной путь и серый объездной должны совпадать во всех местах, где рисуется карта.
internal const val CANON_ROUTE_MAIN_ARGB: Int = 0xCC0B6B3A.toInt()
internal const val CANON_ROUTE_ALT_ARGB: Int = 0x55757575.toInt()
// Лестница скруглений. Замер 2026-08-04: по экранам жило 18 разных радиусов (2,4,6,8,9,10,12,13,
// 14,15,16,17,18,20,22,24,26 dp) — глаз читает такой разнобой как несобранность, даже когда
// не может назвать причину. Четыре ступени: чем крупнее элемент, тем мягче угол.
internal val CanonCardShape = RoundedCornerShape(28.dp)   // крупная карточка, шторка
internal val CanonItemShape = RoundedCornerShape(22.dp)   // элемент списка, кнопка во всю ширину
internal val CanonFieldShape = RoundedCornerShape(14.dp)  // поле ввода, чип, плашка
internal val CanonTinyShape = RoundedCornerShape(8.dp)    // бейдж, счётчик, мелкая метка


// ============================== ШКАЛЫ: отступы, текст, глубина, движение ==============================
// Заведены 2026-08-03 после замера: в экранах жило 88 разных значений отступов (из них 18 не
// кратны четырём) и 43 разных кегля. Каждый экран изобретал своё — поэтому приложение и не
// читалось «дорого»: дороговизна в спокойном стиле держится на повторяемости, а не на украшениях.
//
// Правило: в экранах брать отсюда. Нужного значения нет — добавить ступень ЗДЕСЬ, а не написать
// число на месте. Одно исключение — оптические подгонки в 1–2dp внутри одного компонента
// (выравнивание иконки к тексту), их в шкалу не тащим.

/** Отступы. Сетка 4pt: любое значение кратно четырём, ступеней семь — этого хватает на всё. */
internal object CanonSpace {
    val xs = 4.dp      // иконка ↔ подпись, зазор внутри чипа
    val sm = 8.dp      // строки внутри одного блока
    val md = 12.dp     // внутренние поля карточки
    val lg = 16.dp     // поля экрана, зазор между карточками
    val xl = 24.dp     // между смысловыми блоками
    val xxl = 32.dp    // отбивка крупного заголовка
    val huge = 48.dp   // воздух в пустых состояниях
}

/** Глубина. Тень вместо рамки: рамка читается дёшево, мягкая тень — дорого.
 *  Три уровня, больше не нужно: лист лежит на карточке, карточка на фоне. */
internal object CanonDepth {
    val flat = 0.dp        // элемент лежит в потоке
    // 1dp — правило дома (скилл yuldash-ui, «тень 1dp, больше не поднимать») и значение по
    // умолчанию у AppCard. Спокойная премиальность не любит тяжёлых теней: глубина читается
    // мягким краем, а не тёмной подушкой под карточкой.
    val card = 1.dp        // карточка над фоном
    // Исключения — только для того, что реально ПЛАВАЕТ над содержимым и обязано от него
    // отделиться: кнопки поверх карты и шторка снизу. В обычной вёрстке их не использовать.
    val raised = 4.dp      // кнопка/подсказка поверх карты
    val sheet = 12.dp      // шторка снизу, диалог
}

/** Движение. Три длительности на всё приложение — иначе анимации живут вразнобой
 *  и это читается как несобранность. Короче 150мс глаз не замечает, длиннее 350мс раздражает. */
internal object CanonMotion {
    /**
     * Человек мог отключить анимации в системе — и это не каприз.
     *
     * Вестибулярные нарушения, мигрень, укачивание: для таких людей движущийся интерфейс
     * вызывает настоящее физическое недомогание, и Android даёт им общий выключатель
     * (Настройки → Специальные возможности → Удалить анимации). Приложение обязано его
     * уважать — так же, как уважает системный размер шрифта.
     *
     * Учёт этого выключателя в Юлдаше был ровно на ОДНОМ экране (заставка), при 506 местах
     * с анимацией. То есть человек, которого укачивает, видел спокойное приветствие — и
     * дальше всё приложение в движении.
     *
     * Чинится одним местом: все длительности берутся отсюда (это держит `CanonSourceGuardTest`),
     * поэтому достаточно обнулить их при выключенных анимациях. Ноль означает «сразу конечное
     * состояние»: экраны и карточки не перестают появляться, они появляются мгновенно.
     *
     * Значение выставляет `MainActivity` при старте — читать системную настройку на каждый
     * кадр незачем, а меняют её редко и с перезапуском приложения.
     */
    @Volatile
    @JvmField
    var enabled: Boolean = true

    private const val QUICK_MS = 180      // нажатие, смена иконки, мелкий чип
    private const val NORMAL_MS = 260     // появление карточки, переход вкладки
    private const val SLOW_MS = 320       // шторка снизу, крупный экран

    val QUICK: Int get() = if (enabled) QUICK_MS else 0
    val NORMAL: Int get() = if (enabled) NORMAL_MS else 0
    val SLOW: Int get() = if (enabled) SLOW_MS else 0
}

// ------------------------------ типографика ------------------------------
// Замер показал главную беду: 1022 текста из 1074 были жирными (Black 516 + Bold 506) против
// 52 нежирных. Когда выделено ВСЁ — не выделено ничто, и глаз читает это как крик.
// Здесь наоборот: основной текст обычного веса, жирность — редкий инструмент.
// Межстрочный ~1.3 у крупного и ~1.45 у мелкого: мелкому нужно больше воздуха, чтобы не слипаться.

/** Крупное число: цена, заработок, сумма. Единственное место, где уместен большой жирный шрифт. */
internal val CanonDisplay = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

/** Заголовок экрана. */
internal val CanonTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)

/** Заголовок блока внутри экрана. SemiBold, а не Bold: блоков много, крик не нужен. */
internal val CanonHeading = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)

/** ОСНОВНОЙ текст. Normal — это и есть главное изменение против «всё жирное». 16sp, а не 13sp:
 *  тринадцать было самым частым кеглем и это мелко, у Apple и Тинькофф основной 15–17. */
internal val CanonBody = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal)

/** Акцент внутри основного текста: имя, город, цена в строке. Тот же кегль, другой вес. */
internal val CanonBodyStrong = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)

/** Вторичный текст: пояснения, подзаголовки. */
internal val CanonCaption = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)

/** Микротекст: подписи под иконками, метки, время. Medium — чтобы мелкое не «плыло». */
internal val CanonMicro = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)

/** Надпись на кнопке. Единственный жирный из мелких: кнопка обязана читаться первой. */
internal val CanonButton = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
