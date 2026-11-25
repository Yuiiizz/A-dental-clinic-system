package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.InternalMessageService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.PageResult;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/staff/internal-messages")
public class InternalMessageController {
    private final InternalMessageService messages;
    public InternalMessageController(InternalMessageService messages) { this.messages = messages; }

    @GetMapping("/contacts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> contacts(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(messages.contacts(actor), RequestTraceFilter.requestId(request)));
    }

    @GetMapping
    public ResponseEntity<PageResult<Map<String, Object>>> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(name="branch_id") long branchId,
            @RequestParam(name="peer_staff_id") long peerStaffId,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(messages.messages(actor, branchId, peerStaffId, cursor, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> send(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SendBody body, HttpServletRequest request) {
        Map<String, Object> result = messages.send(actor, body.branch_id(), body.peer_staff_id(), body.body(), key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(result, RequestTraceFilter.requestId(request)));
    }

    public record SendBody(@NotNull @Min(1) Long branch_id,
            @NotNull @Min(1) Long peer_staff_id,
            @NotBlank @Size(max=500) String body) {}
}
