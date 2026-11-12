package app.msime.android;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.view.View;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 按键反馈（P23）：按键音、振动与按键动画。
 *
 * <p>按键区（{@link KeyboardKeyArea}）里的键在手指按下时就出声、振动（{@link #pressed}），抬手上屏时的点击反馈只计数、播动画，不再出声振动（#10757，规则见 {@link KeyPressFeedbackPolicy}）。候选栏、工具栏和面板不在按键区里，仍在点击时反馈。
 *
 * <p>按键音开关仍是 Android 本地的 `KeyboardFeedbackStore`；声音只用系统按键音（`AudioManager.FX_KEYPRESS_STANDARD`），不再播放音效包。应用有独立按键音开关和音量，使用 `playSoundEffect` 而不是触摸音效开关，避免受系统设置限制。按键动画按 `touch_key_animation` 由 {@link KeyPressAnimator} 播放，`none` 保持原来的按压态。
 */
final class ImeKeyFeedback implements KeyboardKeyArea.PressListener {
    /** 按键类别：普通字符键。 */
    static final int KEY_STANDARD = 0;
    /** 按键类别：空格。 */
    static final int KEY_SPACE = 1;
    /** 按键类别：回车。 */
    static final int KEY_ENTER = 2;
    /** 按键类别：退格。 */
    static final int KEY_BACKSPACE = 3;

    /** 系统按键音的音量，0–1。 */
    private static final float VOLUME = 1f;

    private final MSIMEInputService s;
    private KeyPressAnimator.Style animation = KeyPressAnimator.Style.NONE;
    /** 原按钮 → 键面替身，见 {@link #stageFace}。 */
    private final Map<View, WeakReference<View>> faces = new WeakHashMap<>();
    /** 按下时已经出声振动过的键（按用户看得见的键面记），在它的点击到来之前武装着。 */
    private final KeyPressFeedbackPolicy<View> pressGate = new KeyPressFeedbackPolicy<>();

    ImeKeyFeedback(MSIMEInputService s) {
        this.s = s;
    }

    /**
     * 按键区里一根手指按下命中 `key`：这时就出声、振动，并武装这个键，它在按下分发里或抬手时的点击不再出声振动。调试计数和按键动画仍留给点击，每次按键只算一次。这个键此刻点了也不做事时（{@link MSIMEInputService#pressGivesFeedback}）什么也不给，返回 null。
     */
    @Override public Object pressed(View key) {
        View face = visible(key);
        if (!s.pressGivesFeedback(face)) return null;
        refreshPreferences();
        if (s.soundEnabled) playSound();
        if (s.hapticsEnabled) KeyboardHaptics.play(s.vibrator, face, s.hapticStrength);
        return pressGate.arm(face);
    }

    /** 按下时反馈过的手指抬起：抬起分发和随后投递的点击里，这个键的点击不再出声振动。 */
    @Override public Object releasing(View key) {
        return pressGate.arm(visible(key));
    }

    @Override public void settled(Object token) {
        pressGate.settle(token);
    }

    /** 按键和点击的反馈入口：这个键刚在按下时反馈过（见 {@link #pressed}）就只计数、播动画，不再出声振动。 */
    void playFeedback(View source) {
        View key = visible(source);
        feedback(key, classify(key), !pressGate.consume(key));
    }

    /** 抬手那一刻的确认性反馈（上滑清空松手执行、松手才补判出的长按语音）：不是这次按键的上屏，按下时的反馈不顶替它。 */
    void confirm(View source) {
        View key = visible(source);
        feedback(key, classify(key), true);
    }

    /**
     * 登记一个画图标的键面替身：⇧ ⌫ 空格 ↵ 的键面点一下会让 SVC 建的原按钮 performClick，原按钮的监听再拿它自己来播反馈；原按钮不在键盘树里，按它分类和播动画都会落空。两边都用弱引用，键盘重建后旧的一对随视图一起回收。
     */
    void stageFace(View original, View face) {
        if (original != null && face != null) faces.put(original, new WeakReference<>(face));
    }

    /** 原按钮换成用户看得见的键面；没有登记过替身的键原样返回。 */
    View visible(View source) {
        WeakReference<View> face = source == null ? null : faces.get(source);
        View resolved = face == null ? null : face.get();
        return resolved != null ? resolved : source;
    }

    /** 由按键视图推断按键类别：空格键、回车键、删除键各自一类，其他都算普通键。优先看建键时登记的键位 id（{@link MSIMEInputService#keyId}），九宫格、日语和笔画的 ⌫ 描述是「按键 删除」，按描述猜会漏。 */
    int classify(View source) {
        if (source == null) return KEY_STANDARD;
        View key = visible(source);
        if (key == s.spaceButton) return KEY_SPACE;
        if (key == s.enterButton) return KEY_ENTER;
        if (key == s.deleteButton || key == s.backspaceRepeatButton) return KEY_BACKSPACE;
        String id = s.keyIdOf(key);
        if (id == null) id = s.keyIdOf(source);
        if ("Backspace".equals(id)) return KEY_BACKSPACE;
        if ("Space".equals(id)) return KEY_SPACE;
        if ("Enter".equals(id)) return KEY_ENTER;
        if (key instanceof KeyboardPressButton press && press.keyboardRole() == KeyboardKeyRole.RETURN)
            return KEY_ENTER;
        CharSequence description = key.getContentDescription();
        if (description != null) {
            String value = description.toString();
            if ("删除".equals(value) || "按键 删除".equals(value)) return KEY_BACKSPACE;
            if ("空格".equals(value)) return KEY_SPACE;
        }
        return KEY_STANDARD;
    }

    private void refreshPreferences() {
        animation = KeyPressAnimator.Style.fromPreference(
            s.localSettings.choice(AndroidLocalSettings.KEY_ANIMATION));
    }

    /** 键盘开始显示时调用；按键音不再预加载任何包，保留调用点。 */
    void prepare() {
        refreshPreferences();
    }

    private void feedback(View source, int keyClass, boolean audible) {
        refreshPreferences();
        if (inside(source, s.candidateViewport) || inside(source, s.expandedCandidates)) {
            s.imeDebugOverlay.recordEvent(DiagnosticEventKind.CANDIDATE_SELECTED);
        } else if (inside(source, s.keyRows) || inside(source, s.actionRow)) {
            s.imeDebugOverlay.onKeyPressed(keyClass == KEY_BACKSPACE);
            if (animation != KeyPressAnimator.Style.NONE) {
                KeyPressAnimator.play(source, s.imeLetterRows.keyPreviewLayer, animation,
                    Color.parseColor(s.skin.accent()));
            }
        }
        if (!audible) return;
        if (s.soundEnabled) playSound();
        if (!s.hapticsEnabled) return;
        KeyboardHaptics.play(s.vibrator, source, s.hapticStrength);
    }

    /** 系统按键音：应用有自己的按键音开关和音量，走 {@link AudioManager#playSoundEffect}。 */
    private void playSound() {
        AudioManager audio = (AudioManager) s.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) audio.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, VOLUME);
    }

    private static boolean inside(View view, View ancestor) {
        if (view == null || ancestor == null) return false;
        if (view == ancestor) return true;
        ViewParent parent = view.getParent();
        while (parent instanceof View node) {
            if (node == ancestor) return true;
            parent = node.getParent();
        }
        return false;
    }

    /** 服务销毁时调用；按键音不再持有任何播放资源，保留调用点。 */
    void shutdown() {
    }
}
