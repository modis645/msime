package app.msime.android;

import org.json.JSONObject;

/** Android 键盘在一种明暗下的皮肤选择；候选条、表情和手写面板按自己的明暗读同一份选择（#10749）。 */
public final class KeyboardSkinModeChoice {
    public record Selected(String theme, JSONObject design) {}

    private KeyboardSkinModeChoice() {}

    public static String key(boolean dark) {
        return dark ? "android_keyboard_skin_dark" : "android_keyboard_skin_light";
    }

    public static String theme(String id) { return "theme:" + id; }

    /** 缺槽时沿用旧主题；固定明暗不合时由调用方按现有规则回退系统。 */
    public static Selected selected(JSONObject preferences, boolean dark) {
        String value = preferences == null ? "" : preferences.optString(key(dark), "");
        if (value.startsWith("theme:")) {
            String id = value.substring("theme:".length());
            if ("system".equals(id) || "shuishan".equals(id) || "light".equals(id)
                    || "paper".equals(id) || "night".equals(id) || "ink".equals(id)
                    || "custom".equals(id)) {
                JSONObject custom = preferences.optJSONObject("custom_theme");
                return new Selected(id, custom == null ? null : custom.optJSONObject("keyboard"));
            }
        }
        String legacy = preferences == null ? "system" : preferences.optString("global_theme", "system");
        JSONObject custom = preferences == null ? null : preferences.optJSONObject("custom_theme");
        return new Selected(legacy, custom == null ? null : custom.optJSONObject("keyboard"));
    }
}
