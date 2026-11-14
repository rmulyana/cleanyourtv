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
    /** From this long before kick-off a match's time becomes a countdown, like the next prayer. */
    private static final long SOON_MS = 30 * 60 * 1000L;
    private static final int HOURS = 4;
    static final Locale ID = new Locale("id", "ID");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, ppd MMM", ID);   // day padded to 2 so the monospace " | " lines up
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH.mm", ID);

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
    /** TheSportsDB team id → its shown name, learnt from the fixtures; names the club pages behind the panel. */
    private static final java.util.Map<Long, String> TEAM_NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Standings config indexes in the order the panel shows them; the full tables open in this order. */
    private Integer[] shownOrder = new Integer[0];
    private final Handler main = new Handler(Looper.getMainLooper());
    private final double lat, lon, elevation;
    private final boolean prayerOn, weatherOn;
    /** config "system": the show_stats readings go to their own panel instead of the line under the clock. */
    final boolean systemOn;
    private final TextView systemStats;
    /** config "islamic": the calendar and verse panels ({@link IslamicDay}) replace the two football panels. */
    private final boolean islamicOn;
    private final TextView calendarText, verseArabic, verseText, verseRef;
    private final long[] teams;
    private final JSONArray standingsCfg;
    /** config "short_names": {"Manchester United": "Man Utd"}, applied to both sides of a match. */
    private final JSONObject shortNames;
    /** config "team_colors": {"Man Utd": "#7A1F2B"}, keyed by the shown (short) name; that side's name is bold.
     *  An optional second colour ("#F2F2F2 #B0001B") sets the text; otherwise black or white by the background. */
    private final JSONObject teamColors;
    private final Typeface light, medium, footballFont, boldFont;
    private TextView[] prayerNames, prayerTimes, hourNames, hourTemps;

    static final class Match {
        final long at;
        final String text, homeLogo, awayLogo;

        Match(long at, String text, String homeLogo, String awayLogo) {
            this.at = at;
            this.text = text;
            this.homeLogo = homeLogo;
            this.awayLogo = awayLogo;
        }
    }

    /** Logos still loading for the football panel; one redraw follows when the first arrives. */
    private boolean logoRedrawPending;

    SchedulePanel(View root, JSONObject cfg, Typeface light, Typeface medium) {
        this.root = root;
        this.light = light;
        this.medium = medium;
        this.footballFont = root.getResources().getFont(R.font.firacode_regular);   // the regular weight the panel texts use
        boldFont = root.getResources().getFont(R.font.firacode_bold);
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
        View prayerPanel = root.findViewById(R.id.prayerPanel);
        prayerPanel.setVisibility(prayerOn ? View.VISIBLE : View.INVISIBLE);
        if (prayerOn) {
            focusRing(prayerPanel);
            prayerPanel.setOnClickListener(v -> new PrayerDialog(root.getContext(), lat, lon, elevation, hijriAdjust,
                    cfg.optString("city", ""), footballFont, medium).show());
        }
        View footballPanel = root.findViewById(R.id.footballPanel);
        footballPanel.setVisibility(teams.length > 0 ? View.VISIBLE : View.INVISIBLE);
        if (teams.length > 0) {
            focusRing(footballPanel);
            footballPanel.setOnClickListener(v -> {
                List<FixturesDialog.Club> clubs = new ArrayList<>();
                for (long id : teams) {
                    String name = TEAM_NAMES.getOrDefault(id, "Klub " + (clubs.size() + 1));
                    String source = "sportsdb::" + id, league = "";
                    // A club whose standings come from ESPN gets its fuller ESPN schedule.
                    for (int i = 0; i < standingsCfg.length(); i++) {
                        JSONObject c = standingsCfg.optJSONObject(i);
                        if (c != null && name.equals(c.optString("name")) && c.optString("source").startsWith("espn:")) {
                            source = c.optString("source");
                            league = c.optString("league");
                        }
                    }
                    clubs.add(new FixturesDialog.Club(name, source, league));
                }
                new FixturesDialog(root.getContext(), clubs, teamColors, shortNames, footballFont, medium).show();
            });
        }
        View weatherPanel = root.findViewById(R.id.weatherPanel);
        weatherPanel.setVisibility(weatherOn ? View.VISIBLE : View.INVISIBLE);
        if (weatherOn) {
            focusRing(weatherPanel);
            weatherPanel.setOnClickListener(v -> new WeatherDialog(root.getContext(), lat, lon, cfg.optString("city", ""),
                    footballFont, medium).show());
        }
        View standingsPanel = root.findViewById(R.id.standingsPanel);
        standingsPanel.setVisibility(standingsCfg.length() > 0 ? View.VISIBLE : View.INVISIBLE);
        if (standingsCfg.length() > 0) {
            // Focusable from the remote (Up from the dock); OK opens the full tables.
            focusRing(standingsPanel);
            standingsPanel.setOnClickListener(v -> {
                List<JSONObject> clubs = new ArrayList<>();
                for (int i : shownOrder) clubs.add(standingsCfg.optJSONObject(i));
                new StandingsDialog(root.getContext(), clubs, teamColors, footballFont, medium).show();
            });
        }
        islamicOn = cfg.optBoolean("islamic", false);
        calendarText = root.findViewById(R.id.calendarText);
        verseArabic = root.findViewById(R.id.verseArabic);
        verseText = root.findViewById(R.id.verseText);
        verseRef = root.findViewById(R.id.verseRef);
        if (islamicOn) {
            footballPanel.setVisibility(View.GONE);
            standingsPanel.setVisibility(View.GONE);
            root.findViewById(R.id.calendarPanel).setVisibility(View.VISIBLE);
            root.findViewById(R.id.versePanel).setVisibility(View.VISIBLE);
        }
        systemOn = cfg.optBoolean("system", false);
        systemStats = root.findViewById(R.id.systemStats);
        View systemPanel = root.findViewById(R.id.systemPanel);
        systemPanel.setVisibility(systemOn ? View.VISIBLE : View.INVISIBLE);
        if (systemOn) {
            focusRing(systemPanel);
            systemPanel.setOnClickListener(v -> new SystemDialog(root.getContext(), lastCpu, footballFont, medium).show());
        }
        root.setVisibility(prayerOn || islamicOn || teams.length > 0 || standingsCfg.length() > 0 ? View.VISIBLE : View.GONE);
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
    /** The CPU figure of the last stats line, e.g. "18%", for the system window; "" when Android hides it. */
    private String lastCpu = "";

    void showSystem(String statsLine, String extras) {
        java.util.regex.Matcher cpu = java.util.regex.Pattern.compile("CPU (\\d+%)").matcher(statsLine);
        lastCpu = cpu.find() ? cpu.group(1) : "";
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
        if (islamicOn) showIslamic();
        show();
        boolean wantsNet = teams.length > 0 || weatherOn || standingsCfg.length() > 0;
        if (wantsNet && !fetching && System.currentTimeMillis() - fetchedAt > FETCH_MS) fetch();
    }

    private void show() {
        String waiting = fetchedAt == 0 ? "Memuat…" : "Belum ada data";
        long now = System.currentTimeMillis();
        List<Match> lines = new ArrayList<>();
        // A finished match stays (as FIN) until its club's next one is listed: the free key's "next" lags.
        for (Match m : matches) if (lines.size() < 4) lines.add(m);
        if (lines.isEmpty()) lines.add(new Match(0, waiting, "", ""));
        footballList.removeAllViews();
        liveDots.clear();
        float d = root.getResources().getDisplayMetrics().density;
        for (Match m : lines) {
            String label = m.at == 0 ? null : kickoffLabel(m.at, now);
            int slot = m.text.indexOf(" - ") + 3;   // the 5-character time after the date
            Match shown = label == null || slot < 3 ? m
                    : new Match(m.at, m.text.substring(0, slot) + label + m.text.substring(slot + 5), m.homeLogo, m.awayLogo);
            TextView v = text(12, footballFont, label == null ? 0xE6FFFFFF : 0xFFFFFFFF);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setPadding(0, (int) (2 * d), 0, (int) (5 * d));
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(withLogos(shown, d, Math.round(13 * d)));
            if (label != null && slot >= 3) {
                boolean live = label.equals(LIVE);
                b.setSpan(label.equals(FIN) ? new android.text.style.ForegroundColorSpan(0x80FFFFFF)
                                : new Bold(boldFont, live ? 0xFFFF5A5A : 0xFF2E9E5B), slot, slot + 5,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (live) liveDots.add(new Object[]{v, b, slot});
            }
            v.setText(b);
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
        shownOrder = order;
        for (int pos = 0; pos < n; pos++) {
            int i = order[pos];
            JSONObject c = standingsCfg.optJSONObject(i);
            LinearLayout row = (LinearLayout) standingsRows.getChildAt(pos);
            String v = standings != null && i < standings.length ? standings[i] : null;
            String[] cells = {c == null ? "" : c.optString("name"), c == null ? "" : competition(c.optString("league")), "", ""};
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
            String[] parts = v == null ? new String[0] : v.split(" ");
            Logos.into((TextView) row.getChildAt(0), parts.length > 2 ? parts[2] : "",
                    Math.round(16 * root.getResources().getDisplayMetrics().density));
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

    private void showIslamic() {
        LocalDate today = LocalDate.now();
        LocalDate fast = IslamicDay.nextMondayThursday(today, hijriAdjust);
        calendarText.setText("Puasa Senin/Kamis · " + (fast == null ? "—" : IslamicDay.when(fast, today))
                + "\nAyyamul Bidh · " + IslamicDay.rangeLine(IslamicDay.nextAyyamulBidh(today, hijriAdjust), today)
                + holidayLines(IslamicDay.nextIslamicHolidays(today, hijriAdjust, 2), today));
        String[] v = IslamicDay.verse(today);
        verseArabic.setText(v[0]);
        verseText.setText("\u201C" + v[1] + "\u201D");
        verseRef.setText(v[2]);
    }

    private static String holidayLines(List<IslamicDay.Holiday> holidays, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        for (IslamicDay.Holiday h : holidays) sb.append('\n').append(IslamicDay.holidayLine(h, today));
        return sb.toString();
    }

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

    /** An emoji for a WMO weather code; clear or partly cloudy nights get the moon. Android's emoji font draws it. */
    static String icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";   // sun / crescent moon
        if (code == 2) return day ? "\u26C5" : "\u2601\uFE0F";                            // sun behind cloud / cloud
        if (code == 3) return "\u2601\uFE0F";                                             // cloud
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F";                        // fog
        if (code >= 51 && code <= 57 || code == 61 || code == 80) return "\uD83C\uDF26\uFE0F";   // sun behind rain cloud
        if (code >= 95) return "\u26C8\uFE0F";                                            // thunder cloud and rain
        if (code >= 61 && code <= 82) return "\uD83C\uDF27\uFE0F";                        // rain cloud
        return "";
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
                    if (ev != null && ev.length() > 0) {
                        JSONObject e0 = ev.getJSONObject(0);
                        String n = String.valueOf(id).equals(e0.optString("idHomeTeam")) ? e0.optString("strHomeTeam") : e0.optString("strAwayTeam");
                        TEAM_NAMES.put(id, teamName(names, n));
                    }
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
                    String logo = small(e.getJSONObject("team"));
                    JSONArray stats = e.getJSONArray("stats");
                    for (int k = 0; k < stats.length(); k++) {
                        JSONObject s = stats.getJSONObject(k);
                        if (s.optString("name").equals("rank")) rank = s.optString("displayValue");
                        if (s.optString("name").equals("points")) points = s.optString("displayValue");
                    }
                    return rank + " " + points + (logo.isEmpty() ? "" : " " + logo);
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
                if (p[2].equals(r.optString("idTeam"))) {
                    String logo = r.optString("strBadge");
                    return r.optString("intRank") + " " + r.optString("intPoints") + (logo.isEmpty() ? "" : " " + logo);
                }
            }
            return "";
        }
        throw new IllegalArgumentException("unknown source " + source);
    }

    /**
     * A competition's short name: "English Premier League" → "EPL", "International Friendlies" → "Uji Coba",
     * "AFC Champions League Two" → "ACL 2". Unknown names pass through. Rules run in order, first match wins.
     */
    static String competition(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("friendl")) return "Uji Coba";
        if (n.contains("world cup") && n.contains("qualif")) return "Kual. Piala Dunia";
        if (n.contains("world cup")) return "Piala Dunia";
        if (n.contains("asian cup") && n.contains("qualif")) return "Kual. Piala Asia";
        if (n.contains("asian cup")) return "Piala Asia";
        if (n.contains("asean") || n.contains("aff ")) return "Piala AFF";
        if (n.contains("champions league elite")) return "ACL Elite";
        if (n.contains("champions league two") || n.contains("champions league 2")) return "ACL 2";
        if (n.contains("afc champions league")) return "ACL";
        if (n.contains("conference league")) return "UECL";
        if (n.contains("europa league")) return "UEL";
        if (n.contains("champions league")) return "UCL";
        if (n.contains("nations league")) return "UNL";
        if (n.contains("super cup") && n.contains("uefa")) return "Piala Super UEFA";
        if (n.contains("club world cup")) return "Piala Dunia Antarklub";
        if (n.contains("indonesia") || n.equals("liga 1") || n.equals("bri liga 1")) return "Liga 1";
        if (n.contains("premier league") && !n.contains("scottish")) return "EPL";
        if (n.contains("fa cup")) return "FA Cup";
        if (n.contains("efl cup") || n.contains("league cup") || n.contains("carabao")) return "EFL Cup";
        if (n.contains("community shield")) return "Community Shield";
        if (n.contains("serie a")) return "Serie A";
        if (n.contains("coppa italia")) return "Coppa Italia";
        if (n.contains("supercoppa")) return "Supercoppa";
        return name;
    }

    /** An ESPN team's logo at 80 px through ESPN's image resizer (the listed one is 500 px), or "". */
    static String small(JSONObject team) {
        JSONArray logos = team.optJSONArray("logos");
        String href = logos == null || logos.length() == 0 ? "" : logos.optJSONObject(0).optString("href");
        int at = href.indexOf("/i/teamlogos/");
        return at < 0 ? href : "https://a.espncdn.com/combiner/i?img=" + href.substring(at) + "&h=80&w=80";
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
                matchLine(local, teamName(shortNames, home), teamName(shortNames, away)),
                tiny(e.optString("strHomeTeamBadge")), tiny(e.optString("strAwayTeamBadge")));
    }

    /** A club's shown name: config "short_names" first, then every "United" as "Utd" (Madura Utd, Java Utd). */
    static String teamName(JSONObject shortNames, String name) {
        return shortNames.optString(name, name).replaceAll("\\bUnited\\b", "Utd");
    }

    /** TheSportsDB's 50 px rendition of a badge URL ("" stays ""). */
    static String tiny(String badge) {
        return badge.isEmpty() || badge.equals("null") ? "" : badge.endsWith("/tiny") ? badge : badge + "/tiny";
    }

    /** The line with each side named in "team_colors" in bold. */
    private CharSequence badges(String line, float d) {
        android.text.SpannableString out = null;
        for (java.util.Iterator<String> it = teamColors.keys(); it.hasNext(); ) {
            String name = it.next();
            int at = teamStart(line, name);
            if (at < 0) continue;
            int[] colors = teamColor(teamColors, name);
            if (colors == null) continue;
            if (out == null) out = new android.text.SpannableString(line);
            out.setSpan(new Bold(boldFont), at, at + name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out == null ? line : out;
    }

    /** Makes a panel focusable from the remote, outlined while focused; OK then runs its click listener. */
    private void focusRing(View panel) {
        float d = root.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
        ring.setCornerRadius(18 * d);
        ring.setStroke((int) (2 * d), 0xE6FFFFFF);
        panel.setFocusable(true);
        panel.setOnFocusChangeListener((v, focused) -> v.setForeground(focused ? ring : null));
    }

    /** {background, text} for a club in "team_colors" ("#bg" or "#bg #text"), or null when absent or malformed. */
    static int[] teamColor(JSONObject teamColors, String name) {
        String v = teamColors.optString(name).trim();
        if (v.isEmpty()) return null;
        String[] spec = v.split("\\s+");
        try {
            int c = android.graphics.Color.parseColor(spec[0]) | 0xFF000000;
            int text = spec.length > 1 ? android.graphics.Color.parseColor(spec[1]) | 0xFF000000
                    : android.graphics.Color.luminance(c) > 0.3f ? 0xFF000000 : 0xFFFFFFFF;
            return new int[]{c, text};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static final String LIVE = "\u25CFLIVE";   // "●LIVE": five characters, like the "23.30" it replaces
    static final String FIN = "FIN  ";

    /**
     * What a match's time slot shows: null before the last {@link #SOON_MS} (keep the time), "25m" counting down
     * to kick-off, then {@link #LIVE} for {@link #LIVE_MS} (no live scores on the free key, so by the clock), then
     * {@link #FIN}.
     * Always five characters so the " | " stays aligned.
     */
    static String kickoffLabel(long kickoff, long now) {
        if (now >= kickoff) return now < kickoff + LIVE_MS ? LIVE : FIN;
        if (kickoff - now > SOON_MS) return null;
        long minutes = Math.max(1, (kickoff - now + 59_999) / 60_000);
        return String.format(Locale.ROOT, "%-5s", minutes + "m");
    }

    /** Live lines' blinking dots: {TextView, its text, the dot's index}. */
    private final List<Object[]> liveDots = new ArrayList<>();
    private final android.text.style.ForegroundColorSpan dotOff = new android.text.style.ForegroundColorSpan(0);

    /** Called with the clock's colon (once a second): the "●" of a live match blinks with it. */
    void blink(boolean on) {
        for (Object[] l : liveDots) {
            android.text.SpannableStringBuilder b = (android.text.SpannableStringBuilder) l[1];
            int at = (Integer) l[2];
            b.removeSpan(dotOff);
            if (!on) b.setSpan(dotOff, at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ((TextView) l[0]).setText(b);
        }
    }

    /** The fetched matches, soonest first, for the overlay's kick-off notice. */
    static List<Match> matches() {
        return matches;
    }

    /** "Man Utd v Aston Villa" from a match line. */
    static String fixture(Match m) {
        int bar = m.text.indexOf(" | ");
        return bar < 0 ? m.text : m.text.substring(bar + 3);
    }

    /**
     * The match line with each side's logo before its name, then the followed clubs in bold. The logos
     * go in first: a span would swallow text inserted at its start.
     */
    private CharSequence withLogos(Match m, float d, int size) {
        String s = m.text;
        int bar = s.indexOf(" | "), vs = s.indexOf(" v ", bar + 3);
        if (bar < 0 || vs < 0) return badges(s, d);
        String home = s.substring(bar + 3, vs), away = s.substring(vs + 3);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(s);
        // The away side first, so inserting it leaves the home side's index unchanged.
        logo(b, vs + 3, m.awayLogo, size, d);
        logo(b, bar + 3, m.homeLogo, size, d);
        int homeAt = bar + 4, awayAt = homeAt + home.length() + 4;   // each logo is one character
        badge(b, homeAt, home, d);
        badge(b, awayAt, away, d);
        return b;
    }

    private void badge(android.text.SpannableStringBuilder b, int at, String name, float d) {
        int[] c = teamColor(teamColors, name);
        if (c != null) b.setSpan(new Bold(boldFont), at, at + name.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void logo(android.text.SpannableStringBuilder b, int at, String url, int size, float density) {
        android.graphics.drawable.Drawable d = Logos.cached(root.getResources(), url, size);
        if (d == null && url != null && !url.isEmpty() && !logoRedrawPending) {
            logoRedrawPending = true;
            Logos.fetch(root.getContext(), url, size, () -> {
                logoRedrawPending = false;
                show();
            });
        }
        b.insert(at, "\uFFFC");
        b.setSpan(new CenteredImage(d != null ? d : Logos.blank(size), Math.round(4 * density)), at, at + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** An inline image centred on the text line (Android 9 has no ImageSpan.ALIGN_CENTER). */
    static final class CenteredImage extends android.text.style.ReplacementSpan {
        private final android.graphics.drawable.Drawable d;
        private final int gap;   // after the image, instead of a space character (narrower in a tight line)

        CenteredImage(android.graphics.drawable.Drawable d, int gap) {
            this.d = d;
            this.gap = gap;
        }

        @Override
        public int getSize(android.graphics.Paint p, CharSequence t, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            return d.getBounds().width() + gap;
        }

        @Override
        public void draw(android.graphics.Canvas c, CharSequence t, int start, int end, float x, int top, int y, int bottom,
                         android.graphics.Paint p) {
            float mid = y + (p.ascent() + p.descent()) / 2;
            c.save();
            c.translate(x, mid - d.getBounds().height() / 2f);
            d.draw(c);
            c.restore();
        }
    }

    /** Where {@code name} starts as the home or away side of a {@link #matchLine}, or -1. */
    static int teamStart(String line, String name) {
        int bar = line.indexOf(" | "), vs = line.indexOf(" v ", bar + 3);
        if (bar < 0 || vs < 0) return -1;
        if (line.substring(bar + 3, vs).equals(name)) return bar + 3;
        return line.substring(vs + 3).equals(name) ? vs + 3 : -1;
    }

    /** Rounded team-colour box with a thin light border and text: legible on the dark red home even for navy or maroon. */
    /** Bold Fira Code over a followed club's name; the monospace width does not change, so " | " stays aligned. */
    static final class Bold extends android.text.style.MetricAffectingSpan {
        private final Typeface bold;
        final int color;

        Bold(Typeface bold) {
            this(bold, 0xFFFFFFFF);
        }

        /** In {@code color}: a ForegroundColorSpan under this one would be painted over. */
        Bold(Typeface bold, int color) {
            this.bold = bold;
            this.color = color;
        }

        @Override
        public void updateDrawState(android.text.TextPaint p) {
            p.setTypeface(bold);
            p.setColor(color);
        }

        @Override
        public void updateMeasureState(android.text.TextPaint p) {
            p.setTypeface(bold);
        }
    }

    /** "Sab, 14 Nov - 01.00 | Jordan v Indonesia", "Min,  8 Nov - 19.00 | ..." (same width either way). */
    static String matchLine(ZonedDateTime local, String home, String away) {
        return DAY.format(local).replace(".", "") + " - " + TIME.format(local) + " | " + home + " v " + away;
    }

    static String get(String url) throws Exception {
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
