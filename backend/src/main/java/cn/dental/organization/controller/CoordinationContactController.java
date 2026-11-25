package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.CoordinationContactService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/contacts")
public class CoordinationContactController {
    private final CoordinationContactService contacts;
    public CoordinationContactController(CoordinationContactService contacts) { this.contacts = contacts; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam("branch_id") long branchId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(contacts.list(actor, branchId, cursor, limit == null ? 20 : limit));
    }

    @GetMapping("/due")
    public ResponseEntity<Map<String,Object>> due(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam("branch_id") long branchId,
            @RequestParam(required=false) Long cursor,
            @RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(contacts.due(actor,branchId,cursor,limit==null?20:limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody ContactBody body, HttpServletRequest request) {
        if (!"人工联系".equals(body.kind())) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long appointmentId;
        try { appointmentId = Long.parseLong(body.appointment_id()); }
        catch (NumberFormatException ex) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(contacts.record(actor,
                new CoordinationContactService.ContactCommand(appointmentId, body.channel(), body.result(),
                    body.acknowledgment_basis(), body.next_contact_at()), key),
                RequestTraceFilter.requestId(request)));
    }

    public record ContactBody(
            @NotBlank @Pattern(regexp = "人工联系") String kind,
            @NotBlank @Pattern(regexp = "电话|微信登记|当面") String channel,
            @NotBlank @Pattern(regexp = "[1-9]\\d*") String appointment_id,
            @NotBlank @Size(max = 300) String result,
            @Size(max = 300) String acknowledgment_basis,
            String next_contact_at) {
        @Override public String toString() { return "ManualContactBody[redacted]"; }
    }
}
