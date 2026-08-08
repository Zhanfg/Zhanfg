package cc.axymorrsen.phigrosextractor

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object AudioProcessor {
    fun convert(
        inputOgg: File,
        outputFile: File,
        coverFile: File?,
        format: OutputFormat,
        meta: TrackMeta
    ) {
        outputFile.parentFile?.mkdirs()
        val temp = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".part." + outputFile.extension)
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

        args += "-map_metadata"
        args += "-1"
        args += "-metadata"
        args += "title=${meta.title}"
        args += "-metadata"
        args += "artist=${meta.composer.ifBlank { "Unknown Artist" }}"
        args += "-metadata"
        args += "album=Phigros"
        args += "-metadata"
        args += "album_artist=Various Artists"
        args += "-metadata"
        args += "genre=Rhythm Game"
        if (meta.illustrator.isNotBlank()) {
            args += "-metadata"
            args += "comment=Illustration: ${meta.illustrator}"
        }
        args += temp.absolutePath

        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
        val rc = session.getReturnCode()
        if (!ReturnCode.isSuccess(rc)) {
            val output = session.getOutput()?.takeLast(4000).orEmpty()
            temp.delete()
            throw IllegalStateException(
                "FFmpeg 转码失败 (${format.displayName}, rc=$rc)" +
                    if (output.isBlank()) "" else "\n$output"
            )
        }
        if (!temp.isFile || temp.length() <= 0L) {
            temp.delete()
            throw IllegalStateException("FFmpeg 返回成功，但没有生成有效输出：${outputFile.name}")
        }

        if (outputFile.exists()) outputFile.delete()
        if (!temp.renameTo(outputFile)) {
            temp.copyTo(outputFile, overwrite = true)
            temp.delete()
        }
    }
}
