package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;

/**
 * Behind the prayer panel: this month's prayer times in two pages (1–15, 16–end), computed on the TV like
 * the panel, so it needs no network. Left/Right switch the halves (this month only); today's row is lit and
 * Fridays stand out. OK or Back closes it.
 */
final class PrayerDialog {
    private static final int ROWS = 16;   // the longest half; shorter halves are padded so the window keeps its height
    private static final String[] HEADER = {"Tanggal", "Hijriah", "Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    private static final float[] WEIGHTS = {2.3f, 3.1f, 1.15f, 1.15f, 1.15f, 1.15f, 1.15f, 1.15f, 1.15f};
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, ppd MMM", SchedulePanel.ID);

    private final Context ctx;
    private final double lat, lon, elevation;
    private final int hijriAdjust;
    private final String city;
    private final Typeface regular, medium;
    private final float d;
    private final Dialog dialog;
    private final TextView title;
    private final LinearLayout rows;
    private final YearMonth month;
    private int half;   // 0: days 1–15, 1: 16 to the end

    PrayerDialog(Context ctx, double lat, double lon, double elevation, int hijriAdjust, String city,
                 Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.lat = lat;
        this.lon = lon;
        this.elevation = elevation;
        this.hijriAdjust = hijriAdjust;
        this.city = city;
        this.regular = regular;
        this.medium = medium;
        d = ctx.getResources().getDisplayMetrics().density;
        LocalDate today = LocalDate.now();
        month = YearMonth.from(today);
        half = today.getDayOfMonth() <= 15 ? 0 : 1;

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(px(28), px(18), px(28), px(16));
        title = text(13, medium, 0xFFFFFFFF);
        title.setPadding(0, 0, 0, px(12));
        panel.addView(title);
        LinearLayout header = new LinearLayout(ctx);
        for (int i = 0; i < HEADER.length; i++) header.addView(cell(HEADER[i], i, regular, 0x8CFFFFFF));
        header.setPadding(px(8), 0, px(8), px(4));
        panel.addView(header);
        rows = new LinearLayout(ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rows);
        TextView status = text(11, regular, 0x8CFFFFFF);
        status.setPadding(0, px(10), 0, 0);
        status.setText("Metode Kemenag, dihitung di TV  ·  ◀ ▶ ganti halaman  ·  Back tutup");
        panel.addView(status);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(780), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        dialog.setOnKeyListener((di, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int want = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                if (want != half) {
                    half = want;
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
    }

    private void showPage() {
        int first = half == 0 ? 1 : 16, last = half == 0 ? 15 : month.lengthOfMonth();
        title.setText("JADWAL SHOLAT" + (city.isEmpty() ? "" : " · " + city.toUpperCase(SchedulePanel.ID)) + "     "
                + (half == 1 ? "◀  " : "   ") + month.getMonth().getDisplayName(TextStyle.FULL, SchedulePanel.ID) + " "
                + month.getYear() + " (" + first + "–" + last + ")" + (half == 0 ? "  ▶" : ""));
        rows.removeAllViews();
        LocalDate today = LocalDate.now();
        for (int day = first; day < first + ROWS; day++) {
            if (day > last) {   // keep the window's height
                rows.addView(row(new String[]{" "}, 0x00FFFFFF, false, false));
                continue;
            }
            LocalDate date = month.atDay(day);
            double tz = ZoneId.systemDefault().getRules().getOffset(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
                    .getTotalSeconds() / 3600.0;
            int[] m = Prayer.minutes(date, lat, lon, elevation, tz);
            String hijri = SchedulePanel.hijri(date, hijriAdjust);
            int year = hijri.lastIndexOf(' ', hijri.length() - 3);   // drop "1448 H"
            String[] cells = {DAY.format(date).replace(".", ""), year > 0 ? hijri.substring(0, year) : hijri,
                    hhmm(m[0] - Prayer.IMSAK_BEFORE_SUBUH_MIN), hhmm(m[0]), hhmm(m[Prayer.TERBIT]),
                    hhmm(m[1]), hhmm(m[2]), hhmm(m[3]), hhmm(m[4])};
            rows.addView(row(cells, 0xE6FFFFFF, date.equals(today), date.getDayOfWeek() == java.time.DayOfWeek.FRIDAY));
        }
    }

    private LinearLayout row(String[] cells, int color, boolean today, boolean friday) {
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(px(8), px(3), px(8), px(3));
        if (today) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2E7D4F);
            bg.setCornerRadius(px(6));
            row.setBackground(bg);
        }
        for (int i = 0; i < cells.length; i++) {
            boolean lit = today || (friday && i == 0);
            int c = today ? 0xFFFFFFFF : i == 1 ? 0x8CFFFFFF : i == 0 && !friday ? 0xB3FFFFFF : color;
            row.addView(cell(cells[i], i, lit ? medium : regular, c));
        }
        return row;
    }

    private TextView cell(String s, int column, Typeface tf, int color) {
        TextView v = text(12, tf, color);
        v.setText(s);
        v.setGravity(column < 2 ? Gravity.START : Gravity.END);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, WEIGHTS[column]));
        return v;
    }

    private static String hhmm(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d.%02d", minutes / 60, minutes % 60);
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
