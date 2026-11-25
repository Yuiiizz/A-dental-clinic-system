package cn.dental.identity.application;

import cn.dental.identity.application.PatientBindingExecutor.Outcome;
import cn.dental.identity.application.PatientRegistrationChallengeService.Challenge;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/** 旧档绑定编排层单元测试：失败计数只命中可识别有效上下文，系统故障绝不误罚。 */
class PatientBindingServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Instant FUTURE = NOW.plusSeconds(600);

    private Challenge regCtx(String openId) {
        return new Challenge(UUID.randomUUID(), "wechat_registration", "mock", openId, null, FUTURE, false, false, 0);
    }

    private Challenge bindingCtx() {
        return new Challenge(UUID.randomUUID(), "patient_binding", null, null, 1L, FUTURE, false, false, 0);
    }

    private PatientBindingService service(PatientRegistrationChallengeService challenges, PatientBindingExecutor executor,
            PatientBindingThrottle throttle, Clock clock) {
        return new PatientBindingService(challenges, executor, throttle, clock);
    }

    @Test
    void systemFailureIsNotCounted() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var reg = regCtx("sys-openid");
        var binding = bindingCtx();
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(reg, binding);
        Mockito.when(executor.execute(Mockito.any(), Mockito.any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        Mockito.verify(throttle).check("127.0.0.1");
        Mockito.verify(challenges, Mockito.never()).recordFailure(Mockito.any(), Mockito.anyString());
    }

    @Test
    void businessConflictCountsBindingContext() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var reg = regCtx("conflict-openid");
        var binding = bindingCtx();
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(reg, binding);
        Mockito.when(executor.execute(reg, binding)).thenThrow(new ApiException(ApiErrorCode.PATIENT_ALREADY_BOUND));

        var e = assertThrows(ApiException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        assertEquals(ApiErrorCode.PATIENT_ALREADY_BOUND, e.code());
        Mockito.verify(challenges).recordFailure(binding.challengeId(), "patient_binding");
        Mockito.verify(challenges, Mockito.never()).recordFailure(Mockito.any(UUID.class));
    }

    @Test
    void invalidBindingCountsRegistration() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var reg = regCtx("valid-openid");
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(reg, null); // 绑定凭证未知

        var e = assertThrows(ApiException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        assertEquals(ApiErrorCode.BINDING_TOKEN_INVALID, e.code());
        Mockito.verify(challenges).recordFailure(reg.challengeId(), "wechat_registration");
    }

    @Test
    void invalidRegistrationCountsBinding() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var binding = bindingCtx();
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(null, binding); // 注册凭证未知

        var e = assertThrows(ApiException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        assertEquals(ApiErrorCode.REGISTRATION_TOKEN_INVALID, e.code());
        Mockito.verify(challenges).recordFailure(binding.challengeId(), "patient_binding");
    }

    @Test
    void bothUnknownDoesNotCount() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Mockito.when(challenges.find(Mockito.anyString())).thenReturn(null, null);

        var e = assertThrows(ApiException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        assertEquals(ApiErrorCode.REGISTRATION_TOKEN_INVALID, e.code());
        Mockito.verify(challenges, Mockito.never()).recordFailure(Mockito.any(), Mockito.anyString());
    }

    @Test
    void overLimitDoesNotQueryDatabase() {
        var challenges = Mockito.mock(PatientRegistrationChallengeService.class);
        var executor = Mockito.mock(PatientBindingExecutor.class);
        var throttle = Mockito.mock(PatientBindingThrottle.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Mockito.doThrow(new ApiException(ApiErrorCode.RATE_LIMITED)).when(throttle).check("127.0.0.1");

        var e = assertThrows(ApiException.class, () -> service(challenges, executor, throttle, clock).bind("reg", "bind", "127.0.0.1"));
        assertEquals(ApiErrorCode.RATE_LIMITED, e.code());
        Mockito.verify(challenges, Mockito.never()).find(Mockito.anyString());
        Mockito.verify(executor, Mockito.never()).execute(Mockito.any(), Mockito.any());
    }

    @Test
    void outcomeToStringIsRedacted() {
        var o = new Outcome("secret-token", "42", NOW.plusSeconds(3600));
        assertFalse(o.toString().contains("secret-token"));
    }
}
