package com.maxgendler.simpletv;

import android.content.Context;

/**
 * The shared Google Photos album link, saved on the stick only.
 * It's not in the code because the GitHub repo is public and anyone with the link can view the album.
 * Set it from the settings gear → "Photo album link".
 */
final class AlbumLink {
    private AlbumLink() {}

    private static final String PREFS = "photos";
    private static final String KEY = "album_link";

    static String get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
    }

    /** Returns false (and saves nothing) if it doesn't look like a Google Photos share link. */
    static boolean set(Context context, String link) {
        String l = link == null ? "" : link.trim();
        boolean ok = l.isEmpty()
                || l.startsWith("https://photos.app.goo.gl/")
                || l.startsWith("https://photos.google.com/share/");
        if (ok) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, l).apply();
        return ok;
    }
}
