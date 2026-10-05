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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Playback-safe localized catalog client for Apple Music 6.5.3.
 *
 * alpha3 fixes two alpha1/alpha2 mistakes:
 * 1. do not resolve every visible row with its own account+ISRC pair of requests;
 * 2. do not assume the CN entity must be found by ISRC.
 *
 * Visible IDs are coalesced for 36 ms. One account batch captures all stable identity aliases
 * (id/subscriptionStoreId/assetAdamId/reportingAdamId/formerIds/playParams.catalogId), then one
 * tokenized CN batch tries those IDs directly. ISRC is only the final fallback.
 *
 * A localized direct query temporarily seeds MediaApi.s because u8.E.v reads it during coroutine
 * setup. The exact v8.D/A5.l/Ic.n executor hook remains the authority: token requests are forced
 * to CN and the token is stripped; any concurrent native request observed during that tiny setup
 * window is pinned back to the captured account storefront.
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
            "catalog side-channel alpha3 installed executors=${methods.size} " +
                "accountStorefront=${accountStorefront ?: "unknown"}",
            null,
        )
    }

    fun resolve(mediaId: String, callback: (Alias?) -> Unit) =
        resolve(mediaId, null, callback)

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
        if (schedule) {
            main.postDelayed(::flush, BATCH_DELAY_MS)
        }
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
        val hintedIds = batch.filterValues { it.isrcHint != null }.keys
        val accountLookupIds = requestedIds.filterNot(hintedIds::contains)

        fun continueWithIdentity(accountEntities: List<EntitySnapshot>) {
            val identityByRequested = requestedIds.associateWith { requestedId ->
                accountEntities.firstOrNull { requestedId in it.ids }
            }

            val lookupIdsByRequested = requestedIds.associateWith { requestedId ->
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
                finishBatch(batch, emptyMap())
                return
            }

            query(
                path = "songs",
                query = linkedMapOf(
                    "ids" to allLookupIds.joinToString(","),
                    "l" to LANGUAGE,
                    "platform" to "android",
                    "include[songs]" to "artists",
                ),
                localized = true,
            ) { localizedResponse ->
                val localizedEntities = parseEntities(localizedResponse)
                val resolved = LinkedHashMap<String, Alias>()
                val unresolved = mutableListOf<String>()

                requestedIds.forEach { requestedId ->
                    val lookupIds = lookupIdsByRequested[requestedId].orEmpty().toSet()
                    val direct = localizedEntities.firstOrNull { entity ->
                        entity.alias != null && entity.ids.any(lookupIds::contains)
                    }?.alias

                    if (direct != null && direct.hasValue()) {
                        resolved[requestedId] = direct
                    } else {
                        unresolved += requestedId
                    }
                }

                if (unresolved.isEmpty()) {
                    finishBatch(batch, resolved)
                    return@query
                }

                resolveByIsrcFallback(
                    unresolved = unresolved,
                    batch = batch,
                    identityByRequested = identityByRequested,
                    resolved = resolved,
                ) { completed ->
                    finishBatch(batch, completed)
                }
            }
        }

        // Artist Top Songs already exposes ISRC in its MediaEntity. Avoid an extra account
        // catalog round-trip for those visible rows; ordinary model/getter requests still use
        // one coalesced account batch to recover alternate Apple IDs and ISRC.
        if (accountLookupIds.isEmpty()) {
            continueWithIdentity(emptyList())
            return
        }

        query(
            path = "songs",
            query = linkedMapOf(
                "ids" to accountLookupIds.joinToString(","),
                "platform" to "android",
                "include[songs]" to "artists",
            ),
            localized = false,
        ) { accountResponse ->
            continueWithIdentity(parseEntities(accountResponse))
        }
    }

    private fun resolveByIsrcFallback(
        unresolved: List<String>,
        batch: Map<String, Pending>,
        identityByRequested: Map<String, EntitySnapshot?>,
        resolved: LinkedHashMap<String, Alias>,
        onDone: (Map<String, Alias>) -> Unit,
    ) {
        val candidates = unresolved.mapNotNull { id ->
            val isrc = batch[id]?.isrcHint ?: identityByRequested[id]?.isrc
            isrc?.let { id to it }
        }
        if (candidates.isEmpty()) {
            onDone(resolved)
            return
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
                localized = true,
            ) { response ->
                parseEntities(response)
                    .firstOrNull { it.alias?.hasValue() == true }
                    ?.alias
                    ?.let { resolved[requestedId] = it }

                if (remaining.decrementAndGet() == 0) {
                    onDone(resolved)
                }
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
                    "localized metadata hit id=$id title=${alias.title}",
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

                if (original.containsKey(TOKEN)) {
                    val args = chain.args.toTypedArray()
                    val query = LinkedHashMap<Any?, Any?>()
                    query.putAll(original)
                    query.remove(TOKEN)
                    query["l"] = LANGUAGE
                    args[target.queryIndex] = query
                    args[3] = STOREFRONT
                    return@intercept chain.proceed(args)
                }

                // During the synchronous setup window MediaApi.s is temporarily CN. If Apple
                // happens to start an unrelated native request on another thread in that window,
                // keep that request on the real account storefront.
                val account = accountStorefront
                if (
                    setupLocalizedCalls.get() > 0 &&
                    !account.isNullOrBlank() &&
                    account != STOREFRONT &&
                    chain.args.getOrNull(3)?.toString() == STOREFRONT
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
        localized: Boolean,
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
        if (localized) {
            directQuery[TOKEN] = requestId
            directQuery["l"] = LANGUAGE
        }

        timeout = Runnable {
            if (completion.compareAndSet(false, true)) {
                logger(
                    Log.ERROR,
                    "catalog side-channel timeout id=$requestId path=$path localized=$localized",
                    null,
                )
                callback(null)
            }
        }.also { main.postDelayed(it, QUERY_TIMEOUT_MS) }

        runCatching {
            if (!localized) {
                access.directQuery.invoke(access.mediaApi, path, directQuery, continuation)
            } else {
                val previous = access.storefrontField.get(access.mediaApi) as? String
                setupLocalizedCalls.incrementAndGet()
                try {
                    access.storefrontField.set(access.mediaApi, STOREFRONT)
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
                "catalog side-channel failed id=$requestId path=$path localized=$localized",
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
        const val STOREFRONT = "cn"
        const val LANGUAGE = "zh-CN"

        private const val BATCH_DELAY_MS = 36L
        private const val BATCH_SIZE = 24
        private const val MAX_LOOKUP_IDS = 64
        private const val QUERY_TIMEOUT_MS = 6_000L
    }
}
