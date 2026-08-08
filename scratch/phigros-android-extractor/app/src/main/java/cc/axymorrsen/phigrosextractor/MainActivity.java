package cc.axymorrsen.phigrosextractor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PHIGROS_PACKAGE = "com.PigeonGames.Phigros";
    private static final int REQ_PICK_DIR = 1001;
    private static final String PREFS = "prefs";
    private static final String KEY_TREE_URI = "tree_uri";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextView status;
    private TextView outputPath;
    private ProgressBar progress;
    private Button extractButton;
    private Uri outputTreeUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        restoreOutputDirectory();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
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

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
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
            } catch (PackageManager.NameNotFoundException e) {
                sb.append("Phigros: 未安装");
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
            return;
        }

        extractButton.setEnabled(false);
        progress.setIndeterminate(true);
        setStatus("正在准备……", true);

        worker.execute(() -> {
            File workRoot = new File(getCacheDir(), "phigros_extract");
            deleteRecursively(workRoot);
            File apkDir = new File(workRoot, "apks");
            File outDir = new File(workRoot, "out");
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

                updatePhase("正在通过 Root 读取 APK……");
                List<String> localApks = new ArrayList<>();
                for (int i = 0; i < sourceApks.size(); i++) {
                    File dst = new File(apkDir, String.format("%02d.apk", i));
                    copyProtectedFile(sourceApks.get(i), dst);
                    localApks.add(dst.getAbsolutePath());
                }

                updatePhase("正在解析 Addressables / UnityFS / FSB5……");
                Python py = Python.getInstance();
                PyObject module = py.getModule("extractor");
                String joined = String.join("\n", localApks);
                PyObject result = module.callAttr(
                        "extract_from_apks",
                        joined,
                        outDir.getAbsolutePath(),
                        getApplicationInfo().nativeLibraryDir
                );
                int extracted = result.toInt();

                if (extracted <= 0) {
                    throw new IllegalStateException("解析完成，但没有提取到音乐。游戏资源结构可能已经变化。");
                }

                updatePhase("正在写入你选择的目录……");
                int copied = copyResultsToTree(outDir, outputTreeUri);
                if (copied <= 0) {
                    throw new IllegalStateException("音乐已解析，但写入输出目录失败。");
                }

                int finalCopied = copied;
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(100);
                    extractButton.setEnabled(true);
                    status.setText("完成：已写入 " + finalCopied + " 首音乐。\n输出目录：" + outputTreeUri);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(0);
                    extractButton.setEnabled(true);
                    status.setText("失败：" + readableMessage(t));
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
        }
        return copied;
    }

    private static boolean isAudioFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".ogg") || lower.endsWith(".mp3") || lower.endsWith(".wav");
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "audio/ogg";
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
