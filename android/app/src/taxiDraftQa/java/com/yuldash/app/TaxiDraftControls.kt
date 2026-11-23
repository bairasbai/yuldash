package com.yuldash.app

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** Read-only UI observation for the pinned QA flavor. Nothing registers a lifecycle listener or
 * keeps an Activity/Compose view alive. Inspect only a focused window of our resumed MainActivity.
 * The process-local generation changes on observation of a different Activity instance.
 */
internal object TaxiDraftControls {
    private var lastActivity: WeakReference<MainActivity>? = null
    private var activityGeneration = 0

    private fun observed(kind: String, nodes: JSONArray, activity: MainActivity): JSONObject {
        if (lastActivity?.get() !== activity) {
            lastActivity = WeakReference(activity)
            activityGeneration++
        }
        return result(kind, nodes).put("activity-generation", activityGeneration)
    }

    private val labels = setOf(
        "QA Stop A", "QA Stop B", "QA Destination", "QA Now Destination", "QA Origin", "QA Alternate Place",
        "Заехать по пути", "Юлда инеп сығыу", "Детали заказа", "Заказ деталдәре",
        "На время", "Ваҡытҡа", "Обратно тоже", "Кире лә", "2 ч", "2 сәғ",
        "Моя позиция", "Минең урын", "Новый заказ", "Яңы заказ", "Убрать остановку", "Туҡталышты алыу",
        "Изменить", "Үҙгәртеү", "Куда едем?", "Ҡайҙа барабыҙ?", "Куда заехать?", "Ҡайҙа инеп сығырға?",
        "Включи геолокацию", "Геолокацияны ҡабыҙ", "Профиль", "Быстрый заказ", "Тиҙ заказ",
        "Рассчитать цену", "Хаҡты иҫәпләү", "Сейчас", "Хәҙер",
    )
    private const val HOUR = "(?:[01][0-9]|2[0-3]):[0-5][0-9]"
    private val scheduledCta = Regex("(?:Заказать на $HOUR|$HOUR-ға заказ итеү)")
    private fun allowed(text: String) = text in labels || scheduledCta.matches(text)

    fun write(context: Context) {
        val snapshot = if (Build.VERSION.SDK_INT >= 29) readForeground() else result("no-root")
        val data = snapshot.toString().toByteArray()
        val bounded = if (data.size <= 150_000) data else result("no-root").toString().toByteArray()
        File(context.filesDir, "taxi-consent-qa").apply { mkdirs() }
            .resolve("controls.json").writeBytes(bounded)
    }

    private fun result(kind: String, nodes: JSONArray = JSONArray()) =
        JSONObject().put("observation", kind).put("nodes", nodes)

    private tailrec fun activity(context: Context): MainActivity? = when {
        context is MainActivity -> context
        context is ContextWrapper && context.baseContext !== context -> activity(context.baseContext)
        else -> null
    }

    @RequiresApi(29)
    private fun readForeground(): JSONObject {
        val windows = WindowInspector.getGlobalWindowViews()
        fun windowActivity(view: View) = view.findViewTreeLifecycleOwner() as? MainActivity ?: activity(view.context)
        val main = windows.firstNotNullOfOrNull { view ->
            windowActivity(view)?.takeIf {
                !it.isFinishing && !it.isDestroyed && it.lifecycle.currentState == Lifecycle.State.RESUMED
            }
        } ?: return result("no-root")
        val decor = main.window.decorView
        val windowToken = decor.applicationWindowToken ?: return result("no-root")
        val activityToken = (decor.layoutParams as? WindowManager.LayoutParams)?.token
        val window = windows.asReversed().firstOrNull {
            it.isShown && it.hasWindowFocus() && (
                it === decor || windowActivity(it) === main || it.applicationWindowToken == windowToken ||
                    activityToken != null && (it.layoutParams as? WindowManager.LayoutParams)?.token == activityToken
                )
        } ?: return result("no-root")
        val nodes = JSONArray()
        val confirm = window.findViewById<View>(android.R.id.button1)
        if (confirm != null && confirm.isShown) {
            val bounds = visibleBounds(confirm) ?: return result("no-root")
            nodes.put(node(bounds, "android.widget.Button", id = "android:id/button1",
                clickable = confirm.isClickable, enabled = confirm.isEnabled))
            return observed("native-dialog", nodes, main)
        }
        val seen = mutableSetOf<String>()
        fun visitView(view: View) {
            if (!view.isShown) return
            if (view is ViewRootForTest) {
                val visible = visibleBounds(view) ?: return
                val origin = IntArray(2).also(view::getLocationOnScreen)
                // Prefer a label's own rectangle. A merging parent may represent several controls.
                collect(view.semanticsOwner.unmergedRootSemanticsNode, origin, visible, nodes, seen)
                collect(view.semanticsOwner.rootSemanticsNode, origin, visible, nodes, seen)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) visitView(view.getChildAt(i))
            }
        }
        visitView(window)
        return if (nodes.length() > 0) observed("focused-compose", nodes, main)
        else result("no-root")
    }

    // getLocalVisibleRect includes ancestor clipping. Add the real view's origin in screen
    // coordinates, including offsets of popup/dialog windows and the status bar.
    private fun visibleBounds(view: View): Rect? {
        val bounds = Rect()
        if (!view.getLocalVisibleRect(bounds)) return null
        val origin = IntArray(2).also(view::getLocationOnScreen)
        bounds.offset(origin[0], origin[1])
        return bounds
    }

    private fun collect(
        semantics: SemanticsNode, origin: IntArray, clip: Rect, nodes: JSONArray, seen: MutableSet<String>,
    ) {
        val bounds = semantics.boundsInRoot
        if (bounds.isEmpty || !bounds.isFinite) return
        val screen = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
        screen.offset(origin[0], origin[1])
        if (!screen.intersect(clip)) return
        val config = semantics.config
        val editable = SemanticsActions.SetText in config
        val role = config.getOrNull(SemanticsProperties.Role)
        val clickable = SemanticsActions.OnClick in config
        val verticalRange = config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
        val scrollable = verticalRange != null
        val checkable = SemanticsProperties.ToggleableState in config || role == Role.RadioButton
        val enabled = SemanticsProperties.Disabled !in config
        val text = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.filter(::allowed)
        val descriptions = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().filter(::allowed)
        val className = when {
            editable -> "android.widget.EditText"
            role == Role.Switch -> "android.widget.Switch"
            role == Role.Checkbox -> "android.widget.CheckBox"
            role == Role.RadioButton -> "android.widget.RadioButton"
            role == Role.Button -> "android.widget.Button"
            text.isNotEmpty() -> "android.widget.TextView"
            else -> "android.view.View"
        }
        // Never export arbitrary text, content descriptions, resource names, or editable values.
        if (clickable || scrollable || checkable || editable || text.isNotEmpty() || descriptions.isNotEmpty()) {
            val row = node(screen, className, text.singleOrNull().orEmpty(), descriptions.singleOrNull().orEmpty(),
                clickable = clickable, scrollable = scrollable, checkable = checkable, enabled = enabled)
            verticalRange?.let { range ->
                val value = range.value()
                val maximum = range.maxValue()
                if (value.isFinite() && maximum.isFinite()) {
                    row.put("scroll-offset", value.roundToInt()).put("scroll-maximum", maximum.roundToInt())
                }
            }
            if (seen.add(row.toString())) nodes.put(row)
        }
        for (child in semantics.children) collect(child, origin, clip, nodes, seen)
    }

    private fun node(
        bounds: Rect, className: String, text: String = "", description: String = "", id: String = "",
        clickable: Boolean = false, scrollable: Boolean = false, checkable: Boolean = false, enabled: Boolean,
    ) = JSONObject().put("text", text).put("content-desc", description).put("class", className)
        .put("resource-id", id).put("bounds", bounds.toShortString())
        .put("clickable", clickable.toString()).put("scrollable", scrollable.toString())
        .put("checkable", checkable.toString()).put("enabled", enabled.toString())
}
