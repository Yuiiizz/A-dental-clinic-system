package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.SchedulingConfigService;
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
@RequestMapping("/api/v1/staff/scheduling")
public class SchedulingController {
    private final SchedulingConfigService scheduling;

    public SchedulingController(SchedulingConfigService scheduling) {
        this.scheduling = scheduling;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(scheduling.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/schedules")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSchedule(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody ScheduleBody body,
            HttpServletRequest request) {
        Map<String, Object> data = scheduling.createSchedule(actor,
            new SchedulingConfigService.ScheduleCommand(body.dentist_id(), body.branch_id(),
                body.starts_at(), body.ends_at(), body.item_ids()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/schedules/{id}/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publish(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(scheduling.publish(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/service-items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createService(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody ServiceBody body,
            HttpServletRequest request) {
        Map<String, Object> data = scheduling.createService(actor,
            new SchedulingConfigService.ServiceCommand(body.name(), body.price(), body.minutes(), body.branch_id()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/resources")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createResource(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody ResourceBody body,
            HttpServletRequest request) {
        Map<String, Object> data = scheduling.createResource(actor,
            new SchedulingConfigService.ResourceCommand(body.name(), body.branch_id()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @PutMapping("/staff/{staffId}/branches/{branchId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignBranch(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long staffId, @PathVariable long branchId,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(scheduling.assignBranch(actor, staffId, branchId),
                RequestTraceFilter.requestId(request)));
    }

    @PutMapping("/staff/{staffId}/services")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setScope(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long staffId,
            @RequestBody ScopeBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(scheduling.setScope(actor, staffId,
                new SchedulingConfigService.ScopeCommand(body.item_ids(), body.approval_basis())), RequestTraceFilter.requestId(request)));
    }

    public record ScheduleBody(
            @NotNull @Min(1) Long dentist_id,
            @NotNull @Min(1) Long branch_id,
            @NotBlank String starts_at,
            @NotBlank String ends_at,
            @NotEmpty List<@NotNull @Min(1) Long> item_ids) {}

    public record ServiceBody(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String price,
            @Min(5) @Max(480) int minutes,
            @NotNull @Min(1) Long branch_id) {}

    public record ResourceBody(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(1) Long branch_id) {}

    public record ScopeBody(List<Long> item_ids, @NotBlank @Size(max = 300) String approval_basis) {}
}
