package com.maxgendler.simpletv;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Max's settings screen (behind the small gear): updates and Android settings. In English on purpose. */
public class SettingsActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView helper, status;
    private TextView updateButton;
    private Updater.Latest latest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackgroundColor(MainActivity.PAGE_BG);
        col.setPadding(dp(60), dp(40), dp(60), dp(40));

        TextView title = text(34, true);
        title.setText("Simple TV settings");
        col.addView(title);

        TextView version = text(20, false);
        version.setText("Installed version " + Updater.installedName(this));
        col.addView(version);

        helper = text(20, false);
        col.addView(helper);

        status = text(22, false);
        status.setPadding(0, dp(20), 0, dp(20));
        col.addView(status);

        updateButton = button("Check for updates", v -> onUpdatePressed());
        col.addView(updateButton, buttonParams());
        col.addView(button("Photo album link", v -> editAlbumLink()), buttonParams());
        col.addView(button("Test game banner (opens Peacock)", v -> testGuide()), buttonParams());
        col.addView(button("Android settings", v -> open(new Intent(Settings.ACTION_SETTINGS))), buttonParams());
        col.addView(button("Accessibility (eTVnet helper)",
                v -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))), buttonParams());
        col.addView(button("Back", v -> finish()), buttonParams());

        ScrollView scroll = new ScrollView(this); // more buttons than fit on one TV screen
        scroll.setBackgroundColor(MainActivity.PAGE_BG);
        scroll.addView(col);
        setContentView(scroll);
        updateButton.requestFocus();
        check();
    }

    @Override
    protected void onResume() {
        super.onResume();
        helper.setText("eTVnet helper: " + (AutoTuneService.isEnabled(this)
                ? "on" : "OFF (the eTVnet channel buttons won't open their channel)"));
    }

    private void check() {
        status.setText("Checking for updates…");
        new Thread(() -> {
            try {
                Updater.Latest l = Updater.latest();
                main.post(() -> {
                    latest = l;
                    if (l.versionCode > Updater.installedCode(this)) {
                        status.setText("Update available: version " + l.versionName);
                        updateButton.setText("Install update");
                    } else {
                        status.setText("Up to date (latest is " + l.versionName + ")");
                        updateButton.setText("Check again");
                    }
                });
            } catch (Exception e) {
                main.post(() -> status.setText("Couldn't check for updates. Is the GitHub repo public? (" + e.getMessage() + ")"));
            }
        }, "update-check").start();
    }

    private void onUpdatePressed() {
        if (latest == null || latest.versionCode <= Updater.installedCode(this)) {
            check();
            return;
        }
        if (!Updater.canInstall(this)) {
            status.setText("First allow Simple TV to install apps (one time), then press Install update again.");
            open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        status.setText("Downloading version " + latest.versionName + "…");
        new Thread(() -> {
            try {
                Updater.downloadAndInstall(this);
                main.post(() -> status.setText("Press Update / Install on the next screen."));
            } catch (Exception e) {
                main.post(() -> status.setText("Download failed: " + e.getMessage()));
            }
        }, "update-install").start();
    }

    /**
     * Paste or type the shared album link (the Google TV app on a phone works as a keyboard).
     * Saved on this stick only, never in the public code.
     */
    private void editAlbumLink() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://photos.app.goo.gl/...");
        field.setText(AlbumLink.get(this));
        new AlertDialog.Builder(this)
                .setTitle("Shared Google Photos album link")
                .setMessage("In Google Photos: open the album → Share → Create link. Leave empty to turn Photos off.")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> status.setText(AlbumLink.set(this, field.getText().toString())
                        ? "Album link saved." : "That isn't a Google Photos share link. Nothing saved."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens Peacock with the Knicks game banner on top, exactly like the Knicks button during a game. */
    private void testGuide() {
        if (!AutoTuneService.isEnabled(this)) {
            status.setText("Switch on the eTVnet helper first (Accessibility). It also shows the banner.");
            return;
        }
        if (AppLauncher.launch(this, Config.PEACOCK)) {
            AutoTuneService.startGuide(MainActivity.guideSteps(Config.PEACOCK, Config.KNICKS));
        } else {
            status.setText("Peacock isn't installed.");
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            status.setText("That settings screen isn't available on this device.");
        }
    }

    private TextView button(String label, View.OnClickListener onClick) {
        TextView b = text(26, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        b.setTextColor(Color.WHITE);
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(14));
        normal.setColor(Color.parseColor("#444444"));
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(14));
        focused.setColor(Color.parseColor("#222222"));
        focused.setStroke(dp(5), MainActivity.FOCUS_RING);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, normal);
        b.setBackground(s);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(520), LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor("#222222"));
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
