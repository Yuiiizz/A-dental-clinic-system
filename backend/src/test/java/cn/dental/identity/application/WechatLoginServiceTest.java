package cn.dental.identity.application;

import cn.dental.identity.integration.WechatCodeResolver;
import cn.dental.identity.integration.WechatResolvedIdentity;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.junit.jupiter.api.Assertions.*;

/** 编排层单元测试：换身份失败绝不进入落地，因此不会留下半条注册上下文或会话。 */
class WechatLoginServiceTest {
    @Test void networkFailureLeavesNoContextOrSession() {
        WechatCodeResolver failing=code->{throw new ApiException(ApiErrorCode.WECHAT_EXCHANGE_FAILED);};
        WechatBindingService binding=Mockito.mock(WechatBindingService.class);
        WechatLoginThrottle throttle=Mockito.mock(WechatLoginThrottle.class);
        var service=new WechatLoginService(failing,binding,throttle);
        assertThrows(ApiException.class,()->service.login("c","127.0.0.1"));
        Mockito.verify(binding,Mockito.never()).bind(Mockito.anyString(),Mockito.anyString());
    }
    @Test void resolvedIdentityFlowsToBinding() {
        WechatCodeResolver resolver=code->new WechatResolvedIdentity("app","open");
        WechatBindingService binding=Mockito.mock(WechatBindingService.class);
        WechatLoginThrottle throttle=Mockito.mock(WechatLoginThrottle.class);
        var outcome=new WechatLoginService.WechatLoginOutcome.Registration("tok",Instant.now());
        Mockito.when(binding.bind("app","open")).thenReturn(outcome);
        var service=new WechatLoginService(resolver,binding,throttle);
        assertSame(outcome,service.login("c","127.0.0.1"));
        Mockito.verify(throttle).check("127.0.0.1");
    }
}
