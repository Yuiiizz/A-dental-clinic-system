package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal work messages are a communication record, never a business mutation command. */
@Service
public class InternalMessageService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public InternalMessageService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** Only currently authorized, activated staff in the same verified operating branch are contacts. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> contacts(SessionService.Principal actor) {
        requireStaff(actor);
        return jdbc.query("""
            SELECT DISTINCT b.branch_id,b.name AS branch_name,peer.staff_id AS peer_staff_id,
                   peer.name AS peer_name,ar.code AS actor_role
            FROM account_role own_role
            JOIN role ar ON ar.role_id=own_role.role_id AND ar.code IN ('frontdesk','dentist')
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id AND perm.code='messages.exchange'
            JOIN role_branch_scope own_scope ON own_scope.account_id=own_role.account_id AND own_scope.role_id=ar.role_id
            JOIN branch b ON b.branch_id=own_scope.branch_id AND b.state='营业' AND b.verified_at IS NOT NULL
            JOIN staff_branch own_sb ON own_sb.branch_id=b.branch_id AND own_sb.staff_id=?
                AND own_sb.enabled=true AND own_sb.verified_at IS NOT NULL
            JOIN staff own_staff ON own_staff.staff_id=own_sb.staff_id AND own_staff.employment_state='在岗'
            JOIN staff_branch peer_sb ON peer_sb.branch_id=b.branch_id AND peer_sb.enabled=true
                AND peer_sb.verified_at IS NOT NULL
            JOIN staff peer ON peer.staff_id=peer_sb.staff_id AND peer.employment_state='在岗'
            JOIN account peer_account ON peer_account.staff_id=peer.staff_id
                AND peer_account.enabled=true AND peer_account.password_hash IS NOT NULL
                AND peer_account.must_change_password=false
            JOIN account_role peer_role ON peer_role.account_id=peer_account.account_id
            JOIN role pr ON pr.role_id=peer_role.role_id
                AND pr.code=CASE WHEN ar.code='frontdesk' THEN 'dentist' ELSE 'frontdesk' END
            JOIN role_permission peer_rp ON peer_rp.role_id=pr.role_id
            JOIN permission peer_perm ON peer_perm.permission_id=peer_rp.permission_id AND peer_perm.code='messages.exchange'
            JOIN role_branch_scope peer_scope ON peer_scope.account_id=peer_account.account_id
                AND peer_scope.role_id=pr.role_id AND peer_scope.branch_id=b.branch_id
            WHERE own_role.account_id=? AND peer.staff_id<>?
              AND ((ar.code='frontdesk' AND own_staff.staff_type='其他人员' AND peer.staff_type='牙医')
                OR (ar.code='dentist' AND own_staff.staff_type='牙医' AND peer.staff_type='其他人员'))
            ORDER BY branch_id,peer_name,peer_staff_id
            """, (rs,n) -> Map.<String,Object>of(
                "branch_id", Long.toString(rs.getLong("branch_id")),
                "branch_name", rs.getString("branch_name"),
                "peer_staff_id", Long.toString(rs.getLong("peer_staff_id")),
                "peer_name", rs.getString("peer_name"),
                "actor_role", rs.getString("actor_role")), actor.staffId(), actor.accountId(), actor.staffId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResult<Map<String, Object>> messages(SessionService.Principal actor, long branchId,
            long peerStaffId, String cursor, Integer requestedLimit) {
        Pair pair = requirePair(actor, branchId, peerStaffId);
        int limit = PageResult.validateLimit(requestedLimit);
        long before = cursor == null || cursor.isBlank() ? Long.MAX_VALUE : positive(cursor);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT message_id,sender_account_id,body,sent_at FROM internal_staff_message
            WHERE branch_id=? AND frontdesk_staff_id=? AND dentist_staff_id=? AND message_id<?
            ORDER BY message_id DESC LIMIT ?
            """, branchId, pair.frontdesk(), pair.dentist(), before, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows.subList(0, Math.min(limit, rows.size()))) items.add(messageRow(row));
        Collections.reverse(items);
        return new PageResult<>(items, more ? String.valueOf(rows.get(limit - 1).get("message_id")) : null);
    }

    public Map<String, Object> send(SessionService.Principal actor, long branchId, long peerStaffId,
            String body, String requestKey) {
        if (body == null || body.isBlank() || body.length() > 500 || body.indexOf('\0') >= 0)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String content = body.trim();
        Runnable authorized = () -> requirePair(actor, branchId, peerStaffId);
        var result = idempotency.execute(actor.accountId(), "internal-message-send", requestKey,
            IdempotencyService.canonicalize(Map.of("branch_id", branchId, "peer_staff_id", peerStaffId, "body", content)),
            authorized,
            () -> {
                Pair pair = requirePair(actor, branchId, peerStaffId);
                Long id = jdbc.queryForObject("""
                    INSERT INTO internal_staff_message(branch_id,frontdesk_staff_id,dentist_staff_id,
                      sender_account_id,body,sent_at) VALUES(?,?,?,?,?,?) RETURNING message_id
                    """, Long.class, branchId, pair.frontdesk(), pair.dentist(), actor.accountId(),
                    content, Timestamp.from(clock.instant()));
                return new IdempotencyService.BusinessResult(id, "sent", null);
            }, id -> {
                Pair pair = requirePair(actor, branchId, peerStaffId);
                Integer visible = jdbc.queryForObject("""
                    SELECT count(*) FROM internal_staff_message WHERE message_id=? AND branch_id=?
                      AND frontdesk_staff_id=? AND dentist_staff_id=? AND sender_account_id=?
                    """, Integer.class, id, branchId,
                    pair.frontdesk(), pair.dentist(), actor.accountId());
                if (visible != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return message(result.targetId());
    }

    private Map<String, Object> message(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT message_id,sender_account_id,body,sent_at FROM internal_staff_message WHERE message_id=?", id);
        if (rows.size() != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
        return messageRow(rows.getFirst());
    }

    private Map<String, Object> messageRow(Map<String, Object> row) {
        return Map.of("message_id", String.valueOf(row.get("message_id")),
            "sender_account_id", String.valueOf(row.get("sender_account_id")),
            "body", row.get("body"), "sent_at", ((Timestamp) row.get("sent_at")).toInstant().toString());
    }

    private Pair requirePair(SessionService.Principal actor, long branchId, long peerStaffId) {
        requireStaff(actor);
        if (branchId <= 0 || peerStaffId <= 0 || peerStaffId == actor.staffId())
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        List<Map<String, Object>> allowed = contacts(actor);
        boolean found = allowed.stream().anyMatch(row -> branchId == Long.parseLong((String) row.get("branch_id"))
            && peerStaffId == Long.parseLong((String) row.get("peer_staff_id")));
        if (!found) throw new ApiException(ApiErrorCode.NOT_FOUND);
        String role = allowed.stream().filter(row -> branchId == Long.parseLong((String) row.get("branch_id"))
            && peerStaffId == Long.parseLong((String) row.get("peer_staff_id")))
            .findFirst().map(row -> (String) row.get("actor_role")).orElseThrow();
        return "frontdesk".equals(role) ? new Pair(actor.staffId(), peerStaffId)
            : new Pair(peerStaffId, actor.staffId());
    }

    private void requireStaff(SessionService.Principal actor) {
        if (actor == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        authorization.requireCurrent(actor);
        if (!SessionService.STAFF.equals(actor.clientType()) || actor.mustChangePassword() || actor.staffId() == null)
            throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private long positive(String value) {
        try { long parsed = Long.parseLong(value); if (parsed > 0) return parsed; }
        catch (NumberFormatException ignored) { /* translated below */ }
        throw new ApiException(ApiErrorCode.INVALID_REQUEST);
    }

    private record Pair(long frontdesk, long dentist) {}
}
