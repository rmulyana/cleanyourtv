package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatsOverlayTest {
    @Test
    public void speedsReadInKilobytesThenMegabytes() {
        assertEquals("   0 KB/s", StatsOverlay.speed(-5));
        assertEquals(" 120 KB/s", StatsOverlay.speed(120 * 1024));
        assertEquals(" 999 KB/s", StatsOverlay.speed(999 * 1024));
        assertEquals(" 1.0 MB/s", StatsOverlay.speed(1023.6 * 1024));
        assertEquals(" 3.2 MB/s", StatsOverlay.speed(3.2 * 1024 * 1024));
        assertEquals(StatsOverlay.speed(5 * 1024).length(), StatsOverlay.speed(12.5 * 1024 * 1024).length());
    }
}
