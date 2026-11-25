package cn.dental.identity.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旧档绑定的进程内固定窗口限流，独立于注册、微信登录、员工登录限流预算。作为绑定入口的前置有界 IP 预算：
 * 在任何数据库查询/执行器调用之前检查，超限即 429，保证超限请求不触发数据库探测。可识别凭证的失败由
 * identity_challenge.failed_attempts 独立计数（见 PatientRegistrationChallengeService），与本预算分离。
 */
@Component
public class PatientBindingThrottle {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(Instant until, int count) {}
    public PatientBindingThrottle(Clock clock) { this.clock = clock; }

    public synchronized void check(String remoteAddress) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        String key = "ip:" + SessionService.hash(remoteAddress);
        Window w = windows.get(key);
        if ((w != null && w.count() >= 30) || (w == null && windows.size() >= 10000))
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        windows.put(key, new Window(w == null ? now.plus(Duration.ofMinutes(15)) : w.until(), w == null ? 1 : w.count() + 1));
    }

    /** 测试/运维可见：清空进程内窗口，避免跨用例共享预算。 */
    public synchronized void reset() { windows.clear(); }
}
