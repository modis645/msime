package app.msime.android;

import android.content.Context;
import android.content.SharedPreferences;
import app.msime.android.home.HostStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 「沙鸥拼音」的一次性收尾迁移：把两个联网能力和剪贴板历史的旧值改成本端出厂值，之后不再重复。
 *
 * <p>这几项在官方版里是开着的（`candidate_translations` 在旧文档缺字段时按开启读、云剪贴板中继和网盘由用户填过地址），而这版已经删掉了翻译服务页和云剪贴板页，用户再没有入口去关它们。所以第一次跑到时做三件事：
 * <ul>
 *   <li>文档里没有 `candidate_translations` 就写 `false`——旧文档按「会往外发文字」的解释不适用于这个精简版；用户显式保存过的值原样留着；</li>
 *   <li>`cloud_clipboard_relay`（自建中继地址+令牌）和 `cloud_clipboard_webdav`（网盘地址+应用密码）清成空对象，等于不再走这两条路；</li>
 *   <li>`clipboard_history_limit` 小于 100 时抬到 100，与 {@link app.msime.android.clipboard.ClipboardHistoryPolicy#DEFAULT_LIMIT} 一致。</li>
 * </ul>
 *
 * <p>第二批（`pinyin_defaults_migrated_v2`）把三项触屏默认改成沙鸥拼音的产品默认，因为它们来自共享层的出厂文档，装在别人手机上时已经是旧值：
 * <ul>
 *   <li>`touch_spelling_position` 不是 `candidate_bar` 就改过来——拼音只画在候选栏上，不写进输入框；</li>
 *   <li>`touch_voice_shortcut` 关着就打开——工具栏的语音入口默认选中；</li>
 *   <li>`touch_toolbar` 里的 `clipboard` 关着就打开——工具栏的剪贴板入口默认选中。</li>
 * </ul>
 * 这三项都是有意的产品默认，不看用户是不是自己关过：这一版就是要装上就有这两个入口、拼音不进输入框。
 *
 * <p>写回走 {@link HostStore#savePreferences}，和设置页一样带修订号的比较交换，被别的进程抢先改过就重读重算；成功后在 `msime_home_v1` 里记下标记，之后直接返回。读不到共享偏好（首次准备还没完成）时什么也不写，下次再试。两个进程各有一份 SharedPreferences 缓存，标记可能各写一次，但迁移本身按文档内容判断，重复跑不会改动已经干净的值。
 */
public final class PinyinDefaultsMigration {
    private static final String STORE = "msime_home_v1";
    private static final String KEY_DONE = "pinyin_defaults_migrated";
    /** 第二批（触屏默认）的标记；第一批已经跑过的机器也要再跑一次这一批。 */
    private static final String KEY_DONE_V2 = "pinyin_defaults_migrated_v2";
    private static final String SPELLING_POSITION = "touch_spelling_position";
    private static final String SPELLING_CANDIDATE_BAR = "candidate_bar";
    private static final String VOICE_SHORTCUT = "touch_voice_shortcut";
    private static final String TOOLBAR = "touch_toolbar";
    private static final String TOOLBAR_CLIPBOARD = "clipboard";
    private static final String TRANSLATIONS = "candidate_translations";
    private static final String RELAY = "cloud_clipboard_relay";
    private static final String WEBDAV = "cloud_clipboard_webdav";
    private static final String HISTORY_LIMIT = "clipboard_history_limit";
    private static final int HISTORY_LIMIT_MIN = 100;
    /** 修订号一直被抢（别的进程在改设置）时重读重算的次数。 */
    private static final int ATTEMPTS = 3;

    private PinyinDefaultsMigration() {}

    /** 已经迁过就不再动共享偏好；在工作线程上调用。 */
    public static void migrate(Context context) {
        SharedPreferences store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        boolean first = store.getBoolean(KEY_DONE, false);
        boolean second = store.getBoolean(KEY_DONE_V2, false);
        if (first && second) return;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            JSONObject snapshot = HostStore.loadPreferences(context);
            if (snapshot == null) return;
            JSONObject preferences = snapshot.optJSONObject("preferences");
            if (preferences == null) return;
            boolean changed = false;
            if (!first) changed |= edit(preferences);
            if (!second) changed |= editTouchDefaults(preferences);
            if (!changed) {
                mark(store, !first, !second);
                return;
            }
            if (HostStore.savePreferences(context, snapshot) != null) {
                mark(store, !first, !second);
                return;
            }
        }
    }

    private static void mark(SharedPreferences store, boolean first, boolean second) {
        SharedPreferences.Editor editor = store.edit();
        if (first) editor.putBoolean(KEY_DONE, true);
        if (second) editor.putBoolean(KEY_DONE_V2, true);
        editor.apply();
    }

    /** 改这份偏好，返回是否真的有改动。 */
    private static boolean edit(JSONObject preferences) {
        boolean changed = false;
        try {
            if (!preferences.has(TRANSLATIONS)) {
                preferences.put(TRANSLATIONS, false);
                changed = true;
            }
            if (configured(preferences.optJSONObject(RELAY))) {
                preferences.put(RELAY, new JSONObject());
                changed = true;
            }
            if (configured(preferences.optJSONObject(WEBDAV))) {
                preferences.put(WEBDAV, new JSONObject());
                changed = true;
            }
            if (preferences.has(HISTORY_LIMIT) && preferences.optInt(HISTORY_LIMIT, 0) < HISTORY_LIMIT_MIN) {
                preferences.put(HISTORY_LIMIT, HISTORY_LIMIT_MIN);
                changed = true;
            }
        } catch (JSONException malformed) {
            // 文档结构不对时不改：宁可留着旧值，也不写坏整份偏好。
            return false;
        }
        return changed;
    }

    /** 第二批：三项触屏默认改成沙鸥拼音的产品默认，返回是否真的有改动。 */
    private static boolean editTouchDefaults(JSONObject preferences) {
        boolean changed = false;
        try {
            if (!SPELLING_CANDIDATE_BAR.equals(preferences.optString(SPELLING_POSITION, ""))) {
                preferences.put(SPELLING_POSITION, SPELLING_CANDIDATE_BAR);
                changed = true;
            }
            if (!preferences.optBoolean(VOICE_SHORTCUT, true)) {
                preferences.put(VOICE_SHORTCUT, true);
                changed = true;
            }
            JSONObject toolbar = preferences.optJSONObject(TOOLBAR);
            if (toolbar == null) toolbar = new JSONObject();
            if (!toolbar.optBoolean(TOOLBAR_CLIPBOARD, true)) {
                toolbar.put(TOOLBAR_CLIPBOARD, true);
                preferences.put(TOOLBAR, toolbar);
                changed = true;
            }
        } catch (JSONException malformed) {
            // 同上：结构不对就一个字节也不动。
            return false;
        }
        return changed;
    }

    /** 这类对象里只要有非空字符串，就是用户配过地址或令牌。 */
    private static boolean configured(JSONObject object) {
        if (object == null) return false;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            Object value = object.opt(keys.next());
            if (value instanceof String text && !text.isEmpty()) return true;
        }
        return false;
    }
}
