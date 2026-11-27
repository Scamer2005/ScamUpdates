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
}
