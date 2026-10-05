package cc.axymorrsen.amtoolnext.runtime

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Low-overhead floating bottom chrome for Apple Music 6.5.3.
 *
 * alpha2 styled the mini player and tab bar as two independent capsules. On the stacked phone
 * host those two views share one fixed-height native holder, so margins/elevation made them collide
 * and exposed the artwork-tinted holder behind them. It also re-scanned/restyled on every window
 * focus change, causing layout churn.
 *
 * alpha3 treats bottom_navigation_root_stacked as the only geometry owner: one floating card,
 * native mini-player + navigation remain vertically stacked inside it, and all inner native
 * backgrounds/seams are made transparent. Styling is identity/revision gated and installed from a
 * bounded resume retry only; no focus/pre-draw/global-layout hot loop exists.
 */
internal class FloatingBottomBarRuntime(
    private val module: XposedModule,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    private data class ViewState(
        val background: Drawable?,
        val elevation: Float,
        val translationZ: Float,
        val clipToOutline: Boolean,
        val visibility: Int,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?,
    )

    private data class ActivitySession(
        val root: WeakReference<View>,
        val revision: Long,
        val enabled: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<View, ViewState>()
    private val sessions = WeakHashMap<Activity, ActivitySession>()
    private val retryGeneration = WeakHashMap<Activity, Long>()
    private var generation = 0L

    fun install() {
        runCatching {
            val postResume = Activity::class.java.getDeclaredMethod("onPostResume")
                .apply { isAccessible = true }
            module.hook(postResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == AppleMusic653.PACKAGE) {
                        scheduleBoundedAttach(activity)
                    }
                    result
                }
            logger(Log.INFO, "floating bottom chrome alpha3 hook installed", null)
        }.onFailure { error ->
            logger(Log.ERROR, "floating bottom chrome alpha3 installation failed", error)
        }
    }

    private fun scheduleBoundedAttach(activity: Activity) {
        val token = synchronized(retryGeneration) {
            generation += 1L
            generation.also { retryGeneration[activity] = it }
        }
        val delays = longArrayOf(0L, 48L, 144L, 320L)
        delays.forEach { delay ->
            main.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                val current = synchronized(retryGeneration) { retryGeneration[activity] }
                if (current != token) return@postDelayed
                if (applyIfReady(activity)) {
                    synchronized(retryGeneration) {
                        if (retryGeneration[activity] == token) retryGeneration.remove(activity)
                    }
                }
            }, delay)
        }
    }

    private fun applyIfReady(activity: Activity): Boolean {
        val root = find(activity, "bottom_navigation_root_stacked")
            ?: find(activity, "bottom_navigation_root_flat")
            ?: return false

        val config = HookConfigRuntime.current()
        val enabled = config.enabled && config.floatingBottomBar
        val previous = synchronized(sessions) { sessions[activity] }
        if (
            previous?.root?.get() === root &&
            previous.revision == config.revision &&
            previous.enabled == enabled
        ) {
            return true
        }

        if (!enabled) {
            restoreOwnedViews()
            synchronized(sessions) {
                sessions[activity] = ActivitySession(WeakReference(root), config.revision, false)
            }
            return true
        }

        styleRoot(activity, root)
        synchronized(sessions) {
            sessions[activity] = ActivitySession(WeakReference(root), config.revision, true)
        }

        // Width/height and short holder ancestors are reliable only after layout. This is a
        // one-shot post, not a permanent layout observer.
        root.post {
            if (root.isAttachedToWindow) {
                clearShortChromeBackdrops(activity, root)
            }
        }
        return true
    }

    private fun styleRoot(activity: Activity, root: View) {
        val density = root.resources.displayMetrics.density
        val surface = resolveSurfaceColor(activity)

        save(root)
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            val side = dp(density, 12f)
            val bottom = dp(density, 8f)
            if (
                params.leftMargin != side ||
                params.rightMargin != side ||
                params.bottomMargin != bottom
            ) {
                params.leftMargin = side
                params.rightMargin = side
                params.bottomMargin = bottom
                root.layoutParams = params
            }
        }
        root.background = capsule(surface, density, 28f)
        root.elevation = 8f * density
        root.translationZ = 0f
        root.clipToOutline = true
        if (root is ViewGroup) {
            root.clipChildren = true
            root.clipToPadding = false
        }

        // One capsule owns the material. Native children keep sizing, click handling, menu state
        // and player gestures, but stop painting rectangular layers behind the capsule.
        listOf(
            "mini_player",
            "mini_player_touch_panel",
            "mini_player_content",
            "bottom_navigation_tabs_frame",
            "bottom_navigation",
        ).mapNotNull { find(activity, it) }.distinct().forEach(::clearBackground)

        listOf(
            "navigation_tabs_divider",
            "nav_tabs_top_shadow",
        ).mapNotNull { find(activity, it) }.forEach(::hideSeam)

        logger(
            Log.INFO,
            "floating bottom chrome applied root=${root.javaClass.name}, revision=" +
                HookConfigRuntime.revision(),
            null,
        )
    }

    /**
     * Apple can paint the artwork tint on a short holder above the stacked root. Clear only
     * ancestors whose measured height is close to the bottom chrome itself. This avoids touching
     * the full player sheet/page background while removing the blue rectangular backdrop visible
     * around the floating card.
     */
    private fun clearShortChromeBackdrops(activity: Activity, root: View) {
        if (root.height <= 0) return
        val decor = activity.window.decorView
        val slack = dp(root.resources.displayMetrics.density, 72f)
        val maxHeight = root.height + slack
        var parent = root.parent as? View
        var depth = 0
        while (parent != null && parent !== decor && depth < 4) {
            if (
                parent.height in 1..maxHeight &&
                parent.width >= (root.width - dp(root.resources.displayMetrics.density, 24f))
            ) {
                clearBackground(parent)
                if (parent is ViewGroup) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                }
            }
            parent = parent.parent as? View
            depth += 1
        }
    }

    private fun clearBackground(view: View) {
        save(view)
        if (view.background != null) view.background = null
        view.elevation = 0f
        view.translationZ = 0f
    }

    private fun hideSeam(view: View) {
        save(view)
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    private fun save(view: View) {
        synchronized(states) {
            if (states.containsKey(view)) return
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            states[view] = ViewState(
                background = view.background,
                elevation = view.elevation,
                translationZ = view.translationZ,
                clipToOutline = view.clipToOutline,
                visibility = view.visibility,
                leftMargin = margins?.leftMargin,
                topMargin = margins?.topMargin,
                rightMargin = margins?.rightMargin,
                bottomMargin = margins?.bottomMargin,
            )
        }
    }

    private fun restoreOwnedViews() {
        synchronized(states) {
            states.entries.toList().forEach { (view, state) ->
                runCatching {
                    view.background = state.background
                    view.elevation = state.elevation
                    view.translationZ = state.translationZ
                    view.clipToOutline = state.clipToOutline
                    view.visibility = state.visibility
                    val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (
                        margins != null &&
                        state.leftMargin != null &&
                        state.topMargin != null &&
                        state.rightMargin != null &&
                        state.bottomMargin != null
                    ) {
                        if (
                            margins.leftMargin != state.leftMargin ||
                            margins.topMargin != state.topMargin ||
                            margins.rightMargin != state.rightMargin ||
                            margins.bottomMargin != state.bottomMargin
                        ) {
                            margins.setMargins(
                                state.leftMargin,
                                state.topMargin,
                                state.rightMargin,
                                state.bottomMargin,
                            )
                            view.layoutParams = margins
                        }
                    }
                }
            }
            states.clear()
        }
    }

    private fun find(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", AppleMusic653.PACKAGE)
        if (id == 0) return null
        return activity.findViewById(id)
    }

    private fun capsule(color: Int, density: Float, radiusDp: Float): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                if (isLight(color)) 0x14000000 else 0x20FFFFFF,
            )
        }

    private fun resolveSurfaceColor(activity: Activity): Int {
        val value = TypedValue()
        val resolved = activity.theme.resolveAttribute(
            android.R.attr.colorBackground,
            value,
            true,
        )
        val base = if (!resolved) {
            Color.WHITE
        } else if (value.resourceId != 0) {
            runCatching { activity.getColor(value.resourceId) }.getOrDefault(value.data)
        } else {
            value.data
        }
        return Color.argb(
            252,
            Color.red(base),
            Color.green(base),
            Color.blue(base),
        )
    }

    private fun dp(density: Float, value: Float): Int = (value * density).roundToInt()

    private fun isLight(color: Int): Boolean {
        val luminance =
            (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color))
        return luminance >= 128.0
    }
}
