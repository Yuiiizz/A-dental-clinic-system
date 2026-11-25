package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Patient self-service booking over the same real schedule/appointment tables as frontdesk. */
@Service
public class PatientBookingService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final ResourceAllocationService resources;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final BookingAvailabilityService availability;

    public PatientBookingService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock,
                                 ResourceAllocationService resources, IdempotencyService idempotency,
                                 AuditService audit, BookingAvailabilityService availability) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
        this.resources = resources;
        this.idempotency = idempotency;
        this.audit = audit;
        this.availability = availability;
    }

    public record CreateCommand(long scheduleId, long itemId, String startsAt, String visitType, String purpose,
                                Long followupPlanId) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        long patientId = requirePatient(actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", jdbc.query("""
            SELECT branch_id,name FROM branch WHERE state='营业' ORDER BY branch_id
            """, (r, n) -> Map.of("branch_id", Long.toString(r.getLong("branch_id")), "name", r.getString("name"))));
        out.put("schedules", jdbc.query("""
            SELECT sc.schedule_id,sc.starts_at,sc.ends_at,sc.branch_id,b.name AS branch_name,
                   sc.dentist_id,s.name AS dentist_name
            FROM schedule sc JOIN branch b ON b.branch_id=sc.branch_id JOIN staff s ON s.staff_id=sc.dentist_id
            WHERE sc.state='已发布' AND sc.ends_at > ? ORDER BY sc.starts_at,s.name
            """, (r, n) -> Map.of("schedule_id", Long.toString(r.getLong("schedule_id")),
                "starts_at", r.getTimestamp("starts_at").toInstant().toString(),
                "ends_at", r.getTimestamp("ends_at").toInstant().toString(),
                "branch_id", Long.toString(r.getLong("branch_id")), "branch_name", r.getString("branch_name"),
                "dentist_id", Long.toString(r.getLong("dentist_id")), "dentist_name", r.getString("dentist_name")),
            Timestamp.from(clock.instant())));
        out.put("services", jdbc.query("""
            SELECT i.item_id,i.name,i.suggested_minutes,i.current_price FROM service_item i
            WHERE i.enabled=true AND i.appointment_enabled=true ORDER BY i.item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")), "name", r.getString("name"),
                "minutes", r.getInt("suggested_minutes"), "price", r.getBigDecimal("current_price").toPlainString())));
        out.put("availability", availability(patientId));
        out.put("appointments", list(actor, patientId));
        return out;
    }

    private List<Map<String, Object>> availability(long patientId) {
        List<Map<String, Object>> schedules = jdbc.queryForList("""
            SELECT schedule_id,branch_id FROM schedule
            WHERE state='已发布' AND ends_at > ? ORDER BY schedule_id
            """, Timestamp.from(clock.instant()));
        List<Long> services = jdbc.queryForList("""
            SELECT item_id FROM service_item WHERE enabled=true AND appointment_enabled=true ORDER BY item_id
            """, Long.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> schedule : schedules) {
            long scheduleId = ((Number) schedule.get("schedule_id")).longValue();
            long branchId = ((Number) schedule.get("branch_id")).longValue();
            for (long itemId : services) {
                List<String> slots = availability.previewSlots(branchId, scheduleId, itemId, true, patientId);
                if (!slots.isEmpty()) {
                    int minutes = jdbc.queryForObject("SELECT duration_minutes FROM schedule_service WHERE schedule_id=? AND item_id=?",
                        Integer.class, scheduleId, itemId);
                    out.add(Map.of("schedule_id", Long.toString(scheduleId), "item_id", Long.toString(itemId),
                        "duration_minutes", minutes, "slots", slots));
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(SessionService.Principal actor, String unused) {
        return list(actor, requirePatient(actor));
    }

    private List<Map<String, Object>> list(SessionService.Principal actor, long patientId) {
        return jdbc.query("""
            SELECT a.appointment_id,a.patient_id,p.name AS patient_name,a.schedule_id,
                   a.confirmed_dentist_id,s.name AS dentist_name,a.branch_id,b.name AS branch_name,
                   a.starts_at,a.ends_at,a.visit_type,a.channel,a.state,a.version,a.purpose,a.previous_id,a.followup_plan_id,
                   q.ticket_id,q.queue_no,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id LIMIT 1) AS item_name
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id JOIN branch b ON b.branch_id=a.branch_id
            LEFT JOIN queue_ticket q ON q.appointment_id=a.appointment_id
            WHERE a.patient_id=? ORDER BY a.starts_at DESC
            """, (r, n) -> row(r), patientId);
    }

    @Transactional
    public Map<String, Object> createOnce(SessionService.Principal actor, CreateCommand c, String key) {
        long patientId = requirePatient(actor);
        if (c == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String,Object> content = new TreeMap<>();
        content.put("patient_id", patientId);
        content.put("schedule_id", c.scheduleId());
        content.put("item_id", c.itemId());
        content.put("starts_at", c.startsAt());
        content.put("visit_type", c.visitType());
        content.put("purpose", c.purpose());
        content.put("followup_plan_id", c.followupPlanId());
        var result = idempotency.execute(actor.accountId(), "patient-appointment-create", key,
            IdempotencyService.canonicalize(content), () -> requirePatient(actor), () -> {
                var created = create(actor, c);
                return new IdempotencyService.BusinessResult(Long.parseLong(created.get("appointment_id").toString()), "CONFIRMED", null);
            }, id -> { requirePatient(actor); requireOwned(id, patientId); });
        return byId(result.targetId());
    }

    @Transactional
    public Map<String, Object> create(SessionService.Principal actor, CreateCommand c) {
        long patientId = requirePatient(actor);
        if (c == null || c.scheduleId() <= 0 || c.itemId() <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        OffsetDateTime start;
        try { start = OffsetDateTime.parse(c.startsAt()); }
        catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        long branchId;
        List<Long> branchRows = jdbc.queryForList(
            "SELECT branch_id FROM schedule WHERE schedule_id=?", Long.class, c.scheduleId());
        if (branchRows.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        branchId = branchRows.getFirst();
        BookingAvailabilityService.Window window = availability.requireAvailable(
            branchId, c.scheduleId(), c.itemId(), start.toInstant(), true);
        // Availability waited on row locks; re-check the patient session before any write.
        requirePatient(actor);
        long dentistId = window.dentistId();
        Instant s = window.startsAt();
        Instant e = window.endsAt();
        String visitType = c.visitType() == null ? "初诊" : c.visitType();
        if (!List.of("初诊", "复诊").contains(visitType)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (c.followupPlanId() != null) {
            if (c.followupPlanId() <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            requireBookableFollowup(c.followupPlanId(), patientId, dentistId, c.itemId(), visitType,
                Duration.between(s, e).toMinutes());
        }
        if (jdbc.queryForObject("""
            SELECT count(*) FROM appointment WHERE state IN ('已确认','候诊中','接诊中')
              AND (confirmed_dentist_id=? OR patient_id=?)
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """, Integer.class, dentistId, patientId, Timestamp.from(s), Timestamp.from(e)) > 0)
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        String purpose = c.purpose() == null ? "患者自助预约" : c.purpose().strip();
        if (purpose.isBlank() || purpose.length() > 200) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long policyId = jdbc.queryForObject("""
            SELECT policy_id FROM booking_policy WHERE branch_id=? AND status='published'
            ORDER BY version_no DESC LIMIT 1
            """, Long.class, branchId);
        long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO appointment(patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
                    starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,
                    version,request_key,request_hash,requested_by,branch_id,policy_id,followup_plan_id)
                VALUES (?,?,?,?,?,?,?,?, '患者端','已确认','无需协调',?,1,?,?,?,?,?,?)
                RETURNING appointment_id
                """, Long.class, patientId, c.scheduleId(), dentistId, dentistId,
                Timestamp.from(s), Timestamp.from(e), visitType, purpose,
                Timestamp.from(clock.instant()), UUID.randomUUID().toString(),
                sha256(patientId + "|" + c.scheduleId() + "|" + c.itemId() + "|" + c.startsAt() + "|" + purpose),
                actor.accountId(), branchId, policyId, c.followupPlanId());
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }
        jdbc.update("INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity) VALUES(?,?,1)", id, c.itemId());
        resources.reserve(branchId, id, Timestamp.from(s), Timestamp.from(e));
        if (c.followupPlanId() != null)
            jdbc.update("UPDATE followup_plan SET state='已预约' WHERE plan_id=? AND state='待协调'", c.followupPlanId());
        return byId(id);
    }

    private void requireBookableFollowup(long planId, long patientId, long dentistId, long itemId, String visitType,
                                         long slotMinutes) {
        var plans = jdbc.queryForList("""
            SELECT f.dentist_id,f.state,f.duration_minutes,f.service_item_id FROM followup_plan f
            JOIN visit_record v ON v.visit_id=f.source_visit_id
            JOIN appointment source ON source.appointment_id=v.appointment_id
            WHERE f.plan_id=? AND source.patient_id=? FOR UPDATE OF f
            """, planId, patientId);
        if (plans.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        var plan = plans.getFirst();
        if (!"待协调".equals(plan.get("state")) || !"复诊".equals(visitType)
                || ((Number)plan.get("dentist_id")).longValue() != dentistId
                || plan.get("service_item_id") != null
                    && ((Number) plan.get("service_item_id")).longValue() != itemId
                || ((Number)plan.get("duration_minutes")).longValue() > slotMinutes)
            throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_CONFLICT);
        if (jdbc.queryForObject("""
            SELECT count(*) FROM appointment WHERE followup_plan_id=?
              AND state IN ('已确认','候诊中','接诊中')
            """, Integer.class, planId) != 0)
            throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_CONFLICT);
    }

    @Transactional
    public Map<String, Object> reschedule(SessionService.Principal actor, long id,
            long scheduleId, long itemId, String startsAt, long expectedVersion, String reason, String key) {
        long patientId = requirePatient(actor);
        requireOwned(id, patientId);
        if (scheduleId <= 0 || itemId <= 0 || expectedVersion <= 0 || reason == null
                || reason.isBlank() || reason.strip().length() > 300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String basis = reason.strip();
        Map<String,Object> content = new TreeMap<>();
        content.put("appointment_id", id); content.put("schedule_id", scheduleId);
        content.put("item_id", itemId); content.put("starts_at", startsAt);
        content.put("expected_version", expectedVersion); content.put("reason", basis);
        Runnable authorized = () -> { requirePatient(actor); requireOwned(id, patientId); };
        var outcome = idempotency.execute(actor.accountId(), "patient-appointment-reschedule", key,
            IdempotencyService.canonicalize(content), authorized, () -> {
                // Lock both branches and both schedules in stable order before the source appointment.
                var source = jdbc.queryForMap("SELECT branch_id,schedule_id FROM appointment WHERE appointment_id=? AND patient_id=?", id, patientId);
                var targets = jdbc.queryForList("SELECT branch_id FROM schedule WHERE schedule_id=?", Long.class, scheduleId);
                if (targets.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
                long sourceBranch = ((Number) source.get("branch_id")).longValue();
                long sourceSchedule = source.get("schedule_id") == null ? 0 : ((Number) source.get("schedule_id")).longValue();
                for (long branch : new TreeSet<>(List.of(sourceBranch, targets.getFirst())))
                    jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branch);
                for (long schedule : new TreeSet<>(List.of(sourceSchedule, scheduleId)))
                    if (schedule > 0) jdbc.queryForList("SELECT schedule_id FROM schedule WHERE schedule_id=? FOR UPDATE", schedule);
                var current = jdbc.queryForMap("SELECT * FROM appointment WHERE appointment_id=? AND patient_id=? FOR UPDATE", id, patientId);
                authorized.run();
                if (((Number)current.get("version")).longValue() != expectedVersion)
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                if (!"已确认".equals(current.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                if (((Number)current.get("branch_id")).longValue() != sourceBranch
                        || !Objects.equals(current.get("schedule_id"), source.get("schedule_id")))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                if (current.get("followup_plan_id") != null && jdbc.queryForObject("""
                    SELECT count(*) FROM followup_plan f JOIN schedule target ON target.dentist_id=f.dentist_id
                    WHERE f.plan_id=? AND target.schedule_id=?
                    """, Integer.class, current.get("followup_plan_id"), scheduleId) != 1)
                    throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_CONFLICT);
                jdbc.update("""
                    UPDATE appointment SET state='已改约',cancelled_at=?,cancelled_by_type='patient',
                      closure_reason=?,version=version+1 WHERE appointment_id=?
                    """, Timestamp.from(clock.instant()), basis, id);
                resources.release(id);
                var replacement = create(actor, new CreateCommand(scheduleId, itemId, startsAt,
                    (String)current.get("visit_type"), (String)current.get("purpose"), null));
                long replacementId = Long.parseLong(replacement.get("appointment_id").toString());
                if (current.get("followup_plan_id") != null && jdbc.queryForObject("""
                    SELECT count(*) FROM followup_plan f JOIN appointment a ON a.appointment_id=?
                    WHERE f.plan_id=? AND EXTRACT(EPOCH FROM (a.ends_at-a.starts_at))/60 >= f.duration_minutes
                      AND (f.service_item_id IS NULL OR f.service_item_id=?)
                    """, Integer.class, replacementId, current.get("followup_plan_id"), itemId) != 1)
                    throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_CONFLICT);
                jdbc.update("UPDATE appointment SET previous_id=?,followup_plan_id=?,special_needs=? WHERE appointment_id=?",
                    id, current.get("followup_plan_id"), current.get("special_needs"), replacementId);
                audit.user(actor.accountId(), "appointment", id, "patient-reschedule",
                    Map.of("state_from", "已确认"), Map.of("state_to", "已改约"), basis);
                return new IdempotencyService.BusinessResult(replacementId, "CONFIRMED", Long.toString(id));
            }, replacementId -> {
                authorized.run(); requireOwned(replacementId, patientId);
                if (jdbc.queryForObject("SELECT count(*) FROM appointment WHERE appointment_id=? AND previous_id=?",
                        Integer.class, replacementId, id) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        var views = byIds(id, outcome.targetId());
        return Map.of("original", views.stream().filter(v -> v.get("appointment_id").equals(Long.toString(id))).findFirst().orElseThrow(),
            "replacement", views.stream().filter(v -> v.get("appointment_id").equals(Long.toString(outcome.targetId()))).findFirst().orElseThrow());
    }

    @Transactional
    public Map<String, Object> cancel(SessionService.Principal actor, long id, String reason) {
        long patientId = requirePatient(actor);
        requireOwned(id, patientId);
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT patient_id,state,followup_plan_id FROM appointment WHERE appointment_id=? AND patient_id=? FOR UPDATE
            """, id, patientId);
        if (((Number) row.get("patient_id")).longValue() != patientId) throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (!"已确认".equals(row.get("state")))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String closure = reason == null || reason.isBlank() ? "患者自助取消" : reason.strip();
        if (closure.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("""
            UPDATE appointment SET state='已取消',cancelled_at=?,cancelled_by_type='patient',closure_reason=?,
                version=version+1 WHERE appointment_id=?
            """, Timestamp.from(clock.instant()), closure, id);
        resources.release(id);
        if (row.get("followup_plan_id") != null)
            jdbc.update("""
                UPDATE followup_plan SET state='待协调' WHERE plan_id=? AND state='已预约'
                  AND NOT EXISTS (SELECT 1 FROM appointment WHERE followup_plan_id=?
                    AND state IN ('已确认','候诊中','接诊中'))
                """, row.get("followup_plan_id"), row.get("followup_plan_id"));
        return byId(id);
    }

    private void requireOwned(long id, long patientId) {
        if (jdbc.queryForObject("SELECT count(*) FROM appointment WHERE appointment_id=? AND patient_id=?",
                Integer.class, id, patientId) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private Map<String, Object> byId(long id) { return byIds(id, id).getFirst(); }

    private List<Map<String, Object>> byIds(long first, long second) {
        return jdbc.query("""
            SELECT a.appointment_id,a.patient_id,p.name AS patient_name,a.schedule_id,
                   a.confirmed_dentist_id,s.name AS dentist_name,a.branch_id,b.name AS branch_name,
                   a.starts_at,a.ends_at,a.visit_type,a.channel,a.state,a.version,a.purpose,a.previous_id,a.followup_plan_id,
                   q.ticket_id,q.queue_no,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id LIMIT 1) AS item_name
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id JOIN branch b ON b.branch_id=a.branch_id
            LEFT JOIN queue_ticket q ON q.appointment_id=a.appointment_id
            WHERE a.appointment_id IN (?,?)
            """, (r, n) -> row(r), first, second);
    }

    private Map<String, Object> row(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appointment_id", Long.toString(r.getLong("appointment_id")));
        m.put("previous_id", r.getObject("previous_id") == null ? null : Long.toString(r.getLong("previous_id")));
        m.put("followup_plan_id", r.getObject("followup_plan_id") == null ? null : Long.toString(r.getLong("followup_plan_id")));
        m.put("patient_id", Long.toString(r.getLong("patient_id")));
        m.put("patient_name", r.getString("patient_name"));
        m.put("schedule_id", r.getObject("schedule_id") == null ? null : r.getLong("schedule_id") + "");
        m.put("dentist_name", r.getString("dentist_name"));
        m.put("branch_id", Long.toString(r.getLong("branch_id")));
        m.put("branch_name", r.getString("branch_name"));
        m.put("starts_at", r.getTimestamp("starts_at").toInstant().toString());
        m.put("ends_at", r.getTimestamp("ends_at").toInstant().toString());
        m.put("visit_type", r.getString("visit_type"));
        m.put("channel", r.getString("channel"));
        m.put("state", r.getString("state"));
        m.put("version", r.getInt("version"));
        m.put("purpose", r.getString("purpose"));
        m.put("item_name", r.getString("item_name"));
        m.put("ticket_id", r.getObject("ticket_id") == null ? null : Long.toString(r.getLong("ticket_id")));
        m.put("queue_no", r.getObject("queue_no") == null ? null : r.getInt("queue_no"));
        return m;
    }

    private long requirePatient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return actor.patientId();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
