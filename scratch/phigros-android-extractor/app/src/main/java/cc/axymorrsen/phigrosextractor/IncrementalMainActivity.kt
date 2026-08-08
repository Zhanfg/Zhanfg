package cc.axymorrsen.phigrosextractor

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private const val V3_PHIGROS_PACKAGE = "com.PigeonGames.Phigros"
private const val V3_OLD_APP_PACKAGE = "cc.axymorrsen.phigrosextractor"
private const val V3_PREFS = "prefs"
private const val V3_KEY_TREE_URI = "tree_uri"
private const val V3_KEY_FORMAT = "output_format"
private const val V3_KEY_KEEP_OGG = "keep_ogg"
private const val V3_KEY_COVER = "cover"
private const val V3_CHECKPOINT_DIR = "phigros_ogg_checkpoint_v1"
private const val V3_CHECKPOINT_META = "checkpoint.properties"
private const val V3_INCREMENTAL_MANIFEST = "incremental_manifest.json"
private const val V3_CURRENT_TRACKS = "current_tracks.json"
private const val V3_EXPORT_INDEX = "phigros_export_index_v1.json"
private const val V3_MAX_LOG_LINES = 900

private enum class V3OutputFormat(val displayName: String, val extension: String) {
    FLAC("FLAC", "flac"),
    MP3("MP3", "mp3");

    fun oldFormat(): OutputFormat = when (this) {
        FLAC -> OutputFormat.FLAC
        MP3 -> OutputFormat.MP3
    }
}

private data class V3UiState(
    val rootOk: Boolean? = null,
    val installed: Boolean = false,
    val version: String = "",
    val splitCount: Int = 0,
    val cacheCount: Int = 0,
    val cacheVersion: String = "",
    val outputUri: Uri? = null,
    val format: V3OutputFormat = V3OutputFormat.FLAC,
    val keepOgg: Boolean = false,
    val cover: Boolean = true,
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "正在检查运行环境…"
)

private data class V3RawTrack(val songId: String, val file: File)
private data class V3TrackPlan(
    val raw: V3RawTrack,
    val meta: TrackMeta,
    val output: File,
    val audioFingerprint: String,
    val metadataFingerprint: String,
    val previousFilename: String?,
    val skipExisting: Boolean
)
private data class V3TrackResult(
    val plan: V3TrackPlan,
    val success: Boolean,
    val coverEmbedded: Boolean,
    val sourceInfo: SourceAudioInfo?,
    val error: String? = null
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class IncrementalMainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val logWatcher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val logs = mutableStateListOf<String>()
    private var uiState by mutableStateOf(V3UiState())
    private var logFuture: ScheduledFuture<*>? = null
    private var lastPythonLogText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        restorePreferences()
        setContent {
            IncrementalScreen(
                state = uiState,
                logs = logs,
                onOutputUri = ::saveOutputUri,
                onFormat = ::saveFormat,
                onKeepOgg = ::saveKeepOgg,
                onCover = ::saveCover,
                onStart = ::start
            )
        }
        appendLog("增量版已启动。旧版数据不会被主动删除。")
        refreshStatus()
    }

    override fun onDestroy() {
        stopPythonLogPolling()
        worker.shutdownNow()
        logWatcher.shutdownNow()
        super.onDestroy()
    }

    private fun restorePreferences() {
        val prefs = getSharedPreferences(V3_PREFS, MODE_PRIVATE)
        val format = runCatching {
            V3OutputFormat.valueOf(prefs.getString(V3_KEY_FORMAT, V3OutputFormat.FLAC.name)!!)
        }.getOrDefault(V3OutputFormat.FLAC)
        uiState = uiState.copy(
            outputUri = prefs.getString(V3_KEY_TREE_URI, null)?.let(Uri::parse),
            format = format,
            keepOgg = prefs.getBoolean(V3_KEY_KEEP_OGG, false),
            cover = prefs.getBoolean(V3_KEY_COVER, true)
        )
    }

    private fun saveOutputUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        getSharedPreferences(V3_PREFS, MODE_PRIVATE).edit()
            .putString(V3_KEY_TREE_URI, uri.toString()).apply()
        uiState = uiState.copy(outputUri = uri)
        appendLog("输出目录已设置：$uri")
    }

    private fun saveFormat(format: V3OutputFormat) {
        getSharedPreferences(V3_PREFS, MODE_PRIVATE).edit()
            .putString(V3_KEY_FORMAT, format.name).apply()
        uiState = uiState.copy(format = format)
    }

    private fun saveKeepOgg(value: Boolean) {
        getSharedPreferences(V3_PREFS, MODE_PRIVATE).edit().putBoolean(V3_KEY_KEEP_OGG, value).apply()
        uiState = uiState.copy(keepOgg = value)
    }

    private fun saveCover(value: Boolean) {
        getSharedPreferences(V3_PREFS, MODE_PRIVATE).edit().putBoolean(V3_KEY_COVER, value).apply()
        uiState = uiState.copy(cover = value)
    }

    private fun refreshStatus() {
        worker.execute {
            val root = checkRoot()
            try {
                val pi = packageManager.getPackageInfo(V3_PHIGROS_PACKAGE, 0)
                val ai = packageManager.getApplicationInfo(V3_PHIGROS_PACKAGE, 0)
                val version = pi.versionName.orEmpty()
                val cache = discoverAllRawTracks(checkpointRawDir()).size
                val cacheVersion = readCheckpointVersion()
                val externalLegacy = if (cache == 0 && root) countExternalLegacyOgg() else 0
                val shown = if (cache > 0) cache else externalLegacy
                onUi {
                    uiState = uiState.copy(
                        rootOk = root,
                        installed = true,
                        version = version,
                        splitCount = ai.splitSourceDirs?.size ?: 0,
                        cacheCount = shown,
                        cacheVersion = cacheVersion,
                        status = when {
                            cache > 0 && cacheVersion == version -> "可直接从 OGG 继续"
                            cache > 0 -> "检测到游戏版本变化：将做增量扫描"
                            externalLegacy > 0 -> "发现旧版 OGG：可直接接管"
                            root -> "已就绪"
                            else -> "需要 Root 授权"
                        }
                    )
                }
                appendLog(
                    "环境：Root=${yesNo(root)}，Phigros=${version.ifBlank { "已安装" }}，" +
                        "本地OGG=$cache，旧版可接管=$externalLegacy，缓存版本=${cacheVersion.ifBlank { "无" }}"
                )
            } catch (_: PackageManager.NameNotFoundException) {
                onUi {
                    uiState = uiState.copy(
                        rootOk = root,
                        installed = false,
                        cacheCount = 0,
                        status = "未检测到 Phigros"
                    )
                }
            }
        }
    }

    private fun start() {
        val snapshot = uiState
        val treeUri = snapshot.outputUri
        if (treeUri == null) {
            appendLog("请先选择输出目录。")
            return
        }
        if (!snapshot.installed) {
            appendLog("未检测到 Phigros。")
            return
        }

        clearLog()
        onUi { uiState = uiState.copy(running = true, progress = 0f, status = "正在准备…") }
        appendLog(
            "开始：目标=${snapshot.format.displayName}，封面=${yesNo(snapshot.cover)}，" +
                "额外写出OGG=${yesNo(snapshot.keepOgg)}。"
        )
        appendLog("策略：音频 bundle 指纹增量 + 输出/元数据增量。")

        worker.execute {
            val processRoot = File(cacheDir, "phigros_incremental_process_v6")
            MainActivity.deleteRecursively(processRoot)
            val apkDir = File(processRoot, "apks").apply { mkdirs() }
            val finalDir = File(processRoot, "final").apply { mkdirs() }
            val coverDir = File(processRoot, "covers").apply { mkdirs() }
            val metadataDir = File(processRoot, "metadata").apply { mkdirs() }
            val pyLog = File(processRoot, "incremental.log")
            val rawDir = checkpointRawDir().apply { mkdirs() }

            try {
                if (!checkRoot()) throw IllegalStateException("没有 Root 授权。")

                val version = snapshot.version
                val imported = importOldAppOggIfNeeded(rawDir, version)
                if (imported > 0) {
                    appendLog("[旧版接管] 已从旧 APK 数据目录接管 $imported 首 OGG；不会重新解这批音频。")
                    onUi { uiState = uiState.copy(cacheCount = imported, cacheVersion = version) }
                }

                var rawTracks = discoverCurrentTracks(rawDir)
                val storedVersion = readCheckpointVersion()
                val sameVersion = storedVersion == version && rawTracks.isNotEmpty()
                val manifestExists = incrementalManifestFile().isFile
                var needsBaselineAfter = sameVersion && !manifestExists
                var apks: List<String> = emptyList()

                if (sameVersion) {
                    phase("复用当前版本 OGG，直接进入整理…", 0.40f)
                    appendLog(
                        "[断点续跑] 当前版本一致，直接复用 ${rawTracks.size} 首 OGG；" +
                            "本轮先跳过 UnityFS / FSB5。"
                    )
                } else {
                    apks = prepareLocalApks(apkDir, 0.02f, 0.10f)
                    phase("扫描 Addressables 增量指纹…", 0.10f)
                    runPythonIncremental(
                        apks = apks,
                        rawDir = rawDir,
                        pyLog = pyLog,
                        gameVersion = version,
                        trustExistingWithoutManifest = false
                    )
                    rawTracks = discoverCurrentTracks(rawDir)
                    if (rawTracks.isEmpty()) throw IllegalStateException("增量扫描后没有当前版本可用音频。")
                    writeCheckpoint(version, rawTracks.size)
                    needsBaselineAfter = false
                    onUi { uiState = uiState.copy(cacheCount = rawTracks.size, cacheVersion = version) }
                    appendLog("增量音频缓存已更新：当前版本 ${rawTracks.size} 首。")
                }

                phase("加载曲目信息…", 0.46f)
                val catalog = try {
                    RemoteCatalog.load(this, metadataDir, ::appendLog)
                } catch (t: Throwable) {
                    appendLog("元数据同步失败：${readableMessage(t)}")
                    appendLog("继续处理音频；无法确认的字段不会伪造。")
                    emptyMap()
                }

                val outputRoot = DocumentFile.fromTreeUri(this, treeUri)
                    ?: throw IllegalStateException("无法访问输出目录。")
                if (!outputRoot.canWrite()) throw IllegalStateException("输出目录不可写。")

                val audioFingerprints = loadAudioFingerprints(rawTracks)
                val exportIndex = loadExportIndex()
                val destinations = allocateDestinations(rawTracks, catalog, finalDir, snapshot.format)
                val plans = rawTracks.map { raw ->
                    val meta = catalog[raw.songId] ?: TrackMeta(
                        songId = raw.songId,
                        title = raw.songId,
                        composer = "",
                        illustrator = ""
                    )
                    val audioFp = audioFingerprints[raw.songId]
                        ?: "raw:${raw.file.length()}:${raw.file.lastModified()}"
                    val metaFp = metadataFingerprint(meta, snapshot.cover)
                    val key = exportKey(treeUri, snapshot.format, raw.songId)
                    val old = exportIndex.optJSONObject(key)
                    val output = destinations.getValue(raw.songId)
                    val oldName = old?.optString("filename")?.takeIf { it.isNotBlank() }
                    val existingName = oldName ?: output.name
                    val existing = outputRoot.findFile(existingName)
                    val unchanged = old != null &&
                        old.optString("audio_fingerprint") == audioFp &&
                        old.optString("metadata_fingerprint") == metaFp &&
                        existing != null && existing.isFile && existing.length() > 0L
                    V3TrackPlan(raw, meta, output, audioFp, metaFp, oldName, unchanged)
                }

                val skipped = plans.count { it.skipExisting }
                val toProcess = plans.filterNot { it.skipExisting }
                appendLog(
                    "[输出增量] 当前 ${plans.size} 首：直接复用输出 $skipped，" +
                        "需要转码/重写标签 ${toProcess.size}。"
                )
                plans.filter { it.skipExisting }.forEach {
                    appendLog("[输出复用] ${it.meta.title}")
                }

                phase("增量转码与标签写入…", 0.52f)
                val results = processPlans(toProcess, coverDir, snapshot.format)
                val successful = results.filter { it.success }
                val failed = results.filterNot { it.success }

                successful.forEachIndexed { index, result ->
                    val plan = result.plan
                    if (plan.previousFilename != null && plan.previousFilename != plan.output.name) {
                        outputRoot.findFile(plan.previousFilename)?.delete()
                        appendLog("[去重] 删除旧文件名：${plan.previousFilename}")
                    }
                    writeOneToTree(plan.output, outputRoot)
                    val key = exportKey(treeUri, snapshot.format, plan.raw.songId)
                    exportIndex.put(
                        key,
                        JSONObject()
                            .put("song_id", plan.raw.songId)
                            .put("filename", plan.output.name)
                            .put("audio_fingerprint", plan.audioFingerprint)
                            .put("metadata_fingerprint", plan.metadataFingerprint)
                            .put("format", snapshot.format.name)
                            .put("tree_uri", treeUri.toString())
                            .put("updated_at", System.currentTimeMillis())
                    )
                    saveExportIndex(exportIndex)
                    val done = skipped + index + 1
                    phase("正在写入 $done/${plans.size}…", 0.70f + done.toFloat() / plans.size.coerceAtLeast(1) * 0.20f)
                }

                failed.forEach { appendLog("[失败] ${it.plan.meta.title}: ${it.error}") }

                if (snapshot.keepOgg) {
                    phase("增量写出 OGG…", 0.91f)
                    val oggStats = copyOggIncremental(rawTracks, outputRoot)
                    appendLog("OGG 写出：新增/更新 ${oggStats.first}，已存在跳过 ${oggStats.second}。")
                }

                // For the one-time migration from the older build we deliberately
                // start transcoding immediately. After useful output exists, build
                // the Addressables baseline without rebuilding audio so future game
                // versions can compare bundle CRC/size fingerprints incrementally.
                if (needsBaselineAfter || !incrementalManifestFile().isFile) {
                    phase("建立后续版本的增量基线…", 0.94f)
                    if (apks.isEmpty()) apks = prepareLocalApks(apkDir, 0.94f, 0.965f)
                    runPythonIncremental(
                        apks = apks,
                        rawDir = rawDir,
                        pyLog = pyLog,
                        gameVersion = version,
                        trustExistingWithoutManifest = true
                    )
                    rawTracks = discoverCurrentTracks(rawDir)
                    writeCheckpoint(version, rawTracks.size)
                    val newFp = loadAudioFingerprints(rawTracks)
                    plans.forEach { plan ->
                        val key = exportKey(treeUri, snapshot.format, plan.raw.songId)
                        val item = exportIndex.optJSONObject(key) ?: return@forEach
                        newFp[plan.raw.songId]?.let { item.put("audio_fingerprint", it) }
                    }
                    saveExportIndex(exportIndex)
                    appendLog("[增量基线] 已记录当前 Addressables bundle 指纹；以后版本更新只处理新增/变更曲目。")
                }

                val written = successful.size
                val totalReady = skipped + written
                appendLog(
                    "完成：当前 ${plans.size} 首；输出复用 $skipped；新处理 $written；失败 ${failed.size}。"
                )
                onUi {
                    uiState = uiState.copy(
                        running = false,
                        progress = 1f,
                        cacheCount = rawTracks.size,
                        cacheVersion = version,
                        status = if (failed.isEmpty())
                            "完成：$totalReady 首已就绪"
                        else "完成：$totalReady 首，失败 ${failed.size} 首"
                    )
                }
            } catch (t: Throwable) {
                stopPythonLogPolling()
                val msg = readableMessage(t)
                appendLog("任务失败：$msg")
                appendLog("已完成的 OGG/增量索引不会清除；修复后可继续。")
                onUi { uiState = uiState.copy(running = false, progress = 0f, status = "失败：$msg") }
            } finally {
                MainActivity.deleteRecursively(processRoot)
            }
        }
    }

    private fun processPlans(
        plans: List<V3TrackPlan>,
        coverDir: File,
        format: V3OutputFormat
    ): List<V3TrackResult> {
        if (plans.isEmpty()) return emptyList()
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
        appendLog("转码线程：$threadCount；Hi-Res 保护：启用。")
        val pool = Executors.newFixedThreadPool(threadCount)
        val completion = ExecutorCompletionService<V3TrackResult>(pool)
        try {
            plans.forEach { plan ->
                completion.submit {
                    try {
                        val info = AudioProcessor.probe(plan.raw.file)
                        appendLog(
                            "[源音质] ${plan.meta.title}: ${info.describe()}" +
                                if (info.isHiRes) " · Hi-Res" else ""
                        )
                        val cover = if (uiState.cover && plan.meta.remoteId != null) {
                            val dst = File(coverDir, "${MainActivity.safeFileName(plan.raw.songId)}.png")
                            runCatching {
                                RemoteCatalog.downloadCover(plan.meta.remoteId, dst)
                                dst
                            }.onFailure {
                                appendLog("[封面失败] ${plan.meta.title}: ${readableMessage(it)}")
                            }.getOrNull()
                        } else null

                        var embedded = false
                        try {
                            AudioProcessor.convert(
                                inputOgg = plan.raw.file,
                                outputFile = plan.output,
                                coverFile = cover,
                                format = format.oldFormat(),
                                meta = plan.meta
                            )
                            embedded = cover != null
                        } catch (first: Throwable) {
                            if (cover == null) throw first
                            appendLog(
                                "[封面兼容回退] ${plan.meta.title}: ${readableMessage(first)}；" +
                                    "改为不带封面重试，音频与文字标签继续保留。"
                            )
                            AudioProcessor.convert(
                                inputOgg = plan.raw.file,
                                outputFile = plan.output,
                                coverFile = null,
                                format = format.oldFormat(),
                                meta = plan.meta
                            )
                        }
                        V3TrackResult(plan, true, embedded, info)
                    } catch (t: Throwable) {
                        V3TrackResult(plan, false, false, null, readableMessage(t))
                    }
                }
            }
            val out = ArrayList<V3TrackResult>()
            repeat(plans.size) { out += completion.take().get() }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runPythonIncremental(
        apks: List<String>,
        rawDir: File,
        pyLog: File,
        gameVersion: String,
        trustExistingWithoutManifest: Boolean
    ) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        startPythonLogPolling(pyLog)
        try {
            Python.getInstance().getModule("extractor").callAttr(
                "extract_from_apks",
                apks.joinToString("\n"),
                rawDir.absolutePath,
                applicationInfo.nativeLibraryDir,
                pyLog.absolutePath,
                threads,
                gameVersion,
                trustExistingWithoutManifest
            )
        } finally {
            drainPythonLog(pyLog)
            stopPythonLogPolling()
        }
    }

    private fun prepareLocalApks(apkDir: File, start: Float, end: Float): List<String> {
        val paths = getPhigrosApkPaths()
        if (paths.isEmpty()) throw IllegalStateException("找不到 Phigros APK。")
        apkDir.mkdirs()
        val out = ArrayList<String>()
        paths.forEachIndexed { index, source ->
            val fraction = index.toFloat() / paths.size.coerceAtLeast(1)
            phase("读取 APK ${index + 1}/${paths.size}…", start + fraction * (end - start))
            val dst = File(apkDir, String.format(Locale.US, "%02d.apk", index))
            copyProtectedFile(source, dst)
            out += dst.absolutePath
        }
        return out
    }

    private fun checkpointRoot(): File = File(filesDir, V3_CHECKPOINT_DIR)
    private fun checkpointRawDir(): File = File(checkpointRoot(), "raw")
    private fun incrementalManifestFile(): File = File(checkpointRoot(), V3_INCREMENTAL_MANIFEST)
    private fun currentTracksFile(): File = File(checkpointRoot(), V3_CURRENT_TRACKS)

    private fun readCheckpointVersion(): String {
        val file = File(checkpointRoot(), V3_CHECKPOINT_META)
        if (!file.isFile) return ""
        return runCatching {
            Properties().apply { FileInputStream(file).use(::load) }
                .getProperty("phigros_version", "")
        }.getOrDefault("")
    }

    private fun writeCheckpoint(version: String, count: Int) {
        val root = checkpointRoot().apply { mkdirs() }
        val props = Properties().apply {
            setProperty("phigros_version", version)
            setProperty("ogg_count", count.toString())
            setProperty("updated_at", System.currentTimeMillis().toString())
        }
        FileOutputStream(File(root, V3_CHECKPOINT_META)).use {
            props.store(it, "Phigros incremental OGG checkpoint")
        }
    }

    private fun discoverAllRawTracks(rawDir: File): List<V3RawTrack> =
        rawDir.listFiles { f -> f.isFile && f.length() > 0L && f.extension.equals("ogg", true) }
            .orEmpty()
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { V3RawTrack(it.nameWithoutExtension, it) }

    private fun discoverCurrentTracks(rawDir: File): List<V3RawTrack> {
        val all = discoverAllRawTracks(rawDir)
        val index = currentTracksFile()
        if (!index.isFile) return all
        val allowed = runCatching {
            val arr = JSONArray(index.readText(Charsets.UTF_8))
            buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
        if (allowed.isEmpty()) return all
        return all.filter { it.songId in allowed }
    }

    private fun loadAudioFingerprints(tracks: List<V3RawTrack>): Map<String, String> {
        val file = incrementalManifestFile()
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val obj = root.optJSONObject("tracks") ?: JSONObject()
            buildMap {
                tracks.forEach { track ->
                    val fp = obj.optJSONObject(track.songId)?.optString("fingerprint").orEmpty()
                    if (fp.isNotBlank()) put(track.songId, fp)
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun importOldAppOggIfNeeded(rawDir: File, currentVersion: String): Int {
        val existing = discoverAllRawTracks(rawDir)
        if (existing.isNotEmpty()) return 0

        // Same-package legacy cache (useful in local/dev installs).
        val localLegacy = File(cacheDir, "phigros_extract_v4/raw")
        val localFiles = discoverAllRawTracks(localLegacy)
        if (localFiles.isNotEmpty()) {
            rawDir.mkdirs()
            localFiles.forEach { src -> src.file.copyTo(File(rawDir, src.file.name), overwrite = true) }
            val count = discoverAllRawTracks(rawDir).size
            writeCheckpoint(currentVersion, count)
            return count
        }

        // The CI recovery APK uses applicationIdSuffix .resume, so it can stay
        // installed beside the older build. Root read-only import prevents the
        // old app's private cache from being destroyed by an uninstall.
        if (packageName == V3_OLD_APP_PACKAGE) return 0
        val candidates = listOf(
            "/data/user/0/$V3_OLD_APP_PACKAGE/files/$V3_CHECKPOINT_DIR/raw",
            "/data/user/0/$V3_OLD_APP_PACKAGE/cache/phigros_extract_v4/raw",
            "/data/data/$V3_OLD_APP_PACKAGE/files/$V3_CHECKPOINT_DIR/raw",
            "/data/data/$V3_OLD_APP_PACKAGE/cache/phigros_extract_v4/raw"
        )
        rawDir.mkdirs()
        var copied = 0
        val seen = HashSet<String>()
        for (dir in candidates) {
            for (source in rootListOgg(dir)) {
                val name = source.substringAfterLast('/')
                if (!seen.add(name)) continue
                val dst = File(rawDir, name)
                if (rootCopyFile(source, dst) && dst.length() > 0L) copied++
            }
            if (copied > 0) break
        }
        if (copied > 0) writeCheckpoint(currentVersion, discoverAllRawTracks(rawDir).size)
        return copied
    }

    private fun countExternalLegacyOgg(): Int {
        if (packageName == V3_OLD_APP_PACKAGE) return 0
        val candidates = listOf(
            "/data/user/0/$V3_OLD_APP_PACKAGE/files/$V3_CHECKPOINT_DIR/raw",
            "/data/user/0/$V3_OLD_APP_PACKAGE/cache/phigros_extract_v4/raw"
        )
        for (dir in candidates) {
            val count = rootListOgg(dir).size
            if (count > 0) return count
        }
        return 0
    }

    private fun rootListOgg(dir: String): List<String> = try {
        val cmd = "find ${MainActivity.shellQuote(dir)} -maxdepth 1 -type f -name '*.ogg' -print 2>/dev/null"
        val p = ProcessBuilder("su", "-c", cmd).start()
        val text = p.inputStream.use { String(readFully(it), StandardCharsets.UTF_8) }
        p.waitFor()
        text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
    } catch (_: Throwable) {
        emptyList()
    }

    private fun rootCopyFile(source: String, destination: File): Boolean = try {
        val p = ProcessBuilder("su", "-c", "cat ${MainActivity.shellQuote(source)}").start()
        BufferedInputStream(p.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                MainActivity.copyStream(input, output)
            }
        }
        p.waitFor() == 0 && destination.length() > 0L
    } catch (_: Throwable) {
        destination.delete()
        false
    }

    private fun getPhigrosApkPaths(): List<String> {
        val ai = packageManager.getApplicationInfo(V3_PHIGROS_PACKAGE, 0)
        return buildList {
            ai.sourceDir?.let(::add)
            ai.splitSourceDirs?.let(::addAll)
        }
    }

    private fun checkRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id -u").start()
        val text = p.inputStream.use { String(readFully(it), StandardCharsets.UTF_8).trim() }
        p.waitFor() == 0 && text == "0"
    } catch (_: Throwable) {
        false
    }

    private fun copyProtectedFile(source: String, destination: File) {
        runCatching {
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    MainActivity.copyStream(input, output)
                }
            }
        }
        if (destination.length() > 0L) return
        if (!rootCopyFile(source, destination)) {
            throw IllegalStateException("Root 读取 APK 失败：$source")
        }
    }

    private fun allocateDestinations(
        tracks: List<V3RawTrack>,
        catalog: Map<String, TrackMeta>,
        outDir: File,
        format: V3OutputFormat
    ): Map<String, File> {
        val used = HashSet<String>()
        return buildMap {
            tracks.forEach { raw ->
                val meta = catalog[raw.songId]
                val title = meta?.title?.takeIf(String::isNotBlank) ?: raw.songId
                val composer = meta?.composer?.takeIf(String::isNotBlank)
                var base = MainActivity.safeFileName(if (composer != null) "$title - $composer" else title)
                var lower = base.lowercase(Locale.ROOT)
                if (!used.add(lower)) {
                    base += " [${MainActivity.safeFileName(raw.songId)}]"
                    lower = base.lowercase(Locale.ROOT)
                    var n = 2
                    while (!used.add(lower)) {
                        base += "_$n"
                        lower = base.lowercase(Locale.ROOT)
                        n++
                    }
                }
                put(raw.songId, File(outDir, "$base.${format.extension}"))
            }
        }
    }

    private fun metadataFingerprint(meta: TrackMeta, cover: Boolean): String {
        val text = buildString {
            append(meta.songId).append('\n')
            append(meta.title).append('\n')
            append(meta.composer).append('\n')
            append(meta.illustrator).append('\n')
            append(meta.songKey).append('\n')
            append(meta.songTitle).append('\n')
            append(meta.chapter).append('\n')
            append(meta.charters.joinToString("\u001f")).append('\n')
            append(meta.difficulties.joinToString("\u001f")).append('\n')
            append(meta.levelNames.joinToString("\u001f")).append('\n')
            append(meta.hasLegacy).append('\n')
            append(meta.remoteId ?: -1).append('\n')
            append("cover=").append(cover)
        }
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun exportKey(uri: Uri, format: V3OutputFormat, songId: String): String =
        sha256("${uri}|${format.name}|$songId".toByteArray(Charsets.UTF_8))

    private fun loadExportIndex(): JSONObject {
        val file = File(filesDir, V3_EXPORT_INDEX)
        if (!file.isFile) return JSONObject()
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())
    }

    private fun saveExportIndex(index: JSONObject) {
        val file = File(filesDir, V3_EXPORT_INDEX)
        val temp = File(filesDir, "$V3_EXPORT_INDEX.part")
        temp.writeText(index.toString(2), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun writeOneToTree(source: File, root: DocumentFile) {
        root.findFile(source.name)?.delete()
        val mime = if (source.extension.equals("flac", true)) "audio/flac" else "audio/mpeg"
        val dst = root.createFile(mime, source.name)
            ?: throw IllegalStateException("无法创建输出：${source.name}")
        val os = contentResolver.openOutputStream(dst.uri, "wt")
            ?: throw IllegalStateException("无法写入：${source.name}")
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(os).use { output -> MainActivity.copyStream(input, output) }
        }
        appendLog("[写入] ${source.name}")
    }

    private fun copyOggIncremental(tracks: List<V3RawTrack>, root: DocumentFile): Pair<Int, Int> {
        var written = 0
        var skipped = 0
        tracks.forEach { track ->
            val existing = root.findFile(track.file.name)
            if (existing != null && existing.isFile && existing.length() == track.file.length()) {
                skipped++
                return@forEach
            }
            existing?.delete()
            val dst = root.createFile("audio/ogg", track.file.name)
                ?: throw IllegalStateException("无法创建 OGG：${track.file.name}")
            val os = contentResolver.openOutputStream(dst.uri, "wt")
                ?: throw IllegalStateException("无法写 OGG：${track.file.name}")
            BufferedInputStream(FileInputStream(track.file)).use { input ->
                BufferedOutputStream(os).use { output -> MainActivity.copyStream(input, output) }
            }
            written++
        }
        return written to skipped
    }

    private fun phase(text: String, value: Float) = onUi {
        uiState = uiState.copy(status = text, progress = value.coerceIn(0f, 1f))
    }

    private fun startPythonLogPolling(file: File) {
        stopPythonLogPolling()
        lastPythonLogText = ""
        logFuture = logWatcher.scheduleAtFixedRate(
            { runCatching { drainPythonLog(file) } }, 100, 180, TimeUnit.MILLISECONDS
        )
    }

    private fun stopPythonLogPolling() {
        logFuture?.cancel(false)
        logFuture = null
    }

    @Synchronized
    private fun drainPythonLog(file: File) {
        if (!file.isFile) return
        val current = file.readText(Charsets.UTF_8)
        if (current.length < lastPythonLogText.length || !current.startsWith(lastPythonLogText)) {
            lastPythonLogText = ""
        }
        if (current.length == lastPythonLogText.length) return
        val delta = current.substring(lastPythonLogText.length)
        lastPythonLogText = current
        delta.lineSequence().filter(String::isNotBlank).forEach { line ->
            appendLogRaw(line)
            val m = V3_PROGRESS.matcher(line)
            if (m.find()) {
                val done = m.group(1)?.toIntOrNull() ?: return@forEach
                val total = m.group(2)?.toIntOrNull() ?: return@forEach
                if (total > 0) phase("增量解包 $done/$total…", 0.12f + done.toFloat() / total * 0.30f)
            }
        }
    }

    private fun appendLog(text: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendLogRaw("$ts $text")
    }

    private fun appendLogRaw(text: String) = onUi {
        logs.add(text)
        while (logs.size > V3_MAX_LOG_LINES) logs.removeAt(0)
    }

    private fun clearLog() = onUi { logs.clear() }
    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread(block)
    }

    private fun readableMessage(t: Throwable): String {
        var cur: Throwable? = t
        var last = t.javaClass.simpleName
        while (cur != null) {
            if (!cur.message.isNullOrBlank()) last = cur.message!!
            cur = cur.cause
        }
        return last
    }

    private fun readFully(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun yesNo(v: Boolean) = if (v) "是" else "否"

    companion object {
        private val V3_PROGRESS = Pattern.compile("\\[进度\\]\\s+(\\d+)/(\\d+)")
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IncrementalScreen(
    state: V3UiState,
    logs: List<String>,
    onOutputUri: (Uri) -> Unit,
    onFormat: (V3OutputFormat) -> Unit,
    onKeepOgg: (Boolean) -> Unit,
    onCover: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onOutputUri(uri)
    }

    MaterialExpressiveTheme(colorScheme = colors) {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Phigros Music Extractor", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Root 本地 · 断点续跑 · Bundle 指纹增量 · Hi-Res 保护 · Material 3 Expressive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("增量状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Root：${when (state.rootOk) { true -> "可用"; false -> "不可用"; null -> "检测中" }}")
                        Text(if (state.installed) "Phigros：${state.version} · base + ${state.splitCount} split" else "Phigros：未检测到")
                        Text("OGG 缓存：${state.cacheCount} 首${if (state.cacheVersion.isNotBlank()) " · 基线 ${state.cacheVersion}" else ""}")
                        if (state.cacheCount > 0 && state.cacheVersion.isNotBlank() && state.cacheVersion != state.version) {
                            Text("游戏版本已变化：只扫描资源指纹，旧曲未变则直接复用。", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("输出设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(state.outputUri?.toString() ?: "尚未选择输出目录", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { picker.launch(state.outputUri) }, enabled = !state.running) {
                            Text("选择输出目录")
                        }
                        Text("目标格式", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            V3OutputFormat.entries.forEachIndexed { index, format ->
                                SegmentedButton(
                                    selected = state.format == format,
                                    onClick = { onFormat(format) },
                                    enabled = !state.running,
                                    shape = SegmentedButtonDefaults.itemShape(index, V3OutputFormat.entries.size),
                                    label = { Text(format.displayName) }
                                )
                            }
                        }
                    }
                }

                IncrementalSettingCard(
                    "写入专辑封面",
                    "封面异常会自动降级为无封面重试，不再让整首音频转码失败。",
                    state.cover,
                    !state.running,
                    onCover
                )
                IncrementalSettingCard(
                    "同时写出原始 OGG",
                    "目标目录中相同大小的 OGG 会直接跳过，不重复复制。",
                    state.keepOgg,
                    !state.running,
                    onKeepOgg
                )

                FilledTonalButton(
                    onClick = onStart,
                    enabled = !state.running && state.outputUri != null && state.installed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.running) "处理中…" else if (state.cacheCount > 0) "增量继续整理" else "开始提取并整理")
                }
                LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text(state.status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IncrementalLogCard(logs)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun IncrementalSettingCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
private fun IncrementalLogCard(logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("运行日志 / 增量与音质验收", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    items(logs) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
