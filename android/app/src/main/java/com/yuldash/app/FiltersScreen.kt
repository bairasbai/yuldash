package com.yuldash.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Фильтры по умолчанию для «Ближайших поездок» — хранятся в SharedPreferences. */
internal object FilterPrefs {
    private const val PREF = "yuldash_filters"
    private const val KEY = "default_filters"
    private const val SEATS_KEY = "default_seats"
    fun load(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    fun save(ctx: Context, set: Set<String>) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putStringSet(KEY, set).apply()
    }
    fun loadSeats(ctx: Context): Int = runCatching {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(SEATS_KEY, 1).takeIf { it in NearbyGroupSizes } ?: 1
    }.getOrDefault(1)
    fun saveSeats(ctx: Context, seats: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(SEATS_KEY, seats).apply()
    }
}

/** Экран «Фильтры по умолчанию»: тумблеры условий, сохраняются и применяются к «Ближайшим». */
@Composable
internal fun FiltersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(FilterPrefs.load(ctx)) }
    val owner by com.yuldash.app.data.ApiClient.sessionChanges.collectAsState()
    var seats by remember(owner) { mutableIntStateOf(FilterPrefs.loadSeats(ctx)) }
    fun toggle(k: String) {
        sel = if (k in sel) sel - k else sel + k
        FilterPrefs.save(ctx, sel)
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Фильтры по умолчанию", "Ғәҙәти фильтрҙар"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { Text(appText("Эти условия применятся к «Ближайшим поездкам» автоматически при открытии карты.", "Был шарттар карта асылғанда «Яҡын сәфәрҙәргә» автомат ҡулланыла."), color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp) }
            item {
                NearbyGroupSizeSetting(seats) { next ->
                    com.yuldash.app.data.ApiClient.runIfUnchangedSession(owner) {
                        seats = next
                        FilterPrefs.saveSeats(ctx, next)
                    }
                }
            }
            item {
                SettingsGroup {
                    SettingSwitchRow(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ"), appText("Показывать только поездки для женщин", "Тик ҡатын-ҡыҙ өсөн сәфәрҙәр"), "women" in sel) { toggle("women") }
                    SettingSwitchRow(Icons.Default.ChildCare, appText("Детское кресло", "Балалар ултырғысы"), appText("Есть детское кресло или бустер", "Балалар ултырғысы бар"), "child" in sel) { toggle("child") }
                    SettingSwitchRow(Icons.Default.Pets, appText("С животным", "Хайуан менән"), appText("Можно ехать с питомцем", "Хайуан менән барырға мөмкин"), "pets" in sel) { toggle("pets") }
                    SettingSwitchRow(Icons.Default.Luggage, appText("Багаж", "Йөк"), appText("Есть место под багаж", "Багаж өсөн урын бар"), "baggage" in sel) { toggle("baggage") }
                    SettingSwitchRow(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), appText("Есть кондиционер в салоне", "Салонда кондиционер бар"), "ac" in sel) { toggle("ac") }
                    SettingSwitchRow(Icons.Default.Block, appText("Некурящий", "Тартмаусы"), appText("Показывать поездки без курения", "Тартыуһыҙ сәфәрҙәр генә"), "nosmoke" in sel) { toggle("nosmoke") }
                    SettingSwitchRow(Icons.Default.VolumeOff, appText("Тихая поездка", "Тыныс сәфәр"), appText("Едем без лишних разговоров и громкой музыки", "Артыҡ һөйләшеүһеҙ, ҡысҡырып музыкаһыҙ"), "quiet" in sel) { toggle("quiet") }
                }
            }
        }
    }
}
