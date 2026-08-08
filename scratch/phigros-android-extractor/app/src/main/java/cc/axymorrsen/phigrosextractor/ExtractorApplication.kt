package cc.axymorrsen.phigrosextractor

import com.chaquo.python.android.PyApplication
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

class ExtractorApplication : PyApplication() {
    override fun onCreate() {
        super.onCreate()

        // Recovery builds should leave a user-visible OGG copy after the first
        // successful run. This avoids tying future resume data to an APK signing
        // certificate or Android app-private storage.
        getSharedPreferences("prefs", MODE_PRIVATE).edit().apply {
            if (!getSharedPreferences("prefs", MODE_PRIVATE).contains("keep_ogg")) {
                putBoolean("keep_ogg", true)
            }
        }.apply()

        runCatching { recoverOldOggCheckpointIfNeeded() }
    }

    private fun recoverOldOggCheckpointIfNeeded() {
        val version = runCatching {
            packageManager.getPackageInfo("com.PigeonGames.Phigros", 0).versionName.orEmpty()
        }.getOrDefault("")
        if (version.isBlank()) return

        val root = File(filesDir, "phigros_ogg_checkpoint_v1")
        val raw = File(root, "raw")
        val meta = File(root, "checkpoint.properties")
        val existing = raw.listFiles { f ->
            f.isFile && f.length() > 0L && f.extension.equals("ogg", true)
        }.orEmpty()

        if (existing.isNotEmpty() && meta.isFile) {
            val props = Properties().apply { FileInputStream(meta).use(::load) }
            val storedVersion = props.getProperty("phigros_version", "")
            val storedCount = props.getProperty("ogg_count", "0").toIntOrNull() ?: 0
            if (storedVersion == version && storedCount == existing.size) return
        }

        val copied = OggCheckpointManager.takeoverOldAppCache(raw) { message ->
            File(filesDir, "startup-recovery.log").appendText(message + "\n", Charsets.UTF_8)
        }
        if (copied <= 0) return

        root.mkdirs()
        val props = Properties().apply {
            setProperty("phigros_version", version)
            setProperty("ogg_count", copied.toString())
            setProperty("completed", "true")
            setProperty("recovered_from", "cc.axymorrsen.phigrosextractor")
            setProperty("updated_at", System.currentTimeMillis().toString())
        }
        FileOutputStream(meta).use { props.store(it, "Recovered Phigros OGG checkpoint") }
    }
}
