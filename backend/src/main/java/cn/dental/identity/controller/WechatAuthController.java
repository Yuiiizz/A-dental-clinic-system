package cn.dental.identity.controller;

import cn.dental.identity.application.WechatLoginService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class WechatAuthController {
    private static final DateTimeFormatter ISO=DateTimeFormatter.ISO_INSTANT;
    private final WechatLoginService login;
    public WechatAuthController(WechatLoginService login){this.login=login;}

    public record WechatCode(@NotBlank @Size(max=256) String code){
        @Override public String toString(){return "WechatCode[redacted]";}
    }

    @PostMapping("/wechat")
    public ResponseEntity<ApiResponse<Map<String,Object>>> wechat(@Valid @RequestBody WechatCode body,HttpServletRequest request){
        WechatLoginService.WechatLoginOutcome outcome=login.login(body.code(),request.getRemoteAddr());
        Map<String,Object> data=switch(outcome){
            case WechatLoginService.WechatLoginOutcome.Session s ->
                Map.of("kind","session","session_token",s.sessionToken(),"expires_at",ISO.format(s.expiresAt()));
            case WechatLoginService.WechatLoginOutcome.Registration r ->
                Map.of("kind","registration","registration_token",r.registrationToken(),"registration_expires_at",ISO.format(r.expiresAt()));
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ApiResponse<>(data,RequestTraceFilter.requestId(request)));
    }
}
