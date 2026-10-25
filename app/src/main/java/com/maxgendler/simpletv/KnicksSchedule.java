package com.maxgendler.simpletv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Reads the NBA's public schedule file and works out, for each Knicks game,
 * which app it's on. Pure logic, no Android pieces, so it's easy to test.
 */
final class KnicksSchedule {
    private KnicksSchedule() {}

    static final class Game {
        final long tipMs;
        final String targetKey;   // matches Config.Target.key
        final String opponent;    // tricode, e.g. "BOS"
        final boolean home;

        Game(long tipMs, String targetKey, String opponent, boolean home) {
            this.tipMs = tipMs;
            this.targetKey = targetKey;
            this.opponent = opponent;
            this.home = home;
        }
    }

    enum Phase { LIVE, SOON, LATER, NONE }

    static final class Status {
        final Phase phase;
        final Game game; // null when NONE

        Status(Phase phase, Game game) {
            this.phase = phase;
            this.game = game;
        }
    }

    // ---------- Parsing the NBA file ----------

    static List<Game> parse(String json, String team) throws JSONException {
        List<Game> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray dates = root.getJSONObject("leagueSchedule").getJSONArray("gameDates");
        for (int d = 0; d < dates.length(); d++) {
            JSONArray games = dates.getJSONObject(d).optJSONArray("games");
            if (games == null) continue;
            for (int g = 0; g < games.length(); g++) {
                JSONObject game = games.getJSONObject(g);
                String home = tricode(game.optJSONObject("homeTeam"));
                String away = tricode(game.optJSONObject("awayTeam"));
                boolean isHome = team.equals(home);
                if (!isHome && !team.equals(away)) continue;
                long tip = parseUtc(game.optString("gameDateTimeUTC", ""));
                if (tip <= 0) continue;
                String key = pickTargetKey(game.optJSONObject("broadcasters"));
                out.add(new Game(tip, key, isHome ? away : home, isHome));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.tipMs, b.tipMs));
        return out;
    }

    /**
     * Decides which app a game is on.
     * National Prime / Peacock games are exclusive, so they win.
     * ESPN/ABC games usually also air on MSG locally, so DAZN can cover them.
     * Everything else (MSG, NBA TV) goes to DAZN.
     */
    static String pickTargetKey(JSONObject broadcasters) {
        if (broadcasters == null) return Config.DAZN.key;
        StringBuilder national = new StringBuilder();
        StringBuilder local = new StringBuilder();
        Iterator<String> keys = broadcasters.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            String lk = k.toLowerCase(Locale.ROOT);
            if (lk.contains("radio")) continue; // ESPN Radio etc. isn't TV
            StringBuilder sink = lk.contains("national") ? national : local;
            collectStrings(broadcasters.opt(k), sink);
        }
        String n = national.toString().toLowerCase(Locale.ROOT);
        String l = local.toString().toLowerCase(Locale.ROOT);

        if (n.contains("prime") || n.contains("amazon")) return Config.PRIME.key;
        if (n.contains("peacock") || n.contains("nbc")) return Config.PEACOCK.key;
        if (n.contains("espn") || n.contains("abc")) {
            if (Config.PREFER_DAZN_OVER_ESPN && l.contains("msg")) return Config.DAZN.key;
            return Config.ESPN.key;
        }
        return Config.DAZN.key;
    }

    private static void collectStrings(Object v, StringBuilder sink) {
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            for (int i = 0; i < arr.length(); i++) collectStrings(arr.opt(i), sink);
        } else if (v instanceof JSONObject) {
            JSONObject obj = (JSONObject) v;
            Iterator<String> it = obj.keys();
            while (it.hasNext()) collectStrings(obj.opt(it.next()), sink);
        } else if (v instanceof String) {
            sink.append(' ').append((String) v);
        }
    }

    private static String tricode(JSONObject team) {
        return team == null ? "" : team.optString("teamTricode", "");
    }

    static long parseUtc(String s) {
        if (s == null || s.isEmpty()) return -1;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String p : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(s).getTime();
            } catch (ParseException ignored) {
            }
        }
        return -1;
    }

    // ---------- What's happening right now ----------

    static Status statusAt(List<Game> games, long now) {
        Game next = null;
        for (Game g : games) {
            if (now >= g.tipMs && now <= g.tipMs + Config.GLOW_AFTER_MS) return new Status(Phase.LIVE, g);
            if (g.tipMs > now) {
                next = g;
                break;
            }
        }
        if (next == null) return new Status(Phase.NONE, null);
        if (next.tipMs - now <= Config.GLOW_BEFORE_MS) return new Status(Phase.SOON, next);
        return new Status(Phase.LATER, next);
    }

    // ---------- Compact cache format: tip|key|opp|H;tip|key|opp|A;... ----------

    static String serialize(List<Game> games) {
        StringBuilder sb = new StringBuilder();
        for (Game g : games) {
            if (sb.length() > 0) sb.append(';');
            sb.append(g.tipMs).append('|').append(g.targetKey).append('|')
              .append(g.opponent).append('|').append(g.home ? 'H' : 'A');
        }
        return sb.toString();
    }

    static List<Game> deserialize(String s) {
        List<Game> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String row : s.split(";")) {
            String[] p = row.split("\\|");
            if (p.length != 4) continue;
            try {
                out.add(new Game(Long.parseLong(p[0]), p[1], p[2], "H".equals(p[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
