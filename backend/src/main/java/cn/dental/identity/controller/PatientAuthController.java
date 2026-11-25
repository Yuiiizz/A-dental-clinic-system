package cn.dental.identity.controller;

import cn.dental.identity.application.PatientBindingService;
import cn.dental.identity.application.PatientRegistrationExecutor.Outcome;
import cn.dental.identity.application.PatientRegistrationService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 患者本人自助建档入口（仅此一条公开路由，无员工 Cookie、无员工角色）。
 * 请求 DTO 严格对应 PatientSelfRegisterRequest 契约：additionalProperties=false 由全局
 * FAIL_ON_UNKNOWN_PROPERTIES 兜底，客户端传入 openid/app_id/account_id/patient_id/角色/启用状态
 * 一律 UNKNOWN_FIELD 拒绝。响应不落缓存（Cache-Control: no-store）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PatientAuthController {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final PatientRegistrationService registration;
    private final PatientBindingService binding;

    public PatientAuthController(PatientRegistrationService registration, PatientBindingService binding) {
        this.registration = registration; this.binding = binding;
    }

    public record PatientSelfRegisterRequest(
            @NotBlank @Size(max=256) String registration_token,
            @NotBlank @Size(max=60) String name,
            @NotBlank @Size(max=30) String phone,
            String birth_date,
            @NotBlank @Pattern(regexp="未申报|无已知|有|不确定") String allergy_status,
            String allergy_notes) {
        @Override public String toString() { return "PatientSelfRegisterRequest[redacted]"; }
    }

    @PostMapping("/patient-register")
    public ResponseEntity<ApiResponse<Map<String,Object>>> register(
            @Valid @RequestBody PatientSelfRegisterRequest body, HttpServletRequest request) {
        Outcome outcome = registration.register(
            body.registration_token(), body.name(), body.phone(), body.birth_date(),
            body.allergy_status(), body.allergy_notes(), request.getRemoteAddr());
        Map<String,Object> data = Map.of(
            "session_token", outcome.sessionToken(),
            "patient_id", outcome.patientId(),
            "expires_at", ISO.format(outcome.expiresAt()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    public record PatientBindRequest(
            @NotBlank @Size(max=256) String registration_token,
            @NotBlank @Size(max=256) String binding_token) {
        @Override public String toString() { return "PatientBindRequest[redacted]"; }
    }

    @PostMapping("/patient-bind")
    public ResponseEntity<ApiResponse<Map<String,Object>>> bind(
            @Valid @RequestBody PatientBindRequest body, HttpServletRequest request) {
        cn.dental.identity.application.PatientBindingExecutor.Outcome outcome =
            binding.bind(body.registration_token(), body.binding_token(), request.getRemoteAddr());
        Map<String,Object> data = Map.of(
            "session_token", outcome.sessionToken(),
            "patient_id", outcome.patientId(),
            "expires_at", ISO.format(outcome.expiresAt()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }
}
