package cc.axymorrsen.phigrosextractor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.regex.Pattern

object OggCheckpointManager {
    private const val CHECKPOINT_FOLDER = "PhigrosExtractorCheckpoint"
    private const val OGG_FOLDER = "OGG"
    private const val META_FILE = "checkpoint.properties"
    private const val OLD_APP_ID = "cc.axymorrsen.phigrosextractor"
    private val progressPattern = Pattern.compile("\\[进度\\]\\s+(\\d+)/(\\d+)")

    fun importExternal(
        context: Context,
        treeUri: Uri,
        targetRawDir: File,
        currentVersion: String,
        log: (String) -> Unit
    ): Int {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return 0
        val checkpoint = root.findFile(CHECKPOINT_FOLDER)?.takeIf { it.isDirectory } ?: return 0
        val oggDir = checkpoint.findFile(OGG_FOLDER)?.takeIf { it.isDirectory } ?: return 0
        val metaDoc = checkpoint.findFile(META_FILE)?.takeIf { it.isFile } ?: return 0

        val props = Properties()
        context.contentResolver.openInputStream(metaDoc.uri)?.use { props.load(it) } ?: return 0
        val version = props.getProperty("phigros_version", "")
        val expected = props.getProperty("ogg_count", "0").toIntOrNull() ?: 0
        val oggs = oggDir.listFiles().filter {
            it.isFile && it.name?.endsWith(".ogg", ignoreCase = true) == true
        }
        if (version != currentVersion || expected <= 0 || oggs.size != expected) return 0

        targetRawDir.mkdirs()
        MainActivity.deleteRecursively(targetRawDir)
        targetRawDir.mkdirs()
        oggs.forEachIndexed { index, doc ->
            val name = doc.name ?: "track_$index.ogg"
            val dst = File(targetRawDir, MainActivity.safeFileName(name.removeSuffix(".ogg")) + ".ogg")
            val input = context.contentResolver.openInputStream(doc.uri)
                ?: throw IllegalStateException("无法读取外部 OGG 断点：$name")
            BufferedInputStream(input).use { src ->
                BufferedOutputStream(FileOutputStream(dst)).use { out ->
                    MainActivity.copyStream(src, out)
                }
            }
            if (dst.length() <= 0L) throw IllegalStateException("外部 OGG 断点为空：$name")
        }
        log("[外部断点] 已从输出目录恢复 ${oggs.size} 首 OGG。")
        return oggs.size
    }

    fun exportExternal(
        context: Context,
        treeUri: Uri,
        rawFiles: List<File>,
        currentVersion: String,
        log: (String) -> Unit
    ) {
        if (rawFiles.isEmpty()) return
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问输出目录，不能保存 OGG 断点。")
        val checkpoint = root.findFile(CHECKPOINT_FOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(CHECKPOINT_FOLDER)
            ?: throw IllegalStateException("无法创建 $CHECKPOINT_FOLDER")
        val oggDir = checkpoint.findFile(OGG_FOLDER)?.takeIf { it.isDirectory }
            ?: checkpoint.createDirectory(OGG_FOLDER)
            ?: throw IllegalStateException("无法创建 OGG 断点目录")

        val oldMeta = checkpoint.findFile(META_FILE)
        val existingCount = oggDir.listFiles().count {
            it.isFile && it.name?.endsWith(".ogg", ignoreCase = true) == true
        }
        if (oldMeta != null && existingCount == rawFiles.size) {
            val props = Properties()
            runCatching {
                context.contentResolver.openInputStream(oldMeta.uri)?.use { props.load(it) }
            }
            if (props.getProperty("phigros_version", "") == currentVersion &&
                props.getProperty("ogg_count", "0").toIntOrNull() == rawFiles.size
            ) {
                log("[外部断点] 已存在 ${rawFiles.size} 首完整 OGG，无需重复备份。")
                return
            }
        }

        // Manifest is written last. If copying is interrupted, a later run will
        // not mistake a partial directory for a completed checkpoint.
        checkpoint.findFile(META_FILE)?.delete()
        oggDir.listFiles().forEach { if (it.isFile) it.delete() }

        rawFiles.forEachIndexed { index, src ->
            val doc = oggDir.createFile("audio/ogg", src.name)
                ?: throw IllegalStateException("无法创建 OGG 断点：${src.name}")
            val output = context.contentResolver.openOutputStream(doc.uri, "wt")
                ?: throw IllegalStateException("无法写入 OGG 断点：${src.name}")
            BufferedInputStream(FileInputStream(src)).use { input ->
                BufferedOutputStream(output).use { out -> MainActivity.copyStream(input, out) }
            }
            if ((index + 1) % 25 == 0 || index + 1 == rawFiles.size) {
                log("[外部断点] 已备份 ${index + 1}/${rawFiles.size} 首 OGG。")
            }
        }

        val props = Properties().apply {
            setProperty("phigros_version", currentVersion)
            setProperty("ogg_count", rawFiles.size.toString())
            setProperty("completed", "true")
            setProperty("updated_at", System.currentTimeMillis().toString())
        }
        val bytes = ByteArrayOutputStream().use { out ->
            props.store(out, "Phigros OGG checkpoint")
            out.toByteArray()
        }
        val meta = checkpoint.createFile("text/plain", META_FILE)
            ?: throw IllegalStateException("无法创建 OGG 断点清单")
        context.contentResolver.openOutputStream(meta.uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("无法写入 OGG 断点清单")
        log("[外部断点] ${rawFiles.size} 首 OGG 已安全保存到 $CHECKPOINT_FOLDER/$OGG_FOLDER。")
    }

    fun takeoverOldAppCache(
        targetRawDir: File,
        log: (String) -> Unit
    ): Int {
        val bases = listOf(
            "/data/user/0/$OLD_APP_ID/cache/phigros_extract_v4",
            "/data/data/$OLD_APP_ID/cache/phigros_extract_v4"
        )
        for (base in bases) {
            val progress = rootReadText("$base/extract-progress.log") ?: continue
            if (!extractionFinished(progress)) continue
            val paths = rootListOgg("$base/raw")
            if (paths.isEmpty()) continue

            MainActivity.deleteRecursively(targetRawDir)
            targetRawDir.mkdirs()
            var copied = 0
            paths.forEach { source ->
                val name = source.substringAfterLast('/')
                val dst = File(targetRawDir, MainActivity.safeFileName(name.removeSuffix(".ogg")) + ".ogg")
                rootCopy(source, dst)
                if (dst.length() > 0L) copied++
            }
            if (copied > 0) {
                log("[旧版接管] 已通过 Root 从当前已安装旧版接管 $copied 首 OGG；无需重新解包。")
                return copied
            }
        }
        return 0
    }

    private fun extractionFinished(text: String): Boolean {
        if (text.contains("[汇总] 解析结束：")) return true
        val matcher = progressPattern.matcher(text)
        var complete = false
        while (matcher.find()) {
            val done = matcher.group(1)?.toIntOrNull() ?: continue
            val total = matcher.group(2)?.toIntOrNull() ?: continue
            if (total > 0 && done == total) complete = true
        }
        return complete
    }

    private fun rootReadText(path: String): String? = try {
        val p = ProcessBuilder("su", "-c", "cat ${MainActivity.shellQuote(path)}").start()
        val bytes = p.inputStream.use { MainActivity.readFully(it) }
        if (p.waitFor() != 0) null else String(bytes, StandardCharsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    private fun rootListOgg(dir: String): List<String> = try {
        val command = "for f in ${MainActivity.shellQuote(dir)}/*.ogg; do [ -f \"\$f\" ] && printf '%s\\n' \"\$f\"; done"
        val p = ProcessBuilder("su", "-c", command).start()
        val text = p.inputStream.use { String(MainActivity.readFully(it), StandardCharsets.UTF_8) }
        if (p.waitFor() != 0) emptyList() else text.lineSequence().map { it.trim() }
            .filter { it.endsWith(".ogg", ignoreCase = true) }.toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun rootCopy(source: String, destination: File) {
        val p = ProcessBuilder("su", "-c", "cat ${MainActivity.shellQuote(source)}").start()
        BufferedInputStream(p.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                MainActivity.copyStream(input, output)
            }
        }
        val code = p.waitFor()
        if (code != 0 || destination.length() <= 0L) {
            destination.delete()
            throw IllegalStateException("Root 接管 OGG 失败：${source.substringAfterLast('/')}")
        }
    }
}
