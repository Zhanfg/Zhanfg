package cc.axymorrsen.phigrosextractor

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class SourceAudioInfo(
    val codecName: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long,
    val bitsPerSample: Int,
    val bitsPerRawSample: Int
) {
    val effectiveBits: Int
        get() = maxOf(bitsPerSample, bitsPerRawSample)

    val isHiRes: Boolean
        get() = sampleRate > 48_000 || effectiveBits > 16

    fun describe(): String = buildString {
        append(codecName.ifBlank { "unknown" })
        if (sampleRate > 0) append(" · ${sampleRate / 1000.0} kHz")
        if (channels > 0) append(" · ${channels}ch")
        if (effectiveBits > 0) append(" · ${effectiveBits}-bit")
        if (bitRate > 0) append(" · ${bitRate / 1000} kbps")
    }
}

object AudioProcessor {
    private const val FFMPEG_TIMEOUT_SECONDS = 90L
    private const val CANCEL_GRACE_SECONDS = 5L

    /**
     * FFmpegKit/FFprobe share native process state. The UnityFS/FSB5 extractor
     * remains multi-threaded, but media conversion is deliberately serialized
     * so two native sessions never block each other on the same Android process.
     */
    @Synchronized
    fun probe(input: File): SourceAudioInfo {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries",
                "stream=codec_name,sample_rate,channels,bit_rate,bits_per_sample,bits_per_raw_sample",
                "-of", "json",
                input.absolutePath
            )
        )
        val rc = session.getReturnCode()
        if (!ReturnCode.isSuccess(rc)) {
            throw IllegalStateException(
                "无法读取原始音频参数：${session.getOutput().takeLast(2000)}"
            )
        }

        val root = JSONObject(session.getOutput())
        val streams = root.optJSONArray("streams")
        if (streams == null || streams.length() == 0) {
            throw IllegalStateException("原始文件没有可识别的音频流：${input.name}")
        }
        val stream = streams.getJSONObject(0)
        return SourceAudioInfo(
            codecName = stream.optString("codec_name"),
            sampleRate = stream.optString("sample_rate").toIntOrNull() ?: 0,
            channels = stream.optInt("channels", 0),
            bitRate = stream.optString("bit_rate").toLongOrNull() ?: 0L,
            bitsPerSample = stream.optInt("bits_per_sample", 0),
            bitsPerRawSample = stream.optString("bits_per_raw_sample").toIntOrNull() ?: 0
        )
    }

    private fun probeTags(input: File): Map<String, String> {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-show_entries", "format_tags",
                "-of", "json",
                input.absolutePath
            )
        )
        if (!ReturnCode.isSuccess(session.getReturnCode())) {
            throw IllegalStateException(
                "无法验收导出标签：${session.getOutput().takeLast(2000)}"
            )
        }
        val tags = JSONObject(session.getOutput())
            .optJSONObject("format")
            ?.optJSONObject("tags")
            ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = tags.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key.lowercase()] = tags.optString(key)
        }
        return result
    }

    @Synchronized
    fun convert(
        inputOgg: File,
        outputFile: File,
        coverFile: File?,
        format: OutputFormat,
        meta: TrackMeta
    ) {
        val source = probe(inputOgg)

        if (format == OutputFormat.MP3 && source.isHiRes) {
            throw IllegalStateException(
                "检测到 Hi-Res 源参数 ${source.describe()}。MP3 无法原样保留 >48 kHz 或 >16-bit 的高规格属性；" +
                    "为防止静默降级，本曲已停止转换，请改选 FLAC。"
            )
        }
        if (format == OutputFormat.FLAC && source.effectiveBits > 24) {
            throw IllegalStateException(
                "检测到 ${source.effectiveBits}-bit 源。FLAC 规范虽支持 32-bit，" +
                    "但当前兼容输出策略只承诺到 24-bit；为避免在部分播放器上静默降位，本曲已停止转换。"
            )
        }

        outputFile.parentFile?.mkdirs()
        val temp = File(
            outputFile.parentFile,
            outputFile.nameWithoutExtension + ".part." + outputFile.extension
        )
        if (temp.exists()) temp.delete()

        val args = ArrayList<String>()
        args += "-hide_banner"
        args += "-loglevel"
        args += "warning"
        args += "-y"
        args += "-i"
        args += inputOgg.absolutePath
        args += "-map"
        args += "0:a:0"

        when (format) {
            OutputFormat.MP3 -> {
                args += "-c:a"
                args += "libmp3lame"
                args += "-b:a"
                args += "320k"
                if (source.sampleRate > 0) {
                    args += "-ar"
                    args += source.sampleRate.toString()
                }
                if (source.channels > 0) {
                    args += "-ac"
                    args += source.channels.toString()
                }
                args += "-id3v2_version"
                args += "3"
            }

            OutputFormat.FLAC -> {
                args += "-c:a"
                args += "flac"
                args += "-compression_level"
                args += "8"
                if (source.sampleRate > 0) {
                    args += "-ar"
                    args += source.sampleRate.toString()
                }
                if (source.channels > 0) {
                    args += "-ac"
                    args += source.channels.toString()
                }
                if (source.isHiRes) {
                    // Vorbis/OGG does not carry a conventional PCM bit-depth
                    // field. For a high-sample-rate source, feed the FLAC
                    // encoder through S32; FFmpeg's compatibility path emits a
                    // 24-bit FLAC while preserving the original sample rate.
                    args += "-sample_fmt"
                    args += "s32"
                }
            }
        }

        // Preserve any source tags first, then add authoritative Phigros fields.
        args += "-map_metadata"
        args += "0"

        addMetadata(args, "title", meta.title)
        if (meta.composer.isNotBlank()) {
            addMetadata(args, "artist", meta.composer)
            addMetadata(args, "composer", meta.composer)
        }
        if (meta.chapter.isNotBlank()) {
            // Phigros exposes chapter rather than a conventional album field.
            // ALBUM is a compatibility projection for ordinary music players;
            // CHAPTER and GROUPING retain the original game semantics as well.
            addMetadata(args, "album", meta.chapter)
            addMetadata(args, "grouping", meta.chapter)
            addMetadata(args, "chapter", meta.chapter)
        }
        meta.remoteId?.let { addMetadata(args, "source_catalog_id", it.toString()) }
        addMetadata(args, "source_game", "Phigros")
        addMetadata(args, "source_song_id", meta.songId)
        addMetadata(args, "source_codec", source.codecName)
        if (source.sampleRate > 0) {
            addMetadata(args, "source_sample_rate", source.sampleRate.toString())
        }
        if (source.channels > 0) {
            addMetadata(args, "source_channels", source.channels.toString())
        }
        if (source.effectiveBits > 0) {
            addMetadata(args, "source_bits_per_sample", source.effectiveBits.toString())
        }
        if (source.bitRate > 0) {
            addMetadata(args, "source_bitrate", source.bitRate.toString())
        }
        if (meta.songKey.isNotBlank()) addMetadata(args, "song_key", meta.songKey)
        if (meta.songTitle.isNotBlank()) addMetadata(args, "song_title", meta.songTitle)
        if (meta.illustrator.isNotBlank()) addMetadata(args, "illustrator", meta.illustrator)
        if (meta.charters.isNotEmpty()) addMetadata(args, "charter", meta.charters.joinToString(" / "))
        if (meta.difficulties.isNotEmpty()) {
            addMetadata(args, "difficulty", meta.difficulties.joinToString(" / "))
        }
        if (meta.levelNames.isNotEmpty()) {
            addMetadata(args, "level_names", meta.levelNames.joinToString(" / "))
        }
        addMetadata(args, "has_legacy", if (meta.hasLegacy) "1" else "0")
        addMetadata(args, "source_quality", source.describe())

        args += temp.absolutePath

        val session = executeFfmpegWithTimeout(args.toTypedArray(), outputFile.name)
        val rc = session.getReturnCode()
        if (!ReturnCode.isSuccess(rc)) {
            val output = session.getOutput().takeLast(4000)
            temp.delete()
            throw IllegalStateException(
                "FFmpeg 转码失败 (${format.displayName}, ${source.describe()}, rc=$rc)" +
                    if (output.isBlank()) "" else "\n$output"
            )
        }
        if (!temp.isFile || temp.length() <= 0L) {
            temp.delete()
            throw IllegalStateException("FFmpeg 返回成功，但没有生成有效输出：${outputFile.name}")
        }

        // Album art is intentionally written AFTER FFmpeg. This removes PNG/
        // MJPEG parsing from the FLAC/MP3 muxing path, which prevents a broken
        // or unsupported image stream from making the whole audio conversion
        // fail with `dimensions not set` / `Could not write header`.
        if (coverFile != null && coverFile.isFile) {
            try {
                Python.getInstance()
                    .getModule("tagger")
                    .callAttr(
                        "embed_cover",
                        temp.absolutePath,
                        coverFile.absolutePath,
                        format.name
                    )
            } catch (t: Throwable) {
                temp.delete()
                throw IllegalStateException(
                    "封面标签写入失败：${t.message ?: t.javaClass.simpleName}",
                    t
                )
            }
        }

        val exported = probe(temp)
        if (source.sampleRate > 0 && exported.sampleRate != source.sampleRate) {
            temp.delete()
            throw IllegalStateException(
                "采样率校验失败：原始 ${source.sampleRate} Hz，导出 ${exported.sampleRate} Hz。已阻止写出降级文件。"
            )
        }
        if (source.channels > 0 && exported.channels != source.channels) {
            temp.delete()
            throw IllegalStateException(
                "声道校验失败：原始 ${source.channels}ch，导出 ${exported.channels}ch。已阻止写出异常文件。"
            )
        }
        if (format == OutputFormat.FLAC && source.isHiRes && exported.effectiveBits in 1..16) {
            temp.delete()
            throw IllegalStateException(
                "Hi-Res 位深校验失败：导出的 FLAC 仅 ${exported.effectiveBits}-bit。已阻止写出。"
            )
        }

        val tags = probeTags(temp)
        verifyTag(tags, "title", meta.title)
        verifyTag(tags, "source_song_id", meta.songId)
        verifyTag(tags, "source_game", "Phigros")
        if (meta.composer.isNotBlank()) {
            verifyTag(tags, "artist", meta.composer)
            verifyTag(tags, "composer", meta.composer)
        }
        if (meta.chapter.isNotBlank()) {
            verifyTag(tags, "album", meta.chapter)
        }
        if (meta.illustrator.isNotBlank()) {
            verifyTag(tags, "illustrator", meta.illustrator)
        }

        if (outputFile.exists()) outputFile.delete()
        if (!temp.renameTo(outputFile)) {
            temp.copyTo(outputFile, overwrite = true)
            temp.delete()
        }
    }

    private fun executeFfmpegWithTimeout(arguments: Array<String>, outputName: String): FFmpegSession {
        val finished = CountDownLatch(1)
        val completed = AtomicReference<FFmpegSession?>(null)
        val session = FFmpegKit.executeWithArgumentsAsync(arguments) { done ->
            completed.set(done)
            finished.countDown()
        }

        if (!finished.await(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            FFmpegKit.cancel(session.getSessionId())
            if (!finished.await(CANCEL_GRACE_SECONDS, TimeUnit.SECONDS)) {
                // Last-resort cancellation: don't allow one wedged native
                // session to keep the whole batch blocked forever.
                FFmpegKit.cancel()
                finished.await(2, TimeUnit.SECONDS)
            }
            throw IllegalStateException(
                "FFmpeg 超过 ${FFMPEG_TIMEOUT_SECONDS}s 无响应，已取消：$outputName。" +
                    "OGG 断点仍保留，可直接重试。"
            )
        }

        return completed.get() ?: session
    }

    private fun addMetadata(args: MutableList<String>, key: String, value: String) {
        if (value.isBlank()) return
        args += "-metadata"
        args += "$key=$value"
    }

    private fun verifyTag(tags: Map<String, String>, key: String, expected: String) {
        if (expected.isBlank()) return
        val actual = tags[key.lowercase()]
        if (actual != expected) {
            throw IllegalStateException(
                "标签验收失败：$key 期望 '$expected'，实际 '${actual ?: "<missing>"}'。已阻止写出。"
            )
        }
    }
}
