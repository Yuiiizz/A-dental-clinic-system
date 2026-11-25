package cn.dental.identity.application;
import cn.dental.shared.web.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
/** Single-process fixed windows, bounded memory. Reverse proxy must also limit login traffic. */
@Component
public class LoginThrottle {
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(Instant until,int count) {}
    public LoginThrottle(Clock clock){this.clock=clock;}
    public synchronized void check(String name,String remoteAddress) {
        Instant now=clock.instant();windows.entrySet().removeIf(e->!e.getValue().until().isAfter(now));
        consume("name:"+SessionService.hash(name),10,now);
        consume("ip:"+SessionService.hash(remoteAddress),60,now);
    }
    private void consume(String key,int limit,Instant now){
        Window w=windows.get(key);
        if(w!=null && w.count()>=limit || w==null && windows.size()>=10000) throw new ApiException(ApiErrorCode.RATE_LIMITED);
        windows.put(key,new Window(w==null?now.plus(Duration.ofMinutes(15)):w.until(),w==null?1:w.count()+1));
    }
}
