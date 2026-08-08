package cc.axymorrsen.phigrosextractor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.Executors

private const val COVER_MIGRATION_PREFS = "cover_migration"
private const val COVER_MIGRATION_KEY = "cover_pipeline_v2"
private const val EXPORT_INDEX_FILE = "phigros_export_index_v1.json"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class RecoveryActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var status by mutableStateOf("正在检查 OGG 断点…")
    private var detail by mutableStateOf("请先不要卸载旧版 Phigros Music Extractor。")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RecoveryScreen(status, detail) }
        executor.execute(::recoverThenLaunch)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun recoverThenLaunch() {
        try {
            invalidateLegacyNoCoverExportsOnce()

            val version = runCatching {
                packageManager.getPackageInfo("com.PigeonGames.Phigros", 0).versionName.orEmpty()
            }.getOrDefault("")
            if (version.isBlank()) {
                update("未检测到 Phigros", "将进入主界面，由增量流程继续检查。")
                launchMain()
                return
            }

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
                if (storedVersion == version && storedCount == existing.size) {
                    update(
                        "已有 OGG 断点",
                        "${existing.size} 首可直接继续转码；旧版无封面输出会在本轮强制重写，不重新解包。"
                    )
                    launchMain()
                    return
                }
            }

            update("正在接管旧版 OGG…", "通过 Root 读取上一版已经完成的 OGG；不会重新解 Phigros。")
            val copied = OggCheckpointManager.takeoverOldAppCache(raw) { message ->
                update("正在接管旧版 OGG…", message)
            }

            if (copied > 0) {
                root.mkdirs()
                val props = Properties().apply {
                    setProperty("phigros_version", version)
                    setProperty("ogg_count", copied.toString())
                    setProperty("completed", "true")
                    setProperty("recovered_from", "cc.axymorrsen.phigrosextractor")
                    setProperty("updated_at", System.currentTimeMillis().toString())
                }
                FileOutputStream(meta).use {
                    props.store(it, "Recovered Phigros OGG checkpoint")
                }
                update(
                    "OGG 接管完成",
                    "$copied 首已转入持久断点；本轮直接重写 FLAC/MP3 封面与标签，不重新解包。"
                )
            } else {
                update("未发现可接管的旧 OGG", "主界面将按增量/正常流程处理。")
            }
            launchMain()
        } catch (t: Throwable) {
            update("断点接管失败", t.message ?: t.javaClass.simpleName)
            launchMain()
        }
    }

    /**
     * Earlier builds could record a successful export index after falling back
     * to an audio-only file when cover embedding failed. That makes newer builds
     * incorrectly skip those files as unchanged. Invalidate only the export
     * index once; the persistent OGG checkpoint remains untouched.
     */
    private fun invalidateLegacyNoCoverExportsOnce() {
        val prefs = getSharedPreferences(COVER_MIGRATION_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(COVER_MIGRATION_KEY, false)) return

        val index = File(filesDir, EXPORT_INDEX_FILE)
        if (index.exists() && !index.delete()) {
            throw IllegalStateException("无法清除旧版无封面导出索引")
        }
        prefs.edit().putBoolean(COVER_MIGRATION_KEY, true).apply()
        update("正在迁移封面管线…", "OGG 断点保留；旧 FLAC/MP3 将只重做封面与标签阶段。")
    }

    private fun update(title: String, text: String) = runOnUiThread {
        status = title
        detail = text
    }

    private fun launchMain() {
        runOnUiThread {
            startActivity(Intent(this, IncrementalMainActivity::class.java))
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecoveryScreen(status: String, detail: String) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(colorScheme = colors) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "OGG 断点接管",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
