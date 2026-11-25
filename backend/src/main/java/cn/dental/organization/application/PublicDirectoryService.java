package cn.dental.organization.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Public directory exposes only verified business and clinician facts, never patient data. */
@Service
public class PublicDirectoryService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final BookingAvailabilityService availability;

    public PublicDirectoryService(JdbcTemplate jdbc, Clock clock, BookingAvailabilityService availability) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.availability = availability;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> branches(String cursor, Integer requestedLimit) {
        long after = id(cursor, false);
        int limit = PageResult.validateLimit(requestedLimit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT b.branch_id,b.name,b.address,b.phone,b.opening_hours,b.public_intro FROM branch b
            JOIN organization o ON o.organization_id=b.organization_id
            WHERE b.branch_id>? AND b.state='营业' AND b.verified_at IS NOT NULL
              AND o.state='营业' AND (b.license_valid_to>=? OR
                (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true))
            ORDER BY b.branch_id LIMIT ?
            """, after, LocalDate.now(clock.withZone(SHANGHAI)), limit + 1);
        return page(rows, limit, "branch_id", this::branchRow);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> branch(long branchId) {
        if (branchId <= 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT b.branch_id,b.name,b.address,b.phone,b.opening_hours,b.public_intro FROM branch b
            JOIN organization o ON o.organization_id=b.organization_id
            WHERE b.branch_id=? AND b.state='营业' AND b.verified_at IS NOT NULL
              AND o.state='营业' AND (b.license_valid_to>=? OR
                (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true))
            """, branchId, LocalDate.now(clock.withZone(SHANGHAI)));
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return branchRow(rows.getFirst());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> doctors(String branchId, String itemId, String cursor, Integer requestedLimit) {
        long branch = id(branchId, false);
        long item = id(itemId, false);
        long after = id(cursor, false);
        int limit = PageResult.validateLimit(requestedLimit);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT s.staff_id,s.name,s.job_title,s.public_intro FROM staff s
            WHERE s.staff_id>? AND s.staff_type='牙医' AND s.employment_state='在岗'
              AND EXISTS (SELECT 1 FROM credential c WHERE c.staff_id=s.staff_id
                AND c.credential_type='dentist_practice' AND c.verification_state='verified'
                AND (c.valid_from IS NULL OR c.valid_from<=?)
                AND (c.no_expiry_confirmed=true OR (c.valid_to IS NOT NULL AND c.valid_to>=?)))
              AND EXISTS (SELECT 1 FROM staff_branch sb JOIN branch b ON b.branch_id=sb.branch_id
                JOIN organization o ON o.organization_id=b.organization_id
                WHERE sb.staff_id=s.staff_id AND sb.enabled=true AND sb.verified_at IS NOT NULL
                  AND b.state='营业' AND b.verified_at IS NOT NULL AND o.state='营业'
                  AND (b.license_valid_to>=? OR
                    (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true))
                  AND (?=0 OR b.branch_id=?)
                  AND (?=0 OR EXISTS (SELECT 1 FROM dentist_service ds
                    JOIN branch_service bs ON bs.branch_id=b.branch_id AND bs.item_id=ds.item_id
                    JOIN service_item si ON si.item_id=ds.item_id
                    WHERE ds.dentist_id=s.staff_id AND ds.item_id=? AND ds.enabled=true
                      AND bs.enabled=true AND si.enabled=true AND si.appointment_enabled=true)))
            ORDER BY s.staff_id LIMIT ?
            """, after, today(), today(), today(), branch, branch, item, item, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(limit, rows.size()))) {
            long doctorId = ((Number) row.get("staff_id")).longValue();
            List<Map<String, Object>> clinics = jdbc.queryForList("""
                SELECT b.branch_id,b.name FROM staff_branch sb
                JOIN branch b ON b.branch_id=sb.branch_id
                JOIN organization o ON o.organization_id=b.organization_id
                WHERE sb.staff_id=? AND sb.enabled=true AND sb.verified_at IS NOT NULL
                  AND b.state='营业' AND b.verified_at IS NOT NULL AND o.state='营业'
                  AND (b.license_valid_to>=? OR
                    (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true))
                  AND (?=0 OR b.branch_id=?)
                  AND (?=0 OR EXISTS (SELECT 1 FROM dentist_service ds
                    JOIN branch_service bs ON bs.branch_id=b.branch_id AND bs.item_id=ds.item_id
                    JOIN service_item si ON si.item_id=ds.item_id
                    WHERE ds.dentist_id=? AND ds.item_id=? AND ds.enabled=true
                      AND bs.enabled=true AND si.enabled=true AND si.appointment_enabled=true))
                ORDER BY b.branch_id
                """, doctorId, today(), branch, branch, item, doctorId, item);
            List<Map<String, String>> branches = clinics.stream().map(b -> Map.of(
                "branch_id", String.valueOf(b.get("branch_id")), "branch_name", (String) b.get("name"))).toList();
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("doctor_id", String.valueOf(doctorId));
            person.put("name", row.get("name"));
            person.put("job_title", row.get("job_title"));
            person.put("branches", branches);
            person.put("public_intro", row.get("public_intro"));
            items.add(person);
        }
        return new PageResult<>(items, more ? String.valueOf(rows.get(limit - 1).get("staff_id")) : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> doctor(long doctorId) {
        if (doctorId <= 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        PageResult<Map<String, Object>> result = doctors(null, null, String.valueOf(doctorId - 1), 1);
        if (result.items().isEmpty() || !String.valueOf(doctorId).equals(result.items().getFirst().get("doctor_id")))
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        return result.items().getFirst();
    }

    /** Anonymous candidates only: the booking transaction remains the final authority. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> slots(String branchRaw, String itemRaw, String dentistRaw,
            String visitType, String fromRaw, String toRaw) {
        long branchId = id(branchRaw, true);
        long itemId = id(itemRaw, true);
        long dentistId = id(dentistRaw, false);
        if (branchId == 0 || itemId == 0 || (dentistRaw != null && !dentistRaw.isBlank() && dentistId == 0)
                || (!"初诊".equals(visitType) && !"复诊".equals(visitType)))
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromRaw);
            to = LocalDate.parse(toRaw);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 30)
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);

        // Public directory visibility includes organization and license state.
        branch(branchId);
        List<Long> policies = jdbc.queryForList("""
            SELECT policy_id FROM booking_policy WHERE branch_id=? AND status='published'
            ORDER BY version_no DESC LIMIT 1
            """, Long.class, branchId);
        if (policies.isEmpty()) return new PageResult<>(List.of(), null);
        String policyId = Long.toString(policies.getFirst());
        Instant lower;
        Instant upper;
        try {
            lower = from.atStartOfDay(SHANGHAI).toInstant();
            upper = to.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        } catch (java.time.DateTimeException ex) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        }
        List<Map<String, Object>> schedules = jdbc.queryForList("""
            SELECT sc.schedule_id,sc.dentist_id,ss.duration_minutes
            FROM schedule sc JOIN schedule_service ss ON ss.schedule_id=sc.schedule_id AND ss.item_id=?
            WHERE sc.branch_id=? AND sc.state='已发布' AND sc.service_type IN ('两者',?)
              AND sc.starts_at<? AND sc.ends_at>?
              AND (?=0 OR sc.dentist_id=?)
            ORDER BY sc.starts_at,sc.schedule_id
            """, itemId, branchId, visitType, java.sql.Timestamp.from(upper),
            java.sql.Timestamp.from(lower), dentistId, dentistId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> schedule : schedules) {
            long scheduleId = ((Number) schedule.get("schedule_id")).longValue();
            long doctor = ((Number) schedule.get("dentist_id")).longValue();
            int minutes = ((Number) schedule.get("duration_minutes")).intValue();
            for (String rawSlot : availability.previewSlots(branchId, scheduleId, itemId, true)) {
                Instant start = Instant.parse(rawSlot);
                LocalDate date = start.atZone(SHANGHAI).toLocalDate();
                if (date.isBefore(from) || date.isAfter(to)) continue;
                items.add(Map.of("schedule_id", Long.toString(scheduleId),
                    "starts_at", rawSlot, "ends_at", start.plus(minutes, ChronoUnit.MINUTES).toString(),
                    "dentist_id", Long.toString(doctor), "policy_id", policyId));
            }
        }
        items.sort(java.util.Comparator.comparing(row -> (String) row.get("starts_at")));
        return new PageResult<>(items, null);
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(SHANGHAI)); }

    private long id(String raw, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) throw new ApiException(ApiErrorCode.INVALID_REQUEST);
            return 0;
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        }
    }

    private Map<String, Object> branchRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("branch_id", String.valueOf(row.get("branch_id")));
        for (String key : List.of("name", "address", "phone", "opening_hours", "public_intro")) result.put(key, row.get(key));
        return result;
    }

    private PageResult<Map<String, Object>> page(List<Map<String, Object>> rows, int limit, String id,
            java.util.function.Function<Map<String, Object>, Map<String, Object>> mapper) {
        boolean more = rows.size() > limit;
        List<Map<String, Object>> result = rows.subList(0, Math.min(limit, rows.size())).stream().map(mapper).toList();
        return new PageResult<>(result, more ? String.valueOf(rows.get(limit - 1).get(id)) : null);
    }
}
