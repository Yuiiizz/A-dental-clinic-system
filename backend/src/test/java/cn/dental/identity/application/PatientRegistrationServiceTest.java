package cn.dental.identity.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/** 编排层单元测试：确认失败计数只命中可识别有效上下文的身份/业务校验失败，绝不因系统故障误罚。 */
class PatientRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    private PatientRegistrationChallengeService.Challenge validContext(Clock clock, String openId) {
        return new PatientRegistrationChallengeService.Challenge(
            UUID.randomUUID(), "wechat_registration", "mock", openId, null, clock.instant().plusSeconds(600), false, false, 0);
    }

    private PatientRegistrationService service(PatientRegistrationChallengeService challenges,
            PatientRegistrationExecutor executor, PatientRegisterThrottle throttle, Clock clock) {
        return new PatientRegistrationService(challenges, executor, throttle, clock);
    }

    @Test
    void systemFailureIsNotCounted() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientRegistrationExecutor.class);
        var throttle = Mockito.mock(PatientRegisterThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var ctx = validContext(clock, "system-openid");
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(ctx);
        Mockito.when(executor.execute(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()))
            .thenThrow(new RuntimeException("db down"));

        var service = service(challenges, executor, throttle, clock);
        assertThrows(RuntimeException.class,
            () -> service.register("tok", "张测试", "13900000000", null, "无已知", null, "127.0.0.1"));
        Mockito.verify(throttle).check("127.0.0.1");
        Mockito.verify(challenges, Mockito.never()).recordFailure(Mockito.any());
    }

    @Test
    void businessValidationFailureIsCountedOnce() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientRegistrationExecutor.class);
        var throttle = Mockito.mock(PatientRegisterThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var ctx = validContext(clock, "counted-openid");
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(ctx);

        var service = service(challenges, executor, throttle, clock);
        // 过敏状态为「有」但说明为空：计数的业务校验失败。
        var e = assertThrows(ApiException.class,
            () -> service.register("tok", "张测试", "13900000000", null, "有", null, "127.0.0.1"));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, e.code());
        Mockito.verify(challenges).recordFailure(ctx.challengeId());
        Mockito.verify(executor, Mockito.never())
            .execute(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void executorPhoneDuplicateIsCountedAfterRollback() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientRegistrationExecutor.class);
        var throttle = Mockito.mock(PatientRegisterThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var ctx = validContext(clock, "dup-openid");
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(ctx);
        // 权威重复检查在事务内抛 PATIENT_DUPLICATE_SUSPECTED，编排层在事务回滚后补记失败计数。
        Mockito.when(executor.execute(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any()))
            .thenThrow(new ApiException(ApiErrorCode.PATIENT_DUPLICATE_SUSPECTED));

        var service = service(challenges, executor, throttle, clock);
        var e = assertThrows(ApiException.class,
            () -> service.register("tok", "张测试", "13900000000", null, "无已知", null, "127.0.0.1"));
        assertEquals(ApiErrorCode.PATIENT_DUPLICATE_SUSPECTED, e.code());
        Mockito.verify(challenges).recordFailure(ctx.challengeId());
    }

    @Test
    void overLimitDoesNotQueryDatabase() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientRegistrationExecutor.class);
        var throttle = Mockito.mock(PatientRegisterThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Mockito.doThrow(new ApiException(ApiErrorCode.RATE_LIMITED)).when(throttle).check("127.0.0.1");

        var service = service(challenges, executor, throttle, clock);
        var e = assertThrows(ApiException.class,
            () -> service.register("tok", "张测试", "13900000000", null, "无已知", null, "127.0.0.1"));
        assertEquals(ApiErrorCode.RATE_LIMITED, e.code());
        // 限流在任何查询之前：find 不被调用，即不触发数据库探测。
        Mockito.verify(challenges, Mockito.never()).find(Mockito.anyString());
        Mockito.verify(executor, Mockito.never())
            .execute(Mockito.any(), Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void challengeToStringIsRedacted() {
        var c = new PatientRegistrationChallengeService.Challenge(
            UUID.randomUUID(), "wechat_registration", "app-123", "open-456", null, NOW.plusSeconds(600), false, false, 0);
        assertFalse(c.toString().contains("app-123"));
        assertFalse(c.toString().contains("open-456"));
    }
}
