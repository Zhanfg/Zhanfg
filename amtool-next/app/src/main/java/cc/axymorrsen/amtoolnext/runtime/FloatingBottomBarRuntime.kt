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
 * Low-overhead floating bottom dock for Apple Music 6.5.3.
 *
 * alpha2 styled mini-player and navigation as two independent capsules and re-applied layout on
 * focus. That fought Apple's stacked holder, produced overlapping pills / a colored rectangle
 * behind them, and caused avoidable layout work. alpha3 owns one stable outer dock instead:
 *
 * - one rounded root containing both mini-player and tabs;
 * - native child geometry and touch dispatch remain untouched;
 * - player background layers are faded out only while the sheet is collapsed;
 * - no per-bind / per-focus hierarchy scans.
 */
internal class FloatingBottomBarRuntime(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    private data class ViewState(
        val background: Drawable?,
        val elevation: Float,
        val translationZ: Float,
        val clipToOutline: Boolean,
        val alpha: Float,
        val visibility: Int,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?,
    )

    private inner class DockSession(
        val activity: Activity,
        val root: View,
        val navFrame: View?,
        val navigation: View?,
        val miniRoot: View?,
        val miniContent: View?,
        val divider: View?,
        val topShadow: View?,
        val playerLayers: List<View>,
    ) {
        private val states = WeakHashMap<View, ViewState>()
        private var attached = false
        private var lastProgress = Float.NaN

        fun attach() {
            if (attached) return
            attached = true

            save(root)
            val density = root.resources.displayMetrics.density
            (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                val baseline = states[root]
                params.leftMargin = (baseline?.leftMargin ?: params.leftMargin) + dp(root, DOCK_SIDE_DP)
                params.rightMargin = (baseline?.rightMargin ?: params.rightMargin) + dp(root, DOCK_SIDE_DP)
                params.bottomMargin = (baseline?.bottomMargin ?: params.bottomMargin) + dp(root, DOCK_BOTTOM_DP)
                root.layoutParams = params
            }
            root.background = dockDrawable(activity)
            root.elevation = DOCK_ELEVATION_DP * density
            root.translationZ = 0f
            root.clipToOutline = true

            listOfNotNull(navFrame, navigation, miniRoot, miniContent).forEach { child ->
                save(child)
                child.background = null
                child.elevation = 0f
                child.translationZ = 0f
            }

            listOfNotNull(divider, topShadow).forEach { seam ->
                save(seam)
                seam.alpha = 0f
            }

            playerLayers.forEach(::save)
            updateProgress(lastSlideProgress)

            logger(
                Log.INFO,
                "floating dock attached root=${resourceName(root)} layers=${playerLayers.size}",
                null,
            )
        }

        fun updateProgress(progress: Float) {
            if (!attached) return
            val p = progress.coerceIn(0f, 1f)
            if (!lastProgress.isNaN() && kotlin.math.abs(lastProgress - p) < 0.008f) return
            lastProgress = p

            // At collapsed=0 the native player artwork/background is what created the blue
            // rectangle behind alpha2's cards. Restore it progressively as the sheet opens.
            val material = smoothStep(0.08f, 0.52f, p)
            playerLayers.forEach { layer ->
                val baseline = states[layer]?.alpha ?: 1f
                val target = baseline * material
                if (kotlin.math.abs(layer.alpha - target) >= 0.01f) {
                    layer.alpha = target
                }
            }

            // Apple's holder owns the actual slide/visibility. We only fade our material late
            // enough that the native full-player background has already returned.
            val dockAlpha = 1f - smoothStep(0.35f, 0.68f, p)
            val baseline = states[root]?.alpha ?: 1f
            val target = baseline * dockAlpha
            if (kotlin.math.abs(root.alpha - target) >= 0.01f) {
                root.alpha = target
            }
        }

        fun restore() {
            if (!attached) return
            attached = false
            states.entries.toList().forEach { (view, state) ->
                runCatching {
                    view.background = state.background
                    view.elevation = state.elevation
                    view.translationZ = state.translationZ
                    view.clipToOutline = state.clipToOutline
                    view.alpha = state.alpha
                    view.visibility = state.visibility
                    val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (
                        params != null &&
                        state.leftMargin != null &&
                        state.topMargin != null &&
                        state.rightMargin != null &&
                        state.bottomMargin != null
                    ) {
                        params.setMargins(
                            state.leftMargin,
                            state.topMargin,
                            state.rightMargin,
                            state.bottomMargin,
                        )
                        view.layoutParams = params
                    }
                }
            }
            states.clear()
        }

        private fun save(view: View) {
            if (states.containsKey(view)) return
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            states[view] = ViewState(
                background = view.background,
                elevation = view.elevation,
                translationZ = view.translationZ,
                clipToOutline = view.clipToOutline,
                alpha = view.alpha,
                visibility = view.visibility,
                leftMargin = margins?.leftMargin,
                topMargin = margins?.topMargin,
                rightMargin = margins?.rightMargin,
                bottomMargin = margins?.bottomMargin,
            )
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val sessions = WeakHashMap<Activity, DockSession>()

    @Volatile
    private var lastSlideProgress = 0f

    fun install() {
        installActivityLifecycle()
        installNativeSlideObserver()
    }

    private fun installActivityLifecycle() {
        runCatching {
            val postResume = Activity::class.java.getDeclaredMethod("onPostResume")
                .apply { isAccessible = true }
            module.hook(postResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity?.packageName == AppleMusic653.PACKAGE) {
                        scheduleAttach(activity)
                    }
                    result
                }

            val destroy = Activity::class.java.getDeclaredMethod("onDestroy")
                .apply { isAccessible = true }
            module.hook(destroy)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val activity = chain.thisObject as? Activity
                    activity?.let { removeSession(it) }
                    chain.proceed()
                }

            logger(Log.INFO, "floating dock lifecycle hooks installed", null)
        }.onFailure { error ->
            logger(Log.ERROR, "floating dock lifecycle installation failed", error)
        }
    }

    private fun installNativeSlideObserver() {
        runCatching {
            val slide = AppleMusic653.stackedNavigationSlide(loader)
            module.hook(slide)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val progress = (chain.args.firstOrNull() as? Number)?.toFloat()
                    if (progress != null) {
                        lastSlideProgress = progress.coerceIn(0f, 1f)
                        main.post {
                            synchronized(sessions) {
                                sessions.values.toList().forEach {
                                    it.updateProgress(lastSlideProgress)
                                }
                            }
                        }
                    }
                    result
                }
            logger(
                Log.INFO,
                "floating dock slide observer installed: ${slide.declaringClass.name}#${slide.name}",
                null,
            )
        }.onFailure { error ->
            logger(Log.ERROR, "floating dock slide observer failed", error)
        }
    }

    private fun scheduleAttach(activity: Activity) {
        if (!HookConfigRuntime.current().let { it.enabled && it.floatingBottomBar }) {
            removeSession(activity)
            return
        }

        val decor = activity.window?.decorView ?: return
        RETRY_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                if (!HookConfigRuntime.current().let { it.enabled && it.floatingBottomBar }) {
                    removeSession(activity)
                    return@postDelayed
                }
                attachIfReady(activity)
            }, delay)
        }
    }

    private fun attachIfReady(activity: Activity) {
        val root = find(activity, "bottom_navigation_root_stacked")
            ?: find(activity, "bottom_navigation_root_flat")
            ?: return

        synchronized(sessions) {
            val existing = sessions[activity]
            if (existing?.root === root) {
                existing.updateProgress(lastSlideProgress)
                return
            }
            existing?.restore()

            val layers = listOfNotNull(
                find(activity, "player_top_shadow"),
                find(activity, "background_layers"),
                find(activity, "player_fragments_host"),
                find(activity, "motion_switcher"),
            )

            val session = DockSession(
                activity = activity,
                root = root,
                navFrame = find(activity, "bottom_navigation_tabs_frame"),
                navigation = find(activity, "bottom_navigation"),
                miniRoot = find(activity, "mini_player")
                    ?: find(activity, "mini_player_touch_panel"),
                miniContent = find(activity, "mini_player_content"),
                divider = find(activity, "navigation_tabs_divider"),
                topShadow = find(activity, "nav_tabs_top_shadow"),
                playerLayers = layers,
            )
            sessions[activity] = session
            session.attach()
        }
    }

    private fun removeSession(activity: Activity) {
        synchronized(sessions) {
            sessions.remove(activity)?.restore()
        }
    }

    private fun find(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", AppleMusic653.PACKAGE)
        if (id == 0) return null
        return activity.findViewById(id)
    }

    private fun dockDrawable(activity: Activity): GradientDrawable {
        val surface = resolveSurfaceColor(activity)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(activity.window.decorView, DOCK_RADIUS_DP).toFloat()
            setColor(surface)
            setStroke(
                dp(activity.window.decorView, 0.5f).coerceAtLeast(1),
                if (isLight(surface)) 0x16000000 else 0x20FFFFFF,
            )
        }
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

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }
            .getOrDefault(view.javaClass.simpleName)

    private fun smoothStep(start: Float, end: Float, value: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        private val RETRY_DELAYS_MS = longArrayOf(0L, 80L, 220L, 500L)
        private const val DOCK_SIDE_DP = 10f
        private const val DOCK_BOTTOM_DP = 8f
        private const val DOCK_RADIUS_DP = 28f
        private const val DOCK_ELEVATION_DP = 8f
    }
}
