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
 * `:ime` 进程的启动自检：把每次 {@code MSIMEInputService.onCreate} 的耗时和异常记几行到私有目录，设置页「输入法启动自检」读它。
 *
 * <p>存在的理由是一个反复出现、又拿不到日志的故障：装好后输入法「调不起来」，要先启用别的输入法再切回来。那种情形下
 * 输入法进程要么崩在 {@code onCreate} 里，要么首帧超过系统允许的绑定时间，而用户手上没有 logcat。这里用一份十几行的
 * 文本文件把这个过程留在设备上，出问题时第一眼就能看到是「没起来」还是「起来了但很慢」。
 *
 * <p>跨进程读写：{@code :ime} 写（工作线程），设置应用的主进程读同一个私有目录下的文件，所以用文件而不是
 * SharedPreferences —— 两个进程各有一份内存缓存，SharedPreferences 的值在这里会读到过期的那份。写只保留最近
 * {@link #KEEP} 行，文件大小有限，任何时候读也不会有额外代价。
 */
public final class ImeStartupProbe {
    private static final String TAG = "MsimeImeStartup";
    private static final String FILE_NAME = "ime-startup.txt";
    /** 保留最近多少次启动。 */
    private static final int KEEP = 20;
    private static final int MAX_SUMMARY_CHARS = 2000;

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
            File file = file(context);
            if (file == null) return;
            List<String> lines = read(file);
            StringBuilder line = new StringBuilder()
                .append(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("  create=").append(createMillis).append("ms")
                .append("  stage=").append(stage == null ? "ok" : stage);
            if (error != null) {
                line.append("  error=").append(error.getClass().getName());
                String message = error.getMessage();
                if (message != null && !message.isEmpty()) line.append(": ").append(message);
            }
            lines.add(line.toString().replace('\n', ' '));
            while (lines.size() > KEEP) lines.remove(0);
            Files.write(file.toPath(), join(lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failed) {
            Log.w(TAG, "startup probe not written", failed);
        }
    }

    /** 设置页要显示的那几行；还没有记录时说明一句。 */
    public static String summary(android.content.Context context) {
        File file = file(context);
        if (file == null || !file.exists()) return "还没有记录：这个版本的输入法进程还没在这台设备上启动过。";
        try {
            String text = String.join("\n", read(file));
            if (text.isEmpty()) return "还没有记录：这个版本的输入法进程还没在这台设备上启动过。";
            if (text.length() > MAX_SUMMARY_CHARS) text = text.substring(text.length() - MAX_SUMMARY_CHARS);
            return text;
        } catch (IOException unreadable) {
            return "读不出记录：" + unreadable.getMessage();
        }
    }

    /** 探针文件；拿不到私有目录时返回 null。 */
    private static File file(android.content.Context context) {
        File files = context == null ? null : context.getFilesDir();
        return files == null ? null : new File(files, FILE_NAME);
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
