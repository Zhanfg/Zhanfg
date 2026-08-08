package cc.axymorrsen.phigrosextractor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String PHIGROS_PACKAGE = "com.PigeonGames.Phigros";
    private static final int REQ_PICK_DIR = 1001;
    private static final String PREFS = "prefs";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final int MAX_LOG_CHARS = 60000;
    private static final Pattern PY_PROGRESS_PATTERN =
            Pattern.compile("\\[进度\\]\\s+(\\d+)/(\\d+)");

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService logWatcher = Executors.newSingleThreadScheduledExecutor();

    private TextView status;
    private TextView outputPath;
    private TextView logView;
    private ScrollView logScroll;
    private ProgressBar progress;
    private Button extractButton;
    private Uri outputTreeUri;

    private ScheduledFuture<?> logFuture;
    private String lastPythonLogText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreOutputDirectory();
        appendLog("应用已启动，正在检查运行环境。");
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        stopPythonLogPolling();
        worker.shutdownNow();
        logWatcher.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Phigros Music Extractor");
        title.setTextSize(24f);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("本机 Root 提取。不会修改 Phigros，不依赖 Termux，不联网。");
        desc.setTextSize(14f);
        desc.setPadding(0, dp(8), 0, dp(16));
        root.addView(desc);

        status = new TextView(this);
        status.setTextSize(14f);
        root.addView(status);

        Button chooseDir = new Button(this);
        chooseDir.setText("选择输出目录");
        chooseDir.setOnClickListener(v -> chooseOutputDirectory());
        root.addView(chooseDir);

        outputPath = new TextView(this);
        outputPath.setTextSize(13f);
        outputPath.setPadding(0, dp(8), 0, dp(12));
        root.addView(outputPath);

        extractButton = new Button(this);
        extractButton.setText("开始提取音乐");
        extractButton.setOnClickListener(v -> startExtraction());
        root.addView(extractButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setPadding(0, dp(16), 0, dp(8));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView logTitle = new TextView(this);
        logTitle.setText("运行日志 / 解析反馈");
        logTitle.setTextSize(15f);
        logTitle.setPadding(0, dp(8), 0, dp(6));
        root.addView(logTitle);

        logView = new TextView(this);
        logView.setTextSize(12f);
        logView.setTextColor(Color.rgb(235, 235, 235));
        logView.setBackgroundColor(Color.rgb(24, 24, 24));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(10), dp(12), dp(10));

        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(300)
        );
        root.addView(logScroll, logParams);

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.addView(root);
        setContentView(page);
    }

    private void refreshStatus() {
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            boolean rootOk = checkRoot();
            sb.append("Root: ").append(rootOk ? "可用" : "不可用").append('\n');

            try {
                PackageInfo pi = getPackageManager().getPackageInfo(PHIGROS_PACKAGE, 0);
                sb.append("Phigros: 已安装");
                if (pi.versionName != null) {
                    sb.append("  v").append(pi.versionName);
                }
                sb.append('\n');

                ApplicationInfo ai = getPackageManager().getApplicationInfo(PHIGROS_PACKAGE, 0);
                int splitCount = ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length;
                sb.append("APK: base + ").append(splitCount).append(" split");

                appendLog("环境检查：Root=" + (rootOk ? "可用" : "不可用")
                        + "，Phigros=" + (pi.versionName == null ? "已安装" : pi.versionName)
                        + "，split=" + splitCount);
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("Phigros: 未安装");
                appendLog("环境检查：未检测到 Phigros。");
            }

            runOnUiThread(() -> status.setText(sb.toString()));
        });
    }

    private void chooseOutputDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_DIR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_DIR || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }

        outputTreeUri = uri;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_TREE_URI, uri.toString()).apply();
        outputPath.setText("输出目录：" + uri);
        appendLog("输出目录已设置：" + uri);
    }

    private void restoreOutputDirectory() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_TREE_URI, null);
        if (saved != null) {
            outputTreeUri = Uri.parse(saved);
            outputPath.setText("输出目录：" + outputTreeUri);
        } else {
            outputPath.setText("输出目录：尚未选择");
        }
    }

    private void startExtraction() {
        if (outputTreeUri == null) {
            setStatus("请先选择输出目录。", false);
            appendLog("任务未开始：尚未选择输出目录。");
            return;
        }

        clearLog();
        extractButton.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setText("正在准备……");

        int workerCount = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        appendLog("开始提取任务。");
        appendLog("解析线程数：" + workerCount + "（按设备 CPU 自动限制为最多 4 线程）。");

        worker.execute(() -> {
            File workRoot = new File(getCacheDir(), "phigros_extract");
            deleteRecursively(workRoot);
            File apkDir = new File(workRoot, "apks");
            File outDir = new File(workRoot, "out");
            File pythonLog = new File(workRoot, "extract-progress.log");
            apkDir.mkdirs();
            outDir.mkdirs();

            try {
                if (!checkRoot()) {
                    throw new IllegalStateException("未获得 Root。请在你的 Root 管理器里允许本应用的 su 请求。");
                }

                updatePhase("正在定位 Phigros 安装包……");
                List<String> sourceApks = getPhigrosApkPaths();
                if (sourceApks.isEmpty()) {
                    throw new IllegalStateException("没有找到 Phigros APK。");
                }
                appendLog("定位到 " + sourceApks.size() + " 个 APK 文件。");
                setTaskProgress(3);

                updatePhase("正在通过 Root 读取 APK……");
                List<String> localApks = new ArrayList<>();
                for (int i = 0; i < sourceApks.size(); i++) {
                    String source = sourceApks.get(i);
                    File dst = new File(apkDir, String.format(Locale.US, "%02d.apk", i));
                    appendLog("读取 APK " + (i + 1) + "/" + sourceApks.size() + "：" + source);
                    copyProtectedFile(source, dst);
                    localApks.add(dst.getAbsolutePath());
                    appendLog("APK 已缓存：" + dst.getName() + "，"
                            + formatBytes(dst.length()));
                    setTaskProgress(3 + Math.round((i + 1) * 7f / sourceApks.size()));
                }

                updatePhase("正在并发解析 Addressables / UnityFS / FSB5……");
                setTaskProgress(10);
                startPythonLogPolling(pythonLog);

                PyObject result;
                try {
                    Python py = Python.getInstance();
                    PyObject module = py.getModule("extractor");
                    String joined = String.join("\n", localApks);
                    result = module.callAttr(
                            "extract_from_apks",
                            joined,
                            outDir.getAbsolutePath(),
                            getApplicationInfo().nativeLibraryDir,
                            pythonLog.getAbsolutePath(),
                            workerCount
                    );
                } finally {
                    drainPythonLog(pythonLog);
                    stopPythonLogPolling();
                }

                int extracted = result.toInt();
                if (extracted <= 0) {
                    throw new IllegalStateException("解析完成，但没有提取到音乐。游戏资源结构可能已经变化。");
                }

                appendLog("解析阶段完成，共生成 " + extracted + " 首音频。");
                updatePhase("正在写入你选择的目录……");
                setTaskProgress(86);

                int copied = copyResultsToTree(outDir, outputTreeUri);
                if (copied <= 0) {
                    throw new IllegalStateException("音乐已解析，但写入输出目录失败。");
                }

                int finalCopied = copied;
                appendLog("全部完成：成功写入 " + finalCopied + " 首音乐。");
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(100);
                    extractButton.setEnabled(true);
                    status.setText("完成：已写入 " + finalCopied + " 首音乐。\n输出目录：" + outputTreeUri);
                });
            } catch (Throwable t) {
                stopPythonLogPolling();
                String message = readableMessage(t);
                appendLog("任务失败：" + message);
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(0);
                    extractButton.setEnabled(true);
                    status.setText("失败：" + message);
                });
            } finally {
                deleteRecursively(apkDir);
            }
        });
    }

    private List<String> getPhigrosApkPaths() throws PackageManager.NameNotFoundException {
        ApplicationInfo ai = getPackageManager().getApplicationInfo(PHIGROS_PACKAGE, 0);
        List<String> paths = new ArrayList<>();
        if (ai.sourceDir != null) {
            paths.add(ai.sourceDir);
        }
        if (ai.splitSourceDirs != null) {
            paths.addAll(Arrays.asList(ai.splitSourceDirs));
        }
        return paths;
    }

    private boolean checkRoot() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").start();
            String text;
            try (InputStream in = p.getInputStream()) {
                text = new String(readFully(in), StandardCharsets.UTF_8).trim();
            }
            int code = p.waitFor();
            return code == 0 && "0".equals(text);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void copyProtectedFile(String source, File destination) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            copyStream(in, out);
            return;
        } catch (Exception ignored) {
        }

        String command = "cat " + shellQuote(source);
        Process p = new ProcessBuilder("su", "-c", command).start();

        try (InputStream in = new BufferedInputStream(p.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            copyStream(in, out);
        }

        int code = p.waitFor();
        if (code != 0 || destination.length() == 0) {
            String err;
            try (InputStream e = p.getErrorStream()) {
                err = new String(readFully(e), StandardCharsets.UTF_8).trim();
            }
            destination.delete();
            throw new IllegalStateException("Root 读取 APK 失败：" + source + (err.isEmpty() ? "" : "\n" + err));
        }
    }

    private int copyResultsToTree(File outDir, Uri treeUri) throws Exception {
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.canWrite()) {
            throw new IllegalStateException("所选目录当前不可写，请重新选择目录。");
        }

        File[] files = outDir.listFiles(file -> file.isFile() && isAudioFile(file.getName()));
        if (files == null) {
            return 0;
        }

        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int copied = 0;
        for (File src : files) {
            DocumentFile old = root.findFile(src.getName());
            if (old != null) {
                old.delete();
            }

            DocumentFile dst = root.createFile(mimeFor(src.getName()), src.getName());
            if (dst == null) {
                throw new IllegalStateException("无法创建文件：" + src.getName());
            }

            try (InputStream in = new BufferedInputStream(new FileInputStream(src));
                 OutputStream out = new BufferedOutputStream(
                         requireNonNull(getContentResolver().openOutputStream(dst.getUri(), "wt"), "无法打开输出流")
                 )) {
                copyStream(in, out);
            }
            copied++;
            appendLog("[输出] " + copied + "/" + files.length + "  " + src.getName()
                    + "  " + formatBytes(src.length()));
            setTaskProgress(86 + Math.round(copied * 14f / Math.max(1, files.length)));
        }
        return copied;
    }

    private void startPythonLogPolling(File logFile) {
        stopPythonLogPolling();
        lastPythonLogText = "";
        logFuture = logWatcher.scheduleAtFixedRate(
                () -> drainPythonLog(logFile),
                0,
                250,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopPythonLogPolling() {
        ScheduledFuture<?> future = logFuture;
        if (future != null) {
            future.cancel(false);
            logFuture = null;
        }
    }

    private void drainPythonLog(File logFile) {
        if (logFile == null || !logFile.isFile()) {
            return;
        }

        try {
            String current = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
            String delta;
            synchronized (this) {
                if (current.equals(lastPythonLogText)) {
                    return;
                }
                if (current.startsWith(lastPythonLogText)) {
                    delta = current.substring(lastPythonLogText.length());
                } else {
                    delta = current;
                }
                lastPythonLogText = current;
            }

            if (!delta.isEmpty()) {
                appendRawLog(delta);
                updateProgressFromPythonLog(delta);
            }
        } catch (Throwable ignored) {
            // Logging must never abort extraction.
        }
    }

    private void updateProgressFromPythonLog(String text) {
        Matcher matcher = PY_PROGRESS_PATTERN.matcher(text);
        int done = -1;
        int total = -1;
        while (matcher.find()) {
            done = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        }

        if (done >= 0 && total > 0) {
            int value = 10 + Math.round(done * 75f / total);
            setTaskProgress(Math.min(85, value));
        }
    }

    private void clearLog() {
        runOnUiThread(() -> logView.setText(""));
        synchronized (this) {
            lastPythonLogText = "";
        }
    }

    private void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        appendRawLog(timestamp + " " + message + "\n");
    }

    private void appendRawLog(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            String merged = logView.getText().toString() + text;
            if (merged.length() > MAX_LOG_CHARS) {
                merged = "……较早日志已截断……\n"
                        + merged.substring(merged.length() - MAX_LOG_CHARS);
            }
            logView.setText(merged);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setTaskProgress(int value) {
        runOnUiThread(() -> {
            progress.setIndeterminate(false);
            progress.setProgress(Math.max(0, Math.min(100, value)));
        });
    }

    private static boolean isAudioFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".ogg") || lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "audio/ogg";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f MiB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KiB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n > 0) out.write(buffer, 0, n);
        }
    }

    private static byte[] readFully(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n > 0) out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void updatePhase(String text) {
        appendLog(text);
        runOnUiThread(() -> status.setText(text));
    }

    private void setStatus(String text, boolean running) {
        status.setText(text);
        extractButton.setEnabled(!running);
        progress.setIndeterminate(running);
    }

    private String readableMessage(Throwable t) {
        Throwable cur = t;
        String last = t.getClass().getSimpleName();
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().trim().isEmpty()) {
                last = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return last;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        f.delete();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
