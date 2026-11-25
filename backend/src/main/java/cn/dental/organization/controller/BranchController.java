package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.BranchService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/branches")
public class BranchController {
    private final BranchService branches;

    public BranchController(BranchService branches) {
        this.branches = branches;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(branches.list(actor, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody Create body,
            HttpServletRequest request) {
        Map<String, Object> data = branches.create(actor,
            new BranchService.CreateCommand(
                body.code(), body.name(), body.address(), body.phone(), body.license_no(),
                body.license_valid_to(), body.opening_hours(), body.manager_id()),
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
            .body(new ApiResponse<>(branches.detail(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patch(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(branches.patch(actor, id, body, idempotencyKey),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VersionBody body,
            HttpServletRequest request) {
        return mutation(branches.submit(actor, id, body.expected_version(), idempotencyKey), request);
    }

    @PostMapping("/{id}/verify")
    public ResponseEntity<ApiResponse<Map<String, Object>>> verify(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VerifyBody body,
            HttpServletRequest request) {
        return mutation(branches.verify(actor, id, body.expected_version(), body.verification_basis(),
            body.license_no_expiry_confirmed(), idempotencyKey), request);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disable(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DisableBody body,
            HttpServletRequest request) {
        return mutation(branches.disable(actor, id, body.expected_version(), body.reason(), idempotencyKey), request);
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reopen(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VersionBody body,
            HttpServletRequest request) {
        return mutation(branches.reopen(actor, id, body.expected_version(), idempotencyKey), request);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> mutation(
            Map<String, Object> data, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    public record VersionBody(
            @NotNull @Min(1) Integer expected_version) {
        @Override
        public String toString() {
            return "BranchVersionBody[redacted]";
        }
    }

    public record VerifyBody(
            @NotNull @Min(1) Integer expected_version,
            @NotBlank @Size(max = 300) String verification_basis,
            Boolean license_no_expiry_confirmed) {
        @Override
        public String toString() {
            return "BranchVerifyBody[redacted]";
        }
    }

    public record DisableBody(
            @NotNull @Min(1) Integer expected_version,
            @NotBlank @Size(max = 300) String reason) {
        @Override
        public String toString() {
            return "BranchDisableBody[redacted]";
        }
    }

    public record Create(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 300) String address,
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 80) String license_no,
            @Size(max = 10) String license_valid_to,
            @NotBlank @Size(max = 200) String opening_hours,
            @NotBlank @Pattern(regexp = "[1-9]\\d*") @Size(max = 19) String manager_id) {
        @Override
        public String toString() {
            return "BranchCreate[redacted]";
        }
    }
}
