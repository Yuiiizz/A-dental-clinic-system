package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.ApiImpactException;
import cn.dental.shared.web.PageResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T024-A：门店创建、列表、详情。
 *
 * 授权规则：
 * - 列表/详情：同一 role 的 branch.configure + role_branch_scope + 有效且已核实 staff_branch；
 * - 创建：唯一 organization 的 responsible_staff_id，且其 owner 角色具有 branches.create；
 * - 创建事务只给创建者的 owner 角色建立新店 scope 和已核实任职，不新授角色。
 */
@Service
public class BranchService {
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public BranchService(
            JdbcTemplate jdbc,
            AuthorizationService authorization,
            IdempotencyService idempotency,
            AuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateCommand(
            String code,
            String name,
            String address,
            String phone,
            String licenseNo,
            String licenseValidTo,
            String openingHours,
            String managerId) {}

    public PageResult<Map<String, Object>> list(SessionService.Principal actor, String cursor, Integer requestedLimit) {
        requireBusinessStaff(actor);
        requireConfigurePermission(actor);
        int limit = PageResult.validateLimit(requestedLimit);
        long after = 0;
        if (cursor != null) {
            try {
                after = Long.parseLong(cursor);
                if (after <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new ApiException(ApiErrorCode.INVALID_REQUEST);
            }
        }
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT b.branch_id,b.code,b.name,b.state,b.version,b.manager_id
            FROM branch b
            WHERE b.branch_id>? AND EXISTS (
                SELECT 1
                FROM account_role ar
                JOIN role_permission rp ON rp.role_id=ar.role_id
                JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='branch.configure'
                JOIN role_branch_scope bs ON bs.account_id=ar.account_id AND bs.role_id=ar.role_id AND bs.branch_id=b.branch_id
                JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                    AND sb.enabled AND sb.verified_at IS NOT NULL
                WHERE ar.account_id=?
            )
            ORDER BY b.branch_id LIMIT ?
            """, (r, n) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("branch_id", Long.toString(r.getLong(1)));
                item.put("code", r.getString(2));
                item.put("name", r.getString(3));
                item.put("state", r.getString(4));
                item.put("version", r.getInt(5));
                item.put("manager_id", Long.toString(r.getLong(6)));
                return item;
            }, after, actor.staffId(), actor.accountId(), limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        return new PageResult<>(page, more ? (String) page.getLast().get("branch_id") : null);
    }

    public Map<String, Object> detail(SessionService.Principal actor, long branchId) {
        requireBusinessStaff(actor);
        if (branchId <= 0 || !canConfigure(actor, branchId)) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return detailInternal(branchId, false);
    }

    /**
     * 创建门店。账号维护锁必须先于幂等锁；幂等业务回调只在首次执行时写入任职/scope，
     * 重放只重新鉴权、检查可见性并返回原结果。
     */
    @Transactional
    public Map<String, Object> create(SessionService.Principal actor, CreateCommand command, String idempotencyKey) {
        Validated create = validate(command, actor);
        jdbc.execute("SELECT pg_advisory_xact_lock(734192014)");
        try {
            IdempotencyService.Outcome outcome = idempotency.execute(
                actor.accountId(),
                "branch-create",
                idempotencyKey,
                requestContent(create),
                () -> requireCreator(actor, create.managerId()),
                () -> createInTx(actor, create),
                branchId -> {
                    if (!canConfigure(actor, branchId)) throw new ApiException(ApiErrorCode.NOT_FOUND);
                });
            requireVisible(actor, outcome.targetId());
            return detailInternal(outcome.targetId(), !outcome.replayed());
        } catch (DuplicateKeyException e) {
            // 唯一冲突（典型为 branch.code）整体回滚；不在失败事务中继续查询。
            throw new ApiException(ApiErrorCode.BRANCH_CODE_EXISTS);
        }
    }

    @Transactional
    public Map<String, Object> patch(SessionService.Principal actor, long branchId, Map<String, Object> body, String key) {
        PatchInput input = parsePatch(body);
        return mutate(actor, branchId, "branch-patch", key, patchContent(branchId, input),
            () -> patchInTx(actor, branchId, input));
    }

    @Transactional
    public Map<String, Object> submit(SessionService.Principal actor, long branchId, int expectedVersion, String key) {
        return mutate(actor, branchId, "branch-submit", key, versionContent(branchId, expectedVersion, "submit"),
            () -> transition(actor, branchId, expectedVersion, "草稿", "待核验", "提交门店核验", "提交核验"));
    }

    @Transactional
    public Map<String, Object> verify(
            SessionService.Principal actor,
            long branchId,
            int expectedVersion,
            String verificationBasis,
            Boolean noExpiryConfirmed,
            String key) {
        String basis = verificationBasis == null ? "" : verificationBasis.trim();
        if (basis.isEmpty() || basis.length() > 300) throw new ApiException(ApiErrorCode.BRANCH_VERIFICATION_BASIS_REQUIRED);
        Map<String, Object> content = versionContent(branchId, expectedVersion, "verify");
        content.put("verification_basis", basis);
        content.put("license_no_expiry_confirmed", Boolean.TRUE.equals(noExpiryConfirmed));
        return mutate(actor, branchId, "branch-verify", key, content,
            () -> verifyInTx(actor, branchId, expectedVersion, basis, Boolean.TRUE.equals(noExpiryConfirmed)));
    }

    @Transactional
    public Map<String, Object> disable(
            SessionService.Principal actor, long branchId, int expectedVersion, String reason, String key) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty() || normalized.length() > 300) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String, Object> content = versionContent(branchId, expectedVersion, "disable");
        content.put("reason", normalized);
        return mutate(actor, branchId, "branch-disable", key, content,
            () -> disableInTx(actor, branchId, expectedVersion, normalized));
    }

    @Transactional
    public Map<String, Object> reopen(SessionService.Principal actor, long branchId, int expectedVersion, String key) {
        return mutate(actor, branchId, "branch-reopen", key, versionContent(branchId, expectedVersion, "reopen"),
            () -> reopenInTx(actor, branchId, expectedVersion));
    }

    private Map<String, Object> mutate(
            SessionService.Principal actor,
            long branchId,
            String operation,
            String key,
            Map<String, Object> content,
            Supplier<IdempotencyService.BusinessResult> business) {
        // 先鉴权，避免无权限员工先等待/持有目标门店行锁；锁后仍由幂等回调再次复查。
        requireAuthorized(actor, branchId);
        lockBranch(branchId);
        IdempotencyService.Outcome outcome = idempotency.execute(
            actor.accountId(), operation, key, IdempotencyService.canonicalize(content),
            () -> requireAuthorized(actor, branchId), business, id -> requireAuthorized(actor, id));
        return mutationResponse(outcome);
    }

    void lockBranch(long branchId) {
        if (branchId <= 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE", branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    void requireAuthorized(SessionService.Principal actor, long branchId) {
        requireBusinessStaff(actor);
        if (!canConfigure(actor, branchId)) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private Map<String, Object> mutationResponse(IdempotencyService.Outcome outcome) {
        int version;
        try {
            version = Integer.parseInt(outcome.resultRef());
        } catch (RuntimeException e) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branch_id", Long.toString(outcome.targetId()));
        data.put("state", outcome.resultCode());
        data.put("version", version);
        data.put("reauthentication_required", false);
        return data;
    }

    private IdempotencyService.BusinessResult patchInTx(SessionService.Principal actor, long branchId, PatchInput input) {
        requireAuthorized(actor, branchId);
        Map<String, Object> row = branchRowForUpdate(branchId);
        int currentVersion = ((Number) row.get("version")).intValue();
        if (input.expectedVersion() != currentVersion) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        String state = (String) row.get("state");

        String code = input.fields().containsKey("code") ? (String) input.fields().get("code") : (String) row.get("code");
        String name = input.fields().containsKey("name") ? (String) input.fields().get("name") : (String) row.get("name");
        String address = input.fields().containsKey("address") ? (String) input.fields().get("address") : (String) row.get("address");
        String phone = input.fields().containsKey("phone") ? (String) input.fields().get("phone") : (String) row.get("phone");
        String licenseNo = input.fields().containsKey("license_no") ? (String) input.fields().get("license_no") : (String) row.get("license_no");
        String openingHours = input.fields().containsKey("opening_hours") ? (String) input.fields().get("opening_hours") : (String) row.get("opening_hours");
        String currentValidity = row.get("license_valid_to") == null ? null : row.get("license_valid_to").toString();
        String licenseValidTo = input.fields().containsKey("license_valid_to")
            ? (String) input.fields().get("license_valid_to") : currentValidity;

        boolean factsChanged =
            !java.util.Objects.equals(address, row.get("address"))
            || !java.util.Objects.equals(licenseNo, row.get("license_no"))
            || !java.util.Objects.equals(licenseValidTo, currentValidity);
        if ("营业".equals(state) && factsChanged) throw new ApiException(ApiErrorCode.BRANCH_FIELD_NOT_EDITABLE);

        boolean changed =
            !java.util.Objects.equals(code, row.get("code"))
            || !java.util.Objects.equals(name, row.get("name"))
            || !java.util.Objects.equals(address, row.get("address"))
            || !java.util.Objects.equals(phone, row.get("phone"))
            || !java.util.Objects.equals(licenseNo, row.get("license_no"))
            || !java.util.Objects.equals(licenseValidTo, currentValidity)
            || !java.util.Objects.equals(openingHours, row.get("opening_hours"));
        if (!changed) return businessResult(branchId, state, currentVersion);

        boolean clearVerification = factsChanged;
        boolean flag = clearVerification ? false : Boolean.TRUE.equals(row.get("license_no_expiry_confirmed"));
        Long verifiedBy = clearVerification ? null : (row.get("verified_by") == null ? null : ((Number) row.get("verified_by")).longValue());
        Timestamp verifiedAt = clearVerification ? null : (Timestamp) row.get("verified_at");

        try {
            jdbc.update("""
                UPDATE branch SET code=?,name=?,address=?,phone=?,license_no=?,license_valid_to=?,
                    opening_hours=?,license_no_expiry_confirmed=?,verified_by=?,verified_at=?,version=version+1
                WHERE branch_id=?
                """, code, name, address, phone, licenseNo,
                licenseValidTo == null ? null : java.sql.Date.valueOf(licenseValidTo),
                openingHours, flag, verifiedBy, verifiedAt, branchId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiErrorCode.BRANCH_CODE_EXISTS);
        }

        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        before.put("state_from", state);
        before.put("state_to", state);
        after.put("state_from", state);
        after.put("state_to", state);
        putAudit(before, "code", (String) row.get("code"));
        putAudit(after, "code", code);
        putAudit(before, "name", (String) row.get("name"));
        putAudit(after, "name", name);
        putAudit(before, "address", (String) row.get("address"));
        putAudit(after, "address", address);
        putAudit(before, "phone", (String) row.get("phone"));
        putAudit(after, "phone", phone);
        putAudit(before, "license_no", (String) row.get("license_no"));
        putAudit(after, "license_no", licenseNo);
        putAudit(before, "license_valid_to", currentValidity);
        putAudit(after, "license_valid_to", licenseValidTo);
        putAudit(before, "opening_hours", (String) row.get("opening_hours"));
        putAudit(after, "opening_hours", openingHours);
        putAudit(before, "license_no_expiry_confirmed", Boolean.TRUE.equals(row.get("license_no_expiry_confirmed")) ? "true" : "false");
        putAudit(after, "license_no_expiry_confirmed", flag ? "true" : "false");
        putAudit(before, "verified_by", row.get("verified_by") == null ? null : row.get("verified_by").toString());
        putAudit(after, "verified_by", verifiedBy == null ? null : verifiedBy.toString());
        putAudit(before, "verified_at", row.get("verified_at") == null ? null : row.get("verified_at").toString());
        putAudit(after, "verified_at", verifiedAt == null ? null : verifiedAt.toString());
        audit.user(actor.accountId(), "branch", branchId, "修改门店档案", before, after, "修改门店档案");
        return businessResult(branchId, state, currentVersion + 1);
    }

    private IdempotencyService.BusinessResult transition(
            SessionService.Principal actor,
            long branchId,
            int expectedVersion,
            String expectedState,
            String nextState,
            String operation,
            String auditReason) {
        requireAuthorized(actor, branchId);
        Map<String, Object> row = branchRowForUpdate(branchId);
        if (expectedVersion != ((Number) row.get("version")).intValue()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (!expectedState.equals(row.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("UPDATE branch SET state=?,version=version+1 WHERE branch_id=?", nextState, branchId);
        audit.user(actor.accountId(), "branch", branchId, operation,
            Map.of("state_from", expectedState), Map.of("state_to", nextState), auditReason);
        return businessResult(branchId, nextState, ((Number) row.get("version")).intValue() + 1);
    }

    private IdempotencyService.BusinessResult verifyInTx(
            SessionService.Principal actor, long branchId, int expectedVersion, String basis, boolean noExpiryConfirmed) {
        requireAuthorized(actor, branchId);
        Map<String, Object> row = branchRowForUpdate(branchId);
        if (expectedVersion != ((Number) row.get("version")).intValue()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (!"待核验".equals(row.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        LocalDate validity = row.get("license_valid_to") == null ? null : ((java.sql.Date) row.get("license_valid_to")).toLocalDate();
        boolean flag;
        if (validity == null) {
            if (!noExpiryConfirmed) throw new ApiException(ApiErrorCode.BRANCH_LICENSE_INVALID);
            flag = true;
        } else {
            if (!licenseDateUsable(validity, noExpiryConfirmed, clock)) throw new ApiException(ApiErrorCode.BRANCH_LICENSE_INVALID);
            flag = false;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
            UPDATE branch SET state='营业',verified_by=?,verified_at=?,license_no_expiry_confirmed=?,version=version+1
            WHERE branch_id=?
            """, actor.staffId(), now, flag, branchId);
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        putAudit(before, "state_from", "待核验");
        putAudit(after, "state_to", "营业");
        putAudit(before, "license_no", (String) row.get("license_no"));
        putAudit(after, "license_no", (String) row.get("license_no"));
        String currentValidity = row.get("license_valid_to") == null ? null : row.get("license_valid_to").toString();
        putAudit(before, "license_valid_to", currentValidity);
        putAudit(after, "license_valid_to", currentValidity);
        putAudit(before, "license_no_expiry_confirmed", Boolean.TRUE.equals(row.get("license_no_expiry_confirmed")) ? "true" : "false");
        putAudit(after, "license_no_expiry_confirmed", flag ? "true" : "false");
        putAudit(before, "verified_by", "");
        putAudit(after, "verified_by", actor.staffId().toString());
        putAudit(before, "verified_at", "");
        putAudit(after, "verified_at", now.toString());
        audit.user(actor.accountId(), "branch", branchId, "核验门店", before, after, basis);
        return businessResult(branchId, "营业", ((Number) row.get("version")).intValue() + 1);
    }

    private IdempotencyService.BusinessResult disableInTx(
            SessionService.Principal actor, long branchId, int expectedVersion, String reason) {
        requireAuthorized(actor, branchId);
        Map<String, Object> row = branchRowForUpdate(branchId);
        if (expectedVersion != ((Number) row.get("version")).intValue()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (!"营业".equals(row.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        enforceNoFutureCommitments(branchId);
        jdbc.update("UPDATE branch SET state='停用',version=version+1 WHERE branch_id=?", branchId);
        audit.user(actor.accountId(), "branch", branchId, "停用门店",
            Map.of("state_from", "营业"), Map.of("state_to", "停用"), reason);
        return businessResult(branchId, "停用", ((Number) row.get("version")).intValue() + 1);
    }

    private IdempotencyService.BusinessResult reopenInTx(SessionService.Principal actor, long branchId, int expectedVersion) {
        requireAuthorized(actor, branchId);
        Map<String, Object> row = branchRowForUpdate(branchId);
        if (expectedVersion != ((Number) row.get("version")).intValue()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if (!"停用".equals(row.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        jdbc.update("""
            UPDATE branch SET state='待核验',verified_by=NULL,verified_at=NULL,
                license_no_expiry_confirmed=false,version=version+1 WHERE branch_id=?
            """, branchId);
        audit.user(actor.accountId(), "branch", branchId, "重新打开门店",
            Map.of("state_from", "停用"), Map.of("state_to", "待核验"), "重新打开后需重新核验");
        return businessResult(branchId, "待核验", ((Number) row.get("version")).intValue() + 1);
    }

    private void enforceNoFutureCommitments(long branchId) {
        Timestamp now = Timestamp.from(clock.instant());
        Map<String, Object> schedules = jdbc.queryForMap("""
            SELECT count(*) AS c,min(starts_at) AS f,max(ends_at) AS l
            FROM schedule WHERE branch_id=? AND state='已发布' AND ends_at>?
            """, branchId, now);
        Map<String, Object> appointments = jdbc.queryForMap("""
            SELECT count(*) AS c,min(starts_at) AS f,max(ends_at) AS l
            FROM appointment
            WHERE branch_id=? AND state IN ('待确认','已确认','候诊中','接诊中')
            """, branchId);
        long scheduleCount = ((Number) schedules.get("c")).longValue();
        long appointmentCount = ((Number) appointments.get("c")).longValue();
        if (scheduleCount == 0 && appointmentCount == 0) return;
        Timestamp first = earlier((Timestamp) schedules.get("f"), (Timestamp) appointments.get("f"));
        Timestamp last = later((Timestamp) schedules.get("l"), (Timestamp) appointments.get("l"));
        throw new ApiImpactException(ApiErrorCode.BRANCH_HAS_FUTURE_COMMITMENTS,
            new ApiImpactException.ApiImpact(scheduleCount, appointmentCount,
                first == null ? null : ISO_INSTANT.format(first.toInstant()),
                last == null ? null : ISO_INSTANT.format(last.toInstant())));
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

    private Map<String, Object> branchRowForUpdate(long branchId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT branch_id,code,name,address,phone,license_no,license_valid_to,
                   license_no_expiry_confirmed,opening_hours,state,verified_by,verified_at,version
            FROM branch WHERE branch_id=? FOR UPDATE
            """, branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return rows.getFirst();
    }

    IdempotencyService.BusinessResult businessResult(long branchId, String state, int version) {
        return new IdempotencyService.BusinessResult(branchId, state, Integer.toString(version));
    }

    private Map<String, Object> versionContent(long branchId, int expectedVersion, String action) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("branch_id", Long.toString(branchId));
        values.put("action", action);
        values.put("expected_version", expectedVersion);
        return values;
    }

    private Map<String, Object> patchContent(long branchId, PatchInput input) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("branch_id", Long.toString(branchId));
        values.put("expected_version", input.expectedVersion());
        values.put("fields", input.fields());
        return values;
    }

    private PatchInput parsePatch(Map<String, Object> body) {
        if (body == null || body.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Set<String> allowed = Set.of("expected_version", "code", "name", "address", "phone",
            "license_no", "license_valid_to", "opening_hours");
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) throw new ApiException(ApiErrorCode.UNKNOWN_FIELD);
        }
        int version = parseVersion(body.get("expected_version"));
        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : List.of("code", "name", "address", "phone", "license_no", "opening_hours")) {
            if (!body.containsKey(field)) continue;
            Object raw = body.get(field);
            if (!(raw instanceof String s) || s.trim().isEmpty() || s.trim().length() > maxLength(field)) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
            fields.put(field, s.trim());
        }
        if (body.containsKey("license_valid_to")) {
            Object raw = body.get("license_valid_to");
            if (raw == null) {
                fields.put("license_valid_to", null);
            } else if (raw instanceof String s && !s.trim().isEmpty()) {
                try {
                    fields.put("license_valid_to", LocalDate.parse(s.trim()).toString());
                } catch (RuntimeException e) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                }
            } else {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
        }
        if (fields.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return new PatchInput(version, fields);
    }

    private int parseVersion(Object value) {
        if (!(value instanceof Number n)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long v = n.longValue();
        if (v < 1 || v > Integer.MAX_VALUE || n.doubleValue() != v) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return (int) v;
    }

    private int maxLength(String field) {
        return switch (field) {
            case "code" -> 30;
            case "name" -> 100;
            case "address" -> 300;
            case "phone" -> 30;
            case "license_no" -> 80;
            case "opening_hours" -> 200;
            default -> 200;
        };
    }

    static LocalDate shanghaiDate(Clock clock) {
        return clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    static boolean licenseDateUsable(LocalDate validity, boolean noExpiryConfirmed, Clock clock) {
        if (validity == null) return noExpiryConfirmed;
        return !noExpiryConfirmed && !validity.isBefore(shanghaiDate(clock));
    }

    private void putAudit(Map<String, String> target, String key, String value) {
        // 空字符串明确表示“已清空/无值”，不把 null 当成未记录；长度由 AuditService 按字段限额复核。
        target.put(key, value == null ? "" : value);
    }

    private record PatchInput(int expectedVersion, Map<String, Object> fields) {}

    private IdempotencyService.BusinessResult createInTx(SessionService.Principal actor, Validated create) {
        // 取得幂等锁后再锁经营主体和账号，并在锁内重新鉴权。
        List<Map<String, Object>> organizations = jdbc.queryForList(
            "SELECT organization_id,responsible_staff_id,state FROM organization ORDER BY organization_id FOR UPDATE");
        requireSingleOrganization(organizations);
        jdbc.queryForList("SELECT account_id FROM account WHERE account_id=? FOR UPDATE", actor.accountId());
        requireCreator(actor, create.managerId());

        Long branchId = jdbc.queryForObject("""
            INSERT INTO branch(organization_id,code,name,address,phone,license_no,license_valid_to,
                               opening_hours,manager_id,state,version)
            VALUES(?,?,?,?,?,?,?,?,?,'草稿',1) RETURNING branch_id
            """, Long.class,
            ((Number) organizations.getFirst().get("organization_id")).longValue(),
            create.code(), create.name(), create.address(), create.phone(), create.licenseNo(),
            create.licenseValidTo() == null ? null : java.sql.Date.valueOf(create.licenseValidTo()),
            create.openingHours(), create.managerId());

        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(?,?,true,?)",
            create.managerId(), branchId, Timestamp.from(clock.instant()));

        int scopes = jdbc.update("""
            INSERT INTO role_branch_scope(account_id,role_id,branch_id)
            SELECT ar.account_id,ar.role_id,?
            FROM account_role ar
            JOIN role r ON r.role_id=ar.role_id
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='branches.create'
            WHERE ar.account_id=? AND r.code='owner'
            """, branchId, actor.accountId());
        if (scopes != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);

        audit.user(actor.accountId(), "branch", branchId, "创建门店", null,
            Map.of("state_to", "草稿"), "组织负责人创建门店，建立 owner 本店启动授权");

        // 授权范围变化按既有规则递增 auth_version 并撤销全部会话；响应要求客户端重新登录。
        jdbc.update("UPDATE account SET auth_version=auth_version+1 WHERE account_id=?", actor.accountId());
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",
            Timestamp.from(clock.instant()), actor.accountId());
        return new IdempotencyService.BusinessResult(branchId, "created", null);
    }

    private void requireVisible(SessionService.Principal actor, long branchId) {
        if (!canConfigure(actor, branchId)) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private Map<String, Object> detailInternal(long branchId, boolean reauthenticationRequired) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT branch_id,code,name,address,phone,license_no,license_valid_to,
                   license_no_expiry_confirmed,opening_hours,manager_id,state,verified_by,verified_at,version
            FROM branch WHERE branch_id=?
            """, branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("branch_id", Long.toString(((Number) row.get("branch_id")).longValue()));
        data.put("code", row.get("code"));
        data.put("name", row.get("name"));
        data.put("address", row.get("address"));
        data.put("phone", row.get("phone"));
        data.put("license_no", row.get("license_no"));
        data.put("license_valid_to", row.get("license_valid_to") == null ? null : row.get("license_valid_to").toString());
        data.put("license_no_expiry_confirmed", Boolean.TRUE.equals(row.get("license_no_expiry_confirmed")));
        data.put("opening_hours", row.get("opening_hours"));
        data.put("manager_id", Long.toString(((Number) row.get("manager_id")).longValue()));
        data.put("state", row.get("state"));
        data.put("verified_by", row.get("verified_by") == null ? null : Long.toString(((Number) row.get("verified_by")).longValue()));
        data.put("verified_at", row.get("verified_at") == null ? null
            : ISO_INSTANT.format(((Timestamp) row.get("verified_at")).toInstant()));
        data.put("version", ((Number) row.get("version")).intValue());
        data.put("reauthentication_required", reauthenticationRequired);
        return data;
    }

    private boolean canConfigure(SessionService.Principal actor, long branchId) {
        if (actor.staffId() == null) return false;
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='branch.configure'
            JOIN role_branch_scope bs ON bs.account_id=ar.account_id AND bs.role_id=ar.role_id AND bs.branch_id=?
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE ar.account_id=?
            """, Integer.class, branchId, actor.staffId(), actor.accountId());
        return count != null && count > 0;
    }

    private void requireBusinessStaff(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword()
                || actor.staffId() == null) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
    }

    private void requireConfigurePermission(SessionService.Principal actor) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='branch.configure'
            WHERE ar.account_id=?
            """, Integer.class, actor.accountId());
        if (count == null || count == 0) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private void requireCreator(SessionService.Principal actor, long managerId) {
        requireBusinessStaff(actor);
        if (managerId != actor.staffId()) throw new ApiException(ApiErrorCode.BRANCH_MANAGER_INVALID);
        List<Map<String, Object>> organizations = jdbc.queryForList(
            "SELECT organization_id,responsible_staff_id,state FROM organization ORDER BY organization_id");
        Map<String, Object> organization = requireSingleOrganization(organizations);
        if ("停用".equals(organization.get("state"))) {
            throw new ApiException(ApiErrorCode.BRANCH_ORGANIZATION_DISABLED);
        }
        if (((Number) organization.get("responsible_staff_id")).longValue() != actor.staffId()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
        Integer allowed = jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role r ON r.role_id=ar.role_id AND r.code='owner'
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='branches.create'
            WHERE ar.account_id=?
            """, Integer.class, actor.accountId());
        if (allowed == null || allowed != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private Map<String, Object> requireSingleOrganization(List<Map<String, Object>> organizations) {
        if (organizations.isEmpty()) throw new ApiException(ApiErrorCode.BRANCH_ORGANIZATION_NOT_CONFIGURED);
        if (organizations.size() > 1) throw new ApiException(ApiErrorCode.BRANCH_ORGANIZATION_AMBIGUOUS);
        return organizations.getFirst();
    }

    private Validated validate(CreateCommand command, SessionService.Principal actor) {
        if (command == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String code = text(command.code(), 30);
        String name = text(command.name(), 100);
        String address = text(command.address(), 300);
        String phone = text(command.phone(), 30);
        String licenseNo = text(command.licenseNo(), 80);
        String openingHours = text(command.openingHours(), 200);
        if (command.managerId() == null || !command.managerId().matches("[1-9]\\d*")) {
            throw new ApiException(ApiErrorCode.BRANCH_MANAGER_INVALID);
        }
        long managerId;
        try {
            managerId = Long.parseLong(command.managerId());
        } catch (NumberFormatException e) {
            throw new ApiException(ApiErrorCode.BRANCH_MANAGER_INVALID);
        }
        if (actor.staffId() == null || managerId != actor.staffId()) {
            throw new ApiException(ApiErrorCode.BRANCH_MANAGER_INVALID);
        }
        LocalDate licenseValidTo = null;
        if (command.licenseValidTo() != null && !command.licenseValidTo().isBlank()) {
            try {
                licenseValidTo = LocalDate.parse(command.licenseValidTo().trim());
            } catch (RuntimeException e) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
        }
        return new Validated(code, name, address, phone, licenseNo, licenseValidTo, openingHours, managerId);
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        return value.trim();
    }

    private String requestContent(Validated c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("code", c.code());
        values.put("name", c.name());
        values.put("address", c.address());
        values.put("phone", c.phone());
        values.put("license_no", c.licenseNo());
        values.put("license_valid_to", c.licenseValidTo() == null ? null : c.licenseValidTo().toString());
        values.put("opening_hours", c.openingHours());
        values.put("manager_id", Long.toString(c.managerId()));
        return IdempotencyService.canonicalize(values);
    }

    private record Validated(
            String code,
            String name,
            String address,
            String phone,
            String licenseNo,
            LocalDate licenseValidTo,
            String openingHours,
            long managerId) {}
}
