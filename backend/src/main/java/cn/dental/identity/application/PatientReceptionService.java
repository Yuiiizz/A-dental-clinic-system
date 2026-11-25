package cn.dental.identity.application;

import cn.dental.identity.application.SessionService.Principal;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 前台精确检索、patient-only 建档、线下核验后签发短时选择凭证。
 *
 * 姓名+电话只用于找候选，不表示已核实本人；选择/建档都必须提交实际线下核验方式和 confirmed 结果。
 * 选择凭证只存 SHA-256 摘要，绑定操作账号、员工、目标门店和患者；预约提交原子核销。
 */
@Service
public class PatientReceptionService {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final List<String> RESOLUTIONS = List.of(
        "new_patient", "single_exact", "manual_candidate", "shared_phone", "same_name");
    private static final List<String> METHODS = List.of("visit_evidence", "id_document");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final PatientReceptionThrottle throttle;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PatientReceptionService(JdbcTemplate jdbc, AuthorizationService authorization, AuditService audit,
                                   IdempotencyService idempotency, PatientReceptionThrottle throttle, Clock clock) {
        this.jdbc = jdbc; this.authorization = authorization; this.audit = audit;
        this.idempotency = idempotency; this.throttle = throttle; this.clock = clock;
    }

    public record Selection(UUID selectionId, long patientId, long branchId) {}

    /** Scoped summary after an actual appointment at this branch or this actor's live selection. */
    @Transactional
    public Map<String, Object> summary(Principal actor, long branchId, long patientId) {
        requireBranch(actor, "patients.lookup", branchId);
        int visible = jdbc.queryForObject("""
            SELECT count(*) FROM patient p WHERE p.patient_id=? AND (
              EXISTS (SELECT 1 FROM appointment a WHERE a.patient_id=p.patient_id AND a.branch_id=?)
              OR EXISTS (SELECT 1 FROM patient_selection ps WHERE ps.patient_id=p.patient_id
                AND ps.branch_id=? AND ps.account_id=? AND ps.staff_id=? AND ps.expires_at>?))
            """, Integer.class, patientId, branchId, branchId, actor.accountId(), actor.staffId(), Timestamp.from(clock.instant()));
        if (visible == 0) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> patient = jdbc.queryForMap("""
            SELECT name,birth_date,allergy_status,source_channel FROM patient WHERE patient_id=?
            """, patientId);
        out.put("patient_id", Long.toString(patientId));
        out.put("name", patient.get("name"));
        out.put("birth_date", patient.get("birth_date") == null ? null : patient.get("birth_date").toString());
        out.put("allergy_status", patient.get("allergy_status"));
        out.put("source_channel", patient.get("source_channel"));
        List<Map<String, Object>> last = jdbc.queryForList("""
            SELECT s.name AS dentist_name,v.completed_at FROM visit_record v
            JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN staff s ON s.staff_id=v.dentist_id
            WHERE a.patient_id=? AND a.branch_id=? AND v.completed_at IS NOT NULL
            ORDER BY v.completed_at DESC,v.visit_id DESC LIMIT 1
            """, patientId, branchId);
        out.put("last_visit_dentist", last.isEmpty() ? null : last.getFirst().get("dentist_name"));
        out.put("last_visit_at", last.isEmpty() ? null : last.getFirst().get("completed_at").toString());
        audit.user(actor.accountId(), "patient", patientId, "patient-summary-read", null,
            Map.of("branch_id", Long.toString(branchId)), null);
        return out;
    }

    /** Frontdesk contact and non-clinical history, loaded only for a patient booked at this branch. */
    @Transactional
    public Map<String, Object> receptionSummary(Principal actor, long branchId, long patientId) {
        requireBranch(actor, "patients.lookup", branchId);
        List<Map<String, Object>> patients = jdbc.queryForList("""
            SELECT p.name,p.phone FROM patient p
            WHERE p.patient_id=? AND EXISTS (
              SELECT 1 FROM appointment a WHERE a.patient_id=p.patient_id AND a.branch_id=?)
            """, patientId, branchId);
        if (patients.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient_id", Long.toString(patientId));
        out.put("name", patients.getFirst().get("name"));
        out.put("phone", patients.getFirst().get("phone"));
        out.put("appointments", jdbc.query("""
            SELECT a.appointment_id,a.starts_at,a.state,a.visit_type,
                   s.name AS dentist_name,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_name
            FROM appointment a LEFT JOIN staff s ON s.staff_id=a.confirmed_dentist_id
            WHERE a.patient_id=? AND a.branch_id=?
            ORDER BY a.starts_at DESC,a.appointment_id DESC LIMIT 8
            """, (r, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("appointment_id", Long.toString(r.getLong("appointment_id")));
                row.put("starts_at", r.getTimestamp("starts_at").toInstant().toString());
                row.put("state", r.getString("state"));
                row.put("visit_type", r.getString("visit_type"));
                row.put("dentist_name", r.getString("dentist_name"));
                row.put("item_name", r.getString("item_name"));
                return row;
            }, patientId, branchId));
        out.put("visits", jdbc.query("""
            SELECT v.completed_at,s.name AS dentist_name,
                   (SELECT i.name FROM appointment_item ai JOIN service_item i ON i.item_id=ai.item_id
                    WHERE ai.appointment_id=a.appointment_id ORDER BY ai.item_id LIMIT 1) AS item_name
            FROM visit_record v JOIN appointment a ON a.appointment_id=v.appointment_id
            JOIN staff s ON s.staff_id=v.dentist_id
            WHERE a.patient_id=? AND a.branch_id=? AND v.completed_at IS NOT NULL
            ORDER BY v.completed_at DESC,v.visit_id DESC LIMIT 5
            """, (r, n) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("completed_at", r.getTimestamp("completed_at").toInstant().toString());
                row.put("dentist_name", r.getString("dentist_name"));
                row.put("item_name", r.getString("item_name"));
                return row;
            }, patientId, branchId));
        audit.user(actor.accountId(), "patient", patientId, "reception-summary-read", null,
            Map.of("branch_id", Long.toString(branchId)), null);
        return out;
    }

    @Transactional
    public Map<String, Object> search(Principal actor, long branchId, String name, String phone) {
        requireBranch(actor, "patients.lookup", branchId);
        String n = normalizeName(name);
        String p = normalizePhone(phone);
        throttle.check(actor.accountId(), branchId);
        List<Map<String, Object>> rows = candidates(n, p);
        List<Map<String, Object>> matches = new ArrayList<>();
        int exactCount = 0;
        int samePhone = 0;
        int sameName = 0;
        int total = rows.size();
        for (Map<String, Object> row : rows) {
            String rowName = String.valueOf(row.get("name")).strip();
            String rowPhone = normalizePhone(String.valueOf(row.get("phone")));
            boolean nameExact = rowName.equals(n);
            boolean phoneExact = rowPhone.equals(p);
            if (nameExact && phoneExact) exactCount++;
            else if (phoneExact) samePhone++;
            else if (nameExact) sameName++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("patient_id", Long.toString(((Number) row.get("patient_id")).longValue()));
            item.put("display_name", maskName(rowName));
            item.put("phone_suffix", rowPhone.length() <= 4 ? rowPhone : rowPhone.substring(rowPhone.length() - 4));
            item.put("birth_year", birthYear(row.get("birth_date")));
            item.put("match_type", nameExact && phoneExact ? "exact_name_phone"
                : phoneExact ? "same_phone" : "same_name");
            if (matches.size() < 10) matches.add(item);
        }
        boolean ambiguous = total != 1 || exactCount != 1;
        String reason = exactCount > 1 ? "multiple_exact"
            : samePhone > 0 ? "same_phone" : sameName > 0 ? "same_name" : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch_id", Long.toString(branchId));
        out.put("matches", matches);
        out.put("requires_manual_verification", ambiguous);
        out.put("verification_required", !matches.isEmpty());
        out.put("can_create", matches.isEmpty());
        out.put("reason", reason);
        audit.user(actor.accountId(), "branch", branchId, "patient-lookup", null,
            Map.of("result_count", Integer.toString(matches.size()), "match_type", reason == null ? "none" : reason), null);
        return out;
    }

    @Transactional
    public Map<String, Object> create(Principal actor, long branchId, String name, String phone, String birthDateText,
                                      String allergyStatus, String allergyNotes, String method, String verificationResult,
                                      boolean inPerson, String duplicateResolution, String sourceChannel, String idempotencyKey) {
        requireBranch(actor, "patients.create", branchId);
        String n = normalizeName(name);
        String p = normalizePhone(phone);
        validateVerification(method, verificationResult, inPerson);
        String resolution = duplicateResolution == null ? "" : duplicateResolution.strip();
        if (!resolution.isEmpty() && !RESOLUTIONS.contains(resolution))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        LocalDate birthDate = parseDate(birthDateText);
        String allergy = allergyStatus == null ? "" : allergyStatus.strip();
        if (!List.of("未申报", "无已知", "有", "不确定").contains(allergy))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String notes = allergyNotes == null ? null : allergyNotes.strip();
        if (("有".equals(allergy) || "不确定".equals(allergy)) && (notes == null || notes.isBlank()))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (notes != null && notes.length() > 1000) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String source = sourceChannel == null || sourceChannel.isBlank() ? null : sourceChannel.strip();
        if (source != null && !List.of("现场", "电话", "转介绍", "微信小程序", "其他").contains(source))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("branch_id", branchId); content.put("name", n); content.put("phone", p);
        content.put("birth_date", birthDate == null ? null : birthDate.toString());
        content.put("allergy_status", allergy); content.put("allergy_notes", notes);
        content.put("verification_method", method); content.put("verification_result", verificationResult);
        content.put("in_person", inPerson); content.put("duplicate_resolution", resolution);
        content.put("source_channel", source);
        AtomicReference<String> plainToken = new AtomicReference<>();
        AtomicReference<Instant> expiry = new AtomicReference<>();
        var outcome = idempotency.execute(actor.accountId(), "patient-create", idempotencyKey,
            IdempotencyService.canonicalize(content),
            () -> requireBranch(actor, "patients.create", branchId),
            () -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("patient-phone", p) + ")");
                List<Map<String, Object>> rows = candidates(n, p);
                boolean exact = false, phoneMatch = false, nameMatch = false;
                for (Map<String, Object> row : rows) {
                    String rn = String.valueOf(row.get("name")).strip();
                    String rp = normalizePhone(String.valueOf(row.get("phone")));
                    if (rn.equals(n) && rp.equals(p)) exact = true;
                    else if (rp.equals(p)) phoneMatch = true;
                    else if (rn.equals(n)) nameMatch = true;
                }
                if (exact) throw new ApiException(ApiErrorCode.PATIENT_DUPLICATE_SUSPECTED);
                if (!resolution.isEmpty()
                    && !("shared_phone".equals(resolution) && phoneMatch)
                    && !("same_name".equals(resolution) && nameMatch))
                    throw new ApiException(ApiErrorCode.PATIENT_MATCH_REQUIRES_VERIFICATION);
                if (phoneMatch && !("shared_phone".equals(resolution) && "id_document".equals(method)))
                    throw new ApiException(ApiErrorCode.PATIENT_MATCH_REQUIRES_VERIFICATION);
                if (nameMatch && !("same_name".equals(resolution) && "id_document".equals(method)))
                    throw new ApiException(ApiErrorCode.PATIENT_MATCH_REQUIRES_VERIFICATION);
                long patientId = jdbc.queryForObject("""
                    INSERT INTO patient(name,phone,birth_date,allergy_status,allergy_notes,source_channel)
                    VALUES(?,?,?,?,?,?) RETURNING patient_id
                    """, Long.class, n, p, birthDate == null ? null : Date.valueOf(birthDate), allergy, notes, source);
                String token = token();
                Instant exp = clock.instant().plus(TTL);
                UUID selectionId = UUID.randomUUID();
                insertSelection(selectionId, token, actor, branchId, patientId, "create", method,
                    resolution.isEmpty() ? "new_patient" : resolution, exp);
                audit.user(actor.accountId(), "patient", patientId, "patient-create", null,
                    Map.of("verification_method", method, "verification_result", verificationResult,
                        "resolution", resolution.isEmpty() ? "new_patient" : resolution), null);
                plainToken.set(token); expiry.set(exp);
                return new IdempotencyService.BusinessResult(patientId, "CREATED", selectionId.toString());
            },
            patientId -> {
                requireBranch(actor, "patients.create", branchId);
                if (jdbc.queryForObject("SELECT count(*) FROM patient WHERE patient_id=?", Integer.class, patientId) != 1)
                    throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient_id", Long.toString(outcome.targetId()));
        out.put("branch_id", Long.toString(branchId));
        if (outcome.replayed()) {
            out.put("token_shown_once", true);
            out.put("message", "患者已建档；明文选择凭证无法重显，请重新执行受控选择");
        } else {
            out.put("selection_token", plainToken.get());
            out.put("expires_at", expiry.get().toString());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> select(Principal actor, long patientId, long branchId, String name, String phone,
                                      String method, String verificationResult, boolean inPerson, String resolution) {
        requireBranch(actor, "patients.lookup", branchId);
        String n = normalizeName(name);
        String p = normalizePhone(phone);
        validateVerification(method, verificationResult, inPerson);
        String res = resolution == null ? "" : resolution.strip();
        if (!RESOLUTIONS.contains(res) || "new_patient".equals(res))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT patient_id,name,phone FROM patient WHERE patient_id=? AND btrim(name)=?
              AND regexp_replace(phone,'[^0-9]','','g')=?
            """, patientId, n, p);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        List<Map<String, Object>> all = candidates(n, p);
        int exactCount = 0, samePhone = 0, sameName = 0;
        for (Map<String, Object> row : all) {
            String rn = String.valueOf(row.get("name")).strip();
            String rp = normalizePhone(String.valueOf(row.get("phone")));
            if (rn.equals(n) && rp.equals(p)) exactCount++;
            else if (rp.equals(p)) samePhone++;
            else if (rn.equals(n)) sameName++;
        }
        boolean uniqueExact = exactCount == 1 && samePhone == 0 && sameName == 0;
        boolean allowed = uniqueExact
            ? "single_exact".equals(res)
            : "manual_candidate".equals(res)
                || ("shared_phone".equals(res) && samePhone > 0)
                || ("same_name".equals(res) && sameName > 0);
        if (!allowed) throw new ApiException(ApiErrorCode.PATIENT_MATCH_REQUIRES_VERIFICATION);
        String token = token();
        Instant exp = clock.instant().plus(TTL);
        UUID selectionId = UUID.randomUUID();
        insertSelection(selectionId, token, actor, branchId, patientId, "lookup", method, res, exp);
        audit.user(actor.accountId(), "patient", patientId, "patient-select", null,
            Map.of("verification_method", method, "verification_result", verificationResult, "resolution", res), null);
        return Map.of("selection_token", token, "expires_at", exp.toString());
    }

    /** Locks and validates a selection row inside the appointment transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Selection lockForAppointment(Principal actor, String token, long patientId, long branchId) {
        if (token == null || token.isBlank()) throw new ApiException(ApiErrorCode.PATIENT_SELECTION_REQUIRED);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT selection_id,account_id,staff_id,branch_id,patient_id,expires_at,consumed_at
            FROM patient_selection WHERE token_hash=? FOR UPDATE
            """, SessionService.hash(token));
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.PATIENT_SELECTION_INVALID);
        Map<String, Object> row = rows.getFirst();
        if (((Number) row.get("account_id")).longValue() != actor.accountId()
            || ((Number) row.get("staff_id")).longValue() != actor.staffId()
            || ((Number) row.get("branch_id")).longValue() != branchId
            || ((Number) row.get("patient_id")).longValue() != patientId)
            throw new ApiException(ApiErrorCode.PATIENT_SELECTION_INVALID);
        if (row.get("consumed_at") != null) throw new ApiException(ApiErrorCode.PATIENT_SELECTION_USED);
        if (!((Timestamp) row.get("expires_at")).toInstant().isAfter(clock.instant()))
            throw new ApiException(ApiErrorCode.PATIENT_SELECTION_EXPIRED);
        return new Selection((UUID) row.get("selection_id"), patientId, branchId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(Selection selection, long appointmentId) {
        int rows = jdbc.update("""
            UPDATE patient_selection SET consumed_at=?,appointment_id=?
            WHERE selection_id=? AND consumed_at IS NULL
            """, Timestamp.from(clock.instant()), appointmentId,
            selection.selectionId());
        if (rows != 1) throw new ApiException(ApiErrorCode.PATIENT_SELECTION_USED);
    }

    private void insertSelection(UUID selectionId, String plainToken, Principal actor, long branchId,
                                 long patientId, String source, String method, String resolution, Instant expiresAt) {
        jdbc.update("""
            INSERT INTO patient_selection(selection_id,token_hash,account_id,staff_id,branch_id,patient_id,
                source,verification_method,verification_result,resolution,in_person,expires_at,created_at)
            VALUES(?,?,?,?,?,?,?,?, 'confirmed',?,true,?,?)
            """, selectionId, SessionService.hash(plainToken), actor.accountId(), actor.staffId(), branchId, patientId,
            source, method, resolution, Timestamp.from(expiresAt), Timestamp.from(clock.instant()));
    }

    private List<Map<String, Object>> candidates(String name, String phone) {
        return jdbc.queryForList("""
            SELECT patient_id,name,phone,birth_date FROM patient
            WHERE btrim(name)=? OR regexp_replace(phone,'[^0-9]','','g')=?
            ORDER BY patient_id
            """, name, phone);
    }

    private void requireBranch(Principal actor, String permission, long branchId) {
        authorization.requireBranch(actor, permission, branchId);
        int found = jdbc.queryForObject("""
            SELECT count(*) FROM branch b JOIN staff_branch sb ON sb.branch_id=b.branch_id
            WHERE b.branch_id=? AND b.state='营业' AND b.verified_at IS NOT NULL
              AND sb.staff_id=? AND sb.enabled AND sb.verified_at IS NOT NULL
            """, Integer.class, branchId, actor.staffId());
        if (found != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    private void validateVerification(String method, String result, boolean inPerson) {
        if (!METHODS.contains(method) || !"confirmed".equals(result) || !inPerson)
            throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
    }

    private static String normalizeName(String value) {
        if (value == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String v = value.strip().replaceAll("\\s+", " ");
        if (v.isEmpty() || v.length() > 60) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return v;
    }

    private static String normalizePhone(String value) {
        if (value == null) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String v = value.replaceAll("[^0-9]", "");
        if (v.length() < 5 || v.length() > 20) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return v;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.strip()); }
        catch (DateTimeParseException e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) return "*";
        int first = name.offsetByCodePoints(0, 1);
        return name.substring(0, first) + "**";
    }

    private static String birthYear(Object value) {
        if (value == null) return null;
        if (value instanceof Date d) return Integer.toString(d.toLocalDate().getYear());
        if (value instanceof java.time.LocalDate d) return Integer.toString(d.getYear());
        return null;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
