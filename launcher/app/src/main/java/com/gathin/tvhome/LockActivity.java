package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The parental lock screen ({@link AppLock}): a sequence of remote arrows, then OK. Only dots show, never
 * the arrows. With no code yet it asks for a new one twice. Back leaves for Home, not the locked app.
 */
public class LockActivity extends Activity {
    static final String EXTRA_PACKAGE = "package";
    /** Change the code from Home: the old code first, then the new one twice. */
    static final String EXTRA_CHANGE = "change";

    private final StringBuilder input = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String pkg;
    /** No code yet: ask for a new one; firstEntry holds it while asking to repeat it. */
    private boolean setup;
    private String firstEntry;
    /** Change mode; setup turns on once the old code was right. */
    private boolean change;
    private TextView title, dots, hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        change = getIntent().getBooleanExtra(EXTRA_CHANGE, false);
        setup = !AppLock.hasCode(this);
        Typeface regular = getResources().getFont(R.font.firacode_regular);
        Typeface light = getResources().getFont(R.font.firacode_light);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xF5000000);
        title = text(regular, 26, 0xFFFFFFFF);
        TextView app = text(light, 16, 0x8CFFFFFF);
        dots = text(regular, 34, 0xFFFFFFFF);
        hint = text(light, 14, 0x99FFFFFF);
        app.setText(change ? "Ganti kode kunci" : appLabel());
        root.addView(title);
        root.addView(app);
        root.addView(dots);
        root.addView(hint);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        dots.setPadding(0, pad, 0, pad);
        setContentView(root);
        render();
    }

    private TextView text(Typeface tf, int sp, int color) {
        TextView v = new TextView(this);
        v.setTypeface(tf);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private CharSequence appLabel() {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Exception e) {
            return pkg == null ? "" : pkg;
        }
    }

    private void render() {
        long wait = AppLock.blockedUntil - System.currentTimeMillis();
        if (!setup && wait > 0) {
            title.setText("Terkunci");
            dots.setText("—");
            hint.setText("Terlalu banyak salah. Coba lagi dalam " + (wait / 1000 + 1) + " detik");
            handler.postDelayed(this::render, 1000);
            return;
        }
        title.setText(setup ? (firstEntry == null ? (change ? "Kode baru" : "Buat kode kunci") : "Ulangi kode")
                : change ? "Kode lama" : "Terkunci");
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < input.length(); i++) d.append(i == 0 ? "●" : " ●");
        dots.setText(d.length() == 0 ? "_" : d);
        hint.setText(setup
                ? "Tekan " + AppLock.MIN_LEN + "–" + AppLock.MAX_LEN + " tombol panah, lalu OK"
                : "Masukkan kode dengan tombol panah, lalu OK");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        char c = arrow(keyCode);
        if (c != 0) {
            if (input.length() < AppLock.MAX_LEN && (setup || AppLock.blockedUntil <= System.currentTimeMillis())) {
                input.append(c);
                render();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            submit();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (input.length() > 0) {
                input.setLength(0);
                render();
            } else {
                goHome();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    static char arrow(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return 'U';
            case KeyEvent.KEYCODE_DPAD_DOWN: return 'D';
            case KeyEvent.KEYCODE_DPAD_LEFT: return 'L';
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 'R';
            default: return 0;
        }
    }

    private void submit() {
        String seq = input.toString();
        input.setLength(0);
        if (setup) {
            if (seq.length() < AppLock.MIN_LEN) {
                shake();
            } else if (firstEntry == null) {
                firstEntry = seq;
            } else if (firstEntry.equals(seq)) {
                AppLock.setCode(this, seq);
                if (change) finish();
                else open();
                return;
            } else {
                firstEntry = null;   // mismatch: start the new code over
                shake();
            }
            render();
            return;
        }
        if (AppLock.blockedUntil > System.currentTimeMillis()) return;
        if (AppLock.check(this, seq)) {
            AppLock.wrongTries = 0;
            if (change) {   // old code right: now ask for the new one
                setup = true;
                render();
            } else {
                open();
            }
            return;
        }
        if (++AppLock.wrongTries >= AppLock.MAX_TRIES) {
            AppLock.wrongTries = 0;
            AppLock.blockedUntil = System.currentTimeMillis() + AppLock.COOLDOWN_MS;
        }
        shake();
        render();
    }

    /** The locked app is still underneath: unlocking just uncovers it. */
    private void open() {
        if (pkg != null) AppLock.unlock(pkg);
        finish();
    }

    private void shake() {
        float d = 12 * getResources().getDisplayMetrics().density;
        dots.animate().cancel();
        dots.setTranslationX(d);
        dots.animate().translationX(0).setDuration(300)
                .setInterpolator(new android.view.animation.CycleInterpolator(3)).start();
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving any other way (Home key, another app) without the code: nothing stays unlocked.
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
