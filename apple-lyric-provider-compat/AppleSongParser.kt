/*
 * Copyright 2026 Andrea-TB
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.andrealtb.coloroslyrics.provider.apple

object AppleSongParser {
    fun parse(songNative: Any): AppleSongModel? {
        val adamId = AppleNativeCalls.call(songNative, "getAdamId")?.toString()
            ?.takeIf { it.isNotBlank() && it != "0" }
            ?: return null
        val duration = AppleNativeCalls.callInt(songNative, "getDuration")
        val sections = AppleNativeCalls.call(songNative, "getSections")
        val lyrics = if (sections != null) parseSectionVector(sections) else emptyList()
        return AppleSongModel(
            adamId = adamId,
            durationMs = duration.toLong(),
            lyrics = lyrics
        )
    }

    fun applySystemTranslation(songNative: Any, language: String?): Boolean {
        val requested = language?.trim()?.takeIf(String::isNotEmpty) ?: return false
        if (AppleNativeCalls.callBoolean(songNative, "setTranslation", requested) == true) {
            return true
        }

        val available = AppleNativeCalls.call(songNative, "getTranslationLanguages")
            ?.let(::stringVector)
            .orEmpty()
        val selected = selectCompatibleLanguage(requested, available) ?: return false
        return AppleNativeCalls.callBoolean(songNative, "setTranslation", selected) == true
    }

    private fun selectCompatibleLanguage(
        requested: String,
        available: List<String>
    ): String? {
        val target = parseLanguage(requested) ?: return null
        val parsed = available.mapNotNull { candidate ->
            parseLanguage(candidate)?.let { candidate to it }
        }
        return parsed.firstOrNull { (_, value) -> value.normalized == target.normalized }?.first
            ?: parsed.firstOrNull { (_, value) ->
                value.language == target.language &&
                    value.script != null &&
                    target.script != null &&
                    value.script == target.script
            }?.first
            ?: parsed.firstOrNull { (_, value) ->
                value.language == target.language &&
                    value.region != null &&
                    target.region != null &&
                    value.region == target.region
            }?.first
            ?: parsed.firstOrNull { (_, value) -> value.language == target.language }?.first
    }

    private data class LanguageParts(
        val normalized: String,
        val language: String,
        val script: String?,
        val region: String?
    )

    private fun parseLanguage(raw: String): LanguageParts? {
        val normalized = raw.trim().replace('_', '-').lowercase()
        if (normalized.isEmpty()) return null
        val parts = normalized.split('-').filter(String::isNotEmpty)
        val language = parts.firstOrNull() ?: return null
        val script = parts.firstOrNull { it.length == 4 }
        val region = parts.firstOrNull {
            (it.length == 2 && it != language) || (it.length == 3 && it.all(Char::isDigit))
        }
        return LanguageParts(normalized, language, script, region)
    }

    private fun stringVector(vector: Any): List<String> {
        val size = AppleNativeCalls.vectorSize(vector).coerceIn(0L, 128L)
        val result = ArrayList<String>(size.toInt())
        var index = 0L
        while (index < size) {
            (AppleNativeCalls.vectorItem(vector, index) as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(result::add)
            index++
        }
        return result
    }

    private fun parseSectionVector(vector: Any): List<AppleLyricLineModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val lines = mutableListOf<AppleLyricLineModel>()
        var index = 0L
        while (index < size) {
            val sectionNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (sectionNative != null) {
                AppleNativeCalls.call(sectionNative, "getLines")?.let { lineVector ->
                    lines += parseLineVector(lineVector)
                }
            }
            index++
        }
        return lines
    }

    private fun parseLineVector(vector: Any): List<AppleLyricLineModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val lines = mutableListOf<AppleLyricLineModel>()
        var index = 0L
        while (index < size) {
            val lineNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (lineNative != null) {
                lines += parseLine(lineNative)
            }
            index++
        }
        return lines
    }

    private fun parseLine(lineNative: Any): AppleLyricLineModel {
        val words = AppleNativeCalls.call(lineNative, "getWords")?.let(::parseWordVector).orEmpty()
        val backgroundWords = parseBackgroundWords(lineNative)
        return AppleLyricLineModel(
            begin = AppleNativeCalls.callInt(lineNative, "getBegin"),
            end = AppleNativeCalls.callInt(lineNative, "getEnd"),
            duration = AppleNativeCalls.callInt(lineNative, "getDuration"),
            htmlLineText = AppleNativeCalls.callString(lineNative, "getHtmlLineText"),
            htmlTranslationLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlTranslationLineText"),
            htmlBackgroundVocalsLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlBackgroundVocalsLineText"),
            htmlPronunciationLineText =
                AppleNativeCalls.callString(lineNative, "getHtmlPronunciationLineText"),
            words = words,
            backgroundWords = backgroundWords
        )
    }

    private fun parseBackgroundWords(lineNative: Any): List<AppleLyricWordModel> {
        val withFlag = AppleNativeCalls.call(lineNative, "getBackgroundWords", false)
        val vector = withFlag ?: AppleNativeCalls.call(lineNative, "getBackgroundWords")
        return vector?.let(::parseWordVector).orEmpty()
    }

    private fun parseWordVector(vector: Any): List<AppleLyricWordModel> {
        val size = AppleNativeCalls.vectorSize(vector)
        val words = mutableListOf<AppleLyricWordModel>()
        var index = 0L
        while (index < size) {
            val wordNative = AppleNativeCalls.unwrapPtr(AppleNativeCalls.vectorItem(vector, index))
            if (wordNative != null) {
                words += AppleLyricWordModel(
                    begin = AppleNativeCalls.callInt(wordNative, "getBegin"),
                    end = AppleNativeCalls.callInt(wordNative, "getEnd"),
                    duration = AppleNativeCalls.callInt(wordNative, "getDuration"),
                    text = AppleNativeCalls.callString(wordNative, "getHtmlLineText"),
                    whitespace = AppleNativeCalls.callBoolean(wordNative, "isWhitespace") == true
                )
            }
            index++
        }
        return words
    }
}
