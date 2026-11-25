package cn.dental.organization;

import cn.dental.organization.application.BookingAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Database failures must not be translated into "slot unavailable". */
class BookingAvailabilityExceptionTest {
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2030-01-02T00:50:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test void branchLookupDatabaseFailurePropagates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var service = new BookingAvailabilityService(jdbc, CLOCK);
        assertThrows(DataAccessResourceFailureException.class,
            () -> service.requireAvailable(1, 1, 1, Instant.parse("2030-01-02T01:00:00Z"), false));
    }

    @Test void scheduleLookupDatabaseFailureAfterBranchLookupPropagates() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
            .thenReturn(List.of(1L));
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
            .thenReturn(Map.of("state", "营业", "verified_at", java.sql.Timestamp.valueOf("2029-01-01 00:00:00")));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var service = new BookingAvailabilityService(jdbc, CLOCK);
        assertThrows(DataAccessResourceFailureException.class,
            () -> service.requireAvailable(1, 1, 1, Instant.parse("2030-01-02T01:00:00Z"), false));
    }
}
