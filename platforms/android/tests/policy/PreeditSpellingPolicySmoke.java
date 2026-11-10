import app.msime.android.PreeditSpellingPolicy;

public final class PreeditSpellingPolicySmoke {
    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    public static void main(String[] args) {
        // 全拼的 reading 为空：这时要用引擎分好音节的 preedit，读音行才带分隔（nihao -> ni'hao）。
        check(PreeditSpellingPolicy.spelling("", "ni'hao", "nihao").equals("ni'hao"),
            "an empty reading falls back to the engine's segmented preedit");
        // 日语、注音这类方案给的 reading 优先，任何情况下都不换。
        check(PreeditSpellingPolicy.spelling("んあ", "n'a", "na").equals("んあ"),
            "a drawn reading always wins");
        check(PreeditSpellingPolicy.spelling("niha", "ni'ha", "nihao").equals("niha"),
            "a non-empty reading is never replaced by the preedit");
        // preedit 与按键对不上（辅助码、本地模式、编辑器改过的写法）时照旧画按键。
        check(PreeditSpellingPolicy.spelling("", "ni'hao", "nihh").equals("nihh"),
            "a preedit that does not spell the keys stays out of the row");
        check(PreeditSpellingPolicy.spelling("", "www.", "www.").equals("www."),
            "a local mode that keeps the same letters is not treated as a spelling");
        // 大小写不同不算对不上：引擎按用户打的 Shift 写法重放 preedit。
        check(PreeditSpellingPolicy.spelling("", "Ni'Hao", "NiHao").equals("Ni'Hao"),
            "case alone does not count as a mismatch");
        // 26 键自己打了分隔符时，两边都跳过。
        check(PreeditSpellingPolicy.spelling("", "ni'hao", "ni'hao").equals("ni'hao"),
            "a separator the user typed survives on both sides");
        // 空的按键不画 preedit：否则读音行会替还没开始的组字画一串拼写。
        check(PreeditSpellingPolicy.spelling("", "ni'hao", "").isEmpty(),
            "an empty editing text draws nothing");

        check(PreeditSpellingPolicy.sameLetters("xian", "xian"), "the same letters match");
        check(PreeditSpellingPolicy.sameLetters("xi'an", "xian"), "separators are skipped on the shown side");
        check(PreeditSpellingPolicy.sameLetters("xian", "xi'an"), "separators are skipped on the typed side");
        check(!PreeditSpellingPolicy.sameLetters("xi'an", "xiam"), "a different letter does not match");
        check(!PreeditSpellingPolicy.sameLetters("xian", "xia"), "a longer preedit is not the same spelling");
        check(!PreeditSpellingPolicy.sameLetters("xia", "xian"), "a shorter preedit is not the same spelling");
        check(!PreeditSpellingPolicy.sameLetters("'", "xian"), "separators alone spell nothing");
        System.out.println("Android reading row: the segmented preedit reaches the row for quanpin passed");
    }
}
