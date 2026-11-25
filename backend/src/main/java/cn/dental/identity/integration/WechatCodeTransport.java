package cn.dental.identity.integration;

import java.io.IOException;

/**
 * 微信 code2session 交换的底层 HTTP 传输。真实实现固定官方 HTTPS 地址、校验证书、
 * 显式连接/读取超时且不跟随重定向；测试用可控替身，绝不访问真实微信。
 */
@FunctionalInterface
public interface WechatCodeTransport {
    /** 返回原始 JSON 正文；网络失败/超时以 IOException 表示，调用方不得把原始正文外传。 */
    String exchange(String url) throws IOException;
}
