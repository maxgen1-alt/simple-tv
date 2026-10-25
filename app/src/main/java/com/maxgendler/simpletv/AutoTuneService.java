package com.maxgendler.simpletv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.Locale;

/**
 * eTVnet has no links to its channels, so this presses the buttons for them:
 * opens "Прямой эфир" (Live), scrolls the channel row until it finds the channel by name, and clicks it.
 *
 * Only watches the eTVnet app, and only acts for ~25 seconds after one of our channel buttons is pressed.
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

    /** Called right before we open eTVnet. */
    static void request(String channelName) {
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

        // 2. We're on the Live screen (cards have channel numbers): scroll the row sideways.
        if (!byId(root, pkg, "channel_nr").isEmpty()) {
            if (scrolls++ > MAX_SCROLLS) {
                pendingName = null; // not found; leave them on the Live screen
                return;
            }
            AccessibilityNodeInfo row = firstRow(root, pkg);
            Log.i(TAG, "scrolling, try " + scrolls);
            if (row != null) row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
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

    /** The main channel row ("Центральные"), where RTVI and NTV live. Falls back to the first row. */
    private static AccessibilityNodeInfo firstRow(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> rows = byId(root, pkg, "row_content");
        for (AccessibilityNodeInfo r : rows) {
            if (matches(r.getContentDescription(), "Центральные")) return r;
        }
        return rows.isEmpty() ? null : rows.get(0);
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
