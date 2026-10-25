package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Keeps a saved copy of one team's schedule and refreshes it in the background. */
final class ScheduleStore {
    private static final String KEY_GAMES = "games";
    private static final String KEY_FETCHED_AT = "fetched_at";
    /** Within this long of kickoff, refresh hourly and look up the channel if it's still missing. */
    private static final long GAME_DAY_MS = 36L * 60 * 60 * 1000;
    private static final long GAME_DAY_REFRESH_MS = 60L * 60 * 1000;

    private final Config.Team team;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching = false;

    ScheduleStore(Context context, Config.Team team) {
        this.team = team;
        // "v2": cache format gained the league field; a new name forces one fresh download
        prefs = context.getApplicationContext()
                       .getSharedPreferences("schedule_v2_" + team.key, Context.MODE_PRIVATE);
    }

    List<Schedule.Game> games() {
        return Schedule.deserialize(prefs.getString(KEY_GAMES, ""));
    }

    /** Downloads a fresh schedule if ours is old. Calls onUpdated on the main thread if it changed. */
    void refreshIfStale(Runnable onUpdated) {
        long now = System.currentTimeMillis();
        long age = now - prefs.getLong(KEY_FETCHED_AT, 0);
        long every = gameSoon(games(), now) ? GAME_DAY_REFRESH_MS : Config.SCHEDULE_REFRESH_MS;
        if (age < every || fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                String json = Net.downloadText(team.scheduleUrl);
                List<Schedule.Game> fresh = Schedule.parse(json, team);
                if (!fresh.isEmpty()) {
                    List<Schedule.Game> games = Schedule.merge(fresh, games(), System.currentTimeMillis());
                    games = fillMissingChannels(games);
                    prefs.edit()
                         .putString(KEY_GAMES, Schedule.serialize(games))
                         .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                         .apply();
                    main.post(onUpdated);
                }
            } catch (Exception ignored) {
                // No internet or ESPN changed the file. Keep the old copy, try again later.
            } finally {
                fetching = false;
            }
        }, "schedule-" + team.key).start();
    }

    private static boolean gameSoon(List<Schedule.Game> games, long now) {
        for (Schedule.Game g : games) {
            if (g.tipMs + Config.GLOW_AFTER_MS > now && g.tipMs - now < GAME_DAY_MS) return true;
        }
        return false;
    }

    /**
     * ESPN's team schedule often leaves the channel blank for Premier League games, but
     * that day's league scoreboard has it (e.g. "USA Net", "NBCSN", "Peacock"). So for a
     * game coming up soon with no channel, read the scoreboard for that day.
     */
    private List<Schedule.Game> fillMissingChannels(List<Schedule.Game> games) {
        long now = System.currentTimeMillis();
        List<Schedule.Game> out = new ArrayList<>(games.size());
        for (Schedule.Game g : games) {
            boolean soon = g.tipMs + Config.GLOW_AFTER_MS > now && g.tipMs - now < GAME_DAY_MS;
            if (soon && g.networks.isEmpty() && !g.league.isEmpty()) {
                Schedule.Game found = fromScoreboard(g);
                if (found != null) {
                    out.add(found);
                    continue;
                }
            }
            out.add(g);
        }
        return out;
    }

    private Schedule.Game fromScoreboard(Schedule.Game g) {
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US);
            day.setTimeZone(TimeZone.getTimeZone("UTC"));
            String sport = team.sport == Config.Sport.SOCCER ? "soccer" : "basketball";
            String url = "https://site.api.espn.com/apis/site/v2/sports/" + sport + "/" + g.league
                    + "/scoreboard?dates=" + day.format(new Date(g.tipMs));
            for (Schedule.Game s : Schedule.parse(Net.downloadText(url), team)) {
                if (s.tipMs == g.tipMs && !s.networks.isEmpty()) {
                    // Scoreboard events don't carry the league, so keep ours for the app choice.
                    return new Schedule.Game(g.tipMs, Schedule.pickTargetKey(team.sport, s.networks, g.league),
                            g.opponent, g.home, s.networks, g.league);
                }
            }
        } catch (Exception ignored) {
            // Try again at the next refresh.
        }
        return null;
    }
}
