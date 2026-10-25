package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** Keeps a saved copy of the Knicks schedule and refreshes it in the background. */
final class ScheduleStore {
    private static final String PREFS = "knicks";
    private static final String KEY_GAMES = "games";
    private static final String KEY_FETCHED_AT = "fetched_at";

    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean fetching = false;

    ScheduleStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<KnicksSchedule.Game> games() {
        return KnicksSchedule.deserialize(prefs.getString(KEY_GAMES, ""));
    }

    /** Downloads a fresh schedule if ours is old. Calls onUpdated on the main thread if it changed. */
    void refreshIfStale(Runnable onUpdated) {
        long age = System.currentTimeMillis() - prefs.getLong(KEY_FETCHED_AT, 0);
        if (age < Config.SCHEDULE_REFRESH_MS || fetching) return;
        fetching = true;
        new Thread(() -> {
            try {
                String json = download(Config.SCHEDULE_URL);
                List<KnicksSchedule.Game> games = KnicksSchedule.parse(json, Config.TEAM_TRICODE);
                if (!games.isEmpty()) {
                    prefs.edit()
                         .putString(KEY_GAMES, KnicksSchedule.serialize(games))
                         .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                         .apply();
                    main.post(onUpdated);
                }
            } catch (Exception ignored) {
                // No internet or NBA changed the file. Keep the old copy, try again later.
            } finally {
                fetching = false;
            }
        }, "knicks-schedule").start();
    }

    private static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SimpleTV");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
