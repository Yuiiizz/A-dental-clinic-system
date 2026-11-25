package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.BookingService;
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
@RequestMapping("/api/v1/staff")
public class BookingController {
    private final BookingService booking;
    public BookingController(BookingService booking) { this.booking = booking; }

    @GetMapping("/booking")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/appointments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required = false) String date, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.list(actor, date), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/appointments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody CreateBody body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        Map<String, Object> data = booking.createOnce(actor, new BookingService.CreateCommand(
            body.patient_id(), body.schedule_id(), body.item_id(), body.starts_at(),
            body.visit_type(), body.channel(), body.purpose(), body.patient_selection_token()), key, body.consent_basis());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkin(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @Valid @RequestBody CheckinBody body,
            @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.checkin(actor, id, body.expected_version(),
                body.verification_basis(), body.exception_reason(), key), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/queue")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> queue(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam("branch_id") long branchId, @RequestParam("date") String date,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(booking.queue(actor, branchId, date), RequestTraceFilter.requestId(request)));
    }

    public record CreateBody(
            @NotNull @Min(1) Long patient_id,
            @NotNull @Min(1) Long schedule_id,
            @NotNull @Min(1) Long item_id,
            @NotBlank String starts_at,
            String visit_type,
            String channel,
            String purpose, @NotBlank @jakarta.validation.constraints.Size(max=300) String consent_basis,
            @NotBlank String patient_selection_token) {}
    public record CancelBody(String reason) {}
    public record CheckinBody(@NotNull @Min(1) Long expected_version,
                              @NotBlank @jakarta.validation.constraints.Size(max=300) String verification_basis,
                              @jakarta.validation.constraints.Size(max=300) String exception_reason) {}
}
