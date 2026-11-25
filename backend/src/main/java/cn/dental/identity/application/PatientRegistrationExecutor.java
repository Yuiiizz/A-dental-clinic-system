package cn.dental.identity.application;

import cn.dental.identity.application.PatientRegistrationChallengeService.Challenge;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人自助建档的原子落地事务。单事务内建立 patient/account/wechat_identity、消费注册上下文、
 * 签发 patient_miniapp 会话并写必要审计；任何一步失败（含会话签发）整体回滚，不留孤儿档案或
 * 「已消费却无账号」的上下文。
 *
 * 锁序固定（先微信身份、后手机号、再挑战行，任何并发路径都按同一顺序取锁，避免相反顺序死锁）：
 * 1. 微信身份 (app_id, open_id) 事务级 advisory 锁——串行化同一 openid 的并发建档（含同凭证并发）。
 * 2. 规范化手机号事务级 advisory 锁——串行化同一手机号、不同微信的并发建档。家庭共用手机号是真实场景，
 *    不给 phone 加唯一约束，也不按手机号合并/登录；本入口并发时同手机号只建一份，其余安全转人工。
 * 3. 上下文行 FOR UPDATE 权威复查判活；持锁后重查 wechat_identity 绑定（第 4 步）与手机号疑似重复（第 5 步）。
 *
 * 手机号疑似重复的权威检查在此事务内完成（抛 {@link ApiErrorCode#PATIENT_DUPLICATE_SUSPECTED}），
 * 杜绝编排层先查后写的 TOCTOU。失败计数由编排层在注册事务结束后独立提交；本方法绝不调用 recordFailure，
 * 避免持锁再 REQUIRES_NEW 导致自锁。数据库唯一约束 uq_wechat_identity_business(app_id,open_id) 作为
 * 最终兜底；本方法不捕获唯一冲突后在已失败事务内继续查询——advisory 锁已使该路径不可达，若仍发生则整体回滚。
 */
@Service
public class PatientRegistrationExecutor {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final Clock clock;

    public PatientRegistrationExecutor(JdbcTemplate jdbc, SessionService sessions, AuditService audit, Clock clock) {
        this.jdbc = jdbc; this.sessions = sessions; this.audit = audit; this.clock = clock;
    }

    public record Outcome(String sessionToken, String patientId, Instant expiresAt) {
        @Override public String toString() { return "PatientSession[redacted]"; }
    }

    @Transactional
    public Outcome execute(Challenge ctx, String name, String phone, LocalDate birthDate, String allergyStatus, String allergyNotes) {
        // 1. 微信身份事务锁（串行化同 openid）。
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("wechat-identity", ctx.appId(), ctx.openId()) + ")");
        // 2. 规范化手机号事务锁（串行化同手机号不同微信）。
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("patient-phone", phone) + ")");
        // 3. 持锁后权威复查上下文（FOR UPDATE），并发期间可能已被消费/撤销/失效。
        var rows = jdbc.queryForList("""
            SELECT challenge_id,purpose,app_id,open_id,expires_at,consumed_at,revoked_at,failed_attempts
            FROM identity_challenge WHERE challenge_id=? FOR UPDATE
            """, ctx.challengeId());
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        var r = rows.getFirst();
        String purpose = (String) r.get("purpose");
        String appId = (String) r.get("app_id");
        String openId = (String) r.get("open_id");
        boolean consumed = r.get("consumed_at") != null;
        boolean revoked = r.get("revoked_at") != null;
        int failed = ((Number) r.get("failed_attempts")).intValue();
        if (!"wechat_registration".equals(purpose) || consumed || revoked || failed >= 5
                || !((Timestamp) r.get("expires_at")).toInstant().isAfter(clock.instant()))
            throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        // 4. 持锁后重查绑定：已绑定（含停用账号）绝不重绑/重新启用。
        int bound = jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id=? AND open_id=?", Integer.class, appId, openId);
        if (bound != 0) throw new ApiException(ApiErrorCode.WECHAT_IDENTITY_ALREADY_BOUND);
        // 5. 持手机号锁后权威重查疑似重复：不合并、不登录，转人工（计数由编排层在事务后独立提交）。
        int dup = jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone);
        if (dup != 0) throw new ApiException(ApiErrorCode.PATIENT_DUPLICATE_SUSPECTED);
        // 6-8. 建档 + 账号 + 微信身份（同事务）。
        long patientId = jdbc.queryForObject("""
            INSERT INTO patient(name,phone,birth_date,allergy_status,allergy_notes,source_channel) VALUES(?,?,?,?,?,'微信小程序') RETURNING patient_id
            """, Long.class, name, phone, birthDate == null ? null : Date.valueOf(birthDate), allergyStatus, allergyNotes);
        long accountId = jdbc.queryForObject("""
            INSERT INTO account(login_name,patient_id,staff_id,enabled,password_hash,must_change_password,created_at)
            VALUES(NULL,?,NULL,true,NULL,false,?) RETURNING account_id
            """, Long.class, patientId, Timestamp.from(clock.instant()));
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(?,?,?,?)",
            accountId, appId, openId, Timestamp.from(clock.instant()));
        // 9. 单次消费上下文；行已被本事务锁定，若意外不是 1 行说明已并发消费，安全拒绝。
        int consumedRows = jdbc.update("UPDATE identity_challenge SET consumed_at=? WHERE challenge_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
            Timestamp.from(clock.instant()), ctx.challengeId());
        if (consumedRows != 1) throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        // 10. 签发患者会话（不签发员工 Cookie、不授予任何角色）；失败则整体回滚。
        var issued = sessions.issue(accountId, SessionService.PATIENT);
        // 11. 必要审计（系统事件，最小摘要；不记录 openid/token/姓名/电话）。
        audit.system("patient", patientId, "患者自助建档", null, Map.of("state_to", "created"), null);
        return new Outcome(issued.token(), Long.toString(patientId), issued.expiresAt());
    }
}
