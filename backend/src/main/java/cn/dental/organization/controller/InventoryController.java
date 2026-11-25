package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.InventoryService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.PageResult;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/staff")
public class InventoryController {
    private final InventoryService inventory;
    public InventoryController(InventoryService inventory) { this.inventory=inventory; }

    @GetMapping("/stock-items")
    public ResponseEntity<PageResult<Map<String,Object>>> items(@AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(inventory.items(actor,cursor,limit));
    }
    @PostMapping("/stock-items")
    public ResponseEntity<ApiResponse<Map<String,Object>>> createItem(
            @AuthenticationPrincipal SessionService.Principal actor,@RequestHeader("Idempotency-Key") String key,
            @RequestBody ItemBody body,HttpServletRequest request) {
        var data=inventory.createItem(actor,new InventoryService.ItemCreate(body.code(),body.name(),
            body.specification(),body.base_unit(),body.storage_condition()),key);
        return created(data,request);
    }

    @GetMapping("/warehouses")
    public ResponseEntity<PageResult<Map<String,Object>>> warehouses(@AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(name="branch_id") long branch,@RequestParam(required=false) String cursor,
            @RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inventory.warehouses(actor,branch,cursor,limit));
    }
    @PostMapping("/warehouses")
    public ResponseEntity<ApiResponse<Map<String,Object>>> createWarehouse(
            @AuthenticationPrincipal SessionService.Principal actor,@RequestHeader("Idempotency-Key") String key,
            @RequestBody WarehouseBody body,HttpServletRequest request) {
        var data=inventory.createWarehouse(actor,new InventoryService.WarehouseCreate(body.branch_id(),body.name()),key);
        return created(data,request);
    }

    @GetMapping("/purchase-receipts")
    public ResponseEntity<PageResult<Map<String,Object>>> receipts(@AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(name="branch_id") long branch,@RequestParam(name="batch_id",required=false) Long batch,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inventory.receipts(actor,branch,batch,cursor,limit));
    }
    @GetMapping("/purchase-receipts/{id}")
    public ResponseEntity<ApiResponse<Map<String,Object>>> receipt(@AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable long id,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(inventory.receipt(actor,id),RequestTraceFilter.requestId(request)));
    }
    @PostMapping("/purchase-receipts")
    public ResponseEntity<ApiResponse<Map<String,Object>>> receive(
            @AuthenticationPrincipal SessionService.Principal actor,@RequestHeader("Idempotency-Key") String key,
            @RequestBody ReceiptBody body,HttpServletRequest request) {
        List<InventoryService.ReceiptLine> lines=body.lines()==null?null:body.lines().stream()
            .map(l->l==null?null:new InventoryService.ReceiptLine(l.line_no(),l.stock_item_id(),
                l.warehouse_id(),l.manufacturer(),l.holder_name(),l.batch_no(),l.expires_on(),
                l.trace_reference(),l.quantity(),l.unit_cost())).toList();
        var data=inventory.receive(actor,new InventoryService.ReceiptCreate(body.branch_id(),body.supplier_id(),
            body.document_no(),body.supplier_document(),lines),key);
        return created(data,request);
    }
    @GetMapping("/stock-balances")
    public ResponseEntity<PageResult<Map<String,Object>>> balances(@AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(name="branch_id") long branch,@RequestParam(name="warehouse_id",required=false) Long warehouse,
            @RequestParam(name="stock_item_id",required=false) Long item,@RequestParam(name="batch_id",required=false) Long batch,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inventory.balances(actor,branch,warehouse,item,batch,cursor,limit));
    }

    private static ResponseEntity<ApiResponse<Map<String,Object>>> created(Map<String,Object> data,HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(data,RequestTraceFilter.requestId(request)));
    }
    public record ItemBody(String code,String name,String specification,String base_unit,String storage_condition) {}
    public record WarehouseBody(long branch_id,String name) {}
    public record ReceiptBody(long branch_id,long supplier_id,String document_no,String supplier_document,
            List<LineBody> lines) {}
    public record LineBody(int line_no,long stock_item_id,long warehouse_id,String manufacturer,
            String holder_name,String batch_no,String expires_on,String trace_reference,
            BigDecimal quantity,BigDecimal unit_cost) {}
}
