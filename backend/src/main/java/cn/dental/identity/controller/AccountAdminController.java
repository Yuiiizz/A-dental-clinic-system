package cn.dental.identity.controller;
import cn.dental.identity.application.*;
import cn.dental.shared.web.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1")
public class AccountAdminController {
    private final AuthorizationService authorization;private final AccountAdminService accounts;
    public AccountAdminController(AuthorizationService authorization,AccountAdminService accounts){this.authorization=authorization;this.accounts=accounts;}
    public record Disable(@NotBlank @Size(max=300) String reason){}
    @GetMapping("/auth/me") public ResponseEntity<ApiResponse<Map<String,Object>>> me(@AuthenticationPrincipal SessionService.Principal p,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(authorization.me(p),RequestTraceFilter.requestId(request)));
    }
    @GetMapping("/staff/accounts") public ResponseEntity<PageResult<Map<String,Object>>> list(@AuthenticationPrincipal SessionService.Principal p,@RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list(p,cursor,limit));
    }
    public record Create(@NotBlank String staff_id,@NotBlank @Size(max=100) String login_name){@Override public String toString(){return "Create[redacted]";}}
    @PostMapping("/staff/accounts") public ResponseEntity<ApiResponse<Map<String,Object>>> create(@AuthenticationPrincipal SessionService.Principal p,@RequestHeader(value="Idempotency-Key",required=false) String idempotencyKey,@Valid @RequestBody Create body,HttpServletRequest request){
        var result=accounts.create(p,body.staff_id(),body.login_name(),idempotencyKey);
        return ResponseEntity.status(result.replayed()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(new ApiResponse<>(result.account(),RequestTraceFilter.requestId(request)));
    }
    @PostMapping("/staff/accounts/{id}/disable") public ApiResponse<Map<String,Object>> disable(@AuthenticationPrincipal SessionService.Principal p,@PathVariable long id,@Valid @RequestBody Disable body,HttpServletRequest request){
        accounts.disable(p,id,body.reason());return new ApiResponse<>(Map.of("account_id",Long.toString(id),"enabled",false),RequestTraceFilter.requestId(request));
    }

    @GetMapping("/staff/accounts/{id}/role-scopes") public ResponseEntity<ApiResponse<Map<String,Object>>> roleScopes(@AuthenticationPrincipal SessionService.Principal p,@PathVariable long id,HttpServletRequest request){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApiResponse<>(accounts.roleScopes(p,id),RequestTraceFilter.requestId(request)));
    }

    public record Roles(@NotNull @Min(1) Integer expected_version,@NotNull @Size(max=7) java.util.List<AccountAdminService.Grant> roles){}
    @PutMapping("/staff/accounts/{id}/role-scopes") public ApiResponse<Map<String,Object>> roles(@AuthenticationPrincipal SessionService.Principal p,@PathVariable long id,@Valid @RequestBody Roles body,HttpServletRequest request){
        return new ApiResponse<>(accounts.setRoles(p,id,body.expected_version(),body.roles()),RequestTraceFilter.requestId(request));
    }
}
