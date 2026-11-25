package cn.dental.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 营业区间日期范围和“今天”边界的注入 Clock 测试。 */
class OpeningIntervalClockTest {
    @Test void rangeAndShanghaiTodayBoundary() {
        LocalDate day = LocalDate.of(2026, 10, 25);
        assertEquals(1, OpeningIntervalService.rangeDays(day, day));
        assertEquals(31, OpeningIntervalService.rangeDays(day, day.plusDays(30)));
        assertEquals(32, OpeningIntervalService.rangeDays(day, day.plusDays(31)));

        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-25T15:59:59Z"), ZoneOffset.UTC); // 23:59:59 +08
        assertFalse(OpeningIntervalService.isBeforeToday(day, beforeMidnight));
        assertTrue(OpeningIntervalService.isBeforeToday(day.minusDays(1), beforeMidnight));

        Clock afterMidnight = Clock.fixed(Instant.parse("2026-10-25T16:00:00Z"), ZoneOffset.UTC); // 次日 00:00 +08
        assertTrue(OpeningIntervalService.isBeforeToday(day, afterMidnight));
        assertFalse(OpeningIntervalService.isBeforeToday(day.plusDays(1), afterMidnight));
    }
}
