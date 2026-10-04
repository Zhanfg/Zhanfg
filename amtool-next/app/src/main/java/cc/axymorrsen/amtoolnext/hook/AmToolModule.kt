package cc.axymorrsen.amtoolnext.hook

import android.content.SharedPreferences
import android.util.Log
import cc.axymorrsen.amtoolnext.config.ConfigCodec
import cc.axymorrsen.amtoolnext.config.ConfigKeys
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AmToolModule : XposedModule() {
    private val installed = AtomicBoolean(false)
    private var remotePreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val catalogHits = AtomicLong()
    private val lyricsHits = AtomicLong()

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
        installLyricsRequestLocalizationHook(loader)
        installCatalogRequestLocalizationHooks(loader)
        installMetadataLanguageHook(loader)
        installTranslationPreferenceGuard(loader)
        log(Log.INFO, TAG, "Apple Music 6.5.3 hooks installed")
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
     * Alpha1 kept the Turkish storefront and only changed preferred-language arrays. The
     * Turkish storefront does not expose Simplified Chinese localization/translation for many
     * catalog items, so no translation lane/button was created. For lyrics GETs only, use the
     * US catalog surface with zh-Hans. Account, DSID, entitlement and playback remain untouched.
     */
    private fun installLyricsRequestLocalizationHook(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.lyricsNetworkRequest(loader)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || (!config.chineseLyrics && !config.autoTranslation)) {
                        return@intercept chain.proceed()
                    }
                    val args = chain.args.toTypedArray()
                    val oldStorefront = args[3]?.toString()
                    args[3] = LanguagePolicy.CONTENT_STOREFRONT
                    @Suppress("UNCHECKED_CAST")
                    val oldQuery = args.getOrNull(5) as? Map<Any?, Any?>
                    val query = LinkedHashMap<Any?, Any?>()
                    if (oldQuery != null) query.putAll(oldQuery)
                    query["l"] = LanguagePolicy.CONTENT_LANGUAGE
                    args[5] = query
                    val hit = lyricsHits.incrementAndGet()
                    if (hit <= 12) {
                        log(
                            Log.INFO,
                            TAG,
                            "lyrics request localized hit=$hit storefront=$oldStorefront->${LanguagePolicy.CONTENT_STOREFRONT} l=${LanguagePolicy.CONTENT_LANGUAGE}",
                        )
                    }
                    chain.proceed(args)
                }
            log(Log.INFO, TAG, "lyrics request storefront/localization hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "lyrics request storefront/localization hook failed", it)
        }
    }

    /**
     * Rewrites catalog/editorial request executors to the US storefront with zh-Hans. US is used
     * instead of CN because it retains broad catalog coverage while Apple can still return Chinese
     * localized metadata; when no Chinese localization exists Apple naturally falls back to its
     * default English metadata. Playback/account requests are not routed through these executors.
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
                    val oldStorefront = args.getOrNull(3)?.toString()
                    args[3] = LanguagePolicy.CONTENT_STOREFRONT

                    // Query-map index differs between single, batch and search executors.
                    val queryIndex = method.parameterTypes.indices
                        .firstOrNull { index ->
                            index > 3 && Map::class.java.isAssignableFrom(method.parameterTypes[index])
                        }
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
                            "catalog localized hit=$hit ${method.declaringClass.name}#${method.name} storefront=$oldStorefront->${LanguagePolicy.CONTENT_STOREFRONT}",
                        )
                    }
                    chain.proceed(args)
                }
        }.onFailure {
            log(Log.ERROR, TAG, "catalog executor hook failed ${method.declaringClass.name}#${method.name}", it)
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
                        (result as? MutableMap<Any?, Any?>)?.set("l", LanguagePolicy.CONTENT_LANGUAGE)
                    }
                    result
                }
            log(Log.INFO, TAG, "catalog language hook installed")
        }.onFailure {
            log(Log.ERROR, TAG, "catalog language hook failed", it)
        }
    }

    /**
     * When enabled, attempts by Apple Music to persist a false translation selection are rewritten
     * to true. Availability still comes from Apple's lyrics payload; alpha2 now requests that
     * payload through the Chinese-capable content storefront.
     */
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

    companion object {
        private const val TAG = "AMToolNext"
    }
}
