package cc.axymorrsen.amtoolnext.hook

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import cc.axymorrsen.amtoolnext.config.ConfigCodec
import cc.axymorrsen.amtoolnext.config.ConfigKeys
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AmToolModule : XposedModule() {
    private val installed = AtomicBoolean(false)
    private var remotePreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val catalogHits = AtomicLong()
    private val lyricsHits = AtomicLong()
    private val ampHits = AtomicLong()
    private val reflectionMethodCache = ConcurrentHashMap<String, Method>()
    private val reflectionFieldCache = ConcurrentHashMap<String, Field>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            val prefs = getRemotePreferences(ConfigKeys.GROUP)
            remotePreferences = prefs
            HookConfigRuntime.update(ConfigCodec.read(prefs))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
                val next = ConfigCodec.read(changed)
                if (HookConfigRuntime.update(next)) {
                    log(Log.INFO, TAG, "hot reload revision=${next.revision}")
                }
            }
            preferenceListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
            log(Log.INFO, TAG, "configuration attached revision=${HookConfigRuntime.revision()}")
        }.onFailure {
            log(Log.ERROR, TAG, "RemotePreferences unavailable; defaults active", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != AppleMusic653.PACKAGE || !param.isFirstPackage) return
        if (!installed.compareAndSet(false, true)) return
        val loader = param.classLoader

        installLyricsLanguageHook(loader)
        installCurrentLyricsLanguageHook(loader)
        installSongInfoTranslationCompatibility(loader)
        installLyricsRequestObservationHook(loader)

        // Playback safety: never rewrite Apple Music's native catalog/editorial requests.
        // Chinese metadata will be reintroduced through a side-channel lookup + model overlay,
        // not by changing the storefront of the objects that carry playParams.
        log(Log.INFO, TAG, "native metadata storefront rewrite disabled for playback safety")

        installTranslationPreferenceGuard(loader)
        log(Log.INFO, TAG, "Apple Music 6.5.3 alpha3-hotfix2 hooks installed")
    }

    private fun installLyricsLanguageHook(loader: ClassLoader) {
        runCatching {
            val constructor = AppleMusic653.lyricsLanguageConstructor(loader)
            val stringArrayIndexes = constructor.parameterTypes.indices
                .filter { constructor.parameterTypes[it] == Array<String>::class.java }
            val translationIndex = stringArrayIndexes.first()
            val pronunciationIndex = stringArrayIndexes.last()

            hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled) return@intercept chain.proceed()

                    val args = chain.args.toTypedArray()
                    args[translationIndex] = LanguagePolicy.translations(
                        args.getOrNull(translationIndex) as? Array<*>,
                        config.chineseLyrics || config.autoTranslation,
                    )
                    args[pronunciationIndex] = LanguagePolicy.pronunciations(
                        args.getOrNull(pronunciationIndex) as? Array<*>,
                        config.pronunciation,
                    )
                    chain.proceed(args)
                }
            log(Log.INFO, TAG, "lyrics preferred-language hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "lyrics preferred-language hook failed", it)
        }
    }

    /**
     * Apple Music asks SongInfo again using its current system lyrics language.
     * Make that language Chinese while leaving the account/storefront untouched.
     */
    private fun installCurrentLyricsLanguageHook(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.currentSystemLyricsLanguage(loader)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (config.enabled && (config.chineseLyrics || config.autoTranslation)) {
                        LanguagePolicy.LYRICS_SYSTEM_LANGUAGE
                    } else {
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "current lyrics language hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "current lyrics language hook failed", it)
        }
    }

    /**
     * Official SongInfo can expose "zh-Hans-CN" while the UI probes "zh-Hans".
     * Without this mapping the payload is present but the translation availability check fails,
     * which is exactly the state where the translation button disappears.
     */
    private fun installSongInfoTranslationCompatibility(loader: ClassLoader) {
        val translationLanguages = runCatching {
            AppleMusic653.songInfoTranslationLanguages(loader)
        }.onFailure {
            log(Log.ERROR, TAG, "SongInfo translation-language getter resolve failed", it)
        }.getOrNull() ?: return

        val methods = AppleMusic653.songInfoTranslationMethods(loader)
        methods.forEach { method ->
            runCatching {
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val config = HookConfigRuntime.current()
                        if (!config.enabled || (!config.chineseLyrics && !config.autoTranslation)) {
                            return@intercept chain.proceed()
                        }

                        val requested = chain.args.firstOrNull() as? String
                            ?: return@intercept chain.proceed()
                        val songNative = chain.thisObject
                            ?: return@intercept chain.proceed()
                        val available = readNativeStringVector(
                            translationLanguages.invoke(songNative)
                        )
                        val selected = LanguagePolicy.selectAvailableTranslation(
                            requestedLanguage = requested,
                            availableLanguages = available,
                        ) ?: return@intercept chain.proceed()

                        if (selected == requested) {
                            chain.proceed()
                        } else {
                            val args = chain.args.toTypedArray()
                            args[0] = selected
                            log(
                                Log.INFO,
                                TAG,
                                "translation tag mapped ${method.name}: $requested -> $selected available=$available",
                            )
                            chain.proceed(args)
                        }
                    }
                log(Log.INFO, TAG, "SongInfo translation compatibility installed: ${method.name}")
            }.onFailure {
                log(Log.ERROR, TAG, "SongInfo translation compatibility failed: ${method.name}", it)
            }
        }
    }

    /**
     * Lyrics must stay on the real account storefront. Alpha2 incorrectly changed this to US,
     * which can break the account-scoped lyrics payload and does not unlock the translation UI.
     */
    private fun installLyricsRequestObservationHook(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.lyricsNetworkRequest(loader)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val hit = lyricsHits.incrementAndGet()
                    if (hit <= 12) {
                        val storefront = chain.args.getOrNull(3)?.toString()
                        @Suppress("UNCHECKED_CAST")
                        val query = chain.args.getOrNull(5) as? Map<Any?, Any?>
                        log(
                            Log.INFO,
                            TAG,
                            "lyrics request preserved hit=$hit storefront=$storefront queryKeys=${query?.keys}",
                        )
                    }
                    chain.proceed()
                }
            log(Log.INFO, TAG, "lyrics storefront guard installed (preserve account storefront)")
        }.onFailure {
            log(Log.ERROR, TAG, "lyrics storefront guard failed", it)
        }
    }

    /**
     * Exact 6.5.3 repository executors. These cover direct catalog/search paths.
     * Chinese metadata uses the CN storefront; if Apple has no localized title, its response
     * naturally carries the canonical/English title.
     */
    private fun installCatalogRequestLocalizationHooks(loader: ClassLoader) {
        val methods = AppleMusic653.catalogRequestExecutors(loader)
        if (methods.isEmpty()) {
            log(Log.ERROR, TAG, "no catalog request executors resolved")
            return
        }
        methods.forEach { method -> installCatalogExecutorHook(method) }
        log(Log.INFO, TAG, "catalog storefront hooks installed count=${methods.size}")
    }

    private fun installCatalogExecutorHook(method: Method) {
        runCatching {
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || !config.chineseMetadata) return@intercept chain.proceed()

                    val args = chain.args.toTypedArray()
                    val queryIndex = method.parameterTypes.indices
                        .firstOrNull { index ->
                            index > 3 && Map::class.java.isAssignableFrom(method.parameterTypes[index])
                        }

                    if (shouldPreserveAccountStorefront(method, args, queryIndex)) {
                        log(
                            Log.INFO,
                            TAG,
                            "playback-sensitive catalog request preserved: " +
                                "${method.declaringClass.name}#${method.name}",
                        )
                        return@intercept chain.proceed()
                    }

                    val oldStorefront = args.getOrNull(3)?.toString()
                    args[3] = LanguagePolicy.CONTENT_STOREFRONT

                    if (queryIndex != null) {
                        @Suppress("UNCHECKED_CAST")
                        val oldQuery = args.getOrNull(queryIndex) as? Map<Any?, Any?>
                        val query = LinkedHashMap<Any?, Any?>()
                        if (oldQuery != null) query.putAll(oldQuery)
                        query["l"] = LanguagePolicy.CONTENT_LANGUAGE
                        args[queryIndex] = query
                    }

                    val hit = catalogHits.incrementAndGet()
                    if (hit <= 20) {
                        log(
                            Log.INFO,
                            TAG,
                            "catalog localized hit=$hit ${method.declaringClass.name}#${method.name} " +
                                "storefront=$oldStorefront->${LanguagePolicy.CONTENT_STOREFRONT}",
                        )
                    }
                    chain.proceed(args)
                }
        }.onFailure {
            log(Log.ERROR, TAG, "catalog executor hook failed ${method.declaringClass.name}#${method.name}", it)
        }
    }

    /**
     * 6.5.3 has content pages that bypass the repository executors. Their final request goes
     * through w8.d#a. Alpha2 did not hook this path, so artist/album pages could remain entirely
     * English even when the executor hooks were working.
     */
    private fun installAmpApiLocalizationHook(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.ampHttpInterceptor(loader)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || !config.chineseMetadata) {
                        return@intercept chain.proceed()
                    }
                    val httpChain = chain.args.firstOrNull()
                        ?: return@intercept chain.proceed()
                    rewriteAmpRequest(httpChain)
                    chain.proceed()
                }
            log(Log.INFO, TAG, "amp-api last-mile localization hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "amp-api last-mile localization hook failed", it)
        }
    }

    private fun rewriteAmpRequest(httpChain: Any) {
        runCatching {
            val request = readField(httpChain, AppleMusic653.HTTP_CHAIN_REQUEST_FIELD) ?: return
            val url = readField(request, AppleMusic653.HTTP_REQUEST_URL_FIELD)?.toString().orEmpty()
            if (url.isBlank()) return

            val uri = Uri.parse(url)
            if (!uri.host.orEmpty().contains("apple", ignoreCase = true)) return

            val segments = uri.pathSegments.toMutableList()
            if (
                isLyricsPath(segments) ||
                isAccountScopedPlaybackPath(segments) ||
                isDirectSongResourcePath(segments) ||
                hasPlaybackSensitiveQuery(uri)
            ) {
                return
            }

            val pathHasStorefront =
                segments.size > 2 &&
                    segments[0] == "v1" &&
                    (segments[1] == "catalog" || segments[1] == "editorial")
            if (!pathHasStorefront) return

            val sourceStorefront = segments[2]
            segments[2] = LanguagePolicy.CONTENT_STOREFRONT

            val builder = uri.buildUpon()
            builder.encodedPath(
                segments.joinToString(separator = "/", prefix = "/") { Uri.encode(it) }
            )
            builder.clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (name != "l") {
                    uri.getQueryParameters(name).forEach { value ->
                        builder.appendQueryParameter(name, value)
                    }
                }
            }
            builder.appendQueryParameter("l", LanguagePolicy.CONTENT_LANGUAGE)
            val rewrittenUrl = builder.build().toString()

            val requestBuilder = callMember(
                request,
                AppleMusic653.HTTP_REQUEST_NEW_BUILDER_METHOD,
            ) ?: return

            callMember(
                requestBuilder,
                AppleMusic653.HTTP_REQUEST_BUILDER_URL_METHOD,
                rewrittenUrl,
            )
            callMember(
                requestBuilder,
                AppleMusic653.HTTP_REQUEST_BUILDER_HEADER_METHOD,
                "Accept-Language",
                LanguagePolicy.CONTENT_LANGUAGE,
            )
            val rebuilt = callMember(
                requestBuilder,
                AppleMusic653.HTTP_REQUEST_BUILDER_BUILD_METHOD,
            ) ?: return

            writeField(httpChain, AppleMusic653.HTTP_CHAIN_REQUEST_FIELD, rebuilt)

            val hit = ampHits.incrementAndGet()
            if (hit <= 30) {
                log(
                    Log.INFO,
                    TAG,
                    "amp-api localized hit=$hit storefront=$sourceStorefront->${LanguagePolicy.CONTENT_STOREFRONT} " +
                        "path=${uri.path}",
                )
            }
        }.onFailure {
            log(Log.ERROR, TAG, "amp-api request rewrite failed", it)
        }
    }

    private fun installMetadataLanguageHook(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.mediaApiLocalization(loader)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val result = chain.proceed()
                    val config = HookConfigRuntime.current()
                    if (config.enabled && config.chineseMetadata) {
                        @Suppress("UNCHECKED_CAST")
                        (result as? MutableMap<Any?, Any?>)?.set(
                            "l",
                            LanguagePolicy.CONTENT_LANGUAGE,
                        )
                    }
                    result
                }
            log(Log.INFO, TAG, "catalog language hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "catalog language hook failed", it)
        }
    }

    private fun installTranslationPreferenceGuard(loader: ClassLoader) {
        val setter = AppleMusic653.translationSetter(loader) ?: return
        runCatching {
            hook(setter)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || !config.autoTranslation) return@intercept chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[0] = true
                    chain.proceed(args)
                }
            log(Log.INFO, TAG, "translation preference guard installed")
        }.onFailure {
            log(Log.ERROR, TAG, "translation preference guard failed", it)
        }
    }

    private fun readNativeStringVector(vector: Any?): List<String> {
        vector ?: return emptyList()
        return runCatching {
            val size = (callMember(vector, "size") as? Number)?.toInt() ?: return emptyList()
            val result = ArrayList<String>(size.coerceAtLeast(0))
            repeat(size.coerceIn(0, 128)) { index ->
                val value = callMember(vector, "get", index)
                    ?: callMember(vector, "get", index.toLong())
                if (value is String && value.isNotBlank()) result += value
            }
            result
        }.getOrElse { emptyList() }
    }

    private fun shouldPreserveAccountStorefront(
        method: Method,
        args: Array<Any?>,
        queryIndex: Int?,
    ): Boolean {
        if (method.declaringClass.name == "v8.D" && method.name == "d") {
            val tail = args.getOrNull(4)?.toString().orEmpty().trimStart('/')
            if (tail == "songs" || tail.startsWith("songs/")) return true
        }
        if (queryIndex != null) {
            @Suppress("UNCHECKED_CAST")
            val query = args.getOrNull(queryIndex) as? Map<Any?, Any?>
            if (query != null && query.entries.any { (key, value) ->
                    isPlaybackSensitiveToken(key?.toString()) ||
                        isPlaybackSensitiveToken(value?.toString())
                }
            ) return true
        }
        return false
    }

    private fun isDirectSongResourcePath(segments: List<String>): Boolean =
        segments.getOrNull(1) == "catalog" &&
            segments.getOrNull(3) == "songs" &&
            segments.size >= 5

    private fun hasPlaybackSensitiveQuery(uri: Uri): Boolean =
        uri.queryParameterNames.any { name ->
            isPlaybackSensitiveToken(name) ||
                uri.getQueryParameters(name).any(::isPlaybackSensitiveToken)
        }

    private fun isPlaybackSensitiveToken(value: String?): Boolean {
        val normalized = value.orEmpty().lowercase()
        return normalized.contains("playparam") ||
            normalized.contains("playback") ||
            normalized.contains("extendedasset") ||
            normalized.contains("asseturl") ||
            normalized.contains("audio-variant") ||
            normalized.contains("audiovariant") ||
            normalized.contains("stream")
    }

    private fun isLyricsPath(segments: List<String>): Boolean =
        segments.getOrNull(3) == "songs" &&
            segments.lastOrNull()?.contains("lyrics", ignoreCase = true) == true

    private fun isAccountScopedPlaybackPath(segments: List<String>): Boolean =
        segments.any { segment ->
            segment.equals("radio", ignoreCase = true) ||
                segment.equals("station", ignoreCase = true) ||
                segment.equals("stations", ignoreCase = true)
        }

    private fun readField(instance: Any, name: String): Any? {
        val field = findField(instance.javaClass, name) ?: return null
        return field.get(instance)
    }

    private fun writeField(instance: Any, name: String, value: Any?) {
        val field = findField(instance.javaClass, name) ?: return
        field.set(instance, value)
    }

    private fun findField(type: Class<*>, name: String): Field? {
        val key = "${type.name}#$name"
        reflectionFieldCache[key]?.let { return it }
        var current: Class<*>? = type
        while (current != null) {
            val field = current.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                reflectionFieldCache[key] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun callMember(instance: Any, name: String, vararg args: Any?): Any? {
        val method = findCompatibleMethod(instance.javaClass, name, args) ?: return null
        return method.invoke(instance, *args)
    }

    private fun findCompatibleMethod(
        type: Class<*>,
        name: String,
        args: Array<out Any?>,
    ): Method? {
        val key = "${type.name}#$name#${args.size}#${args.joinToString(",") { it?.javaClass?.name ?: "null" }}"
        reflectionMethodCache[key]?.let { return it }

        var current: Class<*>? = type
        while (current != null) {
            val method = current.declaredMethods.firstOrNull { candidate ->
                candidate.name == name &&
                    candidate.parameterCount == args.size &&
                    candidate.parameterTypes.indices.all { index ->
                        isCompatible(candidate.parameterTypes[index], args[index])
                    }
            }
            if (method != null) {
                method.isAccessible = true
                reflectionMethodCache[key] = method
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun isCompatible(parameter: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameter.isPrimitive
        if (!parameter.isPrimitive) return parameter.isInstance(value)
        return when (parameter) {
            java.lang.Boolean.TYPE -> value is Boolean
            java.lang.Byte.TYPE -> value is Byte || value is Number
            java.lang.Short.TYPE -> value is Short || value is Number
            java.lang.Integer.TYPE -> value is Int || value is Number
            java.lang.Long.TYPE -> value is Long || value is Number
            java.lang.Float.TYPE -> value is Float || value is Number
            java.lang.Double.TYPE -> value is Double || value is Number
            java.lang.Character.TYPE -> value is Char
            else -> false
        }
    }

    companion object {
        private const val TAG = "AMToolNext"
    }
}
