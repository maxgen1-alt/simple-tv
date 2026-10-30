package com.maxgendler.simpletv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
 * The whole home screen: black background, a clock, and a row of giant buttons.
 *
 *   [ Knicks ] [ Chelsea ] [ 7 ] [ 4 ] [ RTVI ] [ НТВ ] [ РТР ] [ Первый ] [ Ностальгия ]
 */
public class MainActivity extends Activity {

    private static final Locale RU = new Locale("ru");
    /** Light yellow page behind the tiles. */
    static final int PAGE_BG = Color.parseColor("#FFF3C4");
    /** Ring around the selected tile: deep blue shows up on both the yellow page and the dark tiles. */
    static final int FOCUS_RING = Color.parseColor("#0047FF");
    /** How thick the selection ring is, and how faded the tiles that aren't selected are. */
    private static final int RING_DP = 16;
    private static final float UNSELECTED_ALPHA = 0.55f;
    private static final int TILE_BG = Color.parseColor("#161616");
    private static final int TILE_BG_FOCUSED = Color.parseColor("#2B2B2B");
    private static final String CANT_OPEN = "Не получается открыть. Позвоните Максу.";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView dateView, weatherView, timeView, messageView;
    private TeamTile knicks, chelsea;
    private ChannelTile[] channelTiles;
    private Tile firstTile;
    private int secretPos = 0; // how far into the secret settings key pattern

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            for (TeamTile t : teams()) {
                t.update();
                t.store.refreshIfStale(t::update);
            }
            PrimeGames.refreshIfStale(MainActivity.this);
            Updater.autoUpdateIfDue(MainActivity.this); // only runs while the home screen is showing
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
            LogoStore.load(this, t.team.key, t.team.logoUrl, t::showLogo);
        }
        for (int i = 0; i < channelTiles.length; i++) {
            ChannelTile t = channelTiles[i];
            LogoStore.load(this, "ch" + i, Config.CHANNELS[i].logoUrl, t::showLogo);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AutoTuneService.stopGuide(); // back home: no game banner
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

    /** Watches for the secret key pattern (Config.SECRET_KEYS) that opens Simple TV settings. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int k = event.getKeyCode();
            int[] secret = Config.SECRET_KEYS;
            secretPos = k == secret[secretPos] ? secretPos + 1 : (k == secret[0] ? 1 : 0);
            if (secretPos == secret.length) {
                secretPos = 0;
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- building the screen

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(PAGE_BG);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(30), dp(20), dp(30), dp(34)); // room below so the bottom row's ring isn't cut off
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Clock row, with Max's small settings gear tucked in the corner
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.HORIZONTAL);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setPadding(dp(10), 0, dp(4), 0);
        dateView = text(30, Color.parseColor("#5A5040"), false);
        timeView = text(40, Color.parseColor("#222222"), true);
        clock.addView(dateView);
        weatherView = text(26, Color.parseColor("#1F4E8C"), true); // Brooklyn weather, right after the date
        weatherView.setPadding(dp(32), 0, dp(32), 0); // gap before the clock too
        clock.addView(weatherView);
        clock.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock.addView(timeView);
        column.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Two rows. Top: sports, US channels, photos. Bottom: Russian channels (eTVnet).
        knicks = new TeamTile(Config.KNICKS);
        chelsea = new TeamTile(Config.CHELSEA);
        channelTiles = new ChannelTile[Config.CHANNELS.length];

        LinearLayout top = new LinearLayout(this);
        LinearLayout bottom = new LinearLayout(this);
        addToRow(top, knicks.root);
        addToRow(top, chelsea.root);
        for (int i = 0; i < Config.CHANNELS.length; i++) {
            Config.Channel ch = Config.CHANNELS[i];
            channelTiles[i] = new ChannelTile(ch);
            channelTiles[i].root.setOnClickListener(v -> openChannel(ch));
            addToRow(ch.topRow ? top : bottom, channelTiles[i].root);
        }
        Tile photos = photosTile();
        addToRow(top, photos.root);
        firstTile = knicks;
        // Right from the last tile of the top row continues at the start of the bottom row
        View bottomFirst = bottom.getChildAt(0);
        if (bottomFirst != null) {
            bottomFirst.setId(View.generateViewId());
            photos.root.setNextFocusRightId(bottomFirst.getId());
        }

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rowParams.topMargin = dp(10);
        column.addView(top, rowParams);
        column.addView(bottom, new LinearLayout.LayoutParams(rowParams));
        // Let the selected tile grow past its row without its ring being cut off
        for (ViewGroup g : new ViewGroup[]{top, bottom, column, root}) g.setClipChildren(false);

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
        p.setMargins(dp(10), dp(8), dp(10), dp(8));
        row.addView(tile, p);
    }

    /** Family photos: the picture icon on a white panel, like the channel tiles. */
    private Tile photosTile() {
        Tile t = new Tile();
        FrameLayout panel = new FrameLayout(this);
        GradientDrawable white = new GradientDrawable();
        white.setCornerRadius(dp(16));
        white.setColor(Color.WHITE);
        panel.setBackground(white);
        panel.setPadding(dp(10), dp(10), dp(10), dp(10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.photos);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.content.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView caption = text(22, Color.WHITE, true);
        caption.setGravity(Gravity.CENTER);
        caption.setText("ФОТО");
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
        cp.topMargin = dp(8);
        t.content.addView(caption, cp);

        t.root.setOnClickListener(v -> {
            if (AlbumLink.get(this).isEmpty()) showMessage("Альбом ещё не настроен. Позвоните Максу.");
            else startActivity(new Intent(this, PhotosActivity.class));
        });
        return t;
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
            // The selected tile grows and stays bright; the others fade back, so it's obvious which one he's on
            root.setAlpha(UNSELECTED_ALPHA);
            root.setOnFocusChangeListener((v, has) -> v.animate()
                    .scaleX(has ? 1.10f : 1f).scaleY(has ? 1.10f : 1f)
                    .alpha(has ? 1f : UNSELECTED_ALPHA)
                    .setDuration(150).start());

            content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            // Room inside for the thick selection ring, so it never covers the logo
            content.setPadding(dp(RING_DP + 4), dp(RING_DP + 6), dp(RING_DP + 4), dp(RING_DP + 6));
            root.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    /** A channel: its logo on a white panel with the name underneath. Big text until the logo loads. */
    private final class ChannelTile extends Tile {
        final TextView top, big, caption;
        final FrameLayout panel;
        final ImageView logo;

        ChannelTile(Config.Channel ch) {
            top = text(26, Color.parseColor("#BDBDBD"), true);
            top.setGravity(Gravity.CENTER);
            top.setText(ch.ruTop);
            top.setVisibility(ch.ruTop.isEmpty() ? View.GONE : View.VISIBLE);
            content.addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            big = text(110, Color.WHITE, true);
            big.setGravity(Gravity.CENTER);
            big.setMaxLines(1);
            big.setAutoSizeTextTypeUniformWithConfiguration(20, 120, 2, TypedValue.COMPLEX_UNIT_SP);
            big.setText(ch.ruBig);
            content.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // White panel so dark logos (ABC) and light ones both stand out on the black screen
            panel = new FrameLayout(MainActivity.this);
            GradientDrawable white = new GradientDrawable();
            white.setCornerRadius(dp(16));
            white.setColor(Color.WHITE);
            panel.setBackground(white);
            panel.setPadding(dp(10), dp(10), dp(10), dp(10));
            logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            panel.addView(logo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            panel.setVisibility(View.GONE);
            content.addView(panel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // Long two-word names ("СПОРТИВНАЯ ПРОГРАММА") go on two lines so they stay big enough to read
            String label = ch.ruTop.isEmpty() ? ch.ruBig : ch.ruTop + " " + ch.ruBig;
            boolean twoLines = label.length() > 14 && label.contains(" ");
            caption = text(22, Color.WHITE, true);
            caption.setGravity(Gravity.CENTER);
            caption.setMaxLines(twoLines ? 2 : 1);
            caption.setAutoSizeTextTypeUniformWithConfiguration(9, 22, 1, TypedValue.COMPLEX_UNIT_SP);
            caption.setText(twoLines ? label.replaceFirst(" ", "\n") : label);
            caption.setVisibility(View.GONE);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(twoLines ? 56 : 30));
            cp.topMargin = dp(10);
            content.addView(caption, cp);
        }

        void showLogo(android.graphics.Bitmap bmp) {
            logo.setImageBitmap(bmp);
            panel.setVisibility(View.VISIBLE);
            caption.setVisibility(View.VISIBLE);
            top.setVisibility(View.GONE);
            big.setVisibility(View.GONE);
        }
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

            // Shrinks to fit the narrow tile instead of breaking words ("Сегодн-я")
            status = text(20, Color.WHITE, true);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            status.setAutoSizeTextTypeUniformWithConfiguration(10, 20, 1, TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
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
                    // ◉ = the round middle button: the ONN remote has no "OK" written on it
                    status.setText("ИГРА ИДЁТ\nНажмите ◉");
                    break;
                case SOON:
                    status.setText("Игра в " + time(g.tipMs) + "\nНажмите ◉");
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

        // Selected: a thick blue ring with a thin white line just inside it, so it stands out
        // against both the yellow page and the dark tile
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(24));
        ring.setColor(TILE_BG_FOCUSED);
        ring.setStroke(dp(RING_DP), FOCUS_RING);
        GradientDrawable innerLine = new GradientDrawable();
        innerLine.setCornerRadius(dp(24 - RING_DP));
        innerLine.setStroke(dp(3), Color.WHITE);
        LayerDrawable focused = new LayerDrawable(new Drawable[]{ring, innerLine});
        focused.setLayerInset(1, dp(RING_DP), dp(RING_DP), dp(RING_DP), dp(RING_DP));

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
            channelTiles[i].content.setAlpha(ok ? 1f : 0.35f); // the tile itself fades when not selected
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
        weatherView.setText(Weather.line(this));
        Weather.refreshIfStale(this, () -> weatherView.setText(Weather.line(this)));
        timeView.setText(new SimpleDateFormat("HH:mm", RU).format(now));
    }

    // ---------------------------------------------------------------- opening things

    private void openTeam(TeamTile t) {
        Config.Team team = t.team;
        Schedule.Status s = t.current;

        if (s.gameOn() && s.game != null) {
            // 1. Game is on a channel the cable app has a link for: straight to the channel.
            if (AppLauncher.openLink(this, Config.CABLE, Schedule.cableLink(s.game))) return;
            // 2. Otherwise open the app that has the game, with the step-by-step banner on top.
            Config.Target app = targetForKey(s.game.targetKey);
            // Prime game with its link listed: straight to the game's page, and the helper presses OK
            String primeLink = app == Config.PRIME ? PrimeGames.linkFor(this, s.game.tipMs) : null;
            if (AppLauncher.openLink(this, app, primeLink)) {
                AutoTuneService.startGuide(guideSteps(app, team).pressForHim(primeLink));
                return;
            }
            // Peacock has no game links: start it fresh and the helper drives it to the game
            if (app == Config.PEACOCK && AutoTuneService.isEnabled(this) && AppLauncher.launchFresh(this, app)) {
                AutoTuneService.startGuide(guideSteps(app, team).steerPeacock());
                return;
            }
            if (AppLauncher.launch(this, app)) {
                AutoTuneService.startGuide(guideSteps(app, team));
                return;
            }
            // 3. That app isn't installed: try the team's everyday app.
            if (team.fallback != null && AppLauncher.launch(this, team.fallback)) {
                AutoTuneService.startGuide(guideSteps(team.fallback, team));
                return;
            }
            showMessage(CANT_OPEN);
            return;
        }

        showInfo(s.game != null ? nextGameSentence(s.game.tipMs) : "Сегодня игры нет.");
    }

    /** What the banner tells Grandpa to press in each app (the helper picks the step from the screen). */
    static AutoTuneService.Guide guideSteps(Config.Target app, Config.Team team) {
        String find = "Найдите игру «" + team.ruName + "» и нажмите круглую кнопку ◉";
        String watch = "Нажмите «Watch» (Смотреть) — игра начнётся";
        String profile = null;
        if (app == Config.PEACOCK) profile = Config.PEACOCK_PROFILE;
        if (app == Config.PRIME || app == Config.PARAMOUNT) profile = Config.PRIME_PROFILE; // Paramount+ is inside Prime
        String pick = profile == null ? null : "Выберите «" + profile + "» и нажмите круглую кнопку ◉";
        // team.key ("knicks" / "chelsea") is how the team's name reads on the apps' English screens
        return new AutoTuneService.Guide(pick, find, watch, team.key, team.ruName,
                profile == null ? null : profile.toLowerCase(Locale.ROOT));
    }

    private void openChannel(Config.Channel ch) {
        if (!AppLauncher.isInstalled(this, ch.app)) {
            showMessage(CANT_OPEN);
        } else if (ch.name != null) {
            // eTVnet: start it fresh and let AutoTuneService click through to the channel.
            // If the helper isn't switched on, they just land in eTVnet.
            if (AutoTuneService.isEnabled(this)) AutoTuneService.request(ch.name, ch.row);
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
