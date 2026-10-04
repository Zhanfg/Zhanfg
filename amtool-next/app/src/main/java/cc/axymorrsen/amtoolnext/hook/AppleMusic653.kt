package cc.axymorrsen.amtoolnext.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.LinkedHashMap

/** Exact symbols verified against Apple Music 6.5.3 (1599). */
internal object AppleMusic653 {
    const val PACKAGE = "com.apple.android.music"
    const val LYRICS_LANGUAGE_REQUEST =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f"
    const val APP_SHARED_PREFERENCES = "com.apple.android.music.utils.AppSharedPreferences"
    const val MEDIA_API_LOCALIZATION = "u8.E"

    private data class MethodSpec(val className: String, val methodName: String, val parameterCount: Int)

    // 6.5.3 request executors. All use storefront at argument index 3.
    private val catalogExecutors = listOf(
        MethodSpec("v8.D", "d", 7),
        MethodSpec("v8.D", "b", 7),
        MethodSpec("A5.l", "d", 7),
        MethodSpec("A5.l", "c", 6),
        MethodSpec("Ic.n", "d", 7),
        MethodSpec("Ic.n", "e", 7),
    )

    fun lyricsLanguageConstructor(loader: ClassLoader): Constructor<*> {
        val type = loader.loadClass(LYRICS_LANGUAGE_REQUEST)
        return type.declaredConstructors.single { ctor ->
            ctor.parameterTypes.count { it == Array<String>::class.java } == 2
        }.apply { isAccessible = true }
    }

    fun mediaApiLocalization(loader: ClassLoader): Method {
        val type = loader.loadClass(MEDIA_API_LOCALIZATION)
        return type.declaredMethods.single { method ->
            method.name == "c0" &&
                method.parameterCount == 1 &&
                Map::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                LinkedHashMap::class.java.isAssignableFrom(method.returnType)
        }.apply { isAccessible = true }
    }

    fun catalogRequestExecutors(loader: ClassLoader): List<Method> = catalogExecutors.mapNotNull { spec ->
        runCatching {
            loader.loadClass(spec.className).declaredMethods.single { method ->
                method.name == spec.methodName &&
                    method.parameterCount == spec.parameterCount &&
                    method.parameterTypes.getOrNull(3) == String::class.java
            }.apply { isAccessible = true }
        }.getOrNull()
    }

    /**
     * v8.N0.d(Long dsid, String userAgent, String authorization, String storefront,
     * String id, Map query, Continuation) — verified on Apple Music 6.5.3 (1599).
     */
    fun lyricsNetworkRequest(loader: ClassLoader): Method =
        loader.loadClass("v8.N0").declaredMethods.single { method ->
            method.name == "d" &&
                method.parameterCount == 7 &&
                method.parameterTypes.getOrNull(3) == String::class.java &&
                method.parameterTypes.getOrNull(4) == String::class.java &&
                Map::class.java.isAssignableFrom(method.parameterTypes.getOrNull(5))
        }.apply { isAccessible = true }

    fun translationSetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "setLyricsTranslationSelected" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()
}
