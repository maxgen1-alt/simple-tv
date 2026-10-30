package com.maxgendler.simpletv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Over-the-air updates: every GitHub build on main is published as a Release with
 * SimpleTV.apk and version.json. This reads version.json and installs the APK.
 * The first time, Android shows its own "Update this app?" box (confirm with OK); after that the
 * home screen installs new versions by itself (autoUpdateIfDue).
 */
final class Updater {
    private Updater() {}

    static final class Latest {
        final long versionCode;
        final String versionName;

        Latest(long versionCode, String versionName) {
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    /** Call from a background thread. */
    static Latest latest() throws Exception {
        JSONObject j = new JSONObject(Net.downloadText(Config.UPDATES_URL + "version.json"));
        return new Latest(j.getLong("versionCode"), j.optString("versionName", "?"));
    }

    static long installedCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Android's one-time "allow this app to install apps" switch. */
    static boolean canInstall(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    private static final long AUTO_CHECK_EVERY_MS = 4L * 60 * 60 * 1000;
    private static volatile boolean autoBusy;

    /**
     * Installs new versions by itself, so changes pushed to GitHub reach their TV from anywhere.
     * Called from the home screen's clock tick, so it never interrupts a show. Silent only once
     * Simple TV installed itself at least once (Android's rule); until then it does nothing, so
     * Grandpa never sees an "Update?" box. Do the first update by hand: settings → Install update.
     */
    static void autoUpdateIfDue(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (autoBusy || Build.VERSION.SDK_INT < 31 || !canInstall(context) || !installedBySelf(context)
                || now - prefs.getLong("auto_checked_at", 0) < AUTO_CHECK_EVERY_MS) return;
        prefs.edit().putLong("auto_checked_at", now).apply();
        autoBusy = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (latest().versionCode > installedCode(app)) install(app, true);
            } catch (Exception ignored) {
                // No internet: try again next time
            } finally {
                autoBusy = false;
            }
        }, "auto-update").start();
    }

    /** True once a past update came through Simple TV itself (not adb), which allows silent updates. */
    static boolean installedBySelf(Context context) {
        try {
            String installer = Build.VERSION.SDK_INT >= 30
                    ? context.getPackageManager().getInstallSourceInfo(context.getPackageName()).getInstallingPackageName()
                    : context.getPackageManager().getInstallerPackageName(context.getPackageName());
            return context.getPackageName().equals(installer);
        } catch (Exception e) {
            return false;
        }
    }

    /** Downloads the APK and hands it to Android's installer. Call from a background thread. */
    static void downloadAndInstall(Context context) throws Exception {
        install(context, false);
    }

    /** quiet = automatic update: never show Android's confirm box (if it would need one, skip). */
    private static void install(Context context, boolean quiet) throws Exception {
        byte[] apk = Net.download(Config.UPDATES_URL + "SimpleTV.apk");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("SimpleTV.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // installer adds its result
            PendingIntent done = PendingIntent.getBroadcast(context, id,
                    new Intent(context, InstallReceiver.class).putExtra(InstallReceiver.QUIET, quiet), flags);
            session.commit(done.getIntentSender());
        }
    }
}
