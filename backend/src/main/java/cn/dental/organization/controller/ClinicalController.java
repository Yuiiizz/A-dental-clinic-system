package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.ClinicalService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ClinicalController {
    private final ClinicalService clinical;
    public ClinicalController(ClinicalService clinical) { this.clinical = clinical; }

    @GetMapping("/staff/clinical")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/staff/visits")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> visits(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.visits(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/appointments/{id}/start-visit")
    public ApiResponse<Map<String,Object>> start(@AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id, HttpServletRequest request) {
        return new ApiResponse<>(clinical.startVisit(actor,id),RequestTraceFilter.requestId(request));
    }

    @PostMapping("/staff/visits")
    public ResponseEntity<ApiResponse<Map<String, Object>>> completeVisit(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody VisitBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.completeVisit(actor,
                new ClinicalService.VisitCommand(body.appointment_id(), body.allergy_result(), body.allergy_snapshot(), body.clinical_notes(), body.followup_goal_achieved())),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/staff/followup-plans")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> plans(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.plans(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/followup-plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createPlan(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody PlanBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.createPlan(actor,
                new ClinicalService.PlanCommand(body.visit_id(), body.suggested_from(), body.suggested_to(),
                    body.duration_minutes(), body.purpose(), body.service_item_id())), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/me/records")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patientRecords(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(clinical.patientRecords(actor), RequestTraceFilter.requestId(request)));
    }

    public record VisitBody(@NotNull @Min(1) Long appointment_id, @NotBlank String allergy_result, String allergy_snapshot,
                            String clinical_notes, Boolean followup_goal_achieved) {}
    public record PlanBody(
            @NotNull @Min(1) Long visit_id,
            @NotBlank String suggested_from,
            @NotBlank String suggested_to,
            @Min(5) @Max(480) int duration_minutes,
            @NotBlank @Size(max = 200) String purpose,
            @Positive Long service_item_id) {}
}
