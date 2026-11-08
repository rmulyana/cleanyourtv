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
 * ↓ 3.2 MB/s ↑ 120 KB/s", refreshed every second, with a 15 s notice under it at a followed club's kick-off and
 * at each prayer time. Drawn by {@link HomeKeyService} as an accessibility
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
    private WindowManager.LayoutParams lp;
    private boolean onHome = true;
    private long rxPrev = -1, txPrev = -1, atPrev;
    private int lastCpu = -1;
    private static final long NOTICE_MS = 15_000;
    private long checkedTo;            // kick-offs and prayer times up to here are announced (or were before we looked)
    private final double lat, lon, elevation;   // config "schedule"; NaN: no prayer notices
    private java.time.LocalDate prayerDay;
    private int[] prayerMin;
    private final java.util.Set<String> followed = new java.util.HashSet<>();   // config "team_colors" keys
    private final java.util.List<SchedulePanel.Match> kickoffs = new java.util.ArrayList<>();
    private long kickoffUntil;
    private String prayer = "";
    private long prayerUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (view == null) return;
            setLine(line());
            main.postDelayed(this, 1000);
        }
    };

    /**
     * Shows the text in a window exactly as wide as its longest line. Android lays out a wrap-content window at
     * no more than its preferred dialog width (640 px here) and lets text wrap or clip inside that, so the
     * width is set by hand whenever it changes.
     */
    private void setLine(String text) {
        view.setText(text);
        float widest = 0;
        for (String l : text.split("\n")) widest = Math.max(widest, view.getPaint().measureText(l));
        int width = (int) Math.ceil(widest) + view.getPaddingLeft() + view.getPaddingRight() + 2;
        if (width != lp.width) {
            lp.width = width;
            try {
                wm.updateViewLayout(view, lp);
            } catch (RuntimeException ignored) {
                // the service is going away
            }
        }
    }

    /**
     * Up to two lines, each shown for 15 s from its last event: the kick-offs ("⚽ Man Utd v Aston Villa
     * dimulai", or for several "⚽ 2 laga dimulai: Persib · Inter") and the prayer time ("🕌 Waktunya sholat Isya");
     * else "".
     */
    private String notice(long now) {
        for (SchedulePanel.Match m : SchedulePanel.matches()) {
            if (m.at > checkedTo && m.at <= now) {
                if (now >= kickoffUntil) kickoffs.clear();
                kickoffs.add(m);
                kickoffUntil = now + NOTICE_MS;
            }
        }
        if (!Double.isNaN(lat)) {
            java.time.ZonedDateTime today = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault());
            if (!today.toLocalDate().equals(prayerDay)) {
                prayerDay = today.toLocalDate();
                prayerMin = Prayer.minutes(prayerDay, lat, lon, elevation, today.getOffset().getTotalSeconds() / 3600.0);
            }
            long midnight = today.toInstant().toEpochMilli();
            for (int i = 0; i < Prayer.NAMES.length; i++) {
                long at = midnight + prayerMin[i] * 60_000L;
                if (at > checkedTo && at <= now) {
                    prayer = "\uD83D\uDD4C Waktunya sholat " + Prayer.NAMES[i];
                    prayerUntil = now + NOTICE_MS;
                }
            }
        }
        checkedTo = now;
        StringBuilder out = new StringBuilder();
        if (now < kickoffUntil) {
            java.util.Set<String> fixtures = new java.util.LinkedHashSet<>();   // a derby of two followed clubs is listed twice
            for (SchedulePanel.Match m : kickoffs) fixtures.add(SchedulePanel.fixture(m));
            out.append(kickoffNotice(fixtures, followed));
        }
        if (now < prayerUntil) out.append(out.length() > 0 ? "\n" : "").append(prayer);
        return out.toString();
    }

    /**
     * "⚽ Man Utd v Aston Villa dimulai" for one kick-off; for several, one line with each match's followed
     * clubs ("⚽ 4 laga dimulai: Persib · Inter · Man Utd · Indonesia"), the home side when neither is followed.
     */
    static String kickoffNotice(java.util.Collection<String> fixtures, java.util.Set<String> followed) {
        if (fixtures.size() == 1) return "\u26BD " + fixtures.iterator().next() + " dimulai";
        java.util.Set<String> clubs = new java.util.LinkedHashSet<>();
        for (String f : fixtures) {
            String[] sides = f.split(" v ", 2);
            int before = clubs.size();
            for (String side : sides) if (followed.contains(side)) clubs.add(side);
            if (clubs.size() == before) clubs.add(sides[0]);
        }
        return "\u26BD " + fixtures.size() + " laga dimulai: " + String.join(" \u00B7 ", clubs);
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener onToggle = (p, key) -> {
        if (KEY_ON.equals(key)) update();
    };

    /** {@code ctx}: the accessibility service, whose window manager may add accessibility overlays. */
    StatsOverlay(Context ctx) {
        this.ctx = ctx;
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        prefs(ctx).registerOnSharedPreferenceChangeListener(onToggle);
        org.json.JSONObject sched = Config.load(ctx).schedule;   // the home panel's city, for prayer-time notices
        org.json.JSONObject tc = sched == null ? null : sched.optJSONObject("team_colors");
        if (tc != null) for (java.util.Iterator<String> it = tc.keys(); it.hasNext(); ) followed.add(it.next());
        lat = sched == null ? Double.NaN : sched.optDouble("lat", Double.NaN);
        lon = sched == null ? Double.NaN : sched.optDouble("lon", Double.NaN);
        elevation = sched == null ? 0 : sched.optDouble("elevation", 0);
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
        view.setMaxLines(3);   // the stats, and for 15 s a kick-off line and a prayer line under them
        view.setPadding(Math.round(8 * d), Math.round(4 * d), Math.round(8 * d), Math.round(4 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x80000000);
        bg.setCornerRadius(8 * d);
        view.setBackground(bg);
        lp = new WindowManager.LayoutParams(
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
        checkedTo = System.currentTimeMillis();
        String first = line();
        view.setText(first);
        float widest = 0;
        for (String l : first.split("\n")) widest = Math.max(widest, view.getPaint().measureText(l));
        lp.width = (int) Math.ceil(widest) + view.getPaddingLeft() + view.getPaddingRight() + 2;
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
        // Every field is there from the first second, a dash until it has a value, so the line never lengthens.
        s.append(lastCpu >= 0 ? String.format(Locale.ROOT, " · CPU %3d%%", lastCpu) : " · CPU   -%");
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        s.append(String.format(Locale.ROOT, " · RAM %4d MB", mi.availMem / 1048576));
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = System.currentTimeMillis();
        if (rx >= 0 && tx >= 0) {
            if (rxPrev >= 0 && now > atPrev) {
                double secs = (now - atPrev) / 1000.0;
                s.append(" · ↓ ").append(speed((rx - rxPrev) / secs)).append(" ↑ ").append(speed((tx - txPrev) / secs));
            } else {
                s.append(" · ↓    - KB/s ↑    - KB/s");
            }
            rxPrev = rx;
            txPrev = tx;
            atPrev = now;
        }
        String n = notice(now);
        return n.isEmpty() ? s.toString() : s + "\n" + n;
    }

    /**
     * " 120 KB/s", " 3.2 MB/s": the number padded to four places, so the line keeps its width as speeds change
     * (Fira Code is monospace) and the overlay does not jump or wrap.
     */
    static String speed(double bytesPerSecond) {
        double kb = Math.max(0, bytesPerSecond) / 1024;
        return kb < 1023.5 ? String.format(Locale.ROOT, "%4d KB/s", Math.round(kb))
                : String.format(Locale.ROOT, "%4.1f MB/s", kb / 1024);
    }
}
