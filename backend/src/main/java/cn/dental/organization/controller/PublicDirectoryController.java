package cn.dental.organization.controller;

import cn.dental.organization.application.PublicDirectoryService;
import cn.dental.shared.web.ApiResponse;
import cn.dental.shared.web.PageResult;
import cn.dental.shared.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicDirectoryController {
    private final PublicDirectoryService directory;
    public PublicDirectoryController(PublicDirectoryService directory) { this.directory = directory; }

    @GetMapping("/branches")
    public ResponseEntity<PageResult<Map<String, Object>>> branches(
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.branches(cursor, limit));
    }

    @GetMapping("/branches/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> branch(@PathVariable long id, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(directory.branch(id), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/doctors")
    public ResponseEntity<PageResult<Map<String, Object>>> doctors(
            @RequestParam(required = false, name = "branch_id") String branchId,
            @RequestParam(required = false, name = "item_id") String itemId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(directory.doctors(branchId, itemId, cursor, limit));
    }

    @GetMapping("/doctors/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> doctor(@PathVariable long id, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new ApiResponse<>(directory.doctor(id), RequestTraceFilter.requestId(request)));
    }

    @GetMapping("/slots")
    public ResponseEntity<PageResult<Map<String, Object>>> slots(
            @RequestParam(name = "branch_id") String branchId,
            @RequestParam(name = "item_id") String itemId,
            @RequestParam(name = "dentist_id", required = false) String dentistId,
            @RequestParam(name = "visit_type") String visitType,
            @RequestParam(name = "from") String from,
            @RequestParam(name = "to") String to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(directory.slots(branchId, itemId, dentistId, visitType, from, to));
    }
}
