package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatsOverlayTest {
    @Test
    public void speedsReadInKilobytesThenMegabytes() {
        assertEquals("0 KB/s", StatsOverlay.speed(-5));
        assertEquals("120 KB/s", StatsOverlay.speed(120 * 1024));
        assertEquals("3.2 MB/s", StatsOverlay.speed(3.2 * 1024 * 1024));
    }
}
