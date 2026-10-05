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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val targets = ConcurrentHashMap<String, MutableList<WeakReference<Any>>>()

    fun install() {
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
                        if (!HookConfigRuntime.current().let { it.enabled && it.chineseMetadata }) {
                            return@intercept original
                        }

                        val item = chain.thisObject ?: return@intercept original
                        val mediaId = canonicalId(item, identity) ?: return@intercept original
                        remember(mediaId, item)

                        val alias = when (val current = state[mediaId] ?: State.Unknown) {
                            is State.Hit -> current.alias
                            is State.Miss -> {
                                if (SystemClock.uptimeMillis() >= current.untilUptime) {
                                    state.remove(mediaId, current)
                                    request(mediaId, notify)
                                }
                                null
                            }
                            State.Unknown -> {
                                request(mediaId, notify)
                                null
                            }
                            is State.Loading -> null
                        }

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
            "metadata overlay installed classes=${classes.size} methods=${hooked.size}",
            null,
        )
    }

    private fun request(mediaId: String, notify: Method?) {
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
            if (alias != null) notifyTargets(mediaId, notify)
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

    private fun remember(mediaId: String, item: Any) {
        val refs = targets.computeIfAbsent(mediaId) {
            Collections.synchronizedList(mutableListOf())
        }
        synchronized(refs) {
            refs.removeAll { it.get() == null || it.get() === item }
            refs += WeakReference(item)
            while (refs.size > MAX_TARGETS) refs.removeAt(0)
        }
    }

    private fun notifyTargets(mediaId: String, notify: Method?) {
        notify ?: return
        val refs = targets[mediaId] ?: return
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

    companion object {
        private const val MISS_TTL_MS = 10 * 60 * 1000L
        private const val MAX_TARGETS = 24
    }
}
