// Заглушка androidx.compose.runtime (артефакт только на dl.google.com).
// Здесь нужны ТОЛЬКО обычные библиотечные функции состояния — @Composable не задействован,
// поэтому плагин Compose-компилятора не требуется.
package androidx.compose.runtime

import kotlin.reflect.KProperty

interface State<out T> { val value: T }

interface MutableState<T> : State<T> { override var value: T }

private class MutableStateImpl<T>(override var value: T) : MutableState<T>

fun <T> mutableStateOf(value: T): MutableState<T> = MutableStateImpl(value)

operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}
