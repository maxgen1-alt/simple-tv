package com.maxgendler.simpletv;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the Prime Video page of a Knicks / Chelsea game, so the team button can open it directly.
 *
 * Automatic: Prime's public sports page (primevideo.com/sports) lists upcoming games, each with a
 * time label ("Fri, Nov 13 6:30 PM EST"), a name ("Knicks vs. Pacers") and its code (B0HL62F5XC).
 * The stick reads it every 6 hours and remembers the team's games by date.
 *
 * By hand (optional override, wins over automatic): prime-games.txt in the GitHub repo, one game per
 * line: "2026-10-30 <Prime link>". Re-read every half hour, no app update needed.
 */
final class PrimeGames {
    private PrimeGames() {}

    static final String LIST_URL = "https://raw.githubusercontent.com/maxgen1-alt/simple-tv/main/prime-games.txt";
    private static final String SPORTS_URL = "https://www.primevideo.com/sports";
    private static final long REFRESH_MS = 30L * 60 * 1000;
    private static final long PRIME_REFRESH_MS = 6L * 60 * 60 * 1000;
    /** Prime's game code: "B0" + 8 letters/digits, from any Prime link (or typed on its own). */
    private static final Pattern CODE = Pattern.compile("\\b(B0[A-Z0-9]{8})\\b");
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d{4}-\\d{2}-\\d{2})\\s+(.+)$");
    // Pieces of each game card on Prime's sports page
    private static final Pattern CARD_ID = Pattern.compile("\"titleID\":\"(B0[A-Z0-9]{8})\"");
    private static final Pattern CARD_TITLE = Pattern.compile("\"title\":\"([^\"]{3,80})\"");
    private static final Pattern CARD_TIME = Pattern.compile("\"timeBadge\":\"([^\"]{3,60})\"");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.? (\\d{1,2})\\b");

    private static volatile boolean fetching, fetchingPrime;

    /** Downloads our list (every half hour) and Prime's sports page (every 6 hours) in the background. */
    static void refreshIfStale(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = System.currentTimeMillis();
        if (!fetching && now - prefs.getLong("fetched_at", 0) >= REFRESH_MS) {
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
        if (!fetchingPrime && now - prefs.getLong("prime_fetched_at", 0) >= PRIME_REFRESH_MS) {
            fetchingPrime = true;
            new Thread(() -> {
                try {
                    String found = findTeamGames(Net.downloadText(SPORTS_URL), System.currentTimeMillis());
                    if (!found.isEmpty()) prefs.edit().putString("found", found).apply();
                    prefs.edit().putLong("prime_fetched_at", System.currentTimeMillis()).apply();
                } catch (Exception ignored) {
                    // Page unreachable or changed: keep what we found before, try again later
                } finally {
                    fetchingPrime = false;
                }
            }, "prime-sports").start();
        }
    }

    /** The Prime TV-app link for the game on this day (New York date), or null if we don't know it. */
    static String linkFor(Context context, long tipMs) {
        String wanted = day().format(new Date(tipMs));
        SharedPreferences prefs = prefs(context);
        // Your list first (it can correct a wrong automatic match), then what we found on Prime
        for (String list : new String[]{prefs.getString("list", ""), prefs.getString("found", "")}) {
            for (String line : list.split("\n")) {
                Matcher m = LINE.matcher(line);
                if (!m.matches() || !m.group(1).equals(wanted)) continue;
                Matcher code = CODE.matcher(m.group(2));
                if (code.find()) return "https://app.primevideo.com/detail?asin=" + code.group(1);
            }
        }
        return null;
    }

    /**
     * Reads Prime's sports page: every card whose name has one of our teams in it becomes a line
     * "2026-11-13 B0HL62F5XC" (the date from its time label, New York time).
     */
    static String findTeamGames(String page, long nowMs) {
        StringBuilder out = new StringBuilder();
        Matcher id = CARD_ID.matcher(page);
        int cardStart = 0;
        while (id.find()) {
            // This card's own fields sit between the previous card's code and this one
            String card = page.substring(Math.max(cardStart, id.start() - 4000), id.start());
            cardStart = id.end();
            String title = last(CARD_TITLE, card), time = last(CARD_TIME, card);
            if (title == null || time == null || !isOurTeam(title)) continue;
            String date = dateOf(time, nowMs);
            if (date != null) out.append(date).append(' ').append(id.group(1)).append('\n');
        }
        return out.toString();
    }

    private static boolean isOurTeam(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (!t.contains(" vs") && !t.contains(" at ") && !t.contains("@")) return false; // a game, not a show
        return t.contains(Config.KNICKS.key) || t.contains(Config.CHELSEA.key);
    }

    /** "Fri, Nov 13 6:30 PM EST" -> 2026-11-13; "Live at 7 PM" / "Today" -> today; "Tomorrow" -> tomorrow. */
    private static String dateOf(String time, long nowMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));
        c.setTimeInMillis(nowMs);
        String t = time.toLowerCase(Locale.ROOT);
        Matcher md = MONTH_DAY.matcher(time);
        if (md.find()) {
            int month = "JanFebMarAprMayJunJulAugSepOctNovDec".indexOf(md.group(1)) / 3;
            int dayOfMonth = Integer.parseInt(md.group(2));
            int year = c.get(Calendar.YEAR);
            // Labels have no year: a date months behind us means next year (Dec -> Jan)
            if (month < c.get(Calendar.MONTH) - 2) year++;
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
        }
        if (t.contains("tomorrow")) c.add(Calendar.DAY_OF_MONTH, 1);
        else if (!t.contains("live") && !t.contains("today") && !t.contains("tonight")) return null;
        return day().format(c.getTime());
    }

    private static String last(Pattern p, String s) {
        Matcher m = p.matcher(s);
        String found = null;
        while (m.find()) found = m.group(1);
        return found;
    }

    private static SimpleDateFormat day() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        return f;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("prime_games", Context.MODE_PRIVATE);
    }
}
