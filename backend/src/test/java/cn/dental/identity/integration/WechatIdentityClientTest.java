package cn.dental.identity.integration;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实适配器 + 可控传输替身；不访问真实微信、不生成真实凭证。 */
class WechatIdentityClientTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static WechatIdentityClient client(WechatCodeTransport transport){
        return new WechatIdentityClient(transport,JSON,"appid","secret");
    }
    @Test void exchangesCodeAndReturnsServerOpenId()throws Exception {
        var c=client(url->{
            assertTrue(url.startsWith("https://api.weixin.qq.com/sns/jscode2session"),url);
            assertTrue(url.contains("appid=appid"));
            assertTrue(url.contains("grant_type=authorization_code"));
            assertTrue(url.contains("js_code=test-code")); // 连字符不编码
            return "{\"openid\":\"open-1\",\"session_key\":\"SK\"}";
        });
        var identity=c.resolve("test-code");
        assertEquals("appid",identity.appId());
        assertEquals("open-1",identity.openId());
    }
    @Test void wechatBusinessErrorIsSafe() {
        var c=client(url->"{\"errcode\":40029,\"errmsg\":\"invalid code\"}");
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
    }
    @Test void timeoutIsSafe() {
        var c=client(url->{throw new HttpTimeoutException("timeout");});
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
    }
    @Test void malformedBodyIsSafe() {
        var c=client(url->"not-json");
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
    }
    @Test void missingOpenIdIsSafe() {
        var c=client(url->"{\"errcode\":0}");
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
    }
    @Test void missingConfigDoesNotFallBack() {
        var c=new WechatIdentityClient(url->"{}",JSON,"","");
        assertFalse(c.configured());
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_NOT_CONFIGURED,e.code());
    }
    @Test void errcodeZeroIsAllowed() {
        var c=client(url->"{\"errcode\":0,\"openid\":\"open-1\"}");
        assertEquals("open-1",c.resolve("c").openId());
    }
    @Test void malformedErrcodeTypesAreRejected() {
        for (String body : new String[]{
            "{\"errcode\":\"0\",\"openid\":\"open-1\"}",
            "{\"errcode\":true,\"openid\":\"open-1\"}",
            "{\"errcode\":[0],\"openid\":\"open-1\"}",
            "{\"errcode\":{\"a\":1},\"openid\":\"open-1\"}",
            "{\"errcode\":0.0,\"openid\":\"open-1\"}",
            "{\"errcode\":null,\"openid\":\"open-1\"}"}) {
            var c=client(url->body);
            var e=assertThrows(ApiException.class,()->c.resolve("c"),body);
            assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code(),body);
        }
    }
    @Test void oversizedNonzeroErrcodeCannotWrapToZero() {
        for (String value : new String[]{"18446744073709551616", "-18446744073709551616"}) {
            var c=client(url->"{\"errcode\":"+value+",\"openid\":\"open-1\"}");
            var e=assertThrows(ApiException.class,()->c.resolve("c"));
            assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
        }
    }
    @Test void nonObjectTopLevelIsRejected() {
        for (String body : new String[]{"[]","123","\"x\""}) {
            var c=client(url->body);
            var e=assertThrows(ApiException.class,()->c.resolve("c"),body);
            assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code(),body);
        }
    }
    @Test void wrongOpenIdTypeIsRejected() {
        for (String body : new String[]{"{\"openid\":123}","{\"openid\":[\"x\"]}","{\"openid\":{\"a\":1}}"}) {
            var c=client(url->body);
            var e=assertThrows(ApiException.class,()->c.resolve("c"),body);
            assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code(),body);
        }
    }
    @Test void overlongOpenIdIsRejected() {
        var c=client(url->"{\"openid\":\""+"x".repeat(129)+"\"}");
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_EXCHANGE_FAILED,e.code());
    }
    @Test void overlongConfiguredAppIdIsRejected() {
        var c=new WechatIdentityClient(url->"{}",JSON,"a".repeat(81),"secret");
        var e=assertThrows(ApiException.class,()->c.resolve("c"));
        assertEquals(ApiErrorCode.WECHAT_NOT_CONFIGURED,e.code());
    }
}
