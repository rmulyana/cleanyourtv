package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The screensaver opened from Home's button: the same face as {@link ClockDream}, at once, with sound.
 * Left/Right pick an ambient sound (config folder's sounds/, see {@link #SOUNDS}); Up/Down step through the
 * owner's music (config folder's music/, any MP3/M4A/OGG, shuffled each time), with "off" before the first
 * track and after the last. Only one plays at a time: picking an ambient sound turns the music off, and
 * turning the music on silences the ambient sound. OK pauses
 * everything, Back leaves. The choices and the music's place are remembered.
 */
public class ScreensaverActivity extends Activity {
    /** {shown name, file in <config folder>/sounds/}; index 0 is silence. */
    static final String[][] SOUNDS = {
            {"Tanpa suara", null},
            {"Hujan", "hujan.ogg"},
            {"Hujan badai", "petir.ogg"},
            {"Malam", "malam.ogg"},
            {"Api unggun", "api.ogg"},
            {"Ombak", "ombak.ogg"},
            {"Hutan", "hutan.ogg"},
    };

    private SaverView face;
    private MediaPlayer ambient, music;
    private int sound;
    private List<File> tracks = new ArrayList<>();
    private int track = -1;   // -1: music off
    private int loaded = -1;   // the track the music player holds; kept, paused, while music is off
    private boolean paused;
    /** File name → title tag, read once off the main thread (reading tags scans a long file). */
    private final java.util.Map<String, String> titles = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        face = new SaverView(this);
        setContentView(face);
        sound = prefs().getInt("sound", 0);
        if (sound < 0 || sound >= SOUNDS.length) sound = 0;
        tracks = musicFiles();
        Collections.shuffle(tracks);
        // Back to the track that was playing (it may sit anywhere after the shuffle), or the first one.
        String last = prefs().getString("music_file", "");
        if (prefs().getBoolean("music_on", false) && !tracks.isEmpty()) {
            track = 0;
            for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).getName().equals(last)) track = i;
        }
        if (track >= 0 && sound != 0) {   // both were left on by an older version: the music wins
            sound = 0;
            prefs().edit().putInt("sound", 0).apply();
        }
        List<File> all = new ArrayList<>(tracks);
        new Thread(() -> {
            for (File f : all) titles.put(f.getName(), title(f));
        }, "music-titles").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        face.start();
        playAmbient(false);
        playMusic(false, true);
    }

    @Override
    protected void onPause() {
        face.stop();
        saveMusicPlace();
        release(ambient);
        ambient = null;
        release(music);
        music = null;
        loaded = -1;
        super.onPause();
        finish();   // Home or another app ends it; the button opens a fresh one
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sound = (sound + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : SOUNDS.length - 1)) % SOUNDS.length;
                prefs().edit().putInt("sound", sound).apply();
                paused = false;
                if (sound != 0 && track >= 0) {   // one at a time: an ambient sound stops the music
                    saveMusicPlace();
                    track = -1;
                    playMusic(false, false);
                }
                playAmbient(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (tracks.isEmpty()) {
                    face.showNote("Musik: folder music/ masih kosong");
                    return true;
                }
                saveMusicPlace();
                // off → first … last → off, and back the other way
                int n = tracks.size(), at = track + 1;   // 0 = off, 1..n = tracks
                at = (at + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? 1 : n)) % (n + 1);
                track = at - 1;
                paused = false;
                if (track >= 0 && sound != 0) {   // one at a time: music silences the ambient sound
                    sound = 0;
                    prefs().edit().putInt("sound", 0).apply();
                    playAmbient(false);
                }
                playMusic(true, false);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (ambient == null && music == null) return true;
                paused = !paused;
                for (MediaPlayer p : new MediaPlayer[]{ambient, track >= 0 ? music : null}) {
                    if (p == null) continue;
                    if (paused) p.pause();
                    else p.start();
                }
                face.showNote(paused ? "Dijeda" : "Lanjut");
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // ---- ambient ------------------------------------------------------------------------------------

    private void playAmbient(boolean announce) {
        release(ambient);
        ambient = null;
        String file = SOUNDS[sound][1];
        String note = "◀ ▶  Ambient mati";
        if (file != null) {
            File f = new File(new File(getExternalFilesDir(null), "sounds"), file);
            if (!f.isFile()) {
                note = SOUNDS[sound][0] + ": file " + file + " belum ada";
            } else {
                ambient = open(f, true);
                if (ambient == null) {
                    note = SOUNDS[sound][0] + ": tidak bisa diputar";
                } else {
                    if (!paused) ambient.start();
                    note = "◀ ▶  Ambient nyala";
                }
            }
        }
        // The sound's name on the same line the music uses, so both read alike; only one plays at a time.
        if (ambient != null) face.setNowPlaying("♪ " + SOUNDS[sound][0]);
        else if (track < 0) face.setNowPlaying("");
        if (announce) face.showNote(note);
    }

    // ---- music --------------------------------------------------------------------------------------

    /**
     * Plays {@link #track}, or pauses the music at -1. Turning music off only pauses the player, and turning it
     * back on to the same track resumes it; a different track is prepared off the main thread, so the remote
     * never waits on a long file. {@code resume}: start where the track was last left.
     */
    private void playMusic(boolean announce, boolean resume) {
        prefs().edit().putBoolean("music_on", track >= 0).apply();
        if (track < 0) {
            if (music != null && music.isPlaying()) music.pause();
            face.setNowPlaying("");
            if (announce) face.showNote("▲ ▼  Musik mati");
            return;
        }
        File f = tracks.get(track);
        String title = titles.getOrDefault(f.getName(), plainName(f));
        face.setNowPlaying("♪ " + title);
        if (announce) face.showNote("▲ ▼  Musik nyala");   // the title already shows on its own line
        if (music != null && loaded == track) {
            if (!paused) music.start();
            return;
        }
        release(music);
        music = null;
        loaded = -1;
        int want = track;
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(f.getPath());
        } catch (Exception e) {
            p.release();
            face.setNowPlaying("");
            face.showNote(f.getName() + ": tidak bisa diputar");
            return;
        }
        p.setOnPreparedListener(mp -> {
            if (track != want || isFinishing()) {   // turned off or moved on while it was loading
                mp.release();
                return;
            }
            if (resume && f.getName().equals(prefs().getString("music_file", ""))) {
                int at = prefs().getInt("music_pos", 0);
                if (at > 0 && at < mp.getDuration() - 5000) mp.seekTo(at);
            }
            mp.setOnCompletionListener(done -> {   // the next track, round to the first after the last
                track = (track + 1) % tracks.size();
                prefs().edit().putInt("music_pos", 0).apply();
                playMusic(true, false);
            });
            music = mp;
            loaded = want;
            if (!paused) mp.start();
        });
        p.setOnErrorListener((mp, what, extra) -> {
            face.showNote(f.getName() + ": tidak bisa diputar");
            return true;
        });
        p.prepareAsync();
    }

    private static String plainName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void saveMusicPlace() {
        if (music == null || loaded < 0) return;
        try {
            prefs().edit().putString("music_file", tracks.get(loaded).getName())
                    .putInt("music_pos", music.getCurrentPosition()).apply();
        } catch (IllegalStateException ignored) {
            // not prepared; keep the last saved place
        }
    }

    /** The music folder's playable files, by name. */
    private List<File> musicFiles() {
        File[] all = new File(getExternalFilesDir(null), "music").listFiles(f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".ogg")
                    || n.endsWith(".opus") || n.endsWith(".flac") || n.endsWith(".wav"));
        });
        List<File> out = all == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(all));
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** The file's title tag, or its name without the extension. */
    private static String title(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            if (t != null && !t.trim().isEmpty()) return t.trim();
        } catch (RuntimeException ignored) {
            // unreadable tags: fall back to the name
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // nothing to free
            }
        }
        return plainName(f);
    }

    // ---- players ------------------------------------------------------------------------------------

    private static MediaPlayer open(File f, boolean loop) {
        MediaPlayer p = new MediaPlayer();
        try {
            p.setDataSource(f.getPath());
            p.setLooping(loop);
            p.prepare();
            return p;
        } catch (Exception e) {
            p.release();
            return null;
        }
    }

    private static void release(MediaPlayer p) {
        if (p == null) return;
        try {
            p.stop();
        } catch (IllegalStateException ignored) {
            // never started
        }
        p.release();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("saver", MODE_PRIVATE);
    }
}
