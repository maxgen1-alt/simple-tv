package com.maxgendler.simpletv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Consumer;

/** Downloads a team logo once and keeps it on the device, so it shows even without internet. */
final class LogoStore {
    private LogoStore() {}

    static void load(Context context, Config.Team team, Consumer<Bitmap> onLoaded) {
        File file = new File(context.getFilesDir(), "logo_" + team.key + ".png");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Bitmap bmp = file.exists() ? BitmapFactory.decodeFile(file.getPath()) : null;
            if (bmp == null) {
                try {
                    byte[] bytes = Net.download(team.logoUrl);
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(bytes);
                        }
                    }
                } catch (Exception ignored) {
                    // Offline. The tile shows the team name instead; we'll try again next time.
                }
            }
            Bitmap result = bmp;
            if (result != null) main.post(() -> onLoaded.accept(result));
        }, "logo-" + team.key).start();
    }
}
