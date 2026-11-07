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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The full league table behind the Klasemen panel: one league per page (Left/Right), the followed
 * clubs' rows filled with their "team_colors". OK or Back closes it.
 */
final class StandingsDialog {
    private static final String TAG = "TvHome.Standings";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    /** source → {fetched at (Long), rows (List<String[]>)}; shared by every open of the dialog. */
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    // Column weights: #, club, played, won, drawn, lost, goal difference, points, then the last five results.
    private static final float[] WEIGHTS = {0.7f, 4f, 1f, 0.8f, 0.8f, 0.8f, 1.1f, 1.1f};
    private static final float FORM_WEIGHT = 3.2f;
    private static final String[] HEADER = {"#", "Tim", "Main", "M", "S", "K", "SG", "Poin"};

    private final Context ctx;
    private final List<JSONObject> clubs;   // standings config entries, in the panel's order
    private final JSONObject teamColors;
    private final Typeface regular, medium;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView title, status;
    private final LinearLayout rows;
    private int page;

    StandingsDialog(Context ctx, List<JSONObject> clubs, JSONObject teamColors, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.clubs = clubs;
        this.teamColors = teamColors;
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
        panel.addView(row(HEADER, "", 0x8CFFFFFF, 0, 0));
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
            w.setLayout(px(780), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        dialog.setOnKeyListener((di, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int n = clubs.size();
                page = (page + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                showPage();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                dialog.dismiss();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;   // nothing to focus inside
        });
    }

    void show() {
        if (clubs.isEmpty()) return;
        showPage();
        dialog.show();
    }

    private void showPage() {
        JSONObject club = clubs.get(page);
        String source = club.optString("source");
        SpannableStringBuilder t = new SpannableStringBuilder("KLASEMEN   ");
        for (int i = 0; i < clubs.size(); i++) {
            if (i > 0) t.append("  ·  ");
            int start = t.length();
            t.append(clubs.get(i).optString("league"));
            t.setSpan(new ForegroundColorSpan(i == page ? 0xFFFFFFFF : 0x66FFFFFF), start, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(t);
        Object[] hit = CACHE.get(source);
        @SuppressWarnings("unchecked")
        List<String[]> table = hit == null ? null : (List<String[]>) hit[1];
        fill(table);
        if (table != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return;
        if (table == null) status.setText("Memuat…");
        int asked = page;
        new Thread(() -> {
            List<String[]> got = null;
            try {
                got = table(source);
            } catch (Exception e) {
                Log.w(TAG, source + ": " + e);
            }
            List<String[]> result = got;
            main.post(() -> {
                if (result != null) CACHE.put(source, new Object[]{System.currentTimeMillis(), result});
                if (page != asked || !dialog.isShowing()) return;
                if (result != null) fill(result);
                else if (table == null) status.setText("Gagal memuat. Coba lagi nanti.");
            });
        }, "standings-table").start();
    }

    private void fill(List<String[]> table) {
        rows.removeAllViews();
        if (table == null) return;
        // Every followed club that plays in this table, by team id (the third part of "espn:eng.1:360").
        Map<String, int[]> mine = new HashMap<>();
        String league = leagueOf(clubs.get(page).optString("source"));
        for (JSONObject c : clubs) {
            String[] p = c.optString("source").split(":");
            if (p.length != 3 || !leagueOf(c.optString("source")).equals(league)) continue;
            int[] colors = SchedulePanel.teamColor(teamColors, c.optString("name"));
            mine.put(p[2], colors != null ? colors : new int[]{0xFF3A3A40, 0xFFFFFFFF});
        }
        for (String[] r : table) {
            int[] c = mine.get(r[8]);
            rows.addView(row(new String[]{r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7]}, r[9],
                    c != null ? c[1] : 0xE6FFFFFF, c != null ? c[0] : 0, 1));
        }
        String source = clubs.get(page).optString("source");
        status.setText(source.startsWith("sportsdb:")
                ? "Liga 1: sumber gratis hanya memberi 5 besar  ·  ◀ ▶ ganti liga  ·  Back tutup"
                : "◀ ▶ ganti liga  ·  Back tutup");
    }

    /** "espn:eng.1:360" → "espn:eng.1", so clubs in one league share a page's highlights. */
    private static String leagueOf(String source) {
        int i = source.lastIndexOf(':');
        return i < 0 ? source : source.substring(0, i);
    }

    /** A table row; {@code form} is up to five of W/D/L, oldest first, drawn as coloured chips ("" = none, null = header). */
    private LinearLayout row(String[] cells, String form, int color, int fill, int pad) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(px(8), px(2 + pad), px(8), px(2 + pad));
        if (fill != 0) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(fill);
            bg.setCornerRadius(px(6));
            row.setBackground(bg);
        }
        for (int i = 0; i < cells.length; i++) {
            TextView v = text(11, fill != 0 ? medium : regular, color);
            v.setText(cells[i]);
            v.setGravity(i == 1 ? Gravity.START : Gravity.END);
            if (i == 1) v.setPadding(px(10), 0, 0, 0);
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, WEIGHTS[i]));
        }
        LinearLayout chips = new LinearLayout(ctx);
        chips.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (cells == HEADER) {
            TextView h = text(11, regular, color);
            h.setText("5 laga terakhir");
            chips.addView(h);
        }
        for (int i = 0; i < form.length(); i++) {
            char f = form.charAt(i);
            TextView chip = text(10, medium, 0xFFFFFFFF);
            chip.setText(String.valueOf(f));
            chip.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(f == 'W' ? 0xFF2E9E5B : f == 'L' ? 0xFFD64545 : 0xFF7A7A80);
            bg.setCornerRadius(px(3));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(16), px(15));
            lp.setMarginStart(px(4));
            chips.addView(chip, lp);
        }
        row.addView(chips, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, FORM_WEIGHT));
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

    /**
     * Rows of {rank, club, played, won, drawn, lost, goal difference, points, team id, last five results
     * (W/D/L, oldest first)}, by rank, from a
     * standings source ("espn:&lt;league&gt;:&lt;team id&gt;" or "sportsdb:&lt;league id&gt;:&lt;team id&gt;").
     */
    static List<String[]> table(String source) throws Exception {
        String[] p = source.split(":");
        List<String[]> out = new ArrayList<>();
        if (p.length == 3 && p[0].equals("espn")) {
            Map<String, String> form = espnForm(p[1]);
            JSONArray children = new JSONObject(SchedulePanel.get("https://site.api.espn.com/apis/v2/sports/soccer/" + p[1]
                    + "/standings")).getJSONArray("children");
            for (int c = 0; c < children.length(); c++) {
                JSONArray entries = children.getJSONObject(c).getJSONObject("standings").getJSONArray("entries");
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i), team = e.getJSONObject("team");
                    Map<String, String> s = new HashMap<>();
                    JSONArray stats = e.getJSONArray("stats");
                    for (int k = 0; k < stats.length(); k++) {
                        JSONObject st = stats.getJSONObject(k);
                        s.put(st.optString("name"), st.optString("displayValue"));
                    }
                    out.add(new String[]{s.getOrDefault("rank", ""), team.optString("shortDisplayName", team.optString("displayName")),
                            s.getOrDefault("gamesPlayed", ""), s.getOrDefault("wins", ""), s.getOrDefault("ties", ""),
                            s.getOrDefault("losses", ""), s.getOrDefault("pointDifferential", ""), s.getOrDefault("points", ""),
                            team.optString("id"), form.getOrDefault(team.optString("id"), "")});
                }
            }
        } else if (p.length == 3 && p[0].equals("sportsdb")) {
            JSONArray t = new JSONObject(SchedulePanel.get("https://www.thesportsdb.com/api/v1/json/123/lookuptable.php?l=" + p[1]))
                    .getJSONArray("table");
            for (int i = 0; i < t.length(); i++) {
                JSONObject r = t.getJSONObject(i);
                String gd = r.optString("intGoalDifference");
                out.add(new String[]{r.optString("intRank"), r.optString("strTeam"), r.optString("intPlayed"),
                        r.optString("intWin"), r.optString("intDraw"), r.optString("intLoss"),
                        gd.startsWith("-") || gd.equals("0") ? gd : "+" + gd, r.optString("intPoints"), r.optString("idTeam"),
                        // strForm is newest first ("LWWLW": lost the last match); the table shows oldest first.
                        new StringBuilder(r.optString("strForm").replaceAll("[^WDL]", "")).reverse().toString()});
            }
        } else {
            throw new IllegalArgumentException("unknown source " + source);
        }
        out.sort((a, b) -> Integer.compare(rank(a[0]), rank(b[0])));
        return out;
    }

    /**
     * Team id → its last five league results (W/D/L, oldest first), from ESPN's scoreboard for this month and
     * the two before (one request each; a range query is refused). Empty when it cannot be read.
     */
    private static Map<String, String> espnForm(String league) {
        List<String[]> results = new ArrayList<>();   // {date, team id, W/D/L}
        java.time.YearMonth month = java.time.YearMonth.now();
        try {
            for (int m = 2; m >= 0; m--) {
                String ym = month.minusMonths(m).toString().replace("-", "");
                JSONArray events = new JSONObject(SchedulePanel.get("https://site.api.espn.com/apis/site/v2/sports/soccer/"
                        + league + "/scoreboard?dates=" + ym)).optJSONArray("events");
                for (int i = 0; events != null && i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    if (!e.getJSONObject("status").getJSONObject("type").optBoolean("completed")) continue;
                    JSONArray c = e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
                    if (c.length() != 2) continue;
                    for (int k = 0; k < 2; k++) {
                        boolean won = c.getJSONObject(k).optBoolean("winner"), lost = c.getJSONObject(1 - k).optBoolean("winner");
                        results.add(new String[]{e.optString("date"), c.getJSONObject(k).getJSONObject("team").optString("id"),
                                won ? "W" : lost ? "L" : "D"});
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, league + " form: " + e);
            return new HashMap<>();
        }
        return lastFive(results);
    }

    /** {date (ISO, sortable), team id, W/D/L} rows → team id → its last five results, oldest first. */
    static Map<String, String> lastFive(List<String[]> results) {
        results.sort((a, b) -> a[0].compareTo(b[0]));
        Map<String, String> form = new HashMap<>();
        for (String[] r : results) {
            String f = form.getOrDefault(r[1], "") + r[2];
            form.put(r[1], f.length() > 5 ? f.substring(f.length() - 5) : f);
        }
        return form;
    }

    private static int rank(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
