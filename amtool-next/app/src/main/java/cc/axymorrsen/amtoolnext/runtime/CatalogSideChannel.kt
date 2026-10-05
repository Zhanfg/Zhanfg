package cc.axymorrsen.amtoolnext.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Playback-safe localized catalog client for Apple Music 6.5.3.
 *
 * Requests are module-owned and batched. The resolution ladder is:
 *
 * 1. same account storefront + zh-CN (keeps the original catalog identity);
 * 2. CN storefront using every stable Adam-ID alias carried by the entity;
 * 3. CN storefront by ISRC.
 *
 * This matters because some Apple entities expose translated metadata through the language
 * parameter without changing storefront, while others are separate CN catalog entities.
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

    private data class EntitySnapshot(
        val ids: Set<String>,
        val isrc: String?,
        val alias: Alias?,
    )

    private data class Pending(
        val callbacks: MutableList<(Alias?) -> Unit> = mutableListOf(),
        var isrcHint: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val setupLocalizedCalls = AtomicInteger()
    private val access by lazy { AppleMusic653.mediaApiAccess(loader) }

    private val pendingLock = Any()
    private val pending = LinkedHashMap<String, Pending>()
    private var flushScheduled = false

    private val requestStorefronts = ConcurrentHashMap<String, String>()

    @Volatile
    private var accountStorefront: String? = null

    fun installRouter() {
        val access = access
        accountStorefront = runCatching {
            access.storefrontField.get(access.mediaApi) as? String
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

        val methods = AppleMusic653.catalogRequestExecutors(loader)
        require(methods.isNotEmpty()) { "6.5.3 catalog executors unavailable" }
        methods.forEach(::installExecutor)

        logger(
            Log.INFO,
            "catalog side-channel installed executors=${methods.size} " +
                "accountStorefront=${accountStorefront ?: "unknown"}",
            null,
        )
    }

    fun resolve(mediaId: String, callback: (Alias?) -> Unit) =
        resolve(mediaId, isrcHint = null, callback = callback)

    fun resolve(
        mediaId: String,
        isrcHint: String?,
        callback: (Alias?) -> Unit,
    ) {
        val id = mediaId.trim()
        if (id.isEmpty() || !id.all(Char::isDigit)) {
            main.post { callback(null) }
            return
        }

        val hint = isrcHint?.trim()?.takeIf(String::isNotEmpty)
        var schedule = false
        synchronized(pendingLock) {
            val request = pending.getOrPut(id) { Pending() }
            request.callbacks += callback
            if (request.isrcHint == null && hint != null) request.isrcHint = hint
            if (!flushScheduled) {
                flushScheduled = true
                schedule = true
            }
        }
        if (schedule) main.postDelayed(::flush, BATCH_DELAY_MS)
    }

    private fun flush() {
        val batch = LinkedHashMap<String, Pending>()
        synchronized(pendingLock) {
            pending.entries.take(BATCH_SIZE).forEach { (id, request) ->
                batch[id] = request
            }
            batch.keys.forEach(pending::remove)
            flushScheduled = pending.isNotEmpty()
        }

        if (batch.isEmpty()) return
        if (flushScheduled) main.postDelayed(::flush, BATCH_DELAY_MS)
        resolveBatch(batch)
    }

    private fun resolveBatch(batch: Map<String, Pending>) {
        val requestedIds = batch.keys.toList()
        val accountTarget = accountStorefront

        query(
            path = "songs",
            query = linkedMapOf(
                "ids" to requestedIds.joinToString(","),
                "l" to LANGUAGE,
                "platform" to "android",
                "include[songs]" to "artists",
            ),
            targetStorefront = accountTarget,
        ) { accountResponse ->
            val accountEntities = parseEntities(accountResponse)
            val identityByRequested = requestedIds.associateWith { requestedId ->
                val entity = accountEntities.firstOrNull { requestedId in it.ids }
                val hint = batch[requestedId]?.isrcHint
                when {
                    entity == null && hint != null -> EntitySnapshot(
                        ids = setOf(requestedId),
                        isrc = hint,
                        alias = null,
                    )
                    entity != null && entity.isrc == null && hint != null ->
                        entity.copy(isrc = hint)
                    else -> entity
                }
            }

            val resolved = LinkedHashMap<String, Alias>()
            val fallbacks = LinkedHashMap<String, Alias>()
            val unresolved = mutableListOf<String>()

            requestedIds.forEach { requestedId ->
                val alias = identityByRequested[requestedId]?.alias
                when {
                    alias?.hasChineseTitle() == true -> resolved[requestedId] = alias
                    else -> {
                        if (alias?.hasValue() == true) fallbacks[requestedId] = alias
                        unresolved += requestedId
                    }
                }
            }

            if (unresolved.isEmpty()) {
                finishBatch(batch, resolved)
                return@query
            }

            val lookupIdsByRequested = unresolved.associateWith { requestedId ->
                LinkedHashSet<String>().apply {
                    add(requestedId)
                    identityByRequested[requestedId]?.ids?.let(::addAll)
                }.toList()
            }
            val allLookupIds = lookupIdsByRequested.values
                .flatten()
                .distinct()
                .take(MAX_LOOKUP_IDS)

            if (allLookupIds.isEmpty()) {
                resolveByIsrcFallback(
                    unresolved = unresolved,
                    identityByRequested = identityByRequested,
                    resolved = resolved,
                    fallbacks = fallbacks,
                ) { completed ->
                    finishBatch(batch, completed)
                }
                return@query
            }

            query(
                path = "songs",
                query = linkedMapOf(
                    "ids" to allLookupIds.joinToString(","),
                    "l" to LANGUAGE,
                    "platform" to "android",
                    "include[songs]" to "artists",
                ),
                targetStorefront = CN_STOREFRONT,
            ) { localizedResponse ->
                val localizedEntities = parseEntities(localizedResponse)
                val stillUnresolved = mutableListOf<String>()

                unresolved.forEach { requestedId ->
                    val lookupIds = lookupIdsByRequested[requestedId].orEmpty().toSet()
                    val direct = localizedEntities.firstOrNull { entity ->
                        entity.alias != null && entity.ids.any(lookupIds::contains)
                    }?.alias

                    when {
                        direct?.hasChineseTitle() == true -> resolved[requestedId] = direct
                        else -> {
                            if (direct?.hasValue() == true) fallbacks[requestedId] = direct
                            stillUnresolved += requestedId
                        }
                    }
                }

                if (stillUnresolved.isEmpty()) {
                    finishBatch(batch, resolved)
                    return@query
                }

                resolveByIsrcFallback(
                    unresolved = stillUnresolved,
                    identityByRequested = identityByRequested,
                    resolved = resolved,
                    fallbacks = fallbacks,
                ) { completed ->
                    finishBatch(batch, completed)
                }
            }
        }
    }

    private fun resolveByIsrcFallback(
        unresolved: List<String>,
        identityByRequested: Map<String, EntitySnapshot?>,
        resolved: LinkedHashMap<String, Alias>,
        fallbacks: Map<String, Alias>,
        onDone: (Map<String, Alias>) -> Unit,
    ) {
        val candidates = unresolved.mapNotNull { id ->
            identityByRequested[id]?.isrc?.let { id to it }
        }

        if (candidates.isEmpty()) {
            unresolved.forEach { id -> fallbacks[id]?.let { resolved[id] = it } }
            onDone(resolved)
            return
        }

        val candidateIds = candidates.mapTo(HashSet()) { it.first }
        unresolved.filterNot(candidateIds::contains).forEach { id ->
            fallbacks[id]?.let { resolved[id] = it }
        }

        val remaining = AtomicInteger(candidates.size)
        candidates.forEach { (requestedId, isrc) ->
            query(
                path = "songs",
                query = linkedMapOf(
                    "filter[isrc]" to isrc,
                    "l" to LANGUAGE,
                    "platform" to "android",
                    "include[songs]" to "artists",
                    "limit" to "1",
                ),
                targetStorefront = CN_STOREFRONT,
            ) { response ->
                val alias = parseEntities(response)
                    .firstOrNull { it.alias?.hasValue() == true }
                    ?.alias

                when {
                    alias?.hasChineseTitle() == true -> resolved[requestedId] = alias
                    alias?.hasValue() == true -> resolved[requestedId] = alias
                    else -> fallbacks[requestedId]?.let { resolved[requestedId] = it }
                }

                if (remaining.decrementAndGet() == 0) onDone(resolved)
            }
        }
    }

    private fun finishBatch(
        batch: Map<String, Pending>,
        resolved: Map<String, Alias>,
    ) {
        batch.forEach { (id, request) ->
            val alias = resolved[id]
            if (alias == null) {
                logger(Log.INFO, "localized metadata miss id=$id", null)
            } else {
                logger(
                    Log.INFO,
                    "localized metadata hit id=$id chinese=${alias.hasChineseTitle()} " +
                        "title=${alias.title}",
                    null,
                )
            }
            request.callbacks.forEach { callback -> callback(alias) }
        }
    }

    private fun installExecutor(target: AppleMusic653.CatalogExecutor) {
        module.hook(target.method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                @Suppress("UNCHECKED_CAST")
                val original = chain.args.getOrNull(target.queryIndex) as? Map<Any?, Any?>
                    ?: return@intercept chain.proceed()

                val token = original[TOKEN]?.toString()
                if (token != null) {
                    val args = chain.args.toTypedArray()
                    val query = LinkedHashMap<Any?, Any?>()
                    query.putAll(original)
                    query.remove(TOKEN)
                    query["l"] = LANGUAGE
                    args[target.queryIndex] = query
                    requestStorefronts[token]?.let { args[3] = it }
                    return@intercept chain.proceed(args)
                }

                // MediaApi.s is only seeded for the synchronous setup of our own request.
                // An unrelated native request that races that tiny window stays on the real
                // account storefront.
                val account = accountStorefront
                if (
                    setupLocalizedCalls.get() > 0 &&
                    !account.isNullOrBlank() &&
                    chain.args.getOrNull(3)?.toString() != account
                ) {
                    val args = chain.args.toTypedArray()
                    args[3] = account
                    return@intercept chain.proceed(args)
                }

                chain.proceed()
            }
    }

    private fun query(
        path: String,
        query: LinkedHashMap<String, String>,
        targetStorefront: String?,
        callback: (Any?) -> Unit,
    ) {
        val access = access
        val continuationType = access.directQuery.parameterTypes[2]
        val completion = AtomicBoolean(false)
        val context = emptyCoroutineContext(continuationType)
        val requestId = sequence.incrementAndGet().toString(36)
        var timeout: Runnable? = null

        fun finish(value: Any?) {
            if (!completion.compareAndSet(false, true)) return
            timeout?.let(main::removeCallbacks)
            requestStorefronts.remove(requestId)
            main.post { callback(value) }
        }

        val continuation = Proxy.newProxyInstance(
            continuationType.classLoader ?: loader,
            arrayOf(continuationType),
        ) { proxy, method, args ->
            when (method.name) {
                "getContext" -> context
                "resumeWith" -> {
                    val result = args?.firstOrNull()
                    finish(if (isCoroutineFailure(result)) null else result)
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AMToolCatalogContinuation($path/$requestId)"
                else -> null
            }
        }

        val directQuery = LinkedHashMap(query)
        if (targetStorefront != null) {
            directQuery[TOKEN] = requestId
            directQuery["l"] = LANGUAGE
            requestStorefronts[requestId] = targetStorefront
        }

        timeout = Runnable {
            if (completion.compareAndSet(false, true)) {
                requestStorefronts.remove(requestId)
                logger(
                    Log.ERROR,
                    "catalog side-channel timeout id=$requestId path=$path " +
                        "storefront=${targetStorefront ?: "native"}",
                    null,
                )
                callback(null)
            }
        }.also { main.postDelayed(it, QUERY_TIMEOUT_MS) }

        runCatching {
            if (targetStorefront == null) {
                access.directQuery.invoke(access.mediaApi, path, directQuery, continuation)
            } else {
                val previous = access.storefrontField.get(access.mediaApi) as? String
                setupLocalizedCalls.incrementAndGet()
                try {
                    access.storefrontField.set(access.mediaApi, targetStorefront)
                    access.directQuery.invoke(access.mediaApi, path, directQuery, continuation)
                } finally {
                    runCatching { access.storefrontField.set(access.mediaApi, previous) }
                    setupLocalizedCalls.decrementAndGet()
                }
            }
        }.onSuccess { immediate ->
            if (!isCoroutineSuspended(immediate)) finish(immediate)
        }.onFailure { error ->
            logger(
                Log.ERROR,
                "catalog side-channel failed id=$requestId path=$path " +
                    "storefront=${targetStorefront ?: "native"}",
                error,
            )
            finish(null)
        }
    }

    private fun parseEntities(response: Any?): List<EntitySnapshot> {
        response ?: return emptyList()
        return collectionValues(call(response, "getData")).mapNotNull { entity ->
            val attributes = call(entity, "getAttributes")
            val ids = LinkedHashSet<String>()

            fun addId(value: Any?) {
                value?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?.let(ids::add)
            }

            addId(call(entity, "getId"))
            addId(call(entity, "getSubscriptionStoreId"))
            addId(call(entity, "getAssetAdamId"))
            addId(call(entity, "getReportingAdamId"))
            collectionValues(call(entity, "getFormerIds")).forEach(::addId)

            val playParams = attributes?.let { call(it, "getPlayParams") }
            addId(playParams?.let { call(it, "getCatalogId") })

            val isrc = attributes
                ?.let { call(it, "getIsrc") as? String }
                ?.trim()
                ?.takeIf(String::isNotEmpty)

            val title = attributes
                ?.let { call(it, "getName") as? String }
                .orEmpty()
                .trim()
            val artist = attributes
                ?.let { call(it, "getArtistName") as? String }
                .orEmpty()
                .trim()
            val album = attributes
                ?.let { call(it, "getAlbumName") as? String }
                .orEmpty()
                .trim()

            if (ids.isEmpty() && isrc == null && title.isEmpty() && artist.isEmpty()) {
                null
            } else {
                EntitySnapshot(
                    ids = ids,
                    isrc = isrc,
                    alias = Alias(title, artist, album).takeIf { it.hasValue() },
                )
            }
        }
    }

    private fun Alias.hasValue(): Boolean =
        title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()

    private fun Alias.hasChineseTitle(): Boolean =
        title.any { char ->
            val code = char.code
            code in 0x3400..0x4DBF ||
                code in 0x4E00..0x9FFF ||
                code in 0xF900..0xFAFF
        }

    private fun collectionValues(value: Any?): List<Any> = when (value) {
        is Array<*> -> value.filterNotNull()
        is Iterable<*> -> value.filterNotNull()
        is Map<*, *> -> value.values.filterNotNull()
        else -> emptyList()
    }

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
        value?.javaClass?.name == "kotlin.Result\$Failure"

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
        const val LANGUAGE = "zh-CN"
        private const val CN_STOREFRONT = "cn"

        private const val BATCH_DELAY_MS = 36L
        private const val BATCH_SIZE = 24
        private const val MAX_LOOKUP_IDS = 64
        private const val QUERY_TIMEOUT_MS = 6_000L
    }
}
