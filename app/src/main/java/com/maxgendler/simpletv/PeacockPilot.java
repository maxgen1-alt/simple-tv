package com.maxgendler.simpletv;

import java.util.List;
import java.util.Locale;

/**
 * Drives Peacock to the team's game with remote presses, since Peacock has no public game links.
 *
 * Peacock shows the helper nothing but its picture, so every 2 seconds it gets the screen's words (with
 * where they are) and a way to look for Peacock's yellow highlight, and answers with one remote key:
 *   "Who's watching?"            -> OK (his profile is the last used, already highlighted)
 *   Peacock's side menu is open  -> move the yellow ring to "Sports", OK
 *   anywhere else before Sports  -> Left (opens the side menu)
 *   the team's game on screen    -> walk the highlight to it, OK on its yellow WATCH
 *   "Watch live / from beginning" -> the live one
 *   no game in sight on Sports   -> Down, row by row, then give up (the banner tells him what to do)
 */
final class PeacockPilot {

    enum Key { NONE, OK, UP, DOWN, LEFT, RIGHT }

    /** One line of words on screen and its box, in screenshot pixels. */
    static final class Line {
        final String text; // lowercase
        final int l, t, r, b;

        Line(String text, int l, int t, int r, int b) {
            this.text = text.toLowerCase(Locale.ROOT);
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }

        int cx() { return (l + r) / 2; }
        int cy() { return (t + b) / 2; }
    }

    /** How many of Peacock's yellow highlight pixels are inside a box of the screenshot. */
    interface Yellow {
        int count(int l, int t, int r, int b);
    }

    private static final int MAX_PRESSES = 45;
    private static final int MAX_LEFTS = 10;   // from deep in a row, Left takes a few presses to reach the menu
    private static final int MAX_EXPLORE = 10; // rows to look through on the Sports page
    private static final int MENU_RIGHT_EDGE = 480;
    private static final int BIG_TITLE = 40; // px tall: a page title, not a row tile's caption

    private final String team;
    private int presses, lefts, explored, profileTries;
    private boolean onSports, pressedWatch, gaveUp;
    String why = ""; // what it decided last, for the log

    PeacockPilot(String teamWord) {
        team = teamWord;
    }

    /** Still pressing buttons for him (false once it has given up). */
    boolean active() {
        return !gaveUp;
    }

    /** It has pressed Watch on the team's game. */
    boolean pressedWatch() {
        return pressedWatch;
    }

    Key next(List<Line> lines, Yellow yellow) {
        if (gaveUp) return Key.NONE;
        if (presses >= MAX_PRESSES) return giveUp("too many presses");
        Key k = decide(lines, yellow);
        if (k != Key.NONE) presses++;
        return k;
    }

    private Key decide(List<Line> lines, Yellow yellow) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.text).append('\n');
        String all = sb.toString();

        // 1. Profiles
        if (all.contains("who's watching") || all.contains("who is watching")) {
            if (profileTries++ >= 4) return giveUp("profile screen won't go away");
            return say(Key.OK, "profile");
        }

        // 2. After Watch: "Watch live" or "Watch from beginning"
        if (pressedWatch && all.contains("beginning")) {
            Line live = null, focused = null;
            for (Line l : lines) {
                if (l.text.contains("live") && !l.text.contains("beginning")) live = live == null ? l : live;
                if (isLit(l, yellow, 12)) focused = l;
            }
            if (live == null || focused == null || focused == live) return say(Key.OK, "watch live");
            return say(live.cy() < focused.cy() ? Key.UP : Key.DOWN, "towards watch live");
        }

        // 3. Side menu open: ring to "Sports"
        Line sports = null, lit = null;
        int menuWords = 0, best = 0;
        for (Line l : lines) {
            if (l.r > MENU_RIGHT_EDGE) continue;
            if (l.text.equals("search") || l.text.equals("home") || l.text.equals("movies")
                    || l.text.equals("tv shows") || l.text.equals("sports") || l.text.equals("channels")) {
                menuWords++;
                // The ring is drawn around the whole menu item, a little outside the word
                int n = yellow.count(40, l.t - 30, MENU_RIGHT_EDGE - 40, l.b + 30);
                if (n > best) {
                    best = n;
                    lit = l;
                }
                if (l.text.equals("sports")) sports = l;
            }
        }
        if (menuWords >= 3 && sports != null) {
            if (lit == null || best < 40) return say(Key.DOWN, "menu, ring not found");
            if (lit == sports) {
                onSports = true;
                return say(Key.OK, "sports");
            }
            return say(lit.cy() < sports.cy() ? Key.DOWN : Key.UP, "menu towards sports");
        }

        // 4. The team's game on screen: walk to it
        Line game = findGame(lines);
        Line watch = litWatchButton(lines, yellow);
        if (game != null) {
            if (watch != null && game.b - game.t >= BIG_TITLE && watch.t >= game.b - 10
                    && Math.abs(watch.l - game.l) < 400 && watch.t - game.b < 500) {
                // The game's own page (big title, WATCH highlighted under it). Row titles are small,
                // so a tile in the row above can't be mistaken for this.
                pressedWatch = true;
                return say(Key.OK, "watch (game page)");
            }
            int[] focus = watch != null ? new int[]{watch.l, watch.t, watch.r, watch.b} : null;
            if (focus == null) return say(Key.DOWN, "game seen, no highlight yet");
            int fcx = (focus[0] + focus[2]) / 2;
            boolean sameColumn = game.cx() > focus[0] - 140 && game.cx() < focus[2] + 140;
            boolean rightBelow = game.t >= focus[3] - 10 && game.t - focus[3] < 260;
            if (sameColumn && rightBelow) {
                // A row tile turns into INFO / WATCH when highlighted; its title sits just under it
                pressedWatch = true;
                return say(Key.OK, "watch (tile)");
            }
            if (game.t > focus[3] + 260) return say(Key.DOWN, "towards game (down)");
            if (game.b < focus[1]) return say(Key.UP, "towards game (up)");
            return say(game.cx() > fcx ? Key.RIGHT : Key.LEFT, "towards game (sideways)");
        }

        // 5. Not there yet
        if (!onSports) {
            if (lefts++ >= MAX_LEFTS) return giveUp("menu never opened");
            return say(Key.LEFT, "open menu");
        }
        if (explored++ >= MAX_EXPLORE) return giveUp("game not found on Sports");
        return say(Key.DOWN, "look further down");
    }

    /** A title with the team in it, skipping replays (an old game's tile has a "Replay" badge above it). */
    private Line findGame(List<Line> lines) {
        Line found = null;
        for (Line l : lines) {
            if (!l.text.contains(team)) continue;
            if (l.text.contains("replay") || l.text.contains("highlights") || l.text.contains("recap")
                    || l.text.contains("classic") || l.text.contains("mini match")) continue;
            boolean replay = false;
            for (Line o : lines) {
                if (o.text.contains("replay") && o.b <= l.t && l.t - o.b < 360
                        && o.cx() > l.l - 60 && o.cx() < l.r + 260) replay = true;
            }
            if (replay) continue;
            // Prefer the one marked live
            if (found == null || l.text.contains("live")) found = l;
        }
        return found;
    }

    /** Peacock's highlighted button is filled yellow: WATCH / WATCH NOW / WATCH LIVE / RESUME. */
    private static Line litWatchButton(List<Line> lines, Yellow yellow) {
        for (Line l : lines) {
            String t = l.text.trim();
            if (!(t.equals("watch") || t.startsWith("watch now") || t.startsWith("watch live")
                    || t.startsWith("resume"))) continue;
            if (isLit(l, yellow, 15)) return l;
        }
        return null;
    }

    /** Yellow on at least this percent of the word's box (the button around it is yellow). */
    private static boolean isLit(Line l, Yellow yellow, int percent) {
        int area = Math.max(1, (l.r - l.l) * (l.b - l.t));
        return yellow.count(l.l - 10, l.t - 6, l.r + 10, l.b + 6) * 100 / area >= percent;
    }

    private Key say(Key k, String reason) {
        why = reason;
        return k;
    }

    private Key giveUp(String reason) {
        gaveUp = true;
        why = "gave up: " + reason;
        return Key.NONE;
    }

    /** Peacock's yellow (about #FFC800 to #FFE000), as opposed to white text or skin tones. */
    static boolean isYellow(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r > 200 && g > 150 && b < 100 && r - b > 130;
    }
}
