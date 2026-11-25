package cn.dental.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentSafetyInitializerTest {
    private void check(MockEnvironment env) {
        try (var context = new GenericApplicationContext()) {
            context.setEnvironment(env);
            new EnvironmentSafetyInitializer().initialize(context);
        }
    }
    private MockEnvironment production() {
        var env = new MockEnvironment()
                .withProperty("app.mock.wechat-enabled", "false")
                .withProperty("app.seed.enabled", "false")
                .withProperty("server.servlet.session.cookie.secure", "true");
        env.setActiveProfiles("production");
        return env;
    }
    @Test void permitsSafeProduction() { assertDoesNotThrow(() -> check(production())); }
    @Test void rejectsHighPriorityMockOverride() {
        var env = production();
        env.getPropertySources().addFirst(new MapPropertySource("override",
                Map.of("app.mock.wechat-enabled", "true")));
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void rejectsSeedOverride() {
        assertThrows(IllegalStateException.class,
                () -> check(production().withProperty("app.seed.enabled", "true")));
    }
    @Test void rejectsInsecureCookie() {
        assertThrows(IllegalStateException.class,
                () -> check(production().withProperty("server.servlet.session.cookie.secure", "false")));
    }
    @Test void rejectsMixedProfiles() {
        for (String profile : new String[]{"local", "test"}) {
            var env = production(); env.setActiveProfiles("production", profile);
            assertThrows(IllegalStateException.class, () -> check(env));
        }
    }
    @Test void permitsLocalMock() {
        var env = new MockEnvironment().withProperty("app.mock.wechat-enabled", "true");
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> check(env));
    }
    @Test void permitsMockWithDefaultLocalProfile() {
        var env = new MockEnvironment().withProperty("app.mock.wechat-enabled", "true");
        env.setActiveProfiles(); env.setDefaultProfiles("local");
        assertDoesNotThrow(() -> check(env));
    }
    @Test void rejectsMockWithoutLocalProfile() {
        for (String profile : new String[]{"staging", "test"}) {
            var env = new MockEnvironment().withProperty("app.mock.wechat-enabled", "true");
            env.setActiveProfiles(profile);
            assertThrows(IllegalStateException.class, () -> check(env));
        }
    }
    @Test void rejectsMockWithNonLocalDefaultProfile() {
        var env = new MockEnvironment().withProperty("app.mock.wechat-enabled", "true");
        env.setActiveProfiles(); env.setDefaultProfiles("staging");
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void rejectsProductionWithLocalMock() {
        var env = production().withProperty("app.mock.wechat-enabled", "true");
        env.setActiveProfiles("production", "local");
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void checksProductionDefaultProfile() {
        var env = production().withProperty("app.seed.enabled", "true");
        env.setActiveProfiles(); env.setDefaultProfiles("production");
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void rejectsBootstrapAndActivationBothEnabled() {
        var env = new MockEnvironment()
                .withProperty("app.bootstrap.enabled", "true")
                .withProperty("app.activation.enabled", "true")
                .withProperty("spring.main.web-application-type", "none");
        env.setActiveProfiles("local");
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void rejectsActivationInWebMode() {
        var env = new MockEnvironment().withProperty("app.activation.enabled", "true");
        env.setActiveProfiles("local");
        assertThrows(IllegalStateException.class, () -> check(env));
    }
    @Test void permitsActivationNonWebMode() {
        var env = new MockEnvironment()
                .withProperty("app.activation.enabled", "true")
                .withProperty("spring.main.web-application-type", "none");
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> check(env));
    }
}
