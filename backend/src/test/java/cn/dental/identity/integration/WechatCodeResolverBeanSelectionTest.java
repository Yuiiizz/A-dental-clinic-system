package cn.dental.identity.integration;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 mock 与真实适配器的 Bean 选择：仅 local profile 且开关开启时才选 mock，绝不静默切换。 */
class WechatCodeResolverBeanSelectionTest {
    @Configuration
    @Import({LocalWechatMock.class, WechatIdentityClient.class})
    static class ResolverConfig {
        @Bean WechatCodeTransport transport(){ return url -> "{}"; }
        @Bean JsonMapper json(){ return JsonMapper.builder().build(); }
    }

    private WechatCodeResolver resolver(String profile, String mockEnabled) {
        var ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().setActiveProfiles(profile);
        ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("t", Map.of("app.mock.wechat-enabled", mockEnabled)));
        ctx.register(ResolverConfig.class);
        ctx.refresh();
        try {
            return ctx.getBean(WechatCodeResolver.class);
        } finally {
            ctx.close();
        }
    }

    @Test void localProfileWithMockSelectsMockBean() {
        assertInstanceOf(LocalWechatMock.class, resolver("local", "true"));
    }

    @Test void localProfileWithoutMockSelectsRealAdapter() {
        assertInstanceOf(WechatIdentityClient.class, resolver("local", "false"));
    }

    @Test void nonLocalProfileWithoutMockSelectsRealAdapter() {
        assertInstanceOf(WechatIdentityClient.class, resolver("test", "false"));
    }
}
