package cn.dental.identity.integration;

/**
 * 把客户端 code 换成服务端身份。真实实现 {@link WechatIdentityClient} 走官方 code2session；
 * 本地替身 {@link LocalWechatMock} 只认预定义合成 code。二者由 profile/mock 开关二选一注入。
 */
public interface WechatCodeResolver {
    WechatResolvedIdentity resolve(String code);
}
