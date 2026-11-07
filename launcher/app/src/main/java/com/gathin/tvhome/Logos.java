package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Club logos next to club names: fetched once from the data provider's URL, kept in the app's cache folder (so a restart
 * does not fetch them again) and in memory.
 */
final class Logos {
    private static final String TAG = "TvHome.Logos";
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(96);
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Logos() {}

    /** Puts the logo at {@code url} before {@code v}'s text, {@code sizePx} square; a blank of that size until it loads. */
    static void into(TextView v, String url, int sizePx) {
        v.setTag(R.id.card, url);   // any id works as the key; the newest request for a view wins
        v.setCompoundDrawablePadding(sizePx / 2);
        if (url == null || url.isEmpty()) {
            v.setCompoundDrawablesRelative(blank(sizePx), null, null, null);
            return;
        }
        Bitmap hit = MEMORY.get(url + "@" + sizePx);
        if (hit != null) {
            set(v, hit, sizePx);
            return;
        }
        v.setCompoundDrawablesRelative(blank(sizePx), null, null, null);
        fetch(v.getContext(), url, sizePx, () -> {
            Bitmap b = MEMORY.get(url + "@" + sizePx);
            if (b != null && url.equals(v.getTag(R.id.card))) set(v, b, sizePx);
        });
    }

    /** The logo as a {@code sizePx} drawable if it is in memory, else null. */
    static Drawable cached(android.content.res.Resources r, String url, int sizePx) {
        Bitmap b = url == null || url.isEmpty() ? null : MEMORY.get(url + "@" + sizePx);
        if (b == null) return null;
        Drawable d = new BitmapDrawable(r, b);
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    /** Loads the logo into memory off the main thread, then runs {@code done} on the main thread. */
    static void fetch(Context ctx, String url, int sizePx, Runnable done) {
        Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            String key = url + "@" + sizePx;
            if (MEMORY.get(key) == null) {
                Bitmap b = load(app, url, sizePx);
                if (b == null) return;
                MEMORY.put(key, b);
            }
            MAIN.post(done);
        });
    }

    private static void set(TextView v, Bitmap b, int size) {
        Drawable d = new BitmapDrawable(v.getResources(), b);
        d.setBounds(0, 0, size, size);
        v.setCompoundDrawablesRelative(d, null, null, null);
    }

    static Drawable blank(int size) {
        Drawable d = new ColorDrawable(0);
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static Bitmap load(Context ctx, String url, int size) {
        File dir = new File(ctx.getCacheDir(), "logos");
        File file = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        try {
            byte[] bytes;
            if (file.isFile()) {
                bytes = Files.readAllBytes(file.toPath());
            } else {
                bytes = fetch(url);
                if (dir.isDirectory() || dir.mkdirs()) {
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(bytes);
                    }
                }
            }
            // Decode no larger than needed: the ESPN logos are 500 px; a 1 GB TV keeps them small.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size * 2) o.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            // Fit the crest in the square, keeping its shape (TheSportsDB badges are not always square).
            float scale = (float) size / Math.max(b.getWidth(), b.getHeight());
            float w = b.getWidth() * scale, h = b.getHeight() * scale;
            c.drawBitmap(b, null, new android.graphics.RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), p);
            b.recycle();
            return out;
        } catch (Exception e) {
            Log.w(TAG, url + ": " + e);
            return null;
        }
    }

    private static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
