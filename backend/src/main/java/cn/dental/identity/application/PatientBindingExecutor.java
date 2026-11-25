package cn.dental.identity.application;

import cn.dental.identity.application.PatientRegistrationChallengeService.Challenge;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧档绑定的原子落地事务：有效注册上下文（wechat_registration）+ 有效绑定凭证（patient_binding）核验后，
 * 单事务内消费两个凭证、建立或关联唯一患者账号及微信身份、签发患者会话并写必要审计；任何一步失败整体回滚，
 * 不留「已消费却未绑定」的上下文或孤儿账号。禁止自动转移/合并、禁止启用停用账号。
 *
 * 锁序固定（与注册、签发/重签共用 IdentityLocks 派生，避免相反顺序死锁）：
 * 1. 微信身份 (app_id, open_id) 事务级 advisory 锁——串行化同一 openid 的并发绑定/注册。
 * 2. 患者绑定 patient-bind 事务级 advisory 锁——串行化同一患者的绑定与签发/重签。
 * 3. 注册上下文行 FOR UPDATE 权威复查（用途/摘要身份/未撤销/未消费/未到期/失败次数）。
 * 4. 绑定凭证行 FOR UPDATE 权威复查（同上，且 patient_id 与快照一致，客户端不能替换身份）。
 * 5. 持锁后重查 wechat_identity(app_id,open_id) 未绑定（含停用账号，绝不重绑/重新启用）。
 * 6. 目标患者账号：先 account 行 FOR UPDATE 锁定（避免 wechat_identity 外键的 FOR KEY SHARE 隐式锁升级
 *    与并发停用竞争），持锁后重查绑定与启用；已有另一微信→409、已停用→403、无账号→新建（同事务）、未绑定且启用→关联。
 * 7. 关联微信身份（账号行已锁定，外键不再隐式升级）。
 * 8. 按固定顺序（先注册后绑定）单次消费两个凭证。
 * 9. 签发患者会话（leaf：SessionService.issue 会取 account 行 FOR UPDATE）。
 * 10. 系统审计（最小摘要）。
 *
 * 账号维护停用走 734192014→account 行 FOR UPDATE，本方法走 patient-bind advisory→…→account 行 FOR UPDATE
 * 且不取 734192014；两端都以 account 行 FOR UPDATE 为叶子，无反向锁序，不构成死锁。
 */
@Service
public class PatientBindingExecutor {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final Clock clock;

    public PatientBindingExecutor(JdbcTemplate jdbc, SessionService sessions, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.sessions = sessions; this.audit = audit; this.clock = clock;
    }

    public record Outcome(String sessionToken, String patientId, Instant expiresAt) {
        @Override public String toString() { return "PatientSession[redacted]"; }
    }

    @Transactional
    public Outcome execute(Challenge regCtx, Challenge bindingCtx) {
        String appId = regCtx.appId();
        String openId = regCtx.openId();
        Long patientId = bindingCtx.patientId();
        if (appId == null || openId == null || patientId == null)
            throw new ApiException(ApiErrorCode.BINDING_TOKEN_INVALID);
        // 1. 微信身份事务锁。
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("wechat-identity", appId, openId) + ")");
        // 2. 患者绑定事务锁（与签发/重签共用同一患者锁）。
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("patient-bind", Long.toString(patientId)) + ")");
        // 3. 持锁后权威复查注册上下文。
        var regRows = jdbc.queryForList("""
            SELECT purpose,app_id,open_id,expires_at,consumed_at,revoked_at,failed_attempts
            FROM identity_challenge WHERE challenge_id=? FOR UPDATE
            """, regCtx.challengeId());
        if (regRows.isEmpty()) throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        var reg = regRows.getFirst();
        if (!"wechat_registration".equals(reg.get("purpose")) || reg.get("consumed_at") != null || reg.get("revoked_at") != null
                || ((Number) reg.get("failed_attempts")).intValue() >= 5
                || !((Timestamp) reg.get("expires_at")).toInstant().isAfter(clock.instant())
                || !appId.equals(reg.get("app_id")) || !openId.equals(reg.get("open_id")))
            throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        // 4. 持锁后权威复查绑定凭证。
        var bindRows = jdbc.queryForList("""
            SELECT purpose,patient_id,expires_at,consumed_at,revoked_at,failed_attempts
            FROM identity_challenge WHERE challenge_id=? FOR UPDATE
            """, bindingCtx.challengeId());
        if (bindRows.isEmpty()) throw new ApiException(ApiErrorCode.BINDING_TOKEN_INVALID);
        var bind = bindRows.getFirst();
        if (!"patient_binding".equals(bind.get("purpose")) || bind.get("consumed_at") != null || bind.get("revoked_at") != null
                || ((Number) bind.get("failed_attempts")).intValue() >= 5
                || !((Timestamp) bind.get("expires_at")).toInstant().isAfter(clock.instant())
                || !patientId.equals(((Number) bind.get("patient_id")).longValue()))
            throw new ApiException(ApiErrorCode.BINDING_TOKEN_INVALID);
        // 5. 微信身份未绑定（含停用账号）。
        int bound = jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id=? AND open_id=?", Integer.class, appId, openId);
        if (bound != 0) throw new ApiException(ApiErrorCode.WECHAT_IDENTITY_ALREADY_BOUND);
        // 6. 先锁定并权威检查目标患者账号（account 行 FOR UPDATE），再写微信身份：
        //    避免 wechat_identity 外键的 FOR KEY SHARE 隐式锁升级，并与并发停用在 account 行锁上串行化。
        long accountId;
        var accounts = jdbc.queryForList("SELECT account_id,enabled FROM account WHERE patient_id=? FOR UPDATE", patientId);
        if (accounts.isEmpty()) {
            // 新账号仍在同一事务内建立；INSERT 立即持有该行锁，后续 wechat_identity 外键不再升级。
            accountId = jdbc.queryForObject("""
                INSERT INTO account(login_name,patient_id,staff_id,enabled,password_hash,must_change_password,created_at)
                VALUES(NULL,?,NULL,true,NULL,false,?) RETURNING account_id
                """, Long.class, patientId, Timestamp.from(clock.instant()));
        } else {
            var a = accounts.getFirst();
            accountId = ((Number) a.get("account_id")).longValue();
            // 持账号锁后重查绑定与启用：与并发停用在 account 行锁上串行化，读到最新 committed 状态。
            int accountBound = jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE account_id=?", Integer.class, accountId);
            if (accountBound != 0) throw new ApiException(ApiErrorCode.PATIENT_ALREADY_BOUND);
            if (!Boolean.TRUE.equals(a.get("enabled"))) throw new ApiException(ApiErrorCode.PATIENT_ACCOUNT_DISABLED);
        }
        // 7. 关联微信身份（账号行已锁定，外键不再隐式升级）。
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(?,?,?,?)",
            accountId, appId, openId, Timestamp.from(clock.instant()));
        // 8. 按固定顺序（先注册后绑定）单次消费两个凭证。
        int c1 = jdbc.update("UPDATE identity_challenge SET consumed_at=? WHERE challenge_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
            Timestamp.from(clock.instant()), regCtx.challengeId());
        if (c1 != 1) throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        int c2 = jdbc.update("UPDATE identity_challenge SET consumed_at=? WHERE challenge_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
            Timestamp.from(clock.instant()), bindingCtx.challengeId());
        if (c2 != 1) throw new ApiException(ApiErrorCode.BINDING_TOKEN_INVALID);
        // 9. 签发患者会话（leaf：account 行 FOR UPDATE）。
        var issued = sessions.issue(accountId, SessionService.PATIENT);
        // 10. 系统审计（最小摘要，不记录 openid/token/姓名/电话）。
        audit.system("patient", patientId, "旧档绑定微信", null, Map.of("state_to", "bound"), null);
        return new Outcome(issued.token(), Long.toString(patientId), issued.expiresAt());
    }
}
