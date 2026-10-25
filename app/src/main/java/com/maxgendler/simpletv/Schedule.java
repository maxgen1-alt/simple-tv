package com.maxgendler.simpletv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Reads ESPN's public team schedule and works out, for each game,
 * which app it's on. Pure logic, no Android pieces, so it's easy to test.
 */
final class Schedule {
    private Schedule() {}

    static final class Game {
        final long tipMs;
        final String targetKey;   // matches Config.Target.key
        final String opponent;    // abbreviation, e.g. "BOS"
        final boolean home;
        final String networks;    // lowercase channel names, comma separated, e.g. "nbc,peacock"
        final String league;      // ESPN league slug, e.g. "eng.1" ("" if unknown)

        Game(long tipMs, String targetKey, String opponent, boolean home, String networks, String league) {
            this.tipMs = tipMs;
            this.targetKey = targetKey;
            this.opponent = opponent;
            this.home = home;
            this.networks = networks;
            this.league = league;
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

        boolean gameOn() {
            return phase == Phase.LIVE || phase == Phase.SOON;
        }
    }

    // ---------- Parsing the ESPN file ----------

    static List<Game> parse(String json, Config.Team team) throws JSONException {
        List<Game> out = new ArrayList<>();
        JSONArray events = new JSONObject(json).optJSONArray("events");
        if (events == null) return out;
        for (int e = 0; e < events.length(); e++) {
            JSONObject event = events.getJSONObject(e);
            JSONArray comps = event.optJSONArray("competitions");
            JSONObject comp = comps == null ? null : comps.optJSONObject(0);
            if (comp == null) continue;
            long tip = parseUtc(event.optString("date", comp.optString("date", "")));
            if (tip <= 0) continue;

            boolean home = false;
            String opponent = "";
            JSONArray competitors = comp.optJSONArray("competitors");
            if (competitors != null) {
                for (int c = 0; c < competitors.length(); c++) {
                    JSONObject side = competitors.getJSONObject(c);
                    JSONObject t = side.optJSONObject("team");
                    String id = t != null ? t.optString("id", side.optString("id")) : side.optString("id");
                    if (team.espnTeamId.equals(id)) {
                        home = "home".equals(side.optString("homeAway"));
                    } else if (t != null) {
                        opponent = t.optString("abbreviation", "");
                    }
                }
            }

            String networks = networks(comp.optJSONArray("broadcasts"));
            JSONObject league = event.optJSONObject("league");
            String leagueSlug = league == null ? "" : league.optString("slug", "");
            out.add(new Game(tip, pickTargetKey(team.sport, networks, leagueSlug), opponent, home, networks, leagueSlug));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.tipMs, b.tipMs));
        return out;
    }

    private static String networks(JSONArray broadcasts) {
        StringBuilder sb = new StringBuilder();
        if (broadcasts == null) return "";
        for (int i = 0; i < broadcasts.length(); i++) {
            JSONObject b = broadcasts.optJSONObject(i);
            if (b == null) continue;
            JSONObject media = b.optJSONObject("media");
            if (media != null) add(sb, media.optString("shortName", ""));
            JSONArray names = b.optJSONArray("names"); // scoreboard-style listing
            if (names != null) for (int n = 0; n < names.length(); n++) add(sb, names.optString(n, ""));
        }
        return sb.toString();
    }

    private static void add(StringBuilder sb, String name) {
        String clean = name.toLowerCase(Locale.ROOT).replaceAll("[|;,]", " ").trim();
        if (clean.isEmpty()) return;
        if (sb.length() > 0) sb.append(',');
        sb.append(clean);
    }

    /**
     * Decides which app a game is on.
     * Prime / Peacock / Paramount+ games are exclusive, so they win.
     * Knicks games with no national channel are on MSG, which is on DAZN.
     * Chelsea games with no channel announced yet go by competition.
     */
    static String pickTargetKey(Config.Sport sport, String networks, String leagueSlug) {
        String n = networks;
        if (n.contains("prime") || n.contains("amazon")) return Config.PRIME.key;
        if (n.contains("peacock") || n.contains("nbc")) return Config.PEACOCK.key;
        if (n.contains("paramount") || n.contains("cbs")) return Config.PARAMOUNT.key;
        if (n.contains("espn") || n.contains("abc")) return Config.ESPN.key;
        if (sport == Config.Sport.BASKETBALL) return Config.DAZN.key;

        if (n.contains("usa")) return Config.CABLE.key;  // USA Network is cable-only
        if (leagueSlug.startsWith("uefa.") || leagueSlug.equals("eng.league_cup")) return Config.PARAMOUNT.key;
        if (leagueSlug.equals("eng.fa")) return Config.ESPN.key;
        return Config.PEACOCK.key; // Premier League
    }

    /**
     * The live link for this game's channel in the cable app, or null if none is set.
     * Checked before any streaming app, so a game on a cable channel always opens there.
     * Names must match exactly ("nbc", "usa net"): NBCSN isn't NBC and ESPN2 isn't ESPN.
     */
    static String cableLink(Game game) {
        if (game.networks.isEmpty()) return null;
        for (String name : game.networks.split(",")) {
            if (name.contains("+")) continue; // ESPN+, Paramount+ etc. are apps, not channels
            for (String[] rule : Config.SPORTS_ON_CABLE) {
                boolean same = name.equals(rule[0]) || name.startsWith(rule[0] + " ");
                if (same && !rule[1].isEmpty()) return rule[1];
            }
        }
        return null;
    }

    static long parseUtc(String s) {
        if (s == null || s.isEmpty()) return -1;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"};
        for (String p : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
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

    /** Keeps a game that's on right now even if a fresh download dropped it (some feeds list only future games). */
    static List<Game> merge(List<Game> fresh, List<Game> old, long now) {
        List<Game> out = new ArrayList<>(fresh);
        for (Game g : old) {
            boolean live = now >= g.tipMs && now <= g.tipMs + Config.GLOW_AFTER_MS;
            if (!live) continue;
            boolean present = false;
            for (Game f : fresh) if (f.tipMs == g.tipMs) present = true;
            if (!present) out.add(g);
        }
        Collections.sort(out, (a, b) -> Long.compare(a.tipMs, b.tipMs));
        return out;
    }

    // ---------- Compact cache format: tip|key|opp|H|networks|league;... ----------

    static String serialize(List<Game> games) {
        StringBuilder sb = new StringBuilder();
        for (Game g : games) {
            if (sb.length() > 0) sb.append(';');
            sb.append(g.tipMs).append('|').append(g.targetKey).append('|')
              .append(g.opponent).append('|').append(g.home ? 'H' : 'A').append('|')
              .append(g.networks).append('|').append(g.league);
        }
        return sb.toString();
    }

    static List<Game> deserialize(String s) {
        List<Game> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String row : s.split(";")) {
            String[] p = row.split("\\|", -1);
            if (p.length != 6) continue;
            try {
                out.add(new Game(Long.parseLong(p[0]), p[1], p[2], "H".equals(p[3]), p[4], p[5]));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
