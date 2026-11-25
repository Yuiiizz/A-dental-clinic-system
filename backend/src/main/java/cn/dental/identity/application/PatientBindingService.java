package cn.dental.identity.application;

import cn.dental.identity.application.PatientRegistrationChallengeService.Challenge;
import cn.dental.identity.application.PatientBindingExecutor.Outcome;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧档绑定编排层。Propagation.NEVER 明确禁止加入任何外层事务：本层不写库，只做入口限流、摘要查找、判活；
 * 权威复查与原子落地都在 {@link PatientBindingExecutor} 事务内完成。NEVER 避免「外层事务持锁 → 进入本层 →
 * recordFailure 再 REQUIRES_NEW」的自锁风险。
 *
 * 失败计数归责（failed_attempts 只在「可识别有效上下文的身份/业务校验失败」时累加）：
 * - 注册有效而绑定无效 → 计注册上下文；绑定有效而注册无效 → 计绑定上下文（至少计可识别有效的那一侧）。
 * - 双方有效但业务冲突：患者已绑定另一微信 / 目标账号已停用 → 计绑定上下文（绑定凭证已过时/错误）；
 *   WECHAT_IDENTITY_ALREADY_BOUND（注册微信已被绑定）→ 不计，与 T017-A 一致，用户应直接登录。
 * - 未知凭证（无可更新行）→ 入口 IP 限流，不读库计数；系统/数据库错误 → 绝不惩罚。
 * 计数在业务事务结束（回滚）后独立提交，封顶5、撤销后不增。
 */
@Service
public class PatientBindingService {
    private final PatientRegistrationChallengeService challenges;
    private final PatientBindingExecutor executor;
    private final PatientBindingThrottle throttle;
    private final Clock clock;

    public PatientBindingService(PatientRegistrationChallengeService challenges, PatientBindingExecutor executor,
            PatientBindingThrottle throttle, Clock clock) {
        this.challenges = challenges; this.executor = executor; this.throttle = throttle; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome bind(String registrationToken, String bindingToken, String remoteAddress) {
        throttle.check(remoteAddress);
        String regHash = SessionService.hash(registrationToken.strip());
        String bindingHash = SessionService.hash(bindingToken.strip());
        Challenge regCtx = challenges.find(regHash);
        Challenge bindingCtx = challenges.find(bindingHash);
        boolean regValid = regCtx != null && "wechat_registration".equals(regCtx.purpose()) && !regCtx.consumed() && !regCtx.revoked()
                && regCtx.failedAttempts() < 5 && regCtx.expiresAt().isAfter(clock.instant());
        boolean bindingValid = bindingCtx != null && "patient_binding".equals(bindingCtx.purpose()) && bindingCtx.patientId() != null
                && !bindingCtx.consumed() && !bindingCtx.revoked()
                && bindingCtx.failedAttempts() < 5 && bindingCtx.expiresAt().isAfter(clock.instant());
        if (!regValid) {
            if (bindingValid) challenges.recordFailure(bindingCtx.challengeId(), "patient_binding");
            throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        }
        if (!bindingValid) {
            challenges.recordFailure(regCtx.challengeId(), "wechat_registration");
            throw new ApiException(ApiErrorCode.BINDING_TOKEN_INVALID);
        }
        try {
            return executor.execute(regCtx, bindingCtx);
        } catch (ApiException e) {
            if (e.code() == ApiErrorCode.PATIENT_ALREADY_BOUND || e.code() == ApiErrorCode.PATIENT_ACCOUNT_DISABLED) {
                challenges.recordFailure(bindingCtx.challengeId(), "patient_binding");
            }
            throw e;
        }
    }
}
