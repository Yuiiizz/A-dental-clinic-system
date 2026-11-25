package cn.dental.identity.controller;
import cn.dental.identity.application.*;
import cn.dental.shared.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/auth")
public class StaffAuthController {
    private final StaffAuthService auth; private final LoginThrottle throttle; private final boolean secure;
    public StaffAuthController(StaffAuthService auth,LoginThrottle throttle,@Value("${server.servlet.session.cookie.secure:true}") boolean secure){this.auth=auth;this.throttle=throttle;this.secure=secure;}
    public record Login(@NotBlank @Size(max=100) String login_name,@NotBlank @Size(max=72) String password){@Override public String toString(){return "Login[redacted]";}}
    public record Change(@NotBlank @Size(max=72) String current_password,@NotBlank @Size(max=72) String new_password){@Override public String toString(){return "Change[redacted]";}}
    @PostMapping("/staff-login") public ResponseEntity<ApiResponse<Map<String,Object>>> login(@Valid @RequestBody Login body,HttpServletRequest request){
        if("cross-site".equals(request.getHeader("Sec-Fetch-Site")))throw new ApiException(ApiErrorCode.FORBIDDEN);
        throttle.check(body.login_name().strip().toLowerCase(java.util.Locale.ROOT),request.getRemoteAddr());
        return response(auth.login(body.login_name(),body.password()),request);
    }
    @PostMapping("/password-change") public ResponseEntity<ApiResponse<Map<String,Object>>> change(@AuthenticationPrincipal SessionService.Principal principal,@Valid @RequestBody Change body,HttpServletRequest request){
        throttle.check("change:"+principal.accountId(),request.getRemoteAddr());
        return response(auth.change(principal,body.current_password(),body.new_password()),request);
    }
    private ResponseEntity<ApiResponse<Map<String,Object>>> response(StaffAuthService.LoginResult result,HttpServletRequest request){
        String cookie=ResponseCookie.from("A_CLINIC_SESSION",result.session().token()).httpOnly(true).secure(secure).sameSite("Lax").path("/").build().toString();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(new ApiResponse<>(Map.of("must_change_password",result.mustChangePassword(),"csrf_token",result.session().csrfToken()),RequestTraceFilter.requestId(request)));
    }
}
