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
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Lightweight floating navigation dock.
 *
 * Important: the mini-player is deliberately left on Apple's native geometry and background.
 * Previous alpha builds styled both the mini-player and bottom navigation independently, which
 * created overlapping surfaces, retained the outer chrome and forced repeated relayouts.
 *
 * alpha3 only decorates the navigation frame. The stock stacked root keeps its height/insets, so
 * the mini-player remains above the dock and all Apple gestures continue to use native bounds.
 */
internal class FloatingBottomBarRuntime(
    private val module: XposedModule,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    private data class ViewState(
        val background: Drawable?,
        val elevation: Float,
        val translationZ: Float,
        val translationY: Float,
        val clipToOutline: Boolean,
        val alpha: Float,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?,
    )

    private val states = WeakHashMap<View, ViewState>()
    private val appliedNavigation = WeakHashMap<Activity, WeakReference<View>>()

    fun install() {
        hookActivityCallback("onPostResume")
        hookActivityCallback("onContentChanged")
        logger(Log.INFO, "floating navigation dock hooks installed", null)
    }

    private fun hookActivityCallback(name: String) {
        runCatching {
            val method = Activity::class.java.getDeclaredMethod(name).apply { isAccessible = true }
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity != null && activity.packageName == AppleMusic653.PACKAGE) {
                        activity.window?.decorView?.post { apply(activity) }
                    }
                    result
                }
        }.onFailure { error ->
            logger(Log.ERROR, "floating dock Activity#$name hook failed", error)
        }
    }

    private fun apply(activity: Activity) {
        val config = HookConfigRuntime.current()
        if (!config.enabled || !config.floatingBottomBar) {
            restoreActivity(activity)
            return
        }

        val navFrame = find(activity, "bottom_navigation_tabs_frame")
            ?: find(activity, "bottom_navigation")
            ?: return
        val existing = appliedNavigation[activity]?.get()
        if (existing === navFrame && states.containsKey(navFrame)) return

        restoreActivity(activity)

        val root = find(activity, "bottom_navigation_root_stacked")
            ?: find(activity, "bottom_navigation_root_flat")
        val nav = find(activity, "bottom_navigation")
        val divider = find(activity, "navigation_tabs_divider")
        val topShadow = find(activity, "nav_tabs_top_shadow")

        root?.let { view ->
            save(view)
            view.background = null
            if (view is ViewGroup) {
                view.clipChildren = false
                view.clipToPadding = false
            }
        }

        listOfNotNull(divider, topShadow).forEach { seam ->
            save(seam)
            seam.alpha = 0f
        }

        val density = navFrame.resources.displayMetrics.density
        val horizontalInset = (12f * density).roundToInt()

        save(navFrame)
        (navFrame.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            // Preserve Apple's vertical geometry exactly; only inset horizontally.
            params.leftMargin = horizontalInset
            params.rightMargin = horizontalInset
            navFrame.layoutParams = params
        }
        navFrame.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f * density
            setColor(resolveSurfaceColor(activity))
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                if (isLight(resolveSurfaceColor(activity))) 0x18000000 else 0x24FFFFFF,
            )
        }
        navFrame.elevation = 8f * density
        navFrame.translationZ = 0f
        navFrame.translationY = -6f * density
        navFrame.clipToOutline = true

        // Keep BottomNavigationView's selection/ripple/tint implementation, but make sure its
        // rectangular child background cannot escape the rounded parent outline.
        nav?.let { view ->
            save(view)
            view.clipToOutline = true
        }

        appliedNavigation[activity] = WeakReference(navFrame)
        logger(Log.INFO, "floating navigation dock applied", null)
    }

    private fun save(view: View) {
        synchronized(states) {
            if (states.containsKey(view)) return
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            states[view] = ViewState(
                background = view.background,
                elevation = view.elevation,
                translationZ = view.translationZ,
                translationY = view.translationY,
                clipToOutline = view.clipToOutline,
                alpha = view.alpha,
                leftMargin = margins?.leftMargin,
                topMargin = margins?.topMargin,
                rightMargin = margins?.rightMargin,
                bottomMargin = margins?.bottomMargin,
            )
        }
    }

    private fun restoreActivity(activity: Activity) {
        val root = activity.window?.decorView ?: return
        synchronized(states) {
            states.entries.toList().forEach { (view, state) ->
                if (!isDescendantOf(view, root)) return@forEach
                runCatching {
                    view.background = state.background
                    view.elevation = state.elevation
                    view.translationZ = state.translationZ
                    view.translationY = state.translationY
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
                states.remove(view)
            }
        }
        appliedNavigation.remove(activity)
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun find(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", AppleMusic653.PACKAGE)
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
            250,
            Color.red(base),
            Color.green(base),
            Color.blue(base),
        )
    }

    private fun isLight(color: Int): Boolean {
        val luminance =
            0.2126 * Color.red(color) +
                0.7152 * Color.green(color) +
                0.0722 * Color.blue(color)
        return luminance >= 128.0
    }
}
