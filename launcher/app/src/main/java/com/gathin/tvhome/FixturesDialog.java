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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behind the Jadwal bola panel: one followed club per page (Left/Right) with its last results and next
 * matches. A club with an ESPN standings source ("espn:eng.1:360") gets five of each from ESPN; the others
 * get what TheSportsDB's free key gives, one of each. OK or Back closes it.
 */
final class FixturesDialog {
    private static final String TAG = "TvHome.Fixtures";
    private static final long CACHE_MS = 30 * 60 * 1000L;
    private static final int MAX = 5;
    /** Club key → {fetched at (Long), Club}. */
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    /** One followed club: its tab name, and where its matches come from. */
    static final class Club {
        final String name, source, league;   // source: "espn:<league>:<team id>" or "sportsdb::<team id>"

        Club(String name, String source, String league) {
            this.name = name;
            this.source = source;
            this.league = league;
        }
    }

    /** A played or upcoming match; scores are null before kick-off. */
    static final class Game {
        final long at;
        final String comp, home, away, homeScore, awayScore;
        final char result;   // W/D/L for the followed club, 0 when not played
        String homeLogo = "", awayLogo = "";

        Game(long at, String comp, String home, String away, String homeScore, String awayScore, char result) {
            this.at = at;
            this.comp = comp;
            this.home = home;
            this.away = away;
            this.homeScore = homeScore;
            this.awayScore = awayScore;
            this.result = result;
        }
    }

    private final Context ctx;
    private final List<Club> clubs;
    private final JSONObject teamColors, shortNames;
    private final Typeface regular, medium;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView title, status;
    private final LinearLayout body;
    private int page;

    FixturesDialog(Context ctx, List<Club> clubs, JSONObject teamColors, JSONObject shortNames, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.clubs = clubs;
        this.teamColors = teamColors;
        this.shortNames = shortNames;
        this.regular = regular;
        this.medium = medium;
        d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(px(28), px(18), px(28), px(16));
        title = text(13, medium, 0xFFFFFFFF);
        title.setPadding(0, 0, 0, px(8));
        panel.addView(title);
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body);
        status = text(11, regular, 0x8CFFFFFF);
        status.setPadding(0, px(12), 0, 0);
        panel.addView(status);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(720), ViewGroup.LayoutParams.WRAP_CONTENT);
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
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
    }

    void show() {
        if (clubs.isEmpty()) return;
        showPage();
        dialog.show();
    }

    private void showPage() {
        Club club = clubs.get(page);
        SpannableStringBuilder t = new SpannableStringBuilder("JADWAL BOLA   ");
        for (int i = 0; i < clubs.size(); i++) {
            if (i > 0) t.append("  ·  ");
            int start = t.length();
            t.append(clubs.get(i).name);
            t.setSpan(new ForegroundColorSpan(i == page ? 0xFFFFFFFF : 0x66FFFFFF), start, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(t);
        Object[] hit = CACHE.get(club.source);
        @SuppressWarnings("unchecked")
        List<Game>[] games = hit == null ? null : (List<Game>[]) hit[1];
        fill(games);
        if (games != null && System.currentTimeMillis() - (Long) hit[0] < CACHE_MS) return;
        if (games == null) status.setText("Memuat…");
        int asked = page;
        new Thread(() -> {
            List<Game>[] got = null;
            try {
                got = games(club, shortNames);
            } catch (Exception e) {
                Log.w(TAG, club.source + ": " + e);
            }
            List<Game>[] result = got;
            main.post(() -> {
                if (result != null) CACHE.put(club.source, new Object[]{System.currentTimeMillis(), result});
                if (page != asked || !dialog.isShowing()) return;
                if (result != null) fill(result);
                else if (games == null) status.setText("Gagal memuat. Coba lagi nanti.");
            });
        }, "club-fixtures").start();
    }

    /** games[0]: last results, oldest first; games[1]: next matches, soonest first. */
    private void fill(List<Game>[] games) {
        body.removeAllViews();
        if (games == null) return;
        Club club = clubs.get(page);
        body.addView(heading("Hasil terakhir"));
        if (games[0].isEmpty()) body.addView(note("Belum ada data"));
        for (Game g : games[0]) body.addView(row(g, club.name));
        body.addView(heading("Jadwal berikutnya"));
        if (games[1].isEmpty()) body.addView(note("Belum ada jadwal"));
        for (Game g : games[1]) body.addView(row(g, club.name));
        boolean limited = club.source.startsWith("sportsdb:");
        status.setText((limited ? "Sumber gratis: 1 hasil dan 1 jadwal per klub  ·  " : "") + "◀ ▶ ganti klub  ·  Back tutup");
    }

    private TextView heading(String s) {
        TextView v = text(11, medium, 0x8CFFFFFF);
        v.setText(s.toUpperCase(SchedulePanel.ID));
        v.setLetterSpacing(0.1f);
        v.setPadding(0, px(12), 0, px(4));
        return v;
    }

    private TextView note(String s) {
        TextView v = text(12, regular, 0x8CFFFFFF);
        v.setText(s);
        v.setPadding(px(8), px(3), 0, px(3));
        return v;
    }

    private LinearLayout row(Game g, String mine) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(4), px(8), px(4));
        ZonedDateTime at = Instant.ofEpochMilli(g.at).atZone(ZoneId.systemDefault());
        String when = SchedulePanel.DAY.format(at).replace(".", "") + (g.result == 0 ? " - " + SchedulePanel.TIME.format(at) : "");
        add(row, text(12, regular, 0xE6FFFFFF), when, 3.9f, Gravity.START);
        add(row, text(12, regular, 0x8CFFFFFF), g.comp, 2.6f, Gravity.START);
        team(row, g.home, g.homeLogo, mine, Gravity.END);
        String mid = g.result == 0 ? "v" : g.homeScore + " - " + g.awayScore;
        add(row, text(12, g.result == 0 ? regular : medium, 0xFFFFFFFF), mid, 1.4f, Gravity.CENTER);
        team(row, g.away, g.awayLogo, mine, Gravity.START);
        TextView chip = text(10, medium, 0xFFFFFFFF);
        if (g.result != 0) {
            chip.setText(String.valueOf(g.result));
            chip.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(g.result == 'W' ? 0xFF2E9E5B : g.result == 'L' ? 0xFFD64545 : 0xFF7A7A80);
            bg.setCornerRadius(px(3));
            chip.setBackground(bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(18), px(16));
        lp.setMarginStart(px(10));
        row.addView(chip, lp);
        return row;
    }

    /**
     * A team column with the club's logo; the followed club's name is bold, like the home panel.
     */
    private void team(LinearLayout row, String name, String logo, String mine, int gravity) {
        LinearLayout cell = new LinearLayout(ctx);
        cell.setGravity(gravity | Gravity.CENTER_VERTICAL);
        TextView v = text(12, regular, 0xE6FFFFFF);
        v.setText(name);
        if (name.equals(mine)) {
            v.setTypeface(ctx.getResources().getFont(R.font.firacode_bold));
            v.setTextColor(0xFFFFFFFF);
        }
        Logos.into(v, logo, px(15));
        cell.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f));
    }

    private void add(LinearLayout row, TextView v, CharSequence s, float weight, int gravity) {
        v.setText(s);
        v.setGravity(gravity);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
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

    // ---- data ------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<Game>[] games(Club club, JSONObject shortNames) throws Exception {
        String[] p = club.source.split(":", -1);
        List<Game> played = new ArrayList<>(), next = new ArrayList<>();
        if (p[0].equals("espn")) {
            String base = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + p[1] + "/teams/" + p[2] + "/schedule";
            espn(new JSONObject(SchedulePanel.get(base)), p[2], club.league, shortNames, played);
            espn(new JSONObject(SchedulePanel.get(base + "?fixture=true")), p[2], club.league, shortNames, next);
        } else {
            String api = "https://www.thesportsdb.com/api/v1/json/123/";
            // Each list on its own: one failed request (TheSportsDB's free key is rate limited) leaves the other.
            Exception failed = null;
            try {
                sportsDb(new JSONObject(SchedulePanel.get(api + "eventslast.php?id=" + p[2])).optJSONArray("results"), p[2], shortNames, played);
            } catch (Exception e) {
                failed = e;
            }
            try {
                sportsDb(new JSONObject(SchedulePanel.get(api + "eventsnext.php?id=" + p[2])).optJSONArray("events"), p[2], shortNames, next);
            } catch (Exception e) {
                if (failed != null) throw e;
            }
        }
        played.removeIf(g -> g.result == 0);
        next.removeIf(g -> g.result != 0);
        played.sort((a, b) -> Long.compare(a.at, b.at));
        next.sort((a, b) -> Long.compare(a.at, b.at));
        return new List[]{new ArrayList<>(played.subList(Math.max(0, played.size() - MAX), played.size())),
                new ArrayList<>(next.subList(0, Math.min(MAX, next.size())))};
    }

    private static void espn(JSONObject json, String teamId, String league, JSONObject shortNames, List<Game> out) throws Exception {
        JSONArray events = json.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray c = comp.getJSONArray("competitors");
            if (c.length() != 2) continue;
            JSONObject home = c.getJSONObject(0).optString("homeAway").equals("home") ? c.getJSONObject(0) : c.getJSONObject(1);
            JSONObject away = home == c.getJSONObject(0) ? c.getJSONObject(1) : c.getJSONObject(0);
            boolean done = comp.getJSONObject("status").getJSONObject("type").optBoolean("completed");
            char r = 0;
            if (done) {
                JSONObject me = home.getJSONObject("team").optString("id").equals(teamId) ? home : away;
                JSONObject them = me == home ? away : home;
                r = me.optBoolean("winner") ? 'W' : them.optBoolean("winner") ? 'L' : 'D';
            }
            // "2026-11-08T16:30Z" has no seconds, which Instant.parse rejects on Android 9; this parser makes them optional.
            long at = java.time.OffsetDateTime.parse(e.optString("date")).toInstant().toEpochMilli();
            Game g = new Game(at, SchedulePanel.competition(league), name(home, shortNames), name(away, shortNames),
                    done ? score(home) : null, done ? score(away) : null, r);
            g.homeLogo = SchedulePanel.small(home.getJSONObject("team"));
            g.awayLogo = SchedulePanel.small(away.getJSONObject("team"));
            out.add(g);
        }
    }

    private static String name(JSONObject competitor, JSONObject shortNames) {
        JSONObject t = competitor.optJSONObject("team");
        String n = t == null ? "" : t.optString("shortDisplayName", t.optString("displayName"));
        return SchedulePanel.teamName(shortNames, n);
    }

    /** ESPN gives a score as "2" on some endpoints and {"displayValue": "2"} on others. */
    private static String score(JSONObject competitor) {
        JSONObject s = competitor.optJSONObject("score");
        return s != null ? s.optString("displayValue") : competitor.optString("score");
    }

    private static void sportsDb(JSONArray events, String teamId, JSONObject shortNames, List<Game> out) {
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e == null) continue;
            String ts = e.optString("strTimestamp", "");
            LocalDateTime utc;
            try {
                utc = LocalDateTime.parse(ts.length() >= 19 ? ts.substring(0, 19)
                        : e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00"));
            } catch (Exception ex) {
                continue;
            }
            String hs = e.optString("intHomeScore", ""), as = e.optString("intAwayScore", "");
            boolean done = !hs.isEmpty() && !as.isEmpty() && !hs.equals("null") && !as.equals("null");
            char r = 0;
            if (done) {
                int mine = Integer.parseInt(teamId.equals(e.optString("idHomeTeam")) ? hs : as);
                int theirs = Integer.parseInt(teamId.equals(e.optString("idHomeTeam")) ? as : hs);
                r = mine > theirs ? 'W' : mine < theirs ? 'L' : 'D';
            }
            String home = e.optString("strHomeTeam"), away = e.optString("strAwayTeam");
            Game g = new Game(utc.toInstant(ZoneOffset.UTC).toEpochMilli(), SchedulePanel.competition(e.optString("strLeague")),
                    SchedulePanel.teamName(shortNames, home), SchedulePanel.teamName(shortNames, away), done ? hs : null, done ? as : null, r);
            g.homeLogo = SchedulePanel.tiny(e.optString("strHomeTeamBadge"));
            g.awayLogo = SchedulePanel.tiny(e.optString("strAwayTeamBadge"));
            out.add(g);
        }
    }
}
