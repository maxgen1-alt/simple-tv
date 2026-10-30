package com.maxgendler.simpletv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * eTVnet has no links to its channels, so this presses the buttons for them:
 * opens "Прямой эфир" (Live), scrolls the channel row until it finds the channel by name, and clicks it.
 *
 * Only watches the eTVnet app, and only acts for ~25 seconds after one of our channel buttons is pressed.
 * Also shows the step-by-step game banner over Prime / Peacock / DAZN (see "game guide banner").
 * Must be switched on once: Settings → System → Accessibility → Простой ТВ.
 */
public class AutoTuneService extends AccessibilityService {

    private static final String TAG = "AutoTune";
    private static final String LIVE_TAB = "Прямой эфир";
    private static final long GIVE_UP_MS = 25_000;
    private static final int MAX_SCROLLS = 40;

    private static volatile String pendingName;
    private static volatile long pendingUntil;
    private static volatile int scrolls;
    private static volatile int focusTries;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::step;

    private static volatile String pendingRow = "Центральные";

    /** Called right before we open eTVnet. row = the heading of the Live-screen row it's in. */
    static void request(String channelName, String row) {
        pendingRow = row;
        pendingName = channelName;
        pendingUntil = System.currentTimeMillis() + GIVE_UP_MS;
        scrolls = 0;
        focusTries = 0;
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(context, AutoTuneService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (pendingName == null) return;
        if (System.currentTimeMillis() > pendingUntil) {
            pendingName = null;
            return;
        }
        // eTVnet fires lots of events while loading; act once things settle.
        handler.removeCallbacks(step);
        handler.postDelayed(step, 500);
    }

    @Override
    public void onInterrupt() {}

    // ---------------------------------------------------------------- game guide banner
    //
    // Prime / Peacock / DAZN hide their screens, so we can't press things in them. Instead, a big
    // banner sits on top of the app and tells Grandpa what to press, one step per OK press.
    // It goes away once a show has been playing (sound on) for a few seconds, on Home, or after 5 minutes.

    private static final long GUIDE_MAX_MS = 5 * 60_000;
    private static final int PLAYING_SECONDS = 6;
    private static volatile AutoTuneService instance;

    private TextView banner;
    private String[] guideSteps;
    private int guideStep, okPresses, playingSeconds;
    private long guideUntil;
    private final Runnable guideTick = this::guideTick;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideGuide();
        instance = null;
        return super.onUnbind(intent);
    }

    /** Shows the banner with these steps (no-op if the helper is switched off). */
    static void startGuide(String[] steps) {
        AutoTuneService s = instance;
        if (s != null) s.handler.post(() -> s.showGuide(steps));
    }

    static void stopGuide() {
        AutoTuneService s = instance;
        if (s != null) s.handler.post(s::hideGuide);
    }

    private void showGuide(String[] steps) {
        hideGuide();
        guideSteps = steps;
        guideStep = 0;
        okPresses = 0;
        playingSeconds = 0;
        guideUntil = System.currentTimeMillis() + GUIDE_MAX_MS;

        banner = new TextView(this);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setTextColor(Color.WHITE);
        banner.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        banner.setPadding(Math.round(28 * d), Math.round(16 * d), Math.round(28 * d), Math.round(16 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * d);
        bg.setColor(Color.parseColor("#E61A56DB")); // the same blue as the home screen, slightly see-through
        bg.setStroke(Math.round(4 * d), Color.parseColor("#FFF3C4"));
        banner.setBackground(bg);
        showStepText();

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(24 * d);
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(banner, lp);
        } catch (Exception e) {
            Log.w(TAG, "couldn't show guide banner", e);
            banner = null;
            return;
        }
        handler.postDelayed(guideTick, 1000);
    }

    private void showStepText() {
        String prefix = guideSteps.length > 1
                ? "Шаг " + (guideStep + 1) + " из " + guideSteps.length + ":  " : "";
        banner.setText(prefix + guideSteps[guideStep]);
    }

    private void hideGuide() {
        handler.removeCallbacks(guideTick);
        if (banner != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(banner);
            } catch (Exception ignored) {
            }
            banner = null;
        }
    }

    /** Once a second: gone after the game has been playing a few seconds (or after 5 minutes). */
    private void guideTick() {
        if (banner == null) return;
        if (System.currentTimeMillis() > guideUntil) {
            hideGuide();
            return;
        }
        // Only count sound after he's gone through the steps, so trailers on the app's home don't hide it
        boolean pastSteps = okPresses >= Math.max(1, guideSteps.length - 1);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        playingSeconds = pastSteps && audio.isMusicActive() ? playingSeconds + 1 : 0;
        if (playingSeconds >= PLAYING_SECONDS) {
            hideGuide();
            return;
        }
        handler.postDelayed(guideTick, 1000);
    }

    /** Watches (never blocks) remote presses while the banner is up: OK = next step, Back = previous. */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (banner != null && event.getAction() == KeyEvent.ACTION_UP) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                okPresses++;
                if (guideStep < guideSteps.length - 1) guideStep++;
                showStepText();
            } else if (k == KeyEvent.KEYCODE_BACK && guideStep > 0) {
                guideStep--;
                okPresses = Math.max(0, okPresses - 1);
                showStepText();
            } else if (k == KeyEvent.KEYCODE_HOME) {
                hideGuide();
            }
        }
        return false; // the key still goes to the app as normal
    }

    private void step() {
        String target = pendingName;
        if (target == null || System.currentTimeMillis() > pendingUntil) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String pkg = String.valueOf(root.getPackageName());
        if (!pkg.equals(Config.ETVNET.packages[0])) return;

        // 1. The channel's card is on screen. eTVnet plays whichever card is highlighted,
        //    so highlight it first (like moving the remote onto it), then press it.
        for (AccessibilityNodeInfo n : byId(root, pkg, "card_text_view")) {
            if (!matches(n.getText(), target)) continue;
            AccessibilityNodeInfo card = clickableParent(n);
            if (card == null) continue;
            if (!card.isFocused() && focusTries++ < 3) {
                Log.i(TAG, "focusing " + target);
                card.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                handler.postDelayed(step, 600);
                return;
            }
            Log.i(TAG, "clicking " + target);
            card.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            pendingName = null;
            return;
        }

        // 2. We're on the Live screen (cards have channel numbers): find the channel's row,
        //    scrolling down to it if needed, then scroll that row sideways.
        if (!byId(root, pkg, "channel_nr").isEmpty()) {
            if (scrolls++ > MAX_SCROLLS) {
                pendingName = null; // not found; leave them on the Live screen
                return;
            }
            AccessibilityNodeInfo row = findRow(root, pkg, pendingRow);
            if (row != null) {
                Log.i(TAG, "scrolling row " + pendingRow + ", try " + scrolls);
                row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } else {
                Log.i(TAG, "looking further down for row " + pendingRow);
                for (AccessibilityNodeInfo list : byId(root, pkg, "container_list")) {
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                }
            }
            handler.postDelayed(step, 700); // scrolling may not fire a new event
            return;
        }

        // 3. Anywhere else in eTVnet (it opens on its Archive tab): press "Прямой эфир".
        for (AccessibilityNodeInfo n : byId(root, pkg, "nav_button")) {
            if (matches(n.getText(), LIVE_TAB)) {
                Log.i(TAG, "opening Live tab");
                AccessibilityNodeInfo tab = clickableParent(n);
                if (tab != null) tab.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return;
            }
        }
    }

    /** The row with this heading (each row's description is its heading), or null if it's off screen. */
    private static AccessibilityNodeInfo findRow(AccessibilityNodeInfo root, String pkg, String heading) {
        for (AccessibilityNodeInfo r : byId(root, pkg, "row_content")) {
            if (matches(r.getContentDescription(), heading)) return r;
        }
        return null;
    }

    private static List<AccessibilityNodeInfo> byId(AccessibilityNodeInfo root, String pkg, String id) {
        return root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
    }

    private static boolean matches(CharSequence text, String wanted) {
        if (text == null) return false;
        return text.toString().trim().toLowerCase(Locale.ROOT).equals(wanted.trim().toLowerCase(Locale.ROOT));
    }

    /** The node, or the nearest parent that can be clicked (the whole channel card). */
    private static AccessibilityNodeInfo clickableParent(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable()) return n;
        }
        return null;
    }
}
