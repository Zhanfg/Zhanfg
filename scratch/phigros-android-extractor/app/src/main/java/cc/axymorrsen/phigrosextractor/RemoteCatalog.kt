package cc.axymorrsen.phigrosextractor

import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

data class TrackMeta(
    val songId: String,
    val title: String,
    val composer: String,
    val illustrator: String,
    val remoteId: Int?
)

object RemoteCatalog {
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/SonolusHaniwa/phigros-decrypted-data/main"
    private const val METADATA_URL = "$RAW_BASE/metadata.json"
    private const val MUSIC_URL = "$RAW_BASE/music.json"

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

        val byStrongKey = HashMap<String, Int>()
        val byTitle = HashMap<String, MutableList<Pair<Int, String>>>()

        for (i in 0 until music.length()) {
            val item = music.getJSONObject(i)
            val id = item.optInt("id", -1)
            if (id < 0) continue
            val title = item.optString("title")
            val composer = item.optString("composer")
            byStrongKey[strongKey(title, composer)] = id
            byTitle.getOrPut(norm(title)) { ArrayList() }.add(id to composer)
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

            val value = TrackMeta(
                songId = songId,
                title = title,
                composer = composer,
                illustrator = illustrator,
                remoteId = remoteId
            )
            result[songId] = value
            result.putIfAbsent(MainActivity.safeFileName(songId), value)
        }

        log("曲目信息已匹配：${result.values.distinctBy { it.songId }.size} 条；可定位封面 ${result.values.distinctBy { it.songId }.count { it.remoteId != null }} 条。")
        return result
    }

    fun downloadCover(remoteId: Int, destination: File) {
        destination.parentFile?.mkdirs()
        val temp = File(destination.parentFile, destination.name + ".part")
        val url = "$RAW_BASE/music/$remoteId/cover.png"
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}：cover $remoteId")
            }
            BufferedInputStream(conn.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(temp)).use { output ->
                    MainActivity.copyStream(input, output)
                }
            }
            if (temp.length() < 1024L) {
                throw IllegalStateException("封面文件异常小：${temp.length()} B")
            }
            if (destination.exists()) destination.delete()
            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }
        } finally {
            conn.disconnect()
            if (temp.exists()) temp.delete()
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
            connectTimeout = 12_000
            readTimeout = 25_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PhigrosMusicExtractor/0.2")
            setRequestProperty("Accept", "application/json,image/png,*/*")
            instanceFollowRedirects = true
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
