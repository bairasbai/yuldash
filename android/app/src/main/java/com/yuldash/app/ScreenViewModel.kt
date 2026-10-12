package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * ViewModel на время жизни одного экрана.
 *
 * В Юлдаше один Activity, а навигация — `enum Screen` + `when` в YuldashApp. Обычный
 * `viewModel()` жил бы до закрытия приложения, и запросы ушедшего экрана продолжали бы
 * менять его состояние. Здесь хранилище рождается вместе с экраном и очищается, когда экран
 * уходит из композиции: viewModelScope отменяется ровно тогда же.
 *
 * Ничего не публикует в LocalViewModelStoreOwner: дети экрана по-прежнему видят общий
 * YuldashViewModel.
 */
@Composable
internal inline fun <reified VM : ViewModel> rememberScreenViewModel(crossinline create: () -> VM): VM {
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store) {
        ViewModelProvider(store, viewModelFactory { initializer { create() } })[VM::class.java]
    }
}
