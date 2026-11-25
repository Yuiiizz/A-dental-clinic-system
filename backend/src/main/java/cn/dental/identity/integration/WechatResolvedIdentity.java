package cn.dental.identity.integration;

/** 服务端换取的微信身份，仅含小程序标识与 openid；绝不携带 session_key。toString 脱敏，不输出身份值。 */
public record WechatResolvedIdentity(String appId, String openId) {
    @Override public String toString(){ return "WechatResolvedIdentity[redacted]"; }
}
