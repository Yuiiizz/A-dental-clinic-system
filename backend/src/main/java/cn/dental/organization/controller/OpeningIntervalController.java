package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.OpeningIntervalService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/branches")
public class OpeningIntervalController {
    private final OpeningIntervalService intervals;

    public OpeningIntervalController(OpeningIntervalService intervals) {
        this.intervals = intervals;
    }

    @GetMapping("/{id}/opening-intervals")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestParam("from_date") LocalDate fromDate,
            @RequestParam("to_date") LocalDate toDate,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(intervals.get(actor, id, fromDate, toDate), RequestTraceFilter.requestId(request)));
    }

    @PutMapping("/{id}/opening-intervals")
    public ResponseEntity<ApiResponse<Map<String, Object>>> replace(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReplaceRequest body,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(intervals.replace(actor, id, body.from_date(), body.to_date(),
                body.intervals(), body.expected_version(), idempotencyKey), RequestTraceFilter.requestId(request)));
    }

    public record ReplaceRequest(
            @NotNull LocalDate from_date,
            @NotNull LocalDate to_date,
            @NotNull List<OpeningIntervalService.IntervalInput> intervals,
            @NotNull @Min(1) Integer expected_version) {
        @Override
        public String toString() {
            return "OpeningIntervalsReplaceRequest[redacted]";
        }
    }
}
