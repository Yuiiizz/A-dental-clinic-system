package cn.dental.identity.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 身份上下文读取与失败计数（注册 wechat_registration 与绑定 patient_binding 共用）。查找仅按 token_hash
 * （摘要），原始凭证从不入库/入参、不写日志或异常。
 *
 * 失败计数（failed_attempts）须在业务异常回滚后仍可靠提交：recordFailure 使用 REQUIRES_NEW 独立事务，
 * 由非事务编排层在抛出安全 API 错误前/后调用，与主事务不共享连接，因此绝不出现「外层持 challenge 行锁再
 * REQUIRES_NEW 更新同一行」的自锁。计数用单条 UPDATE 原子累加，并用权威条件约束：用途匹配、未消费、
 * 未撤销、未到期、failed_attempts<5；并发不丢失、最多累加到 5。读取后已过期/已消费/已撤销/已达上限的
 * 上下文不再累加。
 */
@Service
public class PatientRegistrationChallengeService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PatientRegistrationChallengeService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** 摘要查找的身份上下文快照；仅用于编排层判活，落库前的权威复查在事务内以行锁完成。 */
    public record Challenge(UUID challengeId, String purpose, String appId, String openId, Long patientId,
                            Instant expiresAt, boolean consumed, boolean revoked, int failedAttempts) {
        @Override public String toString() { return "RegistrationChallenge[redacted]"; }
    }

    public Challenge find(String tokenHash) {
        var rows = jdbc.queryForList("""
            SELECT challenge_id,purpose,app_id,open_id,patient_id,expires_at,consumed_at,revoked_at,failed_attempts
            FROM identity_challenge WHERE token_hash=?
            """, tokenHash);
        if (rows.isEmpty()) return null;
        var r = rows.getFirst();
        return new Challenge((UUID) r.get("challenge_id"), (String) r.get("purpose"),
            (String) r.get("app_id"), (String) r.get("open_id"),
            (Long) r.get("patient_id"),
            ((Timestamp) r.get("expires_at")).toInstant(),
            r.get("consumed_at") != null, r.get("revoked_at") != null, ((Number) r.get("failed_attempts")).intValue());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId) {
        recordFailure(challengeId, "wechat_registration");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID challengeId, String purpose) {
        jdbc.update("""
            UPDATE identity_challenge SET failed_attempts=failed_attempts+1
            WHERE challenge_id=? AND purpose=? AND consumed_at IS NULL AND revoked_at IS NULL
              AND expires_at>? AND failed_attempts<5
            """, challengeId, purpose, Timestamp.from(clock.instant()));
    }
}
