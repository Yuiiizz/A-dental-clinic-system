package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.StaffService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/staff-members")
public class StaffController {
    private final StaffService staff;

    public StaffController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(staff.list(actor, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody Create body,
            HttpServletRequest request) {
        StaffService.CredentialCommand credential = null;
        if (body.credential() != null) {
            credential = new StaffService.CredentialCommand(
                body.credential().credential_type(),
                body.credential().certificate_no(),
                body.credential().valid_from(),
                body.credential().valid_to(),
                body.credential().no_expiry_confirmed());
        }
        Map<String, Object> data = staff.create(actor,
            new StaffService.CreateCommand(body.name(), body.staff_type(), body.job_title(),
                body.practice_no(), credential),
            idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            HttpServletRequest request) {
        if (id <= 0) throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(staff.detail(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/{id}/credentials")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addCredential(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody Credential body,
            HttpServletRequest request) {
        if (id <= 0) throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        var command = new StaffService.CredentialCommand(body.credential_type(), body.certificate_no(),
            body.valid_from(), body.valid_to(), body.no_expiry_confirmed());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(staff.addCredential(actor, id, command, idempotencyKey),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/{id}/credentials/{credentialId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewCredential(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @PathVariable long credentialId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReviewBody body,
            HttpServletRequest request) {
        if (id <= 0 || credentialId <= 0) {
            throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        }
        var command = new StaffService.ReviewCommand(body.decision(), body.basis(), body.expected_version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(staff.reviewCredential(actor, id, credentialId, command, idempotencyKey),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/{id}/credentials/{credentialId}/revoke")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeCredential(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @PathVariable long credentialId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RevokeBody body,
            HttpServletRequest request) {
        if (id <= 0 || credentialId <= 0) {
            throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        }
        var command = new StaffService.ReviewCommand("rejected", body.basis(), body.expected_version());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(staff.revokeCredential(actor, id, credentialId, command, idempotencyKey),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/{id}/credentials/{credentialId}/reviews")
    public ResponseEntity<?> credentialHistory(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @PathVariable long credentialId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        if (id <= 0 || credentialId <= 0) {
            throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(staff.credentialHistory(actor, id, credentialId, cursor, limit));
    }

    @GetMapping("/{id}/credentials/{credentialId}/facts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> credentialFacts(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @PathVariable long credentialId,
            HttpServletRequest request) {
        if (id <= 0 || credentialId <= 0) {
            throw new cn.dental.shared.web.ApiException(cn.dental.shared.web.ApiErrorCode.NOT_FOUND);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(staff.credentialFacts(actor, id, credentialId),
                RequestTraceFilter.requestId(request)));
    }

    public record Create(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 20) String staff_type,
            @NotBlank @Size(max = 60) String job_title,
            @Size(max = 60) String practice_no,
            @Valid Credential credential) {
        @Override
        public String toString() {
            return "StaffCreate[redacted]";
        }
    }

    public record Credential(
            @NotBlank @Size(max = 40) String credential_type,
            @NotBlank @Size(max = 80) String certificate_no,
            @Size(max = 10) String valid_from,
            @Size(max = 10) String valid_to,
            Boolean no_expiry_confirmed) {
        @Override
        public String toString() {
            return "StaffCredential[redacted]";
        }
    }

    public record RevokeBody(
            @NotBlank @Size(max = 300) String basis,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) Integer expected_version) {
        @Override public String toString() { return "StaffCredentialRevoke[redacted]"; }
    }

    public record ReviewBody(
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "verified|rejected") String decision,
            @NotBlank @Size(max = 300) String basis,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(1) Integer expected_version) {
        @Override
        public String toString() {
            return "StaffCredentialReview[redacted]";
        }
    }
}
