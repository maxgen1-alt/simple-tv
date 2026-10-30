package com.maxgendler.simpletv;

import java.util.List;
import java.util.Locale;

/**
 * Drives an app with no game links (Peacock, DAZN) to the team's game, one remote press at a time.
 * Every 2 seconds the helper hands it what's on screen and it answers with the next key.
 */
abstract class Pilot {

    enum Key { NONE, OK, UP, DOWN, LEFT, RIGHT, BACK }

    /** Something with words on screen and its box (1080p pixels). */
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

    /** Looks at the screenshot: how many pixels of the app's highlight colour are inside a box. */
    interface Pixels {
        int yellow(int l, int t, int r, int b);
        int white(int l, int t, int r, int b);
    }

    private static final int MAX_PRESSES = 45;

    private int presses;
    private boolean gaveUp;
    protected boolean pressedWatch;
    String why = ""; // what it decided last, for the log

    /** true: the app's own screen elements are readable (better than reading the picture). */
    boolean readsElements() {
        return false;
    }

    boolean active() {
        return !gaveUp;
    }

    /** It has pressed play on the team's game. */
    boolean pressedWatch() {
        return pressedWatch;
    }

    final Key next(List<Line> lines, Pixels px) {
        if (gaveUp) return Key.NONE;
        if (presses >= MAX_PRESSES) return giveUp("too many presses");
        Key k = decide(lines, px);
        if (k != Key.NONE) presses++;
        return k;
    }

    protected abstract Key decide(List<Line> lines, Pixels px);

    protected Key say(Key k, String reason) {
        why = reason;
        return k;
    }

    protected Key giveUp(String reason) {
        gaveUp = true;
        why = "gave up: " + reason;
        return Key.NONE;
    }

    static String joined(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) sb.append(l.text).append('\n');
        return sb.toString();
    }

    /** A game title naming the team: "nets @ knicks", "knicks vs. 76ers", "man united v chelsea". */
    static boolean isGameTitle(String text, String team) {
        return text.contains(team) && (text.contains("@") || text.contains(" vs") || text.contains(" v ")
                || text.contains(" at "));
    }

    /** Peacock's yellow (about #FFC800 to #FFE000), as opposed to white text or skin tones. */
    static boolean isYellow(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return r > 200 && g > 150 && b < 100 && r - b > 130;
    }

    /** DAZN's highlight outline is plain white. */
    static boolean isWhite(int argb) {
        return ((argb >> 16) & 0xFF) > 225 && ((argb >> 8) & 0xFF) > 225 && (argb & 0xFF) > 225;
    }
}
