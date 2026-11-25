package cn.dental.identity.application;

import cn.dental.identity.application.PatientRegistrationChallengeService.Challenge;
import cn.dental.identity.application.PatientRegistrationExecutor.Outcome;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者自助建档编排层。Propagation.NEVER 明确禁止加入任何外层事务：本层本身不写库，只做入口限流、
 * 摘要查找、判活、字段解析与纯字段校验；权威的手机号重复检查与原子落地都在 {@link PatientRegistrationExecutor}
 * 事务内完成。NEVER 是为了避免未来出现「外层事务持锁 → 进入本层 → recordFailure 再 REQUIRES_NEW」的自锁风险。
 *
 * 失败计数分类（failed_attempts 只在「可识别有效上下文的身份/业务校验失败」时累加，由
 * {@link PatientRegistrationChallengeService#recordFailure} 以权威条件约束）：
 * 计数的失败：过敏状态为 有/不确定 而说明为空（纯字段校验）、手机号疑似重复（事务内权威检查后）。
 * 不计数的失败：未知凭证（入口 IP 限流，不读库）、已失效/已消费/错用途/已达失败上限（死上下文）、
 * 出生日期格式错误与 bean 校验错误（基本格式错误）、系统/数据库错误（绝不惩罚）。
 */
@Service
public class PatientRegistrationService {
    private final PatientRegistrationChallengeService challenges;
    private final PatientRegistrationExecutor executor;
    private final PatientRegisterThrottle throttle;
    private final Clock clock;

    public PatientRegistrationService(PatientRegistrationChallengeService challenges, PatientRegistrationExecutor executor,
            PatientRegisterThrottle throttle, Clock clock) {
        this.challenges = challenges; this.executor = executor; this.throttle = throttle; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome register(String token, String name, String phone, String birthDateText,
            String allergyStatus, String allergyNotes, String remoteAddress) {
        // 入口前独立有界 IP 预算：在任何数据库查询/执行器调用之前检查，超限即 429，不触发数据库探测。
        throttle.check(remoteAddress);
        String hash = SessionService.hash(token.strip());
        Challenge ctx = challenges.find(hash);
        if (ctx == null) throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);
        // 死上下文（错用途/已消费/已撤销/已达失败上限/已过期）：安全拒绝，不计数。
        if (!"wechat_registration".equals(ctx.purpose()) || ctx.consumed() || ctx.revoked() || ctx.failedAttempts() >= 5
                || !ctx.expiresAt().isAfter(clock.instant()))
            throw new ApiException(ApiErrorCode.REGISTRATION_TOKEN_INVALID);

        LocalDate birthDate = parseBirthDate(birthDateText); // 格式错误：非计数的 VALIDATION_FAILED
        String normalizedName = name.strip();
        String normalizedPhone = phone.strip();
        String notes = allergyNotes == null ? null : allergyNotes.strip();

        // 纯字段校验（不依赖数据库）：过敏状态为 有/不确定 时说明必填，计数。
        if (notesRequired(allergyStatus) && (notes == null || notes.isEmpty())) {
            challenges.recordFailure(ctx.challengeId());
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }

        try {
            return executor.execute(ctx, normalizedName, normalizedPhone, birthDate, allergyStatus, notes);
        } catch (ApiException e) {
            // 权威手机号重复检查在事务内抛 PATIENT_DUPLICATE_SUSPECTED；此处事务已回滚、锁已释放，
            // 独立提交失败计数，绝不自锁。
            if (e.code() == ApiErrorCode.PATIENT_DUPLICATE_SUSPECTED) {
                challenges.recordFailure(ctx.challengeId());
            }
            throw e;
        }
    }

    private static boolean notesRequired(String allergyStatus) {
        return "有".equals(allergyStatus) || "不确定".equals(allergyStatus);
    }

    private static LocalDate parseBirthDate(String text) {
        if (text == null || text.isBlank()) return null;
        try { return LocalDate.parse(text.strip()); }
        catch (DateTimeParseException e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }
}
