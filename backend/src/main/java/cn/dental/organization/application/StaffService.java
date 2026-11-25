package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * T025-A: staff list, global create and detail.
 *
 * Global staff maintenance is deliberately separate from account/role administration:
 * create never creates an account, role or staff_branch. Branch users can only read staff
 * already related to one of their authorized branches.
 */
@Service
public class StaffService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ISO_INSTANT = DateTimeFormatter.ISO_INSTANT;
    private static final Set<String> STAFF_TYPES = Set.of("牙医", "其他人员");

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public StaffService(
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

    public record CredentialCommand(
            String credentialType,
            String certificateNo,
            String validFrom,
            String validTo,
            Boolean noExpiryConfirmed) {
        @Override
        public String toString() {
            return "CredentialCommand[redacted]";
        }
    }

    public record CreateCommand(
            String name,
            String staffType,
            String jobTitle,
            String practiceNo,
            CredentialCommand credential) {
        @Override
        public String toString() {
            return "CreateCommand[redacted]";
        }
    }

    public record ReviewCommand(String decision, String basis, int expectedVersion) {
        @Override
        public String toString() {
            return "ReviewCommand[redacted]";
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> list(
            SessionService.Principal actor, String cursor, Integer requestedLimit) {
        requireBusinessStaff(actor);
        int limit = PageResult.validateLimit(requestedLimit);
        long after = parseCursor(cursor);

        boolean global = authorization.isOrganizationOwner(actor);
        List<Long> branchIds = global ? List.of() : authorizedBranches(actor);
        if (!global && branchIds.isEmpty()) throw new ApiException(ApiErrorCode.FORBIDDEN);

        List<Map<String, Object>> rows;
        if (global) {
            rows = jdbc.query("""
                SELECT staff_id,name,staff_type,job_title,employment_state,version
                FROM staff WHERE staff_id>? ORDER BY staff_id LIMIT ?
                """, (r, n) -> staffListRow(r), after, limit + 1);
        } else {
            String placeholders = String.join(",", Collections.nCopies(branchIds.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(after);
            args.addAll(branchIds);
            args.add(limit + 1);
            rows = jdbc.query("""
                SELECT s.staff_id,s.name,s.staff_type,s.job_title,s.employment_state,s.version
                FROM staff s
                WHERE s.staff_id>? AND EXISTS (
                    SELECT 1 FROM staff_branch sb
                    WHERE sb.staff_id=s.staff_id AND sb.branch_id IN (%s)
                )
                ORDER BY s.staff_id LIMIT ?
                """.formatted(placeholders), (r, n) -> staffListRow(r), args.toArray());
        }

        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        return new PageResult<>(page, more ? (String) page.getLast().get("staff_id") : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(SessionService.Principal actor, long staffId) {
        requireBusinessStaff(actor);
        boolean global = authorization.isOrganizationOwner(actor);
        List<Long> branches = global ? List.of() : authorizedBranches(actor);
        if (!global && branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return detailInternal(staffId, global, branches);
    }

    /**
     * Create a global staff master and, optionally, one pending credential.
     * The idempotency key is required; the organization row is locked before any insert.
     * No account, role, scope or staff_branch is created here.
     */
    @Transactional
    public Map<String, Object> create(
            SessionService.Principal actor, CreateCommand command, String idempotencyKey) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        ValidatedCreate input = validate(command);
        String content = requestContent(input);

        IdempotencyService.Outcome outcome = idempotency.execute(
            actor.accountId(),
            "staff-create",
            idempotencyKey,
            content,
            () -> {
                requireBusinessStaff(actor);
                authorization.requireOrganizationOwner(actor);
            },
            () -> createInTx(actor, input),
            staffId -> {
                authorization.requireOrganizationOwner(actor);
                requireStaffExists(staffId);
            });

        return detailInternal(outcome.targetId(), true, List.of());
    }

    /** Create one pending credential for an existing staff member; only the global organization owner. */
    @Transactional
    public Map<String, Object> addCredential(
            SessionService.Principal actor, long staffId, CredentialCommand command, String idempotencyKey) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        CredentialCommand input = validateCredential(command);
        String content = credentialContent(staffId, null, "create", input, null, null);
        IdempotencyService.Outcome outcome = idempotency.execute(
            actor.accountId(), "credential-create", idempotencyKey, content,
            () -> {
                requireBusinessStaff(actor);
                authorization.requireOrganizationOwner(actor);
                requireStaffExists(staffId);
            },
            () -> addCredentialInTx(actor, staffId, input),
            credentialId -> {
                authorization.requireOrganizationOwner(actor);
                requireCredentialOwned(staffId, credentialId);
            });
        return credentialView(staffId, outcome.targetId());
    }

    /** First review only: pending -> verified or rejected. Revocation/re-review is deliberately absent. */
    @Transactional
    public Map<String, Object> reviewCredential(
            SessionService.Principal actor, long staffId, long credentialId, ReviewCommand command,
            String idempotencyKey) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        String decision = command == null ? null : command.decision();
        if (!"verified".equals(decision) && !"rejected".equals(decision)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        String basis = command == null ? null : command.basis();
        if (basis == null || basis.isBlank() || basis.trim().length() > 300 || command.expectedVersion() < 1) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        String normalizedBasis = basis.trim();
        String content = credentialContent(staffId, credentialId, "review", null, decision,
            normalizedBasis + "|" + command.expectedVersion());
        IdempotencyService.Outcome outcome = idempotency.execute(
            actor.accountId(), "credential-review", idempotencyKey, content,
            () -> {
                requireBusinessStaff(actor);
                authorization.requireOrganizationOwner(actor);
                requireCredentialOwned(staffId, credentialId);
            },
            () -> reviewInTx(actor, staffId, credentialId, decision, normalizedBasis,
                command.expectedVersion(), false),
            targetId -> {
                authorization.requireOrganizationOwner(actor);
                requireCredentialOwned(staffId, targetId);
            });
        return credentialView(staffId, outcome.targetId());
    }

    @Transactional
    public Map<String, Object> revokeCredential(
            SessionService.Principal actor, long staffId, long credentialId, ReviewCommand command,
            String idempotencyKey) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        String decision = "rejected";
        String basis = command == null ? null : command.basis();
        if (basis == null || basis.isBlank() || basis.trim().length() > 300 || command.expectedVersion() < 1) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        String normalizedBasis = basis.trim();
        String content = credentialContent(staffId, credentialId, "revoke", null, decision,
            normalizedBasis + "|" + command.expectedVersion());
        IdempotencyService.Outcome outcome = idempotency.execute(
            actor.accountId(), "credential-revoke", idempotencyKey, content,
            () -> {
                requireBusinessStaff(actor);
                authorization.requireOrganizationOwner(actor);
                requireCredentialOwned(staffId, credentialId);
            },
            () -> reviewInTx(actor, staffId, credentialId, decision, normalizedBasis,
                command.expectedVersion(), true),
            targetId -> {
                authorization.requireOrganizationOwner(actor);
                requireCredentialOwned(staffId, targetId);
            });
        return credentialView(staffId, outcome.targetId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> credentialHistory(
            SessionService.Principal actor, long staffId, long credentialId, String cursor,
            Integer requestedLimit) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        requireCredentialOwned(staffId, credentialId);
        int limit = PageResult.validateLimit(requestedLimit);
        long after = parseCursor(cursor);
        List<Map<String, Object>> rows = jdbc.query("""
            SELECT review_id,credential_id,action,from_state,to_state,actor_staff_id,basis,
                   occurred_at,resulting_version
            FROM credential_review
            WHERE credential_id=? AND review_id>?
            ORDER BY review_id LIMIT ?
            """, (r, n) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("review_id", Long.toString(r.getLong("review_id")));
                item.put("credential_id", Long.toString(r.getLong("credential_id")));
                item.put("action", r.getString("action"));
                item.put("from_state", r.getString("from_state"));
                item.put("to_state", r.getString("to_state"));
                item.put("actor_staff_id", Long.toString(r.getLong("actor_staff_id")));
                item.put("basis", r.getString("basis"));
                item.put("occurred_at", ISO_INSTANT.format(r.getTimestamp("occurred_at").toInstant()));
                item.put("resulting_version", r.getInt("resulting_version"));
                return item;
            }, credentialId, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> page = new ArrayList<>(rows.subList(0, Math.min(limit, rows.size())));
        return new PageResult<>(page, more ? (String) page.getLast().get("review_id") : null);
    }

    /** Sensitive credential facts are disclosed only on an explicit reviewer request. */
    @Transactional
    public Map<String, Object> credentialFacts(
            SessionService.Principal actor, long staffId, long credentialId) {
        requireBusinessStaff(actor);
        authorization.requireOrganizationOwner(actor);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT credential_id,staff_id,credential_type,certificate_no,valid_from,valid_to,
                   no_expiry_confirmed,verification_state,version
            FROM credential WHERE credential_id=? AND staff_id=?
            """, credentialId, staffId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        // Recheck after the database read; a revoked session must not receive the facts.
        authorization.requireOrganizationOwner(actor);
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("credential_id", Long.toString(credentialId));
        data.put("staff_id", Long.toString(staffId));
        data.put("credential_type", row.get("credential_type"));
        data.put("certificate_no", row.get("certificate_no"));
        data.put("valid_from", row.get("valid_from") == null ? null
            : ((java.sql.Date) row.get("valid_from")).toLocalDate().toString());
        data.put("valid_to", row.get("valid_to") == null ? null
            : ((java.sql.Date) row.get("valid_to")).toLocalDate().toString());
        data.put("no_expiry_confirmed", row.get("no_expiry_confirmed"));
        data.put("verification_state", row.get("verification_state"));
        data.put("version", ((Number) row.get("version")).intValue());
        audit.user(actor.accountId(), "credential", credentialId, "查看资格证件事实", null,
            Map.of("credential_version", data.get("version").toString()), "资格审核核对");
        return data;
    }

    private IdempotencyService.BusinessResult addCredentialInTx(
            SessionService.Principal actor, long staffId, CredentialCommand command) {
        lockOrganizationAndStaff(actor, staffId);
        authorization.requireOrganizationOwner(actor);
        long credentialId = insertPendingCredential(actor, staffId, command);
        return new IdempotencyService.BusinessResult(credentialId, "pending", "1");
    }

    private long insertPendingCredential(
            SessionService.Principal actor, long staffId, CredentialCommand command) {
        long credentialId;
        try {
            credentialId = jdbc.queryForObject("""
                INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,
                                       verified_by,verified_at,verification_state,no_expiry_confirmed,version)
                VALUES(?,?,?,?,?,NULL,NULL,'pending',?,1) RETURNING credential_id
                """, Long.class, staffId, command.credentialType(), command.certificateNo(),
                command.validFrom() == null ? null : java.sql.Date.valueOf(command.validFrom()),
                command.validTo() == null ? null : java.sql.Date.valueOf(command.validTo()),
                command.noExpiryConfirmed());
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_EXISTS);
        }
        Timestamp now = Timestamp.from(clock.instant());
        long reviewId = insertReview(credentialId, "submit", null, "pending", actor.staffId(),
            null, now, 1);
        audit.user(actor.accountId(), "credential", credentialId, "录入待核验资格", null,
            Map.of("state_to", "pending", "credential_version", "1", "review_id", Long.toString(reviewId)),
            "录入资格待核验");
        return credentialId;
    }

    private IdempotencyService.BusinessResult reviewInTx(
            SessionService.Principal actor, long staffId, long credentialId, String decision,
            String basis, int expectedVersion, boolean revoke) {
        lockOrganizationAndStaff(actor, staffId);
        authorization.requireOrganizationOwner(actor);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT credential_id,credential_type,certificate_no,verification_state,valid_from,valid_to,
                   no_expiry_confirmed,version
            FROM credential WHERE credential_id=? AND staff_id=? FOR UPDATE
            """, credentialId, staffId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> row = rows.getFirst();
        // Recheck after the credential row lock; role/owner may have changed while waiting.
        authorization.requireOrganizationOwner(actor);
        int currentVersion = ((Number) row.get("version")).intValue();
        if (currentVersion != expectedVersion) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        String sourceState = revoke ? "verified" : "pending";
        if (!sourceState.equals(row.get("verification_state"))) {
            throw new ApiException(ApiErrorCode.CREDENTIAL_STATE_CONFLICT);
        }
        Instant reviewInstant = clock.instant();
        LocalDate today = LocalDate.ofInstant(reviewInstant, SHANGHAI);
        LocalDate from = row.get("valid_from") == null ? null
            : ((java.sql.Date) row.get("valid_from")).toLocalDate();
        LocalDate to = row.get("valid_to") == null ? null
            : ((java.sql.Date) row.get("valid_to")).toLocalDate();
        boolean noExpiry = Boolean.TRUE.equals(row.get("no_expiry_confirmed"));
        if ("verified".equals(decision)) {
            if (to == null && !noExpiry) throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_INVALID);
            if (noExpiry && to != null) throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_INVALID);
            if (!noExpiry && to.isBefore(today)) throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_EXPIRED);
            // Future valid_from may be verified for its authenticity; effective remains false until then.
        }
        Timestamp now = Timestamp.from(reviewInstant);
        int nextVersion = currentVersion + 1;
        if (revoke) {
            // Keep the original verification facts. Revoker/time/basis belong to history.
            jdbc.update("UPDATE credential SET verification_state='rejected',version=? WHERE credential_id=?",
                nextVersion, credentialId);
        } else {
            jdbc.update("UPDATE credential SET verification_state=?,verified_by=?,verified_at=?,version=? WHERE credential_id=?",
                decision, actor.staffId(), now, nextVersion, credentialId);
        }
        long reviewId = insertReview(credentialId, revoke ? "revoke" : ("verified".equals(decision) ? "verify" : "reject"),
            sourceState, decision, actor.staffId(), basis, now, nextVersion);
        Map<String, String> before = new LinkedHashMap<>();
        before.put("state_from", sourceState);
        before.put("credential_version", Integer.toString(currentVersion));
        Map<String, String> after = new LinkedHashMap<>();
        after.put("state_to", decision);
        after.put("credential_version", Integer.toString(nextVersion));
        after.put("review_id", Long.toString(reviewId));
        audit.user(actor.accountId(), "credential", credentialId,
            revoke ? "撤销资格" : ("verified".equals(decision) ? "核验资格" : "驳回资格"), before, after, revoke ? "撤销已核验资格" : "首次核验资格");
        return new IdempotencyService.BusinessResult(credentialId, decision, Integer.toString(nextVersion));
    }

    private long insertReview(
            long credentialId, String action, String fromState, String toState, long actorStaffId,
            String basis, Timestamp occurredAt, int resultingVersion) {
        return jdbc.queryForObject("""
            INSERT INTO credential_review(credential_id,action,from_state,to_state,actor_staff_id,basis,
                                          occurred_at,resulting_version)
            VALUES(?,?,?,?,?,?,?,?) RETURNING review_id
            """, Long.class, credentialId, action, fromState, toState, actorStaffId, basis,
            occurredAt, resultingVersion);
    }

    private void lockOrganizationAndStaff(SessionService.Principal actor, long staffId) {
        List<Map<String, Object>> organizations = jdbc.queryForList(
            "SELECT organization_id,responsible_staff_id,state FROM organization ORDER BY organization_id FOR UPDATE");
        if (organizations.isEmpty()) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_NOT_CONFIGURED);
        if (organizations.size() > 1) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_AMBIGUOUS);
        Map<String, Object> org = organizations.getFirst();
        if ("停用".equals(org.get("state"))) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_DISABLED);
        List<Map<String, Object>> staffRows = jdbc.queryForList(
            "SELECT staff_id FROM staff WHERE staff_id=? FOR UPDATE", staffId);
        if (staffRows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        authorization.requireOrganizationOwner(actor);
    }

    private Map<String, Object> credentialView(long staffId, long credentialId) {
        // One statement is one snapshot: current credential row and latest review reference cannot mix.
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT c.credential_id,c.staff_id,c.credential_type,c.verification_state,c.valid_from,c.valid_to,
                   c.no_expiry_confirmed,c.verified_at,c.version,
                   (SELECT coalesce(max(r.review_id),0) FROM credential_review r
                    WHERE r.credential_id=c.credential_id) AS latest_review_id
            FROM credential c WHERE c.credential_id=? AND c.staff_id=?
            """, credentialId, staffId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> row = rows.getFirst();
        LocalDate today = today();
        LocalDate from = row.get("valid_from") == null ? null
            : ((java.sql.Date) row.get("valid_from")).toLocalDate();
        LocalDate to = row.get("valid_to") == null ? null
            : ((java.sql.Date) row.get("valid_to")).toLocalDate();
        boolean noExpiry = Boolean.TRUE.equals(row.get("no_expiry_confirmed"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("credential_id", Long.toString(((Number) row.get("credential_id")).longValue()));
        data.put("staff_id", Long.toString(((Number) row.get("staff_id")).longValue()));
        data.put("credential_type", row.get("credential_type"));
        data.put("verification_state", row.get("verification_state"));
        data.put("valid_from", from == null ? null : from.toString());
        data.put("valid_to", to == null ? null : to.toString());
        data.put("no_expiry_confirmed", noExpiry);
        data.put("verified_at", row.get("verified_at") == null ? null
            : ISO_INSTANT.format(((Timestamp) row.get("verified_at")).toInstant()));
        data.put("version", ((Number) row.get("version")).intValue());
        data.put("effective", credentialEffective((String) row.get("verification_state"), from, to, noExpiry, today));
        data.put("latest_review_id", ((Number) row.get("latest_review_id")).toString());
        return data;
    }

    private void requireCredentialOwned(long staffId, long credentialId) {
        if (jdbc.queryForObject("""
            SELECT count(*) FROM credential WHERE credential_id=? AND staff_id=?
            """, Integer.class, credentialId, staffId) != 1) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    private String credentialContent(
            long staffId, Long credentialId, String action, CredentialCommand credential,
            String decision, String basisAndVersion) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("staff_id", Long.toString(staffId));
        values.put("credential_id", credentialId == null ? null : Long.toString(credentialId));
        values.put("action", action);
        if (credential != null) {
            values.put("credential_type", credential.credentialType());
            values.put("certificate_no", credential.certificateNo());
            values.put("valid_from", credential.validFrom());
            values.put("valid_to", credential.validTo());
            values.put("no_expiry_confirmed", credential.noExpiryConfirmed());
        }
        values.put("decision", decision);
        values.put("review", basisAndVersion);
        return IdempotencyService.canonicalize(values);
    }

    private Map<String, Object> detailInternal(long staffId, boolean global, List<Long> visibleBranches) {
        // One business date for every credential in this response, even across Shanghai midnight.
        LocalDate today = today();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT staff_id,name,staff_type,job_title,practice_no,employment_state,version
            FROM staff WHERE staff_id=?
            """, staffId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> row = rows.getFirst();

        List<Map<String, Object>> branches;
        if (global) {
            branches = jdbc.query("""
                SELECT sb.branch_id,b.name,b.state,sb.enabled,sb.verified_at
                FROM staff_branch sb JOIN branch b ON b.branch_id=sb.branch_id
                WHERE sb.staff_id=? ORDER BY sb.branch_id
                """, (r, n) -> branchRow(r), staffId);
        } else {
            String placeholders = String.join(",", Collections.nCopies(visibleBranches.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(staffId);
            args.addAll(visibleBranches);
            branches = jdbc.query("""
                SELECT sb.branch_id,b.name,b.state,sb.enabled,sb.verified_at
                FROM staff_branch sb JOIN branch b ON b.branch_id=sb.branch_id
                WHERE sb.staff_id=? AND sb.branch_id IN (%s) ORDER BY sb.branch_id
                """.formatted(placeholders), (r, n) -> branchRow(r), args.toArray());
            if (branches.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        }

        List<Map<String, Object>> credentials = jdbc.query("""
            SELECT credential_id,credential_type,verification_state,valid_from,valid_to,
                   no_expiry_confirmed,verified_at,version
            FROM credential WHERE staff_id=? ORDER BY credential_id
            """, (r, n) -> credentialRow(r, today), staffId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("staff_id", Long.toString(((Number) row.get("staff_id")).longValue()));
        data.put("name", row.get("name"));
        data.put("staff_type", row.get("staff_type"));
        data.put("job_title", row.get("job_title"));
        data.put("practice_no", row.get("practice_no"));
        data.put("employment_state", row.get("employment_state"));
        data.put("version", ((Number) row.get("version")).intValue());
        data.put("branches", branches);
        data.put("credentials", credentials);
        return data;
    }

    private Map<String, Object> staffListRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("staff_id", Long.toString(r.getLong("staff_id")));
        item.put("name", r.getString("name"));
        item.put("staff_type", r.getString("staff_type"));
        item.put("job_title", r.getString("job_title"));
        item.put("employment_state", r.getString("employment_state"));
        item.put("version", r.getInt("version"));
        return item;
    }

    private Map<String, Object> branchRow(java.sql.ResultSet r) throws java.sql.SQLException {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("branch_id", Long.toString(r.getLong("branch_id")));
        item.put("name", r.getString("name"));
        item.put("state", r.getString("state"));
        item.put("enabled", r.getBoolean("enabled"));
        item.put("verified_at", r.getTimestamp("verified_at") == null ? null
            : ISO_INSTANT.format(r.getTimestamp("verified_at").toInstant()));
        return item;
    }

    private Map<String, Object> credentialRow(java.sql.ResultSet r, LocalDate today) throws java.sql.SQLException {
        LocalDate from = r.getDate("valid_from") == null ? null : r.getDate("valid_from").toLocalDate();
        LocalDate to = r.getDate("valid_to") == null ? null : r.getDate("valid_to").toLocalDate();
        boolean noExpiry = r.getBoolean("no_expiry_confirmed");
        String state = r.getString("verification_state");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("credential_id", Long.toString(r.getLong("credential_id")));
        item.put("credential_type", r.getString("credential_type"));
        item.put("verification_state", state);
        item.put("valid_from", from == null ? null : from.toString());
        item.put("valid_to", to == null ? null : to.toString());
        item.put("no_expiry_confirmed", noExpiry);
        item.put("verified_at", r.getTimestamp("verified_at") == null ? null
            : ISO_INSTANT.format(r.getTimestamp("verified_at").toInstant()));
        item.put("version", r.getInt("version"));
        item.put("effective", credentialEffective(state, from, to, noExpiry, today));
        return item;
    }

    static boolean credentialEffective(
            String state, LocalDate validFrom, LocalDate validTo, boolean noExpiry, LocalDate today) {
        return CredentialValidity.onDate(state, validFrom, validTo, noExpiry, today);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), SHANGHAI);
    }

    private IdempotencyService.BusinessResult createInTx(SessionService.Principal actor, ValidatedCreate input) {
        // Serialize global staff creation against organization/owner changes, then recheck under the lock.
        List<Map<String, Object>> organizations = jdbc.queryForList(
            "SELECT organization_id,responsible_staff_id,state FROM organization ORDER BY organization_id FOR UPDATE");
        if (organizations.isEmpty()) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_NOT_CONFIGURED);
        if (organizations.size() > 1) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_AMBIGUOUS);
        Map<String, Object> org = organizations.getFirst();
        if ("停用".equals(org.get("state"))) throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_DISABLED);
        authorization.requireOrganizationOwner(actor);

        long staffId;
        try {
            staffId = jdbc.queryForObject("""
                INSERT INTO staff(name,staff_type,job_title,practice_no,employment_state,version)
                VALUES(?,?,?,?,?,1) RETURNING staff_id
                """, Long.class, input.name(), input.staffType(), input.jobTitle(), input.practiceNo(), "在岗");
        } catch (DuplicateKeyException e) {
            throw new ApiException(ApiErrorCode.STAFF_PRACTICE_NO_EXISTS);
        }

        audit.user(actor.accountId(), "staff", staffId, "创建员工", null,
            Map.of("state_to", "在岗"), "全局员工建档");
        if (input.credential() != null) {
            insertPendingCredential(actor, staffId, input.credential());
        }
        return new IdempotencyService.BusinessResult(staffId, "created", "1");
    }

    private ValidatedCreate validate(CreateCommand command) {
        if (command == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String name = text(command.name(), 60);
        String staffType = text(command.staffType(), 20);
        if (!STAFF_TYPES.contains(staffType)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String jobTitle = text(command.jobTitle(), 60);
        String practiceNo = trimToNull(command.practiceNo());
        if (practiceNo != null && practiceNo.length() > 60) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if ("牙医".equals(staffType) && practiceNo == null) {
            throw new ApiException(ApiErrorCode.STAFF_PRACTICE_NO_REQUIRED);
        }

        CredentialCommand credential = null;
        if (command.credential() != null) {
            credential = validateCredential(command.credential());
        }
        return new ValidatedCreate(name, staffType, jobTitle, practiceNo, credential);
    }

    private CredentialCommand validateCredential(CredentialCommand command) {
        if (command == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String type = text(command.credentialType(), 40);
        String certificate = text(command.certificateNo(), 80);
        LocalDate from = parseDate(command.validFrom());
        LocalDate to = parseDate(command.validTo());
        boolean noExpiry = Boolean.TRUE.equals(command.noExpiryConfirmed());
        if (to != null && from != null && to.isBefore(from)) {
            throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_INVALID);
        }
        if (noExpiry && to != null) {
            throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_INVALID);
        }
        return new CredentialCommand(type, certificate,
            from == null ? null : from.toString(), to == null ? null : to.toString(), noExpiry);
    }

    private String requestContent(ValidatedCreate c) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", c.name());
        values.put("staff_type", c.staffType());
        values.put("job_title", c.jobTitle());
        values.put("practice_no", c.practiceNo());
        if (c.credential() == null) {
            values.put("credential", null);
        } else {
            Map<String, Object> credential = new LinkedHashMap<>();
            credential.put("credential_type", c.credential().credentialType());
            credential.put("certificate_no", c.credential().certificateNo());
            credential.put("valid_from", c.credential().validFrom());
            credential.put("valid_to", c.credential().validTo());
            credential.put("no_expiry_confirmed", c.credential().noExpiryConfirmed());
            values.put("credential", credential);
        }
        return IdempotencyService.canonicalize(values);
    }

    private List<Long> authorizedBranches(SessionService.Principal actor) {
        return jdbc.query("""
            SELECT DISTINCT bs.branch_id
            FROM account_role ar
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission p ON p.permission_id=rp.permission_id AND p.code='branch.configure'
            JOIN role_branch_scope bs ON bs.account_id=ar.account_id AND bs.role_id=ar.role_id
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE ar.account_id=?
            ORDER BY bs.branch_id
            """, (r, n) -> r.getLong(1), actor.staffId(), actor.accountId());
    }

    private void requireBusinessStaff(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword()
                || actor.staffId() == null) {
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
    }

    private void requireStaffExists(long staffId) {
        if (jdbc.queryForObject("SELECT count(*) FROM staff WHERE staff_id=?", Integer.class, staffId) != 1) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }

    private long parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            long value = Long.parseLong(cursor);
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new ApiException(ApiErrorCode.STAFF_CREDENTIAL_INVALID);
        }
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record ValidatedCreate(
            String name,
            String staffType,
            String jobTitle,
            String practiceNo,
            CredentialCommand credential) {
        @Override
        public String toString() {
            return "ValidatedCreate[redacted]";
        }
    }
}
