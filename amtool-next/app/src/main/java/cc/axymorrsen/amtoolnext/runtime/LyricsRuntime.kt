package cc.axymorrsen.amtoolnext.runtime

import android.util.Log
import cc.axymorrsen.amtoolnext.config.HookConfigRuntime
import cc.axymorrsen.amtoolnext.hook.AppleMusic653
import cc.axymorrsen.amtoolnext.hook.LanguagePolicy
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Lyrics language policy only.
 *
 * This domain never changes storefront, entitlement, playback URLs or account state.
 */
internal class LyricsRuntime(
    private val module: XposedModule,
    private val loader: ClassLoader,
    private val logger: (priority: Int, message: String, error: Throwable?) -> Unit,
) {
    fun install() {
        installPreferredLanguages()
        installSystemLyricsLanguage()
        installSongInfoLanguageCompatibility()
        installSelectionStateHooks()
        installTranslationPreferenceGuard()
    }

    private fun installPreferredLanguages() {
        runCatching {
            val constructor = AppleMusic653.lyricsLanguageConstructor(loader)
            val stringArrays = constructor.parameterTypes.indices
                .filter { constructor.parameterTypes[it] == Array<String>::class.java }
            val translationIndex = stringArrays.first()
            val pronunciationIndex = stringArrays.last()

            module.hook(constructor)
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
            logger(Log.INFO, "lyrics preferred-language hook installed", null)
        }.onFailure {
            logger(Log.ERROR, "lyrics preferred-language hook failed", it)
        }
    }

    private fun installSystemLyricsLanguage() {
        runCatching {
            val method = AppleMusic653.currentSystemLyricsLanguage(loader)
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (config.enabled && (config.chineseLyrics || config.autoTranslation)) {
                        LanguagePolicy.LYRICS_SYSTEM_LANGUAGE
                    } else {
                        chain.proceed()
                    }
                }
            logger(Log.INFO, "system lyrics language hook installed", null)
        }.onFailure {
            logger(Log.ERROR, "system lyrics language hook failed", it)
        }
    }

    private fun installSongInfoLanguageCompatibility() {
        val languages = runCatching {
            AppleMusic653.songInfoTranslationLanguages(loader)
        }.onFailure {
            logger(Log.ERROR, "SongInfo translation-language getter resolve failed", it)
        }.getOrNull() ?: return

        AppleMusic653.songInfoTranslationMethods(loader).forEach { method ->
            installSongInfoMethod(method, languages)
        }
    }

    private fun installSongInfoMethod(method: Method, languages: Method) {
        runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || (!config.chineseLyrics && !config.autoTranslation)) {
                        return@intercept chain.proceed()
                    }

                    val requested = chain.args.firstOrNull() as? String
                        ?: return@intercept chain.proceed()
                    val songNative = chain.thisObject ?: return@intercept chain.proceed()
                    val available = readNativeStringVector(languages.invoke(songNative))
                    val selected = LanguagePolicy.selectAvailableTranslation(
                        requestedLanguage = requested,
                        availableLanguages = available,
                    ) ?: return@intercept chain.proceed()

                    if (selected == requested) {
                        chain.proceed()
                    } else {
                        val args = chain.args.toTypedArray()
                        args[0] = selected
                        chain.proceed(args)
                    }
                }
            logger(
                Log.INFO,
                "SongInfo translation compatibility installed: ${method.name}",
                null,
            )
        }.onFailure {
            logger(
                Log.ERROR,
                "SongInfo translation compatibility failed: ${method.name}",
                it,
            )
        }
    }

    private fun installSelectionStateHooks() {
        AppleMusic653.translationSelectedGetter(loader)?.let { getter ->
            runCatching {
                module.hook(getter)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val config = HookConfigRuntime.current()
                        if (config.enabled && config.autoTranslation) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                logger(Log.INFO, "lyrics translation selected getter forced when enabled", null)
            }.onFailure {
                logger(Log.ERROR, "lyrics translation selected getter hook failed", it)
            }
        }

        AppleMusic653.pronunciationSelectedGetter(loader)?.let { getter ->
            runCatching {
                module.hook(getter)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val config = HookConfigRuntime.current()
                        if (config.enabled && config.pronunciation) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                logger(Log.INFO, "lyrics pronunciation selected getter forced when enabled", null)
            }.onFailure {
                logger(Log.ERROR, "lyrics pronunciation selected getter hook failed", it)
            }
        }
    }

    private fun installTranslationPreferenceGuard() {
        val setter = AppleMusic653.translationSetter(loader) ?: return
        runCatching {
            module.hook(setter)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val config = HookConfigRuntime.current()
                    if (!config.enabled || !config.autoTranslation) {
                        return@intercept chain.proceed()
                    }
                    val args = chain.args.toTypedArray()
                    args[0] = true
                    chain.proceed(args)
                }
            logger(Log.INFO, "translation preference guard installed", null)
        }.onFailure {
            logger(Log.ERROR, "translation preference guard failed", it)
        }
    }

    private fun readNativeStringVector(vector: Any?): List<String> {
        vector ?: return emptyList()
        return runCatching {
            val sizeMethod = vector.javaClass.methods.firstOrNull {
                it.name == "size" && it.parameterCount == 0
            } ?: return emptyList()
            val getMethod = vector.javaClass.methods.firstOrNull {
                it.name == "get" && it.parameterCount == 1
            } ?: return emptyList()
            val size = (sizeMethod.invoke(vector) as? Number)?.toInt()?.coerceIn(0, 128)
                ?: return emptyList()
            buildList {
                repeat(size) { index ->
                    (getMethod.invoke(vector, index) as? String)
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }
}
