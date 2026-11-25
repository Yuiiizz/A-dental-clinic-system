package cn.dental.identity.integration;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 微信 code2session 真实适配器。用服务端配置的 AppID/Secret 换身份，绝不接受客户端自报 openid。
 * 固定官方 HTTPS 地址（经 WechatCodeTransport 保证证书校验、超时与不跟随重定向）；未配置凭证时
 * 明确报配置缺失，不悄悄降级。不保存 session_key、不把 openid/secret/code 写日志或异常。
 * 仅当未开启本地 mock 时启用（生产默认关闭 mock）。
 */
@Service
@ConditionalOnProperty(name="app.mock.wechat-enabled",havingValue="false",matchIfMissing=true)
public class WechatIdentityClient implements WechatCodeResolver {
    private static final String OFFICIAL_URL="https://api.weixin.qq.com/sns/jscode2session";
    // 长度上限取自数据字典：wechat_identity.app_id / identity_challenge.app_id varchar(80)、open_id varchar(128)。
    private static final int APP_ID_MAX=80;
    private static final int OPEN_ID_MAX=128;
    private final WechatCodeTransport transport;
    private final JsonMapper json;
    private final String appId;
    private final String secret;
    public WechatIdentityClient(WechatCodeTransport transport,JsonMapper json,
            @Value("${app.wechat.app-id:}") String appId,
            @Value("${app.wechat.app-secret:}") String secret){
        this.transport=transport;this.json=json;this.appId=appId;this.secret=secret;
    }
    public boolean configured(){return appId!=null&&!appId.isBlank()&&secret!=null&&!secret.isBlank();}

    @Override public WechatResolvedIdentity resolve(String code){
        if(!configured())throw new ApiException(ApiErrorCode.WECHAT_NOT_CONFIGURED);
        if(appId.length()>APP_ID_MAX)throw new ApiException(ApiErrorCode.WECHAT_NOT_CONFIGURED); // 配置超限，安全处理，不输出配置值
        String url=OFFICIAL_URL+"?appid="+enc(appId)+"&secret="+enc(secret)+"&js_code="+enc(code)+"&grant_type=authorization_code";
        String body;
        try{body=transport.exchange(url);}
        catch(java.io.IOException e){throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED);} // 超时/网络失败，不回传原文
        return parse(body);
    }

    private WechatResolvedIdentity parse(String body){
        try {
            var node=json.readTree(body);
            if(node==null || !node.isObject())
                throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED); // 顶层必须为对象
            if(node.has("errcode")){
                var err=node.get("errcode");
                // errcode 若出现必须是整数 0；null/字符串/布尔/数组/对象/小数/非零均拒绝，避免畸形值被宽松转成 0。
                if(err.isNull() || !err.isIntegralNumber() || err.bigIntegerValue().signum()!=0)
                    throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED);
            }
            if(!node.has("openid") || node.get("openid").isNull())
                throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED); // 缺 openid
            String openid=node.get("openid").textValue();
            if(openid==null || openid.isBlank() || openid.length()>OPEN_ID_MAX)
                throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED); // 非字符串/空白/超长
            return new WechatResolvedIdentity(appId,openid);
        } catch(ApiException e){throw e;}
        catch(RuntimeException e){throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED);} // 非JSON/畸形
    }

    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
