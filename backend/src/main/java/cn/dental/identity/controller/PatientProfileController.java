package cn.dental.identity.controller;

import cn.dental.identity.application.PatientProfileService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/profile")
public class PatientProfileController {
    private final PatientProfileService profiles;
    public PatientProfileController(PatientProfileService profiles) { this.profiles=profiles; }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> view(@AuthenticationPrincipal SessionService.Principal actor,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(profiles.view(actor),RequestTraceFilter.requestId(request)));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> update(@AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ChangeBody body,HttpServletRequest request) {
        var change=new PatientProfileService.Change(body.phone(),body.email(),body.emergency_name(),
            body.emergency_phone(),body.allergy_status(),body.allergy_notes(),body.expected_version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(profiles.update(actor,change,key),RequestTraceFilter.requestId(request)));
    }
    public record ChangeBody(@Size(max=30) String phone,@Size(max=120) String email,
            @Size(max=60) String emergency_name,@Size(max=30) String emergency_phone,
            @NotBlank String allergy_status,@Size(max=1000) String allergy_notes,
            @NotNull @Min(1) Integer expected_version) {}
}
