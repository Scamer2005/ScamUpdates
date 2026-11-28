package de.scamupdates.restart;

import de.scamupdates.logging.ScamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class RestartScheduleStoreTest {

    @TempDir Path dir;

    @Test
    void recurringRulesAndOneTimeRestartPersistAndCalculateNextTrigger() {
        ScamLog log = new ScamLog(Logger.getLogger("ScheduleTest"), null);
        Path file = dir.resolve("restarts.yml");

        RestartScheduleStore store = new RestartScheduleStore(file, log);
        var daily = store.addRule("Nacht-Neustart", null, 4, 30, true);
        var sunday = store.addRule("Sonntag", DayOfWeek.SUNDAY, 6, 0, false);
        assertEquals(2, store.allRules().size());

        Instant now = ZonedDateTime.of(2026, 6, 10, 5, 0, 0, 0, ZoneOffset.UTC).toInstant(); // Mittwoch 05:00 UTC
        long expectedDaily = ZonedDateTime.of(2026, 6, 11, 4, 30, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        long expectedSunday = ZonedDateTime.of(2026, 6, 14, 6, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();

        assertEquals(expectedDaily, daily.nextTriggerMillis(now, ZoneOffset.UTC).orElseThrow());
        assertEquals(expectedSunday, sunday.nextTriggerMillis(now, ZoneOffset.UTC).orElseThrow());
        assertEquals(expectedDaily, store.nextTriggerMillis(now, ZoneOffset.UTC).orElseThrow());

        long oneTimeAt = ZonedDateTime.of(2026, 6, 10, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        var one = store.scheduleOneTime(oneTimeAt, "Admin", "Wartung", false);
        assertFalse(one.isDue(now.toEpochMilli()));
        assertTrue(one.isDue(oneTimeAt));

        RestartScheduleStore reloaded = new RestartScheduleStore(file, log);
        assertEquals(2, reloaded.allRules().size());
        assertTrue(reloaded.oneTime().isPresent());
        assertEquals(oneTimeAt, reloaded.nextTriggerMillis(now, ZoneOffset.UTC).orElseThrow());

        assertTrue(reloaded.setRuleEnabled(daily.id(), false));
        assertTrue(reloaded.cancelOneTime());
        assertEquals(expectedSunday, reloaded.nextTriggerMillis(now, ZoneOffset.UTC).orElseThrow());
        assertTrue(reloaded.removeRule(daily.id()));
        assertEquals(1, new RestartScheduleStore(file, log).allRules().size());
    }

    @Test
    void rejectsOutOfRangeHourOrMinute() {
        assertThrows(IllegalArgumentException.class, () -> new RestartScheduleStore.ScheduleRule("1", "", null, 24, 0, true, false));
        assertThrows(IllegalArgumentException.class, () -> new RestartScheduleStore.ScheduleRule("1", "", null, 12, 60, true, false));
    }

    @Test
    void hourlyAndMultiDayAndEditWorkCorrectly() {
        ScamLog log = new ScamLog(Logger.getLogger("ScheduleTest"), null);
        RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts-multi.yml"), log);

        var hourly = store.addRule("Stuendlich", RestartScheduleStore.Frequency.HOURLY, java.util.Set.of(), 0, 15, false);
        var multi = store.addRule("MoFr", RestartScheduleStore.Frequency.MULTI_DAY,
                java.util.Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), 4, 30, false);

        Instant now = ZonedDateTime.of(2026, 6, 10, 14, 10, 0, 0, RestartScheduleStore.DEFAULT_ZONE).toInstant(); // Mi 14:10 CEST
        long expectedHourly = ZonedDateTime.of(2026, 6, 10, 14, 15, 0, 0, RestartScheduleStore.DEFAULT_ZONE).toInstant().toEpochMilli();
        long expectedFriday = ZonedDateTime.of(2026, 6, 12, 4, 30, 0, 0, RestartScheduleStore.DEFAULT_ZONE).toInstant().toEpochMilli();
        assertEquals(expectedHourly, hourly.nextTriggerMillis(now, RestartScheduleStore.DEFAULT_ZONE).orElseThrow());
        assertEquals(expectedFriday, multi.nextTriggerMillis(now, RestartScheduleStore.DEFAULT_ZONE).orElseThrow());

        var edited = store.updateRule(multi.id(), "Wochenende", RestartScheduleStore.Frequency.MULTI_DAY,
                java.util.Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), 5, 0, true, true);
        assertEquals("Wochenende", edited.label());
        assertTrue(edited.requirePending());
        assertEquals(2, new RestartScheduleStore(dir.resolve("restarts-multi.yml"), log).allRules().size());
    }

    @Test
    void europeBerlinDstTransitionsSpringForwardAndFallBackAreHandledCleanly() {
        var berlin = RestartScheduleStore.DEFAULT_ZONE;
        // Sommerzeit-Umstellung 2026: Sonntag 29.03.2026 02:00 -> 03:00
        var daily0230 = new RestartScheduleStore.ScheduleRule("dst1", "DST", RestartScheduleStore.Frequency.DAILY,
                java.util.Set.of(), 2, 30, true, false);
        Instant beforeSpring = ZonedDateTime.of(2026, 3, 29, 0, 30, 0, 0, berlin).toInstant();
        long springTrigger = daily0230.nextTriggerMillis(beforeSpring, berlin).orElseThrow();
        ZonedDateTime springZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(springTrigger), berlin);
        assertEquals(2026, springZdt.getYear());
        assertEquals(3, springZdt.getMonthValue());
        assertEquals(29, springZdt.getDayOfMonth());
        assertEquals(3, springZdt.getHour(), "Nicht existierende 02:30-Stunde verschiebt sich in Europe/Berlin sauber auf 03:30 CEST");
        assertEquals(30, springZdt.getMinute());

        // Stuendlich ueber Zeitumstellung zurueck (Sonntag 25.10.2026 02:00 CEST -> 02:00 CET)
        var hourly15 = new RestartScheduleStore.ScheduleRule("dst2", "HourlyDST", RestartScheduleStore.Frequency.HOURLY,
                java.util.Set.of(), 0, 15, true, false);
        Instant at0150Cest = ZonedDateTime.of(2026, 10, 25, 1, 50, 0, 0, berlin).toInstant();
        long nextFall = hourly15.nextTriggerMillis(at0150Cest, berlin).orElseThrow();
        assertTrue(nextFall > at0150Cest.toEpochMilli());
        assertEquals(15, ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextFall), berlin).getMinute());
    }

    @Test
    void detectsDuplicateAndOverlappingScheduleConflicts() {
        ScamLog log = new ScamLog(Logger.getLogger("ScheduleConflictTest"), null);
        RestartScheduleStore store = new RestartScheduleStore(dir.resolve("restarts-conflict.yml"), log);

        var base = store.addRule("Nacht", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false);

        // Exaktes Duplikat (DAILY 04:00)
        assertThrows(RestartScheduleStore.ScheduleConflictException.class,
                () -> store.addRule("Duplikat", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false));
        // Ueberschneidung auf gemeinsamem Wochentag < 5 Minuten
        assertThrows(RestartScheduleStore.ScheduleConflictException.class,
                () -> store.addRule("ZuNah", RestartScheduleStore.Frequency.WEEKLY,
                        java.util.Set.of(DayOfWeek.MONDAY), 4, 3, false));

        // Deaktivierte Regel blockiert keine neue Regel; Re-Aktivierung bei bestehendem Konflikt wird abgelehnt
        store.setRuleEnabled(base.id(), false);
        assertDoesNotThrow(() -> store.addRule("Neu", RestartScheduleStore.Frequency.DAILY, java.util.Set.of(), 4, 0, false));
        assertThrows(RestartScheduleStore.ScheduleConflictException.class, () -> store.setRuleEnabled(base.id(), true));
    }

    @Test
    void europeBerlin2027SpringForwardHandlesAllFrequenciesCleanly() {
        var berlin = RestartScheduleStore.DEFAULT_ZONE; // Europe/Berlin
        // Zeitumstellung Fruehjahr 2027: Sonntag, 28.03.2027, 02:00 CET (+01:00) -> 03:00 CEST (+02:00)
        Instant before = ZonedDateTime.of(2027, 3, 28, 0, 45, 0, 0, berlin).toInstant();

        var daily = new RestartScheduleStore.ScheduleRule("d27", "Daily", RestartScheduleStore.Frequency.DAILY,
                java.util.Set.of(), 2, 30, true, false);
        long dailyMs = daily.nextTriggerMillis(before, berlin).orElseThrow();
        ZonedDateTime dailyZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dailyMs), berlin);
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, berlin), dailyZdt);
        // Naechster Tag 29.03.2027 wieder regulaer um 02:30 CEST
        long nextDayMs = daily.nextTriggerMillis(dailyZdt.toInstant(), berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 29, 2, 30, 0, 0, berlin),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextDayMs), berlin));

        var weekly = new RestartScheduleStore.ScheduleRule("w27", "Weekly", RestartScheduleStore.Frequency.WEEKLY,
                java.util.Set.of(DayOfWeek.SUNDAY), 2, 30, true, false);
        long weeklyMs = weekly.nextTriggerMillis(before, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, berlin),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(weeklyMs), berlin));
        long nextSundayMs = weekly.nextTriggerMillis(Instant.ofEpochMilli(weeklyMs), berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 4, 4, 2, 30, 0, 0, berlin),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(nextSundayMs), berlin));

        var multiDay = new RestartScheduleStore.ScheduleRule("m27", "MultiDay", RestartScheduleStore.Frequency.MULTI_DAY,
                java.util.Set.of(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY), 2, 15, true, false);
        long multiMs = multiDay.nextTriggerMillis(before, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 15, 0, 0, berlin),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(multiMs), berlin));
        long wedMs = multiDay.nextTriggerMillis(Instant.ofEpochMilli(multiMs), berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 31, 2, 15, 0, 0, berlin),
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(wedMs), berlin));

        var hourly = new RestartScheduleStore.ScheduleRule("h27", "Hourly", RestartScheduleStore.Frequency.HOURLY,
                java.util.Set.of(), 0, 30, true, false);
        long h1 = hourly.nextTriggerMillis(before, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 28, 1, 30, 0, 0, berlin).toInstant().toEpochMilli(), h1);
        Instant at0145 = ZonedDateTime.of(2027, 3, 28, 1, 45, 0, 0, berlin).toInstant();
        long h2 = hourly.nextTriggerMillis(at0145, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, berlin).toInstant().toEpochMilli(), h2);
        assertEquals(45 * 60_000L, h2 - at0145.toEpochMilli(), "Realer Zeitabstand ueber Sprungstunde betraegt exakt 45 Minuten");
    }

    @Test
    void europeBerlin2027FallBackHandlesHourlyOverlapAndPreventsDailyWeeklyMultiDayDuplicates() {
        var berlin = RestartScheduleStore.DEFAULT_ZONE; // Europe/Berlin
        // Zeitumstellung Herbst 2027: Sonntag, 31.10.2027, 03:00 CEST (+02:00) -> 02:00 CET (+01:00)
        var hourly = new RestartScheduleStore.ScheduleRule("h27f", "Hourly", RestartScheduleStore.Frequency.HOURLY,
                java.util.Set.of(), 0, 15, true, false);
        Instant at0130Cest = ZonedDateTime.of(2027, 10, 31, 1, 30, 0, 0, berlin).toInstant();
        long t1 = hourly.nextTriggerMillis(at0130Cest, berlin).orElseThrow(); // 02:15+02:00
        long t2 = hourly.nextTriggerMillis(Instant.ofEpochMilli(t1), berlin).orElseThrow(); // 02:15+01:00
        long t3 = hourly.nextTriggerMillis(Instant.ofEpochMilli(t2), berlin).orElseThrow(); // 03:15+01:00

        assertEquals(ZonedDateTime.of(2027, 10, 31, 2, 15, 0, 0, berlin).withEarlierOffsetAtOverlap().toInstant().toEpochMilli(), t1);
        assertEquals(ZonedDateTime.of(2027, 10, 31, 2, 15, 0, 0, berlin).withLaterOffsetAtOverlap().toInstant().toEpochMilli(), t2);
        assertEquals(3_600_000L, t2 - t1, "Beide 02:15-Durchlaeufe muessen exakt 60 Minuten auseinander liegen");
        assertEquals(3_600_000L, t3 - t2, "Uebergang auf 03:15+01:00 betraegt exakt 60 Minuten");

        Instant at0100Cest = ZonedDateTime.of(2027, 10, 31, 1, 0, 0, 0, berlin).toInstant();
        long expectedFirst0230 = ZonedDateTime.of(2027, 10, 31, 2, 30, 0, 0, berlin).withEarlierOffsetAtOverlap().toInstant().toEpochMilli();
        Instant duringSecondHour0205Cet = ZonedDateTime.of(2027, 10, 31, 2, 5, 0, 0, berlin).withLaterOffsetAtOverlap().toInstant();

        var daily = new RestartScheduleStore.ScheduleRule("d27f", "Daily", RestartScheduleStore.Frequency.DAILY,
                java.util.Set.of(), 2, 30, true, false);
        assertEquals(expectedFirst0230, daily.nextTriggerMillis(at0100Cest, berlin).orElseThrow());
        long afterDaily = daily.nextTriggerMillis(duringSecondHour0205Cet, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 11, 1, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(), afterDaily,
                "DAILY darf in der doppelten 02:00-Stunde niemals doppelt ausloesen");

        var weekly = new RestartScheduleStore.ScheduleRule("w27f", "Weekly", RestartScheduleStore.Frequency.WEEKLY,
                java.util.Set.of(DayOfWeek.SUNDAY), 2, 30, true, false);
        assertEquals(expectedFirst0230, weekly.nextTriggerMillis(at0100Cest, berlin).orElseThrow());
        long afterWeekly = weekly.nextTriggerMillis(duringSecondHour0205Cet, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 11, 7, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(), afterWeekly,
                "WEEKLY darf in der doppelten 02:00-Stunde niemals doppelt ausloesen");

        var multi = new RestartScheduleStore.ScheduleRule("m27f", "Multi", RestartScheduleStore.Frequency.MULTI_DAY,
                java.util.Set.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), 2, 30, true, false);
        assertEquals(expectedFirst0230, multi.nextTriggerMillis(at0100Cest, berlin).orElseThrow());
        long afterMulti = multi.nextTriggerMillis(duringSecondHour0205Cet, berlin).orElseThrow();
        assertEquals(ZonedDateTime.of(2027, 11, 1, 2, 30, 0, 0, berlin).toInstant().toEpochMilli(), afterMulti,
                "MULTI_DAY darf in der doppelten 02:00-Stunde niemals doppelt ausloesen");
    }
}
