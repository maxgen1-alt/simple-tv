package com.maxgendler.simpletv;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
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

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * eTVnet has no links to its channels, so this presses the buttons for them:
 * opens "Прямой эфир" (Live), scrolls the channel row until it finds the channel by name, and clicks it.
 *
 * Only watches eTVnet and DAZN, and only acts for a short while after one of our buttons is pressed.
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
    // Prime / Peacock hide their screens from helpers, so we can't press things in them (DAZN: see below). Instead,
    // a big banner sits on top of the app and tells Grandpa what to press. Every 2 seconds the helper
    // takes a picture of the screen and reads its English words (on-device, nothing leaves the stick):
    //   "Who's watching?"            -> pick the profile
    //   team name + a "Watch" button -> press Watch
    //   anything else                -> find the game
    // so the banner follows wherever he actually is, even after a wrong click. If reading ever fails it
    // falls back to one step per OK press. It hides once the game plays, on Home, or after 5 minutes.

    /** What to show for one app: the profile step is null when the app has no profile screen. */
    static final class Guide {
        final String profile, find, watch, teamWord, teamRu;

        Guide(String profile, String find, String watch, String teamWord, String teamRu) {
            this.profile = profile;
            this.find = find;
            this.watch = watch;
            this.teamWord = teamWord;
            this.teamRu = teamRu;
        }
    }

    private enum Stage { PROFILE, FIND, WATCH, WRONG_SHOW }

    private static final long GUIDE_MAX_MS = 5 * 60_000;
    private static final int PLAYING_SECONDS = 6;
    private static final long WRONG_SHOW_MS = 20_000;
    private static final long READ_EVERY_MS = 2000;
    private static final Pattern WATCH_BUTTON =
            Pattern.compile("\\b(watch|watch live|watch now|play|resume|start over)\\b");
    private static volatile AutoTuneService instance;

    private TextView banner;
    private Guide guide;
    private Stage stage;
    private int okPresses, playingSeconds;
    private boolean sawWatch, screenBlank, readerWorks, reading;
    private long guideUntil, wrongShowUntil, lastRead;
    private TextRecognizer reader;
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

    /** Shows the banner for this app (no-op if the helper is switched off). */
    static void startGuide(Guide g) {
        AutoTuneService s = instance;
        if (s != null) s.handler.post(() -> s.showGuide(g));
    }

    static void stopGuide() {
        AutoTuneService s = instance;
        daznTeam = null; // back home: stop pressing things in DAZN too
        if (s != null) s.handler.post(s::hideGuide);
    }

    private void showGuide(Guide g) {
        hideGuide();
        guide = g;
        stage = g.profile != null ? Stage.PROFILE : Stage.FIND;
        okPresses = 0;
        playingSeconds = 0;
        sawWatch = false;
        screenBlank = false;
        readerWorks = false;
        wrongShowUntil = 0;
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
        if (banner == null) return;
        if (daznTeam != null) {
            banner.setText("Включаю игру «" + guide.teamRu + "»… подождите");
            return;
        }
        if (stage == Stage.WRONG_SHOW) {
            banner.setText("Если это не игра «" + guide.teamRu + "» — нажмите НАЗАД");
            return;
        }
        int total = guide.profile != null ? 3 : 2;
        int n = stage == Stage.PROFILE ? 1 : stage == Stage.FIND ? total - 1 : total;
        String text = stage == Stage.PROFILE ? guide.profile : stage == Stage.FIND ? guide.find : guide.watch;
        banner.setText("Шаг " + n + " из " + total + ":  " + text);
    }

    private void setStage(Stage s) {
        if (s == Stage.PROFILE && guide.profile == null) s = Stage.FIND;
        if (s == Stage.WATCH) sawWatch = true;
        if (s != stage) {
            stage = s;
            showStepText();
        }
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

    /** Once a second: read the screen every 2 seconds, and hide once the game is playing. */
    private void guideTick() {
        if (banner == null) return;
        long now = System.currentTimeMillis();
        if (now > guideUntil || (stage == Stage.WRONG_SHOW && now > wrongShowUntil)) {
            hideGuide();
            return;
        }
        if (!reading && now - lastRead >= READ_EVERY_MS) readScreen();

        // Playing = steady sound. With screen reading, a playing video also looks blank (the picture
        // is protected), which tells it apart from trailers on the app's home page.
        boolean playingLooksReal = readerWorks ? screenBlank : okPresses >= (guide.profile != null ? 2 : 1);
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        playingSeconds = playingLooksReal && audio.isMusicActive() ? playingSeconds + 1 : 0;
        if (playingSeconds >= PLAYING_SECONDS && stage != Stage.WRONG_SHOW) {
            if (sawWatch || !readerWorks) {
                hideGuide(); // he pressed Watch on the team's game: enjoy
                return;
            }
            // Something is playing, but we never saw the team's game page: maybe a misclick
            stage = Stage.WRONG_SHOW;
            wrongShowUntil = now + WRONG_SHOW_MS;
            showStepText();
        }
        handler.postDelayed(guideTick, 1000);
    }

    /** Takes a picture of the screen and reads its English words (Android 11+; on-device only). */
    private void readScreen() {
        if (Build.VERSION.SDK_INT < 30) return;
        reading = true;
        lastRead = System.currentTimeMillis();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult shot) {
                    Bitmap picture = null;
                    try (HardwareBuffer buffer = shot.getHardwareBuffer()) {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, shot.getColorSpace());
                        if (hw != null) picture = hw.copy(Bitmap.Config.ARGB_8888, false);
                    } catch (Exception ignored) {
                    }
                    if (picture == null) {
                        reading = false;
                        return;
                    }
                    readText(picture);
                }

                @Override
                public void onFailure(int errorCode) {
                    reading = false;
                }
            });
        } catch (Exception e) {
            reading = false;
        }
    }

    private void readText(Bitmap picture) {
        if (reader == null) reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        Rect skip = bannerRect(picture);
        reader.process(InputImage.fromBitmap(picture, 0))
              .addOnSuccessListener(text -> {
                  StringBuilder words = new StringBuilder();
                  for (Text.TextBlock block : text.getTextBlocks()) {
                      Rect box = block.getBoundingBox();
                      if (box != null && skip != null && Rect.intersects(box, skip)) continue; // our own banner
                      words.append(block.getText()).append('\n');
                  }
                  readerWorks = true;
                  onScreenWords(words.toString().toLowerCase(Locale.ROOT).replace('’', '\''));
              })
              .addOnCompleteListener(t -> reading = false);
    }

    /** Where our banner is, in the screenshot's pixels, so its own words are ignored. */
    private Rect bannerRect(Bitmap picture) {
        if (banner == null || banner.getWidth() == 0) return null;
        int[] at = new int[2];
        banner.getLocationOnScreen(at);
        float sx = picture.getWidth() / (float) getResources().getDisplayMetrics().widthPixels;
        float sy = picture.getHeight() / (float) getResources().getDisplayMetrics().heightPixels;
        return new Rect(Math.round(at[0] * sx), Math.round(at[1] * sy),
                Math.round((at[0] + banner.getWidth()) * sx), Math.round((at[1] + banner.getHeight()) * sy));
    }

    private void onScreenWords(String words) {
        if (banner == null) return;
        screenBlank = words.trim().length() < 12; // a protected, playing video reads as (almost) nothing
        if (screenBlank || stage == Stage.WRONG_SHOW) return;
        if (words.contains("who's watching") || words.contains("who is watching")
                || words.contains("choose profile") || words.contains("select profile")) {
            setStage(Stage.PROFILE);
        } else if (words.contains(guide.teamWord) && WATCH_BUTTON.matcher(words).find()) {
            setStage(Stage.WATCH);
        } else {
            setStage(Stage.FIND);
        }
    }

    /**
     * Watches (never blocks) remote presses while the banner is up. Each press makes the helper look
     * at the screen again soon; if screen reading isn't working, OK / Back move the steps instead.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (banner != null && event.getAction() == KeyEvent.ACTION_UP) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_HOME) {
                daznTeam = null;
                hideGuide();
                return false;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) okPresses++;
            if (!readerWorks) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    setStage(stage == Stage.PROFILE ? Stage.FIND : Stage.WATCH);
                } else if (k == KeyEvent.KEYCODE_BACK) {
                    setStage(stage == Stage.WATCH ? Stage.FIND : Stage.PROFILE);
                    okPresses = Math.max(0, okPresses - 1);
                }
            }
            if (stage == Stage.WRONG_SHOW && k == KeyEvent.KEYCODE_BACK) {
                stage = Stage.FIND; // he backed out of the wrong show: help him find the game again
                playingSeconds = 0;
                showStepText();
            }
            lastRead = 0; // look again right away
        }
        return false; // the key still goes to the app as normal
    }

    // ---------------------------------------------------------------- DAZN: pick the game for him
    //
    // Unlike Prime / Peacock, DAZN's screens can be read: every game is a button named like
    // "Nets @ Knicks (MSG Broadcast)". So the helper opens the MSG+ page, presses the team's game under
    // "Live and Coming Up", then presses the play option ("Watch live", "Full Replay", ...). The banner
    // says "please wait" meanwhile; if anything goes wrong it falls back to the normal steps.

    private static final long DAZN_GIVE_UP_MS = 45_000;
    private static final long DAZN_STEP_MS = 1500;
    private static final Pattern PLAY_OPTION = Pattern.compile(
            "^(watch live|live|watch now|watch|play|resume|full replay|start over|watch from (the )?(start|beginning))\\b");

    private static volatile String daznTeam;   // e.g. "knicks" while picking; null otherwise
    private static volatile boolean daznReplayOk;
    private static volatile long daznUntil;
    private boolean daznGameOpened;
    private int daznRailClicks;
    private final Runnable daznStep = this::daznStep;

    /**
     * Call right before opening DAZN. replayOk = if there's no live/upcoming game, a replay will do
     * (only for testing from Settings; on game day it would be the wrong game).
     */
    static void pickDaznGame(String teamWord, boolean replayOk) {
        daznTeam = teamWord.toLowerCase(Locale.ROOT);
        daznReplayOk = replayOk;
        // On game day only wait briefly: until the remote-OK press is proven, the helper's presses
        // don't open anything in DAZN, so he gets the normal banner steps soon
        daznUntil = System.currentTimeMillis() + (replayOk ? DAZN_GIVE_UP_MS : 15_000);
        AutoTuneService s = instance;
        if (s != null) s.handler.post(() -> {
            s.daznGameOpened = false;
            s.daznRailClicks = 0;
            s.handler.removeCallbacks(s.daznStep);
            s.handler.postDelayed(s.daznStep, 3000); // let DAZN open first
        });
    }

    private void daznStep() {
        String team = daznTeam;
        if (team == null) return;
        if (System.currentTimeMillis() > daznUntil) {
            daznDone(false);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && Config.DAZN.packages[0].equals(String.valueOf(root.getPackageName()))) {
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            flatten(root, nodes);
            if (daznGameOpened) {
                AccessibilityNodeInfo play = teamPlayOption(nodes, team);
                if (play != null) {
                    Log.i(TAG, "DAZN: pressing " + play.getText());
                    press(play);
                    daznDone(true);
                    return;
                }
            } else {
                AccessibilityNodeInfo game = teamGame(nodes, team);
                if (game != null) {
                    Log.i(TAG, "DAZN: opening " + game.getText());
                    press(game);
                    daznGameOpened = true;
                } else if (!has(nodes, "live and coming up") && daznRailClicks++ < 3) {
                    // Not on the MSG+ page yet: press MSG+ in the menu on the left
                    for (AccessibilityNodeInfo n : nodes) {
                        if (n.isClickable() && matches(n.getText(), "MSG+")) {
                            Log.i(TAG, "DAZN: opening MSG+");
                            press(n);
                            break;
                        }
                    }
                }
            }
        }
        handler.postDelayed(daznStep, DAZN_STEP_MS);
    }

    /** Picking is over: on success the game is starting; otherwise the banner shows the normal steps. */
    private void daznDone(boolean started) {
        daznTeam = null;
        handler.removeCallbacks(daznStep);
        if (banner == null) return;
        if (started) {
            setStage(Stage.WATCH); // counts as "he pressed Watch on the team's game"
            banner.setText("Игра «" + guide.teamRu + "» начинается…");
        } else {
            showStepText();
        }
    }

    /**
     * The team's game button: under "Live and Coming Up" first (preferring the MSG broadcast, since
     * the YES one may be locked). The Settings test uses a finished game instead (replayOk), since
     * an upcoming game has nothing to play yet.
     */
    private static AccessibilityNodeInfo teamGame(List<AccessibilityNodeInfo> nodes, String team) {
        List<AccessibilityNodeInfo> live = new ArrayList<>(), other = new ArrayList<>();
        boolean inLive = false;
        for (AccessibilityNodeInfo n : nodes) {
            String label = lower(n.getContentDescription()) + " " + lower(n.getText());
            if (label.contains("live and coming up")) inLive = true;
            else if (label.contains("full replays") || label.contains("best of")) inLive = false;
            String text = lower(n.getText());
            if (!n.isClickable() || !text.contains(team) || !text.contains(" @ ")) continue;
            (inLive ? live : other).add(n);
        }
        if (!daznReplayOk) return bestBroadcast(live);
        AccessibilityNodeInfo replay = bestBroadcast(other);
        return replay != null ? replay : bestBroadcast(live);
    }

    private static AccessibilityNodeInfo bestBroadcast(List<AccessibilityNodeInfo> games) {
        for (AccessibilityNodeInfo g : games) if (lower(g.getText()).contains("msg broadcast")) return g;
        for (AccessibilityNodeInfo g : games) if (!lower(g.getText()).contains("yes broadcast")) return g;
        return games.isEmpty() ? null : games.get(0);
    }

    /**
     * A play button for his game, "live" ones first. The page's big "Watch now" belongs to some other
     * game, so a button only counts if the nearest game title around it ("Knicks @ Bulls") is his team's.
     */
    private static AccessibilityNodeInfo teamPlayOption(List<AccessibilityNodeInfo> nodes, String team) {
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (!isPlayOption(n)) continue;
            String title = nearestGameTitle(n);
            if (title == null || !title.contains(team)) continue;
            if (lower(n.getText()).contains("live")) return n;
            if (first == null) first = n;
        }
        return first;
    }

    /** The first "A @ B" title in the small group of things around this button, or null. */
    private static String nearestGameTitle(AccessibilityNodeInfo button) {
        AccessibilityNodeInfo group = button;
        for (int up = 0; up < 5; up++) {
            group = group.getParent();
            if (group == null) return null;
            List<AccessibilityNodeInfo> inside = new ArrayList<>();
            flatten(group, inside);
            if (inside.size() > 80) return null; // that's the whole page, not one game's box
            for (AccessibilityNodeInfo n : inside) {
                String t = lower(n.getText());
                if (t.contains(" @ ")) return t;
            }
        }
        return null;
    }

    private static boolean isPlayOption(AccessibilityNodeInfo n) {
        return n.isClickable() && PLAY_OPTION.matcher(lower(n.getText())).find();
    }

    private static boolean has(List<AccessibilityNodeInfo> nodes, String words) {
        for (AccessibilityNodeInfo n : nodes) {
            if ((lower(n.getContentDescription()) + " " + lower(n.getText())).contains(words)) return true;
        }
        return false;
    }

    /**
     * Moves the highlight onto it, then presses it. DAZN ignores a plain "click" from the helper, so the
     * Settings test also sends the remote's OK (Android 13+). Not on game day yet: if DAZN's highlight
     * didn't really move, OK would press whatever it's on (e.g. another game's "Watch now").
     */
    private void press(AccessibilityNodeInfo n) {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (daznReplayOk && Build.VERSION.SDK_INT >= 33) {
            handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_DPAD_CENTER), 700);
        }
    }

    /** Every node on screen, top to bottom in reading order. */
    private static void flatten(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) flatten(node.getChild(i), out);
    }

    private static String lower(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
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
