package cn.dental.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 许可证日期按 Asia/Shanghai 和注入 Clock 判定的直接边界测试。 */
class BranchLicenseClockTest {
    @Test void validityIsComparedOnShanghaiCalendarDay() {
        Clock shanghaiMorning = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), ZoneOffset.UTC); // 10:00 +08
        assertEquals(LocalDate.of(2026, 10, 25), BranchService.shanghaiDate(shanghaiMorning));
        assertTrue(BranchService.licenseDateUsable(LocalDate.of(2026, 10, 25), false, shanghaiMorning));
        assertFalse(BranchService.licenseDateUsable(LocalDate.of(2026, 10, 24), false, shanghaiMorning));
        assertFalse(BranchService.licenseDateUsable(null, false, shanghaiMorning));
        assertTrue(BranchService.licenseDateUsable(null, true, shanghaiMorning));
        assertFalse(BranchService.licenseDateUsable(LocalDate.of(2026, 10, 25), true, shanghaiMorning));

        Clock shanghaiLastSecond = Clock.fixed(Instant.parse("2026-10-25T15:59:59Z"), ZoneOffset.UTC);
        assertTrue(BranchService.licenseDateUsable(LocalDate.of(2026, 10, 25), false, shanghaiLastSecond));
        Clock shanghaiNextDay = Clock.fixed(Instant.parse("2026-10-25T16:00:00Z"), ZoneOffset.UTC);
        assertEquals(LocalDate.of(2026, 10, 26), BranchService.shanghaiDate(shanghaiNextDay));
        assertFalse(BranchService.licenseDateUsable(LocalDate.of(2026, 10, 25), false, shanghaiNextDay));
    }
}
