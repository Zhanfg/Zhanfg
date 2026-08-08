package cc.axymorrsen.phigrosextractor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

data class TrackMeta(
    val songId: String,
    val title: String,
    val composer: String,
    val illustrator: String,
    val songKey: String = "",
    val songTitle: String = "",
    val chapter: String = "",
    val charters: List<String> = emptyList(),
    val difficulties: List<String> = emptyList(),
    val levelNames: List<String> = emptyList(),
    val hasLegacy: Boolean = false,
    val remoteId: Int? = null
)

private data class MusicRecord(
    val id: Int,
    val title: String,
    val composer: String,
    val chapter: String,
    val illustrator: String,
    val charters: List<String>,
    val difficulties: List<String>,
    val hasLegacy: Boolean
)

object RemoteCatalog {
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/SonolusHaniwa/phigros-decrypted-data/main"
    private const val METADATA_URL = "$RAW_BASE/metadata.json"
    private const val MUSIC_URL = "$RAW_BASE/music.json"
    private const val EMBEDDED_COVER_MAX_EDGE = 1600

    fun load(
        context: android.content.Context,
        cacheDir: File,
        log: (String) -> Unit
    ): Map<String, TrackMeta> {
        cacheDir.mkdirs()
        val metadataFile = File(cacheDir, "metadata.json")
        val musicFile = File(cacheDir, "music.json")

        fetchTextToFile(METADATA_URL, metadataFile)
        fetchTextToFile(MUSIC_URL, musicFile)

        val metadata = JSONArray(metadataFile.readText(Charsets.UTF_8))
        val music = JSONArray(musicFile.readText(Charsets.UTF_8))

        val byId = HashMap<Int, MusicRecord>()
        val byStrongKey = HashMap<String, Int>()
        val byTitle = HashMap<String, MutableList<Pair<Int, String>>>()

        for (i in 0 until music.length()) {
            val item = music.getJSONObject(i)
            val id = item.optInt("id", -1)
            if (id < 0) continue
            val record = MusicRecord(
                id = id,
                title = item.optString("title"),
                composer = item.optString("composer"),
                chapter = item.optString("chapter"),
                illustrator = item.optString("illustrator"),
                charters = item.optStringList("charter"),
                difficulties = item.optStringList("level"),
                hasLegacy = item.optBoolean("hasLegacy", false)
            )
            byId[id] = record
            byStrongKey[strongKey(record.title, record.composer)] = id
            byTitle.getOrPut(norm(record.title)) { ArrayList() }.add(id to record.composer)
        }

        val result = LinkedHashMap<String, TrackMeta>()
        for (i in 0 until metadata.length()) {
            val item = metadata.getJSONObject(i)
            val songId = item.optString("songId")
            if (songId.isBlank()) continue

            val title = item.optString("songName").ifBlank {
                item.optString("songKey").ifBlank { songId }
            }
            val composer = item.optString("composer")
            val illustrator = item.optString("illustrator")

            var remoteId: Int? = byStrongKey[strongKey(title, composer)]

            if (remoteId == null && i < music.length()) {
                val candidate = music.getJSONObject(i)
                val cTitle = candidate.optString("title")
                val cComposer = candidate.optString("composer")
                if (norm(cTitle) == norm(title) &&
                    (norm(cComposer) == norm(composer) || norm(composer).isBlank())
                ) {
                    candidate.optInt("id", -1).takeIf { it >= 0 }?.let { remoteId = it }
                }
            }

            if (remoteId == null) {
                val candidates = byTitle[norm(title)].orEmpty()
                remoteId = when {
                    candidates.size == 1 -> candidates.first().first
                    candidates.isNotEmpty() -> candidates.minByOrNull {
                        editDistance(norm(it.second), norm(composer))
                    }?.first
                    else -> null
                }
            }

            val musicRecord = remoteId?.let(byId::get)
            val value = TrackMeta(
                songId = songId,
                title = title,
                composer = composer.ifBlank { musicRecord?.composer.orEmpty() },
                illustrator = illustrator.ifBlank { musicRecord?.illustrator.orEmpty() },
                songKey = item.optString("songKey"),
                songTitle = item.optString("songTitle"),
                chapter = musicRecord?.chapter.orEmpty(),
                charters = item.optStringList("charter").ifEmpty {
                    musicRecord?.charters.orEmpty()
                },
                difficulties = item.optStringList("difficulty").ifEmpty {
                    musicRecord?.difficulties.orEmpty()
                },
                levelNames = item.optStringList("levels"),
                hasLegacy = musicRecord?.hasLegacy ?: false,
                remoteId = remoteId
            )
            result[songId] = value
            result.putIfAbsent(MainActivity.safeFileName(songId), value)
        }

        val unique = result.values.distinctBy { it.songId }
        log(
            "曲目信息已匹配：${unique.size} 条；章节 ${unique.count { it.chapter.isNotBlank() }} 条；" +
                "谱师 ${unique.count { it.charters.isNotEmpty() }} 条；可定位封面 ${unique.count { it.remoteId != null }} 条。"
        )
        return result
    }

    fun downloadCover(remoteId: Int, destination: File) {
        destination.parentFile?.mkdirs()
        val downloaded = File(destination.parentFile, destination.name + ".download")
        val normalized = File(destination.parentFile, destination.name + ".normalized")
        val url = "$RAW_BASE/music/$remoteId/cover.png"
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}：cover $remoteId")
            }
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(downloaded)).use { output ->
                    MainActivity.copyStream(input, output)
                }
            }
            if (downloaded.length() < 1024L) {
                throw IllegalStateException("封面文件异常小：${downloaded.length()} B")
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(downloaded.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalStateException(
                    "封面无法解析尺寸：${downloaded.length()} B，${bounds.outMimeType ?: "unknown mime"}"
                )
            }

            val source = BitmapFactory.decodeFile(downloaded.absolutePath)
                ?: throw IllegalStateException(
                    "封面解码失败：${bounds.outWidth}x${bounds.outHeight}，${bounds.outMimeType ?: "unknown mime"}"
                )

            val maxEdge = maxOf(source.width, source.height)
            val target = if (maxEdge > EMBEDDED_COVER_MAX_EDGE) {
                val scale = EMBEDDED_COVER_MAX_EDGE.toFloat() / maxEdge.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).roundToInt().coerceAtLeast(1),
                    (source.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            } else source

            try {
                FileOutputStream(normalized).use { output ->
                    // JPEG is intentionally used for embedded album art. It is
                    // substantially smaller than the source PNG and has better
                    // compatibility with Android media scanners and music apps.
                    if (!target.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                        throw IllegalStateException("封面标准化 JPEG 写入失败")
                    }
                    output.fd.sync()
                }
            } finally {
                if (target !== source) target.recycle()
                source.recycle()
            }

            val check = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(normalized.absolutePath, check)
            if (check.outWidth <= 0 || check.outHeight <= 0 || normalized.length() < 128L) {
                throw IllegalStateException("标准化后的 JPEG 封面仍不可解析")
            }

            if (destination.exists()) destination.delete()
            if (!normalized.renameTo(destination)) {
                normalized.copyTo(destination, overwrite = true)
                normalized.delete()
            }
        } finally {
            conn.disconnect()
            if (downloaded.exists()) downloaded.delete()
            if (normalized.exists()) normalized.delete()
        }
    }

    private fun fetchTextToFile(url: String, destination: File) {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}：$url")
            }
            val temp = File(destination.parentFile, destination.name + ".part")
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(temp)).use { output ->
                    MainActivity.copyStream(input, output)
                }
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PhigrosMusicExtractor/0.3")
            setRequestProperty("Accept", "application/json,image/png,image/jpeg,*/*")
            instanceFollowRedirects = true
        }

    private fun JSONObject.optStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun strongKey(title: String, composer: String): String =
        norm(title) + "|" + norm(composer)

    private fun norm(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        return normalized
            .replace(Regex("""[\s\p{P}\p{S}]+"""), "")
            .trim()
    }

    private fun editDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(
                    cur[j] + 1,
                    prev[j + 1] + 1,
                    prev[j] + cost
                )
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length]
    }
}
