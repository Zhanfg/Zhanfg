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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
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

private const val PHIGROS_PACKAGE = "com.PigeonGames.Phigros"
private const val PREFS = "prefs"
private const val KEY_TREE_URI = "tree_uri"
private const val KEY_FORMAT = "output_format"
private const val KEY_KEEP_OGG = "keep_ogg"
private const val KEY_COVER = "cover"
private const val MAX_LOG_LINES = 700
private const val CHECKPOINT_DIR = "phigros_ogg_checkpoint_v1"
private const val CHECKPOINT_META = "checkpoint.properties"
private const val LEGACY_WORK_DIR = "phigros_extract_v4"

enum class OutputFormat(val displayName: String, val extension: String, val mime: String) {
    FLAC("FLAC", "flac", "audio/flac"),
    MP3("MP3", "mp3", "audio/mpeg")
}

data class AppUiState(
    val rootOk: Boolean? = null,
    val phigrosInstalled: Boolean = false,
    val phigrosVersion: String = "",
    val splitCount: Int = 0,
    val cachedOggCount: Int = 0,
    val outputUri: Uri? = null,
    val outputFormat: OutputFormat = OutputFormat.FLAC,
    val keepOgg: Boolean = false,
    val fetchCover: Boolean = true,
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "正在检查运行环境…"
)

data class RawTrack(val songId: String, val rawFile: File)

data class ProcessedTrack(
    val raw: RawTrack,
    val outputFile: File,
    val coverEmbedded: Boolean,
    val sourceInfo: SourceAudioInfo
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val logWatcher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val logs = mutableStateListOf<String>()
    private var uiState by mutableStateOf(AppUiState())
    private var logFuture: ScheduledFuture<*>? = null
    private var lastPythonLogText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        restorePreferences()
        setContent {
            AppScreen(
                state = uiState,
                logs = logs,
                onOutputUri = ::saveOutputUri,
                onFormat = ::saveFormat,
                onKeepOgg = ::saveKeepOgg,
                onFetchCover = ::saveCoverPreference,
                onStart = ::startExtraction
            )
        }
        appendLog("应用已启动。")
        refreshStatus()
    }

    override fun onDestroy() {
        stopPythonLogPolling()
        worker.shutdownNow()
        logWatcher.shutdownNow()
        super.onDestroy()
    }

    private fun restorePreferences() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val format = runCatching {
            OutputFormat.valueOf(prefs.getString(KEY_FORMAT, OutputFormat.FLAC.name)!!)
        }.getOrDefault(OutputFormat.FLAC)
        uiState = uiState.copy(
            outputUri = prefs.getString(KEY_TREE_URI, null)?.let(Uri::parse),
            outputFormat = format,
            keepOgg = prefs.getBoolean(KEY_KEEP_OGG, false),
            fetchCover = prefs.getBoolean(KEY_COVER, true)
        )
    }

    private fun saveOutputUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_TREE_URI, uri.toString())
            .apply()
        uiState = uiState.copy(outputUri = uri)
        appendLog("输出目录已设置：$uri")
    }

    private fun saveFormat(format: OutputFormat) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FORMAT, format.name).apply()
        uiState = uiState.copy(outputFormat = format)
    }

    private fun saveKeepOgg(value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_KEEP_OGG, value).apply()
        uiState = uiState.copy(keepOgg = value)
    }

    private fun saveCoverPreference(value: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_COVER, value).apply()
        uiState = uiState.copy(fetchCover = value)
    }

    private fun refreshStatus() {
        worker.execute {
            val rootOk = checkRoot()
            try {
                val pi = packageManager.getPackageInfo(PHIGROS_PACKAGE, 0)
                val ai = packageManager.getApplicationInfo(PHIGROS_PACKAGE, 0)
                val version = pi.versionName.orEmpty()
                val splits = ai.splitSourceDirs?.size ?: 0
                val cached = inspectReusableOggCount(version)
                onUi {
                    uiState = uiState.copy(
                        rootOk = rootOk,
                        phigrosInstalled = true,
                        phigrosVersion = version,
                        splitCount = splits,
                        cachedOggCount = cached,
                        status = when {
                            cached > 0 -> "可从 OGG 断点继续"
                            rootOk -> "已就绪"
                            else -> "需要 Root 授权"
                        }
                    )
                }
                appendLog(
                    "环境检查：Root=${if (rootOk) "可用" else "不可用"}，" +
                        "Phigros=${pi.versionName ?: "已安装"}，split=$splits，OGG断点=$cached 首"
                )
            } catch (_: PackageManager.NameNotFoundException) {
                onUi {
                    uiState = uiState.copy(
                        rootOk = rootOk,
                        phigrosInstalled = false,
                        cachedOggCount = 0,
                        status = "未检测到 Phigros"
                    )
                }
                appendLog("环境检查：未检测到 Phigros。")
            }
        }
    }

    private fun startExtraction() {
        val snapshot = uiState
        val outputUri = snapshot.outputUri
        if (outputUri == null) {
            appendLog("任务未开始：请先选择输出目录。")
            onUi { uiState = uiState.copy(status = "请先选择输出目录") }
            return
        }
        if (!snapshot.phigrosInstalled) {
            appendLog("任务未开始：没有检测到 Phigros。")
            return
        }

        clearLog()
        onUi { uiState = uiState.copy(running = true, progress = 0f, status = "正在准备…") }

        val format = snapshot.outputFormat
        val keepOgg = snapshot.keepOgg
        val fetchCover = snapshot.fetchCover
        val pythonWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        appendLog("开始任务：目标=${format.displayName}，保留 OGG=${yesNo(keepOgg)}，封面=${yesNo(fetchCover)}。")
        appendLog("元数据写入始终启用，与封面开关无关。")

        worker.execute {
            val processRoot = File(cacheDir, "phigros_process_v5")
            deleteRecursively(processRoot)
            val apkDir = File(processRoot, "apks").apply { mkdirs() }
            val finalDir = File(processRoot, "final").apply { mkdirs() }
            val coverDir = File(processRoot, "covers").apply { mkdirs() }
            val pythonLog = File(processRoot, "extract-progress.log")
            val checkpointRoot = File(filesDir, CHECKPOINT_DIR).apply { mkdirs() }
            val rawDir = File(checkpointRoot, "raw").apply { mkdirs() }

            try {
                val version = snapshot.phigrosVersion
                var rawTracks = prepareReusableOggCheckpoint(version)

                if (rawTracks.isNotEmpty()) {
                    phase("检测到 OGG 断点，直接继续整理…", 0.55f)
                    appendLog(
                        "[断点续跑] 复用 ${rawTracks.size} 首 OGG；" +
                            "跳过 APK → Addressables → UnityFS → FSB5。"
                    )
                } else {
                    if (!checkRoot()) {
                        throw IllegalStateException("未获得 Root。请在 Root 管理器中允许本应用的 su 请求。")
                    }

                    phase("正在定位 Phigros 安装包…", 0.02f)
                    val sourceApks = getPhigrosApkPaths()
                    if (sourceApks.isEmpty()) throw IllegalStateException("没有找到 Phigros APK。")
                    appendLog("定位到 ${sourceApks.size} 个 APK 文件。")

                    val localApks = ArrayList<String>()
                    sourceApks.forEachIndexed { index, source ->
                        phase(
                            "正在读取 APK ${index + 1}/${sourceApks.size}…",
                            0.03f + index.toFloat() / sourceApks.size * 0.07f
                        )
                        val dst = File(apkDir, String.format(Locale.US, "%02d.apk", index))
                        copyProtectedFile(source, dst)
                        localApks += dst.absolutePath
                        appendLog("APK 已缓存：${dst.name}，${formatBytes(dst.length())}")
                    }

                    phase("正在并发解析 Addressables / UnityFS / FSB5…", 0.10f)
                    startPythonLogPolling(pythonLog)
                    try {
                        Python.getInstance().getModule("extractor").callAttr(
                            "extract_from_apks",
                            localApks.joinToString("\n"),
                            rawDir.absolutePath,
                            applicationInfo.nativeLibraryDir,
                            pythonLog.absolutePath,
                            pythonWorkers
                        )
                    } finally {
                        drainPythonLog(pythonLog)
                        stopPythonLogPolling()
                    }

                    rawTracks = discoverRawTracks(rawDir)
                    if (rawTracks.isEmpty()) {
                        throw IllegalStateException("解析完成，但没有得到可处理的音乐。")
                    }
                    writeCheckpoint(version, rawTracks.size)
                    onUi { uiState = uiState.copy(cachedOggCount = rawTracks.size) }
                    appendLog("本地音频提取完成：${rawTracks.size} 首；OGG 断点已持久保存。")
                }

                phase("正在加载 Phigros 曲目信息…", 0.55f)
                val catalog = try {
                    RemoteCatalog.load(this, File(processRoot, "metadata-cache"), ::appendLog)
                } catch (t: Throwable) {
                    appendLog("曲目信息同步失败：${readableMessage(t)}")
                    appendLog("将继续处理音频；无法确认的文字字段不会伪造。")
                    emptyMap()
                }

                val destinations = allocateDestinations(rawTracks, catalog, finalDir, format)
                val transcodeWorkers = Runtime.getRuntime().availableProcessors().coerceIn(1, 2)
                appendLog("转码线程：$transcodeWorkers。Hi-Res 保护：启用。")

                val pool = Executors.newFixedThreadPool(transcodeWorkers)
                val completion = ExecutorCompletionService<ProcessedTrack>(pool)
                try {
                    rawTracks.forEach { raw ->
                        completion.submit {
                            val meta = catalog[raw.songId] ?: TrackMeta(
                                songId = raw.songId,
                                title = raw.songId,
                                composer = "",
                                illustrator = ""
                            )
                            val sourceInfo = AudioProcessor.probe(raw.rawFile)
                            appendLog(
                                "[源音质] ${meta.title}: ${sourceInfo.describe()}" +
                                    if (sourceInfo.isHiRes) " · Hi-Res" else ""
                            )

                            val cover = if (fetchCover && meta.remoteId != null) {
                                val dst = File(coverDir, "${safeFileName(raw.songId)}.png")
                                runCatching {
                                    RemoteCatalog.downloadCover(meta.remoteId, dst)
                                    dst
                                }.onFailure {
                                    appendLog("[封面失败] ${meta.title}: ${readableMessage(it)}")
                                }.getOrNull()
                            } else null

                            appendLog(
                                "[转码] ${meta.title} → ${format.displayName}" +
                                    if (cover != null) " + 封面" else ""
                            )
                            AudioProcessor.convert(
                                inputOgg = raw.rawFile,
                                outputFile = destinations.getValue(raw.songId),
                                coverFile = cover,
                                format = format,
                                meta = meta
                            )
                            ProcessedTrack(
                                raw,
                                destinations.getValue(raw.songId),
                                cover != null,
                                sourceInfo
                            )
                        }
                    }

                    var done = 0
                    var covers = 0
                    var hiRes = 0
                    repeat(rawTracks.size) {
                        val processed = completion.take().get()
                        done++
                        if (processed.coverEmbedded) covers++
                        if (processed.sourceInfo.isHiRes) hiRes++
                        appendLog(
                            "[完成] ${processed.outputFile.name} · " +
                                formatBytes(processed.outputFile.length())
                        )
                        phase(
                            "正在转码 $done/${rawTracks.size}…",
                            0.60f + done.toFloat() / rawTracks.size * 0.33f
                        )
                    }
                    appendLog(
                        "转码完成：${rawTracks.size} 首；Hi-Res 源 $hiRes 首；嵌入封面 $covers 首。"
                    )
                } finally {
                    pool.shutdownNow()
                }

                phase("正在写入你选择的目录…", 0.94f)
                val finalFiles = finalDir.listFiles { f ->
                    f.isFile && f.extension.equals(format.extension, true)
                }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
                val finalCopied = copyFilesToTree(
                    finalFiles,
                    outputUri,
                    0.94f,
                    if (keepOgg) 0.985f else 1f
                )

                var oggCopied = 0
                if (keepOgg) {
                    val originals = rawTracks.map { it.rawFile }
                    appendLog("正在额外保留 ${originals.size} 个原始 OGG。")
                    oggCopied = copyFilesToTree(originals, outputUri, 0.985f, 1f)
                }

                appendLog(
                    "全部完成：${format.displayName} $finalCopied 首" +
                        if (keepOgg) "，OGG $oggCopied 首。" else "。"
                )
                onUi {
                    uiState = uiState.copy(
                        running = false,
                        progress = 1f,
                        cachedOggCount = rawTracks.size,
                        status = "完成：已写入 $finalCopied 首 ${format.displayName}"
                    )
                }
            } catch (t: Throwable) {
                stopPythonLogPolling()
                val message = readableMessage(t)
                appendLog("任务失败：$message")
                appendLog("OGG 断点会保留；修复后再次点击开始即可从 OGG 继续。")
                onUi {
                    uiState = uiState.copy(
                        running = false,
                        progress = 0f,
                        status = "失败：$message"
                    )
                }
            } finally {
                deleteRecursively(processRoot)
            }
        }
    }

    private fun prepareReusableOggCheckpoint(currentVersion: String): List<RawTrack> {
        val checkpointRoot = File(filesDir, CHECKPOINT_DIR)
        val rawDir = File(checkpointRoot, "raw")
        val metaFile = File(checkpointRoot, CHECKPOINT_META)

        val existing = discoverRawTracks(rawDir)
        if (existing.isNotEmpty() && metaFile.isFile) {
            val props = readProperties(metaFile)
            val storedVersion = props.getProperty("phigros_version", "")
            val storedCount = props.getProperty("ogg_count", "0").toIntOrNull() ?: 0
            if (storedVersion == currentVersion && storedCount == existing.size) {
                return existing
            }
            appendLog(
                "OGG 断点失效：版本/数量不匹配（缓存 $storedVersion/$storedCount，" +
                    "当前 $currentVersion/${existing.size}），将重新提取。"
            )
            deleteRecursively(checkpointRoot)
        } else if (existing.isNotEmpty()) {
            appendLog("OGG 断点缺少版本信息，为避免使用陈旧资源，将重新提取。")
            deleteRecursively(checkpointRoot)
        }

        // Upgrade path for the user's already-completed OGG extraction from
        // versions before persistent checkpoints existed. Only accept it when
        // the old extraction log proves the extractor reached its final summary.
        val legacyRoot = File(cacheDir, LEGACY_WORK_DIR)
        val legacyRaw = File(legacyRoot, "raw")
        val legacyLog = File(legacyRoot, "extract-progress.log")
        val legacyTracks = discoverRawTracks(legacyRaw)
        if (legacyTracks.isNotEmpty() && legacyExtractionFinished(legacyLog)) {
            rawDir.mkdirs()
            legacyTracks.forEach { track ->
                val dst = File(rawDir, track.rawFile.name)
                BufferedInputStream(FileInputStream(track.rawFile)).use { input ->
                    BufferedOutputStream(FileOutputStream(dst)).use { output ->
                        copyStream(input, output)
                    }
                }
            }
            val migrated = discoverRawTracks(rawDir)
            if (migrated.isNotEmpty()) {
                writeCheckpoint(currentVersion, migrated.size)
                appendLog(
                    "[断点迁移] 找到上一版已经提取完成的 ${migrated.size} 首 OGG，" +
                        "已迁移到持久断点；本轮不会重新解包。"
                )
                return migrated
            }
        }

        rawDir.mkdirs()
        return emptyList()
    }

    private fun inspectReusableOggCount(currentVersion: String): Int {
        val checkpointRoot = File(filesDir, CHECKPOINT_DIR)
        val rawDir = File(checkpointRoot, "raw")
        val metaFile = File(checkpointRoot, CHECKPOINT_META)
        val tracks = discoverRawTracks(rawDir)
        if (tracks.isNotEmpty() && metaFile.isFile) {
            val props = readProperties(metaFile)
            val version = props.getProperty("phigros_version", "")
            val count = props.getProperty("ogg_count", "0").toIntOrNull() ?: 0
            if (version == currentVersion && count == tracks.size) return tracks.size
        }

        val legacyRoot = File(cacheDir, LEGACY_WORK_DIR)
        val legacyTracks = discoverRawTracks(File(legacyRoot, "raw"))
        return if (legacyTracks.isNotEmpty() && legacyExtractionFinished(File(legacyRoot, "extract-progress.log"))) {
            legacyTracks.size
        } else 0
    }

    private fun legacyExtractionFinished(logFile: File): Boolean {
        if (!logFile.isFile) return false
        val text = runCatching { logFile.readText(Charsets.UTF_8) }.getOrDefault("")
        if (text.contains("[汇总] 解析结束：")) return true
        var finished = false
        LEGACY_PROGRESS_PATTERN.matcher(text).let { matcher ->
            while (matcher.find()) {
                val done = matcher.group(1)?.toIntOrNull() ?: continue
                val total = matcher.group(2)?.toIntOrNull() ?: continue
                if (total > 0 && done == total) finished = true
            }
        }
        return finished
    }

    private fun writeCheckpoint(version: String, count: Int) {
        val root = File(filesDir, CHECKPOINT_DIR).apply { mkdirs() }
        val props = Properties().apply {
            setProperty("phigros_version", version)
            setProperty("ogg_count", count.toString())
            setProperty("completed", "true")
            setProperty("updated_at", System.currentTimeMillis().toString())
        }
        FileOutputStream(File(root, CHECKPOINT_META)).use { props.store(it, "Phigros OGG checkpoint") }
    }

    private fun readProperties(file: File): Properties = Properties().apply {
        FileInputStream(file).use(::load)
    }

    private fun allocateDestinations(
        tracks: List<RawTrack>,
        catalog: Map<String, TrackMeta>,
        outDir: File,
        format: OutputFormat
    ): Map<String, File> {
        val used = HashSet<String>()
        val result = LinkedHashMap<String, File>()
        tracks.forEach { raw ->
            val meta = catalog[raw.songId]
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: raw.songId
            val composer = meta?.composer?.takeIf { it.isNotBlank() }
            var base = safeFileName(if (composer != null) "$title - $composer" else title)
            var candidate = base.lowercase(Locale.ROOT)
            if (!used.add(candidate)) {
                base = "${base} [${safeFileName(raw.songId)}]"
                candidate = base.lowercase(Locale.ROOT)
                var n = 2
                while (!used.add(candidate)) {
                    base = "${base}_$n"
                    candidate = base.lowercase(Locale.ROOT)
                    n++
                }
            }
            result[raw.songId] = File(outDir, "$base.${format.extension}")
        }
        return result
    }

    private fun discoverRawTracks(rawDir: File): List<RawTrack> =
        rawDir.listFiles { file ->
            file.isFile && file.length() > 0L && file.extension.equals("ogg", true)
        }.orEmpty()
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { RawTrack(it.nameWithoutExtension, it) }

    private fun getPhigrosApkPaths(): List<String> {
        val ai = packageManager.getApplicationInfo(PHIGROS_PACKAGE, 0)
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
        try {
            BufferedInputStream(FileInputStream(source)).use { input ->
                BufferedOutputStream(FileOutputStream(destination)).use { output ->
                    copyStream(input, output)
                }
            }
            if (destination.length() > 0L) return
        } catch (_: Throwable) {
        }

        val p = ProcessBuilder("su", "-c", "cat ${shellQuote(source)}").start()
        BufferedInputStream(p.inputStream).use { input ->
            BufferedOutputStream(FileOutputStream(destination)).use { output -> copyStream(input, output) }
        }
        val code = p.waitFor()
        if (code != 0 || destination.length() == 0L) {
            val err = p.errorStream.use { String(readFully(it), StandardCharsets.UTF_8).trim() }
            destination.delete()
            throw IllegalStateException(
                "Root 读取 APK 失败：$source${if (err.isBlank()) "" else "\n$err"}"
            )
        }
    }

    private fun copyFilesToTree(files: List<File>, treeUri: Uri, start: Float, end: Float): Int {
        val root = DocumentFile.fromTreeUri(this, treeUri)
            ?: throw IllegalStateException("无法访问所选输出目录。")
        if (!root.canWrite()) throw IllegalStateException("所选目录当前不可写，请重新选择。")

        files.forEachIndexed { index, src ->
            root.findFile(src.name)?.delete()
            val dst = root.createFile(mimeFor(src.name), src.name)
                ?: throw IllegalStateException("无法创建文件：${src.name}")
            val os = contentResolver.openOutputStream(dst.uri, "wt")
                ?: throw IllegalStateException("无法打开输出流：${src.name}")
            BufferedInputStream(FileInputStream(src)).use { input ->
                BufferedOutputStream(os).use { output -> copyStream(input, output) }
            }
            val fraction = (index + 1).toFloat() / files.size.coerceAtLeast(1)
            phase("正在写入 ${index + 1}/${files.size}…", start + fraction * (end - start))
            appendLog("[写入] ${src.name}")
        }
        return files.size
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".flac", true) -> "audio/flac"
        name.endsWith(".ogg", true) -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun phase(text: String, progress: Float) = onUi {
        uiState = uiState.copy(status = text, progress = progress.coerceIn(0f, 1f))
    }

    private fun startPythonLogPolling(logFile: File) {
        stopPythonLogPolling()
        lastPythonLogText = ""
        logFuture = logWatcher.scheduleAtFixedRate(
            { runCatching { drainPythonLog(logFile) } },
            100,
            180,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopPythonLogPolling() {
        logFuture?.cancel(false)
        logFuture = null
    }

    @Synchronized
    private fun drainPythonLog(logFile: File) {
        if (!logFile.isFile) return
        val current = logFile.readText(Charsets.UTF_8)
        if (current.length < lastPythonLogText.length || !current.startsWith(lastPythonLogText)) {
            lastPythonLogText = ""
        }
        if (current.length == lastPythonLogText.length) return
        val delta = current.substring(lastPythonLogText.length)
        lastPythonLogText = current
        delta.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            appendLogRaw(line)
            val matcher = PY_PROGRESS_PATTERN.matcher(line)
            if (matcher.find()) {
                val done = matcher.group(1)?.toIntOrNull() ?: return@forEach
                val total = matcher.group(2)?.toIntOrNull() ?: return@forEach
                if (total > 0) {
                    phase(
                        "正在提取音乐 $done/$total…",
                        0.10f + done.toFloat() / total * 0.45f
                    )
                }
            }
        }
    }

    private fun appendLog(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        appendLogRaw("$ts $message")
    }

    private fun appendLogRaw(message: String) = onUi {
        logs.add(message)
        while (logs.size > MAX_LOG_LINES) logs.removeAt(0)
    }

    private fun clearLog() = onUi { logs.clear() }

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else runOnUiThread(block)
    }

    private fun readableMessage(t: Throwable): String {
        var current: Throwable? = t
        var last = t.javaClass.simpleName
        while (current != null) {
            if (!current.message.isNullOrBlank()) last = current.message!!
            current = current.cause
        }
        return last
    }

    companion object {
        private val PY_PROGRESS_PATTERN = Pattern.compile("\\[进度\\]\\s+(\\d+)/(\\d+)")
        private val LEGACY_PROGRESS_PATTERN = Pattern.compile("\\[进度\\]\\s+(\\d+)/(\\d+)")

        fun safeFileName(value: String): String = value
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]+"""), "_")
            .trim()
            .trim('.')
            .ifBlank { "unnamed" }
            .take(180)

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val mib = bytes / (1024.0 * 1024.0)
            return if (mib >= 1.0) String.format(Locale.US, "%.2f MiB", mib)
            else String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        }

        fun copyStream(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) output.write(buffer, 0, n)
            }
        }

        fun readFully(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        fun deleteRecursively(file: File?) {
            if (file == null || !file.exists()) return
            if (file.isDirectory) file.listFiles()?.forEach(::deleteRecursively)
            file.delete()
        }

        private fun yesNo(value: Boolean): String = if (value) "是" else "否"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppScreen(
    state: AppUiState,
    logs: List<String>,
    onOutputUri: (Uri) -> Unit,
    onFormat: (OutputFormat) -> Unit,
    onKeepOgg: (Boolean) -> Unit,
    onFetchCover: (Boolean) -> Unit,
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
    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onOutputUri(uri)
    }

    MaterialExpressiveTheme(colorScheme = colors) {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Phigros Music Extractor",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Root 本地提取 · OGG 断点续跑 · Hi-Res 保护 · 完整标签 · Material 3 Expressive",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StatusCard(state)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "输出设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            state.outputUri?.toString() ?: "尚未选择输出目录",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FilledTonalButton(
                            onClick = { directoryLauncher.launch(state.outputUri) },
                            enabled = !state.running
                        ) { Text("选择输出目录") }

                        Text("目标格式", style = MaterialTheme.typography.labelLarge)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            OutputFormat.entries.forEachIndexed { index, format ->
                                SegmentedButton(
                                    selected = state.outputFormat == format,
                                    onClick = { onFormat(format) },
                                    enabled = !state.running,
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index,
                                        OutputFormat.entries.size
                                    ),
                                    label = { Text(format.displayName) }
                                )
                            }
                        }

                        Text(
                            if (state.outputFormat == OutputFormat.FLAC)
                                "FLAC 保持源采样率与声道，并在导出后再次校验；检测到 Hi-Res 源时防止 16-bit 静默降级。"
                            else
                                "MP3 使用 320 kbps。若源参数属于 Hi-Res 且 MP3 无法表示，将直接阻止转换，不伪造 Hi-Res 标签。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                SettingCard(
                    title = "写入专辑封面",
                    subtitle = "只控制曲绘下载/嵌入；作曲者、章节、谱师、难度等文字元数据始终写入。",
                    checked = state.fetchCover,
                    enabled = !state.running,
                    onCheckedChange = onFetchCover
                )
                SettingCard(
                    title = "同时保留原始 OGG",
                    subtitle = "额外写到你选择的目录；应用内部的 OGG 断点会自动保留用于失败后续跑。",
                    checked = state.keepOgg,
                    enabled = !state.running,
                    onCheckedChange = onKeepOgg
                )

                FilledTonalButton(
                    onClick = onStart,
                    enabled = !state.running && state.outputUri != null && state.phigrosInstalled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            state.running -> "处理中…"
                            state.cachedOggCount > 0 -> "从 OGG 断点继续整理"
                            else -> "开始提取并整理音乐"
                        }
                    )
                }

                LinearWavyProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LogCard(logs)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(state: AppUiState) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "设备状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Root：${when (state.rootOk) {
                    true -> "可用"
                    false -> "不可用"
                    null -> "检测中"
                }}"
            )
            Text(
                if (state.phigrosInstalled)
                    "Phigros：${state.phigrosVersion.ifBlank { "已安装" }} · base + ${state.splitCount} split"
                else "Phigros：未检测到"
            )
            Text(
                if (state.cachedOggCount > 0)
                    "OGG 断点：${state.cachedOggCount} 首，可直接继续转码"
                else "OGG 断点：暂无"
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun LogCard(logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "运行日志 / 音质验收",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                ) {
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
