package com.maxgendler.simpletv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Remote buttons that do nothing at all (e.g. the Netflix / YouTube / Disney+ buttons on the Onn remote).
 * Learned in settings: press "Block a remote button", then press the button. Saved on the stick.
 * The eTVnet helper (AutoTuneService) swallows these presses everywhere, in every app, so it must be on.
 *
 * A button is saved by its key code ("k191"), or by its raw scan code ("s421") when Android has no
 * key code for it, so two unknown buttons don't get blocked together.
 */
final class BlockedKeys {
    private BlockedKeys() {}

    interface Listener {
        /** Called on the main thread with the button's name, or null if that button can't be blocked. */
        void learned(String name);
    }

    private static final String PREFS = "blocked_keys";
    private static final String KEY = "keys";

    private static volatile Set<String> blocked;
    private static volatile Listener learning;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static synchronized Set<String> all(Context context) {
        if (blocked == null) {
            blocked = Collections.unmodifiableSet(new HashSet<>(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY, Collections.emptySet())));
        }
        return blocked;
    }

    private static synchronized void save(Context context, Set<String> keys) {
        blocked = Collections.unmodifiableSet(new HashSet<>(keys));
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY, new HashSet<>(keys)).apply();
    }

    static void clear(Context context) {
        save(context, Collections.emptySet());
    }

    /** The next button pressed (other than the arrows / OK / Back / Home) gets blocked. */
    static void startLearning(Listener listener) {
        learning = listener;
    }

    static void stopLearning() {
        learning = null;
    }

    /**
     * Arrows, OK, Back and Home: blocking these would lock everyone out. Volume, Mute and Power: an
     * accidental press while "Block a remote button" is waiting would silently kill the volume in
     * every app. None of these are ever blocked, even if saved as blocked by an older version.
     */
    static boolean isProtected(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_TV_POWER:
                return true;
            default:
                return false;
        }
    }

    private static String id(KeyEvent e) {
        return e.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN ? "k" + e.getKeyCode() : "s" + e.getScanCode();
    }

    /** "NETFLIX", "BUTTON_3", or "button #421" for one Android has no name for. */
    static String name(String id) {
        try {
            int n = Integer.parseInt(id.substring(1));
            if (id.startsWith("k")) return KeyEvent.keyCodeToString(n).replace("KEYCODE_", "");
            return "button #" + n;
        } catch (RuntimeException e) {
            return id;
        }
    }

    /**
     * From AutoTuneService.onKeyEvent: true = swallow this press (nobody sees it).
     * While learning, the first non-protected press is saved and swallowed.
     */
    static boolean filter(Context context, KeyEvent e) {
        if (isProtected(e.getKeyCode())) return false;
        Listener l = learning;
        if (l != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            learning = null;
            if (e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN && e.getScanCode() == 0) {
                main.post(() -> l.learned(null));
                return true;
            }
            String id = id(e);
            Set<String> keys = new HashSet<>(all(context));
            keys.add(id);
            save(context, keys);
            main.post(() -> l.learned(name(id)));
            return true;
        }
        return all(context).contains(id(e));
    }
}
