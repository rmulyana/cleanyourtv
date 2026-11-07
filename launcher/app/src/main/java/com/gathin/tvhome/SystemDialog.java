package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.Inet4Address;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Behind the system panel, two pages (Left/Right): the device (model, uptime, CPU, memory, storage, Wi-Fi,
 * addresses) with "Bersihkan memori", and the apps by storage use. The apps page needs Usage Access,
 * granted once over adb: appops set com.gathin.tvhome GET_USAGE_STATS allow. Back closes it.
 */
final class SystemDialog {
    private static final String[] PAGES = {"Perangkat", "Aplikasi"};
    private static final int APP_ROWS = 14;
    private static final String[] APP_HEADER = {"Aplikasi", "Ukuran", "Data", "Cache", "Total"};
    private static final float[] APP_WEIGHTS = {4f, 1.4f, 1.4f, 1.4f, 1.4f};
    private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("EEE, d MMM HH.mm", SchedulePanel.ID);

    private final Context ctx;
    private final String cpu;
    private final Typeface regular, medium;
    private final float d;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Dialog dialog;
    private final TextView title, status;
    private final LinearLayout rows;
    private int page;
    private String freed = "";   // the last "Bersihkan memori" result, shown under the button
    private boolean cleaning;

    /** {@code cpu}: the home panel's last CPU figure, e.g. "18%", or "" (Android may hide /proc/stat). */
    SystemDialog(Context ctx, String cpu, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        this.cpu = cpu;
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
        status.setPadding(0, px(12), 0, 0);
        panel.addView(status);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(760), ViewGroup.LayoutParams.WRAP_CONTENT);
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
                if (page == 0) clean();
                else dialog.dismiss();
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
        SpannableStringBuilder t = new SpannableStringBuilder("SISTEM     ");
        for (int i = 0; i < PAGES.length; i++) {
            if (i > 0) t.append("  ·  ");
            int start = t.length();
            t.append(PAGES[i]);
            t.setSpan(new ForegroundColorSpan(i == page ? 0xFFFFFFFF : 0x66FFFFFF), start, t.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        title.setText(t);
        rows.removeAllViews();
        if (page == 0) {
            device();
            status.setText("OK bersihkan memori  ·  ◀ ▶ ganti halaman  ·  Back tutup");
        } else {
            apps();
            status.setText("Diurutkan dari yang terbesar  ·  ◀ ▶ ganti halaman  ·  Back tutup");
        }
    }

    // ---- page 1: the device -------------------------------------------------------------------------

    private void device() {
        String version = "";
        try {
            version = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // keep it empty
        }
        line("Perangkat", Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1)
                + " " + Build.MODEL + "  ·  Android TV " + Build.VERSION.RELEASE + (version == null || version.isEmpty() ? "" : "  ·  Launcher " + version));
        long up = SystemClock.elapsedRealtime();
        line("Menyala", SchedulePanel.uptime(up / 60000) + "  (sejak "
                + SINCE.format(Instant.ofEpochMilli(System.currentTimeMillis() - up).atZone(ZoneId.systemDefault())) + ")");
        line("CPU", (cpu.isEmpty() ? "" : cpu + "  ·  ") + Runtime.getRuntime().availableProcessors() + " inti");

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long memUsed = mi.totalMem - mi.availMem;
        bar("Memori", memUsed, mi.totalMem, mb(memUsed) + " / " + mb(mi.totalMem) + " terpakai");

        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), used = total - fs.getAvailableBytes();
        bar("Penyimpanan", used, total, gb(used) + " / " + gb(total) + " terpakai");

        String[] net = network();
        line("Wi-Fi", net[0]);
        line("Alamat", net[1]);

        TextView button = text(12, medium, 0xFF000000);
        button.setText(cleaning ? "Membersihkan…" : "Bersihkan memori");
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F2F2);
        bg.setCornerRadius(px(20));
        button.setBackground(bg);
        button.setPadding(px(18), px(8), px(18), px(8));
        LinearLayout holder = new LinearLayout(ctx);
        holder.setGravity(Gravity.CENTER_VERTICAL);
        holder.setPadding(px(8), px(14), 0, px(2));
        holder.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (!freed.isEmpty()) {
            TextView note = text(12, regular, 0xFFCFFFCF);
            note.setText(freed);
            note.setPadding(px(14), 0, 0, 0);
            holder.addView(note);
        }
        rows.addView(holder);
    }

    /** {"November_2.4G · 72 Mbps · sinyal 68% · 2.4 GHz", "IP … · gateway … · DNS …"}. */
    @SuppressWarnings("deprecation")   // getConnectionInfo: the only Wi-Fi details without a network callback
    private String[] network() {
        String wifi = "Tidak terhubung", addr = "–";
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                String ssid = info.getSSID().replace("\"", "");
                int pct = WifiManager.calculateSignalLevel(info.getRssi(), 101);
                int mhz = info.getFrequency();
                wifi = ssid + "  ·  " + info.getLinkSpeed() + " Mbps  ·  sinyal " + pct + "%"
                        + (mhz > 0 ? "  ·  " + (mhz >= 5000 ? "5 GHz" : "2.4 GHz") : "");
            }
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm == null ? null : cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                String ip = "", gw = "", dns = "";
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) ip = a.getAddress().getHostAddress();
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) gw = r.getGateway().getHostAddress();
                }
                for (java.net.InetAddress a : lp.getDnsServers()) {
                    if (dns.isEmpty() && a instanceof Inet4Address) dns = a.getHostAddress();   // the short IPv4 one
                }
                if (dns.isEmpty() && !lp.getDnsServers().isEmpty()) dns = lp.getDnsServers().get(0).getHostAddress();
                addr = "IP " + ip + (gw.isEmpty() ? "" : "  ·  gateway " + gw) + (dns.isEmpty() ? "" : "  ·  DNS " + dns);
            }
        } catch (Exception ignored) {
            // leave the defaults
        }
        return new String[]{wifi, addr};
    }

    /** Kills background apps (not the launcher), then shows how much memory came back. */
    private void clean() {
        if (cleaning) return;
        cleaning = true;
        freed = "";
        showPage();
        new Thread(() -> {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo(), after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(before);
            for (ApplicationInfo a : ctx.getPackageManager().getInstalledApplications(0)) {
                if (!a.packageName.equals(ctx.getPackageName())) am.killBackgroundProcesses(a.packageName);
            }
            SystemClock.sleep(1500);   // let the processes go before measuring
            am.getMemoryInfo(after);
            long gained = Math.max(0, after.availMem - before.availMem);
            main.post(() -> {
                cleaning = false;
                freed = mb(gained) + " dibebaskan";
                if (dialog.isShowing() && page == 0) showPage();
            });
        }, "clean-memory").start();
    }

    // ---- page 2: apps by storage --------------------------------------------------------------------

    private void apps() {
        rows.addView(row(APP_HEADER, 0x8CFFFFFF, regular));
        if (!usageAccess()) {
            TextView v = text(12, regular, 0xE6FFFFFF);
            v.setSingleLine(false);
            v.setText("Izin Usage Access belum diberikan. Sekali saja dari komputer:\n"
                    + "adb shell appops set " + ctx.getPackageName() + " GET_USAGE_STATS allow");
            v.setPadding(px(8), px(8), 0, 0);
            rows.addView(v);
            return;
        }
        TextView loading = text(12, regular, 0x8CFFFFFF);
        loading.setText("Menghitung…");
        loading.setPadding(px(8), px(6), 0, 0);
        rows.addView(loading);
        new Thread(() -> {
            List<String[]> list = appSizes();
            main.post(() -> {
                if (!dialog.isShowing() || page != 1) return;
                rows.removeView(loading);
                for (int i = 0; i < Math.min(APP_ROWS, list.size()); i++) rows.addView(row(list.get(i), 0xE6FFFFFF, regular));
            });
        }, "app-sizes").start();
    }

    private boolean usageAccess() {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /** {label, app, data, cache, total} for every app with a Home or TV entry, largest first. */
    private List<String[]> appSizes() {
        PackageManager pm = ctx.getPackageManager();
        Set<String> pkgs = new HashSet<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                pkgs.add(r.activityInfo.packageName);
            }
        }
        StorageStatsManager ssm = ctx.getSystemService(StorageStatsManager.class);
        List<Object[]> sized = new ArrayList<>();
        for (String pkg : pkgs) {
            try {
                StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle());
                long cache = s.getCacheBytes(), data = Math.max(0, s.getDataBytes() - cache), app = s.getAppBytes();
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                sized.add(new Object[]{app + data + cache, new String[]{label.toString(), size(app), size(data), size(cache), size(app + data + cache)}});
            } catch (Exception ignored) {
                // an app that disappeared or is hidden from us
            }
        }
        sized.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        List<String[]> out = new ArrayList<>();
        for (Object[] o : sized) out.add((String[]) o[1]);
        return out;
    }

    // ---- views --------------------------------------------------------------------------------------

    private void line(String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(px(8), px(5), px(8), px(5));
        TextView l = text(12, regular, 0x8CFFFFFF);
        l.setText(label.toUpperCase(SchedulePanel.ID));
        l.setLetterSpacing(0.08f);
        row.addView(l, new LinearLayout.LayoutParams(px(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(12, regular, 0xE6FFFFFF);
        v.setText(value);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rows.addView(row);
    }

    /** A labelled usage bar: yellow above 80%, red above 90%. */
    private void bar(String label, long used, long total, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(5), px(8), px(5));
        TextView l = text(12, regular, 0x8CFFFFFF);
        l.setText(label.toUpperCase(SchedulePanel.ID));
        l.setLetterSpacing(0.08f);
        row.addView(l, new LinearLayout.LayoutParams(px(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        float share = total <= 0 ? 0 : (float) used / total;
        int color = share > 0.9f ? 0xFFD64545 : share > 0.8f ? 0xFFE0B040 : 0xFF2E9E5B;
        LinearLayout track = new LinearLayout(ctx);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0x26FFFFFF);
        tb.setCornerRadius(px(4));
        track.setBackground(tb);
        TextView fill = new TextView(ctx);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(color);
        fb.setCornerRadius(px(4));
        fill.setBackground(fb);
        track.setWeightSum(1f);
        track.addView(fill, new LinearLayout.LayoutParams(0, px(10), Math.max(0.02f, share)));
        row.addView(track, new LinearLayout.LayoutParams(px(220), px(10)));
        TextView v = text(12, regular, 0xE6FFFFFF);
        v.setText(value + "  (" + Math.round(share * 100) + "%)");
        v.setPadding(px(14), 0, 0, 0);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        rows.addView(row);
    }

    private LinearLayout row(String[] cells, int color, Typeface tf) {
        LinearLayout row = new LinearLayout(ctx);
        row.setPadding(px(8), px(4), px(8), px(4));
        for (int i = 0; i < cells.length; i++) {
            TextView v = text(12, tf, color);
            v.setText(cells[i]);
            v.setGravity(i == 0 ? Gravity.START : Gravity.END);
            row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, APP_WEIGHTS[i]));
        }
        return row;
    }

    private static String mb(long bytes) {
        return Math.round(bytes / 1048576.0) + " MB";
    }

    private static String gb(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }

    /** "850 KB", "95 MB", "1.2 GB". */
    static String size(long bytes) {
        if (bytes < 1048576) return Math.round(bytes / 1024.0) + " KB";
        if (bytes < 1073741824) return Math.round(bytes / 1048576.0) + " MB";
        return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
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
