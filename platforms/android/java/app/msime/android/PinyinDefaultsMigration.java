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
 * <p>写回走 {@link HostStore#savePreferences}，和设置页一样带修订号的比较交换，被别的进程抢先改过就重读重算；成功后在 `msime_home_v1` 里记下标记，之后直接返回。读不到共享偏好（首次准备还没完成）时什么也不写，下次再试。两个进程各有一份 SharedPreferences 缓存，标记可能各写一次，但迁移本身按文档内容判断，重复跑不会改动已经干净的值。
 */
public final class PinyinDefaultsMigration {
    private static final String STORE = "msime_home_v1";
    private static final String KEY_DONE = "pinyin_defaults_migrated";
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
        if (store.getBoolean(KEY_DONE, false)) return;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            JSONObject snapshot = HostStore.loadPreferences(context);
            if (snapshot == null) return;
            JSONObject preferences = snapshot.optJSONObject("preferences");
            if (preferences == null) return;
            if (!edit(preferences)) {
                store.edit().putBoolean(KEY_DONE, true).apply();
                return;
            }
            if (HostStore.savePreferences(context, snapshot) != null) {
                store.edit().putBoolean(KEY_DONE, true).apply();
                return;
            }
        }
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

    /** 这类对象里只要有非空字符串，就是用户配过地址或令牌。 */
    private static boolean configured(JSONObject object) {
        if (object == null) return false;
        for (String key : object.keySet()) {
            Object value = object.opt(key);
            if (value instanceof String text && !text.isEmpty()) return true;
        }
        return false;
    }
}
