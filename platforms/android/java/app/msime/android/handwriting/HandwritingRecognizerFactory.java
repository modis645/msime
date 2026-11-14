package app.msime.android;

import android.content.Context;
import java.lang.reflect.InvocationTargetException;

/** Keeps optional model SDK classes outside the lightweight Android preview classpath. */
public final class HandwritingRecognizerFactory {
    private static final String IMPLEMENTATION = "app.msime.android.MlKitHandwritingRecognizer";

    private HandwritingRecognizerFactory() {}

    public static HandwritingRecognizer create(Context context) {
        initialize(context);
        try {
            Object value = Class.forName(IMPLEMENTATION).getConstructor(Context.class)
                .newInstance(context);
            if (value instanceof HandwritingRecognizer recognizer) return recognizer;
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException | SecurityException
                 | LinkageError error) {
            // 预览构建本来就没有 ML Kit 依赖。真正的失败原因（例如构造时抛的 MlKitException）只记类型，
            // 不记内容：`adb logcat -s MsimeHandwriting` 能看到，用户不用打开任何调试开关。
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
            android.util.Log.w("MsimeHandwriting",
                "handwriting recognizer unavailable: " + cause.getClass().getName());
        }
        return new UnavailableRecognizer();
    }

    /**
     * 在手写第一次被用到时初始化 ML Kit，而不是在 `:ime` 进程启动时。
     *
     * <p>进程启动时初始化放在 {@link MlKitImeInitProvider} 里跑过一版，那等于每次冷启动都替手写付一笔钱；
     * 后来为了启动把它删掉，结果是手写面板构造识别器时 ML Kit 还没初始化，
     * `DigitalInkRecognition.getClient` 抛 MlKitException，工厂退回 UnavailableRecognizer，
     * 面板显示「此构建不含手写识别」——模型明明已经下载好了。放在这里既不在启动路径上，
     * 又在真正要手写之前，失败也只影响手写。
     */
    private static void initialize(Context context) {
        if (context == null) return;
        try {
            Class.forName("com.google.mlkit.common.MlKit")
                .getMethod("initialize", Context.class)
                .invoke(null, context);
        } catch (Throwable ignored) {
            // 没有 ML Kit 的预览构建：照旧走下面的反射，失败就回退到 UnavailableRecognizer。
        }
    }

    private static final class UnavailableRecognizer implements HandwritingRecognizer {
        @Override public Availability availability() { return Availability.UNAVAILABLE; }
        @Override public void download(DownloadListener listener) { listener.onFailure(); }
        @Override public void recognize(Request request, RecognitionListener listener) {
            listener.onFailure(request.revision());
        }
        @Override public void cancelPending() {}
        @Override public void close() {}
    }
}
