package cn.dental.identity.integration;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 真实微信交换传输：固定 HTTPS + 证书校验（JDK 默认）+ 显式超时 + 不跟随重定向。 */
public final class HttpWechatCodeTransport implements WechatCodeTransport {
    private final HttpClient http;
    public HttpWechatCodeTransport(HttpClient http){this.http=http;}
    @Override public String exchange(String url)throws IOException{
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IOException("wechat http status "+response.statusCode());
            return response.body();
        } catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IOException("wechat exchange interrupted",e);
        }
    }
}
