package app.msime.android;

/**
 * 读音行画哪一串文字：引擎让宿主画的读音（日语、注音这类方案），或全拼自己打下的按键。
 *
 * <p>全拼的 {@code reading} 是空的（{@link app.msime.android.InputSchemeTraits} 里只有日语罗马字、韩语、注音、笔画让宿主画读音），
 * 改造版因此一直退回 {@code editing_text}，读音行上看到的是没分音节的 {@code nihao}。引擎的 {@code preedit} 是分好音节的
 * （{@code ni'hao}，{@code crates/engine/src/quanpin/scheme.rs} 的 {@code normalized_segmentation}），只要它去掉分隔后与按键逐个
 * 相同，就画它，读音行便带上音节分隔；辅助码、大小写写法、本地模式里那些与按键对不上的 {@code preedit} 一律不画，照旧画按键。
 *
 * <p>{@link CompositionCaretPolicy#alignment} 本来就把只在显示时才有的分隔（{@code '} 和空格）跳过，所以换成带分隔的写法不影响
 * 点按和光标换算。
 */
public final class PreeditSpellingPolicy {
    private PreeditSpellingPolicy() {}

    /**
     * 读音行该画的文字。
     *
     * @param reading 引擎给宿主画的读音（{@code display_reading} 或 {@code reading}）；全拼为空
     * @param preedit 引擎的 {@code preedit}，全拼是分好音节的拼写
     * @param editing 打下的原始按键（{@code editing_text}）
     */
    public static String spelling(String reading, String preedit, String editing) {
        if (reading != null && !reading.isEmpty()) return reading;
        if (preedit == null || preedit.isEmpty() || editing == null || editing.isEmpty()) return editing;
        return sameLetters(preedit, editing) ? preedit : editing;
    }

    /**
     * {@code shown} 去掉只在显示时才有的分隔后，是否与 {@code typed} 逐个字母相同（不分大小写，两边的分隔都跳过）。
     */
    public static boolean sameLetters(String shown, String typed) {
        int i = 0;
        int j = 0;
        while (true) {
            while (i < shown.length() && separator(shown.charAt(i))) i++;
            while (j < typed.length() && separator(typed.charAt(j))) j++;
            if (i >= shown.length() || j >= typed.length()) {
                return i >= shown.length() && j >= typed.length();
            }
            if (Character.toLowerCase(shown.charAt(i)) != Character.toLowerCase(typed.charAt(j))) return false;
            i++;
            j++;
        }
    }

    static boolean separator(char value) {
        return value == '\'' || value == ' ';
    }
}
