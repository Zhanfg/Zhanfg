package cc.axymorrsen.amtoolnext.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Display-only localized metadata projection.
 *
 * V3 alpha1 only hooked BaseContentItem-style getters. Apple Music 6.5.3's artist Top Songs
 * page has already copied those values into an Epoxy model (music.e1) before render, so getter
 * replacement alone cannot change the visible row. alpha2 owns that exact consumption seam too:
 * the builder identifies the MediaEntity, and the binder projects the resolved alias into e1.L.
 *
 * Canonical Apple IDs, playParams, MediaEntity attributes and playback objects are never mutated.
 */
internal class MetadataOverlayRuntime(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val catalog: CatalogSideChannel,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    private sealed interface State {
        data object Unknown : State
        data class Loading(val request: Long) : State
        data class Hit(val alias: CatalogSideChannel.Alias) : State
        data class Miss(val untilUptime: Long) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val state = ConcurrentHashMap<String, State>()

    private val contentTargets =
        ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()
    private val contentNotifyMethods = ConcurrentHashMap<String, Method>()
    private val isrcHints = ConcurrentHashMap<String, String>()

    private val topSongModels =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val topSongControllers =
        ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()
    private val pendingControllerRebuilds =
        Collections.synchronizedMap(WeakHashMap<Any, Runnable>())

    private var topSongSurface: AppleMusic653.ArtistTopSongSurface? = null

    fun install() {
        installContentItemProjection()
        installArtistTopSongsProjection()
    }

    private fun installContentItemProjection() {
        val classes = AppleMusic653.contentItemRuntimeClasses(loader)
        val hooked = HashSet<Method>()

        classes.forEach { type ->
            val identity = AppleMusic653.contentItemIdentityMethods(type)
            val notify = AppleMusic653.contentItemNotifyChange(type)

            AppleMusic653.contentItemGetterMethods(type).forEach getterLoop@{ (name, method) ->
                if (!hooked.add(method)) return@getterLoop
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val original = chain.proceed()
                        if (!metadataEnabled()) return@intercept original

                        val item = chain.thisObject ?: return@intercept original
                        val mediaId = canonicalId(item, identity) ?: return@intercept original
                        rememberContentTarget(mediaId, item)
                        if (notify != null) contentNotifyMethods[mediaId] = notify

                        val alias = aliasOrRequest(mediaId)
                        val replacement = when (name) {
                            "getTitle", "getNowPlayingTitle" -> alias?.title
                            "getArtistName", "getNowPlayingSubtitle" -> alias?.artist
                            "getCollectionName" -> alias?.album
                            else -> null
                        }?.takeIf(String::isNotBlank)

                        replacement ?: original
                    }
            }
        }

        logger(
            Log.INFO,
            "content-item metadata projection installed classes=${classes.size} methods=${hooked.size}",
            null,
        )
    }

    /**
     * Exact 6.5.3 artist-page consumption path:
     *
     * BaseProfileEpoxyController#addSwipingChartItemA2("top-songs", MediaEntity, ...)
     *      -> com.apple.android.music.e1
     * e1#L = title
     * e1#a(position, holder) = visible bind
     *
     * We never alter MediaEntity itself. The Epoxy model is a disposable display projection.
     */
    private fun installArtistTopSongsProjection() {
        runCatching {
            val surface = AppleMusic653.artistTopSongSurface(loader)
            topSongSurface = surface

            module.hook(surface.buildMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val model = chain.proceed()
                    if (!metadataEnabled() || model == null) return@intercept model
                    if (chain.args.getOrNull(0)?.toString() != "top-songs") {
                        return@intercept model
                    }

                    val entity = chain.args.getOrNull(1) ?: return@intercept model
                    val mediaId = AppleMusic653.mediaEntityCatalogId(entity)
                        ?: return@intercept model

                    topSongModels[model] = mediaId
                    AppleMusic653.mediaEntityIsrc(entity)?.let { isrcHints[mediaId] = it }
                    chain.thisObject?.let { rememberTopSongController(mediaId, it) }

                    aliasOrRequest(mediaId)?.let { alias ->
                        applyTopSongAlias(model, alias)
                    }
                    model
                }

            module.hook(surface.bindMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (metadataEnabled()) {
                        val model = chain.thisObject
                        val mediaId = model?.let { topSongModels[it] }
                        if (model != null && mediaId != null) {
                            aliasOrRequest(mediaId)?.let { alias ->
                                applyTopSongAlias(model, alias)
                            }
                        }
                    }
                    chain.proceed()
                }

            logger(
                Log.INFO,
                "artist Top Songs projection installed builder=" +
                    "${surface.buildMethod.declaringClass.name}#${surface.buildMethod.name}, " +
                    "binder=${surface.bindMethod.declaringClass.name}#${surface.bindMethod.name}",
                null,
            )
        }.onFailure { error ->
            logger(Log.ERROR, "artist Top Songs projection failed", error)
        }
    }

    private fun metadataEnabled(): Boolean =
        HookConfigRuntime.current().let { it.enabled && it.chineseMetadata }

    private fun aliasOrRequest(
        mediaId: String,
    ): CatalogSideChannel.Alias? {
        return when (val current = state[mediaId] ?: State.Unknown) {
            is State.Hit -> current.alias
            is State.Loading -> null
            is State.Miss -> {
                if (SystemClock.uptimeMillis() >= current.untilUptime) {
                    state.remove(mediaId, current)
                    request(mediaId)
                }
                null
            }
            State.Unknown -> {
                request(mediaId)
                null
            }
        }
    }

    private fun request(mediaId: String) {
        val id = sequence.incrementAndGet()
        if (state.putIfAbsent(mediaId, State.Loading(id)) != null) return

        catalog.resolve(mediaId, isrcHints[mediaId]) { alias ->
            val expected = state[mediaId] as? State.Loading
            if (expected?.request != id) return@resolve

            state[mediaId] = if (alias == null) {
                State.Miss(SystemClock.uptimeMillis() + MISS_TTL_MS)
            } else {
                State.Hit(alias)
            }

            if (alias != null) {
                notifyContentTargets(mediaId)
                refreshTopSongTargets(mediaId, alias)
                logger(
                    Log.INFO,
                    "localized metadata resolved id=$mediaId title=${alias.title}",
                    null,
                )
            } else {
                logger(Log.INFO, "localized metadata miss id=$mediaId", null)
            }
        }
    }

    private fun canonicalId(item: Any, methods: Map<String, Method>): String? {
        sequenceOf("getSubscriptionStoreId", "getId").forEach { name ->
            val value = runCatching { methods[name]?.invoke(item)?.toString() }
                .getOrNull()
                ?.trim()
            if (!value.isNullOrEmpty() && value.all(Char::isDigit)) return value
        }
        return null
    }

    private fun rememberContentTarget(mediaId: String, item: Any) {
        val refs = contentTargets.computeIfAbsent(mediaId) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(refs) {
            refs.removeAll { it.get() == null || it.get() === item }
            refs += WeakReference(item)
            while (refs.size > MAX_TARGETS) refs.removeAt(0)
        }
    }

    private fun rememberTopSongController(mediaId: String, controller: Any) {
        val refs = topSongControllers.computeIfAbsent(mediaId) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(refs) {
            refs.removeAll { it.get() == null || it.get() === controller }
            refs += WeakReference(controller)
            while (refs.size > MAX_CONTROLLERS) refs.removeAt(0)
        }
    }

    private fun notifyContentTargets(mediaId: String) {
        val notify = contentNotifyMethods[mediaId] ?: return
        val refs = contentTargets[mediaId] ?: return
        main.post {
            synchronized(refs) {
                refs.removeAll { ref ->
                    val item = ref.get()
                    if (item == null) {
                        true
                    } else {
                        runCatching { notify.invoke(item) }
                        false
                    }
                }
            }
        }
    }

    private fun applyTopSongAlias(
        model: Any,
        alias: CatalogSideChannel.Alias,
    ): Boolean {
        val surface = topSongSurface ?: return false
        val title = alias.title.trim()
        if (title.isEmpty()) return false
        return runCatching {
            if (surface.titleField.get(model)?.toString() != title) {
                surface.titleField.set(model, title)
                true
            } else {
                false
            }
        }.onFailure { error ->
            logger(Log.ERROR, "artist Top Songs title projection failed", error)
        }.getOrDefault(false)
    }

    private fun refreshTopSongTargets(
        mediaId: String,
        alias: CatalogSideChannel.Alias,
    ) {
        main.post {
            var changed = false
            synchronized(topSongModels) {
                topSongModels.entries.forEach { (model, id) ->
                    if (id == mediaId) {
                        changed = applyTopSongAlias(model, alias) || changed
                    }
                }
            }

            val controllers = topSongControllers[mediaId]
            if (controllers != null) {
                synchronized(controllers) {
                    controllers.removeAll { ref ->
                        val controller = ref.get()
                        if (controller == null) {
                            true
                        } else {
                            scheduleControllerRebuild(controller)
                            false
                        }
                    }
                }
            }

            if (changed) {
                logger(Log.INFO, "artist Top Songs rebound id=$mediaId title=${alias.title}", null)
            }
        }
    }

    private fun scheduleControllerRebuild(controller: Any) {
        synchronized(pendingControllerRebuilds) {
            if (pendingControllerRebuilds.containsKey(controller)) return
            val task = Runnable {
                synchronized(pendingControllerRebuilds) {
                    pendingControllerRebuilds.remove(controller)
                }
                requestModelBuild(controller)
            }
            pendingControllerRebuilds[controller] = task
            main.postDelayed(task, 32L)
        }
    }

    private fun requestModelBuild(controller: Any) {
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { candidate ->
                candidate.name == "requestModelBuild" && candidate.parameterCount == 0
            }
            if (method != null) {
                runCatching {
                    method.isAccessible = true
                    method.invoke(controller)
                }.onFailure { error ->
                    logger(Log.ERROR, "artist controller requestModelBuild failed", error)
                }
                return
            }
            type = type.superclass
        }
    }

    companion object {
        private const val MISS_TTL_MS = 10 * 60 * 1000L
        private const val MAX_TARGETS = 24
        private const val MAX_CONTROLLERS = 8
    }
}
