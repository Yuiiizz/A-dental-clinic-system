package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes new booking entry immediately. Existing appointments stay occupied until a separate,
 * explicit coordination flow changes them; registration is never a cancellation or notice.
 */
@Service
public class UnavailabilityService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public UnavailabilityService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> doctor(SessionService.Principal actor, long dentistId,
            String startsAt, String endsAt, String reason, String key) {
        Window window = window(startsAt, endsAt);
        String basis = reason(reason, 300);
        if (dentistId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> content = Map.of("doctor_id", dentistId,
            "starts_at", window.start().toString(), "ends_at", window.end().toString(), "reason", basis);
        IdempotencyService.Outcome outcome = idempotency.execute(actor.accountId(),
            "dentist-unavailability-create", key, IdempotencyService.canonicalize(content),
            () -> authorization.requireOrganizationOwner(actor),
            () -> {
                // Booking writers lock branch before schedule. Lock all affected branches in ID order.
                List<Long> branches = jdbc.queryForList("""
                    SELECT branch_id FROM branch WHERE branch_id IN (
                      SELECT DISTINCT branch_id FROM schedule WHERE dentist_id=? AND state='已发布'
                        AND starts_at<? AND ends_at>?) ORDER BY branch_id FOR UPDATE
                    """, Long.class, dentistId, Timestamp.from(window.end()), Timestamp.from(window.start()));
                authorization.requireOrganizationOwner(actor);
                Integer dentist = jdbc.queryForObject("""
                    SELECT count(*) FROM staff WHERE staff_id=? AND staff_type='牙医' AND employment_state='在岗'
                    """, Integer.class, dentistId);
                if (dentist == null || dentist != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
                long id = jdbc.queryForObject("""
                    INSERT INTO dentist_unavailable(dentist_id,starts_at,ends_at,reason,state,recorded_by,recorded_at)
                    VALUES(?,?,?,?,'有效',?,?) RETURNING absence_id
                    """, Long.class, dentistId, Timestamp.from(window.start()), Timestamp.from(window.end()),
                    basis, actor.staffId(), Timestamp.from(clock.instant()));
                audit.user(actor.accountId(), "dentist_unavailable", id, "create", null,
                    Map.of("state_to", "有效"), "登记停诊");
                return new IdempotencyService.BusinessResult(id, "ACTIVE", null);
            }, id -> {
                authorization.requireOrganizationOwner(actor);
                if (jdbc.queryForObject("SELECT count(*) FROM dentist_unavailable WHERE absence_id=? AND dentist_id=?",
                        Integer.class, id, dentistId) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return doctorView(outcome.targetId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> doctorList(SessionService.Principal actor, String requestedState,
            Integer requestedLimit) {
        authorization.requireOrganizationOwner(actor);
        String state = stateFilter(requestedState);
        int limit = limit(requestedLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT du.absence_id,du.dentist_id,s.name AS doctor_name,
                       du.starts_at,du.ends_at,du.reason,du.state,
                       du.recorded_by,du.recorded_at,du.resolved_at,du.resolved_by,du.resolve_basis
                FROM dentist_unavailable du JOIN staff s ON s.staff_id=du.dentist_id
                WHERE (?::text IS NULL OR du.state=?)
                ORDER BY du.starts_at DESC,du.absence_id DESC LIMIT ?
                """, state, state, limit)) {
            items.add(doctorItem(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("generated_at", clock.instant().toString());
        return result;
    }

    @Transactional
    public Map<String, Object> resolveDoctor(SessionService.Principal actor, long id,
            String resolveBasis, String key) {
        if (id <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String basis = reason(resolveBasis, 300);
        Map<String, Object> content = Map.of("absence_id", id, "resolve_basis", basis);
        idempotency.execute(actor.accountId(), "dentist-unavailability-resolve", key,
            IdempotencyService.canonicalize(content),
            () -> authorization.requireOrganizationOwner(actor),
            () -> {
                authorization.requireOrganizationOwner(actor);
                List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT state FROM dentist_unavailable WHERE absence_id=? FOR UPDATE
                    """, id);
                if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
                if (!"有效".equals(rows.getFirst().get("state")))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                Timestamp now = Timestamp.from(clock.instant());
                jdbc.update("""
                    UPDATE dentist_unavailable
                    SET state='已撤销',resolved_at=?,resolved_by=?,resolve_basis=?
                    WHERE absence_id=?
                    """, now, actor.staffId(), basis, id);
                audit.user(actor.accountId(), "dentist_unavailable", id, "resolve",
                    Map.of("state_from", "有效"), Map.of("state_to", "已撤销"), "人工解除医生停诊");
                return new IdempotencyService.BusinessResult(id, "RESOLVED", null);
            }, target -> {
                authorization.requireOrganizationOwner(actor);
                if (jdbc.queryForObject("SELECT count(*) FROM dentist_unavailable WHERE absence_id=?",
                        Integer.class, target) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return doctorView(id);
    }

    @Transactional
    public Map<String, Object> resource(SessionService.Principal actor, long resourceId,
            String kind, String startsAt, String endsAt, String reason, String key) {
        Window window = window(startsAt, endsAt);
        String basis = reason(reason, 200);
        if (resourceId <= 0 || !List.of("维护", "故障").contains(kind))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long branchId = resourceBranch(resourceId);
        Map<String, Object> content = Map.of("resource_id", resourceId, "kind", kind,
            "starts_at", window.start().toString(), "ends_at", window.end().toString(), "reason", basis);
        IdempotencyService.Outcome outcome = idempotency.execute(actor.accountId(),
            "resource-unavailability-create", key, IdempotencyService.canonicalize(content),
            () -> requireResourceManager(actor, branchId),
            () -> {
                lockBranch(branchId);
                requireResourceManager(actor, branchId);
                List<Long> resources = jdbc.queryForList(
                    "SELECT resource_id FROM resource WHERE resource_id=? AND branch_id=? FOR UPDATE",
                    Long.class, resourceId, branchId);
                if (resources.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
                long id = jdbc.queryForObject("""
                    INSERT INTO resource_unavailable(resource_id,kind,starts_at,ends_at,reason,state,recorded_by,recorded_at)
                    VALUES(?,?,?,?,?,'有效',?,?) RETURNING unavailable_id
                    """, Long.class, resourceId, kind, Timestamp.from(window.start()),
                    Timestamp.from(window.end()), basis, actor.staffId(), Timestamp.from(clock.instant()));
                audit.user(actor.accountId(), "resource_unavailable", id, "create", null,
                    Map.of("state_to", "有效"), "登记资源禁用");
                return new IdempotencyService.BusinessResult(id, "ACTIVE", null);
            }, id -> {
                requireResourceManager(actor, branchId);
                if (jdbc.queryForObject("""
                    SELECT count(*) FROM resource_unavailable u JOIN resource r ON r.resource_id=u.resource_id
                    WHERE u.unavailable_id=? AND u.resource_id=? AND r.branch_id=?
                    """, Integer.class, id, resourceId, branchId) != 1)
                    throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return resourceView(outcome.targetId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resourceList(SessionService.Principal actor, long branchId,
            String requestedState, Integer requestedLimit) {
        requireResourceManager(actor, branchId);
        String state = stateFilter(requestedState);
        int limit = limit(requestedLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT ru.unavailable_id,ru.resource_id,r.name AS resource_name,r.resource_type,
                       r.branch_id,b.name AS branch_name,ru.kind,ru.starts_at,ru.ends_at,ru.reason,
                       ru.state,ru.recorded_by,ru.recorded_at,ru.resolved_at,ru.resolved_by,
                       ru.resolve_type,ru.resolve_basis
                FROM resource_unavailable ru
                JOIN resource r ON r.resource_id=ru.resource_id
                JOIN branch b ON b.branch_id=r.branch_id
                WHERE r.branch_id=? AND (?::text IS NULL OR ru.state=?)
                ORDER BY ru.starts_at DESC,ru.unavailable_id DESC LIMIT ?
                """, branchId, state, state, limit)) {
            items.add(resourceItem(row));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("branch_id", Long.toString(branchId));
        result.put("generated_at", clock.instant().toString());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> resourceOptions(SessionService.Principal actor, long branchId) {
        requireResourceManager(actor, branchId);
        List<Map<String, Object>> items = jdbc.query("""
            SELECT r.resource_id,r.name,r.resource_type,r.operating_state,
                   r.branch_id,b.name AS branch_name
            FROM resource r JOIN branch b ON b.branch_id=r.branch_id
            WHERE r.branch_id=? ORDER BY r.resource_id
            """, (r, n) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resource_id", Long.toString(r.getLong("resource_id")));
                item.put("name", r.getString("name"));
                item.put("resource_type", r.getString("resource_type"));
                item.put("operating_state", r.getString("operating_state"));
                item.put("branch_id", Long.toString(r.getLong("branch_id")));
                item.put("branch_name", r.getString("branch_name"));
                return item;
            }, branchId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("branch_id", Long.toString(branchId));
        result.put("next_cursor", null);
        return result;
    }

    @Transactional
    public Map<String, Object> resolveResource(SessionService.Principal actor, long id,
            String resolveType, String resolveBasis, String key) {
        if (id <= 0 || !List.of("误登记撤销", "已修复").contains(resolveType))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String basis = reason(resolveBasis, 300);
        long branchId = unavailableBranch(id);
        Map<String, Object> content = Map.of("unavailable_id", id,
            "resolve_type", resolveType, "resolve_basis", basis);
        idempotency.execute(actor.accountId(), "resource-unavailability-resolve", key,
            IdempotencyService.canonicalize(content), () -> requireResourceManager(actor, branchId),
            () -> {
                lockBranch(branchId);
                requireResourceManager(actor, branchId);
                List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT u.state,u.resource_id FROM resource_unavailable u
                    JOIN resource r ON r.resource_id=u.resource_id
                    WHERE u.unavailable_id=? AND r.branch_id=? FOR UPDATE OF u
                    """, id, branchId);
                if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
                if (!"有效".equals(rows.getFirst().get("state")))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                jdbc.update("""
                    UPDATE resource_unavailable SET state='已撤销',resolved_at=?,resolved_by=?,
                      resolve_type=?,resolve_basis=? WHERE unavailable_id=?
                    """, Timestamp.from(clock.instant()), actor.staffId(), resolveType, basis, id);
                audit.user(actor.accountId(), "resource_unavailable", id, "resolve",
                    Map.of("state_from", "有效"), Map.of("state_to", "已撤销"), "人工解除资源禁用");
                return new IdempotencyService.BusinessResult(id, "RESOLVED", null);
            }, target -> {
                requireResourceManager(actor, branchId);
                if (jdbc.queryForObject("SELECT count(*) FROM resource_unavailable WHERE unavailable_id=?",
                        Integer.class, target) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return resourceView(id);
    }

    private void requireResourceManager(SessionService.Principal actor, long branchId) {
        authorization.requireBranch(actor, "branch.configure", branchId);
        Integer verified = jdbc.queryForObject("""
            SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=?
              AND enabled=true AND verified_at IS NOT NULL
            """, Integer.class, actor.staffId(), branchId);
        if (verified == null || verified != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private long resourceBranch(long id) {
        return jdbc.query("SELECT branch_id FROM resource WHERE resource_id=?",
            (rs, n) -> rs.getLong(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private long unavailableBranch(long id) {
        return jdbc.query("""
            SELECT r.branch_id FROM resource_unavailable u JOIN resource r ON r.resource_id=u.resource_id
            WHERE u.unavailable_id=?
            """, (rs, n) -> rs.getLong(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private void lockBranch(long branchId) {
        if (jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",
                Long.class, branchId).isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private Map<String, Object> doctorView(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT du.absence_id,du.dentist_id,s.name AS doctor_name,
                   du.starts_at,du.ends_at,du.reason,du.state,
                   du.recorded_by,du.recorded_at,du.resolved_at,du.resolved_by,du.resolve_basis
            FROM dentist_unavailable du JOIN staff s ON s.staff_id=du.dentist_id
            WHERE du.absence_id=?
            """, id);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return doctorItem(rows.getFirst());
    }

    private Map<String, Object> doctorItem(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        long absenceId = longValue(row.get("absence_id"));
        long dentistId = longValue(row.get("dentist_id"));
        result.put("absence_id", Long.toString(absenceId));
        result.put("doctor_id", Long.toString(dentistId));
        result.put("doctor_name", row.get("doctor_name"));
        result.put("starts_at", instant(row.get("starts_at")));
        result.put("ends_at", instant(row.get("ends_at")));
        result.put("reason", row.get("reason"));
        result.put("state", row.get("state"));
        result.put("recorded_by", nullableId(row.get("recorded_by")));
        result.put("recorded_at", instant(row.get("recorded_at")));
        result.put("resolved_at", instant(row.get("resolved_at")));
        result.put("resolved_by", nullableId(row.get("resolved_by")));
        result.put("resolve_basis", row.get("resolve_basis"));
        List<Map<String, Object>> affected = "有效".equals(row.get("state"))
            ? doctorAffected(dentistId, timestamp(row.get("starts_at")), timestamp(row.get("ends_at")))
            : List.of();
        result.put("affected_count", affected.size());
        result.put("affected_appointments", affected);
        return result;
    }

    private Map<String, Object> resourceView(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT ru.unavailable_id,ru.resource_id,r.name AS resource_name,r.resource_type,
                   r.branch_id,b.name AS branch_name,ru.kind,ru.starts_at,ru.ends_at,ru.reason,
                   ru.state,ru.recorded_by,ru.recorded_at,ru.resolved_at,ru.resolved_by,
                   ru.resolve_type,ru.resolve_basis
            FROM resource_unavailable ru
            JOIN resource r ON r.resource_id=ru.resource_id
            JOIN branch b ON b.branch_id=r.branch_id
            WHERE ru.unavailable_id=?
            """, id);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return resourceItem(rows.getFirst());
    }

    private Map<String, Object> resourceItem(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        long id = longValue(row.get("unavailable_id"));
        long resourceId = longValue(row.get("resource_id"));
        result.put("unavailable_id", Long.toString(id));
        result.put("resource_id", Long.toString(resourceId));
        result.put("resource_name", row.get("resource_name"));
        result.put("resource_type", row.get("resource_type"));
        result.put("branch_id", Long.toString(longValue(row.get("branch_id"))));
        result.put("branch_name", row.get("branch_name"));
        result.put("kind", row.get("kind"));
        result.put("starts_at", instant(row.get("starts_at")));
        result.put("ends_at", instant(row.get("ends_at")));
        result.put("reason", row.get("reason"));
        result.put("state", row.get("state"));
        result.put("recorded_by", nullableId(row.get("recorded_by")));
        result.put("recorded_at", instant(row.get("recorded_at")));
        result.put("resolved_at", instant(row.get("resolved_at")));
        result.put("resolved_by", nullableId(row.get("resolved_by")));
        result.put("resolve_type", row.get("resolve_type"));
        result.put("resolve_basis", row.get("resolve_basis"));
        List<Map<String, Object>> affected = "有效".equals(row.get("state"))
            ? resourceAffected(resourceId, timestamp(row.get("starts_at")), timestamp(row.get("ends_at")))
            : List.of();
        result.put("affected_count", affected.size());
        result.put("affected_appointments", affected);
        return result;
    }

    private List<Map<String, Object>> doctorAffected(long dentistId, Timestamp start, Timestamp end) {
        return jdbc.query("""
            SELECT a.appointment_id,a.starts_at,a.ends_at,a.state,a.coordination_state,
                   a.branch_id,b.name AS branch_name,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id LIMIT 1) AS item_name
            FROM appointment a JOIN branch b ON b.branch_id=a.branch_id
            WHERE a.confirmed_dentist_id=? AND a.state IN ('已确认','候诊中','接诊中') AND a.ends_at>?
              AND tstzrange(?,?,'[)') && tstzrange(a.starts_at,a.ends_at,'[)')
            ORDER BY a.starts_at,a.appointment_id
            """, (r, n) -> affectedRow(r), dentistId, Timestamp.from(clock.instant()), start, end);
    }

    private List<Map<String, Object>> resourceAffected(long resourceId, Timestamp start, Timestamp end) {
        return jdbc.query("""
            SELECT a.appointment_id,a.starts_at,a.ends_at,a.state,a.coordination_state,
                   a.branch_id,b.name AS branch_name,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id LIMIT 1) AS item_name
            FROM appointment a JOIN branch b ON b.branch_id=a.branch_id
            WHERE a.state IN ('已确认','候诊中','接诊中') AND a.ends_at>?
              AND tstzrange(?,?,'[)') && tstzrange(a.starts_at,a.ends_at,'[)')
              AND EXISTS (SELECT 1 FROM resource_booking rb
                WHERE rb.appointment_id=a.appointment_id AND rb.resource_id=? AND rb.state='有效'
                  AND tstzrange(rb.starts_at,rb.ends_at,'[)') && tstzrange(a.starts_at,a.ends_at,'[)'))
            ORDER BY a.starts_at,a.appointment_id
            """, (r, n) -> affectedRow(r), Timestamp.from(clock.instant()), start, end, resourceId);
    }

    private Map<String, Object> affectedRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("appointment_id", Long.toString(r.getLong("appointment_id")));
        item.put("starts_at", r.getTimestamp("starts_at").toInstant().toString());
        item.put("ends_at", r.getTimestamp("ends_at").toInstant().toString());
        item.put("state", r.getString("state"));
        item.put("coordination_state", r.getString("coordination_state"));
        item.put("item_name", r.getString("item_name"));
        item.put("branch_id", Long.toString(r.getLong("branch_id")));
        item.put("branch_name", r.getString("branch_name"));
        return item;
    }

    private static Long longValue(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String nullableId(Object value) {
        return value == null ? null : Long.toString(((Number) value).longValue());
    }

    private static Timestamp timestamp(Object value) {
        return value == null ? null : (Timestamp) value;
    }

    private static String instant(Object value) {
        return value == null ? null : ((Timestamp) value).toInstant().toString();
    }

    private static String stateFilter(String state) {
        if (state == null || state.isBlank()) return null;
        String normalized = state.strip();
        if (!List.of("有效", "已撤销").contains(normalized))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return normalized;
    }

    private static int limit(Integer requested) {
        if (requested == null) return 50;
        if (requested < 1 || requested > 100) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return requested;
    }

    private static String reason(String raw, int limit) {
        if (raw == null || raw.isBlank() || raw.strip().length() > limit)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return raw.strip();
    }

    private static Window window(String start, String end) {
        try {
            Instant s = OffsetDateTime.parse(start).toInstant();
            Instant e = OffsetDateTime.parse(end).toInstant();
            if (!e.isAfter(s)) throw new IllegalArgumentException();
            return new Window(s, e);
        } catch (Exception ex) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
    }

    private record Window(Instant start, Instant end) {}
}
