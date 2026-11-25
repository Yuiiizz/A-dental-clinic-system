package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.BillingService;
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
public class BillingController {
    private final BillingService billing;
    public BillingController(BillingService billing) { this.billing = billing; }

    @GetMapping("/staff/billing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.overview(actor), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/bills")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createBill(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody BillBody body, HttpServletRequest request) {
        List<BillingService.ItemInput> items = body.items().stream()
            .map((i) -> new BillingService.ItemInput(i.name(), i.quantity(), i.unit_price_cents())).toList();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.createBill(actor, new BillingService.BillCommand(body.visit_id(), items)),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/bills/{id}/payments")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pay(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @Valid @RequestBody PayBody body, @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.payOnce(actor, id,
                new BillingService.PayCommand(body.amount_cents(), body.method(), body.note()), key),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/refunds")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refund(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody RefundBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.requestRefund(actor,
                new BillingService.RefundCommand(body.bill_id(), body.payment_id(), body.amount_cents(), body.reason())),
                RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/refunds/{id}/process")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processRefund(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id,
            @Valid @RequestBody RefundDecisionBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.processRefund(actor, id,
                new BillingService.RefundDecision(body.decision(), body.basis())), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/invoices")
    public ResponseEntity<ApiResponse<Map<String, Object>>> invoice(
            @AuthenticationPrincipal SessionService.Principal actor,
            @Valid @RequestBody InvoiceBody body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.createInvoice(actor,
                new BillingService.InvoiceCommand(body.bill_id(), body.title(), body.invoice_type(), body.buyer_type(), body.buyer_tax_no(), body.tax_treatment(), body.tax_rate_bps(), body.net_amount_cents(), body.tax_amount_cents())), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/treatment-plans/{id}/bill")
    public ResponseEntity<ApiResponse<Map<String, Object>>> billFromPlan(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.createBillFromPlan(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/invoices/{id}/red")
    public ResponseEntity<ApiResponse<Map<String, Object>>> redInvoice(
            @AuthenticationPrincipal SessionService.Principal actor, @PathVariable long id, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.redInvoice(actor, id), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/invoices/{id}/issue")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueInvoice(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @Valid @RequestBody IssueInvoiceBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.issueInvoice(actor, id, body.invoice_no(), body.basis()), RequestTraceFilter.requestId(request)));
    }

    @PostMapping("/staff/invoices/{id}/void")
    public ResponseEntity<ApiResponse<Map<String, Object>>> voidInvoice(
            @AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,
            @Valid @RequestBody VoidInvoiceBody body, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.voidInvoice(actor, id, body.reason()), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/me/billing")
    public ResponseEntity<ApiResponse<Map<String, Object>>> patientBilling(
            @AuthenticationPrincipal SessionService.Principal actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(billing.patientBilling(actor), RequestTraceFilter.requestId(request)));
    }

    public record ItemBody(@NotBlank @Size(max = 100) String name, @Min(1) int quantity, @Min(0) long unit_price_cents) {}
    public record BillBody(@NotNull @Min(1) Long visit_id, @NotEmpty List<@Valid ItemBody> items) {}
    public record PayBody(@Min(1) long amount_cents, @NotBlank String method, @Size(max = 300) String note) {}
    public record RefundBody(@NotNull @Min(1) Long bill_id, @Min(0) long payment_id,
                             @Min(1) long amount_cents, @NotBlank @Size(max = 300) String reason) {}
    public record RefundDecisionBody(@NotBlank String decision, @NotBlank @Size(max = 300) String basis) {}
    public record InvoiceBody(@NotNull @Min(1) Long bill_id, @NotBlank @Size(max = 120) String title,
                              String invoice_type, String buyer_type, @Size(max = 30) String buyer_tax_no,
                              String tax_treatment, @Min(0) @Max(10000) int tax_rate_bps,
                              @Min(0) long net_amount_cents, @Min(0) long tax_amount_cents) {}
    public record IssueInvoiceBody(@NotBlank @Size(max = 40) String invoice_no, @Size(max = 300) String basis) {}
    public record VoidInvoiceBody(@NotBlank @Size(max = 300) String reason) {}
}
