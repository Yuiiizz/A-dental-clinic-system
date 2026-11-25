package cn.dental.identity.controller;

import cn.dental.identity.application.PatientBindingTokenService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前台签发/重签患者绑定凭证（staff_web，Cookie + CSRF，由 SessionAuthenticationFilter 统一校验）。
 * 请求 DTO 严格对应契约（additionalProperties:false 由全局 FAIL_ON_UNKNOWN_PROPERTIES 兜底），
 * 员工/患者姓名电话仅用于匹配档案、不能证明本人；verification_method + in_person 为结构化线下核验留痕。
 */
@RestController
@RequestMapping("/api/v1/staff/patients/{id}/binding-token")
public class PatientBindingController {
    private final PatientBindingTokenService service;

    public PatientBindingController(PatientBindingTokenService service) { this.service = service; }

    public record IssueRequest(
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String branch_id,
            @NotBlank @Size(max=60) String patient_name,
            @NotBlank @Size(max=30) String patient_phone,
            String appointment_id,
            @NotBlank @Pattern(regexp="visit_evidence|id_document") String verification_method,
            @NotNull Boolean in_person) {
        @Override public String toString() { return "BindingTokenRequest[redacted]"; }
    }

    public record ReissueRequest(
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String branch_id,
            @NotBlank @Size(max=60) String patient_name,
            @NotBlank @Size(max=30) String patient_phone,
            String appointment_id,
            @NotBlank @Pattern(regexp="visit_evidence|id_document") String verification_method,
            @NotNull Boolean in_person,
            @NotBlank String previous_challenge_ref) {
        @Override public String toString() { return "BindingTokenReissueRequest[redacted]"; }
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> issue(
            @AuthenticationPrincipal SessionService.Principal p,
            @PathVariable long id,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey,
            @Valid @RequestBody IssueRequest body, HttpServletRequest request) {
        var result = service.issue(p, id, Long.parseLong(body.branch_id()), body.patient_name(), body.patient_phone(),
            body.appointment_id(), body.verification_method(), body.in_person(), idempotencyKey);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(result.data(), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<Map<String,Object>>> reissue(
            @AuthenticationPrincipal SessionService.Principal p,
            @PathVariable long id,
            @RequestHeader(value="Idempotency-Key", required=false) String idempotencyKey,
            @Valid @RequestBody ReissueRequest body, HttpServletRequest request) {
        var result = service.reissue(p, id, Long.parseLong(body.branch_id()), body.patient_name(), body.patient_phone(),
            body.appointment_id(), body.verification_method(), body.in_person(), body.previous_challenge_ref(), idempotencyKey);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(result.data(), RequestTraceFilter.requestId(request)));
    }
}
