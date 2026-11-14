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
        java.util.List<IslamicDay.Holiday> next = IslamicDay.nextIslamicHolidays(g(1448, 6, 3), -1, 2);
        assertEquals(2, next.size());
        assertEquals("Isra Mi'raj", next.get(0).name);
        assertEquals(g(1448, 7, 27), next.get(0).date);
        assertEquals("Awal Ramadan", next.get(1).name);
        LocalDate fitri = g(1448, 10, 1);
        assertEquals("Idul Fitri · hari ini", IslamicDay.holidayLine(IslamicDay.nextIslamicHolidays(fitri, -1, 1).get(0), fitri));
        assertEquals("Idul Fitri · besok",
                IslamicDay.holidayLine(IslamicDay.nextIslamicHolidays(fitri.minusDays(1), -1, 1).get(0), fitri.minusDays(1)));
    }

    @Test
    public void calendarMarks() {
        LocalDate isra = g(1448, 7, 27);
        assertTrue(IslamicDay.marks(isra, -1).contains("■"));
        assertEquals("Isra Mi'raj", IslamicDay.holiday(isra, -1));
        LocalDate bidh = g(1448, 6, 14);
        assertTrue(IslamicDay.marks(bidh, -1).contains("◆"));
        LocalDate ramadanMonday = g(1448, 9, 10);
        while (ramadanMonday.getDayOfWeek() != DayOfWeek.MONDAY) ramadanMonday = ramadanMonday.plusDays(1);
        assertEquals("", IslamicDay.marks(ramadanMonday, -1).replace("■", ""));
        assertEquals("https://everyayah.com/data/Alafasy_128kbps/001005.mp3",
                IslamicDay.audioUrl(new String[]{"", "", "QS. Al-Fatihah: 5", "001005"}));
    }

    @Test
    public void dayEventsAndSurahs() {
        assertEquals(java.util.Arrays.asList("Idul Adha"), IslamicDay.events(g(1448, 12, 10), -1));
        LocalDate plain = g(1448, 6, 20);
        while (plain.getDayOfWeek() != DayOfWeek.TUESDAY) plain = plain.plusDays(1);
        assertTrue(IslamicDay.events(plain, -1).isEmpty());
        assertTrue(IslamicDay.events(g(1448, 6, 13), -1).contains("Puasa Ayyamul Bidh"));
        assertEquals(114, IslamicDay.SURAH.length);
        assertEquals("Al-Fatihah", IslamicDay.SURAH[0]);
        assertEquals("An-Nas", IslamicDay.SURAH[113]);
        assertEquals("https://cdn.mp3quran.net/audio/mishary-alafasy/r1/036.mp3", IslamicDay.surahAudioUrl(36));
    }

    @Test
    public void dailyTexts() {
        LocalDate d = LocalDate.of(2026, 11, 14);
        assertTrue(IslamicDay.verse(d) != IslamicDay.verse(d.plusDays(1)));
        for (String[] v : IslamicDay.VERSES) {
            assertEquals(4, v.length);
            assertTrue(v[2], v[2].endsWith(": " + Integer.parseInt(v[3].substring(3))));
            assertFalse(v[2], v[0].startsWith("\u0628\u0650\u0633\u0652\u0645\u0650"));   // no Basmalah prefix
            assertTrue(v[2], v[1].length() <= 110);
        }
    }
}
