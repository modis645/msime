package app.msime.android.home;

import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import app.msime.android.AndroidLocalSettings;
import app.msime.android.MlKitHandwritingModel;

/**
 * 手写输入页：书写模式、识别等待时间、识别后显示拼音、笔迹颜色和笔迹粗细。它们只有 Android 用，存在 {@link AndroidLocalSettings} 的 `platform.android.handwriting_*` 里（不在共享偏好里），键盘的手写面板读同一个文件。
 *
 * <p>范围与步长（等待 200–1500 ms 步长 100，粗细 1–8 px）由本地设置的校验给出，与同步时 client-core 接受的范围相同。
 *
 * <p>手写模型是 Google ML Kit 的中文数字墨水模型，不随包发布，第一次用手写键盘时由键盘那侧下载；这一页读同一份状态，也可以直接下载，两边不再是两套东西。下载没有百分比进度（ML Kit 不提供），所以只有「下载中」和「已安装/未安装」三种状态，下载完成后会回读一次确认确实装好。
 */
public final class HandwritingPage extends ReloadingDetailPage {
    private static final String[] MODES = {"single", "overlap", "line"};
    private static final String[] MODE_LABELS = {"单字", "叠写", "行写"};
    private static final String[] COLORS = {"follow_skin", "black", "white", "blue"};
    private static final String[] COLOR_LABELS = {"跟随皮肤", "黑色", "白色", "蓝色"};

    /** 当前页面显示的设置，选择面板据此打 ✓。 */
    private AndroidLocalSettings.Snapshot shown = AndroidLocalSettings.defaults();
    /** 手写模型的安装状态；null 表示还在读。 */
    @Nullable private Boolean modelInstalled;
    private boolean modelDownloading;

    @Override protected void reload() {
        HostTask.run(this, context -> AndroidLocalSettings.load(context), this::render);
    }

    private void render(AndroidLocalSettings.Snapshot settings) {
        LinearLayout target = contentColumn();
        if (target == null) return;
        target.removeAllViews();
        shown = settings;
        renderSettings(target, settings);
        renderModel(target);
    }

    private void renderSettings(LinearLayout target, AndroidLocalSettings.Snapshot settings) {
        String mode = settings.choice(AndroidLocalSettings.HANDWRITING_MODE);
        int delay = settings.integer(AndroidLocalSettings.HANDWRITING_DELAY_MS);
        String color = settings.choice(AndroidLocalSettings.HANDWRITING_STROKE_COLOR);
        int width = settings.integer(AndroidLocalSettings.HANDWRITING_STROKE_WIDTH);
        AndroidLocalSettings.Spec delaySpec = AndroidLocalSettings.spec(AndroidLocalSettings.HANDWRITING_DELAY_MS);
        AndroidLocalSettings.Spec widthSpec = AndroidLocalSettings.spec(AndroidLocalSettings.HANDWRITING_STROKE_WIDTH);

        GroupCard writing = GroupCard.add(target, "书写");
        GroupCard.Row[] modeRow = new GroupCard.Row[1];
        modeRow[0] = writing.nav("书写模式", null, label(MODES, MODE_LABELS, mode, 1), () -> pick("书写模式",
            MODES, MODE_LABELS, AndroidLocalSettings.HANDWRITING_MODE, modeRow[0]));
        writing.slider("识别等待时间", delaySpec.min, delaySpec.max, delaySpec.step, delay, value -> value + "ms",
            value -> save(AndroidLocalSettings.HANDWRITING_DELAY_MS, value));
        writing.toggle("识别后显示拼音", null, settings.bool(AndroidLocalSettings.HANDWRITING_SHOW_PINYIN),
            checked -> save(AndroidLocalSettings.HANDWRITING_SHOW_PINYIN, checked));

        GroupCard stroke = GroupCard.add(target, "笔迹");
        GroupCard.Row[] colorRow = new GroupCard.Row[1];
        colorRow[0] = stroke.nav("笔迹颜色", null, label(COLORS, COLOR_LABELS, color, 0), () -> pick("笔迹颜色",
            COLORS, COLOR_LABELS, AndroidLocalSettings.HANDWRITING_STROKE_COLOR, colorRow[0]));
        stroke.slider("笔迹粗细", widthSpec.min, widthSpec.max, widthSpec.step, width, value -> value + "px",
            value -> save(AndroidLocalSettings.HANDWRITING_STROKE_WIDTH, value));
    }

    /** 手写模型的状态与下载：和键盘的手写面板读同一份 ML Kit 模型，下载完再回读确认。 */
    private void renderModel(LinearLayout target) {
        GroupCard group = GroupCard.add(target, "手写模型");
        Boolean installed = modelInstalled;
        group.value("识别模型", null,
            installed == null ? "检查中…" : modelDownloading ? "下载中…" : installed ? "已安装" : "未安装");
        if (installed == null || !installed || modelDownloading) {
            group.button("下载手写模型", null, modelDownloading ? "下载中…" : "下载", this::downloadModel);
            if (modelDownloading) group.help("下载没有百分比进度，完成后自动变成「已安装」；停在下载中不动时多半是网络或 Google Play 服务不可用。");
        }
        group.help(MlKitHandwritingModel.TITLE + "：键盘手写用这一份，下载一次即可离线识别；不装也不影响拼音与语音。");
        if (installed == null) checkModel();
        GroupCard others = GroupCard.add(target, "其他按需下载");
        others.help("整个输入法只有三样东西要按需下载：中文手写识别模型（本页或键盘的手写面板）、"
            + "本地语音模型（语音输入页）、词库更新包（词库页）；其余功能都不额外下载。");
    }

    private void checkModel() {
        MlKitHandwritingModel.check(installed -> {
            if (!isAdded()) return;
            modelInstalled = installed;
            reload();
        });
    }

    private void downloadModel() {
        if (modelDownloading) return;
        modelDownloading = true;
        reload();
        MlKitHandwritingModel.download(installed -> {
            if (!isAdded()) return;
            modelDownloading = false;
            modelInstalled = installed;
            MsToast.show(requireContext(), installed
                ? "手写模型已下载" : "手写模型没有装好，请检查网络或 Google Play 服务后重试");
            reload();
        });
    }

    private void pick(String title, String[] values, String[] labels, String key, GroupCard.Row row) {
        String selected = shown.choice(key);
        OptionSheet sheet = new OptionSheet(requireContext(), title, null);
        for (int index = 0; index < values.length; index++) {
            String value = values[index];
            String text = labels[index];
            sheet.option(text, value.equals(selected), () -> {
                row.setValue(text);
                save(key, value);
            });
        }
        sheet.show();
    }

    private void save(String key, Object value) {
        HostTask.run(this, context -> KeyboardSheets.writeLocal(context, key, value)
            ? AndroidLocalSettings.load(context) : null, saved -> {
            if (saved == null) {
                MsToast.show(requireContext(), "保存失败，请重试");
                reload();
            } else {
                shown = saved;
            }
        });
    }

    private static String label(String[] values, String[] labels, String value, int fallback) {
        for (int index = 0; index < values.length; index++) if (values[index].equals(value)) return labels[index];
        return labels[fallback];
    }
}
