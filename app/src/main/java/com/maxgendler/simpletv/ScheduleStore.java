package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.List;

/** Keeps a saved copy of one team's schedule and refreshes it in the background. */
final class ScheduleStore {
    private static final String KEY_GAMES = "games";
    private static final String KEY_FETCHED_AT = "fetched_at";

    private final Config.Team team;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching = false;

    ScheduleStore(Context context, Config.Team team) {
        this.team = team;
        prefs = context.getApplicationContext()
                       .getSharedPreferences("schedule_" + team.key, Context.MODE_PRIVATE);
    }

    List<Schedule.Game> games() {
        return Schedule.deserialize(prefs.getString(KEY_GAMES, ""));
    }

    /** Downloads a fresh schedule if ours is old. Calls onUpdated on the main thread if it changed. */
    void refreshIfStale(Runnable onUpdated) {
        long age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0);
        if (age < Config.SCHEDULE_REFRESH_MS || fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                String json = Net.downloadText(team.scheduleUrl);
                List<Schedule.Game> fresh = Schedule.parse(json, team);
                if (!fresh.isEmpty()) {
                    List<Schedule.Game> games = Schedule.merge(fresh, games(), System.currentTimeMillis());
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
}
