package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;

/**
 * Behind the verse panel: the verse large, Arabic over its translation. Left/Right step through the collection
 * ({@link IslamicDay#VERSES}), OK plays or stops Mishary Alafasy's recitation (streamed, so it needs the
 * internet), Back closes and stops it.
 */
final class VerseDialog {
    private final Context ctx;
    private final float d;
    private final Dialog dialog;
    private final TextView title, arabic, meaning, ref, status;
    private int index = IslamicDay.verseIndex(LocalDate.now());
    private MediaPlayer player;

    VerseDialog(Context ctx, Typeface regular, Typeface medium) {
        this.ctx = ctx;
        d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setPadding(px(32), px(18), px(32), px(16));
        title = text(13, medium, 0xFFFFFFFF);
        title.setSingleLine(true);
        panel.addView(title);
        arabic = text(34, Typeface.create("sans-serif", Typeface.NORMAL), 0xFFFFFFFF);   // Android's Arabic font draws the harakat
        arabic.setGravity(Gravity.CENTER);
        arabic.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        arabic.setLineSpacing(px(8), 1f);
        arabic.setPadding(0, px(28), 0, px(20));
        panel.addView(arabic);
        meaning = text(17, regular, 0xE6FFFFFF);
        meaning.setGravity(Gravity.CENTER);
        meaning.setLineSpacing(px(4), 1f);
        panel.addView(meaning);
        ref = text(12, regular, 0x8CFFFFFF);
        ref.setGravity(Gravity.CENTER);
        ref.setPadding(0, px(10), 0, px(22));
        panel.addView(ref);
        status = text(13, medium, 0xFFCFFFCF);
        status.setGravity(Gravity.CENTER);
        panel.addView(status);
        TextView hint = text(11, regular, 0x8CFFFFFF);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, px(8), 0, 0);
        hint.setText("◀ ▶ ayat lain  ·  OK putar / berhenti  ·  Back tutup");
        panel.addView(hint);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(780), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.7f);
        }
        dialog.setOnDismissListener(di -> stop());
        dialog.setOnKeyListener((di, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int n = IslamicDay.VERSES.length;
                index = (index + (key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n;
                stop();
                showVerse();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (player != null) stop();
                else play();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
    }

    void show() {
        showVerse();
        dialog.show();
    }

    private void showVerse() {
        String[] v = IslamicDay.VERSES[index];
        title.setText("AYAT HARI INI" + (index == IslamicDay.verseIndex(LocalDate.now()) ? "" : " · koleksi")
                + "     " + (index + 1) + " / " + IslamicDay.VERSES.length + "     ◀ ▶");
        arabic.setText(v[0]);
        meaning.setText("“" + v[1] + "”");
        ref.setText(v[2]);
        status.setText("OK  ▶ Putar murottal (Mishary Alafasy)");
    }

    private void play() {
        MediaPlayer p = new MediaPlayer();
        player = p;
        status.setText("Memuat murottal…");
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(IslamicDay.audioUrl(IslamicDay.VERSES[index]));
            p.setOnPreparedListener(mp -> {
                if (player != mp) return;   // stopped or moved on while loading
                mp.start();
                status.setText("■ Sedang diputar  ·  OK berhenti");
            });
            p.setOnCompletionListener(mp -> {
                if (player == mp) stop();
            });
            p.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    stop();
                    status.setText("Tidak bisa memutar, cek internet  ·  OK coba lagi");
                }
                return true;
            });
            p.prepareAsync();
        } catch (Exception e) {
            stop();
            status.setText("Tidak bisa memutar  ·  OK coba lagi");
        }
    }

    private void stop() {
        if (player == null) return;
        MediaPlayer p = player;
        player = null;
        try {
            p.release();
        } catch (RuntimeException ignored) {
            // already released
        }
        status.setText("OK  ▶ Putar murottal (Mishary Alafasy)");
    }

    private TextView text(int sp, Typeface tf, int color) {
        TextView v = new TextView(ctx);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(tf);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    private int px(float dp) {
        return Math.round(dp * d);
    }
}
