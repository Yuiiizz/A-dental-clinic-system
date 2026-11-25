package cn.dental.organization.application;

import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.ApiImpactException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 营业区间按日期范围替换；branch.version 是集合聚合版本。 */
@Service
public class OpeningIntervalService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAYS = 31;
    private static final int MAX_INTERVALS = 1000;
    private static final DateTimeFormatter OFFSET_SECONDS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final BranchService branches;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private Clock clock;

    public OpeningIntervalService(
            JdbcTemplate jdbc,
            BranchService branches,
            IdempotencyService idempotency,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(
            SessionService.Principal actor, long branchId, LocalDate fromDate, LocalDate toDate) {
        branches.requireAuthorized(actor, branchId);
        validateRange(fromDate, toDate);
        return view(branchId, fromDate, toDate);
    }

    @Transactional
    public Map<String, Object> replace(
            SessionService.Principal actor,
            long branchId,
            LocalDate fromDate,
            LocalDate toDate,
            List<IntervalInput> inputs,
            int expectedVersion,
            String key) {
        branches.requireAuthorized(actor, branchId);
        validateRange(fromDate, toDate);
        List<Parsed> requested = parseAndValidate(inputs, fromDate, toDate);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("branch_id", Long.toString(branchId));
        content.put("from_date", fromDate.toString());
        content.put("to_date", toDate.toString());
        content.put("expected_version", expectedVersion);
        content.put("intervals", canonical(requested));
        branches.lockBranch(branchId);
        idempotency.execute(
            actor.accountId(), "branch-opening-intervals", key, IdempotencyService.canonicalize(content),
            () -> branches.requireAuthorized(actor, branchId),
            () -> replaceInTx(actor, branchId, fromDate, toDate, requested, expectedVersion),
            id -> branches.requireAuthorized(actor, id));
        return view(branchId, fromDate, toDate);
    }

    private IdempotencyService.BusinessResult replaceInTx(
            SessionService.Principal actor,
            long branchId,
            LocalDate fromDate,
            LocalDate toDate,
            List<Parsed> requested,
            int expectedVersion) {
        branches.requireAuthorized(actor, branchId);
        int currentVersion = branchVersionForUpdate(branchId);
        Instant now = clock.instant();
        LocalDate today = now.atZone(SHANGHAI).toLocalDate();
        if (fromDate.isBefore(today)) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_HISTORY_LOCKED);
        if (currentVersion != expectedVersion) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);

        List<Parsed> existingRange = loadRange(branchId, fromDate, toDate);
        List<Parsed> startedToday = fromDate.equals(today)
            ? loadStartedToday(branchId, today, now) : List.of();
        for (Parsed started : startedToday) {
            boolean kept = requested.stream().anyMatch(r -> r.sameKey(started));
            if (!kept) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_HISTORY_LOCKED);
        }
        for (Parsed requestedInterval : requested) {
            if (!requestedInterval.start().isAfter(now)
                && startedToday.stream().noneMatch(existing -> existing.sameKey(requestedInterval))) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_HISTORY_LOCKED);
            }
        }
        if (same(existingRange, requested)) {
            return branches.businessResult(branchId, "opening", currentVersion);
        }

        List<Parsed> outside = loadOutside(branchId, fromDate, toDate);
        List<Parsed> after = new ArrayList<>(outside);
        after.addAll(requested);
        validateNoOverlap(after);
        enforceCoverage(after, branchId, fromDate, toDate, now);

        jdbc.update("DELETE FROM branch_opening_interval WHERE branch_id=? AND valid_on BETWEEN ? AND ?",
            branchId, fromDate, toDate);
        for (Parsed p : requested) {
            jdbc.update("""
                INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
                VALUES(?,?,?,?,1)
                """, branchId, java.sql.Date.valueOf(p.validOn()), Timestamp.from(p.start()), Timestamp.from(p.end()));
        }
        jdbc.update("UPDATE branch SET version=version+1 WHERE branch_id=?", branchId);
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> afterAudit = new LinkedHashMap<>();
        before.put("opening_from", fromDate.toString());
        before.put("opening_to", toDate.toString());
        before.put("opening_interval_count", Integer.toString(existingRange.size()));
        afterAudit.put("opening_from", fromDate.toString());
        afterAudit.put("opening_to", toDate.toString());
        afterAudit.put("opening_interval_count", Integer.toString(requested.size()));
        audit.user(actor.accountId(), "branch", branchId, "替换营业区间", before, afterAudit, "替换营业区间");
        return branches.businessResult(branchId, "opening", currentVersion + 1);
    }

    private void validateRange(LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null || toDate.isBefore(fromDate)) {
            throw new ApiException(ApiErrorCode.OPENING_INTERVAL_RANGE_INVALID);
        }
        long days = rangeDays(fromDate, toDate);
        if (days < 1 || days > MAX_DAYS) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_RANGE_INVALID);
    }

    static long rangeDays(LocalDate fromDate, LocalDate toDate) {
        return ChronoUnit.DAYS.between(fromDate, toDate) + 1;
    }

    static boolean isBeforeToday(LocalDate date, Clock clock) {
        return date.isBefore(BranchService.shanghaiDate(clock));
    }

    private List<Parsed> parseAndValidate(List<IntervalInput> inputs, LocalDate fromDate, LocalDate toDate) {
        if (inputs == null || inputs.size() > MAX_INTERVALS) {
            throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
        }
        List<Parsed> parsed = new ArrayList<>();
        for (IntervalInput input : inputs) {
            if (input == null) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            LocalDate validOn;
            Instant start;
            Instant end;
            try {
                validOn = LocalDate.parse(input.valid_on());
                start = OffsetDateTime.parse(input.starts_at()).toInstant();
                end = OffsetDateTime.parse(input.ends_at()).toInstant();
            } catch (RuntimeException e) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            }
            if (start.getNano() != 0 || end.getNano() != 0) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            }
            if (validOn.isBefore(fromDate) || validOn.isAfter(toDate)) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            }
            ZonedDateTime localStart = start.atZone(SHANGHAI);
            ZonedDateTime localEnd = end.atZone(SHANGHAI);
            if (!localStart.toLocalDate().equals(validOn)) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            }
            boolean sameDay = localEnd.toLocalDate().equals(validOn);
            boolean nextMidnight = localEnd.toLocalDate().equals(validOn.plusDays(1))
                && localEnd.toLocalTime().equals(LocalTime.MIDNIGHT);
            if (!sameDay && !nextMidnight) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            if (!start.isBefore(end)) throw new ApiException(ApiErrorCode.OPENING_INTERVAL_INVALID);
            parsed.add(new Parsed(validOn, start, end));
        }
        parsed.sort(Parsed.ORDER);
        validateNoOverlap(parsed);
        return parsed;
    }

    private void validateNoOverlap(List<Parsed> intervals) {
        List<Parsed> sorted = new ArrayList<>(intervals);
        sorted.sort(Parsed.ORDER);
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).start().isBefore(sorted.get(i - 1).end())) {
                throw new ApiException(ApiErrorCode.OPENING_INTERVAL_OVERLAP);
            }
        }
    }

    private boolean same(List<Parsed> a, List<Parsed> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sameKey(b.get(i))) return false;
        }
        return true;
    }

    private List<Parsed> loadRange(long branchId, LocalDate fromDate, LocalDate toDate) {
        return jdbc.query("""
            SELECT valid_on,starts_at,ends_at FROM branch_opening_interval
            WHERE branch_id=? AND valid_on BETWEEN ? AND ? ORDER BY valid_on,starts_at,interval_id
            """, (rs, rowNum) -> new Parsed(rs.getDate("valid_on").toLocalDate(),
                rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
            branchId, java.sql.Date.valueOf(fromDate), java.sql.Date.valueOf(toDate));
    }

    private List<Parsed> loadOutside(long branchId, LocalDate fromDate, LocalDate toDate) {
        return jdbc.query("""
            SELECT valid_on,starts_at,ends_at FROM branch_opening_interval
            WHERE branch_id=? AND (valid_on < ? OR valid_on > ?) ORDER BY valid_on,starts_at,interval_id
            """, (rs, rowNum) -> new Parsed(rs.getDate("valid_on").toLocalDate(),
                rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
            branchId, java.sql.Date.valueOf(fromDate), java.sql.Date.valueOf(toDate));
    }

    private List<Parsed> loadStartedToday(long branchId, LocalDate today, Instant now) {
        return jdbc.query("""
            SELECT valid_on,starts_at,ends_at FROM branch_opening_interval
            WHERE branch_id=? AND valid_on=? AND starts_at <= ? ORDER BY starts_at,interval_id
            """, (rs, rowNum) -> new Parsed(rs.getDate("valid_on").toLocalDate(),
                rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
            branchId, java.sql.Date.valueOf(today), Timestamp.from(now));
    }

    private void enforceCoverage(
            List<Parsed> after, long branchId, LocalDate fromDate, LocalDate toDate, Instant now) {
        Instant rangeStart = fromDate.atStartOfDay(SHANGHAI).toInstant();
        Instant rangeEnd = toDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        List<Parsed> merged = merge(after);

        List<Map<String, Object>> schedules = jdbc.queryForList("""
            SELECT starts_at,ends_at FROM schedule
            WHERE branch_id=? AND state='已发布' AND ends_at>?
              AND starts_at<? AND ends_at>?
            """, branchId, Timestamp.from(now), Timestamp.from(rangeEnd), Timestamp.from(rangeStart));
        List<Map<String, Object>> appointments = jdbc.queryForList("""
            SELECT starts_at,ends_at FROM appointment
            WHERE branch_id=? AND state IN ('已确认','候诊中','接诊中')
              AND starts_at<? AND ends_at>?
            """, branchId, Timestamp.from(rangeEnd), Timestamp.from(rangeStart));

        long scheduleCount = 0;
        long appointmentCount = 0;
        Timestamp first = null;
        Timestamp last = null;
        for (Map<String, Object> row : schedules) {
            Timestamp start = (Timestamp) row.get("starts_at");
            Timestamp end = (Timestamp) row.get("ends_at");
            if (!covered(merged, start.toInstant(), end.toInstant())) {
                scheduleCount++;
                first = earlier(first, start);
                last = later(last, end);
            }
        }
        for (Map<String, Object> row : appointments) {
            Timestamp start = (Timestamp) row.get("starts_at");
            Timestamp end = (Timestamp) row.get("ends_at");
            if (!covered(merged, start.toInstant(), end.toInstant())) {
                appointmentCount++;
                first = earlier(first, start);
                last = later(last, end);
            }
        }
        if (scheduleCount == 0 && appointmentCount == 0) return;
        throw new ApiImpactException(ApiErrorCode.OPENING_INTERVAL_COVERAGE_CONFLICT,
            new ApiImpactException.ApiImpact(scheduleCount, appointmentCount,
                first == null ? null : first.toInstant().toString(),
                last == null ? null : last.toInstant().toString()));
    }

    private Timestamp earlier(Timestamp a, Timestamp b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.before(b) ? a : b;
    }

    private Timestamp later(Timestamp a, Timestamp b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.after(b) ? a : b;
    }

    private List<Parsed> merge(List<Parsed> intervals) {
        List<Parsed> sorted = new ArrayList<>(intervals);
        sorted.sort(Parsed.ORDER);
        List<Parsed> merged = new ArrayList<>();
        for (Parsed next : sorted) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            Parsed last = merged.getLast();
            if (!next.start().isAfter(last.end())) {
                merged.set(merged.size() - 1, new Parsed(last.validOn(),
                    last.start(), next.end().isAfter(last.end()) ? next.end() : last.end()));
            } else {
                merged.add(next);
            }
        }
        return merged;
    }

    private boolean covered(List<Parsed> merged, Instant start, Instant end) {
        for (Parsed interval : merged) {
            if (!start.isBefore(interval.start()) && !end.isAfter(interval.end())) return true;
        }
        return false;
    }

    private int branchVersionForUpdate(long branchId) {
        List<Integer> rows = jdbc.queryForList("SELECT version FROM branch WHERE branch_id=? FOR UPDATE", Integer.class, branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    private Map<String, Object> view(long branchId, LocalDate fromDate, LocalDate toDate) {
        // Single statement: version and intervals come from one PostgreSQL snapshot, never mixed.
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT b.version,i.valid_on,i.starts_at,i.ends_at
            FROM branch b
            LEFT JOIN branch_opening_interval i
              ON i.branch_id=b.branch_id AND i.valid_on BETWEEN ? AND ?
            WHERE b.branch_id=?
            ORDER BY i.valid_on,i.starts_at,i.interval_id
            """, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("version", rs.getInt("version"));
                row.put("valid_on", rs.getDate("valid_on"));
                row.put("starts_at", rs.getTimestamp("starts_at"));
                row.put("ends_at", rs.getTimestamp("ends_at"));
                return row;
            }, java.sql.Date.valueOf(fromDate), java.sql.Date.valueOf(toDate), branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        int version = ((Number) rows.getFirst().get("version")).intValue();
        List<Map<String, Object>> intervals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("valid_on") == null) continue;
            Instant start = ((Timestamp) row.get("starts_at")).toInstant();
            Instant end = ((Timestamp) row.get("ends_at")).toInstant();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("valid_on", ((java.sql.Date) row.get("valid_on")).toLocalDate().toString());
            value.put("starts_at", OFFSET_SECONDS.format(start.atZone(SHANGHAI)));
            value.put("ends_at", OFFSET_SECONDS.format(end.atZone(SHANGHAI)));
            intervals.add(value);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branch_id", Long.toString(branchId));
        data.put("from_date", fromDate.toString());
        data.put("to_date", toDate.toString());
        data.put("version", version);
        data.put("intervals", intervals);
        return data;
    }

    private List<Map<String, Object>> canonical(List<Parsed> intervals) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Parsed p : intervals) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("valid_on", p.validOn().toString());
            value.put("starts_at", p.start().toString());
            value.put("ends_at", p.end().toString());
            values.add(value);
        }
        return values;
    }

    public record IntervalInput(String valid_on, String starts_at, String ends_at) {}

    private record Parsed(LocalDate validOn, Instant start, Instant end) {
        static final Comparator<Parsed> ORDER = Comparator.comparing(Parsed::start)
            .thenComparing(Parsed::end).thenComparing(Parsed::validOn);
        boolean sameKey(Parsed other) {
            return validOn.equals(other.validOn) && start.equals(other.start) && end.equals(other.end);
        }
    }
}
