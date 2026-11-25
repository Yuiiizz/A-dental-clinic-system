package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
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

/** Frontdesk/owner appointment workbench backed by real schedule, appointment and queue tables. */
@Service
public class BookingService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final ResourceAllocationService resources;
    private final cn.dental.shared.application.IdempotencyService idempotency;
    private final BookingAvailabilityService availability;
    private final cn.dental.identity.application.PatientReceptionService selections;
    private final AuditService audit;
    private final QueueTicketService tickets;

    public BookingService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock,
                          ResourceAllocationService resources, cn.dental.shared.application.IdempotencyService idempotency,
                          BookingAvailabilityService availability,
                          cn.dental.identity.application.PatientReceptionService selections, AuditService audit,
                          QueueTicketService tickets) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
        this.resources = resources; this.idempotency=idempotency;
        this.availability = availability;
        this.selections = selections;
        this.audit = audit;
        this.tickets = tickets;
    }

    public record CreateCommand(long patientId, long scheduleId, long itemId, String startsAt,
                                String visitType, String channel, String purpose, String selectionToken) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = authorizedBranches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "appointments.assist", branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", jdbc.query("""
            SELECT branch_id,name FROM branch WHERE state='营业' AND branch_id IN (%s) ORDER BY branch_id
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "branch_id", Long.toString(r.getLong("branch_id")), "name", r.getString("name")),
            branches.toArray()));
        out.put("patient_count", jdbc.queryForObject("""
            SELECT count(DISTINCT a.patient_id) FROM appointment a
            WHERE a.branch_id IN (%s)
            """.formatted(placeholders(branches)), Integer.class, branches.toArray()));
        // Keep the legacy collection shape during rolling web deployments, without bulk contact data.
        out.put("patients", List.of());
        out.put("schedules", jdbc.query("""
            SELECT sc.schedule_id,sc.starts_at,sc.ends_at,sc.branch_id,b.name AS branch_name,
                   sc.dentist_id,s.name AS dentist_name
            FROM schedule sc JOIN branch b ON b.branch_id=sc.branch_id
            JOIN staff s ON s.staff_id=sc.dentist_id
            WHERE sc.state='已发布' AND sc.branch_id IN (%s) AND sc.ends_at > ?
            ORDER BY sc.starts_at,s.name
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "schedule_id", Long.toString(r.getLong("schedule_id")),
                "branch_id", Long.toString(r.getLong("branch_id")),
                "branch_name", r.getString("branch_name"),
                "dentist_id", Long.toString(r.getLong("dentist_id")),
                "dentist_name", r.getString("dentist_name"),
                "starts_at", r.getTimestamp("starts_at").toInstant().toString(),
                "ends_at", r.getTimestamp("ends_at").toInstant().toString()), append(branches, Timestamp.from(clock.instant()))));
        out.put("assistant_shifts", jdbc.query("""
            SELECT sh.shift_id,sh.branch_id,sh.staff_id,s.name,s.job_title,sh.starts_at,sh.ends_at
            FROM assistant_shift sh JOIN staff s ON s.staff_id=sh.staff_id
            JOIN staff_branch sb ON sb.staff_id=sh.staff_id AND sb.branch_id=sh.branch_id
            WHERE sh.state='已排班' AND s.staff_type='其他人员' AND s.employment_state='在岗'
              AND sb.enabled=true AND sh.branch_id IN (%s) AND sh.ends_at > ?
            ORDER BY sh.starts_at,s.name
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "shift_id", Long.toString(r.getLong("shift_id")),
                "branch_id", Long.toString(r.getLong("branch_id")),
                "staff_id", Long.toString(r.getLong("staff_id")),
                "name", r.getString("name"),
                "job_title", r.getString("job_title"),
                "starts_at", r.getTimestamp("starts_at").toInstant().toString(),
                "ends_at", r.getTimestamp("ends_at").toInstant().toString()), append(branches, Timestamp.from(clock.instant()))));
        out.put("services", jdbc.query("""
            SELECT i.item_id,i.name,i.suggested_minutes,i.current_price
            FROM service_item i WHERE i.enabled=true AND i.appointment_enabled=true ORDER BY i.item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")),
                "name", r.getString("name"), "minutes", r.getInt("suggested_minutes"),
                "price", r.getBigDecimal("current_price").toPlainString())));
        out.put("policies", jdbc.query("""
            SELECT policy_id,branch_id FROM booking_policy WHERE status='published'
              AND branch_id IN (%s) ORDER BY branch_id,version_no DESC
            """.formatted(placeholders(branches)), (r,n) -> Map.of(
                "policy_id",Long.toString(r.getLong("policy_id")),
                "branch_id",Long.toString(r.getLong("branch_id"))),branches.toArray()));
        out.put("availability", availability(branches));
        out.put("appointments", list(actor, null));
        return out;
    }

    private List<Map<String, Object>> availability(List<Long> branches) {
        List<Map<String, Object>> schedules = jdbc.queryForList("""
            SELECT schedule_id,branch_id FROM schedule
            WHERE state='已发布' AND branch_id IN (%s) AND ends_at > ? ORDER BY schedule_id
            """.formatted(placeholders(branches)), append(branches, Timestamp.from(clock.instant())));
        List<Long> services = jdbc.queryForList("""
            SELECT item_id FROM service_item WHERE enabled=true AND appointment_enabled=true ORDER BY item_id
            """, Long.class);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> schedule : schedules) {
            long scheduleId = ((Number) schedule.get("schedule_id")).longValue();
            long branchId = ((Number) schedule.get("branch_id")).longValue();
            for (long itemId : services) {
                List<String> slots = availability.previewSlots(branchId, scheduleId, itemId, false);
                if (!slots.isEmpty()) out.add(Map.of(
                    "schedule_id", Long.toString(scheduleId),
                    "item_id", Long.toString(itemId),
                    "slots", slots));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(SessionService.Principal actor, String date) {
        authorization.requireCurrent(actor);
        List<Long> branches = authorizedBranches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "appointments.assist", branch);
        String day = date == null || date.isBlank() ? null : date;
        return jdbc.query("""
            SELECT a.appointment_id,a.patient_id,p.name AS patient_name,a.schedule_id,
                   a.confirmed_dentist_id,s.name AS dentist_name,a.branch_id,b.name AS branch_name,
                   a.starts_at,a.ends_at,a.visit_type,a.channel,a.state,a.version,a.purpose,a.previous_id,
                   a.coordination_state,
                   (a.state IN ('已确认','候诊中','接诊中') AND a.ends_at>?
                     AND (EXISTS (SELECT 1 FROM dentist_unavailable du
                       WHERE du.dentist_id=a.confirmed_dentist_id AND du.state='有效'
                         AND tstzrange(du.starts_at,du.ends_at,'[)') && tstzrange(a.starts_at,a.ends_at,'[)'))
                       OR EXISTS (SELECT 1 FROM resource_booking rb
                         JOIN resource_unavailable ru ON ru.resource_id=rb.resource_id AND ru.state='有效'
                         WHERE rb.appointment_id=a.appointment_id AND rb.state='有效'
                           AND ru.starts_at<a.ends_at))) AS unavailability_alert,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_name,
                   (SELECT ai.item_id FROM appointment_item ai
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_id
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id
            JOIN branch b ON b.branch_id=a.branch_id
            WHERE a.branch_id IN (%s)
              AND (?::date IS NULL OR (a.starts_at AT TIME ZONE 'Asia/Shanghai')::date = ?::date)
            ORDER BY a.starts_at DESC
            """.formatted(placeholders(branches)), (r, n) -> appointmentRow(r),
            append(List.of(Timestamp.from(clock.instant())), append(branches, day, day)));
    }

    @Transactional
    public Map<String,Object> createOnce(SessionService.Principal actor, CreateCommand c, String key, String basis) {
        if(c==null || basis==null || basis.isBlank() || basis.strip().length()>300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Runnable authorized=()->{
            var branches=jdbc.queryForList("SELECT branch_id FROM schedule WHERE schedule_id=?",Long.class,c.scheduleId());
            if(branches.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            requireWrite(actor,branches.getFirst());
        };
        Map<String,Object> content=new TreeMap<>();
        content.put("patient_id",c.patientId()); content.put("schedule_id",c.scheduleId());
        content.put("item_id",c.itemId()); content.put("starts_at",c.startsAt());
        content.put("visit_type",c.visitType()); content.put("channel",c.channel());
        content.put("purpose",c.purpose()); content.put("consent_basis",basis.strip());
        var outcome=idempotency.execute(actor.accountId(),"staff-appointment-create",key,
            cn.dental.shared.application.IdempotencyService.canonicalize(content),authorized,()->{
                var result=create(actor,c);
                long id=Long.parseLong(String.valueOf(result.get("appointment_id")));
                jdbc.update("UPDATE appointment SET consent_basis=? WHERE appointment_id=?",basis.strip(),id);
                return new cn.dental.shared.application.IdempotencyService.BusinessResult(id,"CONFIRMED",null);
            },id->{
                var rows=jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=? AND patient_id=?",Long.class,id,c.patientId());
                if(rows.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
                requireWrite(actor,rows.getFirst());
            });
        return byId(outcome.targetId());
    }

    @Transactional
    public Map<String, Object> create(SessionService.Principal actor, CreateCommand c) {
        if (c == null || c.patientId() <= 0 || c.scheduleId() <= 0 || c.itemId() <= 0)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        OffsetDateTime start;
        try { start = OffsetDateTime.parse(c.startsAt()); }
        catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        long branchId;
        List<Long> branchRows = jdbc.queryForList(
            "SELECT branch_id FROM schedule WHERE schedule_id=?", Long.class, c.scheduleId());
        if (branchRows.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        branchId = branchRows.getFirst();
        requireWrite(actor, branchId);
        var selection = selections.lockForAppointment(actor, c.selectionToken(), c.patientId(), branchId);
        BookingAvailabilityService.Window window = availability.requireAvailable(
            branchId, c.scheduleId(), c.itemId(), start.toInstant(), false);
        // Availability waited on row locks; re-check permission/session before any write.
        requireWrite(actor, branchId);
        long dentistId = window.dentistId();
        Instant s = window.startsAt();
        Instant e = window.endsAt();
        if (jdbc.queryForObject("""
            SELECT count(*) FROM appointment WHERE state IN ('已确认','候诊中','接诊中')
              AND (confirmed_dentist_id=? OR patient_id=?)
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """, Integer.class, dentistId, c.patientId(), Timestamp.from(s), Timestamp.from(e)) > 0)
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        if (jdbc.queryForObject("SELECT count(*) FROM patient WHERE patient_id=?", Integer.class, c.patientId()) != 1)
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        String visitType = c.visitType() == null ? "初诊" : c.visitType();
        if (!List.of("初诊", "复诊").contains(visitType)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String channel = c.channel() == null ? "电话" : c.channel();
        if (!List.of("患者端", "电话", "微信登记", "现场").contains(channel)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String purpose = c.purpose() == null ? "门诊预约" : c.purpose().strip();
        if (purpose.isBlank() || purpose.length() > 200) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long policyId = jdbc.queryForObject("""
            SELECT policy_id FROM booking_policy WHERE branch_id=? AND status='published'
            ORDER BY version_no DESC LIMIT 1
            """, Long.class, branchId);
        String requestKey = UUID.randomUUID().toString();
        String requestHash = sha256(c.patientId() + "|" + c.scheduleId() + "|" + c.itemId() + "|" + c.startsAt() + "|" + purpose);
        long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO appointment(patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
                    starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,
                    version,request_key,request_hash,requested_by,branch_id,policy_id)
                VALUES (?,?,?,?,?,?,?,?,?,'已确认','无需协调',?,1,?,?,?,?,?)
                RETURNING appointment_id
                """, Long.class, c.patientId(), c.scheduleId(), dentistId, dentistId,
                Timestamp.from(s), Timestamp.from(e), visitType, purpose, channel,
                Timestamp.from(clock.instant()), requestKey, requestHash, actor.accountId(), branchId, policyId);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }
        jdbc.update("INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity) VALUES(?,?,1)", id, c.itemId());
        resources.reserve(branchId, id, Timestamp.from(s), Timestamp.from(e));
        selections.consume(selection, id);
        return byId(id);
    }

    @Transactional
    public Map<String, Object> cancel(SessionService.Principal actor, long id, String reason) {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT branch_id,state,version,followup_plan_id FROM appointment WHERE appointment_id=? FOR UPDATE
            """, id);
        requireWrite(actor, ((Number) row.get("branch_id")).longValue());
        String state = String.valueOf(row.get("state"));
        if ("已取消".equals(state) || "已完成".equals(state)) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String closure = reason == null || reason.isBlank() ? "前台取消预约" : reason.strip();
        if (closure.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("""
            UPDATE appointment SET state='已取消',cancelled_at=?,cancelled_by_type='clinic',closure_reason=?,
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

    @Transactional
    public Map<String, Object> checkin(SessionService.Principal actor, long id, long expectedVersion,
                                        String verificationBasis, String exceptionReason, String requestKey) {
        if (expectedVersion < 1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String basis = requiredCheckinText(verificationBasis);
        String exception = optionalCheckinText(exceptionReason);
        String content = IdempotencyService.canonicalize(Map.of(
            "appointment_id", id, "expected_version", expectedVersion,
            "verification_basis", basis, "exception_reason", exception == null ? "" : exception));
        var outcome = idempotency.execute(actor.accountId(), "staff-checkin", requestKey, content,
            () -> requireCheckinAccess(actor, id),
            () -> new IdempotencyService.BusinessResult(checkinInTx(actor, id, expectedVersion, basis, exception), "CHECKED_IN", null),
            ticketId -> requireCheckinTicketVisible(actor, id, ticketId));
        return tickets.view(outcome.targetId());
    }

    private void requireCheckinAccess(SessionService.Principal actor, long appointmentId) {
        List<Long> branches = jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=?", Long.class, appointmentId);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireWrite(actor, branches.getFirst());
    }

    private void requireCheckinTicketVisible(SessionService.Principal actor, long appointmentId, long ticketId) {
        List<Long> branches = jdbc.queryForList("""
            SELECT a.branch_id FROM queue_ticket q JOIN appointment a ON a.appointment_id=q.appointment_id
            WHERE q.ticket_id=? AND q.appointment_id=?
            """, Long.class, ticketId, appointmentId);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireWrite(actor, branches.getFirst());
    }

    private long checkinInTx(SessionService.Principal actor, long id, long expectedVersion,
                             String basis, String exception) {
        List<Long> branches = jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=?", Long.class, id);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireWrite(actor, branches.getFirst());
        // Queue ticket insertion takes a branch FK key-share lock. Match the patient path's
        // branch -> appointment -> counter order to avoid a cross-channel deadlock.
        jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branches.getFirst());
        requireWrite(actor, branches.getFirst());
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT a.branch_id,a.state,a.starts_at,a.ends_at,a.version,sc.dentist_id
            FROM appointment a JOIN schedule sc ON sc.schedule_id=a.schedule_id
            WHERE a.appointment_id=? FOR UPDATE OF a
            """, id);
        requireWrite(actor, ((Number) row.get("branch_id")).longValue());
        if ("候诊中".equals(String.valueOf(row.get("state")))) {
            return tickets.existing(id);
        }
        if (!"已确认".equals(String.valueOf(row.get("state")))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if (((Number) row.get("version")).longValue() != expectedVersion) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        long branchId = ((Number) row.get("branch_id")).longValue();
        Instant now = clock.instant();
        LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        Instant start = ((Timestamp) row.get("starts_at")).toInstant();
        Instant end = ((Timestamp) row.get("ends_at")).toInstant();
        if (!start.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().equals(day))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        int absence = jdbc.queryForObject("""
            SELECT count(*) FROM dentist_unavailable WHERE dentist_id=? AND state='有效'
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """, Integer.class, ((Number) row.get("dentist_id")).longValue(), Timestamp.from(start), Timestamp.from(end));
        if (absence > 0) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        int resourceClosure = jdbc.queryForObject("""
            SELECT count(*) FROM resource_booking rb
            JOIN resource_unavailable ru ON ru.resource_id=rb.resource_id AND ru.state='有效'
            WHERE rb.appointment_id=? AND rb.state='有效' AND ru.starts_at<?
            """, Integer.class, id, Timestamp.from(end));
        if (resourceClosure > 0) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        List<Map<String, Object>> configs = jdbc.queryForList(
            "SELECT early_minutes,late_minutes FROM branch_booking_config WHERE branch_id=?", branchId);
        boolean withinWindow = false;
        if (!configs.isEmpty() && configs.getFirst().get("early_minutes") != null && configs.getFirst().get("late_minutes") != null) {
            int early = ((Number) configs.getFirst().get("early_minutes")).intValue();
            int late = ((Number) configs.getFirst().get("late_minutes")).intValue();
            Instant windowEnd = start.plus(Duration.ofMinutes(late));
            if (windowEnd.isAfter(end)) windowEnd = end;
            withinWindow = !now.isBefore(start.minus(Duration.ofMinutes(early))) && now.isBefore(windowEnd);
        }
        if (!withinWindow && exception == null) throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
        long ticketId = tickets.allocate(id, branchId, day, actor.staffId(), now, "前台", basis, exception);
        int next = jdbc.queryForObject("SELECT queue_no FROM queue_ticket WHERE ticket_id=?",Integer.class,ticketId);
        audit.user(actor.accountId(), "queue_ticket", ticketId, "frontdesk_checkin", null,
            Map.of("state_to", "候诊", "queue_number", Integer.toString(next), "verification_method", "in_person"),
            exception == null ? "现场核验签到" : "人工超窗签到");
        return ticketId;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> queue(SessionService.Principal actor, long branchId, String date) {
        if (branchId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        LocalDate day;
        try { day = LocalDate.parse(date); }
        catch (RuntimeException ex) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        authorization.requireCurrent(actor);
        if (!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword() || actor.staffId() == null)
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        // Permission, role scope and verified appointment must belong to one chain.
        List<String> permissions = jdbc.queryForList("""
            SELECT DISTINCT p.code FROM account_role ar
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission p ON p.permission_id=rp.permission_id
            JOIN role_branch_scope bs ON bs.account_id=ar.account_id AND bs.role_id=ar.role_id
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled=true AND sb.verified_at IS NOT NULL
            WHERE ar.account_id=? AND bs.branch_id=?
              AND p.code IN ('appointments.assist','appointments.own.read')
            """, String.class, actor.staffId(), actor.accountId(), branchId);
        boolean all = permissions.contains("appointments.assist");
        if (!all && !permissions.contains("appointments.own.read")) throw new ApiException(ApiErrorCode.FORBIDDEN);
        return jdbc.query("""
            SELECT q.ticket_id,q.appointment_id,q.queue_date,q.queue_no,q.state,q.checkin_channel
            FROM queue_ticket q JOIN appointment a ON a.appointment_id=q.appointment_id
            WHERE q.branch_id=? AND q.queue_date=? AND a.branch_id=q.branch_id
              AND (? OR a.confirmed_dentist_id=?)
            ORDER BY q.queue_no,q.ticket_id
            """, (r, n) -> Map.of(
                "ticket_id", Long.toString(r.getLong("ticket_id")),
                "appointment_id", Long.toString(r.getLong("appointment_id")),
                "queue_date", r.getDate("queue_date").toLocalDate().toString(),
                "queue_no", r.getInt("queue_no"),
                "state", r.getString("state"),
                "checkin_channel", r.getString("checkin_channel")),
            branchId, java.sql.Date.valueOf(day), all, actor.staffId());
    }

    private static String requiredCheckinText(String value) {
        String normalized = optionalCheckinText(value);
        if (normalized == null) throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
        return normalized;
    }

    private static String optionalCheckinText(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return normalized;
    }

    private Map<String, Object> byId(long id) {
        return jdbc.queryForObject("""
            SELECT a.appointment_id,a.patient_id,p.name AS patient_name,a.schedule_id,
                   a.confirmed_dentist_id,s.name AS dentist_name,a.branch_id,b.name AS branch_name,
                   a.starts_at,a.ends_at,a.visit_type,a.channel,a.state,a.version,a.purpose,a.previous_id,
                   a.coordination_state,
                   (a.state IN ('已确认','候诊中','接诊中') AND a.ends_at>?
                     AND (EXISTS (SELECT 1 FROM dentist_unavailable du
                       WHERE du.dentist_id=a.confirmed_dentist_id AND du.state='有效'
                         AND tstzrange(du.starts_at,du.ends_at,'[)') && tstzrange(a.starts_at,a.ends_at,'[)'))
                       OR EXISTS (SELECT 1 FROM resource_booking rb
                         JOIN resource_unavailable ru ON ru.resource_id=rb.resource_id AND ru.state='有效'
                         WHERE rb.appointment_id=a.appointment_id AND rb.state='有效'
                           AND ru.starts_at<a.ends_at))) AS unavailability_alert,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_name,
                   (SELECT ai.item_id FROM appointment_item ai
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_id
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id
            JOIN branch b ON b.branch_id=a.branch_id WHERE a.appointment_id=?
            """, (r, n) -> appointmentRow(r), Timestamp.from(clock.instant()), id);
    }

    private Map<String, Object> appointmentRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appointment_id", Long.toString(r.getLong("appointment_id")));
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
        m.put("coordination_state", r.getString("coordination_state"));
        m.put("unavailability_alert", r.getBoolean("unavailability_alert"));
        m.put("version", r.getInt("version"));
        m.put("purpose", r.getString("purpose"));
        m.put("previous_id", r.getObject("previous_id") == null ? null : Long.toString(r.getLong("previous_id")));
        m.put("item_name", r.getString("item_name"));
        m.put("item_id", r.getObject("item_id") == null ? null : Long.toString(r.getLong("item_id")));
        return m;
    }

    private List<Long> authorizedBranches(SessionService.Principal actor) {
        return jdbc.queryForList("""
            SELECT DISTINCT bs.branch_id FROM role_branch_scope bs
            JOIN account_role ar ON ar.account_id=bs.account_id AND ar.role_id=bs.role_id
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='appointments.assist'
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id AND sb.enabled
            WHERE bs.account_id=? ORDER BY bs.branch_id
            """, Long.class, actor.staffId(), actor.accountId());
    }

    private void requireWrite(SessionService.Principal actor, long branchId) {
        authorization.requireBranch(actor, "appointments.assist", branchId);
    }

    private static String placeholders(List<?> values) { return String.join(",", Collections.nCopies(values.size(), "?")); }
    private static Object[] append(List<?> list, Object... tail) {
        List<Object> all = new ArrayList<>(list); all.addAll(Arrays.asList(tail)); return all.toArray();
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
