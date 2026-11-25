package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Real visit completion and follow-up planning on the same appointment data. */
@Service
public class ClinicalService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final AuditService audit;

    public ClinicalService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock, AuditService audit) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
        this.audit = audit;
    }

    public record VisitCommand(long appointmentId, String allergyResult, String allergySnapshot, String notes,
                               Boolean followupGoalAchieved) {}
    public record PlanCommand(long visitId, String from, String to, int duration, String purpose, Long serviceItemId) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "clinical.record", branch);
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> visitRows = visits(actor);
        out.put("visits", visitRows);
        // Historical records remain visible by branch, while a new plan can only use this doctor's own visit.
        out.put("plan_source_visits", visitRows.stream()
            .filter(v -> Long.toString(actor.staffId()).equals(v.get("dentist_id"))).toList());
        out.put("plans", plans(actor));
        out.put("plan_services", jdbc.query("""
            SELECT i.item_id,i.name FROM service_item i
            JOIN dentist_service ds ON ds.item_id=i.item_id
            WHERE ds.dentist_id=? AND ds.enabled=true AND i.enabled=true AND i.appointment_enabled=true
            ORDER BY i.item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")), "name", r.getString("name")), actor.staffId()));
        List<Object> appointmentArgs = new ArrayList<>(branches);
        appointmentArgs.add(actor.staffId());
        out.put("appointments", jdbc.query("""
            SELECT a.appointment_id,a.patient_id,p.name AS patient_name,a.confirmed_dentist_id,s.name AS dentist_name,
                   a.branch_id,b.name AS branch_name,a.starts_at,a.state,a.version,a.followup_plan_id
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id JOIN branch b ON b.branch_id=a.branch_id
            WHERE a.branch_id IN (%s) AND a.confirmed_dentist_id=? AND a.state IN ('候诊中','接诊中')
            ORDER BY a.starts_at
            """.formatted(placeholders(branches)), (r, n) -> {
                Map<String,Object> row = new LinkedHashMap<>();
                row.put("appointment_id", Long.toString(r.getLong("appointment_id")));
                row.put("patient_id", Long.toString(r.getLong("patient_id")));
                row.put("patient_name", r.getString("patient_name"));
                row.put("dentist_name", r.getString("dentist_name"));
                row.put("branch_name", r.getString("branch_name"));
                row.put("starts_at", r.getTimestamp("starts_at").toInstant().toString());
                row.put("state", r.getString("state"));
                row.put("version", r.getInt("version"));
                row.put("followup_plan_id", r.getObject("followup_plan_id") == null ? null : Long.toString(r.getLong("followup_plan_id")));
                return row;
            },
            appointmentArgs.toArray()));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> visits(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "clinical.record", branch);
        return jdbc.query("""
            SELECT v.visit_id,v.appointment_id,v.dentist_id,s.name AS dentist_name,v.allergy_result,
                   v.allergy_snapshot,v.clinical_notes,v.started_at,v.completed_at,
                   p.patient_id,p.name AS patient_name
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN patient p ON p.patient_id=a.patient_id LEFT JOIN staff s ON s.staff_id=v.dentist_id
            WHERE a.branch_id IN (%s)
              AND (v.dentist_id=? OR EXISTS (
                SELECT 1 FROM appointment access_a
                WHERE access_a.patient_id=a.patient_id AND access_a.branch_id=a.branch_id
                  AND access_a.confirmed_dentist_id=?
                  AND access_a.state IN ('已确认','候诊中','接诊中','已完成')
              ))
            ORDER BY v.completed_at DESC,v.visit_id DESC
            """.formatted(placeholders(branches)), (r, n) -> visitRow(r), withDentist(branches, actor.staffId()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> plans(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "clinical.record", branch);
        return jdbc.query("""
            SELECT f.plan_id,f.source_visit_id,f.dentist_id,s.name AS dentist_name,f.suggested_from,
                   f.suggested_to,f.duration_minutes,f.purpose,f.state,f.service_item_id,i.name AS service_item_name,p.name AS patient_name
            FROM followup_plan f JOIN visit_record v ON v.visit_id=f.source_visit_id
            JOIN appointment a ON a.appointment_id=v.appointment_id JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=f.dentist_id
            LEFT JOIN service_item i ON i.item_id=f.service_item_id
            WHERE a.branch_id IN (%s)
              AND (v.dentist_id=? OR EXISTS (
                SELECT 1 FROM appointment access_a
                WHERE access_a.patient_id=a.patient_id AND access_a.branch_id=a.branch_id
                  AND access_a.confirmed_dentist_id=?
                  AND access_a.state IN ('已确认','候诊中','接诊中','已完成')
              ))
            ORDER BY f.plan_id DESC
            """.formatted(placeholders(branches)), (r, n) -> planRow(r), withDentist(branches, actor.staffId()));
    }

    @Transactional
    public Map<String,Object> startVisit(SessionService.Principal actor, long id) {
        var rows = jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=?", Long.class, id);
        if(rows.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        requireBranch(actor, rows.getFirst());
        var a=jdbc.queryForMap("SELECT branch_id,confirmed_dentist_id,state,actual_started_at,actual_dentist_id FROM appointment WHERE appointment_id=? FOR UPDATE",id);
        requireBranch(actor, ((Number)a.get("branch_id")).longValue());
        if(a.get("confirmed_dentist_id")==null || ((Number)a.get("confirmed_dentist_id")).longValue()!=actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if("接诊中".equals(a.get("state")) && a.get("actual_started_at")!=null && ((Number)a.get("actual_dentist_id")).longValue()==actor.staffId())
            return Map.of("appointment_id",Long.toString(id),"state","接诊中");
        if(!"候诊中".equals(a.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("UPDATE appointment SET actual_started_at=?,actual_dentist_id=?,state='接诊中',version=version+1 WHERE appointment_id=?", Timestamp.from(clock.instant()),actor.staffId(),id);
        return Map.of("appointment_id",Long.toString(id),"state","接诊中");
    }

    @Transactional
    public Map<String, Object> completeVisit(SessionService.Principal actor, VisitCommand c) {
        if (c == null || c.appointmentId() <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        var branches = jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=?", Long.class, c.appointmentId());
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireBranch(actor, branches.getFirst());
        Map<String, Object> appointment = jdbc.queryForMap("""
            SELECT a.appointment_id,a.patient_id,a.confirmed_dentist_id,a.branch_id,a.starts_at,a.state,a.version,a.actual_started_at,a.actual_dentist_id,a.followup_plan_id,
                   p.allergy_status,p.allergy_notes
            FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
            WHERE a.appointment_id=? FOR UPDATE
            """, c.appointmentId());
        requireBranch(actor, ((Number) appointment.get("branch_id")).longValue());
        if (!"接诊中".equals(appointment.get("state")) || appointment.get("actual_started_at")==null)
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if (((Number)appointment.get("actual_dentist_id")).longValue()!=actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if (jdbc.queryForObject("SELECT count(*) FROM visit_record WHERE appointment_id=?", Integer.class, c.appointmentId()) != 0)
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String allergy = c.allergyResult() == null ? "" : c.allergyResult().strip();
        if (!List.of("无已知", "有", "不确定").contains(allergy))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String snapshot = c.allergySnapshot() == null ? "" : c.allergySnapshot().strip();
        if (("有".equals(allergy) || "不确定".equals(allergy)) && snapshot.isBlank())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String notes = c.notes() == null ? "" : c.notes().strip();
        Long followupPlanId = appointment.get("followup_plan_id") == null ? null
            : ((Number) appointment.get("followup_plan_id")).longValue();
        if (followupPlanId == null && c.followupGoalAchieved() != null)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (followupPlanId != null && (c.followupGoalAchieved() == null || notes.isBlank()))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (followupPlanId != null) {
            var plans = jdbc.queryForList("""
                SELECT f.state FROM followup_plan f
                JOIN visit_record source_visit ON source_visit.visit_id=f.source_visit_id
                JOIN appointment source ON source.appointment_id=source_visit.appointment_id
                WHERE f.plan_id=? AND source.patient_id=? FOR UPDATE OF f
                """, followupPlanId, appointment.get("patient_id"));
            if (plans.isEmpty() || !"已预约".equals(plans.getFirst().get("state")))
                throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_CONFLICT);
        }
        long visitId;
        try {
            visitId = jdbc.queryForObject("""
                INSERT INTO visit_record(appointment_id,dentist_id,allergy_result,allergy_snapshot,clinical_notes,started_at,completed_at)
                VALUES(?,?,?,?,?,?,?) RETURNING visit_id
                """, Long.class, c.appointmentId(), ((Number) appointment.get("actual_dentist_id")).longValue(),
                allergy, snapshot.isBlank() ? null : snapshot, notes, (Timestamp) appointment.get("actual_started_at"),
                Timestamp.from(clock.instant()));
        } catch (DataIntegrityViolationException e) { throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT); }
        jdbc.update("""
            UPDATE appointment SET state='已完成',version=version+1 WHERE appointment_id=?
            """, c.appointmentId());
        jdbc.update("UPDATE resource_booking SET state='已释放' WHERE appointment_id=? AND state='有效'", c.appointmentId());
        if (followupPlanId != null) {
            String nextState = c.followupGoalAchieved() ? "已完成" : "待协调";
            jdbc.update("UPDATE followup_plan SET state=? WHERE plan_id=?", nextState, followupPlanId);
            audit.user(actor.accountId(), "followup_plan", followupPlanId, "visit-outcome",
                Map.of("state_from", "已预约"), Map.of("state_to", nextState),
                "接诊记录 " + visitId + " 已确认复诊目标结果");
        }
        return jdbc.queryForObject("""
            SELECT v.visit_id,v.appointment_id,v.dentist_id,s.name AS dentist_name,v.allergy_result,
                   v.allergy_snapshot,v.clinical_notes,v.started_at,v.completed_at,
                   p.patient_id,p.name AS patient_name
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN patient p ON p.patient_id=a.patient_id LEFT JOIN staff s ON s.staff_id=v.dentist_id
            WHERE v.visit_id=?
            """, (r, n) -> visitRow(r), visitId);
    }

    @Transactional
    public Map<String, Object> createPlan(SessionService.Principal actor, PlanCommand c) {
        if (c == null || c.visitId() <= 0 || c.serviceItemId() != null && c.serviceItemId() <= 0
            || c.purpose() == null || c.purpose().isBlank()
            || c.purpose().strip().length() > 200 || c.duration() < 5 || c.duration() > 480)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        LocalDate from, to;
        try { from = LocalDate.parse(c.from()); to = LocalDate.parse(c.to()); }
        catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        if (to.isBefore(from) || from.isBefore(LocalDate.now(clock))) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> visit = jdbc.queryForMap("""
            SELECT v.visit_id,v.dentist_id,a.branch_id FROM visit_record v
            JOIN appointment a ON a.appointment_id=v.appointment_id WHERE v.visit_id=?
            """, c.visitId());
        requireBranch(actor, ((Number) visit.get("branch_id")).longValue());
        if (((Number) visit.get("dentist_id")).longValue() != actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if (c.serviceItemId() != null && jdbc.queryForObject("""
            SELECT count(*) FROM service_item i JOIN dentist_service ds ON ds.item_id=i.item_id
            WHERE i.item_id=? AND i.enabled=true AND i.appointment_enabled=true
              AND ds.dentist_id=? AND ds.enabled=true
            """, Integer.class, c.serviceItemId(), actor.staffId()) != 1)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        // Serialize active-plan creation for this source visit without blocking unauthorized actors.
        visit = jdbc.queryForMap("""
            SELECT v.visit_id,v.dentist_id,a.branch_id FROM visit_record v
            JOIN appointment a ON a.appointment_id=v.appointment_id WHERE v.visit_id=? FOR UPDATE OF v
            """, c.visitId());
        requireBranch(actor, ((Number) visit.get("branch_id")).longValue());
        if (((Number) visit.get("dentist_id")).longValue() != actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if (jdbc.queryForObject("""
            SELECT count(*) FROM followup_plan WHERE source_visit_id=? AND state IN ('待协调','已预约')
            """, Integer.class, c.visitId()) != 0) throw new ApiException(ApiErrorCode.FOLLOWUP_PLAN_ALREADY_ACTIVE);
        long id = jdbc.queryForObject("""
            INSERT INTO followup_plan(source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,service_item_id,state)
            VALUES(?,?,?,?,?,?,?,'待协调') RETURNING plan_id
            """, Long.class, c.visitId(), ((Number) visit.get("dentist_id")).longValue(),
            Date.valueOf(from), Date.valueOf(to), c.duration(), c.purpose().strip(), c.serviceItemId());
        return jdbc.queryForObject("""
            SELECT f.plan_id,f.source_visit_id,f.dentist_id,s.name AS dentist_name,f.suggested_from,
                   f.suggested_to,f.duration_minutes,f.purpose,f.state,f.service_item_id,i.name AS service_item_name,p.name AS patient_name
            FROM followup_plan f JOIN visit_record v ON v.visit_id=f.source_visit_id
            JOIN appointment a ON a.appointment_id=v.appointment_id JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=f.dentist_id
            LEFT JOIN service_item i ON i.item_id=f.service_item_id WHERE f.plan_id=?
            """, (r, n) -> planRow(r), id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> patientRecords(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visits", jdbc.query("""
            SELECT v.visit_id,v.appointment_id,v.dentist_id,s.name AS dentist_name,v.allergy_result,
                   v.allergy_snapshot,v.clinical_notes,v.started_at,v.completed_at,
                   a.patient_id,p.name AS patient_name
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN patient p ON p.patient_id=a.patient_id LEFT JOIN staff s ON s.staff_id=v.dentist_id
            WHERE a.patient_id=? ORDER BY v.completed_at DESC,v.visit_id DESC
            """, (r, n) -> visitRow(r), actor.patientId()));
        out.put("plans", jdbc.query("""
            SELECT f.plan_id,f.source_visit_id,f.dentist_id,s.name AS dentist_name,f.suggested_from,
                   f.suggested_to,f.duration_minutes,f.purpose,f.state,f.service_item_id,i.name AS service_item_name,p.name AS patient_name
            FROM followup_plan f JOIN visit_record v ON v.visit_id=f.source_visit_id
            JOIN appointment a ON a.appointment_id=v.appointment_id JOIN patient p ON p.patient_id=a.patient_id
            LEFT JOIN staff s ON s.staff_id=f.dentist_id
            LEFT JOIN service_item i ON i.item_id=f.service_item_id WHERE a.patient_id=? ORDER BY f.plan_id DESC
            """, (r, n) -> planRow(r), actor.patientId()));
        return out;
    }

    private Map<String, Object> visitRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("visit_id", Long.toString(r.getLong("visit_id")));
        m.put("appointment_id", Long.toString(r.getLong("appointment_id")));
        m.put("patient_id", Long.toString(r.getLong("patient_id")));
        m.put("patient_name", r.getString("patient_name"));
        m.put("dentist_id", Long.toString(r.getLong("dentist_id")));
        m.put("dentist_name", r.getString("dentist_name"));
        m.put("allergy_result", r.getString("allergy_result"));
        m.put("allergy_snapshot", r.getString("allergy_snapshot"));
        m.put("clinical_notes", r.getString("clinical_notes"));
        m.put("started_at", r.getTimestamp("started_at").toInstant().toString());
        m.put("completed_at", r.getTimestamp("completed_at").toInstant().toString());
        return m;
    }
    private Map<String, Object> planRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan_id", Long.toString(r.getLong("plan_id")));
        m.put("source_visit_id", Long.toString(r.getLong("source_visit_id")));
        m.put("dentist_id", Long.toString(r.getLong("dentist_id")));
        m.put("patient_name", r.getString("patient_name"));
        m.put("dentist_name", r.getString("dentist_name"));
        m.put("suggested_from", r.getDate("suggested_from").toLocalDate().toString());
        m.put("suggested_to", r.getDate("suggested_to").toLocalDate().toString());
        m.put("duration_minutes", r.getInt("duration_minutes"));
        m.put("service_item_id", r.getObject("service_item_id") == null ? null : Long.toString(r.getLong("service_item_id")));
        m.put("service_item_name", r.getString("service_item_name"));
        m.put("purpose", r.getString("purpose"));
        m.put("state", r.getString("state"));
        return m;
    }
    private List<Long> branches(SessionService.Principal actor) {
        return jdbc.queryForList("""
            SELECT DISTINCT bs.branch_id FROM role_branch_scope bs
            JOIN account_role ar ON ar.account_id=bs.account_id AND ar.role_id=bs.role_id
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='clinical.record'
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE bs.account_id=? ORDER BY bs.branch_id
            """, Long.class, actor.staffId(), actor.accountId());
    }
    private void requireBranch(SessionService.Principal actor, long branch) {
        authorization.requireBranch(actor, "clinical.record", branch);
    }
    private static String placeholders(List<?> list) { return String.join(",", Collections.nCopies(list.size(), "?")); }
    private static Object[] withDentist(List<Long> branches, long dentistId) {
        List<Object> args = new ArrayList<>(branches);
        args.add(dentistId);
        args.add(dentistId);
        return args.toArray();
    }
}
