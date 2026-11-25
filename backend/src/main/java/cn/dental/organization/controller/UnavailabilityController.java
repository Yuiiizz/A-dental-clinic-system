package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.UnavailabilityService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff")
public class UnavailabilityController {
    private final UnavailabilityService service;

    public UnavailabilityController(UnavailabilityService service) { this.service = service; }

    @GetMapping("/dentist-unavailability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> doctorList(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.doctorList(actor, state, limit),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/dentist-unavailability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> doctor(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DoctorBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.doctor(actor, parseId(body.doctor_id()),
                body.starts_at(), body.ends_at(), body.reason(), key),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/resource-unavailability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resource(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ResourceBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.resource(actor, parseId(body.resource_id()), body.kind(),
                body.starts_at(), body.ends_at(), body.reason(), key),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/resource-unavailability")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resourceList(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam String branch_id,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.resourceList(actor, parseId(branch_id), state, limit),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/resources")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resourceOptions(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam String branch_id,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.resourceOptions(actor, parseId(branch_id)),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/resource-unavailability/{id}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resolve(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ResolveBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.resolveResource(actor, id,
                body.resolve_type(), body.resolve_basis(), key), RequestTraceFilter.requestId(request)));
    }

    private static long parseId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.VALIDATION_FAILED);
    }

    public record DoctorBody(
            @NotBlank @Pattern(regexp = "[1-9]\\d*") String doctor_id,
            @NotBlank String starts_at, @NotBlank String ends_at,
            @NotBlank @Size(max = 300) String reason) {
        @Override public String toString() { return "DoctorUnavailabilityBody[redacted]"; }
    }

    @PostMapping("/dentist-unavailability/{id}/resolve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> doctorResolve(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody DoctorResolveBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.resolveDoctor(actor, id, body.resolve_basis(), key),
                RequestTraceFilter.requestId(request)));
    }

    public record DoctorResolveBody(
            @NotBlank @Size(max = 300) String resolve_basis) {
        @Override public String toString() { return "DoctorResolveBody[redacted]"; }
    }

    public record ResourceBody(
            @NotBlank @Pattern(regexp = "[1-9]\\d*") String resource_id,
            @NotBlank @Pattern(regexp = "维护|故障") String kind,
            @NotBlank String starts_at, @NotBlank String ends_at,
            @NotBlank @Size(max = 200) String reason) {
        @Override public String toString() { return "ResourceUnavailabilityBody[redacted]"; }
    }

    public record ResolveBody(
            @NotNull @Pattern(regexp = "误登记撤销|已修复") String resolve_type,
            @NotBlank @Size(max = 300) String resolve_basis) {
        @Override public String toString() { return "ResourceResolveBody[redacted]"; }
    }
}
