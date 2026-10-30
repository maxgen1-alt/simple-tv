package com.maxgendler.simpletv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Over-the-air updates: every GitHub build on main is published as a Release with
 * SimpleTV.apk and version.json. This reads version.json and installs the APK.
 * Android always shows its own "Update this app?" box, which you confirm with OK.
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

    /** Downloads the APK and hands it to Android's installer. Call from a background thread. */
    static void downloadAndInstall(Context context) throws Exception {
        byte[] apk = Net.download(Config.UPDATES_URL + "SimpleTV.apk");
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream out = session.openWrite("SimpleTV.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE; // installer adds its result
            PendingIntent done = PendingIntent.getBroadcast(
                    context, id, new Intent(context, InstallReceiver.class), flags);
            session.commit(done.getIntentSender());
        }
    }
}
