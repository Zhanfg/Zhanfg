package cc.axymorrsen.amtoolnext.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.LinkedHashMap

/** Exact symbols verified against Apple Music 6.5.3 (1599). */
internal object AppleMusic653 {
    const val PACKAGE = "com.apple.android.music"
    const val LYRICS_LANGUAGE_REQUEST =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f"
    const val PLAYER_LYRICS_VIEW_MODEL =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
    const val SONG_INFO_NATIVE =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative"
    const val APP_SHARED_PREFERENCES = "com.apple.android.music.utils.AppSharedPreferences"
    const val MEDIA_API_LOCALIZATION = "u8.E"
    const val BASE_CONTENT_ITEM = "com.apple.android.music.model.BaseContentItem"
    private val CONTENT_ITEM_CLASSES = listOf(
        "com.apple.android.music.model.BaseContentItem",
        "com.apple.android.music.model.BasePlaybackItem",
        "com.apple.android.music.model.Song",
        "com.apple.android.music.model.AlbumCollectionItem",
        "com.apple.android.music.model.ArtistCollectionItem",
        "com.apple.android.music.model.MusicVideo",
    )
    const val MEDIA_API_REPOSITORY_HOLDER =
        "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder"

    // 6.5.3 amp-api final network interceptor.
    const val AMP_HTTP_INTERCEPTOR = "w8.d"
    const val HTTP_CHAIN_REQUEST_FIELD = "e"
    const val HTTP_REQUEST_URL_FIELD = "a"
    const val HTTP_REQUEST_NEW_BUILDER_METHOD = "b"
    const val HTTP_REQUEST_BUILDER_URL_METHOD = "h"
    const val HTTP_REQUEST_BUILDER_HEADER_METHOD = "d"
    const val HTTP_REQUEST_BUILDER_BUILD_METHOD = "b"

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

    fun ampHttpInterceptor(loader: ClassLoader): Method =
        loader.loadClass(AMP_HTTP_INTERCEPTOR).declaredMethods.single { method ->
            method.name == "a" && method.parameterCount == 1
        }.apply { isAccessible = true }

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
                Map::class.java.isAssignableFrom(method.parameterTypes[5])
        }.apply { isAccessible = true }

    fun currentSystemLyricsLanguage(loader: ClassLoader): Method =
        loader.loadClass(PLAYER_LYRICS_VIEW_MODEL).declaredMethods.single { method ->
            method.name == "getCurrentSystemLyricsLanguage" &&
                method.parameterCount == 0 &&
                method.returnType == String::class.java
        }.apply { isAccessible = true }

    fun songInfoTranslationLanguages(loader: ClassLoader): Method =
        loader.loadClass(SONG_INFO_NATIVE).declaredMethods.single { method ->
            method.name == "getTranslationLanguages" && method.parameterCount == 0
        }.apply { isAccessible = true }

    fun songInfoTranslationMethods(loader: ClassLoader): List<Method> {
        val type = loader.loadClass(SONG_INFO_NATIVE)
        return listOf("setTranslation", "hasTranslation").mapNotNull { name ->
            type.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }?.apply { isAccessible = true }
        }
    }

    fun contentItemRuntimeClasses(loader: ClassLoader): List<Class<*>> =
        CONTENT_ITEM_CLASSES.mapNotNull { name ->
            runCatching { loader.loadClass(name) }.getOrNull()
        }

    fun contentItemGetterMethods(type: Class<*>): Map<String, Method> {
        val names = listOf(
            "getTitle",
            "getNowPlayingTitle",
            "getArtistName",
            "getNowPlayingSubtitle",
            "getCollectionName",
        )
        return names.mapNotNull { name ->
            findMethod(type, name, 0)
                ?.takeIf { it.returnType == String::class.java }
                ?.let { name to it }
        }.toMap()
    }

    fun contentItemIdentityMethods(type: Class<*>): Map<String, Method> =
        listOf("getSubscriptionStoreId", "getId").mapNotNull { name ->
            findMethod(type, name, 0)?.let { name to it }
        }.toMap()

    fun contentItemNotifyChange(type: Class<*>): Method? =
        findMethod(type, "notifyChange", 0)

    data class MediaApiAccess(
        val mediaApi: Any,
        val storefrontField: java.lang.reflect.Field,
        val directQuery: Method,
    )

    fun mediaApiAccess(loader: ClassLoader): MediaApiAccess {
        val holder = loader.loadClass(MEDIA_API_REPOSITORY_HOLDER)
        val companionField = holder.declaredFields.firstOrNull { field ->
            java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                field.type.name == "${holder.name}\$Companion"
        } ?: error("MediaApiRepositoryHolder companion unavailable")
        companionField.isAccessible = true
        val companion = requireNotNull(companionField.get(null))
        val getMediaApi = findMethod(companion.javaClass, "getMediaApi", 0)
            ?: error("MediaApiRepositoryHolder#getMediaApi unavailable")
        val mediaApi = requireNotNull(getMediaApi.invoke(companion))
        val storefrontField = findField(mediaApi.javaClass, "s")
            ?.takeIf { it.type == String::class.java }
            ?: error("MediaApi storefront field s unavailable")
        storefrontField.isAccessible = true

        val direct = findMethod(mediaApi.javaClass, "v", 3) { method ->
            val p = method.parameterTypes
            p[0] == String::class.java &&
                Map::class.java.isAssignableFrom(p[1]) &&
                p[2].name == "kotlin.coroutines.Continuation"
        } ?: error("MediaApi#v(String,Map,Continuation) unavailable")
        return MediaApiAccess(mediaApi, storefrontField, direct)
    }

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredFields.firstOrNull { it.name == name }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        parameterCount: Int,
        extra: (Method) -> Boolean = { true },
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name &&
                    method.parameterCount == parameterCount &&
                    extra(method)
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        return null
    }

    fun translationSetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "setLyricsTranslationSelected" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()
}
