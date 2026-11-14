package com.gathin.tvhome;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.chrono.HijrahChronology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IslamicDayTest {
    /** The Gregorian day shown as Hijri {y, m, d} with Indonesia's -1 adjustment. */
    private static LocalDate g(int y, int m, int d) {
        return LocalDate.from(HijrahChronology.INSTANCE.date(y, m, d)).plusDays(1);
    }

    @Test
    public void mondayThursdaySkipsRamadan() {
        LocalDate fast = IslamicDay.nextMondayThursday(g(1448, 9, 5), -1);
        assertNotNull(fast);
        assertTrue(fast.isAfter(g(1448, 10, 1)));
        DayOfWeek w = fast.getDayOfWeek();
        assertTrue(w == DayOfWeek.MONDAY || w == DayOfWeek.THURSDAY);
    }

    @Test
    public void ayyamulBidhIsThreeDaysButTasyrikIsSkipped() {
        LocalDate[] r = IslamicDay.nextAyyamulBidh(g(1448, 6, 1), -1);
        assertEquals(g(1448, 6, 13), r[0]);
        assertEquals(g(1448, 6, 15), r[1]);
        LocalDate[] z = IslamicDay.nextAyyamulBidh(g(1448, 12, 1), -1);
        assertEquals(g(1448, 12, 14), z[0]);   // 13 Zulhijah is a tasyrik day
        assertFalse(IslamicDay.noSunnahFast(g(1448, 6, 13), -1));
    }

    @Test
    public void holidays() {
        IslamicDay.Holiday isra = IslamicDay.nextIslamicHoliday(g(1448, 6, 3), -1);
        assertEquals("Isra Mi'raj", isra.name);
        assertEquals(g(1448, 7, 27), isra.date);
        LocalDate aug17 = LocalDate.of(2027, 8, 17);
        assertEquals("Hari Kemerdekaan · hari ini", IslamicDay.holidayLine(IslamicDay.nextNationalHoliday(aug17), aug17));
        assertEquals("Natal · Jum, 25 Des · 41 hari lagi",
                IslamicDay.holidayLine(IslamicDay.nextNationalHoliday(LocalDate.of(2026, 11, 14)), LocalDate.of(2026, 11, 14)));
    }

    @Test
    public void dailyTexts() {
        assertEquals(99, IslamicDay.ASMA.length);
        assertEquals(IslamicDay.ASMA.length, new java.util.HashSet<>(java.util.Arrays.asList(IslamicDay.ASMA)).size());
        LocalDate d = LocalDate.of(2026, 11, 14);
        assertTrue(IslamicDay.verse(d) != IslamicDay.verse(d.plusDays(1)));
        for (String[] v : IslamicDay.VERSES) assertTrue(v[0], v[0].length() <= 110);
    }
}
