package cn.dental.organization.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Allocates one available chair for an appointment interval under a branch-scoped advisory lock. */
@Service
public class ResourceAllocationService {
    private final JdbcTemplate jdbc;

    public ResourceAllocationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Long reserve(long branchId, long appointmentId, Timestamp startsAt, Timestamp endsAt) {
        // Serialize chair selection per branch; the exclusion constraint remains the final guard.
        jdbc.execute("SELECT pg_advisory_xact_lock(" + (710000L + branchId) + ")");
        List<Long> resources = jdbc.queryForList("""
            SELECT r.resource_id FROM resource r
            WHERE r.branch_id=? AND r.operating_state='可用' AND r.resource_type='牙椅'
              AND NOT EXISTS (
                SELECT 1 FROM resource_unavailable u
                WHERE u.resource_id=r.resource_id AND u.state='有效' AND u.starts_at < ?
              )
              AND NOT EXISTS (
                SELECT 1 FROM resource_booking rb
                WHERE rb.resource_id=r.resource_id AND rb.state='有效'
                  AND tstzrange(rb.starts_at,rb.ends_at,'[)') && tstzrange(?,?,'[)')
              )
            ORDER BY r.resource_id LIMIT 1
            """, Long.class, branchId, endsAt, startsAt, endsAt);
        if (resources.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        long resourceId = resources.getFirst();
        jdbc.update("""
            INSERT INTO resource_booking(appointment_id,resource_id,starts_at,ends_at,state)
            VALUES(?,?,?,?,'有效')
            """, appointmentId, resourceId, startsAt, endsAt);
        return resourceId;
    }

    public void release(long appointmentId) {
        jdbc.update("UPDATE resource_booking SET state='已释放' WHERE appointment_id=? AND state='有效'", appointmentId);
    }
}
