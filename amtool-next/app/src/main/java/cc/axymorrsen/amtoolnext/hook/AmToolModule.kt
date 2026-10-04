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
import java.util.concurrent.atomic.AtomicBoolean

class AmToolModule : XposedModule() {
    private val installed = AtomicBoolean(false)
    private var remotePreferences: SharedPreferences? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        runCatching {
            val prefs = getRemotePreferences(ConfigKeys.GROUP)
            remotePreferences = prefs
            HookConfigRuntime.update(ConfigCodec.read(prefs))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
                val next = ConfigCodec.read(changed)
                if (HookConfigRuntime.update(next)) {
                    log(Log.INFO, TAG, "hot reload revision=${next.revision}")
                    LyricsHotReload.requestRefresh { message ->
                        log(Log.INFO, TAG, message)
                    }
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
        installLyricsReloadCapture(loader)
        installLyricsLanguageHook(loader)
        installMetadataLanguageHook(loader)
        installTranslationPreferenceGuard(loader)
    }

    private fun installLyricsReloadCapture(loader: ClassLoader) {
        runCatching {
            val method = AppleMusic653.lyricsLoadMethod(loader)
            LyricsHotReload.bind(method)
            hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    LyricsHotReload.capture(chain.thisObject, chain.args.toTypedArray())
                    chain.proceed()
                }
            log(Log.INFO, TAG, "lyrics hot-reload capture installed")
        }.onFailure {
            log(Log.ERROR, TAG, "lyrics hot-reload capture failed", it)
        }
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
                        config.chineseLyrics,
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
                        (result as? MutableMap<Any?, Any?>)?.set("l", "zh-CN")
                    }
                    result
                }
            log(Log.INFO, TAG, "catalog language hook installed (storefront untouched)")
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

    companion object {
        private const val TAG = "AMToolNext"
    }
}
