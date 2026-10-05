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
 * Floating bottom chrome for Apple Music 6.5.3.
 *
 * Geometry ownership stays with Apple Music: the stacked navigation root and mini-player root keep
 * their original size/constraints/peek semantics. We only turn the inner tabs frame and
 * mini_player_content into inset rounded surfaces, so player gestures and content insets continue
 * to use Apple's native layout.
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
        val alpha: Float,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?,
    )

    private val states = WeakHashMap<View, ViewState>()

    fun install() {
        runCatching {
            val postResume = Activity::class.java.getDeclaredMethod("onPostResume")
                .apply { isAccessible = true }
            module.hook(postResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    activity?.window?.decorView?.post {
                        apply(activity)
                        // Some artist/album fragments mount bottom chrome after onPostResume.
                        activity.window.decorView.postDelayed({ apply(activity) }, 320L)
                    }
                    result
                }

            val focus = Activity::class.java.getDeclaredMethod(
                "onWindowFocusChanged",
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            module.hook(focus)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && chain.args.firstOrNull() == true) {
                        activity.window.decorView.post { apply(activity) }
                    }
                    result
                }

            logger(Log.INFO, "floating bottom chrome hooks installed", null)
        }.onFailure { error ->
            logger(Log.ERROR, "floating bottom chrome installation failed", error)
        }
    }

    private fun apply(activity: Activity) {
        if (activity.packageName != AppleMusic653.PACKAGE) return

        val enabled = HookConfigRuntime.current().let {
            it.enabled && it.floatingBottomBar
        }
        if (!enabled) {
            restoreAll()
            return
        }

        val root = find(
            activity,
            "bottom_navigation_root_stacked",
        ) ?: find(activity, "bottom_navigation_root_flat")

        val navFrame = find(activity, "bottom_navigation_tabs_frame")
            ?: find(activity, "bottom_navigation")
        val nav = find(activity, "bottom_navigation")
        val miniRoot = find(activity, "mini_player")
            ?: find(activity, "mini_player_touch_panel")
        val miniContent = find(activity, "mini_player_content")
        val divider = find(activity, "navigation_tabs_divider")
        val topShadow = find(activity, "nav_tabs_top_shadow")

        if (navFrame == null && miniContent == null) return

        root?.let { view ->
            save(view)
            view.background = null
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
            }
        }
        miniRoot?.let { view ->
            save(view)
            view.background = null
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
            }
        }

        val surfaceColor = resolveSurfaceColor(activity)
        navFrame?.let { view ->
            styleCapsule(
                view = view,
                color = surfaceColor,
                horizontalMarginDp = 12f,
                topMarginDp = null,
                bottomMarginDp = 8f,
                radiusDp = 28f,
                elevationDp = 8f,
            )
        }

        // Keep BottomNavigationView's native selection/tint/ripple logic. The parent frame clips
        // its stock background to the rounded outline.
        nav?.let { view ->
            save(view)
            view.clipToOutline = true
        }

        miniContent?.let { view ->
            styleCapsule(
                view = view,
                color = surfaceColor,
                horizontalMarginDp = 12f,
                topMarginDp = 4f,
                bottomMarginDp = 4f,
                radiusDp = 20f,
                elevationDp = 7f,
            )
        }

        listOfNotNull(divider, topShadow).forEach { seam ->
            save(seam)
            seam.alpha = 0f
        }
    }

    private fun styleCapsule(
        view: View,
        color: Int,
        horizontalMarginDp: Float,
        topMarginDp: Float?,
        bottomMarginDp: Float?,
        radiusDp: Float,
        elevationDp: Float,
    ) {
        save(view)
        val density = view.resources.displayMetrics.density
        val horizontal = (horizontalMarginDp * density).roundToInt()

        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.leftMargin = horizontal
            params.rightMargin = horizontal
            topMarginDp?.let { params.topMargin = (it * density).roundToInt() }
            bottomMarginDp?.let { params.bottomMargin = (it * density).roundToInt() }
            view.layoutParams = params
        }

        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(color)
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                if (isLight(color)) 0x18000000 else 0x24FFFFFF,
            )
        }
        view.elevation = elevationDp * density
        view.translationZ = 0f
        view.clipToOutline = true
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
                alpha = view.alpha,
                leftMargin = margins?.leftMargin,
                topMargin = margins?.topMargin,
                rightMargin = margins?.rightMargin,
                bottomMargin = margins?.bottomMargin,
            )
        }
    }

    private fun restoreAll() {
        synchronized(states) {
            states.entries.toList().forEach { (view, state) ->
                runCatching {
                    view.background = state.background
                    view.elevation = state.elevation
                    view.translationZ = state.translationZ
                    view.clipToOutline = state.clipToOutline
                    view.alpha = state.alpha
                    val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (
                        margins != null &&
                        state.leftMargin != null &&
                        state.topMargin != null &&
                        state.rightMargin != null &&
                        state.bottomMargin != null
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
            states.clear()
        }
    }

    private fun find(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(
            name,
            "id",
            AppleMusic653.PACKAGE,
        )
        if (id == 0) return null
        return activity.findViewById(id)
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
            248,
            Color.red(base),
            Color.green(base),
            Color.blue(base),
        )
    }

    private fun isLight(color: Int): Boolean {
        val luminance =
            (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color))
        return luminance >= 128.0
    }
}
