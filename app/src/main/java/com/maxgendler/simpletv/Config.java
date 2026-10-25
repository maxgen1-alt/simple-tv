package com.maxgendler.simpletv;

/**
 * Everything you might want to tweak lives here.
 * Change a value, rebuild, reinstall.
 */
final class Config {
    private Config() {}

    /** One app the launcher can open. Tries package names first, then matches the app's visible name. */
    static final class Target {
        final String key;
        final String ruLabel;        // what the grandparents see under the tile
        final String ruShortName;    // used in sentences like "на Peacock"
        final String[] packages;
        final String[] nameHints;    // lowercase pieces of the app name, fallback if package differs

        Target(String key, String ruLabel, String ruShortName, String[] packages, String[] nameHints) {
            this.key = key;
            this.ruLabel = ruLabel;
            this.ruShortName = ruShortName;
            this.packages = packages;
            this.nameHints = nameHints;
        }
    }

    static final Target RUSSIAN_TV = new Target(
            "etvnet", "Русское ТВ", "eTVnet",
            new String[]{"com.etvnet.androidtv.tvstore", "com.etvnet.androidtv", "com.etvnet"},
            new String[]{"etvnet", "etv net"});

    static final Target PRIME = new Target(
            "prime", "Amazon Prime", "Amazon Prime",
            new String[]{"com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"},
            new String[]{"prime video"});

    static final Target PEACOCK = new Target(
            "peacock", "Peacock", "Peacock",
            new String[]{"com.peacocktv.peacockandroid"},
            new String[]{"peacock"});

    static final Target DAZN = new Target(
            "dazn", "DAZN", "DAZN",
            new String[]{"com.dazn"},
            new String[]{"dazn"});

    static final Target ESPN = new Target(
            "espn", "ESPN", "ESPN",
            new String[]{"com.espn.gtv", "com.espn.score_center"},
            new String[]{"espn"});

    // ---- Knicks button ----
    static final String TEAM_TRICODE = "NYK";
    static final String SCHEDULE_URL =
            "https://cdn.nba.com/static/json/staticData/scheduleLeagueV2.json";
    /** Re-download the schedule this often (TV times shift during the season). */
    static final long SCHEDULE_REFRESH_MS = 12L * 60 * 60 * 1000;
    /** Knicks tile starts glowing this long before tip-off... */
    static final long GLOW_BEFORE_MS = 60L * 60 * 1000;
    /** ...and keeps glowing this long after tip-off (games run ~2.5h). */
    static final long GLOW_AFTER_MS = 3L * 60 * 60 * 1000;
    /**
     * If a national ESPN/ABC game is ALSO on MSG locally, send them to DAZN instead.
     * Keep true if they won't have ESPN after cutting cable.
     */
    static final boolean PREFER_DAZN_OVER_ESPN = true;

    // ---- Hidden admin escape ----
    /** Press UP on the remote this many times in a row to open Android settings (for you, not them). */
    static final int ADMIN_UP_PRESSES = 7;
}
