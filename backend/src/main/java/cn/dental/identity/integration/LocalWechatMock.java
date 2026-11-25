package cn.dental.identity.integration;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 显式隔离的本地 mock：仅在 app.mock.wechat-enabled=true 时启用（local/test profile 默认 true），
 * 生产启动守卫会拒绝生产开启 mock。只接受服务端预定义的合成 code，不接受任意客户端身份；
 * 这是本地演示替身，不是真实微信已接通。
 *
 * 合成映射：
 * <ul>
 *   <li>{@code mock-code-bound}   → 已绑定患者的合成 openid（对应 fixture 中的 wechat_identity）。</li>
 *   <li>{@code mock-code-unbound} → 未绑定的合成 openid（触发注册上下文）。</li>
 *   <li>{@code mock-code-expired} → 模拟微信 errcode 40029（code 无效/过期），与未知 code 一样返回安全失败。</li>
 *   <li>{@code mock-code-reused}  → 模拟微信 errcode 40163（code 已被使用），同样返回安全失败。</li>
 * </ul>
 */
@Component
@Profile("local")
@ConditionalOnProperty(name="app.mock.wechat-enabled",havingValue="true")
public class LocalWechatMock implements WechatCodeResolver {
    public static final String APP_ID="mock";
    public static final String BOUND_CODE="mock-code-bound";
    public static final String BOUND_OPEN_ID="mock-openid-bound-0001";
    public static final String UNBOUND_CODE="mock-code-unbound";
    public static final String UNBOUND_OPEN_ID="mock-openid-unbound-0001";
    public static final String EXPIRED_CODE="mock-code-expired";
    public static final String REUSED_CODE="mock-code-reused";

    @Override public WechatResolvedIdentity resolve(String code){
        if(BOUND_CODE.equals(code))return new WechatResolvedIdentity(APP_ID,BOUND_OPEN_ID);
        if(UNBOUND_CODE.equals(code))return new WechatResolvedIdentity(APP_ID,UNBOUND_OPEN_ID);
        // 过期/重复使用/未知 code 一律以真实适配器同款安全失败呈现，不披露具体微信错误细节。
        throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED);
    }
}
