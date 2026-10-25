package com.maxgendler.simpletv;

import android.graphics.Color;

/**
 * Everything you might want to tweak lives here.
 * Change a value, push to GitHub, reinstall the new APK.
 */
final class Config {
    private Config() {}

    /** One app the launcher can open. Tries package names first, then matches the app's visible name. */
    static final class Target {
        final String key;
        final String ruShortName;    // used in sentences like "на Peacock"
        final String[] packages;
        final String[] nameHints;    // lowercase pieces of the app name, fallback if package differs

        Target(String key, String ruShortName, String[] packages, String[] nameHints) {
            this.key = key;
            this.ruShortName = ruShortName;
            this.packages = packages;
            this.nameHints = nameHints;
        }
    }

    static final Target PRIME = new Target(
            "prime", "Amazon Prime",
            new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            new String[]{"prime video"});

    static final Target PEACOCK = new Target(
            "peacock", "Peacock",
            new String[]{"com.peacocktv.peacockandroid"},
            new String[]{"peacock"});

    static final Target PARAMOUNT = new Target(
            "paramount", "Paramount+",
            // No Paramount+ app? It's a channel inside their Prime Video, so open Prime instead.
            new String[]{"com.cbs.ott", "com.cbs.app",
                         "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            new String[]{"paramount"});

    static final Target DAZN = new Target(
            "dazn", "DAZN",
            new String[]{"com.dazn"},
            new String[]{"dazn"});

    static final Target ESPN = new Target(
            "espn", "ESPN",
            new String[]{"com.espn.gtv", "com.espn.score_center"},
            new String[]{"espn"});

    /** The live-TV app the channel buttons use. YouTube TV for testing; swap for their real provider later. */
    static final Target CABLE = new Target(
            "cable", "YouTube TV",
            new String[]{"com.google.android.youtube.tvunplugged"},
            new String[]{"youtube tv"});

    static final Target[] ALL_TARGETS = {PRIME, PEACOCK, PARAMOUNT, DAZN, ESPN, CABLE};

    // ---- Channel buttons (tiles 3 to 6) ----

    /** A button that opens one live channel inside the CABLE app. */
    static final class Channel {
        final String ruTop;   // small line above, e.g. "КАНАЛ"
        final String ruBig;   // big text, e.g. "7"
        final String link;    // live link for this channel in the cable app ("" = not set yet)

        Channel(String ruTop, String ruBig, String link) {
            this.ruTop = ruTop;
            this.ruBig = ruBig;
            this.link = link;
        }
    }

    /*
     * How to get a link: on a computer, open tv.youtube.com, go to Live, start the channel,
     * and copy the address bar (looks like https://tv.youtube.com/watch/AbCdEf12345...).
     * YouTube TV has no channel numbers, so "7" / "4" / "45" / "78" are just which station you pick.
     */
    /** Paste each once: used by its channel button AND by Knicks/Chelsea games on that network. */
    static final String ABC_LINK = "https://tv.youtube.com/watch/ADhENjHS-8c";
    static final String NBC_LINK = "https://tv.youtube.com/watch/fIbKmfCLA9o";
    static final String ESPN_LINK = "https://tv.youtube.com/watch/Y2iC0MjiQeI";
    static final String USA_LINK = "https://tv.youtube.com/watch/Qi_C4zyXBIg";

    static final Channel[] CHANNELS = {
            new Channel("КАНАЛ", "7", ABC_LINK),
            new Channel("КАНАЛ", "4", NBC_LINK),
            new Channel("", "RTVI", ""),       // test: channel 45 (later: real RTVI)
            new Channel("", "НТВ", ""),        // test: channel 78 (later: real NTV)
    };

    /**
     * Sports games on a channel the cable app carries go straight to that channel: one click, game on.
     * Left side: a word in ESPN's channel listing. Right side: that channel's live link ("" = not set).
     */
    static final String[][] SPORTS_ON_CABLE = {
            {"abc", ABC_LINK},
            {"nbc", NBC_LINK},
            {"espn", ESPN_LINK},  // ESPN+ is skipped: it's an app, not this channel
            {"usa", USA_LINK},
            {"msg", ""},
            {"nba tv", ""},
    };

    // ---- Team buttons (tiles 1 and 2) ----

    enum Sport { BASKETBALL, SOCCER }

    static final class Team {
        final String key;
        final String ruName;       // shown if the logo can't load
        final Sport sport;
        final String espnTeamId;
        final String scheduleUrl;
        final String logoUrl;
        final int color;
        final int glowColor;
        final Target fallback;     // tried if the game's own app isn't installed (null = none)

        Team(String key, String ruName, Sport sport, String espnTeamId, String scheduleUrl,
             String logoUrl, int color, int glowColor, Target fallback) {
            this.key = key;
            this.ruName = ruName;
            this.sport = sport;
            this.espnTeamId = espnTeamId;
            this.scheduleUrl = scheduleUrl;
            this.logoUrl = logoUrl;
            this.color = color;
            this.glowColor = glowColor;
            this.fallback = fallback;
        }
    }

    static final Team KNICKS = new Team(
            "knicks", "НИКС", Sport.BASKETBALL, "18",
            "https://site.api.espn.com/apis/site/v2/sports/basketball/nba/teams/ny/schedule",
            "https://a.espncdn.com/i/teamlogos/nba/500/ny.png",
            Color.parseColor("#006BB6"), Color.parseColor("#F58426"),
            null);

    static final Team CHELSEA = new Team(
            "chelsea", "ЧЕЛСИ", Sport.SOCCER, "363",
            // "all" = every competition: Premier League, cups, Champions League
            "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/363/schedule?fixture=true",
            "https://a.espncdn.com/i/teamlogos/soccer/500/363.png",
            Color.parseColor("#034694"), Color.parseColor("#DBA111"),
            null);

    /** Re-download schedules this often (TV channels get announced during the week). */
    static final long SCHEDULE_REFRESH_MS = 6L * 60 * 60 * 1000;
    /** Team tile starts glowing this long before kickoff... */
    static final long GLOW_BEFORE_MS = 60L * 60 * 1000;
    /** ...and keeps glowing this long after kickoff (games run ~2.5h). */
    static final long GLOW_AFTER_MS = 3L * 60 * 60 * 1000;

    // ---- Hidden admin escape ----
    /** Press UP on the remote this many times in a row to open Android settings (for you, not them). */
    static final int ADMIN_UP_PRESSES = 7;
}
