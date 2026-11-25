package cn.dental.identity.config;

import cn.dental.identity.integration.HttpWechatCodeTransport;
import cn.dental.identity.integration.WechatCodeTransport;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 微信交换 HTTP 客户端：固定 HTTPS、证书校验（JDK 默认）、显式超时、不跟随重定向。 */
@Configuration
public class WechatClientConfig {
    @Bean public HttpClient wechatHttpClient(){
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
    @Bean public WechatCodeTransport wechatCodeTransport(HttpClient wechatHttpClient){
        return new HttpWechatCodeTransport(wechatHttpClient);
    }
}
