package app.msime.android;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 装好之后自动把要用的三样东西装上，用户不用再去找下载按钮：中文手写识别模型（ML Kit）、
 * 本地语音运行库（{@code voice-runtime} 资源包）和本地语音模型（固定「快速整句」{@code sense-voice-small}）。
 *
 * <p>前两样原来都要人自己进设置页点一下：手写在键盘面板里点「下载」，语音要先装运行库再装模型。
 * 这一版的默认输入法装上就该能完整用，所以第一次打开设置应用（主进程）时替用户排好，进度和结果在
 * 资源下载通知里；用户想省流量仍可以在设置页取消下载再删。
 *
 * <p>只跑一次：跑过就在 {@code msime_home_v1} 里记下，之后不再自动下载——用户删掉的模型不该被又装回来。
 * 语音运行库和语音模型一次只排一个：共享层同一个时刻只允许一个资源包装载（{@code resource_pack_busy}），
 * 跑完一个再由 {@link ResourcePackService.Listener} 触发下一个。
 */
public final class FirstRunModels {
    /** 本地语音模型：目录里「快速整句」那一档（{@code sense-voice-small}），说完后整句识别、速度最快。 */
    public static final String VOICE_MODEL_ID = "sense-voice-small";
    public static final String VOICE_MODEL_TITLE = "快速整句";
    private static final String STORE = "msime_home_v1";
    private static final String KEY_DONE = "first_run_models_requested";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
        ThreadPolicy.namedFactory("msime-first-run-models"));
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();
    /** 资源包服务跑完一个就再走一轮，接着排下一个。 */
    private static final ResourcePackService.Listener FINISHED = (pack, finished) -> {
        Context context = application;
        if (finished && context != null) startIfNeeded(context);
    };
    private static volatile Context application;

    private FirstRunModels() { }

    /** 三样都齐了就不再动；第一次进入时替用户排好，之后只跟着资源包服务的完成回调往前推。 */
    public static void startIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        application = app;
        SharedPreferences store = app.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        if (store.getBoolean(KEY_DONE, false)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        if (LISTENING.compareAndSet(false, true)) ResourcePackService.addListener(FINISHED);
        WORKER.execute(() -> {
            boolean done;
            try {
                done = advance(app);
            } catch (RuntimeException | LinkageError error) {
                // 共享层暂时不可用（库没加载上）不是用户的事：下次打开设置应用再试。
                android.util.Log.w("MsimeFirstRunModels",
                    "handwriting and voice downloads skipped: " + error.getClass().getName());
                done = false;
            } finally {
                RUNNING.set(false);
            }
            if (done) store.edit().putBoolean(KEY_DONE, true).apply();
        });
    }

    /** 走一轮：返回三样是否都已经齐了。缺什么就排什么，排过一次之后返回 false，等完成回调再来。 */
    private static boolean advance(Context context) {
        boolean complete = requestHandwriting();
        if (!ResourcePacks.installed(context, ResourcePacks.VOICE_RUNTIME)) {
            ResourcePacks.Pack runtime = ResourcePacks.pack(context.getFilesDir(), ResourcePacks.VOICE_RUNTIME);
            if (runtime == null || running(runtime.id())) return false;
            ResourcePackService.startAutomatic(context, runtime.id(), null);
            return false;
        }
        if (!voiceModelInstalled(context)) {
            String key = ResourcePackService.voiceModelKey(VOICE_MODEL_ID);
            if (running(key)) return false;
            ResourcePackService.startAutomatic(context, key, VOICE_MODEL_TITLE);
            return false;
        }
        return complete;
    }

    /**
     * 手写模型走 ML Kit 自己的下载，和资源包服务无关：先读状态，只有确实没装才下，装好了就不动。
     * 返回此刻是否已经装好；还在下载或读不到状态时为 false，交给下一次打开设置应用。
     */
    private static boolean requestHandwriting() {
        final boolean[] ready = {false};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        MlKitHandwritingModel.check(installed -> {
            ready[0] = installed;
            if (!installed) MlKitHandwritingModel.download(result -> { });
            latch.countDown();
        });
        try {
            latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return ready[0];
    }

    /** 目录里那一档「快速整句」是否已经装好；读不出目录时按没装处理。 */
    private static boolean voiceModelInstalled(Context context) {
        try {
            List<LocalVoiceModels.Model> models = LocalVoiceModels.list(context.getFilesDir());
            for (LocalVoiceModels.Model model : models) {
                if (VOICE_MODEL_ID.equals(model.id())) return model.installed();
            }
        } catch (ResourcePacks.Failure ignored) {
            // 目录读不出来：当作没装，下一轮再说。
        }
        return false;
    }

    private static boolean running(String pack) {
        ResourcePackService.Status status = ResourcePackService.status(pack);
        return status != null && status.running();
    }
}
