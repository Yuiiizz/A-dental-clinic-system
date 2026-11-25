package cn.dental.identity.application;

import cn.dental.identity.integration.WechatCodeResolver;
import cn.dental.identity.integration.WechatResolvedIdentity;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 微信登录编排：先在数据库事务外换取身份（外部网络/本地 mock），再交给独立的
 * {@link WechatBindingService} 在事务内做已绑定会话签发或未绑定注册上下文签发，避免
 * 自调用使 @Transactional 失效、也绝不持数据库行锁等待微信网络。换身份失败时不会调用
 * binding，因此不会留下任何半条注册上下文或会话。真实模式未配置凭证时由真实解析器抛
 * WECHAT_NOT_CONFIGURED，绝不回退到 mock。
 */
@Service
public class WechatLoginService {
    private final WechatCodeResolver resolver;
    private final WechatBindingService binding;
    private final WechatLoginThrottle throttle;
    public WechatLoginService(WechatCodeResolver resolver,WechatBindingService binding,WechatLoginThrottle throttle){
        this.resolver=resolver;this.binding=binding;this.throttle=throttle;
    }

    public sealed interface WechatLoginOutcome {
        record Session(String sessionToken,Instant expiresAt) implements WechatLoginOutcome {
            @Override public String toString(){return "WechatSession[redacted]";}
        }
        record Registration(String registrationToken,Instant expiresAt) implements WechatLoginOutcome {
            @Override public String toString(){return "WechatRegistration[redacted]";}
        }
    }

    public WechatLoginOutcome login(String code,String remoteAddress){
        throttle.check(remoteAddress);
        WechatResolvedIdentity identity=resolver.resolve(code);
        return binding.bind(identity.appId(),identity.openId());
    }
}
