import app.msime.android.CommonPhrasesStore;
import app.msime.android.JsonPolicy;
import java.util.List;

public final class CommonPhrasesStoreSmoke {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }

    public static void main(String[] args) {
        check(Boolean.TRUE.equals(JsonPolicy.strictBoolean(Boolean.TRUE)));
        check(JsonPolicy.strictBoolean("true") == null);
        check(JsonPolicy.strictString("synthetic") != null);
        check(JsonPolicy.strictString(7) == null);
        check(JsonPolicy.strictInteger(Integer.valueOf(7)) == 7);
        check(JsonPolicy.strictInteger("7") == null);
        check(JsonPolicy.strictInteger(Double.valueOf(7)) == null);
        check(CommonPhrasesStore.nonNegativeInteger(Integer.valueOf(7)) == 7);
        check(CommonPhrasesStore.nonNegativeInteger(Integer.valueOf(-1)) == null);
        check(CommonPhrasesStore.nonNegativeInteger(Double.valueOf(7)) == null);
        check(CommonPhrasesStore.validText("好的，收到"));
        check(CommonPhrasesStore.validText("第一行\n第二行"));
        check(!CommonPhrasesStore.validText(null));
        check(!CommonPhrasesStore.validText(""));
        check(!CommonPhrasesStore.validText("  \n "));
        check(!CommonPhrasesStore.validText("制表\t符"));
        check(!CommonPhrasesStore.validText("回车\r换行"));
        check(CommonPhrasesStore.validText("长".repeat(CommonPhrasesStore.MAX_PHRASE_UNITS)));
        check(!CommonPhrasesStore.validText("长".repeat(CommonPhrasesStore.MAX_PHRASE_UNITS + 1)));

        check(CommonPhrasesStore.failureMessage("common_phrases_limit").contains("200"));
        check(CommonPhrasesStore.failureMessage("common_phrases_duplicate").contains("已经有"));
        check(CommonPhrasesStore.failureMessage(null).equals(CommonPhrasesStore.failureMessage("whatever")));
        check(!CommonPhrasesStore.failureMessage("common_phrases_io").contains("common_phrases"));

        CommonPhrasesStore.Document document = new CommonPhrasesStore.Document(List.of(
            new CommonPhrasesStore.Phrase("a", "马上到", ""),
            new CommonPhrasesStore.Phrase("b", "此致敬礼", "pack")), 0);
        check(document.ownCount() == 1);
        check(document.phrases().get(0).own() && !document.phrases().get(1).own());
        check(CommonPhrasesStore.Document.EMPTY.ownCount() == 0);
        check(!new CommonPhrasesStore.Result(null, "x").ok());
        check(new CommonPhrasesStore.Result(document, "").ok());
    }
}
