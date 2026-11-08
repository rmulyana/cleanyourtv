package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class KickoffLabelTest {
    private static final long KO = 1_800_000_000_000L, MIN = 60_000;

    @Test
    public void timeThenCountdownThenLiveThenFin() {
        assertNull(SchedulePanel.kickoffLabel(KO, KO - 31 * MIN));
        assertEquals("30m  ", SchedulePanel.kickoffLabel(KO, KO - 30 * MIN));
        assertEquals("25m  ", SchedulePanel.kickoffLabel(KO, KO - 25 * MIN + 1));
        assertEquals("1m   ", SchedulePanel.kickoffLabel(KO, KO - 1));
        assertEquals(SchedulePanel.LIVE, SchedulePanel.kickoffLabel(KO, KO));
        assertEquals(SchedulePanel.LIVE, SchedulePanel.kickoffLabel(KO, KO + 119 * MIN));
        assertEquals(SchedulePanel.FIN, SchedulePanel.kickoffLabel(KO, KO + 120 * MIN));
        assertEquals(5, SchedulePanel.FIN.length());
        assertEquals(5, SchedulePanel.LIVE.length());
    }
}
