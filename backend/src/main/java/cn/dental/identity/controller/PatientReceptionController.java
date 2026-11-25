package cn.dental.identity.controller;

import cn.dental.identity.application.PatientReceptionService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 前台精确检索、patient-only 建档与短时患者选择凭证。 */
@RestController
@RequestMapping("/api/v1/staff/patients")
public class PatientReceptionController {
    private final PatientReceptionService service;

    public PatientReceptionController(PatientReceptionService service) { this.service = service; }

    @GetMapping("/{id}/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestParam String branch_id,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.summary(actor, parseId(branch_id), id),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/{id}/reception-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> receptionSummary(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestParam String branch_id,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.receptionSummary(actor, parseId(branch_id), id),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> search(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam String branch_id,
            @RequestParam String name,
            @RequestParam String phone,
            HttpServletRequest request) {
        long branch = parseId(branch_id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.search(actor, branch, name, phone),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateBody body,
            HttpServletRequest request) {
        Map<String, Object> data = service.create(actor, parseId(body.branch_id()), body.name(), body.phone(),
            body.birth_date(), body.allergy_status(), body.allergy_notes(),
            body.verification_method(), body.verification_result(), body.in_person(),
            body.duplicate_resolution(), body.source_channel(), key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/{id}/selection")
    public ResponseEntity<ApiResponse<Map<String, Object>>> select(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @Valid @RequestBody SelectionBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(service.select(actor, id, parseId(body.branch_id()), body.name(), body.phone(),
                body.verification_method(), body.verification_result(), body.in_person(), body.resolution()),
                RequestTraceFilter.requestId(request)));
    }

    private static long parseId(String value) {
        try { return Long.parseLong(value); }
        catch (Exception e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }

    public record CreateBody(
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String branch_id,
            @NotBlank @Size(max=60) String name,
            @NotBlank @Size(max=30) String phone,
            String birth_date,
            @NotBlank @Pattern(regexp="未申报|无已知|有|不确定") String allergy_status,
            @Size(max=1000) String allergy_notes,
            @NotBlank @Pattern(regexp="visit_evidence|id_document") String verification_method,
            @NotBlank @Pattern(regexp="confirmed") String verification_result,
            boolean in_person,
            @Pattern(regexp="shared_phone|same_name") String duplicate_resolution,
            @Pattern(regexp="现场|电话|转介绍|微信小程序|其他") String source_channel) {}

    public record SelectionBody(
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String branch_id,
            @NotBlank @Size(max=60) String name,
            @NotBlank @Size(max=30) String phone,
            @NotBlank @Pattern(regexp="visit_evidence|id_document") String verification_method,
            @NotBlank @Pattern(regexp="confirmed") String verification_result,
            boolean in_person,
            @NotBlank @Pattern(regexp="single_exact|manual_candidate|shared_phone|same_name") String resolution) {}
}
