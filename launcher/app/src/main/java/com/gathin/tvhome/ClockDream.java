package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver after the TV sits idle (Settings, or `settings put secure screensaver_components
 * com.gathin.tvhome/.ClockDream`): the {@link SaverView} face, silent. Home's Screensaver button opens the same
 * face at once, with ambient sound ({@link ScreensaverActivity}).
 */
public class ClockDream extends DreamService {
    private SaverView face;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        face = new SaverView(this);
        setContentView(face);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        face.start();
    }

    @Override
    public void onDreamingStopped() {
        face.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (face != null) face.stop();
        super.onDetachedFromWindow();
    }
}
