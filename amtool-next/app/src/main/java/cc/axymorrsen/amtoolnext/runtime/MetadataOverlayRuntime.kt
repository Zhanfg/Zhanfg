package cc.axymorrsen.amtoolnext.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Display-only localized metadata projection.
 *
 * alpha3 owns both the data/model seam and the final visible-row seam for Artist Top Songs.
 * The latter matters because Apple Music's Epoxy/DataBinding pipeline can re-copy the original
 * title after the model getter hooks have already returned.
 *
 * Canonical Apple objects, IDs, playParams and playback fields are never mutated.
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

    private data class TopSongSnapshot(
        val mediaId: String,
        val originalTitle: String?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val state = ConcurrentHashMap<String, State>()

    private val contentTargets =
        ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()
    private val contentNotifyMethods = ConcurrentHashMap<String, Method>()

    private val topSongModels =
        Collections.synchronizedMap(WeakHashMap<Any, TopSongSnapshot>())
    private val topSongRoots =
        ConcurrentHashMap<String, MutableList<WeakReference<View>>>()

    @Volatile
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
                        }?.trim()?.takeIf(String::isNotEmpty)

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

                    val originalTitle = runCatching {
                        surface.titleField.get(model)?.toString()?.trim()
                    }.getOrNull()?.takeIf(String::isNotEmpty)

                    topSongModels[model] = TopSongSnapshot(mediaId, originalTitle)

                    aliasOrRequest(mediaId)?.let { alias ->
                        applyTopSongModelAlias(model, alias)
                    }
                    model
                }

            module.hook(surface.bindMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val model = chain.thisObject
                    if (metadataEnabled() && model != null) {
                        val snapshot = topSongModels[model]
                        val alias = snapshot?.mediaId?.let(::aliasOrRequest)
                        if (alias != null) applyTopSongModelAlias(model, alias)
                    }

                    val result = chain.proceed()

                    if (metadataEnabled() && model != null) {
                        val snapshot = topSongModels[model]
                        val root = rootViewFromHolder(chain.args.getOrNull(1))
                        if (snapshot != null && root != null) {
                            rememberTopSongRoot(snapshot.mediaId, root)
                            aliasOrRequest(snapshot.mediaId)?.let { alias ->
                                applyVisibleTopSongTitle(root, snapshot.originalTitle, alias.title)
                            }
                        }
                    }
                    result
                }

            logger(
                Log.INFO,
                "artist Top Songs alpha3 projection installed builder=" +
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

    private fun aliasOrRequest(mediaId: String): CatalogSideChannel.Alias? {
        return when (val current = state[mediaId] ?: State.Unknown) {
            is State.Hit -> current.alias
            is State.Loading -> null
            is State.Miss -> {
                if (SystemClock.uptimeMillis() >= current.untilUptime) {
                    if (state.remove(mediaId, current)) request(mediaId)
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

        catalog.resolve(mediaId) { alias ->
            val expected = state[mediaId] as? State.Loading
            if (expected?.request != id) return@resolve

            state[mediaId] = if (alias == null) {
                State.Miss(SystemClock.uptimeMillis() + MISS_TTL_MS)
            } else {
                State.Hit(alias)
            }

            if (alias == null) {
                logger(Log.INFO, "localized metadata miss id=$mediaId", null)
                return@resolve
            }

            notifyContentTargets(mediaId)
            refreshTopSongTargets(mediaId, alias)
            logger(
                Log.INFO,
                "localized metadata applied id=$mediaId title=${alias.title}",
                null,
            )
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
            while (refs.size > MAX_CONTENT_TARGETS) refs.removeAt(0)
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

    private fun applyTopSongModelAlias(
        model: Any,
        alias: CatalogSideChannel.Alias,
    ): Boolean {
        val surface = topSongSurface ?: return false
        val title = alias.title.trim()
        if (title.isEmpty()) return false

        return runCatching {
            if (surface.titleField.get(model)?.toString()?.trim() == title) {
                false
            } else {
                surface.titleField.set(model, title)
                true
            }
        }.onFailure { error ->
            logger(Log.ERROR, "artist Top Songs model title projection failed", error)
        }.getOrDefault(false)
    }

    private fun rememberTopSongRoot(mediaId: String, root: View) {
        val refs = topSongRoots.computeIfAbsent(mediaId) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(refs) {
            refs.removeAll { it.get() == null || it.get() === root }
            refs += WeakReference(root)
            while (refs.size > MAX_VISIBLE_ROOTS) refs.removeAt(0)
        }
    }

    private fun refreshTopSongTargets(
        mediaId: String,
        alias: CatalogSideChannel.Alias,
    ) {
        main.post {
            synchronized(topSongModels) {
                topSongModels.entries.forEach { (model, snapshot) ->
                    if (snapshot.mediaId == mediaId) {
                        applyTopSongModelAlias(model, alias)
                    }
                }
            }

            val roots = topSongRoots[mediaId]
            if (roots != null) {
                synchronized(roots) {
                    roots.removeAll { ref ->
                        val root = ref.get()
                        if (root == null) {
                            true
                        } else {
                            val originalTitle = synchronized(topSongModels) {
                                topSongModels.values
                                    .firstOrNull { it.mediaId == mediaId }
                                    ?.originalTitle
                            }
                            applyVisibleTopSongTitle(root, originalTitle, alias.title)
                            false
                        }
                    }
                }
            }
        }
    }

    /**
     * Final visual fallback for the 6.5.3 Epoxy/DataBinding row. This only touches text equal to
     * the captured original song title inside the already-associated Top Songs holder.
     */
    private fun applyVisibleTopSongTitle(
        root: View,
        originalTitle: String?,
        localizedTitle: String,
    ): Boolean {
        val original = originalTitle?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val localized = localizedTitle.trim().takeIf(String::isNotEmpty) ?: return false
        if (original == localized) return false

        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_VIEW_SCAN) {
            val view = queue.removeFirst()
            visited++

            if (view is TextView && view.text?.toString()?.trim() == original) {
                view.text = localized
                return true
            }

            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    queue.addLast(view.getChildAt(index))
                }
            }
        }
        return false
    }

    private fun rootViewFromHolder(holder: Any?): View? {
        holder ?: return null
        if (holder is View) return holder

        val preferredMethods = listOf("getItemView", "getRoot", "getView")
        preferredMethods.forEach { name ->
            findZeroArgMethod(holder.javaClass, name)?.let { method ->
                val value = runCatching { method.invoke(holder) }.getOrNull()
                if (value is View) return value
                value?.let(::viewFromBindingLike)?.let { return it }
            }
        }

        var type: Class<*>? = holder.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                runCatching {
                    field.isAccessible = true
                    val value = field.get(holder)
                    when (value) {
                        is View -> return value
                        null -> Unit
                        else -> viewFromBindingLike(value)?.let { return it }
                    }
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun viewFromBindingLike(instance: Any): View? {
        findZeroArgMethod(instance.javaClass, "getRoot")?.let { method ->
            return runCatching { method.invoke(instance) as? View }.getOrNull()
        }
        return null
    }

    private fun findZeroArgMethod(type: Class<*>, name: String): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        private const val MISS_TTL_MS = 2 * 60 * 1000L
        private const val MAX_CONTENT_TARGETS = 24
        private const val MAX_VISIBLE_ROOTS = 12
        private const val MAX_VIEW_SCAN = 96
    }
}
