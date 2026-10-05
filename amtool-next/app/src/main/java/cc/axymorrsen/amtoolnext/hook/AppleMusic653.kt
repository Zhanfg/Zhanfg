package cc.axymorrsen.amtoolnext.hook

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Exact Apple Music 6.5.3 (1599) runtime profile used by V3.
 *
 * Only symbols required by the new architecture live here. Legacy whole-request localization,
 * amp-api rewriting and shared storefront-field mutation were intentionally removed.
 */
internal object AppleMusic653 {
    const val PACKAGE = "com.apple.android.music"
    const val LYRICS_LANGUAGE_REQUEST =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel\$f"
    const val PLAYER_LYRICS_VIEW_MODEL =
        "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel"
    const val SONG_INFO_NATIVE =
        "com.apple.android.music.ttml.javanative.model.SongInfo\$SongInfoNative"
    const val APP_SHARED_PREFERENCES =
        "com.apple.android.music.utils.AppSharedPreferences"
    const val MEDIA_API_REPOSITORY_HOLDER =
        "com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder"
    const val MEDIA_ENTITY = "com.apple.android.music.mediaapi.models.MediaEntity"
    const val ARTIST_BASE_CONTROLLER =
        "com.apple.android.music.profiles.BaseProfileEpoxyController"
    const val ARTIST_TOP_SONG_MODEL = "com.apple.android.music.e1"
    const val STACKED_NAVIGATION_HOLDER =
        "com.apple.android.music.common.activity.PlayerActivity\$StackedBottomNavigationHolder"

    private val CONTENT_ITEM_CLASSES = listOf(
        "com.apple.android.music.model.BaseContentItem",
        "com.apple.android.music.model.BasePlaybackItem",
        "com.apple.android.music.model.Song",
        "com.apple.android.music.model.AlbumCollectionItem",
        "com.apple.android.music.model.ArtistCollectionItem",
        "com.apple.android.music.model.MusicVideo",
    )

    private data class MethodSpec(
        val className: String,
        val methodName: String,
        val parameterCount: Int,
        val queryIndex: Int,
    )

    /**
     * 6.5.3 request executors. Storefront is always arg[3].
     * Query index is explicit because the batch/search shapes differ.
     */
    private val catalogExecutors = listOf(
        MethodSpec("v8.D", "d", 7, 5),
        MethodSpec("v8.D", "b", 7, 4),
        MethodSpec("A5.l", "d", 7, 5),
        MethodSpec("A5.l", "c", 6, 4),
        MethodSpec("Ic.n", "d", 7, 5),
        MethodSpec("Ic.n", "e", 7, 5),
    )

    data class CatalogExecutor(
        val method: Method,
        val queryIndex: Int,
    )

    data class MediaApiAccess(
        val mediaApi: Any,
        val directQuery: Method,
    )

    fun lyricsLanguageConstructor(loader: ClassLoader): Constructor<*> {
        val type = loader.loadClass(LYRICS_LANGUAGE_REQUEST)
        return type.declaredConstructors.single { ctor ->
            ctor.parameterTypes.count { it == Array<String>::class.java } == 2
        }.apply { isAccessible = true }
    }

    fun catalogRequestExecutors(loader: ClassLoader): List<CatalogExecutor> =
        catalogExecutors.mapNotNull { spec ->
            runCatching {
                val method = loader.loadClass(spec.className).declaredMethods.single { candidate ->
                    candidate.name == spec.methodName &&
                        candidate.parameterCount == spec.parameterCount &&
                        candidate.parameterTypes.getOrNull(3) == String::class.java &&
                        Map::class.java.isAssignableFrom(
                            candidate.parameterTypes.getOrNull(spec.queryIndex)
                        )
                }.apply { isAccessible = true }
                CatalogExecutor(method, spec.queryIndex)
            }.getOrNull()
        }

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

    fun translationSelectedGetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "isLyricsTranslationSelected" &&
                method.parameterCount == 0 &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()

    fun pronunciationSelectedGetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "isLyricsPronunciationSelected" &&
                method.parameterCount == 0 &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()

    fun translationSetter(loader: ClassLoader): Method? = runCatching {
        loader.loadClass(APP_SHARED_PREFERENCES).declaredMethods.single { method ->
            method.name == "setLyricsTranslationSelected" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }.apply { isAccessible = true }
    }.getOrNull()

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

    data class ArtistTopSongSurface(
        val buildMethod: Method,
        val bindMethod: Method,
        val titleField: Field,
    )

    /**
     * 6.5.3 artist Top Songs do not consume BaseContentItem#getTitle at render time.
     * BaseProfileEpoxyController materializes an e1 Epoxy model and e1#a binds its L field.
     */
    fun artistTopSongSurface(loader: ClassLoader): ArtistTopSongSurface {
        val mediaEntity = loader.loadClass(MEDIA_ENTITY)
        val controller = loader.loadClass(ARTIST_BASE_CONTROLLER)
        val model = loader.loadClass(ARTIST_TOP_SONG_MODEL)

        val build = controller.declaredMethods.single { method ->
            method.name == "addSwipingChartItemA2" &&
                method.parameterCount == 6 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == mediaEntity &&
                method.parameterTypes[2] == Int::class.javaPrimitiveType &&
                method.parameterTypes[3] == Int::class.javaPrimitiveType &&
                method.parameterTypes[4] == String::class.java &&
                method.parameterTypes[5] == Int::class.javaPrimitiveType
        }.apply { isAccessible = true }

        val bind = findMethod(model, "a", 2) { method ->
            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Any::class.java &&
                method.returnType == Void.TYPE
        } ?: error("Artist Top Songs model binder e1#a unavailable")

        val title = findField(model, "L")
            ?.takeIf { field ->
                field.type.isAssignableFrom(String::class.java) ||
                    CharSequence::class.java.isAssignableFrom(field.type)
            }
            ?: error("Artist Top Songs title field e1#L unavailable")
        title.isAccessible = true

        return ArtistTopSongSurface(build, bind, title)
    }

    fun stackedNavigationSlide(loader: ClassLoader): Method =
        loader.loadClass(STACKED_NAVIGATION_HOLDER).declaredMethods.single { method ->
            method.name == "c" &&
                method.parameterCount == 1 &&
                method.parameterTypes[0] == Float::class.javaPrimitiveType &&
                method.returnType == Void.TYPE
        }.apply { isAccessible = true }

    fun mediaEntityAttributes(entity: Any): Any? =
        findMethod(entity.javaClass, "getAttributes", 0)
            ?.let { method -> runCatching { method.invoke(entity) }.getOrNull() }

    fun mediaEntityIsrc(entity: Any): String? {
        val attributes = mediaEntityAttributes(entity) ?: return null
        return findMethod(attributes.javaClass, "getIsrc", 0)
            ?.let { method -> runCatching { method.invoke(attributes) as? String }.getOrNull() }
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    fun mediaEntityCatalogId(entity: Any): String? {
        val candidates = sequenceOf(
            "getId",
            "getSubscriptionStoreId",
            "getAssetAdamId",
            "getReportingAdamId",
        )
        candidates.forEach { name ->
            val method = findMethod(entity.javaClass, name, 0) ?: return@forEach
            val value = runCatching { method.invoke(entity)?.toString()?.trim() }.getOrNull()
            if (!value.isNullOrEmpty() && value.all(Char::isDigit)) return value
        }

        val attributes = findMethod(entity.javaClass, "getAttributes", 0)
            ?.let { runCatching { it.invoke(entity) }.getOrNull() }
            ?: return null
        val playParams = findMethod(attributes.javaClass, "getPlayParams", 0)
            ?.let { runCatching { it.invoke(attributes) }.getOrNull() }
            ?: return null
        val catalogId = findMethod(playParams.javaClass, "getCatalogId", 0)
            ?.let { runCatching { it.invoke(playParams)?.toString()?.trim() }.getOrNull() }
        return catalogId?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    fun mediaApiAccess(loader: ClassLoader): MediaApiAccess {
        val holder = loader.loadClass(MEDIA_API_REPOSITORY_HOLDER)
        val companionField = holder.declaredFields.firstOrNull { field ->
            Modifier.isStatic(field.modifiers) &&
                field.type.name == "${holder.name}\$Companion"
        } ?: error("MediaApiRepositoryHolder companion unavailable")
        companionField.isAccessible = true
        val companion = requireNotNull(companionField.get(null))
        val getMediaApi = findMethod(companion.javaClass, "getMediaApi", 0)
            ?: error("MediaApiRepositoryHolder#getMediaApi unavailable")
        val mediaApi = requireNotNull(getMediaApi.invoke(companion))

        val direct = findMethod(mediaApi.javaClass, "v", 3) { method ->
            val p = method.parameterTypes
            p[0] == String::class.java &&
                Map::class.java.isAssignableFrom(p[1]) &&
                method.returnType == Any::class.java
        } ?: error("MediaApi#v(String,Map,Continuation) unavailable")
        return MediaApiAccess(mediaApi, direct)
    }

    private fun findField(type: Class<*>, name: String): Field? {
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
}
