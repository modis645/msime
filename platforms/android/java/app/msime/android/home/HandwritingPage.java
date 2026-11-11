package app.msime.android.home;

import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import app.msime.android.AndroidLocalSettings;
import app.msime.android.OfflineImportPolicy;
import app.msime.android.ResourcePackService;
import app.msime.android.ResourcePacks;
import app.msime.android.ScreenCaptureProtection;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写输入页：书写模式、识别等待时间、识别后显示拼音、笔迹颜色和笔迹粗细。它们只有 Android 用，存在 {@link AndroidLocalSettings} 的 `platform.android.handwriting_*` 里（不在共享偏好里），键盘的手写面板读同一个文件。
 *
 * <p>范围与步长（等待 200–1500 ms 步长 100，粗细 1–8 px）由本地设置的校验给出，与同步时 client-core 接受的范围相同。
 *
 * <p>手写模型是按需资源包 {@link ResourcePacks#HANDWRITING}，不随包发布，第一次用手写键盘时由键盘那侧下载。这一页只补「网络不通时用自己下载好的文件安装」这一条路：列出共享层给出的 `import_files`（上游文件名和长度），用户下载好后一次选上，交给 {@link ResourcePackService#requestImport} 复制并安装；按长度和 SHA-256 认文件，改名不影响。
 */
public final class HandwritingPage extends ReloadingDetailPage {
    private static final String[] MODES = {"single", "overlap", "line"};
    private static final String[] MODE_LABELS = {"单字", "叠写", "行写"};
    private static final String[] COLORS = {"follow_skin", "black", "white", "blue"};
    private static final String[] COLOR_LABELS = {"跟随皮肤", "黑色", "白色", "蓝色"};

    /** 当前页面显示的设置，选择面板据此打 ✓。 */
    private AndroidLocalSettings.Snapshot shown = AndroidLocalSettings.defaults();

    /** 一次刷新里读到的设置和手写包状态；资源包状态是一次 JNI 调用，只在工作线程上读。 */
    private record State(AndroidLocalSettings.Snapshot settings, @Nullable ResourcePacks.Pack model) {}

    /** 等文件选择器返回时要导入的文件长度。 */
    private record ImportSizes(long[] sizes) {}

    private final ActivityResultLauncher<String[]> openDocuments =
        registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onImportPicked);

    @Nullable private ImportSizes pendingImport;

    @Override protected void reload() {
        HostTask.run(this, context -> new State(AndroidLocalSettings.load(context),
            ResourcePacks.pack(context.getFilesDir(), ResourcePacks.HANDWRITING)), this::render);
    }

    private void render(State state) {
        LinearLayout target = contentColumn();
        if (target == null) return;
        target.removeAllViews();
        shown = state.settings();
        renderSettings(target, state.settings());
        renderModel(target, state.model());
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

    /** 手写模型只在没装好、共享层又给出了导入文件时给一条导入入口；装好时只报状态。 */
    private void renderModel(LinearLayout target, @Nullable ResourcePacks.Pack model) {
        GroupCard group = GroupCard.add(target, "手写模型");
        if (model == null) {
            group.note("读不到手写模型的状态，请稍后返回重试。");
            return;
        }
        if (model.installed()) {
            group.value("手写模型", null, "已安装");
            group.help("手写键盘使用这份模型，不需要再下载。");
            return;
        }
        group.value("手写模型", null, "outdated".equals(model.state()) ? "需要更新" : "未安装");
        ResourcePackService.Status status = ResourcePackService.status(ResourcePacks.HANDWRITING);
        if (status != null && status.running()) {
            group.value("进度", null, ResourcePackService.describe(status));
            return;
        }
        if (status != null && status.phase() == ResourcePackService.Phase.FAILED) {
            group.value("上次下载", null, ResourcePackService.describe(status));
        }
        group.button("手写模型", ResourcePackService.size(model.size()), "下载",
            () -> ResourcePackService.request(requireContext(), ResourcePacks.HANDWRITING, model.size()));
        if (model.importFiles().isEmpty()) return;
        group.nav("从文件导入手写模型", null, null, () -> explainImport(model.importFiles()))
            .help("网络不通时，用自己下载好的模型文件安装；按内容认文件，改了文件名也没关系。");
        group.help("手写键盘第一次使用时自动下载这个模型；不装也不影响拼音输入和语音。");
    }

    /** 说明要自己下载哪些文件，再打开文件选择器；「下载地址」用浏览器打开上游地址。 */
    private void explainImport(List<ResourcePacks.ImportFile> files) {
        List<String> lines = new ArrayList<>(files.size());
        long[] sizes = new long[files.size()];
        for (int index = 0; index < files.size(); index++) {
            ResourcePacks.ImportFile file = files.get(index);
            lines.add(OfflineImportPolicy.fileLine(file.name(), file.size()));
            sizes[index] = file.size();
        }
        ScreenCaptureProtection.show(new MaterialAlertDialogBuilder(requireContext())
            .setTitle("导入手写模型")
            .setMessage(OfflineImportPolicy.instructions(lines))
            .setNegativeButton("取消", null)
            .setNeutralButton("下载地址", (dialog, which) -> openDownloads(files))
            .setPositiveButton("选择文件", (dialog, which) -> {
                pendingImport = new ImportSizes(sizes);
                try {
                    openDocuments.launch(new String[] {"*/*"});
                } catch (ActivityNotFoundException error) {
                    pendingImport = null;
                    MsToast.show(requireContext(), "这台设备没有可用的文件选择器");
                }
            })
            .create());
    }

    private void openDownloads(List<ResourcePacks.ImportFile> files) {
        if (files.size() == 1) {
            openUrl(files.get(0).url());
            return;
        }
        OptionSheet sheet = new OptionSheet(requireContext(), "下载地址", null);
        for (ResourcePacks.ImportFile file : files) sheet.option(file.name(), false, () -> openUrl(file.url()));
        sheet.show();
    }

    private void openUrl(String url) {
        if (url.isEmpty()) return;
        if (!LinkActions.open(requireContext(), url)) {
            MsToast.show(requireContext(), "没有可以打开链接的应用");
        }
    }

    /** 文件选择器的结果：交给下载服务复制并安装；用户关掉选择器时什么也不做。 */
    private void onImportPicked(@Nullable List<Uri> uris) {
        ImportSizes target = pendingImport;
        pendingImport = null;
        if (target == null || uris == null || uris.isEmpty() || !isAdded()) return;
        ResourcePackService.requestImport(requireContext(), ResourcePacks.HANDWRITING, null, uris, target.sizes());
        reload();
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
