package cc.axymorrsen.phigrosextractor

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File

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
                "无法读取原始音频参数：${session.getOutput()?.takeLast(2000).orEmpty()}"
            )
        }

        val root = JSONObject(session.getOutput().orEmpty())
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
                "检测到 Hi-Res 源参数 ${source.describe()}。MP3 标准无法保留 >48 kHz 或 >16-bit 的 Hi-Res 属性；" +
                    "为防止静默降级，本曲已停止转换，请改选 FLAC。"
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

        if (coverFile != null && coverFile.isFile) {
            args += "-i"
            args += coverFile.absolutePath
        }

        args += "-map"
        args += "0:a:0"
        if (coverFile != null && coverFile.isFile) {
            args += "-map"
            args += "1:v:0"
        }

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
                if (coverFile != null && coverFile.isFile) {
                    args += "-c:v"
                    args += "mjpeg"
                    args += "-disposition:v:0"
                    args += "attached_pic"
                    args += "-metadata:s:v"
                    args += "title=Album cover"
                    args += "-metadata:s:v"
                    args += "comment=Cover (front)"
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
                    // Vorbis itself has no PCM bit-depth field. For high-rate or
                    // genuinely >16-bit sources, use the FLAC encoder's s32 path
                    // so decoded precision isn't truncated to 16-bit on export.
                    args += "-sample_fmt"
                    args += "s32"
                }
                if (coverFile != null && coverFile.isFile) {
                    args += "-c:v"
                    args += "copy"
                    args += "-disposition:v:0"
                    args += "attached_pic"
                    args += "-metadata:s:v"
                    args += "title=Album cover"
                    args += "-metadata:s:v"
                    args += "comment=Cover (front)"
                }
            }
        }

        // Preserve every metadata field already present in the rebuilt source,
        // then override/add authoritative Phigros fields below.
        args += "-map_metadata"
        args += "0"

        addMetadata(args, "title", meta.title)
        if (meta.composer.isNotBlank()) {
            addMetadata(args, "artist", meta.composer)
            addMetadata(args, "composer", meta.composer)
        }
        if (meta.chapter.isNotBlank()) {
            // Phigros exposes chapter rather than a conventional album field.
            // Mirroring chapter into ALBUM makes normal music libraries group it
            // correctly while GROUPING retains the original semantic meaning.
            addMetadata(args, "album", meta.chapter)
            addMetadata(args, "grouping", meta.chapter)
            addMetadata(args, "chapter", meta.chapter)
        }
        meta.remoteId?.let { addMetadata(args, "track", it.toString()) }
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

        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val rc = session.getReturnCode()
        if (!ReturnCode.isSuccess(rc)) {
            val output = session.getOutput()?.takeLast(4000).orEmpty()
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

        if (outputFile.exists()) outputFile.delete()
        if (!temp.renameTo(outputFile)) {
            temp.copyTo(outputFile, overwrite = true)
            temp.delete()
        }
    }

    private fun addMetadata(args: MutableList<String>, key: String, value: String) {
        if (value.isBlank()) return
        args += "-metadata"
        args += "$key=$value"
    }
}
