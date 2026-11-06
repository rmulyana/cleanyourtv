package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Computed times (five prayers, then Terbit) against Kemenag's published 2026 table for Kota Bandung (api.myquran.com, id 1219). */
public class PrayerTest {
    private static final String[] KEMENAG = {
            "2026-01-15 04:23 12:02 15:26 18:21 19:31 05:37",
            "2026-03-20 04:39 12:01 15:11 18:08 19:12 05:46",
            "2026-05-10 04:33 11:49 15:10 17:48 18:55 05:44",
            "2026-06-21 04:38 11:55 15:15 17:51 19:01 05:52",
            "2026-08-05 04:43 11:59 15:20 17:58 19:05 05:53",
            "2026-09-23 04:24 11:46 14:56 17:53 18:57 05:32",
            "2026-11-06 04:03 11:37 14:54 17:52 19:00 05:14",
            "2026-12-21 04:08 11:51 15:17 18:11 19:22 05:24",
    };

    @Test
    public void countdownAndHijri() {
        assertEquals("Subuh dalam 6 jam 28 menit", SchedulePanel.countdown("Subuh", 388));
        assertEquals("Isya dalam 12 menit", SchedulePanel.countdown("Isya", 12));
        assertEquals("Maghrib dalam 1 jam", SchedulePanel.countdown("Maghrib", 60));
        // Kemenag: Friday 6 November 2026 is 25 Jumadil Awal 1448, a day behind Umm al-Qura.
        assertEquals("25 Jumadil Awal 1448 H", SchedulePanel.hijri(LocalDate.of(2026, 11, 6), -1));
        assertEquals("2 d 4 h", SchedulePanel.uptime(2 * 1440 + 4 * 60 + 7));
        assertEquals("3 h 12 min", SchedulePanel.uptime(192));
        assertEquals("Sab, 14 Nov - 01.00 | Jordan v Indonesia", SchedulePanel.matchLine(
                java.time.ZonedDateTime.of(2026, 11, 14, 1, 0, 0, 0, java.time.ZoneId.of("Asia/Jakarta")), "Jordan", "Indonesia"));
        assertEquals("Min,  8 Nov - 19.00 | Java United v Persib", SchedulePanel.matchLine(
                java.time.ZonedDateTime.of(2026, 11, 8, 19, 0, 0, 0, java.time.ZoneId.of("Asia/Jakarta")), "Java United", "Persib"));
    }

    @Test
    public void bandungMatchesKemenagWithinAMinute() {
        for (String row : KEMENAG) {
            String[] p = row.split(" ");
            int[] got = Prayer.minutes(LocalDate.parse(p[0]), -6.9175, 107.6191, 768, 7);
            for (int i = 0; i <= Prayer.TERBIT; i++) {
                String[] hm = p[i + 1].split(":");
                int want = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
                assertTrue(p[0] + " #" + i + ": got " + got[i] + ", want " + want,
                        Math.abs(got[i] - want) <= 1);
            }
        }
    }
}
