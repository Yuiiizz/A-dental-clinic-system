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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records a human contact attempt about an affected appointment, never a notification receipt. */
@Service
public class CoordinationContactService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public CoordinationContactService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.authorization = authorization; this.idempotency = idempotency;
        this.audit = audit; this.clock = clock;
    }

    public record ContactCommand(long appointmentId, String channel, String result,
                                 String acknowledgmentBasis, String nextContactAt) {
        @Override public String toString() { return "ContactCommand[redacted]"; }
    }

    @Transactional
    public Map<String, Object> record(SessionService.Principal actor, ContactCommand command, String key) {
        if (command == null || command.appointmentId() <= 0
                || !List.of("电话", "微信登记", "当面").contains(command.channel()))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String result = required(command.result(), 300);
        String acknowledgment = optional(command.acknowledgmentBasis(), 300);
        Instant next = parseNext(command.nextContactAt());
        long branch = appointmentBranch(command.appointmentId());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("appointment_id", command.appointmentId());
        content.put("channel", command.channel());
        content.put("result", result);
        content.put("acknowledgment_basis", acknowledgment);
        content.put("next_contact_at", next == null ? null : next.toString());
        var outcome = idempotency.execute(actor.accountId(), "appointment-manual-contact", key,
            IdempotencyService.canonicalize(content), () -> requireFrontdesk(actor, branch),
            () -> {
                jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branch);
                requireFrontdesk(actor, branch);
                List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT appointment_id,state,coordination_state,confirmed_dentist_id,starts_at,ends_at
                    FROM appointment WHERE appointment_id=? AND branch_id=? FOR UPDATE
                    """, command.appointmentId(), branch);
                if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
                Map<String, Object> appointment = rows.getFirst();
                if (!List.of("已确认", "候诊中", "接诊中").contains(appointment.get("state"))
                        || !affected(command.appointmentId(), appointment))
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                Instant now = clock.instant();
                if (next != null && !next.isAfter(now)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                long contactId = jdbc.queryForObject("""
                    INSERT INTO contact_record(appointment_id,kind,channel,contacted_at,state,result,
                      acknowledged_at,acknowledgment_basis,next_contact_at,staff_id)
                    VALUES(?,'人工联系',?,?,'已联系',?,?,?,?,?) RETURNING contact_id
                    """, Long.class, command.appointmentId(), command.channel(), Timestamp.from(now),
                    result, acknowledgment == null ? null : Timestamp.from(now), acknowledgment,
                    next == null ? null : Timestamp.from(next), actor.staffId());
                if ("无需协调".equals(appointment.get("coordination_state")))
                    jdbc.update("UPDATE appointment SET coordination_state='待协调',version=version+1 WHERE appointment_id=?",
                        command.appointmentId());
                audit.user(actor.accountId(), "contact_record", contactId, "manual_contact", null,
                    Map.of("state_to", "已联系"), "登记人工联系尝试");
                return new IdempotencyService.BusinessResult(contactId, "RECORDED", null);
            }, id -> {
                requireFrontdesk(actor, branch);
                if (jdbc.queryForObject("""
                    SELECT count(*) FROM contact_record c JOIN appointment a ON a.appointment_id=c.appointment_id
                    WHERE c.contact_id=? AND a.appointment_id=? AND a.branch_id=?
                    """, Integer.class, id, command.appointmentId(), branch) != 1)
                    throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return detail(outcome.targetId());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(SessionService.Principal actor, long branch, Long cursor, int limit) {
        if (branch <= 0 || cursor != null && cursor <= 0 || limit < 1 || limit > 100)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        requireFrontdesk(actor, branch);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.contact_id,c.appointment_id,c.channel,c.state,c.result,c.contacted_at,
                   c.acknowledged_at,c.next_contact_at,c.staff_id
            FROM contact_record c JOIN appointment a ON a.appointment_id=c.appointment_id
            WHERE a.branch_id=? AND c.kind='人工联系' AND (?::bigint IS NULL OR c.contact_id<?)
            ORDER BY c.contact_id DESC LIMIT ?
            """, branch, cursor, cursor, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? page.getLast().get("contact_id").toString() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", page.stream().map(this::view).toList());
        result.put("next_cursor", next);
        return result;
    }

    /** A live work queue, derived from affected future bookings and the latest actual human contact. */
    @Transactional(readOnly = true)
    public Map<String, Object> due(SessionService.Principal actor, long branch, Long cursor, int limit) {
        if (branch <= 0 || cursor != null && cursor <= 0 || limit < 1 || limit > 100)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        requireFrontdesk(actor, branch);
        Instant now=clock.instant();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT a.appointment_id,a.starts_at,c.contact_id,c.contacted_at,c.next_contact_at,
                   c.acknowledged_at
            FROM appointment a
            LEFT JOIN LATERAL (
              SELECT contact_id,contacted_at,next_contact_at,acknowledged_at
              FROM contact_record WHERE appointment_id=a.appointment_id AND kind='人工联系'
              ORDER BY contact_id DESC LIMIT 1
            ) c ON true
            WHERE a.branch_id=? AND a.state IN ('已确认','候诊中','接诊中') AND a.ends_at>?
              AND (?::bigint IS NULL OR a.appointment_id>?)
              AND (EXISTS (SELECT 1 FROM dentist_unavailable du
                    WHERE du.dentist_id=a.confirmed_dentist_id AND du.state='有效'
                      AND tstzrange(du.starts_at,du.ends_at,'[)') && tstzrange(a.starts_at,a.ends_at,'[)'))
                OR EXISTS (SELECT 1 FROM resource_booking rb JOIN resource_unavailable ru
                    ON ru.resource_id=rb.resource_id AND ru.state='有效'
                    WHERE rb.appointment_id=a.appointment_id AND rb.state='有效'
                      AND ru.starts_at<a.ends_at))
              AND (c.contact_id IS NULL OR c.next_contact_at<=?
                   OR (c.acknowledged_at IS NULL AND c.next_contact_at IS NULL))
            ORDER BY a.appointment_id LIMIT ?
            """,branch,Timestamp.from(now),cursor,cursor,Timestamp.from(now),limit+1);
        boolean more=rows.size()>limit;
        List<Map<String,Object>> page=rows.subList(0,Math.min(limit,rows.size()));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("items",page.stream().map(row->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("appointment_id",row.get("appointment_id").toString());
            item.put("starts_at",((Timestamp)row.get("starts_at")).toInstant().toString());
            item.put("last_contact_id",row.get("contact_id")==null?null:row.get("contact_id").toString());
            item.put("last_contacted_at",row.get("contacted_at")==null?null:
                ((Timestamp)row.get("contacted_at")).toInstant().toString());
            item.put("next_contact_at",row.get("next_contact_at")==null?null:
                ((Timestamp)row.get("next_contact_at")).toInstant().toString());
            item.put("acknowledged",row.get("acknowledged_at")!=null);
            return item;
        }).toList());
        out.put("next_cursor",more?page.getLast().get("appointment_id").toString():null);
        return out;
    }

    private Map<String, Object> detail(long contactId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT contact_id,appointment_id,channel,state,result,contacted_at,acknowledged_at,
                   next_contact_at,staff_id FROM contact_record WHERE contact_id=?
            """, contactId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return view(rows.getFirst());
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contact_id", row.get("contact_id").toString());
        result.put("appointment_id", row.get("appointment_id").toString());
        result.put("kind", "人工联系");
        result.put("channel", row.get("channel"));
        result.put("state", row.get("state"));
        result.put("result", row.get("result"));
        result.put("contacted_at", ((Timestamp) row.get("contacted_at")).toInstant().toString());
        result.put("acknowledged", row.get("acknowledged_at") != null);
        result.put("next_contact_at", row.get("next_contact_at") == null ? null
            : ((Timestamp) row.get("next_contact_at")).toInstant().toString());
        result.put("staff_id", row.get("staff_id").toString());
        return result;
    }

    private boolean affected(long appointmentId, Map<String, Object> appointment) {
        Timestamp start = (Timestamp) appointment.get("starts_at");
        Timestamp end = (Timestamp) appointment.get("ends_at");
        if (!end.toInstant().isAfter(clock.instant())) return false;
        Integer count = jdbc.queryForObject("""
            SELECT (SELECT count(*) FROM dentist_unavailable du WHERE du.dentist_id=? AND du.state='有效'
                    AND tstzrange(du.starts_at,du.ends_at,'[)') && tstzrange(?,?,'[)'))
                 + (SELECT count(*) FROM resource_booking rb JOIN resource_unavailable ru
                      ON ru.resource_id=rb.resource_id AND ru.state='有效'
                    WHERE rb.appointment_id=? AND rb.state='有效' AND ru.starts_at<?)
            """, Integer.class, appointment.get("confirmed_dentist_id"), start, end, appointmentId, end);
        return count != null && count > 0;
    }

    private long appointmentBranch(long id) {
        return jdbc.query("SELECT branch_id FROM appointment WHERE appointment_id=?",
            (rs, n) -> rs.getLong(1), id).stream().findFirst()
            .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    private void requireFrontdesk(SessionService.Principal actor, long branch) {
        authorization.requireBranch(actor, "appointments.assist", branch);
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=?
              AND enabled=true AND verified_at IS NOT NULL
            """, Integer.class, actor.staffId(), branch);
        if (count == null || count != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private static String required(String text, int limit) {
        if (text == null || text.isBlank() || text.strip().length() > limit)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return text.strip();
    }

    private static String optional(String text, int limit) {
        if (text == null || text.isBlank()) return null;
        if (text.strip().length() > limit) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return text.strip();
    }

    private static Instant parseNext(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (Exception ex) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }
}
