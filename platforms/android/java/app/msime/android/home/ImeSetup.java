package app.msime.android.home;

import android.content.Context;
import android.content.ComponentName;
import androidx.annotation.Nullable;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodManager;

/**
 * The two setup facts the host reports about its keyboard, and the two ways to fix them.
 *
 * <p>Shared by the 设置 tab's status card and onboarding's first step, so both read the system the same way. Enabled and default are separate facts: a keyboard can be enabled and never used because another one stays the default.
 */
final class ImeSetup {
    private ImeSetup() {}

    /** 是否在系统已启用输入法列表中。 */
    static boolean enabled(Context context) {
        return app.msime.android.DeviceInfoCollector.keyboardEnabled(context);
    }

    /** 是否为系统当前默认输入法。 */
    static boolean isDefault(Context context) {
        return app.msime.android.DeviceInfoCollector.keyboardDefault(context);
    }

    /** 系统服务不可用或查询被拒时保留未知，不能当成尚未启用。 */
    @Nullable static Boolean enabledState(Context context) {
        try {
            InputMethodManager manager = context.getSystemService(InputMethodManager.class);
            return manager == null ? null : enabled(context);
        } catch (RuntimeException unavailable) { return null; }
    }

    /** 默认输入法标识尚不可读时保留未知。 */
    @Nullable static Boolean currentState(Context context) {
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName component = id == null ? null : ComponentName.unflattenFromString(id);
            return component == null ? null : context.getPackageName().equals(component.getPackageName())
                && "app.msime.android.MSIMEInputService".equals(component.getClassName());
        } catch (RuntimeException unavailable) { return null; }
    }

    /** The system's on-screen keyboard list, where the user turns this keyboard on. */
    static void openSettings(Context context) {
        context.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
    }

    /** Opens the keyboard settings and reports whether the system accepted the intent. */
    static boolean tryOpenSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * 已启用时静默切到本输入法：不需要弹窗，也不打扰用户。没启用、或系统不同意切时返回 false，
     * 由调用方退回 {@link #openSettings} / {@link #makeDefault}。
     */
    static boolean switchTo(Context context) {
        if (!Boolean.TRUE.equals(enabledState(context))) return false;
        try {
            InputMethodManager manager = context.getSystemService(InputMethodManager.class);
            if (manager == null) return false;
            return manager.setInputMethod(context.getPackageName() + "/app.msime.android.MSIMEInputService");
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * 引导页那一个按钮：按当前状态决定做什么。
     *
     * <p>还没启用就跳系统的输入法列表（申请勾选只能在那里做）；已启用但不是当前输入法就直接切过去；
     * 已经是当前输入法、静默切换被系统拒了、或读者分不清状态时，弹系统的输入法切换器当兜底。
     *
     * @return 是否还需要用户动手（已经是当前输入法时返回 false）
     */
    static boolean enableAndSwitch(Context context) {
        Boolean enabled = enabledState(context);
        Boolean current = currentState(context);
        if (Boolean.TRUE.equals(current)) return false;
        if (!Boolean.TRUE.equals(enabled)) {
            if (!tryOpenSettings(context)) makeDefault(context);
            return true;
        }
        if (switchTo(context)) return false;
        makeDefault(context);
        return true;
    }

    /** The system's keyboard picker; only an enabled keyboard is listed there, so before that the settings list is the way. */
    static void makeDefault(Context context) {
        if (!Boolean.TRUE.equals(enabledState(context))) {
            openSettings(context);
            return;
        }
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        if (manager != null) manager.showInputMethodPicker();
    }
}
