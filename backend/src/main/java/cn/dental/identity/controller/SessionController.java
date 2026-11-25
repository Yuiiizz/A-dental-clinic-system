package cn.dental.identity.controller;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.*;
@RestController
@RequestMapping("/api/v1/auth")
public class SessionController {
    private final SessionService sessions;
    private final boolean secure;
    public SessionController(SessionService sessions,@org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:true}") boolean secure){this.sessions=sessions;this.secure=secure;}
    @PostMapping("/logout") public ResponseEntity<Void> logout(@AuthenticationPrincipal SessionService.Principal principal){
        sessions.revoke(principal);
        var response=ResponseEntity.noContent();
        if(SessionService.STAFF.equals(principal.clientType()))response.header(HttpHeaders.SET_COOKIE,ResponseCookie.from("A_CLINIC_SESSION","").httpOnly(true).secure(secure).sameSite("Lax").path("/").maxAge(0).build().toString());
        return response.build();
    }
}
