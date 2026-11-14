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
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;

/**
 * Behind the Islamic calendar panel: one Gregorian month as a Monday-first grid, each day with its Hijri date
 * under it and marks for the sunnah fasts and Islamic holidays ({@link IslamicDay#marks}). Like a phone
 * calendar, the arrows move a selected day (a week with Up/Down), the month follows it, and the selected
 * day's events show under the grid. Today is lit green, the selection ringed. OK or Back closes.
 * Computed on the TV, no network.
 */
final class CalendarDialog {
    private static final String[] DAYS = {"Sen", "Sel", "Rab", "Kam", "Jum", "Sab", "Min"};
    private static final int WEEKS = 6;   // every month fits; shorter ones are padded so the window keeps its height
    private static final DateTimeFormatter LONG = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", SchedulePanel.ID);

    private final Context ctx;
    private final int hijriAdjust;
    private final Typeface regular, medium;
    private final float d;
    private final Dialog dialog;
    private final TextView title, detailDate, detailEvents;
    private final LinearLayout grid;
    private final LinearLayout[] cells = new LinearLayout[WEEKS * 7];
    private final LocalDate today = LocalDate.now();
    private LocalDate selected = today;
    private YearMonth shown;   // the month the grid holds
    private int lead;          // empty cells before day 1

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
        detailDate = text(12, medium, 0xFFFFFFFF);
        detailDate.setPadding(0, px(12), 0, 0);
        panel.addView(detailDate);
        detailEvents = text(12, regular, 0xE6FFFFFF);
        detailEvents.setPadding(0, px(4), 0, 0);
        panel.addView(detailEvents);
        TextView legend = text(11, regular, 0x8CFFFFFF);
        legend.setPadding(0, px(10), 0, 0);
        legend.setText("● Puasa Senin/Kamis   ◆ Ayyamul Bidh   ■ Hari besar   ·   ◀ ▶ ▲ ▼ pilih tanggal  ·  Back tutup");
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
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_LEFT: select(selected.minusDays(1)); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: select(selected.plusDays(1)); return true;
                case KeyEvent.KEYCODE_DPAD_UP: select(selected.minusWeeks(1)); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: select(selected.plusWeeks(1)); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    dialog.dismiss();
                    return true;
                default:
                    return false;
            }
        });
    }

    void show() {
        select(today);
        dialog.show();
    }

    /** Moves the selection; the grid is rebuilt only when the month changes, else just the two cells restyle. */
    private void select(LocalDate date) {
        LocalDate before = selected;
        selected = date;
        if (!YearMonth.from(date).equals(shown)) {
            buildMonth(YearMonth.from(date));
        } else {
            style(before);
            style(date);
        }
        detailDate.setText(LONG.format(date) + "  ·  " + SchedulePanel.hijri(date, hijriAdjust));
        List<String> events = IslamicDay.events(date, hijriAdjust);
        detailEvents.setText(events.isEmpty() ? "Tidak ada acara" : String.join("  ·  ", events));
        detailEvents.setTextColor(events.isEmpty() ? 0x8CFFFFFF : 0xFFCFFFCF);
    }

    private void buildMonth(YearMonth month) {
        shown = month;
        LocalDate first = month.atDay(1), last = month.atEndOfMonth();
        String hFirst = hijriMonth(first), hLast = hijriMonth(last);
        title.setText("KALENDER · " + month.getMonth().getDisplayName(TextStyle.FULL, SchedulePanel.ID).toUpperCase(SchedulePanel.ID)
                + " " + month.getYear() + "     " + (hFirst.equals(hLast) ? hFirst : hFirst + " – " + hLast));
        grid.removeAllViews();
        lead = first.getDayOfWeek().getValue() - 1;   // Monday first
        for (int week = 0; week < WEEKS; week++) {
            LinearLayout row = new LinearLayout(ctx);
            for (int col = 0; col < 7; col++) {
                int i = week * 7 + col, day = i - lead + 1;
                cells[i] = cell(day >= 1 && day <= month.lengthOfMonth() ? month.atDay(day) : null);
                row.addView(cells[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            grid.addView(row);
        }
        for (int day = 1; day <= month.lengthOfMonth(); day++) style(month.atDay(day));
    }

    /** "Jumadil Akhir 1448 H" for the date. */
    private String hijriMonth(LocalDate date) {
        String h = SchedulePanel.hijri(date, hijriAdjust);
        int space = h.indexOf(' ');
        return space < 0 ? h : h.substring(space + 1);
    }

    private LinearLayout cell(LocalDate date) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(px(2), px(4), px(2), px(4));
        TextView top = text(16, regular, 0xE6FFFFFF);
        TextView bottom = text(10, regular, 0x8CFFFFFF);
        if (date != null) {
            int[] h = IslamicDay.hijri(date, hijriAdjust);
            String marks = IslamicDay.marks(date, hijriAdjust);
            top.setText(date.getDayOfMonth() + (marks.isEmpty() ? "" : " " + marks));
            bottom.setText(h == null ? " " : String.valueOf(h[0]));
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

    /** Today: green fill; the selection: a white ring; Fridays: light green numbers. */
    private void style(LocalDate date) {
        if (date == null || !YearMonth.from(date).equals(shown)) return;
        LinearLayout c = cells[lead + date.getDayOfMonth() - 1];
        boolean isToday = date.equals(today), isSelected = date.equals(selected);
        GradientDrawable bg = null;
        if (isToday || isSelected) {
            bg = new GradientDrawable();
            bg.setCornerRadius(px(8));
            bg.setColor(isToday ? 0xFF2E7D4F : 0x26FFFFFF);
            if (isSelected) bg.setStroke(px(2), 0xFFFFFFFF);
        }
        c.setBackground(bg);
        TextView top = (TextView) c.getChildAt(0), bottom = (TextView) c.getChildAt(1);
        boolean friday = date.getDayOfWeek() == java.time.DayOfWeek.FRIDAY;
        top.setTypeface(isToday || isSelected ? medium : regular);
        top.setTextColor(isToday || isSelected ? 0xFFFFFFFF : friday ? 0xFFCFFFCF : 0xE6FFFFFF);
        bottom.setTextColor(isToday || isSelected ? 0xFFFFFFFF : 0x8CFFFFFF);
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
