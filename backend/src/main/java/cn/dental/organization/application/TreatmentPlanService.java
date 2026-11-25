package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Real treatment plan: clinical proposal, explicit patient acceptance, item execution. */
@Service
public class TreatmentPlanService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;

    public TreatmentPlanService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock) {
        this.jdbc = jdbc; this.authorization = authorization; this.clock = clock;
    }
    public record ItemInput(long serviceItemId, String name, String toothPosition, String phase,
                            int quantity, long unitPriceCents) {}
    public record CreateCommand(long sourceVisitId, String title, String diagnosis, List<ItemInput> items) {}
    public record AcceptCommand(String basis) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "clinical.plan", branch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", plans(branches, actor.staffId()));
        out.put("visits", jdbc.query("""
            SELECT v.visit_id,p.name AS patient_name,a.branch_id,v.dentist_id,s.name AS dentist_name,v.completed_at
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN patient p ON p.patient_id=a.patient_id LEFT JOIN staff s ON s.staff_id=v.dentist_id
            WHERE a.branch_id IN (%s) AND v.dentist_id=? ORDER BY v.completed_at DESC
            """.formatted(placeholders(branches)), (r, n) -> Map.of(
                "visit_id", Long.toString(r.getLong("visit_id")), "patient_name", r.getString("patient_name"),
                "branch_id", Long.toString(r.getLong("branch_id")), "dentist_id", Long.toString(r.getLong("dentist_id")),
                "dentist_name", r.getString("dentist_name"),
                "completed_at", r.getTimestamp("completed_at").toInstant().toString()), withDentist(branches, actor.staffId())));
        out.put("services", jdbc.query("""
            SELECT item_id,name,suggested_minutes,current_price FROM service_item
            WHERE enabled=true AND appointment_enabled=true ORDER BY item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")), "name", r.getString("name"),
                "minutes", r.getInt("suggested_minutes"), "price", r.getBigDecimal("current_price").toPlainString())));
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> plans(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        List<Long> branches = branches(actor);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);
        for (long branch : branches) authorization.requireBranch(actor, "clinical.plan", branch);
        return plans(branches, actor.staffId());
    }

    private List<Map<String, Object>> plans(List<Long> branches, long dentistId) {
        return jdbc.query("""
            SELECT tp.plan_id,tp.patient_id,p.name AS patient_name,tp.branch_id,tp.dentist_id,s.name AS dentist_name,
                   tp.source_visit_id,tp.title,tp.diagnosis,tp.state,tp.estimated_total_cents,tp.version,
                   tp.created_at,tp.accepted_at,tp.acceptance_basis,tp.rejected_reason,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id) AS item_count,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id AND i.status='已完成') AS completed_count
            FROM treatment_plan tp JOIN patient p ON p.patient_id=tp.patient_id
            JOIN staff s ON s.staff_id=tp.dentist_id
            WHERE tp.branch_id IN (%s) AND tp.dentist_id=? ORDER BY tp.plan_id DESC
            """.formatted(placeholders(branches)), (r, n) -> {
            Map<String, Object> m = planRow(r);
            m.put("patient_name", r.getString("patient_name"));
            m.put("dentist_name", r.getString("dentist_name"));
            m.put("item_count", r.getInt("item_count"));
            m.put("completed_count", r.getInt("completed_count"));
            m.put("items", items(r.getLong("plan_id")));
            return m;
        }, withDentist(branches, dentistId));
    }

    @Transactional
    public Map<String, Object> create(SessionService.Principal actor, CreateCommand c) {
        if (c == null || c.sourceVisitId() <= 0 || c.title() == null || c.title().isBlank()
            || c.title().strip().length() > 120 || c.items() == null || c.items().isEmpty())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> visit = jdbc.queryForMap("""
            SELECT a.patient_id,a.branch_id,v.dentist_id FROM visit_record v
            JOIN appointment a ON a.appointment_id=v.appointment_id WHERE v.visit_id=?
            """, c.sourceVisitId());
        long branch = ((Number) visit.get("branch_id")).longValue();
        requireBranch(actor, branch);
        if (((Number) visit.get("dentist_id")).longValue() != actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        long total = 0;
        for (ItemInput item : c.items()) {
            if (item == null || item.name() == null || item.name().isBlank() || item.name().strip().length() > 100
                || item.quantity() <= 0 || item.unitPriceCents() < 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            total += item.quantity() * item.unitPriceCents();
        }
        if (total <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long id = jdbc.queryForObject("""
            INSERT INTO treatment_plan(patient_id,branch_id,dentist_id,source_visit_id,title,diagnosis,state,
                estimated_total_cents,version,created_by,created_at)
            VALUES(?,?,?,?,?,?,'草稿',?,1,?,?) RETURNING plan_id
            """, Long.class, ((Number) visit.get("patient_id")).longValue(), branch,
            ((Number) visit.get("dentist_id")).longValue(), c.sourceVisitId(), c.title().strip(),
            c.diagnosis() == null ? null : c.diagnosis().strip(), total, actor.accountId(),
            Timestamp.from(clock.instant()));
        int order = 0;
        for (ItemInput item : c.items()) {
            long amount = (long) item.quantity() * item.unitPriceCents();
            jdbc.update("""
                INSERT INTO treatment_plan_item(plan_id,service_item_id,name,tooth_position,phase,quantity,
                    unit_price_cents,amount_cents,status,sort_order)
                VALUES(?,?,?,?,?,?,?,?,'计划',?)
                """, id, item.serviceItemId() > 0 ? item.serviceItemId() : null, item.name().strip(),
                blank(item.toothPosition()), blank(item.phase()), item.quantity(), item.unitPriceCents(), amount, order++);
        }
        return byId(id);
    }

    @Transactional
    public Map<String, Object> submit(SessionService.Principal actor, long id) {
        Map<String, Object> plan = jdbc.queryForMap("SELECT branch_id,dentist_id,state FROM treatment_plan WHERE plan_id=? FOR UPDATE", id);
        requireBranch(actor, ((Number) plan.get("branch_id")).longValue());
        if (((Number) plan.get("dentist_id")).longValue() != actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if (!"草稿".equals(plan.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("UPDATE treatment_plan SET state='待患者确认',version=version+1 WHERE plan_id=?", id);
        return byId(id);
    }

    @Transactional
    public Map<String, Object> updateItem(SessionService.Principal actor, long planId, long itemId, String status) {
        if (!List.of("计划", "进行中", "已完成", "已取消").contains(status)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> plan = jdbc.queryForMap("SELECT branch_id,dentist_id,state FROM treatment_plan WHERE plan_id=? FOR UPDATE", planId);
        requireBranch(actor, ((Number) plan.get("branch_id")).longValue());
        if (((Number) plan.get("dentist_id")).longValue() != actor.staffId())
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        if (!List.of("已接受", "已完成").contains(plan.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        int changed = jdbc.update("UPDATE treatment_plan_item SET status=? WHERE item_id=? AND plan_id=?", status, itemId, planId);
        if (changed != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        int remaining = jdbc.queryForObject("""
            SELECT count(*) FROM treatment_plan_item WHERE plan_id=? AND status NOT IN ('已完成','已取消')
            """, Integer.class, planId);
        if (remaining == 0) jdbc.update("UPDATE treatment_plan SET state='已完成',version=version+1 WHERE plan_id=?", planId);
        else jdbc.update("UPDATE treatment_plan SET version=version+1 WHERE plan_id=?", planId);
        return byId(planId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> patientPlans(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plans", jdbc.query("""
            SELECT tp.*,p.name AS patient_name,s.name AS dentist_name,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id) AS item_count,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id AND i.status='已完成') AS completed_count
            FROM treatment_plan tp JOIN patient p ON p.patient_id=tp.patient_id JOIN staff s ON s.staff_id=tp.dentist_id
            WHERE tp.patient_id=? ORDER BY tp.plan_id DESC
            """, (r, n) -> {
            Map<String, Object> m = planRow(r);
            m.put("patient_name", r.getString("patient_name")); m.put("dentist_name", r.getString("dentist_name"));
            m.put("item_count", r.getInt("item_count")); m.put("completed_count", r.getInt("completed_count"));
            m.put("items", items(r.getLong("plan_id"))); return m;
        }, actor.patientId()));
        return out;
    }

    @Transactional
    public Map<String, Object> accept(SessionService.Principal actor, long id, String basis) {
        long patient = requirePatient(actor);
        Map<String, Object> plan = jdbc.queryForMap("SELECT patient_id,state FROM treatment_plan WHERE plan_id=? FOR UPDATE", id);
        if (((Number) plan.get("patient_id")).longValue() != patient) throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (!"待患者确认".equals(plan.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String b = basis == null || basis.isBlank() ? "患者端确认" : basis.strip();
        if (b.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("""
            UPDATE treatment_plan SET state='已接受',accepted_at=?,acceptance_basis=?,version=version+1 WHERE plan_id=?
            """, Timestamp.from(clock.instant()), b, id);
        return byId(id);
    }

    @Transactional
    public Map<String, Object> reject(SessionService.Principal actor, long id, String reason) {
        long patient = requirePatient(actor);
        Map<String, Object> plan = jdbc.queryForMap("SELECT patient_id,state FROM treatment_plan WHERE plan_id=? FOR UPDATE", id);
        if (((Number) plan.get("patient_id")).longValue() != patient) throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (!"待患者确认".equals(plan.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        String r = reason == null || reason.isBlank() ? "患者暂时不接受" : reason.strip();
        if (r.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("UPDATE treatment_plan SET state='已拒绝',rejected_reason=?,version=version+1 WHERE plan_id=?", r, id);
        return byId(id);
    }

    private Map<String, Object> byId(long id) {
        return jdbc.queryForObject("""
            SELECT tp.*,p.name AS patient_name,s.name AS dentist_name,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id) AS item_count,
                   (SELECT count(*) FROM treatment_plan_item i WHERE i.plan_id=tp.plan_id AND i.status='已完成') AS completed_count
            FROM treatment_plan tp JOIN patient p ON p.patient_id=tp.patient_id JOIN staff s ON s.staff_id=tp.dentist_id
            WHERE tp.plan_id=?
            """, (r, n) -> {
            Map<String, Object> m = planRow(r);
            m.put("patient_name", r.getString("patient_name")); m.put("dentist_name", r.getString("dentist_name"));
            m.put("item_count", r.getInt("item_count")); m.put("completed_count", r.getInt("completed_count"));
            m.put("items", items(id)); return m;
        }, id);
    }

    private Map<String, Object> planRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan_id", Long.toString(r.getLong("plan_id")));
        m.put("patient_id", Long.toString(r.getLong("patient_id")));
        m.put("branch_id", Long.toString(r.getLong("branch_id")));
        m.put("source_visit_id", Long.toString(r.getLong("source_visit_id")));
        m.put("title", r.getString("title"));
        m.put("diagnosis", r.getString("diagnosis"));
        m.put("state", r.getString("state"));
        m.put("estimated_total_cents", r.getLong("estimated_total_cents"));
        m.put("version", r.getInt("version"));
        m.put("created_at", r.getTimestamp("created_at").toInstant().toString());
        m.put("accepted_at", r.getTimestamp("accepted_at") == null ? null : r.getTimestamp("accepted_at").toInstant().toString());
        m.put("acceptance_basis", r.getString("acceptance_basis"));
        m.put("rejected_reason", r.getString("rejected_reason"));
        return m;
    }

    private List<Map<String, Object>> items(long planId) {
        return jdbc.query("""
            SELECT item_id,service_item_id,name,tooth_position,phase,quantity,unit_price_cents,amount_cents,status,sort_order
            FROM treatment_plan_item WHERE plan_id=? ORDER BY sort_order,item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")),
                "service_item_id", r.getObject("service_item_id") == null ? null : Long.toString(r.getLong("service_item_id")),
                "name", r.getString("name"), "tooth_position", r.getString("tooth_position") == null ? "" : r.getString("tooth_position"),
                "phase", r.getString("phase") == null ? "" : r.getString("phase"), "quantity", r.getInt("quantity"),
                "unit_price_cents", r.getLong("unit_price_cents"), "amount_cents", r.getLong("amount_cents"),
                "status", r.getString("status")), planId);
    }

    private List<Long> branches(SessionService.Principal actor) {
        return jdbc.queryForList("""
            SELECT DISTINCT bs.branch_id FROM role_branch_scope bs
            JOIN account_role ar ON ar.account_id=bs.account_id AND ar.role_id=bs.role_id
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='clinical.plan'
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE bs.account_id=? ORDER BY bs.branch_id
            """, Long.class, actor.staffId(), actor.accountId());
    }
    private void requireBranch(SessionService.Principal actor, long branch) {
        authorization.requireBranch(actor, "clinical.plan", branch);
    }
    private long requirePatient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return actor.patientId();
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String placeholders(List<?> list) { return String.join(",", Collections.nCopies(list.size(), "?")); }
    private static Object[] withDentist(List<Long> branches, long dentistId) {
        List<Object> args = new ArrayList<>(branches);
        args.add(dentistId);
        return args.toArray();
    }
}
