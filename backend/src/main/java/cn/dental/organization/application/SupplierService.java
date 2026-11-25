package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization-wide supplier master data. Purchase and stock movements are separate workflows. */
@Service
public class SupplierService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public SupplierService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.audit = audit;
        this.clock = clock;
    }

    public record Create(String name, String creditCode, String qualificationRef, String validTo) {
        @Override public String toString() { return "SupplierCreate[redacted]"; }
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(SessionService.Principal actor, String cursor, Integer requestedLimit) {
        requireManager(actor);
        int limit = PageResult.validateLimit(requestedLimit);
        long after = 0;
        if (cursor != null) {
            try { after = Long.parseLong(cursor); if (after <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException ex) { throw new ApiException(ApiErrorCode.INVALID_REQUEST); }
        }
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT supplier_id,name,credit_code,valid_to,enabled,verified_at
            FROM supplier WHERE supplier_id>? ORDER BY supplier_id LIMIT ?
            """, (rs, n) -> {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("supplier_id", Long.toString(rs.getLong("supplier_id")));
                item.put("name", rs.getString("name"));
                item.put("credit_code", rs.getString("credit_code"));
                Date validTo = rs.getDate("valid_to");
                item.put("valid_to", validTo == null ? null : validTo.toLocalDate().toString());
                item.put("enabled", rs.getBoolean("enabled"));
                item.put("verified_at", rs.getTimestamp("verified_at").toInstant().toString());
                return item;
            }, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        return new PageResult<>(page, more ? (String) page.getLast().get("supplier_id") : null);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(SessionService.Principal actor, long supplierId) {
        requireManager(actor);
        return detailInternal(supplierId);
    }

    @Transactional
    public Map<String, Object> create(SessionService.Principal actor, Create command, String key) {
        requireManager(actor);
        String name = text(command.name(), 120);
        String code = text(command.creditCode(), 30);
        String qualification = text(command.qualificationRef(), 300);
        LocalDate validTo;
        try { validTo = LocalDate.parse(command.validTo()); }
        catch (DateTimeParseException | NullPointerException ex) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        // Expiry is a first-execution rule. A completed idempotent replay must remain
        // recoverable even when the supplier's qualification expires the next day.
        String content = IdempotencyService.canonicalize(Map.of(
            "name", name, "credit_code", code, "qualification_ref", qualification, "valid_to", validTo.toString()));
        try {
            var outcome = idempotency.execute(actor.accountId(), "supplier-create", key, content,
                () -> requireManager(actor), () -> {
                    requireManager(actor);
                    if (validTo.isBefore(LocalDate.now(clock.withZone(BUSINESS_ZONE))))
                        throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                    Long id = jdbc.queryForObject("""
                        INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled)
                        VALUES(?,?,?,?,?,?,true) RETURNING supplier_id
                        """, Long.class, name, code, qualification, Date.valueOf(validTo), actor.staffId(),
                        Timestamp.from(clock.instant()));
                    audit.user(actor.accountId(), "supplier", id, "create", null,
                        Map.of("code", code, "enabled", "true"), "人工核验供应商资质后建档");
                    return new IdempotencyService.BusinessResult(id, "created", null);
                }, id -> detailInternal(id));
            requireManager(actor);
            return detailInternal(outcome.targetId());
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ApiErrorCode.SUPPLIER_CODE_EXISTS);
        }
    }

    private Map<String,Object> detailInternal(long id) {
        if (id <= 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT supplier_id,name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled
            FROM supplier WHERE supplier_id=?
            """, id);
        if (rows.size() != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String,Object> row = rows.getFirst();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("supplier_id", String.valueOf(row.get("supplier_id")));
        result.put("name", row.get("name"));
        result.put("credit_code", row.get("credit_code"));
        result.put("qualification_ref", row.get("qualification_ref"));
        result.put("valid_to", row.get("valid_to") == null ? null : row.get("valid_to").toString());
        result.put("verified_by", String.valueOf(row.get("verified_by")));
        result.put("verified_at", ((Timestamp) row.get("verified_at")).toInstant().toString());
        result.put("enabled", row.get("enabled"));
        return result;
    }

    private void requireManager(SessionService.Principal actor) {
        if (actor == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        authorization.requireCurrent(actor);
        if (!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword() || actor.staffId() == null)
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        Integer organizations = jdbc.queryForObject("SELECT count(*) FROM organization", Integer.class);
        if (organizations == null || organizations != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
        Integer allowed = jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role r ON r.role_id=ar.role_id AND r.code IN ('inventory','owner')
            JOIN role_permission rp ON rp.role_id=r.role_id
            JOIN permission p ON p.permission_id=rp.permission_id AND p.code='suppliers.manage'
            JOIN role_branch_scope scope ON scope.account_id=ar.account_id AND scope.role_id=ar.role_id
            JOIN staff_branch sb ON sb.branch_id=scope.branch_id AND sb.staff_id=?
                AND sb.enabled=true AND sb.verified_at IS NOT NULL
            JOIN branch b ON b.branch_id=scope.branch_id AND b.state='营业' AND b.verified_at IS NOT NULL
            JOIN organization o ON o.organization_id=b.organization_id AND o.state='营业'
            WHERE ar.account_id=?
            """, Integer.class, actor.staffId(), actor.accountId());
        if (allowed == null || allowed == 0) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String clean = value.trim();
        if (clean.length() > max || clean.indexOf('\0') >= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return clean;
    }
}
