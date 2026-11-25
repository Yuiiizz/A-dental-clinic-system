package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.shared.web.PageResult;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A patient's saved public doctors. A preference never grants access to a hidden doctor. */
@Service
public class DoctorPreferenceService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final List<String> SOURCES = List.of("本人选择", "熟人推荐", "既往就诊");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final Clock clock;

    public DoctorPreferenceService(JdbcTemplate jdbc, AuthorizationService authorization, Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(SessionService.Principal actor, String cursor, Integer requestedLimit) {
        long patientId = patient(actor);
        long after = cursor(cursor);
        int limit = PageResult.validateLimit(requestedLimit);
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT d.dentist_id,d.source,d.created_at FROM doctor_preference d
            JOIN staff s ON s.staff_id=d.dentist_id
            WHERE d.patient_id=? AND d.dentist_id>? AND s.staff_type='牙医' AND s.employment_state='在岗'
              AND EXISTS (SELECT 1 FROM credential c WHERE c.staff_id=s.staff_id
                AND c.credential_type='dentist_practice' AND c.verification_state='verified'
                AND (c.valid_from IS NULL OR c.valid_from<=?)
                AND (c.no_expiry_confirmed=true OR (c.valid_to IS NOT NULL AND c.valid_to>=?)))
              AND EXISTS (SELECT 1 FROM staff_branch sb JOIN branch b ON b.branch_id=sb.branch_id
                JOIN organization o ON o.organization_id=b.organization_id
                WHERE sb.staff_id=s.staff_id AND sb.enabled=true AND sb.verified_at IS NOT NULL
                  AND b.state='营业' AND b.verified_at IS NOT NULL AND o.state='营业'
                  AND (b.license_valid_to>=? OR
                    (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true)))
            ORDER BY d.dentist_id LIMIT ?
            """, patientId, after, today, today, today, limit + 1);
        boolean more = rows.size() > limit;
        List<Map<String, Object>> items = rows.subList(0, Math.min(rows.size(), limit)).stream().map(r -> Map.<String, Object>of(
            "dentist_id", String.valueOf(r.get("dentist_id")),
            "source", r.get("source"),
            "created_at", ((Timestamp) r.get("created_at")).toInstant().toString())).toList();
        return new PageResult<>(items, more ? String.valueOf(rows.get(limit - 1).get("dentist_id")) : null);
    }

    @Transactional
    public void set(SessionService.Principal actor, long doctorId, String source) {
        long patientId = patient(actor);
        if (doctorId <= 0 || !SOURCES.contains(source)) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        Integer visible = jdbc.queryForObject("""
            SELECT count(*) FROM staff s WHERE s.staff_id=? AND s.staff_type='牙医' AND s.employment_state='在岗'
              AND EXISTS (SELECT 1 FROM credential c WHERE c.staff_id=s.staff_id
                AND c.credential_type='dentist_practice' AND c.verification_state='verified'
                AND (c.valid_from IS NULL OR c.valid_from<=?)
                AND (c.no_expiry_confirmed=true OR (c.valid_to IS NOT NULL AND c.valid_to>=?)))
              AND EXISTS (SELECT 1 FROM staff_branch sb JOIN branch b ON b.branch_id=sb.branch_id
                JOIN organization o ON o.organization_id=b.organization_id
                WHERE sb.staff_id=s.staff_id AND sb.enabled=true AND sb.verified_at IS NOT NULL
                  AND b.state='营业' AND b.verified_at IS NOT NULL AND o.state='营业'
                  AND (b.license_valid_to>=? OR
                    (b.license_valid_to IS NULL AND b.license_no_expiry_confirmed=true)))
            """, Integer.class, doctorId, today, today, today);
        if (visible == null || visible == 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        jdbc.update("""
            INSERT INTO doctor_preference(patient_id,dentist_id,source,created_at)
            VALUES(?,?,?,?) ON CONFLICT(patient_id,dentist_id) DO UPDATE
            SET source=EXCLUDED.source WHERE doctor_preference.source<>EXCLUDED.source
            """, patientId, doctorId, source, Timestamp.from(clock.instant()));
    }

    @Transactional
    public void delete(SessionService.Principal actor, long doctorId) {
        long patientId = patient(actor);
        if (doctorId <= 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        jdbc.update("DELETE FROM doctor_preference WHERE patient_id=? AND dentist_id=?", patientId, doctorId);
    }

    private long patient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if (actor.patientId() == null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return actor.patientId();
    }

    private long cursor(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            long value = Long.parseLong(raw);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST);
        }
    }
}
