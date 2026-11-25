package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.PatientBookingService;
import cn.dental.organization.application.PatientCheckinService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class PatientBookingController {
    private final PatientBookingService booking;
    private final PatientCheckinService checkins;
    public PatientBookingController(PatientBookingService booking, PatientCheckinService checkins) {
        this.booking = booking; this.checkins = checkins;
    }

    @GetMapping("/booking")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/appointments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.list(actor, null), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/appointments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateBody body, HttpServletRequest request) {
        Map<String, Object> data = booking.createOnce(actor, new PatientBookingService.CreateCommand(
            body.schedule_id(), body.item_id(), body.starts_at(), body.visit_type(), body.purpose(), body.followup_plan_id()), key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/appointments/{id}/reschedule")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reschedule(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RescheduleBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.reschedule(actor, id, body.schedule_id(), body.item_id(), body.starts_at(), body.expected_version(), body.reason(), key),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/appointments/{id}/cancel")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestBody(required = false) CancelBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.cancel(actor, id, body == null ? null : body.reason()),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/appointments/{id}/checkin")
    public ResponseEntity<ApiResponse<Map<String,Object>>> checkin(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CheckinBody body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(checkins.checkin(actor,id,body.branch_checkin_token(),body.expected_version(),key),
                RequestTraceFilter.requestId(request)));
    }

    public record CreateBody(
            @NotNull @Min(1) Long schedule_id,
            @NotNull @Min(1) Long item_id,
            @NotBlank String starts_at,
            String visit_type,
            String purpose,
            @Min(1) Long followup_plan_id) {}
    public record RescheduleBody(
            @NotNull @Min(1) Long schedule_id,
            @NotNull @Min(1) Long item_id,
            @NotBlank String starts_at,
            @NotNull @Min(1) Long expected_version,
            @NotBlank @jakarta.validation.constraints.Size(max=300) String reason) {}
    public record CancelBody(String reason) {}
    public record CheckinBody(
            @NotBlank String branch_checkin_token,
            @NotNull @Min(1) Long expected_version) {}
}
