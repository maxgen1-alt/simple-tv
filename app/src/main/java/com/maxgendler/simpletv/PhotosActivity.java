package com.maxgendler.simpletv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Family photos from ONE shared Google Photos album (the link saved in settings). View only:
 * ◀ ▶ (or OK) to flip through, Back to go home. Nothing here can change the album.
 */
public class PhotosActivity extends Activity {

    /** Photos in a shared album page look like https://lh3.googleusercontent.com/pw/AP1Gcz... */
    private static final Pattern PHOTO = Pattern.compile("https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_\\-]+");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(4);
    private final List<String> photos = new ArrayList<>();
    private ImageView image;
    private TextView counter;
    private int index = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        counter = new TextView(this);
        counter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        counter.setTypeface(Typeface.DEFAULT_BOLD);
        counter.setTextColor(Color.WHITE);
        counter.setShadowLayer(8, 0, 0, Color.BLACK);
        counter.setGravity(Gravity.CENTER);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        counter.setPadding(pad, pad, pad, pad);
        root.addView(counter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        counter.setText("Загружаем фото…");
        loadAlbum();
    }

    private void loadAlbum() {
        new Thread(() -> {
            try {
                String html = Net.downloadText(AlbumLink.get(this));
                LinkedHashSet<String> found = new LinkedHashSet<>();
                Matcher m = PHOTO.matcher(html);
                while (m.find()) found.add(m.group());
                main.post(() -> {
                    photos.addAll(found);
                    if (photos.isEmpty()) counter.setText("В альбоме нет фото.");
                    else show(0);
                });
            } catch (Exception e) {
                main.post(() -> counter.setText("Не получается открыть фото. Позвоните Максу."));
            }
        }, "album").start();
    }

    private void show(int i) {
        if (photos.isEmpty()) return;
        index = (i + photos.size()) % photos.size(); // wraps around at the ends
        counter.setText("◀   " + (index + 1) + " из " + photos.size() + "   ▶");
        String url = photos.get(index);
        Bitmap cached = cache.get(url);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            image.setImageDrawable(null);
            fetch(url, true);
        }
        fetch(photos.get((index + 1) % photos.size()), false); // get the next one ready
    }

    private void fetch(String url, boolean showWhenReady) {
        if (cache.get(url) != null) return;
        new Thread(() -> {
            try {
                byte[] bytes = Net.download(url + "=w1920-h1080"); // ask Google for a TV-sized copy
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) return;
                main.post(() -> {
                    cache.put(url, bmp);
                    if (showWhenReady && url.equals(photos.get(index))) image.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // Leave it blank; pressing ▶ moves on.
            }
        }, "photo").start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                show(index + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                show(index - 1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
