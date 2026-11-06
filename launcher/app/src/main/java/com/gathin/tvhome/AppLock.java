package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Parental lock: apps listed in config.json "app_lock" open only after a secret sequence of remote arrows.
 * {@link HomeKeyService} sees every app window come up and shows {@link LockActivity} over a locked one, so
 * the lock holds whatever opened the app (dock, voice search, recents, Play Store). Unlocked apps stay open
 * until Home shows again ({@link #relockAll}).
 *
 * The sequence is set on the TV the first time and kept only as a salted SHA-256 in private preferences.
 * "app_lock_reset": true in config.json forgets it (adb push, then remove the flag), for a forgotten code.
 */
final class AppLock {
    private static final String PREFS = "app_lock";
    static final int MIN_LEN = 4, MAX_LEN = 8;
    static final int MAX_TRIES = 5;
    static final long COOLDOWN_MS = 60_000L;

    // The service and the lock screen run in the launcher's process; this is their shared state.
    private static final Set<String> unlocked = new HashSet<>();
    private static Set<String> locked = new HashSet<>();
    private static long configStamp = -1;
    static int wrongTries;
    static long blockedUntil;

    private AppLock() {}

    static synchronized boolean needsLock(Context ctx, String pkg) {
        if (pkg == null || pkg.equals(ctx.getPackageName())) return false;
        reload(ctx);
        return locked.contains(pkg) && !unlocked.contains(pkg);
    }

    static synchronized void unlock(String pkg) {
        unlocked.add(pkg);
    }

    /** Home is showing: every locked app asks again. */
    static synchronized void relockAll() {
        unlocked.clear();
    }

    /** Re-reads the locked list when config.json changed (file timestamp), and applies app_lock_reset. */
    private static void reload(Context ctx) {
        File dir = Config.dir(ctx);
        File file = dir == null ? null : new File(dir, Config.FILE_NAME);
        long stamp = file != null && file.isFile() ? file.lastModified() : 0;
        if (stamp == configStamp) return;
        configStamp = stamp;
        Set<String> fresh = new HashSet<>();
        try {
            if (stamp != 0) {
                byte[] bytes;
                try (FileInputStream in = new FileInputStream(file)) {
                    bytes = new byte[(int) file.length()];
                    int off = 0;
                    for (int n; off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0; ) off += n;
                }
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONArray apps = json.optJSONArray("app_lock");
                for (int i = 0; apps != null && i < apps.length(); i++) fresh.add(apps.optString(i));
                if (json.optBoolean("app_lock_reset", false)) prefs(ctx).edit().clear().apply();
            }
        } catch (Exception ignored) {
            // Unreadable config: keep the previous list rather than unlocking everything.
            return;
        }
        locked = fresh;
    }

    /**
     * Turns HomeKeyService back on if it is off. Android drops it from the enabled list on every update or
     * force-stop of this app, which would silently switch off the lock and the Home key. Needs
     * WRITE_SECURE_SETTINGS, granted once over adb (pm grant); without it this does nothing.
     */
    static void ensureService(Context ctx) {
        String me = ctx.getPackageName() + "/" + HomeKeyService.class.getName();
        String shortMe = ctx.getPackageName() + "/.HomeKeyService";
        android.content.ContentResolver cr = ctx.getContentResolver();
        String list = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list != null && (list.contains(me) || list.contains(shortMe))) return;
        try {
            String fresh = list == null || list.isEmpty() ? shortMe : list + ":" + shortMe;
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, fresh);
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
            // Permission not granted on this TV: the service stays as it is.
        }
    }

    static boolean hasCode(Context ctx) {
        return prefs(ctx).contains("hash");
    }

    static void setCode(Context ctx, String seq) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs(ctx).edit().putString("salt", s).putString("hash", hash(s, seq)).apply();
    }

    static boolean check(Context ctx, String seq) {
        SharedPreferences p = prefs(ctx);
        String salt = p.getString("salt", null), want = p.getString("hash", null);
        return salt != null && want != null && MessageDigest.isEqual(
                want.getBytes(StandardCharsets.US_ASCII), hash(salt, seq).getBytes(StandardCharsets.US_ASCII));
    }

    static String hash(String salt, String seq) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + seq).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
