package app.msime.android;

import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * `:ime` 进程的启动自检：把每次 {@code MSIMEInputService.onCreate} 的耗时、随后的绑定与启动输入耗时、异常，以及「上一个进程有没有正常退出」记在一个私有文件里，设置页「输入法启动自检」读它。
 *
 * <p>存在的理由是一个反复出现、又拿不到日志的故障：装好后输入法「调不起来」，要先启用别的输入法再切回来。那种情形下输入法进程
 * 要么崩在 {@code onCreate} 里，要么崩在系统绑定之后（{@code onBindInput}/{@code onStartInput}/{@code onStartInputView}），
 * 要么首帧超过系统允许的绑定时间，而用户手上没有 logcat。这里把这些节点留在设备上。
 *
 * <p>「被系统杀死/重启」靠一个存活标记：{@code onCreate} 写标记，{@code onDestroy} 删标记。下一次启动时标记还在，
 * 说明上一个进程没走过 {@code onDestroy}——被系统回收、被杀或崩溃，都记成 {@code prev=unclean} 并累加计数。
 *
 * <p>跨进程读写：{@code :ime} 写（工作线程），设置应用的主进程读同一个私有目录下的文件，所以用文件而不是
 * SharedPreferences —— 两个进程各有一份内存缓存，SharedPreferences 的值在这里会读到过期的那份。写只保留最近
 * {@link #KEEP} 行，文件大小有限，任何时候读也不会有额外代价。
 */
public final class ImeStartupProbe {
    private static final String TAG = "MsimeImeStartup";
    private static final String FILE_NAME = "ime-startup.txt";
    /** 存活标记：{@code onCreate} 写、{@code onDestroy} 删；下次启动还在就是没正常退出。 */
    private static final String MARKER_NAME = "ime-startup-alive.txt";
    /** 启动次数与「没正常退出」次数。 */
    private static final String COUNTERS_NAME = "ime-startup-counters.txt";
    /** 保留最近多少次启动。 */
    private static final int KEEP = 20;
    private static final int MAX_SUMMARY_CHARS = 2400;
    /**
     * 只在这个窗口内记绑定与启动输入的耗时：出问题的是「刚起来那一下」，之后每次换输入框的几百微秒没有意义，
     * 也不该为它写文件。
     */
    private static final long STAGE_WINDOW_MILLIS = 15_000;
    /** 两个线程（onCreate 的同步路径、onStartInput 的主线程）可能同时写，串行化。 */
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private ImeStartupProbe() {}

    /**
     * 记一次启动。在工作线程上调用；写不进去时只记日志，绝不影响键盘。
     *
     * @param createMillis 这次 {@code onCreate} 花了多少毫秒
     * @param stage        `ok` 表示走完，其他值是走到的最后一步
     * @param error        致命中途抛出的异常；没有时传 null
     */
    public static void report(android.content.Context context, long createMillis, String stage, Throwable error) {
        try {
            File file = file(context, FILE_NAME);
            if (file == null) return;
            synchronized (LOCK) {
                File marker = file(context, MARKER_NAME);
                boolean unclean = marker != null && marker.exists();
                List<String> lines = read(file);
                StringBuilder line = new StringBuilder()
                    .append(stamp())
                    .append("  create=").append(createMillis).append("ms")
                    .append("  stage=").append(stage == null ? "ok" : stage);
                if (unclean) line.append("  prev=unclean");
                appendError(line, error);
                lines.add(sanitize(line));
                while (lines.size() > KEEP) lines.remove(0);
                Files.write(file.toPath(), join(lines).getBytes(StandardCharsets.UTF_8));
                bumpCounters(context, unclean);
                writeMarker(context, marker);
            }
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "startup probe not written", failed);
        }
    }

    /**
     * 记一个启动节点的耗时：{@code onBindInput}、{@code onStartInput}、{@code onStartInputView} 进来时各调一次。
     *
     * <p>只在这个进程刚起来的 {@link #STAGE_WINDOW_MILLIS} 内落盘，所以正常使用时不产生额外写入；异常由
     * {@link #report} 的 {@code prev=unclean} 体现（崩了就走不到 {@code onDestroy}，下一次启动会记下）。
     *
     * @param createAtMs {@code onCreate} 开始的 uptime 毫秒；还没记到（-1）时不写
     * @param stage      节点名
     * @param error      这个节点抛出的异常；没有时传 null
     */
    public static void stage(android.content.Context context, long createAtMs, String stage, Throwable error) {
        if (createAtMs < 0) return;
        long after = android.os.SystemClock.uptimeMillis() - createAtMs;
        if (after < 0 || after > STAGE_WINDOW_MILLIS) return;
        try {
            File file = file(context, FILE_NAME);
            if (file == null) return;
            synchronized (LOCK) {
                List<String> lines = read(file);
                StringBuilder line = new StringBuilder()
                    .append(stamp())
                    .append("    ").append(stage)
                    .append("=+").append(after).append("ms");
                appendError(line, error);
                lines.add(sanitize(line));
                while (lines.size() > KEEP) lines.remove(0);
                Files.write(file.toPath(), join(lines).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "startup stage not written", failed);
        }
    }

    /** {@code onDestroy}：删掉存活标记，这一次算正常退出。 */
    public static void cleanExit(android.content.Context context) {
        try {
            File marker = file(context, MARKER_NAME);
            if (marker == null) return;
            synchronized (LOCK) {
                if (!marker.delete() && marker.exists()) Log.w(TAG, "startup marker not deleted");
            }
        } catch (RuntimeException failed) {
            Log.w(TAG, "startup marker not deleted", failed);
        }
    }

    /** 设置页要显示的那几行；还没有记录时说明一句。 */
    public static String summary(android.content.Context context) {
        File file = file(context, FILE_NAME);
        if (file == null || !file.exists()) return "还没有记录：这个版本的输入法进程还没在这台设备上启动过。";
        try {
            String text;
            synchronized (LOCK) {
                text = String.join("\n", read(file));
                String counters = readCounters(context);
                if (!counters.isEmpty()) text = text + "\n" + counters;
            }
            if (text.isEmpty()) return "还没有记录：这个版本的输入法进程还没在这台设备上启动过。";
            if (text.length() > MAX_SUMMARY_CHARS) text = text.substring(text.length() - MAX_SUMMARY_CHARS);
            return text;
        } catch (IOException unreadable) {
            return "读不出记录：" + unreadable.getMessage();
        }
    }

    private static void appendError(StringBuilder line, Throwable error) {
        if (error == null) return;
        line.append("  error=").append(error.getClass().getName());
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) line.append(": ").append(message);
    }

    private static String sanitize(StringBuilder line) {
        return line.toString().replace('\n', ' ').replace('\r', ' ');
    }

    private static String stamp() {
        return STAMP.format(new Date());
    }

    /** 探针文件；拿不到私有目录时返回 null。 */
    private static File file(android.content.Context context, String name) {
        File files = context == null ? null : context.getFilesDir();
        return files == null ? null : new File(files, name);
    }

    private static void writeMarker(android.content.Context context, File marker) throws IOException {
        if (marker == null) return;
        Files.write(marker.toPath(),
            ("pid=" + android.os.Process.myPid() + " t=" + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8));
    }

    /** 累计启动次数与「上一个进程没正常退出」次数。读不出来时重记成从这一次算起。 */
    private static void bumpCounters(android.content.Context context, boolean unclean) {
        File counters = file(context, COUNTERS_NAME);
        if (counters == null) return;
        try {
            long starts = 0;
            long uncleanStarts = 0;
            if (counters.exists()) {
                for (String line : read(counters)) {
                    String[] parts = line.split("=");
                    if (parts.length != 2) continue;
                    long value = Long.parseLong(parts[1].trim());
                    if ("starts".equals(parts[0].trim())) starts = value;
                    if ("unclean".equals(parts[0].trim())) uncleanStarts = value;
                }
            }
            starts += 1;
            if (unclean) uncleanStarts += 1;
            Files.write(counters.toPath(),
                ("starts=" + starts + "\nunclean=" + uncleanStarts + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // 计数只是台账，坏了不影响诊断行。
        }
    }

    private static String readCounters(android.content.Context context) throws IOException {
        File counters = file(context, COUNTERS_NAME);
        if (counters == null || !counters.exists()) return "";
        long starts = 0;
        long unclean = 0;
        for (String line : read(counters)) {
            String[] parts = line.split("=");
            if (parts.length != 2) continue;
            long value = Long.parseLong(parts[1].trim());
            if ("starts".equals(parts[0].trim())) starts = value;
            if ("unclean".equals(parts[0].trim())) unclean = value;
        }
        if (starts <= 0) return "";
        return "累计启动 " + starts + " 次，其中 " + unclean + " 次上一个进程没有正常退出（被系统回收、被杀或崩溃）。";
    }

    private static List<String> read(File file) throws IOException {
        if (!file.exists()) return new ArrayList<>();
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }
}
