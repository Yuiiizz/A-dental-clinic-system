package cn.dental.identity.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 已绑定/未绑定的微信身份落地。独立事务代理 bean（由 WechatLoginService 跨代理调用，避免
 * 自调用失效）。已绑定核验患者身份与账号启用后签发 patient_miniapp 会话；停用/异常绑定返回
 * 安全错误，绝不当作未绑定另发注册凭证。未绑定只写 identity_challenge（purpose=wechat_registration、
 * 10 分钟、至少 128 位随机熵且只存摘要），不创建患者/账号。
 */
@Service
public class WechatBindingService {
    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public WechatBindingService(JdbcTemplate jdbc,SessionService sessions,Clock clock){
        this.jdbc=jdbc;this.sessions=sessions;this.clock=clock;
    }

    @Transactional public WechatLoginService.WechatLoginOutcome bind(String appId,String openId){
        var bound=jdbc.queryForList("SELECT account_id FROM wechat_identity WHERE app_id=? AND open_id=?",appId,openId);
        if(bound.isEmpty())return registration(appId,openId);
        long accountId=((Number)bound.getFirst().get("account_id")).longValue();
        var account=jdbc.queryForList("SELECT enabled,patient_id FROM account WHERE account_id=? FOR UPDATE",accountId);
        if(account.isEmpty() || account.getFirst().get("patient_id")==null || !Boolean.TRUE.equals(account.getFirst().get("enabled")))
            throw new ApiException(ApiErrorCode.FORBIDDEN); // 停用/异常绑定：安全错误，不当作未绑定
        var issued=sessions.issue(accountId,SessionService.PATIENT);
        return new WechatLoginService.WechatLoginOutcome.Session(issued.token(),issued.expiresAt());
    }

    private WechatLoginService.WechatLoginOutcome registration(String appId,String openId){
        String token=token(); // 32 字节 = 256 位随机熵，仅存摘要
        Instant now=clock.instant(),expires=now.plus(Duration.ofMinutes(10));
        jdbc.update("""
            INSERT INTO identity_challenge(challenge_id,purpose,token_hash,app_id,open_id,expires_at,created_at)
            VALUES(?,'wechat_registration',?,?,?,?,?)
            """,UUID.randomUUID(),SessionService.hash(token),appId,openId,Timestamp.from(expires),Timestamp.from(now));
        return new WechatLoginService.WechatLoginOutcome.Registration(token,expires);
    }

    private String token(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
