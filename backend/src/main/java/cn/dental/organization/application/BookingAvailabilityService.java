package cn.dental.organization.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One availability rule for patient create, patient reschedule and frontdesk booking.
 *
 * Requires an existing caller transaction. Creation locks branch then schedule here;
 * rescheduling re-enters with its ordered branch/schedule and source appointment locks held.
 * Staff, credential and service facts are read after those locks; their concurrent writers
 * still require their own compatible protocols. Failure rolls back the caller transaction.
 */
@Service
public class BookingAvailabilityService {
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final java.time.Clock clock;

    public BookingAvailabilityService(JdbcTemplate jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Window(long branchId, long dentistId, Instant startsAt, Instant endsAt) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Window requireAvailable(long branchId, long scheduleId, long itemId, Instant startsAt,
                                   boolean patientSelf) {
        if (branchId <= 0 || scheduleId <= 0 || itemId <= 0 || startsAt == null
            || !startsAt.isAfter(clock.instant()))
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        // Shared aggregate lock with opening-interval edits; re-read schedule after the lock.
        List<Long> branches = jdbc.queryForList(
            "SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", Long.class, branchId);
        if (branches.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        Map<String, Object> branch = jdbc.queryForMap("""
            SELECT state,verified_at FROM branch WHERE branch_id=?
            """, branchId);
        if (!"营业".equals(branch.get("state")) || branch.get("verified_at") == null)
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        List<Map<String, Object>> scheduleRows = jdbc.queryForList("""
            SELECT schedule_id,dentist_id,branch_id,starts_at,ends_at,state
            FROM schedule WHERE schedule_id=? FOR UPDATE
            """, scheduleId);
        if (scheduleRows.isEmpty()) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        Map<String, Object> schedule = scheduleRows.getFirst();
        // Re-evaluate wall-clock time after both row locks; a waiter may now be inside/past the slot.
        if (!startsAt.isAfter(clock.instant())) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        if (!"已发布".equals(schedule.get("state"))
            || ((Number) schedule.get("branch_id")).longValue() != branchId)
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        long dentistId = ((Number) schedule.get("dentist_id")).longValue();
        Instant scheduleStart = ((Timestamp) schedule.get("starts_at")).toInstant();
        Instant scheduleEnd = ((Timestamp) schedule.get("ends_at")).toInstant();

        Integer serviceMinutes = jdbc.query("""
            SELECT duration_minutes FROM schedule_service WHERE schedule_id=? AND item_id=?
            """, (r, n) -> r.getInt(1), scheduleId, itemId).stream().findFirst().orElse(null);
        if (serviceMinutes == null || serviceMinutes <= 0) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        Instant endsAt = startsAt.plus(Duration.ofMinutes(serviceMinutes));
        if (startsAt.isBefore(scheduleStart) || endsAt.isAfter(scheduleEnd))
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        Map<String, Object> dentist = jdbc.queryForMap("""
            SELECT staff_type,employment_state FROM staff WHERE staff_id=?
            """, dentistId);
        if (!"牙医".equals(dentist.get("staff_type")) || !"在岗".equals(dentist.get("employment_state")))
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        int branchRelation = jdbc.queryForObject("""
            SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=?
              AND enabled=true AND verified_at IS NOT NULL
            """, Integer.class, dentistId, branchId);
        if (branchRelation != 1) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        int serviceOpen = jdbc.queryForObject("""
            SELECT count(*) FROM service_item i
            JOIN branch_service bs ON bs.item_id=i.item_id AND bs.branch_id=?
            JOIN dentist_service ds ON ds.item_id=i.item_id AND ds.dentist_id=?
            WHERE i.item_id=? AND i.enabled=true AND i.appointment_enabled=true
              AND bs.enabled=true AND (? = false OR bs.auto_bookable=true)
              AND ds.enabled=true
            """, Integer.class, branchId, dentistId, itemId, patientSelf);
        if (serviceOpen != 1) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        LocalDate startDate = startsAt.atZone(SHANGHAI).toLocalDate();
        LocalDate endDate = endsAt.atZone(SHANGHAI).toLocalDate();
        if (endsAt.atZone(SHANGHAI).toLocalTime().equals(LocalTime.MIDNIGHT)) {
            endDate = endDate.minusDays(1);
        }
        int credential = jdbc.queryForObject("""
            SELECT count(*) FROM credential
            WHERE staff_id=? AND credential_type='dentist_practice' AND verification_state='verified'
              AND (valid_from IS NULL OR valid_from <= ?)
              AND (no_expiry_confirmed=true OR (valid_to IS NOT NULL AND valid_to >= ?))
            """, Integer.class, dentistId, java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate));
        if (credential < 1) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        int absence = jdbc.queryForObject("""
            SELECT count(*) FROM dentist_unavailable
            WHERE dentist_id=? AND state='有效'
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """, Integer.class, dentistId, Timestamp.from(startsAt), Timestamp.from(endsAt));
        if (absence > 0) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);

        requireOpeningCoverage(branchId, startsAt, endsAt);
        return new Window(branchId, dentistId, startsAt, endsAt);
    }

    /**
     * Read-only candidate grid for display. It applies persisted configuration gates and excludes
     * known doctor/resource occupancy. It does not reserve a slot; submit revalidates and may conflict.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<String> previewSlots(long branchId, long scheduleId, long itemId, boolean patientSelf) {
        return previewSlots(branchId, scheduleId, itemId, patientSelf, null);
    }

    /**
     * Patient self-service preview additionally excludes the caller's own active appointment
     * overlaps across doctors and branches. Front desk preview passes null because no patient has
     * been selected at this stage. This is advisory only; create still rechecks atomically.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<String> previewSlots(long branchId, long scheduleId, long itemId, boolean patientSelf,
                                     Long patientId) {
        List<Long> branchRows = jdbc.queryForList(
            "SELECT branch_id FROM branch WHERE branch_id=? AND state='营业' AND verified_at IS NOT NULL",
            Long.class, branchId);
        if (branchRows.isEmpty()) return List.of();
        List<Map<String, Object>> scheduleRows = jdbc.queryForList("""
            SELECT dentist_id,starts_at,ends_at,state FROM schedule WHERE schedule_id=? AND branch_id=?
            """, scheduleId, branchId);
        if (scheduleRows.isEmpty() || !"已发布".equals(scheduleRows.getFirst().get("state"))) return List.of();
        Map<String, Object> schedule = scheduleRows.getFirst();
        long dentistId = ((Number) schedule.get("dentist_id")).longValue();
        List<Integer> durations = jdbc.queryForList("""
            SELECT ss.duration_minutes FROM schedule_service ss
            JOIN service_item i ON i.item_id=ss.item_id
            JOIN branch_service bs ON bs.branch_id=? AND bs.item_id=ss.item_id AND bs.enabled=true
              AND (? = false OR bs.auto_bookable=true)
            JOIN dentist_service ds ON ds.dentist_id=? AND ds.item_id=ss.item_id AND ds.enabled=true
            WHERE ss.schedule_id=? AND ss.item_id=? AND i.enabled=true AND i.appointment_enabled=true
            """, Integer.class, branchId, patientSelf, dentistId, scheduleId, itemId);
        if (durations.isEmpty()) return List.of();
        int minutes = durations.getFirst();
        Integer staffCount = jdbc.queryForObject("""
            SELECT count(*) FROM staff s JOIN staff_branch sb ON sb.staff_id=s.staff_id
            WHERE s.staff_id=? AND s.staff_type='牙医' AND s.employment_state='在岗'
              AND sb.branch_id=? AND sb.enabled=true AND sb.verified_at IS NOT NULL
            """, Integer.class, dentistId, branchId);
        if (staffCount == null || staffCount != 1) return List.of();
        List<Long> chairs = jdbc.queryForList("""
            SELECT resource_id FROM resource
            WHERE branch_id=? AND resource_type='牙椅' AND operating_state='可用'
            """, Long.class, branchId);
        if (chairs.isEmpty()) return List.of();
        Instant scheduleStart = ((Timestamp) schedule.get("starts_at")).toInstant();
        Instant scheduleEnd = ((Timestamp) schedule.get("ends_at")).toInstant();
        List<Instant[]> openings = openingIntervals(branchId, scheduleStart, scheduleEnd);
        List<Instant[]> absences = jdbc.query("""
            SELECT starts_at,ends_at FROM dentist_unavailable
            WHERE dentist_id=? AND state='有效' AND ends_at>? AND starts_at<?
            """, (r, n) -> new Instant[]{r.getTimestamp("starts_at").toInstant(), r.getTimestamp("ends_at").toInstant()},
            dentistId, Timestamp.from(scheduleStart), Timestamp.from(scheduleEnd));
        List<Instant[]> doctorBusy = jdbc.query("""
            SELECT starts_at,ends_at FROM appointment
            WHERE confirmed_dentist_id=? AND state IN ('已确认','候诊中','接诊中')
              AND ends_at>? AND starts_at<?
            """, (r, n) -> new Instant[]{r.getTimestamp("starts_at").toInstant(), r.getTimestamp("ends_at").toInstant()},
            dentistId, Timestamp.from(scheduleStart), Timestamp.from(scheduleEnd));
        List<Instant[]> patientBusy = patientId == null ? List.of() : jdbc.query("""
            SELECT starts_at,ends_at FROM appointment
            WHERE patient_id=? AND state IN ('已确认','候诊中','接诊中')
              AND ends_at>? AND starts_at<?
            """, (r, n) -> new Instant[]{r.getTimestamp("starts_at").toInstant(), r.getTimestamp("ends_at").toInstant()},
            patientId, Timestamp.from(scheduleStart), Timestamp.from(scheduleEnd));
        List<Map<String, Object>> maintenance = jdbc.queryForList("""
            SELECT resource_id,starts_at FROM resource_unavailable
            WHERE resource_id IN (%s) AND state='有效' AND starts_at < ?
            """.formatted(placeholders(chairs)), append(chairs, Timestamp.from(scheduleEnd)));
        List<Map<String, Object>> resourceBusy = jdbc.queryForList("""
            SELECT resource_id,starts_at,ends_at FROM resource_booking
            WHERE resource_id IN (%s) AND state='有效' AND ends_at>? AND starts_at<?
            """.formatted(placeholders(chairs)), append(chairs, Timestamp.from(scheduleStart), Timestamp.from(scheduleEnd)));
        List<Map<String, Object>> credentials = jdbc.queryForList("""
            SELECT valid_from,valid_to,no_expiry_confirmed FROM credential
            WHERE staff_id=? AND credential_type='dentist_practice' AND verification_state='verified'
            """, dentistId);
        Instant now = clock.instant();
        List<String> slots = new ArrayList<>();
        for (Instant t = scheduleStart; !t.plus(Duration.ofMinutes(minutes)).isAfter(scheduleEnd);
             t = t.plus(Duration.ofMinutes(30))) {
            Instant end = t.plus(Duration.ofMinutes(minutes));
            if (!t.isAfter(now)) continue;
            if (!covered(openings, t, end)) continue;
            if (overlaps(doctorBusy, t, end) || overlaps(patientBusy, t, end)
                || overlaps(absences, t, end)) continue;
            if (!chairAvailable(chairs, maintenance, resourceBusy, t, end)) continue;
            LocalDate startDate = t.atZone(SHANGHAI).toLocalDate();
            LocalDate endDate = end.atZone(SHANGHAI).toLocalDate();
            if (end.atZone(SHANGHAI).toLocalTime().equals(LocalTime.MIDNIGHT)) endDate = endDate.minusDays(1);
            if (!credentialCovers(credentials, startDate, endDate)) continue;
            slots.add(t.toString());
        }
        return slots;
    }

    private boolean overlaps(List<Instant[]> ranges, Instant start, Instant end) {
        for (Instant[] range : ranges) {
            if (start.isBefore(range[1]) && end.isAfter(range[0])) return true;
        }
        return false;
    }

    private boolean chairAvailable(List<Long> chairs, List<Map<String, Object>> maintenance,
                                   List<Map<String, Object>> bookings, Instant start, Instant end) {
        for (long chair : chairs) {
            boolean blocked = false;
            for (Map<String, Object> row : maintenance) {
                if (((Number) row.get("resource_id")).longValue() == chair
                    && ((Timestamp) row.get("starts_at")).toInstant().isBefore(end)) {
                    blocked = true; break;
                }
            }
            if (blocked) continue;
            for (Map<String, Object> row : bookings) {
                if (((Number) row.get("resource_id")).longValue() == chair) {
                    Instant s = ((Timestamp) row.get("starts_at")).toInstant();
                    Instant e = ((Timestamp) row.get("ends_at")).toInstant();
                    if (start.isBefore(e) && end.isAfter(s)) { blocked = true; break; }
                }
            }
            if (!blocked) return true;
        }
        return false;
    }

    private boolean credentialCovers(List<Map<String, Object>> credentials, LocalDate start, LocalDate end) {
        for (Map<String, Object> row : credentials) {
            java.sql.Date from = (java.sql.Date) row.get("valid_from");
            java.sql.Date to = (java.sql.Date) row.get("valid_to");
            boolean noExpiry = Boolean.TRUE.equals(row.get("no_expiry_confirmed"));
            if ((from == null || !from.toLocalDate().isAfter(start))
                && (noExpiry || (to != null && !to.toLocalDate().isBefore(end)))) return true;
        }
        return false;
    }

    private static String placeholders(List<?> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }

    private static Object[] append(List<?> list, Object... tail) {
        List<Object> all = new ArrayList<>(list);
        all.addAll(Arrays.asList(tail));
        return all.toArray();
    }

    private List<Instant[]> openingIntervals(long branchId, Instant startsAt, Instant endsAt) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT starts_at,ends_at FROM branch_opening_interval
            WHERE branch_id=? AND starts_at < ? AND ends_at > ?
            ORDER BY starts_at,ends_at
            """, branchId, Timestamp.from(endsAt), Timestamp.from(startsAt));
        List<Instant[]> merged = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant s = ((Timestamp) row.get("starts_at")).toInstant();
            Instant e = ((Timestamp) row.get("ends_at")).toInstant();
            if (merged.isEmpty() || s.isAfter(merged.getLast()[1])) merged.add(new Instant[]{s, e});
            else if (e.isAfter(merged.getLast()[1])) merged.getLast()[1] = e;
        }
        return merged;
    }

    private boolean covered(List<Instant[]> merged, Instant start, Instant end) {
        for (Instant[] interval : merged) {
            if (!start.isBefore(interval[0]) && !end.isAfter(interval[1])) return true;
        }
        return false;
    }

    private void requireOpeningCoverage(long branchId, Instant startsAt, Instant endsAt) {
        List<Instant[]> merged = openingIntervals(branchId, startsAt, endsAt);
        if (!covered(merged, startsAt, endsAt)) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
    }
}
