package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.SupplierService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.PageResult;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/suppliers")
public class SupplierController {
    private final SupplierService suppliers;
    public SupplierController(SupplierService suppliers) { this.suppliers = suppliers; }

    @GetMapping
    public ResponseEntity<PageResult<Map<String,Object>>> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(suppliers.list(actor, cursor, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> detail(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(suppliers.detail(actor,id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String,Object>>> create(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateBody body, HttpServletRequest request) {
        var data = suppliers.create(actor, new SupplierService.Create(body.name(), body.credit_code(),
            body.qualification_ref(), body.valid_to()), key);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data, RequestTraceFilter.requestId(request)));
    }

    public record CreateBody(@NotBlank @Size(max=120) String name,
            @NotBlank @Size(max=30) String credit_code,
            @NotBlank @Size(max=300) String qualification_ref,
            @NotBlank String valid_to) {}
}
