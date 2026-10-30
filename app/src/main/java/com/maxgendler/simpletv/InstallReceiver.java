package com.maxgendler.simpletv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** Hears back from Android's installer: shows its confirm box, or says what went wrong. */
public class InstallReceiver extends BroadcastReceiver {
    /** Extra: this was an automatic update, so never pop up boxes on Grandpa's screen. */
    static final String QUIET = "quiet";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (intent.getBooleanExtra(QUIET, false)) return; // automatic: a failure just retries later
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation")
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            // On success the app restarts by itself with the new version.
            String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Update failed: " + why, Toast.LENGTH_LONG).show();
        }
    }
}
