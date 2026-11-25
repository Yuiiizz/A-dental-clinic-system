package cn.dental.organization.controller;

import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.DoctorPreferenceService;
import cn.dental.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/doctor-preferences")
public class DoctorPreferenceController {
    private final DoctorPreferenceService preferences;
    public DoctorPreferenceController(DoctorPreferenceService preferences) { this.preferences = preferences; }

    @GetMapping
    public ResponseEntity<PageResult<Map<String,Object>>> list(
            @AuthenticationPrincipal SessionService.Principal actor,
            @RequestParam(required=false) String cursor, @RequestParam(required=false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.list(actor,cursor,limit));
    }

    @PutMapping("/{doctor_id}")
    public ResponseEntity<Void> set(@AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable("doctor_id") long doctorId, @Valid @RequestBody SourceBody body) {
        preferences.set(actor, doctorId, body.source());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/{doctor_id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal SessionService.Principal actor,
            @PathVariable("doctor_id") long doctorId) {
        preferences.delete(actor, doctorId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record SourceBody(@NotBlank String source) {}
}
