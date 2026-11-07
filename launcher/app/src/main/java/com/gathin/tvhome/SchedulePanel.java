package com.gathin.tvhome;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Four panels in the empty space between the clock and the dock, from config.json "schedule":
 * prayer times (computed on the TV, see {@link Prayer}), the followed clubs' next matches, the weather
 * and the clubs' league positions.
 * <pre>"schedule": {"city": "Bandung", "lat": -6.9175, "lon": 107.6191, "elevation": 768, "hijri_adjust": -1,
 *   "weather": true, "system": true,
 *   "teams": [139356, 133612], "short_names": {"Manchester United": "Man Utd"},
 *   "team_colors": {"Man Utd": "#7A1F2B", "Indonesia": "#F2F2F2 #B0001B"},
 *   "standings": [{"name": "Persib", "league": "Liga 1", "source": "sportsdb:4790:139356"},
 *                 {"name": "Man Utd", "league": "Premier", "source": "espn:eng.1:360"}]}</pre>
 * Teams are TheSportsDB ids. Network data is fetched off the main thread at most every 30 minutes while Home is shown.
 */
final class SchedulePanel {
    private static final String TAG = "TvHome.Schedule";
    private static final long FETCH_MS = 30 * 60 * 1000L;
    /** A match stays listed this long after kick-off. */
    private static final long LIVE_MS = 2 * 60 * 60 * 1000L;
    private static final int HOURS = 4;
    private static final Locale ID = new Locale("id", "ID");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, ppd MMM", ID);   // day padded to 2 so the monospace " | " lines up
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm", ID);

    // Static so a recreate() does not refetch.
    private static List<Match> matches = new ArrayList<>();
    private static JSONObject weather;
    private static String[] standings;
    private static long fetchedAt;
    private static volatile boolean fetching;

    private final View root;
    private final LinearLayout footballList;
    private final TextView weatherTemp, weatherNow, prayerCountdown, prayerInfo;
    private final int hijriAdjust;
    private final LinearLayout standingsRows;
    private final TextView standingsUpdated;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final double lat, lon, elevation;
    private final boolean prayerOn, weatherOn;
    /** config "system": the show_stats readings go to their own panel instead of the line under the clock. */
    final boolean systemOn;
    private final TextView systemStats;
    private final long[] teams;
    private final JSONArray standingsCfg;
    /** config "short_names": {"Manchester United": "Man Utd"}, applied to both sides of a match. */
    private final JSONObject shortNames;
    /** config "team_colors": {"Man Utd": "#7A1F2B"}, keyed by the shown (short) name; that side gets a badge.
     *  An optional second colour ("#F2F2F2 #B0001B") sets the text; otherwise black or white by the background. */
    private final JSONObject teamColors;
    private final Typeface light, medium, footballFont;
    private TextView[] prayerNames, prayerTimes, hourNames, hourTemps;

    static final class Match {
        final long at;
        final String text;

        Match(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    SchedulePanel(View root, JSONObject cfg, Typeface light, Typeface medium) {
        this.root = root;
        this.light = light;
        this.medium = medium;
        this.footballFont = root.getResources().getFont(R.font.firacode_regular);   // the regular weight the panel texts use
        footballList = root.findViewById(R.id.footballList);
        weatherTemp = root.findViewById(R.id.weatherTemp);
        weatherNow = root.findViewById(R.id.weatherNow);
        prayerCountdown = root.findViewById(R.id.prayerCountdown);
        prayerInfo = root.findViewById(R.id.prayerInfo);
        hijriAdjust = cfg.optInt("hijri_adjust", 0);
        standingsRows = root.findViewById(R.id.standingsRows);
        standingsUpdated = root.findViewById(R.id.standingsUpdated);
        lat = cfg.optDouble("lat", Double.NaN);
        lon = cfg.optDouble("lon", Double.NaN);
        elevation = cfg.optDouble("elevation", 0);
        prayerOn = !Double.isNaN(lat) && !Double.isNaN(lon);
        weatherOn = prayerOn && cfg.optBoolean("weather", false);
        JSONArray t = cfg.optJSONArray("teams");
        teams = new long[t == null ? 0 : t.length()];
        for (int i = 0; i < teams.length; i++) teams[i] = t.optLong(i);
        JSONObject sn = cfg.optJSONObject("short_names");
        shortNames = sn == null ? new JSONObject() : sn;
        JSONObject tc = cfg.optJSONObject("team_colors");
        teamColors = tc == null ? new JSONObject() : tc;
        JSONArray s = cfg.optJSONArray("standings");
        standingsCfg = s == null ? new JSONArray() : s;
        String city = cfg.optString("city", "");
        String at = city.isEmpty() ? "" : " · " + city;
        ((TextView) root.findViewById(R.id.prayerTitle)).setText("Jadwal sholat" + at);
        ((TextView) root.findViewById(R.id.weatherTitle)).setText("Cuaca" + at);
        root.findViewById(R.id.prayerPanel).setVisibility(prayerOn ? View.VISIBLE : View.INVISIBLE);
        root.findViewById(R.id.footballPanel).setVisibility(teams.length > 0 ? View.VISIBLE : View.INVISIBLE);
        root.findViewById(R.id.weatherPanel).setVisibility(weatherOn ? View.VISIBLE : View.INVISIBLE);
        root.findViewById(R.id.standingsPanel).setVisibility(standingsCfg.length() > 0 ? View.VISIBLE : View.INVISIBLE);
        systemOn = cfg.optBoolean("system", false);
        systemStats = root.findViewById(R.id.systemStats);
        root.findViewById(R.id.systemPanel).setVisibility(systemOn ? View.VISIBLE : View.INVISIBLE);
        root.setVisibility(prayerOn || teams.length > 0 || standingsCfg.length() > 0 ? View.VISIBLE : View.GONE);
        if (prayerOn) {
            prayerNames = new TextView[Prayer.NAMES.length];
            prayerTimes = new TextView[Prayer.NAMES.length];
            columns(root.findViewById(R.id.prayerRow), prayerNames, prayerTimes, 11, 20, 14);
            for (int i = 0; i < prayerNames.length; i++) prayerNames[i].setText(Prayer.NAMES[i]);
        }
        if (weatherOn) {
            hourNames = new TextView[HOURS];
            hourTemps = new TextView[HOURS];
            columns(root.findViewById(R.id.weatherHours), hourNames, hourTemps, 10, 14, -1);
        }
    }

    /** The stats line ("CPU 8%  ·  Memory 430 MB free  ·  ...") as one reading per line, then {@link #systemExtras}. */
    void showSystem(String statsLine, String extras) {
        String text = statsLine.replace("  ·  ", "\n");
        systemStats.setText(extras.isEmpty() ? text : text + "\n" + extras);
    }

    /** Wi-Fi link speed, and the time since the TV last booted, one per line. Off the main thread. */
    @SuppressWarnings("deprecation")   // getConnectionInfo: the only way to read link speed without a network callback
    static String systemExtras(android.content.Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getSystemService(android.content.Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1 && info.getLinkSpeed() > 0) {
                sb.append("Wi-Fi ").append(info.getLinkSpeed()).append(" Mbps\n");
            }
        } catch (Exception ignored) {
            // No Wi-Fi service on this TV: leave the line out.
        }
        sb.append("Uptime ").append(uptime(android.os.SystemClock.elapsedRealtime() / 60000));
        return sb.toString();
    }

    /** "45 min", "3 h 12 min", "2 d 4 h". */
    static String uptime(long minutes) {
        long d = minutes / (24 * 60), h = minutes / 60 % 24, m = minutes % 60;
        if (d > 0) return d + " d " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    /** A row of small-over-large text columns (prayer times, hourly forecast). */
    private void columns(LinearLayout row, TextView[] tops, TextView[] bottoms, int topSp, int bottomSp, int gapDp) {
        float d = root.getResources().getDisplayMetrics().density;
        for (int i = 0; i < tops.length; i++) {
            LinearLayout col = new LinearLayout(root.getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            // gapDp < 0: the columns share the row's width evenly instead of a fixed gap.
            LinearLayout.LayoutParams lp = gapDp < 0
                    ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0 && gapDp > 0) lp.setMarginStart((int) (gapDp * d));
            tops[i] = text(topSp, medium, 0x8CFFFFFF);
            bottoms[i] = text(bottomSp, light, 0xE6FFFFFF);
            col.addView(tops[i]);
            col.addView(bottoms[i]);
            row.addView(col, lp);
        }
    }

    private TextView text(int sp, Typeface tf, int color) {
        TextView v = new TextView(root.getContext());
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(tf);
        v.setTextColor(color);
        v.setSingleLine(true);
        v.setIncludeFontPadding(false);
        v.setPadding(0, 0, 0, (int) (2 * root.getResources().getDisplayMetrics().density));
        return v;
    }

    /** Once a minute and on resume: today's prayer times with the next one lit, and the fetched panels. */
    void refresh() {
        if (prayerOn) {
            ZoneOffset off = ZoneId.systemDefault().getRules().getOffset(Instant.now());
            double tz = off.getTotalSeconds() / 3600.0;
            LocalDate today = LocalDate.now();
            int[] m = Prayer.minutes(today, lat, lon, elevation, tz);
            LocalTime now = LocalTime.now();
            int nowMin = now.getHour() * 60 + now.getMinute();
            int n = Prayer.NAMES.length;
            int next = -1;
            for (int i = n - 1; i >= 0; i--) if (m[i] > nowMin) next = i;
            // After Isya the next is tomorrow's Subuh.
            int left = next >= 0 ? m[next] - nowMin
                    : Prayer.minutes(today.plusDays(1), lat, lon, elevation, tz)[0] + 24 * 60 - nowMin;
            if (next < 0) next = 0;
            for (int i = 0; i < n; i++) {
                prayerTimes[i].setText(hhmm(m[i]));
                boolean lit = i == next;
                prayerNames[i].setTextColor(lit ? 0xFFCFFFCF : 0x8CFFFFFF);
                prayerTimes[i].setTextColor(lit ? 0xFFFFFFFF : 0x99FFFFFF);
            }
            prayerCountdown.setText(countdown(Prayer.NAMES[next], left));
            prayerCountdown.setTextColor(left <= 15 ? 0xFFCFFFCF : 0xE6FFFFFF);
            prayerInfo.setText(hijri(today, hijriAdjust) + " · Imsak " + hhmm(m[0] - Prayer.IMSAK_BEFORE_SUBUH_MIN)
                    + " · Terbit " + hhmm(m[Prayer.TERBIT]));
        }
        show();
        boolean wantsNet = teams.length > 0 || weatherOn || standingsCfg.length() > 0;
        if (wantsNet && !fetching && System.currentTimeMillis() - fetchedAt > FETCH_MS) fetch();
    }

    private void show() {
        String waiting = fetchedAt == 0 ? "Memuat…" : "Belum ada data";
        long now = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (Match m : matches) if (m.at + LIVE_MS >= now && lines.size() < 4) lines.add(m.text);
        if (lines.isEmpty()) lines.add(waiting);
        footballList.removeAllViews();
        float d = root.getResources().getDisplayMetrics().density;
        for (String line : lines) {
            TextView v = text(12, footballFont, 0xE6FFFFFF);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setPadding(0, (int) (2 * d), 0, (int) (5 * d));
            v.setText(badges(line, d));
            footballList.addView(v);
        }
        if (weatherOn) showWeather(waiting);
        showStandings(waiting);
    }

    private void showWeather(String waiting) {
        JSONObject cur = weather == null ? null : weather.optJSONObject("current");
        JSONObject hourly = weather == null ? null : weather.optJSONObject("hourly");
        if (cur == null || hourly == null) {
            weatherTemp.setText("");
            weatherNow.setText(waiting);
            return;
        }
        weatherTemp.setText(Math.round(cur.optDouble("temperature_2m")) + "°");
        JSONArray times = hourly.optJSONArray("time"), temps = hourly.optJSONArray("temperature_2m"),
                rain = hourly.optJSONArray("precipitation_probability");
        // The hourly series starts at the current hour; the forecast columns are the hours after it.
        String nowHour = cur.optString("time", "");
        nowHour = nowHour.length() >= 13 ? nowHour.substring(0, 13) : nowHour;
        int start = 0;
        while (times != null && start < times.length() && !times.optString(start).startsWith(nowHour)) start++;
        int maxRain = 0;
        for (int i = 0; i < HOURS; i++) {
            int k = start + 1 + i;
            boolean has = times != null && temps != null && k < times.length();
            String time = has ? times.optString(k) : "";
            hourNames[i].setText(time.length() >= 16 ? time.substring(11, 13) + "." + time.substring(14, 16) : "");
            hourTemps[i].setText(has ? Math.round(temps.optDouble(k)) + "°" : "");
            if (rain != null && k < rain.length()) maxRain = Math.max(maxRain, rain.optInt(k));
        }
        weatherNow.setText(describe(cur.optInt("weather_code", -1)) + "\nLembap " + cur.optInt("relative_humidity_2m")
                + "%\nHujan " + maxRain + "%");
    }

    private void showStandings(String waiting) {
        // The last successful network refresh (matches, weather or standings).
        ZonedDateTime at = java.time.Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault());
        standingsUpdated.setText(fetchedAt == 0 ? "" : "Diperbarui "
                + DateTimeFormatter.ofPattern("EEE, d MMM yyyy", ID).format(at).replace(".", "") + " - " + TIME.format(at));
        int n = standingsCfg.length();
        if (standingsRows.getChildCount() != n) {
            standingsRows.removeAllViews();
            float d = root.getResources().getDisplayMetrics().density;
            for (int i = 0; i < n; i++) {
                LinearLayout row = new LinearLayout(root.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, (int) (2 * d));
                float[] weights = {2.6f, 3.6f, 1.4f, 2};
                for (float w : weights) {
                    TextView v = text(12, row.getChildCount() == 0 ? medium : light, 0xE6FFFFFF);
                    row.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w));
                }
                standingsRows.addView(row);
            }
        }
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            JSONObject c = standingsCfg.optJSONObject(i);
            names[i] = c == null ? "" : c.optString("name");
        }
        Integer[] order = order(names, standings);
        for (int pos = 0; pos < n; pos++) {
            int i = order[pos];
            JSONObject c = standingsCfg.optJSONObject(i);
            LinearLayout row = (LinearLayout) standingsRows.getChildAt(pos);
            String v = standings != null && i < standings.length ? standings[i] : null;
            String[] cells = {c == null ? "" : c.optString("name"), c == null ? "" : c.optString("league"), "", ""};
            if (v == null) cells[2] = waiting;
            else if (v.isEmpty()) cells[2] = "di luar 5 besar";
            else {
                String[] rp = v.split(" ");
                cells[2] = "#" + rp[0];
                cells[3] = rp[1] + " poin";
            }
            for (int k = 0; k < 4; k++) {
                TextView tv = (TextView) row.getChildAt(k);
                tv.setText(cells[k]);
                tv.setTextColor(k == 1 ? 0x8CFFFFFF : 0xE6FFFFFF);
            }
            // Status text spans the last two cells.
            ((LinearLayout.LayoutParams) row.getChildAt(2).getLayoutParams()).weight = v == null || v.isEmpty() ? 3.4f : 1.4f;
            row.getChildAt(3).setVisibility(v == null || v.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Row order for the standings: best position first, equal positions by name; clubs without a position
     * (loading, outside the table) last, by name. {@code values} holds "rank points" per club, or null.
     */
    static Integer[] order(String[] names, String[] values) {
        Integer[] idx = new Integer[names.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            int c = Integer.compare(rank(values, a), rank(values, b));
            return c != 0 ? c : names[a].compareToIgnoreCase(names[b]);
        });
        return idx;
    }

    private static int rank(String[] values, int i) {
        String v = values != null && i < values.length ? values[i] : null;
        try {
            return Integer.parseInt(v.split(" ")[0]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d.%02d", minutes / 60, minutes % 60);
    }

    /** "Subuh dalam 6 jam 28 menit", "Isya dalam 12 menit", "Maghrib dalam 1 jam". */
    static String countdown(String name, int minutes) {
        int h = minutes / 60, m = minutes % 60;
        String in = h == 0 ? m + " menit" : m == 0 ? h + " jam" : h + " jam " + m + " menit";
        return name + " dalam " + in;
    }

    private static final String[] HIJRI_MONTHS = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir",
            "Jumadil Awal", "Jumadil Akhir", "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};

    /**
     * "25 Jumadil Awal 1448 H" from Java's Umm al-Qura calendar. {@code adjustDays} (config "hijri_adjust")
     * shifts it to the local sighting; Indonesia often runs a day behind Umm al-Qura.
     */
    static String hijri(LocalDate date, int adjustDays) {
        try {
            java.time.chrono.HijrahDate h = java.time.chrono.HijrahDate.from(date.plusDays(adjustDays));
            return h.get(java.time.temporal.ChronoField.DAY_OF_MONTH) + " "
                    + HIJRI_MONTHS[h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) - 1] + " "
                    + h.get(java.time.temporal.ChronoField.YEAR) + " H";
        } catch (Exception e) {   // outside the calendar's table range
            return "";
        }
    }

    /** WMO weather code (Open-Meteo) in Indonesian. */
    // One word each: the column beside the temperature fits about 10 characters per line.
    static String describe(int code) {
        if (code == 0 || code == 1) return "Cerah";
        if (code == 2) return "Berawan";
        if (code == 3) return "Mendung";
        if (code == 45 || code == 48) return "Berkabut";
        if (code >= 51 && code <= 57 || code == 61 || code == 80) return "Gerimis";
        if (code == 63 || code == 81) return "Hujan";
        if (code == 65 || code == 82 || code == 66 || code == 67) return "Deras";
        if (code >= 95) return "Badai";
        return "—";
    }

    private void fetch() {
        fetching = true;
        long[] ids = teams.clone();
        JSONObject names = shortNames;
        JSONArray sCfg = standingsCfg;
        boolean wantWeather = weatherOn;
        double la = lat, lo = lon;
        new Thread(() -> {
            List<Match> got = new ArrayList<>();
            boolean matchesOk = false;
            for (long id : ids) {
                try {
                    // shortcut: TheSportsDB's free key returns only the next match per team; a paid key lists more.
                    JSONArray ev = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/123/eventsnext.php?id=" + id))
                            .optJSONArray("events");
                    matchesOk = true;
                    for (int i = 0; ev != null && i < ev.length(); i++) {
                        Match m = parse(ev.getJSONObject(i), names);
                        if (m != null && got.stream().noneMatch(x -> x.text.equals(m.text))) got.add(m);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "team " + id + ": " + e);
                }
            }
            got.sort((a, b) -> Long.compare(a.at, b.at));
            JSONObject w = null;
            if (wantWeather) {
                try {
                    w = new JSONObject(get(String.format(Locale.ROOT,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,relative_humidity_2m,weather_code"
                                    + "&hourly=temperature_2m,precipitation_probability&forecast_hours=%d&timezone=auto",
                            la, lo, HOURS + 2)));
                } catch (Exception e) {
                    Log.w(TAG, "weather: " + e);
                }
            }
            String[] st = new String[sCfg.length()];
            boolean anyStanding = false;
            for (int i = 0; i < st.length; i++) {
                JSONObject c = sCfg.optJSONObject(i);
                try {
                    st[i] = standing(c == null ? "" : c.optString("source"));
                    anyStanding = true;
                } catch (Exception e) {
                    Log.w(TAG, "standing " + i + ": " + e);
                }
            }
            boolean mOk = matchesOk, sOk = anyStanding;
            JSONObject wNew = w;
            main.post(() -> {
                // Offline: keep what was shown and try again on the next refresh.
                if (mOk) matches = got;
                if (wNew != null) weather = wNew;
                if (sOk) standings = st;
                if (mOk || wNew != null || sOk) fetchedAt = System.currentTimeMillis();
                fetching = false;
                show();
            });
        }, "schedule").start();
    }

    /**
     * "rank points", "" when the club is not in the table, from a source "espn:&lt;league&gt;:&lt;team id&gt;" or
     * "sportsdb:&lt;league id&gt;:&lt;team id&gt;". Throws when the source cannot be read.
     */
    private static String standing(String source) throws Exception {
        String[] p = source.split(":");
        if (p.length != 3) throw new IllegalArgumentException("bad source " + source);
        if (p[0].equals("espn")) {
            JSONArray children = new JSONObject(get("https://site.api.espn.com/apis/v2/sports/soccer/" + p[1] + "/standings"))
                    .getJSONArray("children");
            for (int c = 0; c < children.length(); c++) {
                JSONArray entries = children.getJSONObject(c).getJSONObject("standings").getJSONArray("entries");
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i);
                    if (!p[2].equals(e.getJSONObject("team").optString("id"))) continue;
                    String rank = "", points = "";
                    JSONArray stats = e.getJSONArray("stats");
                    for (int k = 0; k < stats.length(); k++) {
                        JSONObject s = stats.getJSONObject(k);
                        if (s.optString("name").equals("rank")) rank = s.optString("displayValue");
                        if (s.optString("name").equals("points")) points = s.optString("displayValue");
                    }
                    return rank + " " + points;
                }
            }
            return "";
        }
        if (p[0].equals("sportsdb")) {
            // shortcut: TheSportsDB's free key returns only the top 5; a club below that shows "di luar 5 besar".
            JSONArray table = new JSONObject(get("https://www.thesportsdb.com/api/v1/json/123/lookuptable.php?l=" + p[1]))
                    .getJSONArray("table");
            for (int i = 0; i < table.length(); i++) {
                JSONObject r = table.getJSONObject(i);
                if (p[2].equals(r.optString("idTeam"))) return r.optString("intRank") + " " + r.optString("intPoints");
            }
            return "";
        }
        throw new IllegalArgumentException("unknown source " + source);
    }

    private static Match parse(JSONObject e, JSONObject shortNames) {
        String ts = e.optString("strTimestamp", "");
        LocalDateTime utc;
        try {
            utc = LocalDateTime.parse(ts.length() >= 19 ? ts.substring(0, 19)
                    : e.optString("dateEvent") + "T" + e.optString("strTime", "00:00:00"));
        } catch (Exception ex) {
            return null;
        }
        ZonedDateTime local = utc.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault());
        String home = e.optString("strHomeTeam"), away = e.optString("strAwayTeam");
        return new Match(local.toInstant().toEpochMilli(),
                matchLine(local, shortNames.optString(home, home), shortNames.optString(away, away)));
    }

    /** The line with each side named in "team_colors" drawn as a white-on-colour badge. */
    private CharSequence badges(String line, float d) {
        android.text.SpannableString out = null;
        for (java.util.Iterator<String> it = teamColors.keys(); it.hasNext(); ) {
            String name = it.next();
            int at = teamStart(line, name);
            if (at < 0) continue;
            String[] spec = teamColors.optString(name).trim().split("\\s+");
            int c, text;
            try {
                c = android.graphics.Color.parseColor(spec[0]) | 0xFF000000;
                text = spec.length > 1 ? android.graphics.Color.parseColor(spec[1]) | 0xFF000000
                        : android.graphics.Color.luminance(c) > 0.3f ? 0xFF000000 : 0xFFFFFFFF;
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (out == null) out = new android.text.SpannableString(line);
            out.setSpan(new Badge(c, text, d), at, at + name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out == null ? line : out;
    }

    /** Where {@code name} starts as the home or away side of a {@link #matchLine}, or -1. */
    static int teamStart(String line, String name) {
        int bar = line.indexOf(" | "), vs = line.indexOf(" v ", bar + 3);
        if (bar < 0 || vs < 0) return -1;
        if (line.substring(bar + 3, vs).equals(name)) return bar + 3;
        return line.substring(vs + 3).equals(name) ? vs + 3 : -1;
    }

    /** Rounded team-colour box with a thin light border and text: legible on the dark red home even for navy or maroon. */
    private static final class Badge extends android.text.style.ReplacementSpan {
        private final int fill, text;
        private final float d;

        Badge(int fill, int text, float d) {
            this.fill = fill;
            this.text = text;
            this.d = d;
        }

        @Override
        public int getSize(android.graphics.Paint p, CharSequence t, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            return Math.round(p.measureText(t, start, end) + 12 * d);
        }

        @Override
        public void draw(android.graphics.Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom,
                         android.graphics.Paint p) {
            int color = p.getColor();
            android.graphics.Paint.Style style = p.getStyle();
            android.graphics.RectF r = new android.graphics.RectF(x + d, y + p.ascent() - d, x + p.measureText(t, start, end) + 11 * d,
                    y + p.descent() + d);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(fill);
            c.drawRoundRect(r, 4 * d, 4 * d, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setColor(0x80FFFFFF);
            c.drawRoundRect(r, 4 * d, 4 * d, p);
            p.setStyle(style);
            p.setColor(text);
            c.drawText(t, start, end, x + 6 * d, y, p);
            p.setColor(color);
        }
    }

    /** "Sab, 14 Nov - 01.00 | Jordan v Indonesia", "Min,  8 Nov - 19.00 | ..." (same width either way). */
    static String matchLine(ZonedDateTime local, String home, String away) {
        return DAY.format(local).replace(".", "") + " - " + TIME.format(local) + " | " + home + " v " + away;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
