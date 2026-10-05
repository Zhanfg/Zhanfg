package cc.axymorrsen.amtoolnext.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Module-owned localized Apple catalog lane.
 *
 * alpha2 did two requests per visible song (Türkiye id -> ISRC -> CN), even though Apple Music's
 * own 6.5.3 localization path resolves the same catalog id directly in the configured storefront.
 * Besides unnecessary latency, a failed account-side ISRC lookup prevented any Chinese projection.
 *
 * alpha3 batches visible ids and asks CN/zh-CN directly first. ISRC is only a miss fallback. Artist
 * Top Songs can supply its already-loaded MediaEntity ISRC, avoiding another account request.
 */
internal class CatalogSideChannel(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    data class Alias(
        val title: String,
        val artist: String,
        val album: String,
    )

    private data class Pending(
        val mediaId: String,
        var isrcHint: String?,
        val callbacks: MutableList<(Alias?) -> Unit> = mutableListOf(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val access by lazy { AppleMusic653.mediaApiAccess(loader) }

    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private var flushScheduled = false

    fun installRouter() {
        val methods = AppleMusic653.catalogRequestExecutors(loader)
        require(methods.isNotEmpty()) { "6.5.3 catalog executors unavailable" }
        methods.forEach(::installExecutor)
        logger(Log.INFO, "catalog side-channel alpha3 router installed count=${methods.size}", null)
    }

    fun resolve(mediaId: String, callback: (Alias?) -> Unit) {
        resolve(mediaId, isrcHint = null, callback = callback)
    }

    fun resolve(
        mediaId: String,
        isrcHint: String?,
        callback: (Alias?) -> Unit,
    ) {
        val normalizedId = mediaId.trim()
        if (normalizedId.isEmpty() || !normalizedId.all(Char::isDigit)) {
            callback(null)
            return
        }

        var shouldSchedule = false
        synchronized(pendingLock) {
            val request = pending.getOrPut(normalizedId) { Pending(normalizedId, null) }
            val normalizedIsrc = isrcHint?.trim()?.takeIf(String::isNotEmpty)
            if (request.isrcHint == null && normalizedIsrc != null) {
                request.isrcHint = normalizedIsrc
            }
            request.callbacks += callback
            if (!flushScheduled) {
                flushScheduled = true
                shouldSchedule = true
            }
        }
        if (shouldSchedule) main.postDelayed(::flushBatch, BATCH_DELAY_MS)
    }

    private fun flushBatch() {
        val batch = synchronized(pendingLock) {
            val values = pending.values.take(MAX_BATCH)
            values.forEach { pending.remove(it.mediaId) }
            flushScheduled = pending.isNotEmpty()
            values
        }
        if (batch.isEmpty()) return

        if (synchronized(pendingLock) { flushScheduled }) {
            main.postDelayed(::flushBatch, BATCH_DELAY_MS)
        }

        val ids = batch.map(Pending::mediaId)
        query(
            path = "songs",
            query = linkedMapOf(
                "ids" to ids.joinToString(","),
                "l" to LANGUAGE,
                "platform" to "android",
                "include[songs]" to "artists",
                TOKEN to token("ids", ids.joinToString("-")),
            ),
            localized = true,
        ) { response ->
            val localized = catalogEntities(response)
                .mapNotNull { entity ->
                    val id = catalogId(entity) ?: return@mapNotNull null
                    val alias = alias(entity) ?: return@mapNotNull null
                    id to alias
                }
                .toMap()

            batch.forEach { request ->
                val direct = localized[request.mediaId]
                if (direct != null) {
                    complete(request, direct, "id")
                } else {
                    resolveFallback(request)
                }
            }
        }
    }

    private fun resolveFallback(request: Pending) {
        val hint = request.isrcHint
        if (hint != null) {
            resolveByIsrc(request, hint, source = "entity-isrc")
            return
        }

        // Only misses pay for an account-storefront lookup. This keeps the normal visible page at
        // one batched CN request while preserving cross-storefront recording equivalence fallback.
        query(
            path = "songs",
            query = linkedMapOf(
                "ids" to request.mediaId,
                "platform" to "android",
                "include[songs]" to "artists",
            ),
            localized = false,
        ) { accountResponse ->
            val entity = catalogEntity(accountResponse, request.mediaId)
            val attributes = entity?.let { call(it, "getAttributes") }
            val isrc = (attributes?.let { call(it, "getIsrc") } as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            if (isrc == null) {
                complete(request, null, "miss-no-isrc")
            } else {
                resolveByIsrc(request, isrc, source = "account-isrc")
            }
        }
    }

    private fun resolveByIsrc(
        request: Pending,
        isrc: String,
        source: String,
    ) {
        query(
            path = "songs",
            query = linkedMapOf(
                "filter[isrc]" to isrc,
                "l" to LANGUAGE,
                "platform" to "android",
                "include[songs]" to "artists",
                "limit" to "1",
                TOKEN to token("isrc", request.mediaId),
            ),
            localized = true,
        ) { response ->
            val entity = catalogEntity(response, requestedId = null)
            complete(request, entity?.let(::alias), source)
        }
    }

    private fun complete(request: Pending, alias: Alias?, source: String) {
        logger(
            Log.INFO,
            "localized catalog resolved id=${request.mediaId} source=$source hit=${alias != null}" +
                (alias?.title?.let { " title=$it" } ?: ""),
            null,
        )
        request.callbacks.forEach { callback ->
            runCatching { callback(alias) }
                .onFailure { logger(Log.ERROR, "localized catalog callback failed", it) }
        }
    }

    private fun installExecutor(target: AppleMusic653.CatalogExecutor) {
        module.hook(target.method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                @Suppress("UNCHECKED_CAST")
                val original = chain.args.getOrNull(target.queryIndex) as? Map<Any?, Any?>
                    ?: return@intercept chain.proceed()
                if (!original.containsKey(TOKEN)) return@intercept chain.proceed()

                val args = chain.args.toTypedArray()
                val query = LinkedHashMap<Any?, Any?>()
                query.putAll(original)
                query.remove(TOKEN)
                query["l"] = LANGUAGE
                args[target.queryIndex] = query
                args[3] = STOREFRONT
                chain.proceed(args)
            }
    }

    private fun query(
        path: String,
        query: LinkedHashMap<String, String>,
        localized: Boolean,
        callback: (Any?) -> Unit,
    ) {
        val continuationType = access.directQuery.parameterTypes[2]
        val completion = AtomicBoolean(false)
        val context = emptyCoroutineContext(continuationType)

        fun finish(value: Any?) {
            if (!completion.compareAndSet(false, true)) return
            main.post { callback(value) }
        }

        val timeout = Runnable {
            if (!completion.compareAndSet(false, true)) return@Runnable
            logger(
                Log.WARN,
                "catalog query timeout mode=${if (localized) "localized" else "account"} path=$path",
                null,
            )
            callback(null)
        }
        main.postDelayed(timeout, QUERY_TIMEOUT_MS)

        val continuation = Proxy.newProxyInstance(
            continuationType.classLoader ?: loader,
            arrayOf(continuationType),
        ) { proxy, method, args ->
            when (method.name) {
                "getContext" -> context
                "resumeWith" -> {
                    val result = args?.firstOrNull()
                    main.removeCallbacks(timeout)
                    finish(if (isCoroutineFailure(result)) null else result)
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AMToolV3CatalogContinuation($path)"
                else -> null
            }
        }

        runCatching {
            access.directQuery.invoke(access.mediaApi, path, query, continuation)
        }.onSuccess { immediate ->
            if (!isCoroutineSuspended(immediate)) {
                main.removeCallbacks(timeout)
                finish(immediate)
            }
        }.onFailure { error ->
            main.removeCallbacks(timeout)
            logger(
                Log.ERROR,
                "catalog query failed mode=${if (localized) "localized" else "account"} path=$path",
                error,
            )
            finish(null)
        }
    }

    private fun catalogEntities(response: Any?): List<Any> {
        response ?: return emptyList()
        val data = call(response, "getData")
        return when (data) {
            is Array<*> -> data.filterNotNull()
            is Iterable<*> -> data.filterNotNull()
            is Map<*, *> -> data.values.filterNotNull()
            else -> emptyList()
        }
    }

    private fun catalogEntity(response: Any?, requestedId: String?): Any? {
        val entities = catalogEntities(response)
        if (requestedId == null) return entities.firstOrNull()
        return entities.firstOrNull { candidate ->
            catalogId(candidate) == requestedId ||
                call(candidate, "getSubscriptionStoreId")?.toString() == requestedId
        } ?: entities.firstOrNull()
    }

    private fun catalogId(entity: Any): String? =
        sequenceOf("getId", "getSubscriptionStoreId")
            .mapNotNull { name ->
                call(entity, name)?.toString()?.trim()?.takeIf { value ->
                    value.isNotEmpty() && value.all(Char::isDigit)
                }
            }
            .firstOrNull()

    private fun alias(entity: Any): Alias? {
        val attributes = call(entity, "getAttributes") ?: return null
        val title = (call(attributes, "getName") as? String).orEmpty().trim()
        val artist = (call(attributes, "getArtistName") as? String).orEmpty().trim()
        val album = (call(attributes, "getAlbumName") as? String).orEmpty().trim()
        if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) return null
        return Alias(title, artist, album)
    }

    private fun token(kind: String, identity: String): String =
        "amtn-$kind-${sequence.incrementAndGet().toString(36)}-${identity.hashCode().toUInt().toString(36)}"

    private fun emptyCoroutineContext(continuationType: Class<*>): Any {
        runCatching {
            val type = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, loader)
            return type.getField("INSTANCE").get(null)
        }
        val contextType = continuationType.methods
            .first { it.name == "getContext" && it.parameterCount == 0 }
            .returnType
        return Proxy.newProxyInstance(
            contextType.classLoader ?: loader,
            arrayOf(contextType),
        ) { proxy, method, args ->
            when (method.name) {
                "fold" -> args?.firstOrNull()
                "get" -> null
                "minusKey" -> proxy
                "plus" -> args?.firstOrNull()
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> 0
                "toString" -> "EmptyCoroutineContext"
                else -> null
            }
        }
    }

    private fun isCoroutineSuspended(value: Any?): Boolean =
        value?.toString() == "COROUTINE_SUSPENDED" ||
            (value?.javaClass?.name?.contains("CoroutineSingletons") == true &&
                value.toString().contains("COROUTINE_SUSPENDED"))

    private fun isCoroutineFailure(value: Any?): Boolean =
        value?.javaClass?.name == "kotlin.Result$Failure"

    private fun call(instance: Any, name: String, vararg args: Any?): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == args.size
            }?.let { method ->
                method.isAccessible = true
                return runCatching { method.invoke(instance, *args) }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    companion object {
        const val TOKEN = "amtool_localized_request"
        const val STOREFRONT = "cn"
        const val LANGUAGE = "zh-CN"
        private const val MAX_BATCH = 50
        private const val BATCH_DELAY_MS = 20L
        private const val QUERY_TIMEOUT_MS = 6_000L
    }
}
