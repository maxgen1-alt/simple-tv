package com.maxgendler.simpletv;

import java.util.List;

/**
 * Drives DAZN to the team's live game with remote presses (DAZN's game links all land on its home).
 *
 * DAZN lets the helper read every tile's title and box, but not which one is highlighted: that's a
 * white outline, so it's looked for in the picture. Seen live on 2026-10-30 (Devils @ Golden Knights):
 *   home, big banner is the team's LIVE game  -> OK on its highlighted "Watch now"
 *   home                                      -> Down to the "Live and Coming Up" row,
 *                                                walk the outline to the team's tile, OK
 *   "Join live" / "Watch from start"          -> OK (Join live is highlighted); Back if it's not his game
 */
final class DaznPilot extends Pilot {

    private static final int MAX_DOWNS = 8;
    private static final int MAX_SIDEWAYS = 12;
    private static final int MIN_TILE_W = 250, MIN_TILE_H = 200;

    private final String team;
    private static final int MAX_WAITS = 25; // screen reads, ~2-3 s apart
    private static final int OPENING_READS = 5;
    private int waits, downs, blindDowns, sideways, backs, opening;

    DaznPilot(String teamWord) {
        team = teamWord;
    }

    @Override
    boolean readsElements() {
        return true;
    }

    @Override
    protected Key decide(List<Line> lines, Pixels px) {
        // Done: the game is starting. The player still lists "Join live" behind the video, and
        // another press there would pause it.
        if (pressedWatch) return say(Key.NONE, "done");

        // 0. DAZN shows "Getting things ready for you..." for ~15 s; presses then are lost or worse
        boolean loaded = false;
        for (Line l : lines) {
            if (l.text.equals("watch now") || l.text.startsWith("join live") || isTile(l)) loaded = true;
        }
        if (!loaded) {
            if (waits++ >= MAX_WAITS) return giveUp("DAZN never loaded");
            return say(Key.NONE, "waiting for DAZN to load");
        }

        // 1. The game's "Join live / Watch from start" screen
        Line join = null, title = null;
        for (Line l : lines) {
            if (l.text.startsWith("join live")) join = l;
            if (title == null && (l.text.contains("@") || l.text.contains(" vs"))) title = l;
        }
        if (join != null) {
            if (title != null && !title.text.contains(team)) {
                if (backs++ >= 3) return giveUp("keeps opening other games");
                return say(Key.BACK, "not his game: " + title.text);
            }
            pressedWatch = true;
            return say(Key.OK, "join live");
        }
        // Just opened a game: give its "Join live" screen a few reads to appear before moving on
        if (opening > 0) {
            opening--;
            return say(Key.NONE, "waiting for the game's screen");
        }

        // 2. The big banner at the top is his team's game, live, with "Watch now" highlighted
        Line live = null, watchNow = null;
        for (Line l : lines) {
            if (l.text.equals("live") && l.t < 520) live = l;
            if (l.text.equals("watch now")) watchNow = l;
        }
        if (live != null && watchNow != null && lit(watchNow, px)) {
            for (Line l : lines) {
                if (l.t > live.b && l.t - live.b < 90 && isGameTitle(l.text, team)) {
                    opening = OPENING_READS;
                    return say(Key.OK, "banner: " + l.text);
                }
            }
        }

        // 3. The "Live and Coming Up" row
        Line heading = null;
        for (Line l : lines) {
            if (l.text.startsWith("live and coming up") || l.text.startsWith("live & upcoming")
                    || l.text.equals("live now")) heading = l;
        }
        Line focused = focusedTile(lines, px);
        if (heading == null) {
            if (downs++ >= MAX_DOWNS) return giveUp("no live row");
            return say(Key.DOWN, "looking for the live row");
        }
        Line target = null;
        for (Line l : lines) {
            if (isTile(l) && inRow(l, heading) && isGameTitle(l.text, team)
                    && (target == null || l.l < target.l)) target = l; // leftmost: live games come first
        }
        if (focused == null) {
            if (blindDowns++ >= MAX_DOWNS) return giveUp("highlight not found");
            return say(Key.DOWN, "into the rows");
        }
        if (target == null) {
            if (!inRow(focused, heading)) {
                if (focused.t < heading.t) return say(Key.DOWN, "towards the live row");
                return say(Key.UP, "towards the live row");
            }
            if (sideways++ >= MAX_SIDEWAYS) return giveUp("game not in the live row");
            return say(Key.RIGHT, "along the live row");
        }
        if (focused == target || (Math.abs(focused.l - target.l) < 40 && Math.abs(focused.t - target.t) < 40)) {
            opening = OPENING_READS;
            return say(Key.OK, "open " + target.text);
        }
        if (Math.abs(focused.t - target.t) > 60) {
            return say(focused.t < target.t ? Key.DOWN : Key.UP, "towards " + target.text);
        }
        if (sideways++ >= MAX_SIDEWAYS) return giveUp("couldn't reach the tile");
        return say(focused.l < target.l ? Key.RIGHT : Key.LEFT, "towards " + target.text);
    }

    private static boolean isTile(Line l) {
        return l.r - l.l >= MIN_TILE_W && l.b - l.t >= MIN_TILE_H;
    }

    /** The tile row starts just under its heading. */
    private static boolean inRow(Line tile, Line heading) {
        return tile.t >= heading.b - 5 && tile.t - heading.b < 120;
    }

    /** The highlighted tile has a white outline just above its picture (the background there is black). */
    private static Line focusedTile(List<Line> lines, Pixels px) {
        Line best = null;
        int bestScore = 0;
        for (Line l : lines) {
            if (!isTile(l) || l.t < 6) continue;
            int strip = Math.max(1, (l.r - l.l - 40) * 5);
            int score = px.white(l.l + 20, l.t - 6, l.r - 20, l.t - 1) * 100 / strip;
            if (score > bestScore) {
                bestScore = score;
                best = l;
            }
        }
        return bestScore >= 40 ? best : null;
    }

    /** DAZN's highlighted button is filled white ("Watch now"). */
    private static boolean lit(Line l, Pixels px) {
        int area = Math.max(1, (l.r - l.l) * (l.b - l.t));
        return px.white(l.l, l.t, l.r, l.b) * 100 / area >= 40;
    }
}
