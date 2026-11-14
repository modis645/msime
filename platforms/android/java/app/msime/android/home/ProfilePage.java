package app.msime.android.home;

import app.msime.android.DrawablePolicy;
import app.msime.android.LayoutPolicy;
import app.msime.android.TextPolicy;
import app.msime.android.ThemeColorPolicy;
import app.msime.android.ViewPolicy;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import app.msime.android.AccountStore;
import app.msime.android.BackendAccount;
import app.msime.android.BitmapPolicy;
import app.msime.android.DeviceDataApi;
import app.msime.android.SyncSwitch;

/**
 * 「我的」页资料卡用到的三件事：登录后把账号编号补绑到同步开关、读账号头像、画头像。
 *
 * <p>原「个人资料」页已随精简删除（沙鸥拼音只保留拼音输入与本地功能），这里只留下账号页仍然需要的那部分静态工具，页面本身不再注册进 {@link PageId}。
 */
final class ProfilePage {
    private static final int AVATAR_DECODE_EDGE = 384;

    private ProfilePage() {}

    /** 登录时没读到用户 id 的话，用资料页读到的账号编号补上同步要用的绑定；代次与账号不同时先清空再绑。 */
    static void bindIfNeeded(Context context, DeviceDataApi.Profile profile, String expectedSessionId) {
        synchronized (SyncSwitch.bindingLock()) {
            if (expectedSessionId == null || expectedSessionId.isEmpty()
                    || !expectedSessionId.equals(new BackendAccount(context).sessionId())) return;
            String currentKind = SyncSwitch.loginKind(context);
            String kind = SyncSwitch.validLoginKind(currentKind) ? currentKind : profile.loginKind();
            if (kind.isEmpty()) return;
            String previousAccount = SyncSwitch.accountId(context);
            if (profile.id().equals(previousAccount) && kind.equals(currentKind)) return;
            if (!previousAccount.equals(profile.id())) {
                SignIn.cancelPendingSnapshot(context, previousAccount);
            }
            SyncSwitch.bindAccount(context, profile.id(), kind);
        }
    }

    /** 读头像图片：由 Rust 下载（只认账号头像服务的地址、不超过 1 MiB）；没有或读不到时为 null，界面显示首字头像。阻塞。 */
    @Nullable static Bitmap avatar(DeviceDataApi api, DeviceDataApi.Profile profile) {
        if (!profile.hasAvatar()) return null;
        try {
            byte[] bytes = api.avatar();
            return bytes == null ? null : decodeAvatar(bytes);
        } catch (AccountStore.Failure | RuntimeException unavailable) {
            return null;
        }
    }

    /** 按头像实际显示的大小降采样解码：只限字节数挡不住高度可压缩的大尺寸图片，全尺寸解码会 OutOfMemoryError。 */
    @Nullable private static Bitmap decodeAvatar(byte[] bytes) {
        BitmapFactory.Options bounds = BitmapPolicy.decodeBounds(bytes);
        if (bounds == null) return null;
        int sample = BitmapPolicy.sampleSizeForEdge(
            bounds.outWidth, bounds.outHeight, AVATAR_DECODE_EDGE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    /** 圆形头像：有图片画图片，没有画名字首字；尺寸按 dp 给定。 */
    static FrameLayout avatarView(Context context, int sizeDp, String name, @Nullable Bitmap image) {
        FrameLayout frame = new FrameLayout(context);
        GradientDrawable circle = DrawablePolicy.circle(ThemeColorPolicy.accent(context));
        if (image != null) {
            ImageView picture = new ImageView(context);
            picture.setImageBitmap(image);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ViewPolicy.setBackground(picture, circle);
            picture.setClipToOutline(true);
            ViewPolicy.hideFromAccessibility(picture);
            frame.addView(picture, LayoutPolicy.squareFrameParams(context, sizeDp));
        } else {
            TextView letter = ViewPolicy.centeredLabel(context, TextPolicy.trimmedInitial(name, "?"),
                Math.round(sizeDp * 0.4f), 600, ThemeColorPolicy.onAccent(context));
            ViewPolicy.setBackground(letter, circle);
            ViewPolicy.hideFromAccessibility(letter);
            frame.addView(letter, LayoutPolicy.squareFrameParams(context, sizeDp));
        }
        return frame;
    }
}
