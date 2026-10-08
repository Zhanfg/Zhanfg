package cc.axymorrsen.amtoolnext.hook

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguagePolicyTest {
    @Test
    fun chineseCandidatesArePrependedWithoutDuplicates() {
        val actual = LanguagePolicy.translations(
            arrayOf("en-GB", "zh-CN", "tr-TR"),
            enabled = true,
        )
        assertArrayEquals(
            arrayOf("zh-Hans-CN", "zh-Hans", "zh-CN", "en-GB", "tr-TR"),
            actual,
        )
    }

    @Test
    fun disabledTranslationPolicyPreservesOriginalOrder() {
        val original = arrayOf("en-GB", "tr-TR")
        assertArrayEquals(original, LanguagePolicy.translations(original, enabled = false))
    }

    @Test
    fun pronunciationCandidatesAreOptional() {
        val actual = LanguagePolicy.pronunciations(arrayOf("tr-Latn"), enabled = true)
        assertArrayEquals(
            arrayOf("zh-Latn", "ja-Latn", "ko-Latn", "tr-Latn"),
            actual,
        )
    }

    @Test
    fun regionQualifiedOfficialTranslationIsSelectedForSystemChinese() {
        assertEquals(
            "zh-Hans-CN",
            LanguagePolicy.selectAvailableTranslation(
                requestedLanguage = "zh-Hans",
                availableLanguages = listOf("zh-Hans-CN"),
            ),
        )
    }

    @Test
    fun unrelatedTranslationLanguageIsNotSelected() {
        assertNull(
            LanguagePolicy.selectAvailableTranslation(
                requestedLanguage = "zh-Hans",
                availableLanguages = listOf("ja-JP", "ko-KR"),
            )
        )
    }
}
