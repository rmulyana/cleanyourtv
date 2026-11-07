package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StandingsFormTest {
    @Test
    public void keepsTheLastFiveOldestFirstWhateverTheInputOrder() {
        List<String[]> r = new ArrayList<>();
        String[] days = {"2026-10-31", "2026-08-20", "2026-09-14", "2026-10-03", "2026-09-01", "2026-10-20"};
        String[] res = {"W", "L", "D", "L", "W", "W"};
        for (int i = 0; i < days.length; i++) r.add(new String[]{days[i] + "T15:00Z", "360", res[i]});
        r.add(new String[]{"2026-10-31T15:00Z", "1", "L"});
        Map<String, String> form = StandingsDialog.lastFive(r);
        assertEquals("WDLWW", form.get("360"));   // 20 Aug (L) drops out
        assertEquals("L", form.get("1"));
    }
}
