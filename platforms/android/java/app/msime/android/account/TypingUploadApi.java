package app.msime.android;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 「绑定 QQ 群助手」用到的账号接口：`GET` / `POST /v1/users/me/qq-links` 和 `DELETE /v1/users/me/qq-links/{id}`，都带真实账号的令牌（匿名账号不绑定）。错误说法与共享设置页（`qq-link-card.tsx`）一致。
 *
 * <p>本类不 import `androidx`、`R` 或 `home/`，check-host 会编译它；请求阻塞在网络上，不要在主线程调用。
 */
public final class TypingUploadApi {
    public static final String LINKS_PATH = "/v1/users/me/qq-links";
    /** 服务端的绑定码是 8 位数字。 */
    public static final int CODE_DIGITS = 8;
    private static final int MAX_LINKS = 10;
    private static final DateTimeFormatter LINK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 一条绑定：服务端只存群和成员标识的摘要，所以只有编号和绑定时间。 */
    public record Link(String id, String createdAt) {}

    private final CloudApi api;

    public TypingUploadApi(CloudApi api) {
        this.api = api;
    }

    /** 本人的绑定，新的在前。 */
    public List<Link> links() throws CloudApi.Failure {
        JSONObject response = api.json("GET", LINKS_PATH, null, CloudApi.Auth.ACCOUNT);
        JSONArray rows = response.optJSONArray("links");
        if (rows == null) {
            throw new CloudApi.Failure(200, "invalid_response", "malformed qq links", 0);
        }
        List<Link> links = new ArrayList<>();
        for (int i = 0; i < rows.length() && links.size() < MAX_LINKS; i++) {
            Link link = link(rows.optJSONObject(i));
            if (link != null) links.add(link);
        }
        return links;
    }

    /** 用绑定码绑定。`code` 先经 {@link #normalizeCode}，不合规时抛 400 `invalid_qq_link_code`，不联网。 */
    public Link redeem(String code) throws CloudApi.Failure {
        String digits = normalizeCode(code);
        if (digits == null) throw new CloudApi.Failure(400, "invalid_qq_link_code", "bind code must be 8 digits", 0);
        JSONObject body;
        try {
            body = new JSONObject().put("code", digits);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        Link link = link(api.json("POST", LINKS_PATH, body, CloudApi.Auth.ACCOUNT));
        if (link == null) throw new CloudApi.Failure(201, "invalid_response", "malformed qq link", 0);
        return link;
    }

    /** 解除一条绑定。 */
    public void remove(String id) throws CloudApi.Failure {
        if (!validId(id)) throw new IllegalArgumentException("invalid qq link id");
        api.send("DELETE", LINKS_PATH + "/" + id, null, CloudApi.Auth.ACCOUNT);
    }

    private static Link link(JSONObject row) {
        if (row == null) return null;
        Object id = row.opt("id");
        Object createdAt = row.opt("created_at");
        if (!(id instanceof String text) || !validId(text) || !(createdAt instanceof String time) || time.length() > 64) return null;
        return new Link(text, time);
    }

    /** 服务端的编号是不以 0 开头的十进制整数。 */
    static boolean validId(String id) {
        if (id == null || id.isEmpty() || id.length() > 19 || id.charAt(0) == '0') return false;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
        }
        return true;
    }

    /** 去掉空白和连字符后正好 8 位 ASCII 数字时返回这 8 位，否则 null。和服务端、共享设置页的规则相同。 */
    public static String normalizeCode(String code) {
        if (code == null) return null;
        StringBuilder digits = new StringBuilder(CODE_DIGITS);
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (Character.isWhitespace(c) || c == '-') continue;
            if (c < '0' || c > '9' || digits.length() == CODE_DIGITS) return null;
            digits.append(c);
        }
        return digits.length() == CODE_DIGITS ? digits.toString() : null;
    }

    /** 绑定时间按本机时区显示成「2026-10-17 12:34」；读不懂时原样返回。 */
    public static String displayTime(String createdAt, ZoneId zone) {
        try {
            return OffsetDateTime.parse(createdAt).atZoneSameInstant(zone).format(LINK_TIME);
        } catch (DateTimeParseException | NullPointerException unreadable) {
            return createdAt == null ? "" : createdAt;
        }
    }

    /** 绑定失败时的说法，与共享设置页的 `qqLinkMessage` 相同。 */
    public static String linkMessage(CloudApi.Failure failure) {
        switch (failure.code) {
            case "invalid_qq_link_code": return "绑定码是 8 位数字，请检查后重试。";
            case "qq_link_code_not_found":
                return "绑定码不对或已过期（10 分钟内有效，只能用一次）。请在 QQ 群里重新发 /bind 拿一个新的。";
            case "qq_link_limit": return "这个账号已经绑定了 10 个 QQ 群友，先解绑一个再试。";
            default: break;
        }
        if (failure.rateLimited()) return "尝试次数太多，请一小时后再试。";
        return generalMessage(failure);
    }

    /** 列表和解绑失败时的说法。 */
    public static String generalMessage(CloudApi.Failure failure) {
        if (failure.signedOut()) return "登录已失效，请重新登录后再试。";
        if (failure.network()) return "连不上服务器，请检查网络后再试。";
        if (failure.unavailable() || failure.status == 404) return "这项服务暂时不可用，请稍后再试。";
        return "没有成功，请稍后再试（HTTP " + failure.status + (failure.code.isEmpty() ? "" : " " + failure.code) + "）。";
    }
}
