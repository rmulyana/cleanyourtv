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
import java.time.format.TextStyle;

/**
 * Behind the Islamic calendar panel: one Gregorian month as a Monday-first grid, each day with its Hijri date
 * under it and marks for the sunnah fasts and Islamic holidays ({@link IslamicDay#marks}); today is lit.
 * Left/Right change the month, OK or Back closes. Computed on the TV, no network.
 */
final class CalendarDialog {
    private static final String[] DAYS = {"Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min"};
    private static final int WEEKS = 6;   // every month fits; shorter ones are padded so the window keeps its height

    private final Context ctx;
    private final int hijriAdjust;
    private final Typeface regular, medium;
    private final float d;
    private final Dialog dialog;
    private final TextView title, holidays;
    private final LinearLayout grid;
    private YearMonth month = YearMonth.now();

    CalendarDialog(Context ctx, int hijriAdjust, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.hijriAdjust = hijriAdjust;
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
        LinearLayout header = new LinearLayout(ctx);
        for (int i = 0; i < DAYS.length; i++) {
            TextView h = text(11, regular, i == 4 ? 0xFFCFFFCF : 0x8CFFFFFF);   // Friday stands out, as in the prayer table
            h.setText(DAYS[i]);
            h.setGravity(Gravity.CENTER);
            header.addView(h, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        header.setPadding(0, 0, 0, px(4));
        panel.addView(header);
        grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        panel.addView(grid);
        holidays = text(11, regular, 0xE6FFFFFF);
        holidays.setPadding(0, px(10), 0, 0);
        panel.addView(holidays);
        TextView legend = text(11, regular, 0x8CFFFFFF);
        legend.setPadding(0, px(4), 0, 0);
        legend.setText("● Puasa Senin/Kamis   ◆ Ayyamul Bidh   ■ Hari besar   ·   ◀ ▶ ganti bulan  ·  Back tutup");
        panel.addView(legend);

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
                month = month.plusMonths(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                showMonth();
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
        showMonth();
        dialog.show();
    }

    private void showMonth() {
        LocalDate first = month.atDay(1), last = month.atEndOfMonth(), today = LocalDate.now();
        String hFirst = hijriMonth(first), hLast = hijriMonth(last);
        title.setText("KALENDER · " + month.getMonth().getDisplayName(TextStyle.FULL, SchedulePanel.ID).toUpperCase(SchedulePanel.ID)
                + " " + month.getYear() + "     " + (hFirst.equals(hLast) ? hFirst : hFirst + " – " + hLast)
                + "     ◀ ▶");
        grid.removeAllViews();
        int lead = first.getDayOfWeek().getValue() - 1;   // Monday first
        StringBuilder names = new StringBuilder();
        for (int week = 0; week < WEEKS; week++) {
            LinearLayout row = new LinearLayout(ctx);
            for (int col = 0; col < 7; col++) {
                int day = week * 7 + col - lead + 1;
                LocalDate date = day >= 1 && day <= month.lengthOfMonth() ? month.atDay(day) : null;
                row.addView(cell(date, today), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                String name = date == null ? null : IslamicDay.holiday(date, hijriAdjust);
                if (name != null) names.append(names.length() == 0 ? "" : "   ·   ").append("■ ").append(name).append(' ').append(day);
            }
            grid.addView(row);
        }
        holidays.setText(names.length() == 0 ? "Tidak ada hari besar Islam bulan ini" : names);
    }

    /** "Jumadil Akhir 1448 H" for the date. */
    private String hijriMonth(LocalDate date) {
        String h = SchedulePanel.hijri(date, hijriAdjust);
        int space = h.indexOf(' ');
        return space < 0 ? h : h.substring(space + 1);
    }

    private LinearLayout cell(LocalDate date, LocalDate today) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(px(2), px(4), px(2), px(4));
        boolean isToday = date != null && date.equals(today);
        if (isToday) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF2E7D4F);
            bg.setCornerRadius(px(8));
            c.setBackground(bg);
        }
        TextView top = text(16, isToday ? medium : regular, isToday ? 0xFFFFFFFF : 0xE6FFFFFF);
        TextView bottom = text(10, regular, isToday ? 0xFFFFFFFF : 0x8CFFFFFF);
        if (date != null) {
            int[] h = IslamicDay.hijri(date, hijriAdjust);
            String marks = IslamicDay.marks(date, hijriAdjust);
            top.setText(date.getDayOfMonth() + (marks.isEmpty() ? "" : " " + marks));
            bottom.setText(h == null ? " " : String.valueOf(h[0]));
            if (!isToday && date.getDayOfWeek() == java.time.DayOfWeek.FRIDAY) top.setTextColor(0xFFCFFFCF);
        } else {
            top.setText(" ");
            bottom.setText(" ");
        }
        top.setGravity(Gravity.CENTER);
        bottom.setGravity(Gravity.CENTER);
        c.addView(top);
        c.addView(bottom);
        return c;
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
