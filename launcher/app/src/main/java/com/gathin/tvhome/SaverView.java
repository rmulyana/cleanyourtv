package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver's face, shared by the idle screensaver ({@link ClockDream}) and the one Home's button opens
 * ({@link ScreensaverActivity}): a big clock inside a ring that fills with the seconds of the minute, then the
 * date with the Hijri date, the weather (Open-Meteo, every 30 minutes) and the next prayer with its
 * countdown, on a dark grey to dark blue gradient. The whole face moves a few pixels every five minutes,
 * too little to notice, so nothing sits still for hours.
 */
final class SaverView extends FrameLayout {
    private static final String TAG = "TvHome.Saver";
    private static final long SHIFT_MS = 5 * 60_000L, WEATHER_MS = 30 * 60_000L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", SchedulePanel.ID);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final float d;
    private final LinearLayout face;
    private final Ring ring;
    private final TextView date, weather, prayer, nowPlaying, note;
    private final double lat, lon, elevation;
    private final int hijriAdjust;
    private String weatherLine = "";
    private long weatherAt, shiftedAt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ring.invalidate();
            long now = System.currentTimeMillis();
            if (now / 1000 % 60 == 0 || date.getText().length() == 0) updateLines();
            if (now - shiftedAt >= SHIFT_MS) shift();
            if (now - weatherAt >= WEATHER_MS) fetchWeather();
            main.postDelayed(this, 1000 - now % 1000);   // on the second
        }
    };
    private final Runnable hideNote = this::fadeNote;

    SaverView(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A2C31, 0xFF141B2B, 0xFF0B1730});
        setBackground(bg);

        JSONObject s = Config.load(ctx).schedule;
        lat = s == null ? Double.NaN : s.optDouble("lat", Double.NaN);
        lon = s == null ? Double.NaN : s.optDouble("lon", Double.NaN);
        elevation = s == null ? 0 : s.optDouble("elevation", 0);
        hijriAdjust = s == null ? 0 : s.optInt("hijri_adjust", 0);

        Typeface light = ctx.getResources().getFont(R.font.firacode_light);
        Typeface regular = ctx.getResources().getFont(R.font.firacode_regular);
        face = new LinearLayout(ctx);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER_HORIZONTAL);
        ring = new Ring(ctx, light);
        int size = px(300);
        face.addView(ring, new LinearLayout.LayoutParams(size, size));
        date = line(ctx, regular, 17, 0xE6FFFFFF, 28);
        weather = line(ctx, regular, 15, 0xB3FFFFFF, 10);
        prayer = line(ctx, regular, 15, 0xB3FFFFFF, 8);
        nowPlaying = line(ctx, regular, 13, 0x80FFFFFF, 14);
        nowPlaying.setVisibility(GONE);
        // Wider than the ring: a vertical LinearLayout would otherwise clip each line to the ring's width.
        for (TextView v : new TextView[]{date, weather, prayer, nowPlaying}) {
            face.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        addView(face, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Under the lines, in the same column, so it never sits on top of them; a space keeps its height.
        note = line(ctx, regular, 14, 0xCCFFFFFF, 18);
        note.setText(" ");
        note.setAlpha(0f);
        face.addView(note, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    void start() {
        shiftedAt = System.currentTimeMillis();
        updateLines();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        main.removeCallbacks(tick);
        main.removeCallbacks(hideNote);
    }

    /** A short message at the bottom for three seconds, e.g. the ambient sound's name. */
    /** The music playing, e.g. "♪ Japanese Lofi Hiphop", small under the prayer line; "" hides it. */
    void setNowPlaying(String text) {
        nowPlaying.setText(text);
        nowPlaying.setVisibility(text.isEmpty() ? GONE : VISIBLE);
    }

    void showNote(String text) {
        note.setText(text);
        note.animate().cancel();
        note.setAlpha(1f);
        main.removeCallbacks(hideNote);
        main.postDelayed(hideNote, 3000);
    }

    private void fadeNote() {
        note.animate().alpha(0f).setDuration(400).start();
    }

    private void shift() {
        shiftedAt = System.currentTimeMillis();
        int r = px(10);
        face.animate().translationX(random.nextInt(2 * r + 1) - r).translationY(random.nextInt(2 * r + 1) - r)
                .setDuration(2000).start();
    }

    private void updateLines() {
        LocalDate today = LocalDate.now();
        String hijri = SchedulePanel.hijri(today, hijriAdjust);
        date.setText(DATE.format(today) + (hijri.isEmpty() ? "" : "  ·  " + hijri));
        weather.setText(weatherLine);
        weather.setVisibility(weatherLine.isEmpty() ? GONE : VISIBLE);
        if (Double.isNaN(lat)) {
            prayer.setVisibility(GONE);
            return;
        }
        double tz = ZoneId.systemDefault().getRules().getOffset(Instant.now()).getTotalSeconds() / 3600.0;
        int[] m = Prayer.minutes(today, lat, lon, elevation, tz);
        LocalTime now = LocalTime.now();
        int nowMin = now.getHour() * 60 + now.getMinute(), next = -1;
        for (int i = Prayer.NAMES.length - 1; i >= 0; i--) if (m[i] > nowMin) next = i;
        int left, at;
        if (next >= 0) {
            left = m[next] - nowMin;
            at = m[next];
        } else {   // after Isya: tomorrow's Subuh
            next = 0;
            at = Prayer.minutes(today.plusDays(1), lat, lon, elevation, tz)[0];
            left = at + 24 * 60 - nowMin;
        }
        prayer.setText(Prayer.NAMES[next] + " " + String.format(Locale.ROOT, "%02d.%02d", at / 60, at % 60)
                + "  ·  " + SchedulePanel.countdown(Prayer.NAMES[next], left).replaceFirst("^\\S+ ", ""));
        prayer.setTextColor(left <= 15 ? 0xFF7FD69B : 0xB3FFFFFF);
    }

    private void fetchWeather() {
        weatherAt = System.currentTimeMillis();
        if (Double.isNaN(lat)) return;
        double la = lat, lo = lon;
        new Thread(() -> {
            String text = "";
            try {
                JSONObject f = new JSONObject(SchedulePanel.get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code,is_day&daily=precipitation_probability_max"
                                + "&forecast_days=1&timezone=auto", la, lo)));
                JSONObject cur = f.getJSONObject("current");
                int code = cur.optInt("weather_code", -1);
                String icon = SchedulePanel.icon(code, cur.optInt("is_day", 1) == 1);
                int rain = (int) Math.round(f.getJSONObject("daily").getJSONArray("precipitation_probability_max").optDouble(0, 0));
                text = (icon.isEmpty() ? "" : icon + "  ") + Math.round(cur.optDouble("temperature_2m")) + "°  "
                        + SchedulePanel.describe(code) + "  ·  Hujan " + rain + "%";
            } catch (Exception e) {
                Log.w(TAG, "weather: " + e);
                weatherAt -= WEATHER_MS - 5 * 60_000L;   // try again in five minutes
            }
            String result = text;
            main.post(() -> {
                if (!result.isEmpty()) weatherLine = result;
                updateLines();
            });
        }, "saver-weather").start();
    }

    private TextView line(Context ctx, Typeface tf, int sp, int color, int topDp) {
        TextView v = new TextView(ctx);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(tf);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setPadding(0, px(topDp), 0, 0);
        return v;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }

    /** The clock in a thin ring that fills with the seconds of the minute; the colon blinks each second. */
    private static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG),
                dot = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float d;

        Ring(Context ctx, Typeface tf) {
            super(ctx);
            d = ctx.getResources().getDisplayMetrics().density;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(3 * d);
            track.setColor(0x1FFFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(3 * d);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(0xCCFFFFFF);
            dot.setColor(0xFFFFFFFF);
            text.setTypeface(tf);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 64, ctx.getResources().getDisplayMetrics()));
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = 8 * d, w = getWidth(), h = getHeight();
            box.set(pad, pad, w - pad, h - pad);
            c.drawOval(box, track);
            LocalTime now = LocalTime.now();
            int sec = now.getSecond();
            float sweep = sec == 0 ? 0 : sec * 6f;
            c.drawArc(box, -90, sweep, false, arc);
            double a = Math.toRadians(sweep - 90);
            float r = box.width() / 2;
            c.drawCircle((float) (w / 2 + r * Math.cos(a)), (float) (h / 2 + r * Math.sin(a)), 5 * d, dot);
            String hh = String.format(Locale.ROOT, "%02d", now.getHour()), mm = String.format(Locale.ROOT, "%02d", now.getMinute());
            float y = h / 2 - (text.ascent() + text.descent()) / 2;
            float colonW = text.measureText(":"), half = (text.measureText(hh) + colonW + text.measureText(mm)) / 2;
            text.setTextAlign(Paint.Align.LEFT);
            float x = w / 2 - half;
            c.drawText(hh, x, y, text);
            x += text.measureText(hh);
            if (sec % 2 == 0) c.drawText(":", x, y, text);
            x += colonW;
            c.drawText(mm, x, y, text);
        }
    }
}
