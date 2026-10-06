package cc.axymorrsen.amtoolnext.runtime

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Low-overhead floating navigation shell for Apple Music 6.5.3.
 *
 * The previous implementation styled both mini-player and navigation and changed vertical
 * geometry, which caused overlap and extra relayout work. V2 deliberately owns only the native
 * bottom-navigation frame:
 *
 * - mini-player/sheet geometry is untouched;
 * - no per-frame / onSlide hook;
 * - no global-layout or view-tree scanning;
 * - no vertical margin/translation;
 * - the full-width native root becomes transparent and only the nav frame gets a capsule.
 */
internal class FloatingBottomBarRuntimeV2(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    private data class State(
        val background: Drawable?,
        val visibility: Int,
        val clipToOutline: Boolean,
        val elevation: Float,
        val leftMargin: Int?,
        val rightMargin: Int?,
    )

    private val states = WeakHashMap<View, State>()

    fun install() {
        runCatching {
            val create = AppleMusic653.playerActivityCreateStackedNavigationHolder(loader)
            module.hook(create)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null) {
                        scheduleApply(activity, attempt = 0)
                    }
                    result
                }

            logger(Log.INFO, "floating navigation V2 hook installed", null)
        }.onFailure { error ->
            logger(Log.ERROR, "floating navigation V2 hook failed", error)
        }
    }

    private fun scheduleApply(activity: Activity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.window.decorView.postDelayed(
            {
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                val done = apply(activity)
                if (!done && attempt < MAX_ATTACH_RETRIES) {
                    scheduleApply(activity, attempt + 1)
                }
            },
            if (attempt == 0) 0L else ATTACH_RETRY_MS,
        )
    }

    private fun apply(activity: Activity): Boolean {
        val root = find(activity, "bottom_navigation_root_stacked")
            ?: find(activity, "bottom_navigation_root_flat")
            ?: return false
        val frame = find(activity, "bottom_navigation_tabs_frame")
            ?: return false
        val nav = find(activity, "bottom_navigation")
            ?: return false

        val enabled = HookConfigRuntime.current().let {
            it.enabled && it.floatingBottomBar
        }
        if (!enabled) {
            restore(root)
            restore(frame)
            restore(nav)
            find(activity, "navigation_tabs_divider")?.let(::restore)
            find(activity, "nav_tabs_top_shadow")?.let(::restore)
            return true
        }

        // Preserve all native vertical geometry / peek calculations.
        save(root)
        save(frame)
        save(nav)

        root.background = null

        val margin = dp(frame, HORIZONTAL_MARGIN_DP)
        (frame.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.leftMargin != margin || params.rightMargin != margin) {
                params.leftMargin = margin
                params.rightMargin = margin
                frame.layoutParams = params
            }
        }

        // The frame owns the visual shell. The navigation view keeps its native tint/ripple
        // machinery but loses the old full-width rectangular background.
        nav.background = null
        frame.background = capsule(frame, resolveSurfaceColor(activity))
        frame.clipToOutline = true
        frame.elevation = dp(frame, ELEVATION_DP).toFloat()

        find(activity, "navigation_tabs_divider")?.let { seam ->
            save(seam)
            seam.visibility = View.GONE
        }
        find(activity, "nav_tabs_top_shadow")?.let { shadow ->
            save(shadow)
            shadow.visibility = View.GONE
        }

        RuntimeSignal.once(
            "floating-nav-v2",
            "AMTool：低开销悬浮导航已启用（迷你播放器保持原生）",
        )
        return true
    }

    private fun capsule(view: View, color: Int): Drawable {
        val density = view.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER_RADIUS_DP * density
            setColor(color)
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                if (isLight(color)) 0x18000000 else 0x24FFFFFF,
            )
        }
    }

    private fun save(view: View) {
        synchronized(states) {
            if (states.containsKey(view)) return
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            states[view] = State(
                background = view.background,
                visibility = view.visibility,
                clipToOutline = view.clipToOutline,
                elevation = view.elevation,
                leftMargin = margins?.leftMargin,
                rightMargin = margins?.rightMargin,
            )
        }
    }

    private fun restore(view: View) {
        val state = synchronized(states) { states.remove(view) } ?: return
        runCatching {
            view.background = state.background
            view.visibility = state.visibility
            view.clipToOutline = state.clipToOutline
            view.elevation = state.elevation
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (
                params != null &&
                state.leftMargin != null &&
                state.rightMargin != null
            ) {
                params.leftMargin = state.leftMargin
                params.rightMargin = state.rightMargin
                view.layoutParams = params
            }
        }
    }

    private fun find(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(
            name,
            "id",
            AppleMusic653.PACKAGE,
        )
        return if (id == 0) null else activity.findViewById(id)
    }

    private fun resolveSurfaceColor(activity: Activity): Int {
        val value = TypedValue()
        val resolved = activity.theme.resolveAttribute(
            android.R.attr.colorBackground,
            value,
            true,
        )
        val raw = when {
            !resolved -> Color.WHITE
            value.resourceId != 0 ->
                runCatching { activity.getColor(value.resourceId) }.getOrDefault(value.data)
            else -> value.data
        }
        return Color.argb(
            SURFACE_ALPHA,
            Color.red(raw),
            Color.green(raw),
            Color.blue(raw),
        )
    }

    private fun isLight(color: Int): Boolean {
        val luma =
            0.2126 * Color.red(color) +
                0.7152 * Color.green(color) +
                0.0722 * Color.blue(color)
        return luma >= 128.0
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val HORIZONTAL_MARGIN_DP = 12f
        private const val CORNER_RADIUS_DP = 28f
        private const val ELEVATION_DP = 7f
        private const val SURFACE_ALPHA = 248

        private const val MAX_ATTACH_RETRIES = 5
        private const val ATTACH_RETRY_MS = 90L
    }
}
