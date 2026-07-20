// Корневой build-файл проба-проекта Compose Multiplatform.
// Версии зафиксированы под известные-совместимые: те же AGP/Kotlin, что уже стоят в android/,
// плюс сам Compose Multiplatform 1.11.0 (стабильный iOS-релиз, май 2026).
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.compose") version "1.11.0" apply false
}
