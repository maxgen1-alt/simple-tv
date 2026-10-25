package com.maxgendler.simpletv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.util.List;
import java.util.Locale;

/** Finds and opens the other apps (Prime, Peacock, etc.). */
final class AppLauncher {
    private AppLauncher() {}

    /** Opens the app. Returns false if it isn't installed. */
    static boolean launch(Context context, Config.Target target) {
        return start(context, target, Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** Opens the app from its start screen, not wherever it was left (e.g. mid-show). */
    static boolean launchFresh(Context context, Config.Target target) {
        return start(context, target, Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    private static boolean start(Context context, Config.Target target, int extraFlags) {
        Intent intent = resolve(context, target);
        if (intent == null) return false;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | extraFlags);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Opens a link (a live channel, a game page) inside that app, skipping its home screen.
     * Returns false if the app isn't installed or doesn't understand the link.
     */
    static boolean openLink(Context context, Config.Target target, String link) {
        if (link == null || link.isEmpty()) return false;
        Intent app = resolve(context, target);
        String pkg = app == null ? null : packageOf(app);
        if (pkg == null) return false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isInstalled(Context context, Config.Target target) {
        return resolve(context, target) != null;
    }

    /** The app's own TV banner (wide logo) if it has one, otherwise its icon. */
    static Drawable artwork(Context context, Config.Target target) {
        Intent intent = resolve(context, target);
        if (intent == null) return null;
        PackageManager pm = context.getPackageManager();
        String pkg = packageOf(intent);
        ComponentName component = intent.getComponent();
        try {
            if (component != null) {
                Drawable d = pm.getActivityBanner(component);
                if (d != null) return d;
            }
            if (pkg != null) {
                Drawable d = pm.getApplicationBanner(pkg);
                if (d != null) return d;
                return pm.getApplicationIcon(pkg);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Intent resolve(Context context, Config.Target target) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : target.packages) {
            Intent i = launchIntentFor(pm, pkg);
            if (i != null) return i;
        }
        // Fallback: match by the app's visible name, in case the package name changed.
        String self = context.getPackageName();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)) continue;
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                for (String hint : target.nameHints) {
                    if (label.contains(hint)) {
                        Intent i = launchIntentFor(pm, pkg);
                        if (i != null) return i;
                    }
                }
            }
        }
        return null;
    }

    private static Intent launchIntentFor(PackageManager pm, String pkg) {
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {
        }
        return i;
    }

    private static String packageOf(Intent intent) {
        if (intent.getComponent() != null) return intent.getComponent().getPackageName();
        return intent.getPackage();
    }
}
