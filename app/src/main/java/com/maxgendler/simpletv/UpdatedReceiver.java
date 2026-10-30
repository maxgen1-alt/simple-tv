package com.maxgendler.simpletv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Android's signal the moment an update of Simple TV finishes. While it installed, the TV fell back
 * to the last app (YouTube TV, eTVnet...); go straight back to the home screen. If Android doesn't
 * allow it from here, the helper does it a few seconds later (AutoTuneService.backHomeAfterUpdate).
 */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }
}
