package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The weather outside, shown next to the date: "Бруклин ☀ +12° (54°F) ясно".
 * From Open-Meteo (free, no account or key), re-read every 30 minutes.
 */
final class Weather {
    private Weather() {}

    private static final long REFRESH_MS = 30L * 60 * 1000;
    /** Older than this, it's not "now" any more: show nothing rather than stale weather. */
    private static final long TOO_OLD_MS = 3L * 60 * 60 * 1000;
    private static volatile boolean fetching;

    /** The line to show, or "" if we don't have fresh weather. */
    static String line(Context context) {
        SharedPreferences prefs = prefs(context);
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) > TOO_OLD_MS) return "";
        double c = Double.longBitsToDouble(prefs.getLong("temp_c", 0));
        int code = prefs.getInt("code", 0);
        long f = Math.round(c * 9 / 5 + 32);
        long rounded = Math.round(c);
        String sign = rounded > 0 ? "+" : "";
        return Config.WEATHER_PLACE_RU + "  " + icon(code) + " " + sign + rounded + "° (" + f + "°F) " + words(code);
    }

    /** Downloads fresh weather in the background if ours is old; calls onUpdated on the main thread. */
    static void refreshIfStale(Context context, Runnable onUpdated) {
        SharedPreferences prefs = prefs(context);
        if (fetching || System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < REFRESH_MS) return;
        fetching = true;
        new Thread(() -> {
            try {
                String url = String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code&timezone=auto",
                        Config.WEATHER_LAT, Config.WEATHER_LON);
                JSONObject now = new JSONObject(Net.downloadText(url)).getJSONObject("current");
                prefs.edit()
                     .putLong("temp_c", Double.doubleToLongBits(now.getDouble("temperature_2m")))
                     .putInt("code", now.getInt("weather_code"))
                     .putLong("fetched_at", System.currentTimeMillis())
                     .apply();
                new Handler(Looper.getMainLooper()).post(onUpdated);
            } catch (Exception ignored) {
                // No internet: keep the old reading (until it's too old), try again next time
            } finally {
                fetching = false;
            }
        }, "weather").start();
    }

    /** WMO weather codes (what Open-Meteo returns) in a word Grandpa reads at a glance. */
    private static String words(int code) {
        if (code == 0) return "ясно";
        if (code <= 2) return "малооблачно";
        if (code == 3) return "облачно";
        if (code == 45 || code == 48) return "туман";
        if (code >= 51 && code <= 57) return "морось";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "дождь";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "снег";
        if (code >= 95) return "гроза";
        return "";
    }

    private static String icon(int code) {
        if (code == 0) return "☀";
        if (code <= 2) return "⛅";
        if (code == 3 || code == 45 || code == 48) return "☁";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";
        if (code >= 95) return "⛈";
        return "☂"; // drizzle, rain, showers
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }
}
