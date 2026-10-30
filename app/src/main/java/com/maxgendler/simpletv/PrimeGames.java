package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prime Video has no way to look games up, so each Prime game's link is listed by hand in
 * prime-games.txt in the GitHub repo (editable from a phone). The stick re-reads it every half hour,
 * so a new line works without an app update. One game per line: "2026-10-30 <Prime link>".
 */
final class PrimeGames {
    private PrimeGames() {}

    static final String LIST_URL = "https://raw.githubusercontent.com/maxgen1-alt/simple-tv/main/prime-games.txt";
    private static final long REFRESH_MS = 30L * 60 * 1000;
    /** Prime's game code: "B0" + 8 letters/digits, from any Prime link (or typed on its own). */
    private static final Pattern CODE = Pattern.compile("\\b(B0[A-Z0-9]{8})\\b");
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(.+)$");

    private static volatile boolean fetching;

    /** Downloads the list in the background if our copy is older than half an hour. */
    static void refreshIfStale(Context context) {
        SharedPreferences prefs = prefs(context);
        if (fetching || System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < REFRESH_MS) return;
        fetching = true;
        new Thread(() -> {
            try {
                String text = Net.downloadText(LIST_URL);
                prefs.edit().putString("list", text).putLong("fetched_at", System.currentTimeMillis()).apply();
            } catch (Exception ignored) {
                // No internet: keep the old copy, try again next time
            } finally {
                fetching = false;
            }
        }, "prime-games").start();
    }

    /** The Prime TV-app link for the game on this day (New York date), or null if it isn't listed. */
    static String linkFor(Context context, long tipMs) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        day.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        String wanted = day.format(new Date(tipMs));
        for (String line : prefs(context).getString("list", "").split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches() || !m.group(1).equals(wanted)) continue;
            Matcher code = CODE.matcher(m.group(2));
            if (code.find()) return "https://app.primevideo.com/detail?asin=" + code.group(1);
        }
        return null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("prime_games", Context.MODE_PRIVATE);
    }
}
