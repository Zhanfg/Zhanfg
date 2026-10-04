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

    fun translationSetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "setLyricsTranslationSelected" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()
}
