package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Behind the weather panel: Open-Meteo's forecast in two pages, the next seven days and the next 24 hours
 * in 3-hour steps (Left/Right). One request feeds both, kept for 30 minutes. OK or Back closes it.
 */
final class WeatherDialog {
    private static final String TAG = "TvHome.Weather";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final String[] PAGES = {"7 hari", "24 jam"};
    private static final String[] DAY_HEADER = {"Hari", "Kondisi", "Suhu", "Hujan", "Curah", "Angin", "UV"};
    private static final float[] DAY_WEIGHTS = {2.4f, 2f, 2.2f, 1.2f, 1.5f, 1.7f, 0.8f};
    private static final String[] HOUR_HEADER = {"Jam", "Kondisi", "Suhu", "Terasa", "Lembap", "Hujan", "Curah", "Angin", "UV"};
    private static final float[] HOUR_WEIGHTS = {1.8f, 2.5f, 0.9f, 1.1f, 1.1f, 1f, 1.3f, 1.4f, 0.6f};
    private static final java.time.format.DateTimeFormatter HOUR =
            java.time.format.DateTimeFormatter.ofPattern("EEE HH.mm", SchedulePanel.ID);
    private static final int RAINY_PCT = 60;   // from here the rain chance is drawn in blue
    private static String cachedFor;
    private static JSONObject cached;
    private static long cachedAt;

    private final Context ctx;
    private final double lat, lon;
    private final String city;
    private final Typeface regular, medium;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView title, status;
    private final LinearLayout rows;
    private int page;

    WeatherDialog(Context ctx, double lat, double lon, String city, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.lat = lat;
        this.lon = lon;
        this.city = city;
        this.regular = regular;
        this.medium = medium;
        d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(px(28), px(18), px(28), px(16));
        title = text(13, medium, 0xFFFFFFFF);
        title.setPadding(0, 0, 0, px(12));
        panel.addView(title);
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rows);
        status = text(11, regular, 0x8CFFFFFF);
        status.setPadding(0, px(10), 0, 0);
        panel.addView(status);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(800), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        dialog.setOnKeyListener((di, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int want = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                if (want != page) {
                    page = want;
                    showPage();
                }
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                dialog.dismiss();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
    }

    void show() {
        showPage();
        dialog.show();
        String key = lat + "," + lon;
        if (cached != null && key.equals(cachedFor) && System.currentTimeMillis() - cachedAt < CACHE_MS) return;
        new Thread(() -> {
            JSONObject got = null;
            try {
                got = new JSONObject(SchedulePanel.get(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
                                + "precipitation_sum,wind_speed_10m_max,uv_index_max"
                                + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day,apparent_temperature,"
                                + "relative_humidity_2m,precipitation,wind_speed_10m,uv_index"
                                + "&forecast_days=7&forecast_hours=24&timezone=auto", lat, lon)));
            } catch (Exception e) {
                Log.w(TAG, "forecast: " + e);
            }
            JSONObject result = got;
            main.post(() -> {
                if (result != null) {
                    cached = result;
                    cachedFor = key;
                    cachedAt = System.currentTimeMillis();
                }
                if (!dialog.isShowing()) return;
                if (result != null) showPage();
                else if (cached == null) status.setText("Gagal memuat. Coba lagi nanti.");
            });
        }, "weather-forecast").start();
    }

    private void showPage() {
        SpannableStringBuilder t = new SpannableStringBuilder("CUACA" + (city.isEmpty() ? "" : " · " + city.toUpperCase(SchedulePanel.ID)) + "     ");
        for (int i = 0; i < PAGES.length; i++) {
            if (i > 0) t.append("  ·  ");
            int start = t.length();
            t.append(PAGES[i]);
            t.setSpan(new ForegroundColorSpan(i == page ? 0xFFFFFFFF : 0x66FFFFFF), start, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(t);
        rows.removeAllViews();
        JSONObject f = cached;
        if (f == null) {
            status.setText("Memuat…");
            return;
        }
        if (page == 0) days(f.optJSONObject("daily"));
        else hours(f.optJSONObject("hourly"));
        status.setText("Open-Meteo  ·  ◀ ▶ ganti halaman  ·  Back tutup");
    }

    private void days(JSONObject daily) {
        rows.addView(row(DAY_HEADER, DAY_WEIGHTS, 0x8CFFFFFF, false, -1, -1));
        if (daily == null) return;
        JSONArray time = daily.optJSONArray("time");
        LocalDate today = LocalDate.now();
        for (int i = 0; time != null && i < time.length(); i++) {
            LocalDate date;
            try {
                date = LocalDate.parse(time.optString(i));
            } catch (Exception e) {
                continue;
            }
            int rain = (int) Math.round(daily.optJSONArray("precipitation_probability_max").optDouble(i, 0));
            String[] cells = {SchedulePanel.DAY.format(date).replace(".", ""),
                    withIcon(daily.optJSONArray("weather_code").optInt(i, -1), true),
                    deg(daily.optJSONArray("temperature_2m_min").optDouble(i)) + " – " + deg(daily.optJSONArray("temperature_2m_max").optDouble(i)),
                    rain + "%",
                    String.format(Locale.ROOT, "%.1f mm", daily.optJSONArray("precipitation_sum").optDouble(i, 0)),
                    Math.round(daily.optJSONArray("wind_speed_10m_max").optDouble(i, 0)) + " km/j",
                    String.valueOf(Math.round(daily.optJSONArray("uv_index_max").optDouble(i, 0)))};
            rows.addView(row(cells, DAY_WEIGHTS, 0xE6FFFFFF, date.equals(today), 3, rain));
        }
    }

    private void hours(JSONObject hourly) {
        rows.addView(row(HOUR_HEADER, HOUR_WEIGHTS, 0x8CFFFFFF, false, -1, -1));
        if (hourly == null) return;
        JSONArray time = hourly.optJSONArray("time");
        for (int i = 0; time != null && i < time.length(); i += 3) {
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(time.optString(i));
            } catch (Exception e) {
                continue;
            }
            int rain = (int) Math.round(hourly.optJSONArray("precipitation_probability").optDouble(i, 0));
            String[] cells = {HOUR.format(at),
                    withIcon(hourly.optJSONArray("weather_code").optInt(i, -1),
                            hourly.optJSONArray("is_day") == null || hourly.optJSONArray("is_day").optInt(i, 1) == 1),
                    deg(hourly.optJSONArray("temperature_2m").optDouble(i)),
                    deg(num(hourly, "apparent_temperature", i)),
                    Math.round(num(hourly, "relative_humidity_2m", i)) + "%",
                    rain + "%",
                    String.format(Locale.ROOT, "%.1f mm", num(hourly, "precipitation", i)),
                    Math.round(num(hourly, "wind_speed_10m", i)) + " km/j",
                    String.valueOf(Math.round(num(hourly, "uv_index", i)))};
            rows.addView(row(cells, HOUR_WEIGHTS, 0xE6FFFFFF, i == 0, 5, rain));
        }
    }

    private static String withIcon(int code, boolean day) {
        String icon = SchedulePanel.icon(code, day);
        return (icon.isEmpty() ? "" : icon + "  ") + SchedulePanel.describe(code);
    }

    /** A number from an hourly series, 0 when the series or the value is missing. */
    private static double num(JSONObject series, String name, int i) {
        JSONArray a = series.optJSONArray(name);
        return a == null ? 0 : a.optDouble(i, 0);
    }

    private static String deg(double c) {
        return Double.isNaN(c) ? "–" : Math.round(c) + "°";
    }

    /** A table row; {@code rainColumn} turns blue from {@link #RAINY_PCT}. */
    private LinearLayout row(String[] cells, float[] weights, int color, boolean lit, int rainColumn, int rain) {
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(px(8), px(5), px(8), px(5));
        if (lit) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x33FFFFFF);
            bg.setCornerRadius(px(6));
            row.setBackground(bg);
        }
        for (int i = 0; i < cells.length; i++) {
            int c = i == rainColumn && rain >= RAINY_PCT ? 0xFF6EC1FF : lit ? 0xFFFFFFFF : color;
            TextView v = text(12, lit ? medium : regular, c);
            v.setText(cells[i]);
            v.setGravity(i < 2 ? Gravity.START : Gravity.END);
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights[i]));
        }
        return row;
    }

    private TextView text(int sp, Typeface tf, int color) {
        TextView v = new TextView(ctx);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(tf);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false);
        return v;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }
}
