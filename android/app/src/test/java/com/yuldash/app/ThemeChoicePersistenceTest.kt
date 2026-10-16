package com.yuldash.app

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Проверяет договор сохранения выбора темы в настоящих SharedPreferences Android.
 * После каждого действия получаем хранилище заново, не подменяя editor или чтение.
 * Сброс ThemePrefs моделирует только потерю переменной в памяти: полный холодный запуск
 * и подключение выбора в интерфейсе проверяются отдельно на эмуляторе.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeChoicePersistenceTest {
    private lateinit var context: Context
    private var previousOverride: Boolean? = null

    private fun savedPreferences(): SharedPreferences =
        context.getSharedPreferences("yuldash_theme", Context.MODE_PRIVATE)

    @Before
    fun prepareIsolatedPreferences() {
        context = RuntimeEnvironment.getApplication()
        previousOverride = ThemePrefs.darkOverride
        ThemePrefs.darkOverride = null
        savedPreferences().edit().clear().commit()
    }

    @After
    fun restoreRuntimeState() {
        savedPreferences().edit().clear().commit()
        ThemePrefs.darkOverride = previousOverride
    }

    @Test
    fun darkChoiceRemainsSavedAfterInMemoryChoiceIsCleared() {
        persistThemeChoice(context, true)

        assertEquals(true, ThemePrefs.darkOverride)
        assertTrue(savedPreferences().contains("dark_override"))
        assertTrue(savedPreferences().getBoolean("dark_override", false))

        ThemePrefs.darkOverride = null

        assertNull(ThemePrefs.darkOverride)
        assertTrue(savedPreferences().contains("dark_override"))
        assertTrue(savedPreferences().getBoolean("dark_override", false))
    }

    @Test
    fun lightChoiceIsSavedExplicitlyRatherThanTreatedAsSystemDefault() {
        persistThemeChoice(context, false)

        assertEquals(false, ThemePrefs.darkOverride)
        assertTrue("Светлая тема должна отличаться от отсутствия выбора", savedPreferences().contains("dark_override"))
        assertFalse(savedPreferences().getBoolean("dark_override", true))

        ThemePrefs.darkOverride = null

        assertTrue(savedPreferences().contains("dark_override"))
        assertFalse(savedPreferences().getBoolean("dark_override", true))
    }

    @Test
    fun systemChoiceRemovesSavedDarkChoiceAndClearsRuntimeOverride() {
        persistThemeChoice(context, true)

        persistThemeChoice(context, null)

        assertNull(ThemePrefs.darkOverride)
        assertFalse(savedPreferences().contains("dark_override"))
    }

    @Test
    fun systemChoiceAlsoRemovesExplicitSavedLightChoice() {
        persistThemeChoice(context, false)
        assertTrue(savedPreferences().contains("dark_override"))

        persistThemeChoice(context, null)

        assertNull(ThemePrefs.darkOverride)
        assertFalse(savedPreferences().contains("dark_override"))
    }

    @Test
    fun latestSelectionReplacesEarlierChoiceInMemoryAndPreferences() {
        persistThemeChoice(context, true)
        assertEquals(true, ThemePrefs.darkOverride)
        assertTrue(savedPreferences().getBoolean("dark_override", false))

        persistThemeChoice(context, false)
        assertEquals(false, ThemePrefs.darkOverride)
        assertTrue(savedPreferences().contains("dark_override"))
        assertFalse(savedPreferences().getBoolean("dark_override", true))

        persistThemeChoice(context, true)
        assertEquals(true, ThemePrefs.darkOverride)
        assertTrue(savedPreferences().getBoolean("dark_override", false))
    }

    @Test
    fun writingAndRemovingThemeKeepsUnrelatedPreferences() {
        savedPreferences().edit()
            .putString("unrelated_language", "ba")
            .putInt("unrelated_font_scale", 130)
            .putBoolean("unrelated_hint_seen", true)
            .commit()
        val unrelated = savedPreferences().all.toMap()

        persistThemeChoice(context, true)
        assertEquals(unrelated, savedPreferences().all.filterKeys { it != "dark_override" })
        persistThemeChoice(context, false)
        assertEquals(unrelated, savedPreferences().all.filterKeys { it != "dark_override" })
        persistThemeChoice(context, null)

        assertNull(ThemePrefs.darkOverride)
        assertEquals(unrelated, savedPreferences().all)
    }

    @Test
    fun systemChoiceClearsStaleRuntimeOverrideWhenSavedKeyIsAlreadyAbsent() {
        assertFalse(savedPreferences().contains("dark_override"))
        ThemePrefs.darkOverride = true

        persistThemeChoice(context, null)

        assertNull(ThemePrefs.darkOverride)
        assertFalse(savedPreferences().contains("dark_override"))
        persistThemeChoice(context, null)
        assertNull(ThemePrefs.darkOverride)
        assertFalse(savedPreferences().contains("dark_override"))
    }
}
