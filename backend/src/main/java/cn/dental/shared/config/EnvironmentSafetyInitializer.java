package cn.dental.shared.config;

import java.util.Arrays;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/** Reject unsafe effective production settings before beans or database connections exist. */
public final class EnvironmentSafetyInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        Binder binder = Binder.get(env);
        boolean bootstrap = binder.bind("app.bootstrap.enabled", Boolean.class).orElse(false);
        boolean activation = binder.bind("app.activation.enabled", Boolean.class).orElse(false);
        if (bootstrap && activation) {
            throw new IllegalStateException("Bootstrap and account activation cannot both be enabled");
        }
        if (bootstrap && !"none".equals(env.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Bootstrap requires non-web mode");
        }
        if (activation && !"none".equals(env.getProperty("spring.main.web-application-type"))) {
            throw new IllegalStateException("Account activation requires non-web mode");
        }
        String[] profiles = env.getActiveProfiles().length == 0
                ? env.getDefaultProfiles() : env.getActiveProfiles();
        boolean mockWechat = binder.bind("app.mock.wechat-enabled", Boolean.class).orElse(false);
        boolean seed = binder.bind("app.seed.enabled", Boolean.class).orElse(false);
        if (!Arrays.asList(profiles).contains("production")) {
            // 模拟微信身份仅允许在 local profile 下使用；staging/test/default 等环境开启开关会被拒绝，
            // 不静默切换到真实适配器。
            if (mockWechat && !Arrays.asList(profiles).contains("local")) {
                throw new IllegalStateException("Mock WeChat identity requires the local profile");
            }
            return;
        }
        if (Arrays.asList(profiles).contains("local") || Arrays.asList(profiles).contains("test")) {
            throw new IllegalStateException("Production cannot be combined with local/test profiles");
        }
        if (mockWechat || seed) {
            throw new IllegalStateException("Production forbids mock identity and synthetic seed data");
        }
        if (!binder.bind("server.servlet.session.cookie.secure", Boolean.class).orElse(false)) {
            throw new IllegalStateException("Production requires secure session cookies");
        }
    }
}
