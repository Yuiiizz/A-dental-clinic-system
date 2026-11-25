package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.CheckinConfigurationService;
import cn.dental.organization.application.CheckinTokenService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/branches")
public class CheckinConfigurationController {
    private final CheckinConfigurationService configurations;
    private final CheckinTokenService tokens;
    public CheckinConfigurationController(CheckinConfigurationService configurations,CheckinTokenService tokens) {
        this.configurations=configurations; this.tokens=tokens;
    }
    @GetMapping("/{id}/booking-config")
    public ResponseEntity<ApiResponse<Map<String,Object>>> view(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(configurations.view(actor,id),RequestTraceFilter.requestId(request)));
    }
    @PutMapping("/{id}/booking-config")
    public ResponseEntity<ApiResponse<Map<String,Object>>> update(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody Change body,HttpServletRequest request) {
        var change=new CheckinConfigurationService.Change(body.self_checkin_enabled(),body.early_minutes(),
            body.late_minutes(),body.slot_step_minutes(),body.expected_version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(configurations.update(actor,id,change,key),RequestTraceFilter.requestId(request)));
    }
    @PostMapping("/{id}/checkin-code")
    public ResponseEntity<ApiResponse<Map<String,Object>>> code(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        CheckinTokenService.Code issued=tokens.issue(actor,id,key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(Map.of("code",issued.code(),"expires_at",issued.expiresAt().toString()),
                RequestTraceFilter.requestId(request)));
    }
    public record Change(@NotNull Boolean self_checkin_enabled,
                         @Min(0) Integer early_minutes,@Min(0) Integer late_minutes,
                         @NotNull @Min(1) Integer slot_step_minutes,
                         @NotNull @Min(1) Integer expected_version) {}
}
