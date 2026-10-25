package com.maxgendler.simpletv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The whole home screen: black background, a clock, and four giant buttons.
 *
 *   [ Русское ТВ ]  [ 🏀 НИКС ]
 *   [ Amazon Prime ] [ Peacock ]
 */
public class MainActivity extends Activity {

    private static final Locale RU = new Locale("ru");
    private static final int KNICKS_BLUE = Color.parseColor("#006BB6");
    private static final int KNICKS_ORANGE = Color.parseColor("#F58426");
    private static final int TILE_BG = Color.parseColor("#161616");
    private static final int TILE_BG_FOCUSED = Color.parseColor("#2B2B2B");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScheduleStore schedule;

    private TextView dateView, timeView, messageView;
    private Tile russianTile, knicksTile, primeTile, peacockTile;
    private TextView knicksStatusView;
    private View knicksGlow;
    private ObjectAnimator glowAnim;
    private KnicksSchedule.Status knicksStatus = new KnicksSchedule.Status(KnicksSchedule.Phase.NONE, null);
    private int upPresses = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            updateKnicks(false);
            schedule.refreshIfStale(() -> updateKnicks(false));
            handler.postDelayed(this, 30_000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        schedule = new ScheduleStore(this);
        setContentView(buildScreen());
        russianTile.root.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        refreshArtwork();
        updateKnicks(true);
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** This IS the home screen, so Back does nothing (prevents getting lost). */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            upPresses++;
            if (upPresses >= Config.ADMIN_UP_PRESSES) {
                upPresses = 0;
                openAndroidSettings();
                return true;
            }
        } else {
            upPresses = 0;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- building the screen

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(40), dp(24), dp(40), dp(32));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Clock row
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        dateView = text(30, Color.parseColor("#BDBDBD"), false);
        timeView = text(40, Color.WHITE, true);
        View spacer = new View(this);
        clock.addView(dateView);
        clock.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        clock.addView(timeView);
        column.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Two rows of two tiles
        russianTile = new Tile(Config.RUSSIAN_TV, TILE_BG, TILE_BG_FOCUSED, Color.WHITE);
        knicksTile = buildKnicksTile();
        primeTile = new Tile(Config.PRIME, TILE_BG, TILE_BG_FOCUSED, Color.WHITE);
        peacockTile = new Tile(Config.PEACOCK, TILE_BG, TILE_BG_FOCUSED, Color.WHITE);

        column.addView(row(russianTile.root, knicksTile.root), rowParams());
        column.addView(row(primeTile.root, peacockTile.root), rowParams());

        russianTile.root.setOnClickListener(v -> open(Config.RUSSIAN_TV));
        primeTile.root.setOnClickListener(v -> open(Config.PRIME));
        peacockTile.root.setOnClickListener(v -> open(Config.PEACOCK));
        knicksTile.root.setOnClickListener(v -> openKnicks());

        // Big message strip for "couldn't open" etc.
        messageView = text(34, Color.WHITE, true);
        messageView.setGravity(Gravity.CENTER);
        messageView.setBackgroundColor(Color.parseColor("#CC8B0000"));
        messageView.setPadding(dp(24), dp(20), dp(24), dp(20));
        messageView.setVisibility(View.GONE);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(messageView, mp);

        return root;
    }

    private Tile buildKnicksTile() {
        Tile t = new Tile(null, KNICKS_BLUE, KNICKS_BLUE, Color.WHITE);
        t.label.setText("🏀  НИКС");
        t.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
        t.image.setVisibility(View.GONE);

        knicksStatusView = text(26, Color.WHITE, true);
        knicksStatusView.setGravity(Gravity.CENTER);
        t.content.addView(knicksStatusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Orange glow ring that pulses when a game is on / about to start
        knicksGlow = new View(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(28));
        ring.setStroke(dp(14), KNICKS_ORANGE);
        ring.setColor(Color.TRANSPARENT);
        knicksGlow.setBackground(ring);
        knicksGlow.setAlpha(0f);
        t.root.addView(knicksGlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        glowAnim = ObjectAnimator.ofFloat(knicksGlow, View.ALPHA, 0.25f, 1f);
        glowAnim.setDuration(1100);
        glowAnim.setRepeatMode(ValueAnimator.REVERSE);
        glowAnim.setRepeatCount(ValueAnimator.INFINITE);
        return t;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(14), dp(14), dp(14), dp(14));
        r.addView(a, p);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p2.setMargins(dp(14), dp(14), dp(14), dp(14));
        r.addView(b, p2);
        return r;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    }

    /** One giant button. */
    private final class Tile {
        final Config.Target target;
        final FrameLayout root;
        final LinearLayout content;
        final ImageView image;
        final TextView label;

        Tile(Config.Target target, int bg, int bgFocused, int textColor) {
            this.target = target;
            root = new FrameLayout(MainActivity.this);
            root.setFocusable(true);
            root.setClickable(true);
            root.setBackground(tileBackground(bg, bgFocused));
            root.setOnFocusChangeListener((v, has) ->
                    v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(150).start());

            content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(24), dp(20), dp(24), dp(20));
            root.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setAdjustViewBounds(true);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            ip.bottomMargin = dp(12);
            content.addView(image, ip);

            label = text(44, textColor, true);
            label.setGravity(Gravity.CENTER);
            if (target != null) label.setText(target.ruLabel);
            content.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private Drawable tileBackground(int bg, int bgFocused) {
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(28));
        normal.setColor(bg);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(28));
        focused.setColor(bgFocused);
        focused.setStroke(dp(10), Color.WHITE);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    private void refreshArtwork() {
        for (Tile t : new Tile[]{russianTile, primeTile, peacockTile}) {
            Drawable art = AppLauncher.artwork(this, t.target);
            t.image.setImageDrawable(art);
            t.image.setVisibility(art == null ? View.INVISIBLE : View.VISIBLE);
            t.root.setAlpha(AppLauncher.isInstalled(this, t.target) ? 1f : 0.35f);
        }
    }

    // ---------------------------------------------------------------- clock

    private void updateClock() {
        Date now = new Date();
        String date = new SimpleDateFormat("EEEE, d MMMM", RU).format(now);
        dateView.setText(capitalize(date));
        timeView.setText(new SimpleDateFormat("HH:mm", RU).format(now));
    }

    // ---------------------------------------------------------------- Knicks

    private void updateKnicks(boolean moveFocusIfGame) {
        List<KnicksSchedule.Game> games = schedule.games();
        knicksStatus = KnicksSchedule.statusAt(games, System.currentTimeMillis());
        KnicksSchedule.Game g = knicksStatus.game;

        switch (knicksStatus.phase) {
            case LIVE:
                knicksStatusView.setText("ИГРА ИДЁТ\nНажмите ОК");
                startGlow();
                break;
            case SOON:
                knicksStatusView.setText("Игра в " + time(g.tipMs) + "\nНажмите ОК");
                startGlow();
                break;
            case LATER:
                knicksStatusView.setText("Следующая игра:\n" + dayAndTime(g.tipMs));
                stopGlow();
                break;
            default:
                knicksStatusView.setText("");
                stopGlow();
        }

        boolean gameOn = knicksStatus.phase == KnicksSchedule.Phase.LIVE
                || knicksStatus.phase == KnicksSchedule.Phase.SOON;
        if (moveFocusIfGame && gameOn) knicksTile.root.requestFocus();
    }

    private void startGlow() {
        if (!glowAnim.isRunning()) glowAnim.start();
    }

    private void stopGlow() {
        if (glowAnim.isRunning()) glowAnim.cancel();
        knicksGlow.setAlpha(0f);
    }

    private void openKnicks() {
        Config.Target target = Config.DAZN; // MSG channel is on DAZN 24/7, good default
        boolean gameOn = knicksStatus.phase == KnicksSchedule.Phase.LIVE
                || knicksStatus.phase == KnicksSchedule.Phase.SOON;
        if (gameOn && knicksStatus.game != null) target = targetForKey(knicksStatus.game.targetKey);

        if (!AppLauncher.launch(this, target)) {
            // Fall back to DAZN if, say, ESPN isn't installed.
            if (target == Config.DAZN || !AppLauncher.launch(this, Config.DAZN)) {
                showMessage("Не получается открыть. Позвоните Максу.");
            }
        }
    }

    private static Config.Target targetForKey(String key) {
        if (Config.PRIME.key.equals(key)) return Config.PRIME;
        if (Config.PEACOCK.key.equals(key)) return Config.PEACOCK;
        if (Config.ESPN.key.equals(key)) return Config.ESPN;
        return Config.DAZN;
    }

    // ---------------------------------------------------------------- helpers

    private void open(Config.Target target) {
        if (!AppLauncher.launch(this, target)) {
            showMessage("Не получается открыть. Позвоните Максу.");
        }
    }

    private void showMessage(String msg) {
        messageView.setText(msg);
        messageView.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> messageView.setVisibility(View.GONE), 6000);
    }

    private void openAndroidSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            showMessage("Settings unavailable");
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static String time(long ms) {
        return new SimpleDateFormat("HH:mm", RU).format(new Date(ms));
    }

    private static String dayAndTime(long ms) {
        return new SimpleDateFormat("EE, d MMM · HH:mm", RU).format(new Date(ms));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(RU) + s.substring(1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
