package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CompetitionNameTest {
    @Test
    public void shortensTheCompetitionsTheClubsPlayIn() {
        assertEquals("EPL", SchedulePanel.competition("Premier League"));
        assertEquals("EPL", SchedulePanel.competition("English Premier League"));
        assertEquals("Serie A", SchedulePanel.competition("Italian Serie A"));
        assertEquals("Liga 1", SchedulePanel.competition("Indonesian Super League"));
        assertEquals("Liga 1", SchedulePanel.competition("Liga 1"));
        assertEquals("Uji Coba", SchedulePanel.competition("International Friendlies"));
        assertEquals("Piala AFF", SchedulePanel.competition("FIFA ASEAN Cup"));
        assertEquals("UCL", SchedulePanel.competition("UEFA Champions League"));
        assertEquals("UEL", SchedulePanel.competition("UEFA Europa League"));
        assertEquals("ACL Elite", SchedulePanel.competition("AFC Champions League Elite"));
        assertEquals("ACL 2", SchedulePanel.competition("AFC Champions League Two"));
        assertEquals("Kual. Piala Dunia", SchedulePanel.competition("FIFA World Cup Qualifying - AFC"));
        assertEquals("Piala Super UEFA", SchedulePanel.competition("UEFA Super Cup"));
        assertEquals("Coppa Italia", SchedulePanel.competition("Coppa Italia"));
        assertEquals("Piala Presiden", SchedulePanel.competition("Piala Presiden"));
    }
}
