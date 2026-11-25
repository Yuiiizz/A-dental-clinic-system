package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.TreatmentPlanService;
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
public class TreatmentPlanController {
    private final TreatmentPlanService plans;
    public TreatmentPlanController(TreatmentPlanService plans) { this.plans = plans; }

    @GetMapping("/staff/treatment-plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/staff/treatment-plans/list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.plans(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/treatment-plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody CreateBody body, HttpServletRequest request) {
        List<TreatmentPlanService.ItemInput> items = body.items().stream()
            .map((i) -> new TreatmentPlanService.ItemInput(i.service_item_id(), i.name(), i.tooth_position(),
                i.phase(), i.quantity(), i.unit_price_cents())).toList();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.create(actor,
                new TreatmentPlanService.CreateCommand(body.source_visit_id(), body.title(), body.diagnosis(), items)),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/treatment-plans/{id}/submit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.submit(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/treatment-plans/{id}/items/{itemId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> itemStatus(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @PathVariable long itemId, @Valid @RequestBody ItemStatusBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.updateItem(actor, id, itemId, body.status()),
                RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/me/treatment-plans")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patientPlans(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.patientPlans(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/me/treatment-plans/{id}/accept")
    public ResponseEntity<ApiResponse<Map<String, Object>>> accept(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @RequestBody(required = false) DecisionBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.accept(actor, id, body == null ? null : body.basis()),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/me/treatment-plans/{id}/reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reject(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @RequestBody(required = false) DecisionBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(plans.reject(actor, id, body == null ? null : body.reason()),
                RequestTraceFilter.requestId(request)));
    }

    public record ItemBody(
            @Min(0) long service_item_id,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 20) String tooth_position,
            @Size(max = 40) String phase,
            @Min(1) int quantity,
            @Min(0) long unit_price_cents) {}
    public record CreateBody(
            @NotNull @Min(1) Long source_visit_id,
            @NotBlank @Size(max = 120) String title,
            String diagnosis,
            @NotEmpty List<@Valid ItemBody> items) {}
    public record ItemStatusBody(@NotBlank String status) {}
    public record DecisionBody(String basis, String reason) {}
}
