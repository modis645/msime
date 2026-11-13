package app.msime.android;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;

/**
 * 中文手写识别模型（Google ML Kit 数字墨水）：设置页和键盘的手写面板用同一份按需下载的模型，下载后离线可用。
 *
 * <p>设置页原来下载的是共享层的「手写资源包」，键盘却只认 ML Kit 的这份模型，两边不是同一个东西：
 * 设置页报成功、进手写仍然要下载，就是这个错位造成的。现在两边都走这里。
 */
public final class MlKitHandwritingModel {
    public static final String TITLE = "中文手写识别模型（Google ML Kit，离线识别）";

    /** 下载或检查的去向：装好了为真。 */
    public interface Listener { void onResult(boolean installed); }

    private MlKitHandwritingModel() {}

    private static DigitalInkRecognitionModel model() {
        DigitalInkRecognitionModelIdentifier identifier =
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani-CN");
        if (identifier == null) throw new IllegalStateException("Chinese handwriting model unavailable");
        return DigitalInkRecognitionModel.builder(identifier).build();
    }

    /** 读状态：没装好、读不到状态或设备没有可用的 Google Play 服务时都算未装。 */
    public static void check(Listener listener) {
        RemoteModelManager.getInstance().isModelDownloaded(model())
            .addOnSuccessListener(installed -> listener.onResult(installed))
            .addOnFailureListener(error -> listener.onResult(false));
    }

    /** 下载后回读一次状态：只有回读确实装好了才报成功，不把下载任务的回调当成已安装。 */
    public static void download(Listener listener) {
        RemoteModelManager.getInstance()
            .download(model(), new DownloadConditions.Builder().build())
            .addOnSuccessListener(unused -> check(listener))
            .addOnFailureListener(error -> listener.onResult(false));
    }
}
