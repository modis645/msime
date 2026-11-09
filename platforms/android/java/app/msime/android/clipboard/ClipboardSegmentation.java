package app.msime.android;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剪贴板「分词」：把一条历史切成可以逐个点选的词片，再把点选的词片按原来的顺序拼回去（#5645）。
 *
 * <p>切词用平台的 `java.text.BreakIterator` 词边界。Android 上它由 ICU 实现，中文按 ICU 自带的中日文词典切词；这里不实现任何组词或词库逻辑，那些只属于 Engine。ICU 偶尔会把一长串汉字当成一个词（词典里没有的专名、JDK 的实现则总是整串不切），为了让用户仍能「掐头去尾」，超过 {@link #MAX_IDEOGRAPH_RUN} 个字的纯汉字片段再拆成单字。
 *
 * <p>空白（空格、换行、制表符）是分隔，不作为词片显示；拼回时只有夹在两个相邻的已选词片之间的空白才保留，所以选中连续的英文单词得到的是带空格的原文，掐掉开头或结尾也不会留下多余的空白。
 */
public final class ClipboardSegmentation {
    /** 只对开头这么多个 UTF-16 单元分词：再长的文字排成几千个词片，面板既画不动也没法点选。 */
    public static final int MAX_CHARS = 2_000;
    /** 纯汉字片段超过这么多个字就拆成单字。 */
    public static final int MAX_IDEOGRAPH_RUN = 6;
    /**
     * 网址：整条当一个词片。`BreakIterator` 会把 `https://a.example/b?c=d` 按 `:`、`.`、`/` 切碎，
     * 那样「保留完整网址」就做不到，所以先按这条正则把它整段认出来；只认 ASCII 部分，
     * 碰到空格、中文或标点就在那里断开。
     */
    private static final Pattern URL =
        Pattern.compile("(?:https?://|www\\.)[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    /** 一个片段：可以点选的词片，或者两个词片之间的空白。 */
    public static final class Segment {
        private final String text;
        private final boolean separator;

        Segment(String text, boolean separator) {
            this.text = text;
            this.separator = separator;
        }

        public String text() { return text; }

        /** 是否是空白分隔：不显示、不可点选。 */
        public boolean separator() { return separator; }
    }

    private ClipboardSegmentation() {}

    /** 这段文字是否超过了分词的上限，只有开头的部分会被切开。 */
    public static boolean truncated(String text) {
        return text != null && text.length() > MAX_CHARS;
    }

    /**
     * 把文字切成片段，按原文顺序；所有片段首尾相接就是（截断后的）原文。
     *
     * @param words 一个词边界迭代器，调用方用 `BreakIterator.getWordInstance(locale)` 取得
     */
    public static List<Segment> segment(String text, BreakIterator words) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        if (words == null) throw new IllegalArgumentException("No word iterator");
        String source = text;
        if (truncated(source)) {
            source = TextPolicy.clipSurrogateSafe(source, MAX_CHARS);
        }
        List<Segment> segments = new ArrayList<>();
        Matcher url = URL.matcher(source);
        int cursor = 0;
        while (cursor < source.length()) {
            int from = url.find(cursor) ? url.start() : source.length();
            int to = from < source.length() ? url.end() : source.length();
            if (from > cursor) appendWords(segments, source.substring(cursor, from), words);
            // 网址整条一个片段：点一下整条选中，拼回去也是原来的样子。
            if (to > from) segments.add(new Segment(source.substring(from, to), false));
            cursor = to;
        }
        return segments;
    }

    /** 普通文字按词边界切；空白合成分隔片段，过长的纯汉字片段拆成单字。 */
    private static void appendWords(List<Segment> segments, String text, BreakIterator words) {
        if (text.isEmpty()) return;
        words.setText(text);
        int start = words.first();
        for (int end = words.next(); end != BreakIterator.DONE; start = end, end = words.next()) {
            String piece = text.substring(start, end);
            if (TextPolicy.blank(piece)) {
                appendSeparator(segments, piece);
            } else if (isIdeographRun(piece) && TextPolicy.codePointLength(piece) > MAX_IDEOGRAPH_RUN) {
                for (int offset = 0; offset < piece.length(); ) {
                    int next = piece.offsetByCodePoints(offset, 1);
                    segments.add(new Segment(piece.substring(offset, next), false));
                    offset = next;
                }
            } else {
                segments.add(new Segment(piece, false));
            }
        }
    }

    /** 可点选的词片数。 */
    public static int selectableCount(List<Segment> segments) {
        int count = 0;
        for (Segment segment : segments) if (!segment.separator()) count++;
        return count;
    }

    /**
     * 把选中的词片按原文顺序拼起来；`selected` 与 `segments` 一一对应，分隔片段上的值不看。
     *
     * <p>两个已选词片之间只有空白时，那段空白一起保留；中间隔着未选的词片时，空白一并丢掉。
     */
    public static String join(List<Segment> segments, boolean[] selected) {
        if (segments == null || selected == null || selected.length != segments.size()) {
            throw new IllegalArgumentException("Selection does not match the segments");
        }
        StringBuilder out = new StringBuilder();
        StringBuilder gap = new StringBuilder();
        boolean previousSelected = false;
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment.separator()) {
                if (previousSelected) gap.append(segment.text());
                continue;
            }
            if (selected[index]) {
                if (previousSelected) out.append(gap);
                out.append(segment.text());
                previousSelected = true;
            } else {
                previousSelected = false;
            }
            gap.setLength(0);
        }
        return out.toString();
    }

    private static void appendSeparator(List<Segment> segments, String piece) {
        int last = segments.size() - 1;
        if (last >= 0 && segments.get(last).separator()) {
            segments.set(last, new Segment(segments.get(last).text() + piece, true));
        } else {
            segments.add(new Segment(piece, true));
        }
    }

    private static boolean isIdeographRun(String piece) {
        for (int offset = 0; offset < piece.length(); ) {
            int codePoint = piece.codePointAt(offset);
            if (!Character.isIdeographic(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
