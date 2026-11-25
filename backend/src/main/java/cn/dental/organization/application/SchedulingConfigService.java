package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Minimal real scheduling foundation: service catalog, chair resources, staff scope and schedules. */
@Service
public class SchedulingConfigService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final Clock clock;

    public SchedulingConfigService(JdbcTemplate jdbc, AuthorizationService authorization, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    public record ScheduleCommand(long dentistId, long branchId, String startsAt, String endsAt,
                                  List<Long> itemIds) {}
    public record ServiceCommand(String name, String price, int minutes, long branchId) {}
    public record ResourceCommand(String name, long branchId) {}
    public record ScopeCommand(List<Long> itemIds, String approvalBasis) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview(SessionService.Principal actor) {
        requireOwner(actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", jdbc.query("""
            SELECT branch_id,name,code,state FROM branch WHERE state='营业' AND verified_at IS NOT NULL ORDER BY branch_id
            """, (r, n) -> Map.of("branch_id", Long.toString(r.getLong("branch_id")),
                "name", r.getString("name"), "code", r.getString("code"), "state", r.getString("state"))));
        out.put("dentists", jdbc.query("""
            SELECT s.staff_id,s.name,s.job_title,b.branch_id,b.name AS branch_name
            FROM staff s JOIN staff_branch sb ON sb.staff_id=s.staff_id
            JOIN branch b ON b.branch_id=sb.branch_id
            WHERE s.staff_type='牙医' AND s.employment_state='在岗' AND sb.enabled=true
              AND b.state='营业' ORDER BY s.staff_id,b.branch_id
            """, (r, n) -> Map.of("staff_id", Long.toString(r.getLong("staff_id")),
                "name", r.getString("name"), "job_title", r.getString("job_title"),
                "branch_id", Long.toString(r.getLong("branch_id")), "branch_name", r.getString("branch_name"))));
        out.put("service_items", jdbc.query("""
            SELECT i.item_id,i.name,i.current_price,i.suggested_minutes,i.appointment_enabled,
                   c.name AS category_name
            FROM service_item i JOIN service_category c ON c.category_id=i.category_id
            WHERE i.enabled=true ORDER BY i.item_id
            """, (r, n) -> Map.of("item_id", Long.toString(r.getLong("item_id")),
                "name", r.getString("name"), "price", r.getBigDecimal("current_price").toPlainString(),
                "minutes", r.getInt("suggested_minutes"),
                "appointment_enabled", r.getBoolean("appointment_enabled"),
                "category_name", r.getString("category_name"))));
        out.put("resources", jdbc.query("""
            SELECT r.resource_id,r.name,r.resource_type,r.operating_state,b.name AS branch_name
            FROM resource r JOIN branch b ON b.branch_id=r.branch_id ORDER BY r.resource_id
            """, (r, n) -> Map.of("resource_id", Long.toString(r.getLong("resource_id")),
                "name", r.getString("name"), "type", r.getString("resource_type"),
                "state", r.getString("operating_state"), "branch_name", r.getString("branch_name"))));
        out.put("scopes", jdbc.query("""
            SELECT ds.dentist_id,ds.item_id,i.name FROM dentist_service ds
            JOIN service_item i ON i.item_id=ds.item_id WHERE ds.enabled=true ORDER BY ds.dentist_id,ds.item_id
            """, (r, n) -> Map.of("staff_id", Long.toString(r.getLong("dentist_id")),
                "item_id", Long.toString(r.getLong("item_id")), "name", r.getString("name"))));
        out.put("schedules", schedules(null, null));
        out.put("generated_at", java.time.Instant.now(clock).toString());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> schedules(String from, String to) {
        String sql = """
            SELECT sc.schedule_id,sc.dentist_id,s.name AS dentist_name,sc.branch_id,b.name AS branch_name,
                   sc.starts_at,sc.ends_at,sc.service_type,sc.state,sc.version,sc.published_at,
                   array_agg(ss.item_id ORDER BY ss.item_id) AS item_ids
            FROM schedule sc JOIN staff s ON s.staff_id=sc.dentist_id
            JOIN branch b ON b.branch_id=sc.branch_id
            LEFT JOIN schedule_service ss ON ss.schedule_id=sc.schedule_id
            WHERE (?::timestamptz IS NULL OR sc.ends_at > ?::timestamptz)
              AND (?::timestamptz IS NULL OR sc.starts_at < ?::timestamptz)
            GROUP BY sc.schedule_id,s.name,b.name ORDER BY sc.starts_at DESC,sc.schedule_id DESC
            """;
        return jdbc.query(sql, (r, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schedule_id", Long.toString(r.getLong("schedule_id")));
            m.put("dentist_id", Long.toString(r.getLong("dentist_id")));
            m.put("dentist_name", r.getString("dentist_name"));
            m.put("branch_id", Long.toString(r.getLong("branch_id")));
            m.put("branch_name", r.getString("branch_name"));
            m.put("starts_at", r.getTimestamp("starts_at").toInstant().toString());
            m.put("ends_at", r.getTimestamp("ends_at").toInstant().toString());
            m.put("service_type", r.getString("service_type"));
            m.put("state", r.getString("state"));
            m.put("version", r.getInt("version"));
            Timestamp p = r.getTimestamp("published_at");
            m.put("published_at", p == null ? null : p.toInstant().toString());
            Object[] ids = (Object[]) r.getArray("item_ids").getArray();
            List<String> list = new ArrayList<>();
            for (Object id : ids) if (id != null) list.add(id.toString());
            m.put("item_ids", list);
            return m;
        }, from, from, to, to);
    }

    @Transactional
    public Map<String, Object> createSchedule(SessionService.Principal actor, ScheduleCommand c) {
        requireOwner(actor);
        if (c == null || c.dentistId() <= 0 || c.branchId() <= 0 || c.itemIds() == null || c.itemIds().isEmpty())
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        OffsetDateTime start, end;
        try {
            start = OffsetDateTime.parse(c.startsAt());
            end = OffsetDateTime.parse(c.endsAt());
        } catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        if (!end.isAfter(start)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", c.branchId());
        if (jdbc.queryForObject("""
            SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=? AND enabled=true AND verified_at IS NOT NULL
            """, Integer.class, c.dentistId(), c.branchId()) != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<Long, Integer> durations = new LinkedHashMap<>();
        for (Long itemId : new LinkedHashSet<>(c.itemIds())) {
            if (itemId == null || itemId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            Map<String, Object> item = jdbc.queryForMap("""
                SELECT suggested_minutes FROM service_item WHERE item_id=? AND enabled=true AND appointment_enabled=true
                """, itemId);
            if (jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=? AND item_id=? AND enabled=true",
                Integer.class, c.dentistId(), itemId) != 1) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
            durations.put(itemId, ((Number) item.get("suggested_minutes")).intValue());
        }
        long id;
        try {
            id = jdbc.queryForObject("""
                INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,version,travel_buffer_minutes)
                VALUES (?,?,?,'两者','草稿',?,NULL,1,0) RETURNING schedule_id
                """, Long.class, c.dentistId(), Timestamp.from(start.toInstant()),
                Timestamp.from(end.toInstant()), c.branchId());
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }
        for (var entry : durations.entrySet()) {
            jdbc.update("INSERT INTO schedule_service(schedule_id,item_id,duration_minutes) VALUES(?,?,?)",
                id, entry.getKey(), entry.getValue());
        }
        return schedules(null, null).stream().filter(s -> id == Long.parseLong((String) s.get("schedule_id")))
            .findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> publish(SessionService.Principal actor, long id) {
        requireOwner(actor);
        Long branch = jdbc.query("SELECT branch_id FROM schedule WHERE schedule_id=?", (r,n) -> r.getLong(1), id)
            .stream().findFirst().orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branch);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT schedule_id,state,version FROM schedule WHERE schedule_id=? FOR UPDATE
            """, id);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (!"草稿".equals(rows.getFirst().get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        try {
            jdbc.update("""
                UPDATE schedule SET state='已发布',published_at=?,version=version+1 WHERE schedule_id=?
                """, Timestamp.from(clock.instant()), id);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }
        return schedules(null, null).stream().filter(s -> id == Long.parseLong((String) s.get("schedule_id")))
            .findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> createService(SessionService.Principal actor, ServiceCommand c) {
        requireOwner(actor);
        if (c == null || c.name() == null || c.name().isBlank() || c.name().trim().length() > 100
            || c.minutes() < 5 || c.minutes() > 480 || c.branchId() <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        BigDecimal price;
        try { price = new BigDecimal(c.price()); } catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        if (price.signum() < 0 || price.scale() > 2) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long category = jdbc.queryForObject("SELECT category_id FROM service_category WHERE code='general'", Long.class);
        long id = jdbc.queryForObject("""
            INSERT INTO service_item(name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id)
            VALUES (?,'次',?,true,true,?,?) RETURNING item_id
            """, Long.class, c.name().trim(), price, c.minutes(), category);
        jdbc.update("""
            INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable)
            VALUES (?,?,?,true,true) ON CONFLICT (branch_id,item_id) DO UPDATE SET current_price=EXCLUDED.current_price
            """, c.branchId(), id, price);
        return Map.of("item_id", Long.toString(id), "name", c.name().trim(), "price", price.toPlainString());
    }

    @Transactional
    public Map<String, Object> createResource(SessionService.Principal actor, ResourceCommand c) {
        requireOwner(actor);
        if (c == null || c.name() == null || c.name().isBlank() || c.name().trim().length() > 80 || c.branchId() <= 0)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long category = jdbc.queryForObject("SELECT category_id FROM resource_category WHERE category_code='chair'", Long.class);
        if (jdbc.queryForObject("SELECT count(*) FROM branch WHERE branch_id=? AND state='营业'", Integer.class, c.branchId()) != 1)
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        long id = jdbc.queryForObject("""
            INSERT INTO resource(name,resource_type,category_id,operating_state,branch_id)
            VALUES (?,'诊椅',?,'可用',?) RETURNING resource_id
            """, Long.class, c.name().trim(), category, c.branchId());
        return Map.of("resource_id", Long.toString(id), "name", c.name().trim());
    }

    @Transactional
    public Map<String, Object> assignBranch(SessionService.Principal actor, long staffId, long branchId) {
        requireOwner(actor);
        // 与范围修改、门店创建保持 organization -> branch -> staff 的锁序。
        jdbc.queryForList("SELECT organization_id FROM organization ORDER BY organization_id FOR UPDATE");
        jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branchId);
        jdbc.queryForList("SELECT staff_id FROM staff WHERE staff_id=? FOR UPDATE", staffId);
        requireOwner(actor);
        if (jdbc.queryForObject("SELECT count(*) FROM staff WHERE staff_id=? AND employment_state='在岗'", Integer.class, staffId) != 1
            || jdbc.queryForObject("SELECT count(*) FROM branch WHERE branch_id=? AND state='营业'", Integer.class, branchId) != 1)
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        jdbc.update("""
            INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(?,?,true,?)
            ON CONFLICT (staff_id,branch_id) DO UPDATE SET enabled=true,verified_at=EXCLUDED.verified_at
            """, staffId, branchId, Timestamp.from(clock.instant()));
        return Map.of("staff_id", Long.toString(staffId), "branch_id", Long.toString(branchId));
    }

    @Transactional
    public Map<String, Object> setScope(SessionService.Principal actor, long staffId, ScopeCommand c) {
        requireOwner(actor);
        if (c == null || c.itemIds() == null || c.approvalBasis() == null || c.approvalBasis().isBlank()
            || c.approvalBasis().trim().length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        List<Long> ids = c.itemIds().stream().distinct().toList();
        for (Long id : ids) {
            if (id == null || id <= 0 || jdbc.queryForObject("SELECT count(*) FROM service_item WHERE item_id=? AND enabled=true",
                Integer.class, id) != 1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        // 门店创建与新任职先锁 organization；排班创建/发布先锁 branch。
        // 因此范围修改也按 organization -> branch(升序) -> staff 加锁。
        jdbc.queryForList("SELECT organization_id FROM organization ORDER BY organization_id FOR UPDATE");
        List<Long> branches = jdbc.queryForList("""
            SELECT branch_id FROM staff_branch WHERE staff_id=?
            UNION SELECT branch_id FROM schedule WHERE dentist_id=? AND ends_at>?
            ORDER BY branch_id
            """, Long.class, staffId, staffId, Timestamp.from(clock.instant()));
        for (Long branchId : branches) jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branchId);
        if (jdbc.queryForList("SELECT staff_id FROM staff WHERE staff_id=? AND staff_type='牙医' FOR UPDATE", staffId).isEmpty())
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireOwner(actor);
        List<Long> existing = jdbc.queryForList("SELECT item_id FROM dentist_service WHERE dentist_id=? AND enabled=true",
            Long.class, staffId);
        List<Long> removed = existing.stream().filter(id -> !ids.contains(id)).toList();
        for (Long id : removed) {
            if (jdbc.queryForObject("""
                SELECT count(*) FROM schedule sc JOIN schedule_service ss ON ss.schedule_id=sc.schedule_id
                WHERE sc.dentist_id=? AND ss.item_id=? AND sc.ends_at>? AND sc.state IN ('草稿','已发布','暂停')
                """, Integer.class, staffId, id, Timestamp.from(clock.instant())) > 0)
                throw new ApiException(ApiErrorCode.STAFF_SERVICE_HAS_FUTURE_COMMITMENTS);
        }
        for (Long id : removed) jdbc.update("UPDATE dentist_service SET enabled=false WHERE dentist_id=? AND item_id=?", staffId, id);
        for (Long id : ids) {
            jdbc.update("""
                INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at) VALUES(?,?,true,?)
                ON CONFLICT (dentist_id,item_id) DO UPDATE SET enabled=true,verified_at=EXCLUDED.verified_at
                WHERE dentist_service.enabled=false
                """,
                staffId, id, Timestamp.from(clock.instant()));
        }
        if (!new TreeSet<>(existing).equals(new TreeSet<>(ids))) {
            audit.user(actor.accountId(), "dentist_service", staffId, "set-scope",
                Map.of("service_item_ids", existing.stream().sorted().map(String::valueOf).reduce((a,b) -> a + "," + b).orElse("")),
                Map.of("service_item_ids", ids.stream().sorted().map(String::valueOf).reduce((a,b) -> a + "," + b).orElse("")),
                c.approvalBasis().trim());
        }
        return Map.of("staff_id", Long.toString(staffId), "item_ids", ids.stream().map(String::valueOf).toList());
    }

    private void requireOwner(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        authorization.requireOrganizationOwner(actor);
    }
}
