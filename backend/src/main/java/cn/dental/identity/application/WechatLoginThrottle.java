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
 * 微信换身份的进程内固定窗口限流，独立于员工登录限流（不共享 IP 预算），防止未知 code
 * 无限消耗外部接口或数据库。仅按直接连接 IP 计数，与员工限流同款取舍：反向代理下需按可信代理配置。
 */
@Component
public class WechatLoginThrottle {
    private final Clock clock;
    private final Map<String,Window> windows=new HashMap<>();
    private record Window(Instant until,int count){}
    public WechatLoginThrottle(Clock clock){this.clock=clock;}
    public synchronized void check(String remoteAddress){
        Instant now=clock.instant();
        windows.entrySet().removeIf(e->!e.getValue().until().isAfter(now));
        String key="ip:"+SessionService.hash(remoteAddress);
        Window w=windows.get(key);
        if((w!=null&&w.count()>=30)||(w==null&&windows.size()>=10000))throw new ApiException(ApiErrorCode.RATE_LIMITED);
        windows.put(key,new Window(w==null?now.plus(Duration.ofMinutes(15)):w.until(),w==null?1:w.count()+1));
    }
}
