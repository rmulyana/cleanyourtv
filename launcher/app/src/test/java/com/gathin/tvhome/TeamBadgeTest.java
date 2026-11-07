package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TeamBadgeTest {
    @Test
    public void findsHomeOrAwaySideOnly() {
        String line = "Sab, 14 Nov - 01.00 | Jordan v Indonesia";
        assertEquals(line.indexOf("Indonesia"), SchedulePanel.teamStart(line, "Indonesia"));
        assertEquals(22, SchedulePanel.teamStart(line, "Jordan"));
        assertEquals(-1, SchedulePanel.teamStart(line, "Indo"));
        assertEquals(-1, SchedulePanel.teamStart(line, "Sab"));
        assertEquals(-1, SchedulePanel.teamStart("Memuat…", "Persib"));
    }
}
