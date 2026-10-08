package cc.axymorrsen.amtoolnext.hook

internal object LanguagePolicy {
    /**
     * Chinese metadata must come from the CN catalog, not US + l=zh-Hans.
     * Apple localizes many album / playlist / editorial names by storefront.
     */
    const val CONTENT_STOREFRONT = "cn"
    const val CONTENT_LANGUAGE = "zh-Hans-CN"
    const val LYRICS_SYSTEM_LANGUAGE = "zh-Hans"

    private val simplifiedChinese = listOf("zh-Hans-CN", "zh-Hans", "zh-CN")
    private val pronunciation = listOf("zh-Latn", "ja-Latn", "ko-Latn")

    fun translations(original: Array<*>?, enabled: Boolean): Array<String> {
        val old = original.orEmpty().filterIsInstance<String>()
        if (!enabled) return old.toTypedArray()
        return orderedDistinct(simplifiedChinese + old).toTypedArray()
    }

    fun pronunciations(original: Array<*>?, enabled: Boolean): Array<String> {
        val old = original.orEmpty().filterIsInstance<String>()
        if (!enabled) return old.toTypedArray()
        return orderedDistinct(pronunciation + old).toTypedArray()
    }

    /**
     * Apple may expose zh-Hans-CN but later ask SongInfo with zh-Hans.
     * Resolve the requested language to the concrete tag actually present in SongInfo.
     */
    fun selectAvailableTranslation(
        requestedLanguage: String,
        availableLanguages: List<String>,
    ): String? {
        val requested = parts(requestedLanguage) ?: return null
        val available = availableLanguages.mapNotNull { language ->
            parts(language)?.let { language to it }
        }
        return available.firstOrNull { (_, p) -> p.normalized == requested.normalized }?.first
            ?: available.firstOrNull { (_, p) ->
                p.language == requested.language &&
                    p.script != null &&
                    p.script == requested.script
            }?.first
            ?: available.firstOrNull { (_, p) ->
                p.language == requested.language &&
                    p.region != null &&
                    p.region == requested.region
            }?.first
            ?: available.firstOrNull { (_, p) -> p.language == requested.language }?.first
    }

    private data class LanguageParts(
        val normalized: String,
        val language: String,
        val script: String?,
        val region: String?,
    )

    private fun parts(raw: String): LanguageParts? {
        val normalized = raw.trim().replace('_', '-').lowercase()
        if (normalized.isEmpty()) return null
        val tokens = normalized.split('-').filter(String::isNotEmpty)
        val language = tokens.firstOrNull() ?: return null
        val script = tokens.firstOrNull { it.length == 4 }
        val region = tokens.firstOrNull {
            (it.length == 2 && it != language) || it.length == 3 && it.all(Char::isDigit)
        }
        return LanguageParts(normalized, language, script, region)
    }

    private fun orderedDistinct(values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.map(String::trim).filter(String::isNotEmpty).filter { value ->
            seen.add(value.replace('_', '-').lowercase())
        }
    }
}
