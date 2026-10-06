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
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Localized metadata projection for Apple Music 6.5.3.
 *
 * The visible Artist/Top Songs row is built synchronously from MediaEntity attributes. We do not
 * scan holder view trees or intercept every bind anymore. When an alias is cached we temporarily
 * project it into MediaEntity attributes for exactly one model-build call, then restore the
 * canonical object immediately. On an async cache fill we debounce one Epoxy rebuild per
 * controller, so four songs produce one rebuild instead of four full-page rebuilds.
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

    private data class AttributeSnapshot(
        val attributes: Any,
        val name: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val state = ConcurrentHashMap<String, State>()

    private val contentTargets =
        ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()
    private val contentNotifyMethods = ConcurrentHashMap<String, Method>()

    private val topSongControllers =
        ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()
    private val topSongModels =
        Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val pendingControllerRebuilds =
        Collections.synchronizedMap(WeakHashMap<Any, Runnable>())

    private data class BindingBridge(
        val bindingClass: Class<*>,
        val setVariable: Method,
        val invalidateAll: Method?,
        val executePending: Method?,
        val titleVariableId: Int,
    )

    private val bindingBridge: BindingBridge? by lazy(::resolveBindingBridge)

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

                        val alias = aliasOrRequest(mediaId, isrcHint = null)
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
                    if (!metadataEnabled()) return@intercept chain.proceed()
                    if (chain.args.getOrNull(0)?.toString() != "top-songs") {
                        return@intercept chain.proceed()
                    }

                    val entity = chain.args.getOrNull(1) ?: return@intercept chain.proceed()
                    RuntimeSignal.once("top-songs-hit", "AMTool：Top Songs 显示链已命中")
                    val mediaId = AppleMusic653.mediaEntityCatalogId(entity)
                        ?: run {
                            RuntimeSignal.once(
                                "top-songs-no-id",
                                "AMTool：Top Songs 命中，但取不到曲目 ID",
                            )
                            return@intercept chain.proceed()
                        }
                    val isrc = AppleMusic653.mediaEntityIsrc(entity)
                    chain.thisObject?.let { rememberTopSongController(mediaId, it) }

                    val alias = aliasOrRequest(mediaId, isrc)
                    val snapshot = alias?.let { projectEntity(entity, it) }
                    val model = try {
                        chain.proceed()
                    } finally {
                        snapshot?.let(::restoreEntity)
                    }

                    if (model != null) {
                        topSongModels[model] = mediaId
                        if (alias != null) {
                            applyTopSongModelAlias(model, alias)
                        }
                    }
                    model
                }

            // This is the actual last model seam before DataBinding. Keep the hook tiny:
            // one WeakHashMap lookup + cached alias lookup, no view-tree scan and no rebuild.
            module.hook(surface.bindMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val model = chain.thisObject
                    if (metadataEnabled() && model != null) {
                        val mediaId = topSongModels[model]
                        val alias = mediaId?.let(::cachedAlias)
                        if (alias != null) {
                            applyTopSongModelAlias(model, alias)
                            applyTopSongBindingAlias(chain.args.getOrNull(1), alias)
                        }
                    }
                    chain.proceed()
                }

            RuntimeSignal.once("top-songs-installed", "AMTool：Top Songs Hook 已安装")
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

    private fun cachedAlias(mediaId: String): CatalogSideChannel.Alias? =
        (state[mediaId] as? State.Hit)?.alias

    private fun applyTopSongBindingAlias(
        holder: Any?,
        alias: CatalogSideChannel.Alias,
    ) {
        holder ?: return
        val bridge = bindingBridge ?: return
        val binding = bindingFromHolder(holder, bridge.bindingClass) ?: return
        val title = alias.title.trim().takeIf(String::isNotEmpty) ?: return

        runCatching {
            val applied = bridge.setVariable.invoke(
                binding,
                bridge.titleVariableId,
                title,
            ) == true
            if (applied) {
                bridge.invalidateAll?.invoke(binding)
                bridge.executePending?.invoke(binding)
                RuntimeSignal.once(
                    "top-songs-binding",
                    "AMTool：Top Songs DataBinding 中文标题已写入",
                )
            }
        }.onFailure { error ->
            logger(Log.ERROR, "artist Top Songs DataBinding projection failed", error)
        }
    }

    private fun resolveBindingBridge(): BindingBridge? = runCatching {
        val bindingClass = loader.loadClass("androidx.databinding.ViewDataBinding")
        val setVariable = findMethod(bindingClass, "h0", 2) { method ->
            method.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: error("ViewDataBinding#h0(int,Object) unavailable")
        val invalidate = findMethod(bindingClass, "A", 0)
        val execute = findMethod(bindingClass, "n", 0)

        val br = loader.loadClass("com.apple.android.music.playback.BR")
        val titleField: Field = br.getDeclaredField("title").apply { isAccessible = true }
        val titleVariableId = titleField.getInt(null)

        BindingBridge(
            bindingClass = bindingClass,
            setVariable = setVariable,
            invalidateAll = invalidate,
            executePending = execute,
            titleVariableId = titleVariableId,
        )
    }.onFailure { error ->
        logger(Log.ERROR, "Top Songs DataBinding bridge unavailable", error)
    }.getOrNull()

    private fun bindingFromHolder(holder: Any, bindingClass: Class<*>): Any? =
        generateSequence(holder.javaClass) { it.superclass }
            .flatMap { type -> type.declaredFields.asSequence() }
            .firstOrNull { field -> bindingClass.isAssignableFrom(field.type) }
            ?.let { field ->
                runCatching {
                    field.isAccessible = true
                    field.get(holder)
                }.getOrNull()
            }

    private fun findMethod(
        type: Class<*>,
        name: String,
        count: Int,
        extra: (Method) -> Boolean = { true },
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == count &&
                    extra(method)
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun metadataEnabled(): Boolean =
        HookConfigRuntime.current().let { it.enabled && it.chineseMetadata }

    private fun aliasOrRequest(
        mediaId: String,
        isrcHint: String?,
    ): CatalogSideChannel.Alias? {
        return when (val current = state[mediaId] ?: State.Unknown) {
            is State.Hit -> current.alias
            is State.Loading -> null
            is State.Miss -> {
                if (SystemClock.uptimeMillis() >= current.untilUptime) {
                    if (state.remove(mediaId, current)) request(mediaId, isrcHint)
                }
                null
            }
            State.Unknown -> {
                request(mediaId, isrcHint)
                null
            }
        }
    }

    private fun request(mediaId: String, isrcHint: String?) {
        val id = sequence.incrementAndGet()
        if (state.putIfAbsent(mediaId, State.Loading(id)) != null) return

        catalog.resolve(mediaId, isrcHint) { alias ->
            val expected = state[mediaId] as? State.Loading
            if (expected?.request != id) return@resolve

            state[mediaId] = if (alias == null) {
                State.Miss(SystemClock.uptimeMillis() + MISS_TTL_MS)
            } else {
                State.Hit(alias)
            }

            if (alias == null) {
                RuntimeSignal.once(
                    "metadata-miss",
                    "AMTool：中文 metadata 请求已执行，但没有返回中文曲名",
                )
                logger(
                    Log.INFO,
                    "localized metadata miss id=$mediaId isrc=${isrcHint ?: "unknown"}",
                    null,
                )
                return@resolve
            }

            RuntimeSignal.once(
                "metadata-hit",
                "AMTool：中文曲名已命中：${alias.title.take(24)}",
            )
            notifyContentTargets(mediaId)
            refreshTopSongControllers(mediaId)
            logger(
                Log.INFO,
                "localized metadata resolved id=$mediaId title=${alias.title}",
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

    private fun refreshTopSongControllers(mediaId: String) {
        val refs = topSongControllers[mediaId] ?: return
        main.post {
            synchronized(refs) {
                refs.removeAll { ref ->
                    val controller = ref.get()
                    if (controller == null) {
                        true
                    } else {
                        scheduleModelBuild(controller)
                        false
                    }
                }
            }
        }
    }

    private fun scheduleModelBuild(controller: Any) {
        val next = Runnable {
            synchronized(pendingControllerRebuilds) {
                pendingControllerRebuilds.remove(controller)
            }
            requestModelBuild(controller)
        }

        synchronized(pendingControllerRebuilds) {
            pendingControllerRebuilds.remove(controller)?.let(main::removeCallbacks)
            pendingControllerRebuilds[controller] = next
        }
        main.postDelayed(next, MODEL_REBUILD_DEBOUNCE_MS)
    }

    private fun requestModelBuild(controller: Any) {
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { method ->
                method.name == "requestModelBuild" && method.parameterCount == 0
            }?.let { method ->
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

    private fun projectEntity(
        entity: Any,
        alias: CatalogSideChannel.Alias,
    ): AttributeSnapshot? {
        val attributes = AppleMusic653.mediaEntityAttributes(entity) ?: return null
        val originalName = callString(attributes, "getName")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val localizedName = alias.title.trim().takeIf(String::isNotEmpty)
            ?: return null
        if (!setString(attributes, "setName", localizedName)) return null

        return AttributeSnapshot(
            attributes = attributes,
            name = originalName,
        )
    }

    private fun restoreEntity(snapshot: AttributeSnapshot) {
        setString(snapshot.attributes, "setName", snapshot.name)
    }

    private fun applyTopSongModelAlias(
        model: Any,
        alias: CatalogSideChannel.Alias,
    ): Boolean {
        val surface = topSongSurface ?: return false
        val title = alias.title.trim().takeIf(String::isNotEmpty) ?: return false

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

    private fun callString(instance: Any, name: String): String? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == 0
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(instance) as? String
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun setString(instance: Any, name: String, value: String?): Boolean {
        val clean = value?.trim()?.takeIf(String::isNotEmpty) ?: return false
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(instance, clean)
                    true
                }.getOrDefault(false)
            }
            type = type.superclass
        }
        return false
    }

    companion object {
        private const val MISS_TTL_MS = 2 * 60 * 1000L
        private const val MAX_CONTENT_TARGETS = 24
        private const val MAX_CONTROLLERS = 8
        private const val MODEL_REBUILD_DEBOUNCE_MS = 96L
    }
}
