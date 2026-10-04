package cc.axymorrsen.amtoolnext.hook

internal object LanguagePolicy {
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

    private fun orderedDistinct(values: List<String>): List<String> {
        val seen = HashSet<String>()
        return values.map(String::trim).filter(String::isNotEmpty).filter { value ->
            seen.add(value.replace('_', '-').lowercase())
        }
    }
}
