package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
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

    @Test
    public void kickOffsShareOneLineNamingTheFollowedClubs() {
        Set<String> followed = new HashSet<>(Arrays.asList("Persib", "Inter", "Man Utd", "Indonesia"));
        assertEquals("\u26BD Persib v Persija dimulai",
                StatsOverlay.kickoffNotice(Arrays.asList("Persib v Persija"), followed));
        assertEquals("\u26BD 4 laga dimulai: Persib \u00B7 Inter \u00B7 Man Utd \u00B7 Indonesia",
                StatsOverlay.kickoffNotice(Arrays.asList("Persib v Persija", "Milan v Inter",
                        "Man Utd v Aston Villa", "Jordan v Indonesia"), followed));
        assertEquals("\u26BD 2 laga dimulai: Inter \u00B7 Man Utd \u00B7 Juventus",
                StatsOverlay.kickoffNotice(Arrays.asList("Inter v Man Utd", "Juventus v Roma"), followed));
    }
}
