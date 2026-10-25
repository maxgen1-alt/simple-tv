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
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The whole home screen: black background, a clock, and six giant buttons in a row.
 *
 *   [ Knicks ] [ Chelsea ] [ 7 ] [ 4 ] [ RTVI ] [ НТВ ]
 */
public class MainActivity extends Activity {

    private static final Locale RU = new Locale("ru");
    private static final int TILE_BG = Color.parseColor("#161616");
    private static final int TILE_BG_FOCUSED = Color.parseColor("#2B2B2B");
    private static final String CANT_OPEN = "Не получается открыть. Позвоните Максу.";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView dateView, timeView, messageView;
    private TeamTile knicks, chelsea;
    private Tile[] channelTiles;
    private Tile firstTile;
    private int upPresses = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            for (TeamTile t : teams()) {
                t.update();
                t.store.refreshIfStale(t::update);
            }
            handler.postDelayed(this, 30_000);
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(buildScreen());
        for (TeamTile t : teams()) {
            LogoStore.load(this, t.team, bmp -> t.showLogo(bmp));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        refreshInstalled();
        for (TeamTile t : teams()) t.update();
        focusDefault();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    /** Home pressed while we're already showing: just put the cursor back where it belongs. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        messageView.setVisibility(View.GONE);
        focusDefault();
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
        column.setPadding(dp(30), dp(24), dp(30), dp(24));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Clock row
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(dp(10), 0, dp(10), 0);
        dateView = text(30, Color.parseColor("#BDBDBD"), false);
        timeView = text(40, Color.WHITE, true);
        clock.addView(dateView);
        clock.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock.addView(timeView);
        column.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // One row of six tiles, centered in the space below the clock
        knicks = new TeamTile(Config.KNICKS);
        chelsea = new TeamTile(Config.CHELSEA);
        channelTiles = new Tile[Config.CHANNELS.length];

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addToRow(row, knicks.root);
        addToRow(row, chelsea.root);
        for (int i = 0; i < Config.CHANNELS.length; i++) {
            Config.Channel ch = Config.CHANNELS[i];
            channelTiles[i] = channelTile(ch);
            channelTiles[i].root.setOnClickListener(v -> openChannel(ch));
            addToRow(row, channelTiles[i].root);
        }
        firstTile = knicks;

        column.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        column.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
        column.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Big message strip for "couldn't open" etc.
        messageView = text(34, Color.WHITE, true);
        messageView.setGravity(Gravity.CENTER);
        messageView.setBackgroundColor(Color.parseColor("#CC8B0000"));
        messageView.setPadding(dp(24), dp(20), dp(24), dp(20));
        messageView.setVisibility(View.GONE);
        root.addView(messageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        return root;
    }

    private void addToRow(LinearLayout row, View tile) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(8), dp(8), dp(8), dp(8));
        row.addView(tile, p);
    }

    private TeamTile[] teams() {
        return new TeamTile[]{knicks, chelsea};
    }

    /** One giant button. */
    private class Tile {
        final FrameLayout root;
        final LinearLayout content;

        Tile() {
            root = new FrameLayout(MainActivity.this);
            root.setFocusable(true);
            root.setClickable(true);
            root.setBackground(tileBackground());
            root.setOnFocusChangeListener((v, has) ->
                    v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start());

            content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(dp(12), dp(16), dp(12), dp(16));
            root.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private Tile channelTile(Config.Channel ch) {
        Tile t = new Tile();
        if (!ch.ruTop.isEmpty()) {
            TextView top = text(26, Color.parseColor("#BDBDBD"), true);
            top.setGravity(Gravity.CENTER);
            top.setText(ch.ruTop);
            t.content.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView big = text(110, Color.WHITE, true);
        big.setGravity(Gravity.CENTER);
        big.setMaxLines(1);
        big.setAutoSizeTextTypeUniformWithConfiguration(24, 120, 2, TypedValue.COMPLEX_UNIT_SP);
        big.setText(ch.ruBig);
        t.content.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return t;
    }

    /** Knicks / Chelsea: the team logo, a status line, and a glow ring when a game is on. */
    private final class TeamTile extends Tile {
        final Config.Team team;
        final ScheduleStore store;
        final ImageView logo;
        final TextView name, status;
        final View glow;
        final ObjectAnimator glowAnim;
        Schedule.Status current = new Schedule.Status(Schedule.Phase.NONE, null);

        TeamTile(Config.Team team) {
            this.team = team;
            store = new ScheduleStore(MainActivity.this, team);

            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setVisibility(View.GONE);
            content.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Shown until the logo has downloaded once
            name = text(36, Color.WHITE, true);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);
            name.setAutoSizeTextTypeUniformWithConfiguration(16, 40, 2, TypedValue.COMPLEX_UNIT_SP);
            name.setText(team.ruName);
            content.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            status = text(20, Color.WHITE, true);
            status.setGravity(Gravity.CENTER);
            status.setMinLines(2);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(10);
            content.addView(status, sp);

            // Glow ring in team colors that pulses when a game is on / about to start
            glow = new View(MainActivity.this);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(24));
            ring.setStroke(dp(10), team.glowColor);
            ring.setColor(Color.TRANSPARENT);
            glow.setBackground(ring);
            glow.setAlpha(0f);
            root.addView(glow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            glowAnim = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.25f, 1f);
            glowAnim.setDuration(1100);
            glowAnim.setRepeatMode(ValueAnimator.REVERSE);
            glowAnim.setRepeatCount(ValueAnimator.INFINITE);

            root.setOnClickListener(v -> openTeam(this));
        }

        void showLogo(android.graphics.Bitmap bmp) {
            logo.setImageBitmap(bmp);
            logo.setVisibility(View.VISIBLE);
            name.setVisibility(View.GONE);
        }

        void update() {
            current = Schedule.statusAt(store.games(), System.currentTimeMillis());
            Schedule.Game g = current.game;
            switch (current.phase) {
                case LIVE:
                    status.setText("ИГРА ИДЁТ\nНажмите ОК");
                    break;
                case SOON:
                    status.setText("Игра в " + time(g.tipMs) + "\nНажмите ОК");
                    break;
                case LATER:
                    status.setText(dayAndTime(g.tipMs));
                    break;
                default:
                    status.setText("");
            }
            if (current.gameOn()) {
                status.setTextColor(team.glowColor);
                if (!glowAnim.isRunning()) glowAnim.start();
            } else {
                status.setTextColor(Color.parseColor("#BDBDBD"));
                if (glowAnim.isRunning()) glowAnim.cancel();
                glow.setAlpha(0f);
            }
        }
    }

    private Drawable tileBackground() {
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(24));
        normal.setColor(TILE_BG);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(24));
        focused.setColor(TILE_BG_FOCUSED);
        focused.setStroke(dp(8), Color.WHITE);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** Channel tiles fade out if their app isn't installed. */
    private void refreshInstalled() {
        for (int i = 0; i < channelTiles.length; i++) {
            boolean ok = AppLauncher.isInstalled(this, Config.CHANNELS[i].app);
            channelTiles[i].root.setAlpha(ok ? 1f : 0.35f);
        }
    }

    /** Cursor goes to whichever team has a game on now, otherwise the first tile. */
    private void focusDefault() {
        for (TeamTile t : teams()) {
            if (t.current.gameOn()) {
                t.root.requestFocus();
                return;
            }
        }
        firstTile.root.requestFocus();
    }

    // ---------------------------------------------------------------- clock

    private void updateClock() {
        Date now = new Date();
        String date = new SimpleDateFormat("EEEE, d MMMM", RU).format(now);
        dateView.setText(capitalize(date));
        timeView.setText(new SimpleDateFormat("HH:mm", RU).format(now));
    }

    // ---------------------------------------------------------------- opening things

    private void openTeam(TeamTile t) {
        Config.Team team = t.team;
        Schedule.Status s = t.current;

        if (s.gameOn() && s.game != null) {
            // 1. Game is on a channel the cable app has a link for: straight to the channel.
            if (AppLauncher.openLink(this, Config.CABLE, Schedule.cableLink(s.game))) return;
            // 2. Otherwise open the app that has the game.
            if (AppLauncher.launch(this, targetForKey(s.game.targetKey))) return;
            // 3. That app isn't installed: try the team's everyday app.
            if (team.fallback != null && AppLauncher.launch(this, team.fallback)) return;
            showMessage(CANT_OPEN);
            return;
        }

        showInfo(s.game != null ? nextGameSentence(s.game.tipMs) : "Сегодня игры нет.");
    }

    private void openChannel(Config.Channel ch) {
        if (!AppLauncher.isInstalled(this, ch.app)) {
            showMessage(CANT_OPEN);
        } else if (ch.name != null) {
            // eTVnet: start it fresh and let AutoTuneService click through to the channel.
            // If the helper isn't switched on, they just land in eTVnet.
            if (AutoTuneService.isEnabled(this)) AutoTuneService.request(ch.name);
            if (!AppLauncher.launchFresh(this, ch.app)) showMessage(CANT_OPEN);
        } else if (ch.link.isEmpty()) {
            showMessage("Канал ещё не настроен. Позвоните Максу.");
        } else if (!AppLauncher.openLink(this, ch.app, ch.link)) {
            // App didn't understand the link: at least open it.
            if (!AppLauncher.launch(this, ch.app)) showMessage(CANT_OPEN);
        }
    }

    private static Config.Target targetForKey(String key) {
        for (Config.Target t : Config.ALL_TARGETS) if (t.key.equals(key)) return t;
        return Config.DAZN;
    }

    // ---------------------------------------------------------------- helpers

    /** Red strip: something went wrong. */
    private void showMessage(String msg) {
        showStrip(msg, Color.parseColor("#CC8B0000"));
    }

    /** Calm blue strip: just information, nothing is broken. */
    private void showInfo(String msg) {
        showStrip(msg, Color.parseColor("#CC0D3B66"));
    }

    private void showStrip(String msg, int color) {
        messageView.setBackgroundColor(color);
        messageView.setText(msg);
        messageView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 6000);
    }

    private final Runnable hideMessage = () -> messageView.setVisibility(View.GONE);

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

    /** Tile status line: "Сегодня / 19:00", "Завтра / 15:00", or "ср, 28 окт. / 16:00". */
    private static String dayAndTime(long ms) {
        int days = daysFromToday(ms);
        if (days == 0) return "Сегодня\n" + time(ms);
        if (days == 1) return "Завтра\n" + time(ms);
        return new SimpleDateFormat("EE, d MMM\nHH:mm", RU).format(new Date(ms));
    }

    /** "в понедельник", "во вторник"... (the form used after "в") */
    private static final String[] ON_WEEKDAY = {
            "в воскресенье", "в понедельник", "во вторник", "в среду", "в четверг", "в пятницу", "в субботу"};

    /** e.g. "Сегодня игры нет. Следующая игра во вторник, 28 октября, в 15:00." */
    private static String nextGameSentence(long ms) {
        int days = daysFromToday(ms);
        if (days == 0) return "Игра сегодня в " + time(ms) + ".";
        String when;
        if (days == 1) {
            when = "завтра";
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ms);
            when = ON_WEEKDAY[c.get(Calendar.DAY_OF_WEEK) - 1] + ", "
                    + new SimpleDateFormat("d MMMM", RU).format(new Date(ms)) + ",";
        }
        return "Сегодня игры нет. Следующая игра " + when + " в " + time(ms) + ".";
    }

    private static int daysFromToday(long ms) {
        Calendar today = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ms);
        int days = 0;
        while (today.get(Calendar.YEAR) < then.get(Calendar.YEAR)
                || today.get(Calendar.DAY_OF_YEAR) < then.get(Calendar.DAY_OF_YEAR)) {
            today.add(Calendar.DAY_OF_YEAR, 1);
            if (++days > 400) break;
        }
        return days;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(RU) + s.substring(1);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
