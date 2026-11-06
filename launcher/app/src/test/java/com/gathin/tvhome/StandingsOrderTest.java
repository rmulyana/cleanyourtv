package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

/** Standings rows: by position, ties and unranked clubs by name. */
public class StandingsOrderTest {
    @Test
    public void byRankThenName() {
        String[] names = {"Persib", "Man Utd", "Inter"};
        assertArrayEquals(new Integer[]{2, 0, 1}, SchedulePanel.order(names, new String[]{"4 16", "17 7", "2 25"}));
        assertArrayEquals(new Integer[]{2, 1, 0}, SchedulePanel.order(names, new String[]{"1 9", "1 9", "1 9"}));
        // Outside the top 5 ("") and not loaded yet (null) go last, by name.
        assertArrayEquals(new Integer[]{1, 2, 0}, SchedulePanel.order(names, new String[]{"", "3 10", null}));
        assertArrayEquals(new Integer[]{2, 1, 0}, SchedulePanel.order(names, null));
    }
}
