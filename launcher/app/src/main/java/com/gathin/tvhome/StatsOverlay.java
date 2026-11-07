package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * One line in the top-left corner over every app, fullscreen video included: "21.47 · CPU 18% · RAM 412 MB ·
 * ↓ 3.2 MB/s ↑ 120 KB/s", refreshed every second. Drawn by {@link HomeKeyService} as an accessibility
 * overlay, so it needs no extra permission; hidden on Home, which shows the same figures. Switched on or off
 * from the Settings button's menu on Home ({@link #setEnabled}).
 */
final class StatsOverlay {
    private static final String PREFS = "overlay", KEY_ON = "on";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH.mm");

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CpuMeter cpu = new CpuMeter();
    private TextView view;
    private boolean onHome = true;
    private long rxPrev = -1, txPrev = -1, atPrev;
    private int lastCpu = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            view.setText(line());
            main.postDelayed(this, 1000);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener onToggle = (p, key) -> {
        if (KEY_ON.equals(key)) update();
    };

    /** {@code ctx}: the accessibility service, whose window manager may add accessibility overlays. */
    StatsOverlay(Context ctx) {
        this.ctx = ctx;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs(ctx).registerOnSharedPreferenceChangeListener(onToggle);
    }

    static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The foreground app changed: hide over Home (this launcher), show over anything else. */
    void foreground(boolean home) {
        onHome = home;
        update();
    }

    void destroy() {
        prefs(ctx).unregisterOnSharedPreferenceChangeListener(onToggle);
        hide();
    }

    private void update() {
        if (isEnabled(ctx) && !onHome) show();
        else hide();
    }

    private void show() {
        if (view != null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        view = new TextView(ctx);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        view.setTypeface(ctx.getResources().getFont(R.font.firacode_regular));
        view.setTextColor(0xF2FFFFFF);
        view.setIncludeFontPadding(false);
        view.setSingleLine(true);
        view.setPadding(Math.round(8 * d), Math.round(4 * d), Math.round(8 * d), Math.round(4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x80000000);
        bg.setCornerRadius(8 * d);
        view.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.round(6 * d);   // tucked into the corner, above a broadcast's scoreboard
        lp.y = Math.round(4 * d);
        cpu.reset();
        rxPrev = -1;
        view.setText(line());
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {   // the service is going away
            view = null;
            return;
        }
        main.postDelayed(tick, 1000);
    }

    private void hide() {
        main.removeCallbacks(tick);
        if (view == null) return;
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
            // already gone with the service
        }
        view = null;
    }

    private String line() {
        StringBuilder s = new StringBuilder(CLOCK.format(LocalTime.now()));
        int c = cpu.sample();
        if (c >= 0) lastCpu = c;
        if (lastCpu >= 0) s.append(" · CPU ").append(lastCpu).append('%');
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.append(" · RAM ").append(mi.availMem / 1048576).append(" MB");
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = System.currentTimeMillis();
        if (rx >= 0 && tx >= 0) {
            if (rxPrev >= 0 && now > atPrev) {
                double secs = (now - atPrev) / 1000.0;
                s.append(" · ↓ ").append(speed((rx - rxPrev) / secs)).append(" ↑ ").append(speed((tx - txPrev) / secs));
            }
            rxPrev = rx;
            txPrev = tx;
            atPrev = now;
        }
        return s.toString();
    }

    /** "120 KB/s", "3.2 MB/s". */
    static String speed(double bytesPerSecond) {
        double kb = Math.max(0, bytesPerSecond) / 1024;
        return kb < 1024 ? Math.round(kb) + " KB/s" : String.format(Locale.ROOT, "%.1f MB/s", kb / 1024);
    }
}
