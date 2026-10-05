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
 * Lightweight floating bottom chrome for Apple Music 6.5.3.
 *
 * alpha2 restyled the navigation frame and mini-player independently on every focus event.
 * That caused three visible problems on ColorOS:
 * - two rounded cards visually overlapped;
 * - the stock blue/root background remained visible behind them;
 * - repeated margin/background writes forced layout + redraw and caused jank.
 *
 * alpha3 treats the native mini-player + navigation as one visual shell split into two segments.
 * The native geometry, player peek height, gestures and BottomSheetBehavior stay untouched.
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
        val visibility: Int,
        val leftMargin: Int?,
        val topMargin: Int?,
        val rightMargin: Int?,
        val bottomMargin: Int?,
    )

    private data class Session(
        val activity: Activity,
        val root: View,
        val navFrame: View,
        val navigation: View?,
        val miniRoot: View?,
        val miniSurface: View?,
        val divider: View?,
        val topShadow: View?,
        val states: MutableMap<View, ViewState>,
        var lastMiniVisible: Boolean? = null,
        var enabled: Boolean = false,
        var layoutListener: View.OnLayoutChangeListener? = null,
    )

    private val sessions = WeakHashMap<Activity, Session>()
    private val retryCount = WeakHashMap<Activity, Int>()

    fun install() {
        runCatching {
            val postResume = Activity::class.java.getDeclaredMethod("onPostResume")
                .apply { isAccessible = true }

            module.hook(postResume)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val activity = chain.thisObject as? Activity
                    if (activity?.packageName == AppleMusic653.PACKAGE) {
                        activity.window.decorView.post { attachOrUpdate(activity) }
                    }
                    result
                }

            logger(Log.INFO, "floating bottom chrome alpha3 hook installed", null)
        }.onFailure { error ->
            logger(Log.ERROR, "floating bottom chrome installation failed", error)
        }
    }

    private fun attachOrUpdate(activity: Activity) {
        val existing = synchronized(sessions) { sessions[activity] }
        if (existing != null && existing.root.isAttachedToWindow) {
            applyEnabledState(existing)
            return
        }

        val root = find(activity, "bottom_navigation_root_stacked")
            ?: find(activity, "bottom_navigation_root_flat")
        val navFrame = find(activity, "bottom_navigation_tabs_frame")
            ?: find(activity, "bottom_navigation")

        if (root == null || navFrame == null) {
            val attempt = (retryCount[activity] ?: 0) + 1
            retryCount[activity] = attempt
            if (attempt <= MAX_ATTACH_RETRIES) {
                activity.window.decorView.postDelayed(
                    { attachOrUpdate(activity) },
                    ATTACH_RETRY_MS,
                )
            }
            return
        }

        retryCount.remove(activity)

        val navigation = find(activity, "bottom_navigation")
        val miniRoot = find(activity, "mini_player")
            ?: find(activity, "mini_player_touch_panel")
        val miniSurface = find(activity, "mini_player_content") ?: miniRoot
        val divider = find(activity, "navigation_tabs_divider")
        val topShadow = find(activity, "nav_tabs_top_shadow")

        val states = LinkedHashMap<View, ViewState>()
        listOfNotNull(
            root,
            navFrame,
            navigation,
            miniRoot,
            miniSurface,
            divider,
            topShadow,
        ).distinct().forEach { view ->
            states[view] = capture(view)
        }

        val session = Session(
            activity = activity,
            root = root,
            navFrame = navFrame,
            navigation = navigation,
            miniRoot = miniRoot,
            miniSurface = miniSurface,
            divider = divider,
            topShadow = topShadow,
            states = states,
        )

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!session.enabled) return@OnLayoutChangeListener
            val visible = miniVisible(session)
            if (visible != session.lastMiniVisible) {
                session.lastMiniVisible = visible
                applySegmentShapes(session, visible)
            }
        }
        session.layoutListener = layoutListener
        miniRoot?.addOnLayoutChangeListener(layoutListener)

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                session.layoutListener?.let { listener ->
                    session.miniRoot?.removeOnLayoutChangeListener(listener)
                }
                synchronized(sessions) {
                    if (sessions[activity] === session) sessions.remove(activity)
                }
            }
        })

        synchronized(sessions) {
            sessions[activity] = session
        }
        applyEnabledState(session)
    }

    private fun applyEnabledState(session: Session) {
        val shouldEnable = HookConfigRuntime.current().let {
            it.enabled && it.floatingBottomBar
        }

        if (!shouldEnable) {
            if (session.enabled) restore(session)
            session.enabled = false
            session.lastMiniVisible = null
            return
        }

        if (!session.enabled) {
            applyStaticChrome(session)
            session.enabled = true
        }

        val visible = miniVisible(session)
        if (visible != session.lastMiniVisible) {
            session.lastMiniVisible = visible
            applySegmentShapes(session, visible)
        }
    }

    /**
     * Static properties are written once per attached host view, not on focus/layout frames.
     */
    private fun applyStaticChrome(session: Session) {
        val density = session.root.resources.displayMetrics.density
        val horizontal = (HORIZONTAL_MARGIN_DP * density).roundToInt()
        val bottom = (BOTTOM_MARGIN_DP * density).roundToInt()

        // Remove the stock full-width backing layers. These were the blue/grey slab visible
        // behind alpha2's independent white capsules.
        listOfNotNull(
            session.root,
            session.miniRoot,
            session.navigation,
        ).distinct().forEach { view ->
            view.background = null
        }

        if (session.root is ViewGroup) {
            session.root.clipChildren = false
            session.root.clipToPadding = false
        }
        if (session.miniRoot is ViewGroup) {
            session.miniRoot.clipChildren = false
            session.miniRoot.clipToPadding = false
        }

        setHorizontalMargins(
            view = session.navFrame,
            horizontal = horizontal,
            top = 0,
            bottom = bottom,
        )

        session.miniSurface?.let { mini ->
            setHorizontalMargins(
                view = mini,
                horizontal = horizontal,
                top = (MINI_TOP_MARGIN_DP * density).roundToInt(),
                bottom = 0,
            )
        }

        listOfNotNull(session.divider, session.topShadow).forEach { seam ->
            seam.visibility = View.GONE
        }

        session.navFrame.elevation = ELEVATION_DP * density
        session.navFrame.translationZ = 0f
        session.navFrame.clipToOutline = true

        session.miniSurface?.let { mini ->
            mini.elevation = ELEVATION_DP * density
            mini.translationZ = 0f
            mini.clipToOutline = true
        }
    }

    private fun applySegmentShapes(session: Session, miniVisible: Boolean) {
        val density = session.root.resources.displayMetrics.density
        val color = resolveSurfaceColor(session.activity)
        val stroke = if (isLight(color)) 0x14000000 else 0x20FFFFFF

        session.navFrame.background = roundedSurface(
            color = color,
            stroke = stroke,
            density = density,
            topRadiusDp = if (miniVisible) 0f else NAV_RADIUS_DP,
            bottomRadiusDp = NAV_RADIUS_DP,
        )

        session.miniSurface?.let { mini ->
            if (miniVisible) {
                mini.background = roundedSurface(
                    color = color,
                    stroke = stroke,
                    density = density,
                    topRadiusDp = MINI_RADIUS_DP,
                    bottomRadiusDp = 0f,
                )
                mini.alpha = 1f
            } else {
                // Keep the host's visibility semantics; just remove stale material.
                mini.background = null
            }
        }
    }

    private fun miniVisible(session: Session): Boolean {
        val mini = session.miniRoot ?: session.miniSurface ?: return false
        return mini.visibility == View.VISIBLE &&
            mini.alpha > 0.01f &&
            mini.height > 0 &&
            mini.isShown
    }

    private fun setHorizontalMargins(
        view: View,
        horizontal: Int,
        top: Int,
        bottom: Int,
    ) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val left = horizontal
        val right = horizontal
        if (
            params.leftMargin == left &&
            params.rightMargin == right &&
            params.topMargin == top &&
            params.bottomMargin == bottom
        ) {
            return
        }
        params.setMargins(left, top, right, bottom)
        view.layoutParams = params
    }

    private fun roundedSurface(
        color: Int,
        stroke: Int,
        density: Float,
        topRadiusDp: Float,
        bottomRadiusDp: Float,
    ): Drawable {
        val top = topRadiusDp * density
        val bottom = bottomRadiusDp * density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                top, top,
                top, top,
                bottom, bottom,
                bottom, bottom,
            )
            setColor(color)
            setStroke(
                (0.5f * density).roundToInt().coerceAtLeast(1),
                stroke,
            )
        }
    }

    private fun capture(view: View): ViewState {
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        return ViewState(
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

    private fun restore(session: Session) {
        session.states.forEach { (view, state) ->
            runCatching {
                view.background = state.background
                view.elevation = state.elevation
                view.translationZ = state.translationZ
                view.clipToOutline = state.clipToOutline
                view.alpha = state.alpha
                view.visibility = state.visibility

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
        val base = when {
            !resolved -> Color.WHITE
            value.resourceId != 0 -> runCatching {
                activity.getColor(value.resourceId)
            }.getOrDefault(value.data)
            else -> value.data
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

    companion object {
        private const val HORIZONTAL_MARGIN_DP = 12f
        private const val BOTTOM_MARGIN_DP = 8f
        private const val MINI_TOP_MARGIN_DP = 4f
        private const val NAV_RADIUS_DP = 28f
        private const val MINI_RADIUS_DP = 22f
        private const val ELEVATION_DP = 5f

        private const val MAX_ATTACH_RETRIES = 6
        private const val ATTACH_RETRY_MS = 120L
    }
}
