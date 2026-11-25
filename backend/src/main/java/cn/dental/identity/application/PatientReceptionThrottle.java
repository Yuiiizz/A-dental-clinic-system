package cn.dental.identity.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 前台患者精确检索/选择的进程内固定窗口限流，按操作账号+门店隔离。 */
@Component
public class PatientReceptionThrottle {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(Instant until, int count) {}

    public PatientReceptionThrottle(Clock clock) { this.clock = clock; }

    public synchronized void check(long accountId, long branchId) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        String key = accountId + ":" + branchId;
        Window w = windows.get(key);
        if ((w != null && w.count() >= 30) || (w == null && windows.size() >= 10000))
            throw new ApiException(ApiErrorCode.RATE_LIMITED);
        windows.put(key, new Window(w == null ? now.plus(Duration.ofMinutes(1)) : w.until(),
            w == null ? 1 : w.count() + 1));
    }

    public synchronized void reset() { windows.clear(); }
}
