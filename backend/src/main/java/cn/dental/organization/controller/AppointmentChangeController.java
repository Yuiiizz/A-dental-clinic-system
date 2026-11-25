package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.AppointmentChangeService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AppointmentChangeController {
    private final AppointmentChangeService changes;
    public AppointmentChangeController(AppointmentChangeService changes) { this.changes=changes; }

    @GetMapping("/me/changes")
    public ResponseEntity<ApiResponse<List<Map<String,Object>>>> mine(
            @AuthenticationPrincipal SessionService.Principal actor,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.patientList(actor),RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/staff/changes")
    public ResponseEntity<ApiResponse<List<Map<String,Object>>>> staff(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam("branch_id") long branchId,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.staffList(actor,branchId),RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/appointments/{id}/changes")
    public ResponseEntity<ApiResponse<Map<String,Object>>> propose(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ProposalBody body,HttpServletRequest request) {
        var candidate=body.candidate();
        var result=changes.propose(actor,id,new AppointmentChangeService.Candidate(
            candidate.schedule_id(),candidate.item_id(),candidate.starts_at(),candidate.policy_id()),
            body.reason(),body.absence_id(),body.unavailable_id(),key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(result,RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/me/changes/{id}/response")
    public ResponseEntity<ApiResponse<Map<String,Object>>> patientResponse(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody AnswerBody body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.respondPatient(actor,id,body.accept(),body.expected_version(),key),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/changes/{id}/response")
    public ResponseEntity<ApiResponse<Map<String,Object>>> staffResponse(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody StaffAnswerBody body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.respondStaff(actor,id,body.accept(),body.expected_version(),
                body.confirmation_basis(),key),RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/changes/{id}/apply")
    public ResponseEntity<ApiResponse<Map<String,Object>>> apply(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody ApplyBody body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.apply(actor,id,body.expected_version(),key),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/changes/{id}/withdraw")
    public ResponseEntity<ApiResponse<Map<String,Object>>> withdraw(
            @AuthenticationPrincipal SessionService.Principal actor,@PathVariable long id,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody WithdrawBody body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(changes.withdraw(actor,id,body.expected_version(),body.reason(),key),
                RequestTraceFilter.requestId(request)));
    }

    public record CandidateBody(@Min(1) long schedule_id,@Min(1) long item_id,
            @NotBlank String starts_at,@Min(1) long policy_id) {}
    public record ProposalBody(@NotNull @Valid CandidateBody candidate,
            @NotBlank @Size(max=300) String reason,Long absence_id,Long unavailable_id) {}
    public record AnswerBody(@NotNull Boolean accept,@Min(1) int expected_version) {}
    public record StaffAnswerBody(@NotNull Boolean accept,@Min(1) int expected_version,
            @NotBlank @Size(max=300) String confirmation_basis) {}
    public record ApplyBody(@Min(1) int expected_version) {}
    public record WithdrawBody(@Min(1) int expected_version,
            @NotBlank @Size(max=300) String reason) {}
}
