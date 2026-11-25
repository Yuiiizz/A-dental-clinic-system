package cn.dental.identity.integration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WechatResolvedIdentityTest {
    @Test void toStringDoesNotLeakIdentityValues() {
        var identity = new WechatResolvedIdentity("wx-app-secret-id", "openid-sensitive-value");
        String s = identity.toString();
        assertFalse(s.contains("wx-app-secret-id"));
        assertFalse(s.contains("openid-sensitive-value"));
    }

    @Test void accessorsStillExposeValues() {
        var identity = new WechatResolvedIdentity("app", "open");
        assertEquals("app", identity.appId());
        assertEquals("open", identity.openId());
    }
}
