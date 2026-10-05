package cc.axymorrsen.amtoolnext.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    private val main = Handler(Looper.getMainLooper())
    private val sequence = AtomicLong()
    private val access by lazy { AppleMusic653.mediaApiAccess(loader) }

    fun installRouter() {
        val methods = AppleMusic653.catalogRequestExecutors(loader)
        require(methods.isNotEmpty()) { "6.5.3 catalog executors unavailable" }
        methods.forEach(::installExecutor)
        logger(Log.INFO, "catalog side-channel router installed count=${methods.size}", null)
    }

    fun resolve(mediaId: String, callback: (Alias?) -> Unit) {
        query(
            path = "songs",
            query = linkedMapOf(
                "ids" to mediaId,
                "platform" to "android",
                "include[songs]" to "artists",
            ),
            localized = false,
        ) { accountResponse ->
            val entity = catalogEntity(accountResponse, mediaId)
            val attributes = entity?.let { call(it, "getAttributes") }
            val isrc = (attributes?.let { call(it, "getIsrc") } as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            if (isrc == null) {
                callback(null)
                return@query
            }

            query(
                path = "songs",
                query = linkedMapOf(
                    "filter[isrc]" to isrc,
                    "l" to LANGUAGE,
                    "platform" to "android",
                    "include[songs]" to "artists",
                    "limit" to "1",
                    TOKEN to "amtn-${sequence.incrementAndGet().toString(36)}-$mediaId",
                ),
                localized = true,
            ) { cnResponse ->
                val cnEntity = catalogEntity(cnResponse, requestedId = null)
                val cnAttributes = cnEntity?.let { call(it, "getAttributes") }
                if (cnAttributes == null) {
                    callback(null)
                    return@query
                }
                val title = (call(cnAttributes, "getName") as? String).orEmpty().trim()
                val artist = (call(cnAttributes, "getArtistName") as? String).orEmpty().trim()
                val album = (call(cnAttributes, "getAlbumName") as? String).orEmpty().trim()
                callback(
                    if (title.isEmpty() && artist.isEmpty() && album.isEmpty()) null
                    else Alias(title, artist, album)
                )
            }
        }
    }

    private fun installExecutor(method: Method) {
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val queryIndex = method.parameterTypes.indices.firstOrNull { index ->
                    index > 3 && Map::class.java.isAssignableFrom(method.parameterTypes[index])
                } ?: return@intercept chain.proceed()

                @Suppress("UNCHECKED_CAST")
                val original = chain.args.getOrNull(queryIndex) as? Map<Any?, Any?>
                    ?: return@intercept chain.proceed()
                if (!original.containsKey(TOKEN)) return@intercept chain.proceed()

                val args = chain.args.toTypedArray()
                val query = LinkedHashMap<Any?, Any?>()
                query.putAll(original)
                query.remove(TOKEN)
                query["l"] = LANGUAGE
                args[queryIndex] = query
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
                "toString" -> "AMToolV3CatalogContinuation($path)"
                else -> null
            }
        }

        runCatching {
            access.directQuery.invoke(access.mediaApi, path, query, continuation)
        }.onSuccess { immediate ->
            if (!isCoroutineSuspended(immediate)) finish(immediate)
        }.onFailure { error ->
            logger(
                Log.ERROR,
                "catalog query failed mode=${if (localized) "localized" else "account"} path=$path",
                error,
            )
            finish(null)
        }
    }

    private fun catalogEntity(response: Any?, requestedId: String?): Any? {
        response ?: return null
        val data = call(response, "getData")
        val entities = when (data) {
            is Array<*> -> data.filterNotNull()
            is Iterable<*> -> data.filterNotNull()
            is Map<*, *> -> data.values.filterNotNull()
            else -> emptyList()
        }
        if (requestedId == null) return entities.firstOrNull()
        return entities.firstOrNull { candidate ->
            call(candidate, "getId")?.toString() == requestedId ||
                call(candidate, "getSubscriptionStoreId")?.toString() == requestedId
        } ?: entities.firstOrNull()
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
    }
}
