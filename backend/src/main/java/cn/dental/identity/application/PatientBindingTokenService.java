package cn.dental.identity.application;

import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 前台签发/重签患者绑定凭证（identity_challenge purpose=patient_binding，10 分钟一次性）。
 * 复用 {@link IdempotencyService}：幂等作用域为 发起账号 + 操作（patient-bind-issue / patient-bind-reissue）+ 键；
 * 规范化摘要显式包含 patient_id（避免同名同电话的另一患者重放本患者结果）。同键同内容重放 binding_token=null
 * 且 replayed=true，只返回已签发元信息与当前状态，不伪称旧凭证仍有效。
 * 首发令牌仅在本请求内存（AtomicReference）出现一次，幂等记录只存 UUID 引用，绝不存明文。
 *
 * 锁序固定（幂等 advisory 锁 → patient-bind advisory 锁 → 患者行 FOR UPDATE；所有签发/重签/绑定路径
 * 都先取 patient-bind 再取行锁，无反向顺序）：IdempotencyService 先按键串行化同请求重试，业务层再按患者
 * 串行化并发签发/重签，与 {@link PatientBindingExecutor} 共用同一患者锁派生键，避免同一患者并发签发与绑定
 * 相互交错。姓名+电话只用于匹配档案、不能证明本人；服务端只留痕核验人/时间/门店/方法（白名单类型化摘要），
 * 不得在审计里放姓名电话/证件号或任意自由文本。已绑定患者拒绝首发/重签，绝不重绑/合并/启用停用账号。
 *
 * 业务专用鉴权（不改变其他业务的 {@link AuthorizationService#requireBranch}）：签发/重签/重放除角色门店
 * 授权外，还要求门店营业且已核验、任职门店已核实（enabled）。鉴权在进幂等锁前、取得幂等锁后、取得患者锁后
 * 以及重放返回前各执行一次，覆盖等待患者锁期间被撤权的窗口；与业务提交真正并发的撤权仍属授权状态变更与业务
 * 写入之间仍有竞争窗口：本服务保证每次鉴权前已提交的撤权被拒绝，不保证最后一次鉴权之后的撤权阻止本次提交。
 * 绑定落账侧锁的是患者账号，不能代替这里对签发员工授权的协调。
 */
@Service
public class PatientBindingTokenService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PatientBindingTokenService(JdbcTemplate jdbc, AuthorizationService authorization, AuditService audit,
            IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc; this.authorization = authorization; this.audit = audit; this.idempotency = idempotency; this.clock = clock;
    }

    /** 首发/重放结果：data 含 binding_token（首发为明文、重放为 null），默认 toString 固定脱敏。 */
    public record IssueResult(Map<String, Object> data, boolean replayed) {
        @Override public String toString() { return "IssueResult[replayed=" + replayed + ",data=redacted]"; }
    }

    /** 内部规范化请求；默认 toString 固定脱敏，不输出姓名电话。 */
    private record Request(long patientId, long branchId, String name, String phone, Long appointmentId,
                           String verificationMethod, boolean inPerson) {
        @Override public String toString() { return "BindingRequest[redacted]"; }
    }

    @Transactional
    public IssueResult issue(SessionService.Principal actor, long patientId, long branchId, String name, String phone,
            String appointmentIdText, String verificationMethod, boolean inPerson, String idempotencyKey) {
        Request req = normalize(patientId, branchId, name, phone, appointmentIdText, verificationMethod, inPerson);
        var token = new AtomicReference<String>();
        String content = IdempotencyService.canonicalize(contentMap(req, null));
        IdempotencyService.Outcome out = idempotency.execute(
            actor.accountId(), "patient-bind-issue", idempotencyKey, content,
            () -> requireIssueAuthorized(actor, req.branchId()),
            () -> doIssue(actor, patientId, req, token),
            targetId -> requirePatientVisible(actor, patientId, req.branchId(), targetId));
        return result(out, token.get(), patientId, req.branchId());
    }

    @Transactional
    public IssueResult reissue(SessionService.Principal actor, long patientId, long branchId, String name, String phone,
            String appointmentIdText, String verificationMethod, boolean inPerson, String previousChallengeRef, String idempotencyKey) {
        Request req = normalize(patientId, branchId, name, phone, appointmentIdText, verificationMethod, inPerson);
        UUID previousRef = parseUuid(previousChallengeRef);
        var token = new AtomicReference<String>();
        String content = IdempotencyService.canonicalize(contentMap(req, previousRef.toString()));
        IdempotencyService.Outcome out = idempotency.execute(
            actor.accountId(), "patient-bind-reissue", idempotencyKey, content,
            () -> requireIssueAuthorized(actor, req.branchId()),
            () -> doReissue(actor, patientId, req, previousRef, token),
            targetId -> requirePatientVisible(actor, patientId, req.branchId(), targetId));
        return result(out, token.get(), patientId, req.branchId());
    }

    private IdempotencyService.BusinessResult doIssue(SessionService.Principal actor, long patientId, Request req, AtomicReference<String> token) {
        takePatientLock(patientId);
        requireIssueAuthorized(actor, req.branchId()); // 等待患者锁期间的撤权窗口再鉴权
        verifyBasis(patientId, req);
        verifyAppointment(patientId, req);
        rejectIfBound(patientId);
        revokeExhausted(patientId);
        rejectIfActive(patientId);
        UUID challengeId = insertChallenge(actor, patientId, req, token);
        audit.user(actor.accountId(), "patient", patientId, "签发患者绑定凭证", null,
            Map.of("verification_method", req.verificationMethod(), "in_person", "true", "branch_ids", Long.toString(req.branchId())), null);
        return new IdempotencyService.BusinessResult(patientId, "ISSUED", challengeId.toString());
    }

    private IdempotencyService.BusinessResult doReissue(SessionService.Principal actor, long patientId, Request req, UUID previousRef, AtomicReference<String> token) {
        takePatientLock(patientId);
        requireIssueAuthorized(actor, req.branchId()); // 等待患者锁期间的撤权窗口再鉴权
        verifyBasis(patientId, req);
        verifyAppointment(patientId, req);
        rejectIfBound(patientId);
        revokeExhausted(patientId);
        // 重签必须引用「当前待用」的凭证：已消费/已撤销/已过期/次数耗尽均视为过时引用 409，
        // 避免两名前台在各自重签里相互撤销刚签发的新凭证。
        var rows = jdbc.queryForList("""
            SELECT patient_id,consumed_at,revoked_at,failed_attempts,expires_at
            FROM identity_challenge WHERE challenge_id=? AND purpose='patient_binding'
            """, previousRef);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.STALE_BINDING_REISSUE);
        var prev = rows.getFirst();
        if (!Long.valueOf(patientId).equals(((Number) prev.get("patient_id")).longValue())
                || prev.get("consumed_at") != null || prev.get("revoked_at") != null
                || ((Number) prev.get("failed_attempts")).intValue() >= 5
                || !((Timestamp) prev.get("expires_at")).toInstant().isAfter(clock.instant()))
            throw new ApiException(ApiErrorCode.STALE_BINDING_REISSUE);
        int revoked = jdbc.update("UPDATE identity_challenge SET revoked_at=? WHERE challenge_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND purpose='patient_binding'",
            Timestamp.from(clock.instant()), previousRef);
        if (revoked != 1) throw new ApiException(ApiErrorCode.STALE_BINDING_REISSUE);
        UUID challengeId = insertChallenge(actor, patientId, req, token);
        audit.user(actor.accountId(), "patient", patientId, "重签患者绑定凭证", null,
            Map.of("verification_method", req.verificationMethod(), "in_person", "true", "branch_ids", Long.toString(req.branchId())), null);
        return new IdempotencyService.BusinessResult(patientId, "REISSUED", challengeId.toString());
    }

    private Request normalize(long patientId, long branchId, String name, String phone, String appointmentIdText,
            String verificationMethod, boolean inPerson) {
        if (patientId <= 0 || branchId <= 0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if (!inPerson) throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
        if (!"visit_evidence".equals(verificationMethod) && !"id_document".equals(verificationMethod))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String normalizedName = name == null ? "" : name.strip();
        String normalizedPhone = phone == null ? "" : phone.strip();
        if (normalizedName.isEmpty() || normalizedPhone.isEmpty()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return new Request(patientId, branchId, normalizedName, normalizedPhone, parseOptionalId(appointmentIdText), verificationMethod, true);
    }

    /** 签发/重签/重放统一鉴权：角色门店授权 + 门店营业且已核验、任职门店已核实。 */
    private void requireIssueAuthorized(SessionService.Principal actor, long branchId) {
        authorization.requireBranch(actor, "patients.bind_issue", branchId);
        requireBranchBusiness(actor, branchId);
    }

    /**
     * 签发/重签专用业务鉴权：门店须营业（state='营业'）且已核验（verified_at 非空），
     * 员工任职门店已核实（staff_branch.enabled；verified_at 由列 NOT NULL 保证）。不改变其他业务的 requireBranch。
     */
    private void requireBranchBusiness(SessionService.Principal actor, long branchId) {
        int found = jdbc.queryForObject("""
            SELECT count(*) FROM branch b
            JOIN staff_branch sb ON sb.branch_id=b.branch_id AND sb.staff_id=? AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE b.branch_id=? AND b.state='营业' AND b.verified_at IS NOT NULL
            """, Integer.class, actor.staffId(), branchId);
        if (found != 1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }

    /** 重放可见性校验：目标须为本次请求的患者，患者仍存在，且当前授权范围仍覆盖本店（撤权后不得借旧结果返回）。 */
    private void requirePatientVisible(SessionService.Principal actor, long patientId, long branchId, long targetId) {
        if (targetId != patientId) throw new ApiException(ApiErrorCode.NOT_FOUND);
        if (jdbc.queryForObject("SELECT count(*) FROM patient WHERE patient_id=?", Integer.class, patientId) != 1)
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        requireIssueAuthorized(actor, branchId);
    }

    private void takePatientLock(long patientId) {
        jdbc.execute("SELECT pg_advisory_xact_lock(" + IdentityLocks.lockKey("patient-bind", Long.toString(patientId)) + ")");
    }

    private void verifyBasis(long patientId, Request req) {
        var rows = jdbc.queryForList("SELECT name,phone FROM patient WHERE patient_id=? FOR UPDATE", patientId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        var r = rows.getFirst();
        if (!req.name().equals(r.get("name")) || !req.phone().equals(r.get("phone")))
            throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
    }

    private void verifyAppointment(long patientId, Request req) {
        if (req.appointmentId() == null) return;
        int found = jdbc.queryForObject("SELECT count(*) FROM appointment WHERE appointment_id=? AND patient_id=? AND branch_id=?",
            Integer.class, req.appointmentId(), patientId, req.branchId());
        if (found != 1) throw new ApiException(ApiErrorCode.BUSINESS_BASIS_REQUIRED);
    }

    private void rejectIfBound(long patientId) {
        int bound = jdbc.queryForObject(
            "SELECT count(*) FROM wechat_identity w JOIN account a ON a.account_id=w.account_id WHERE a.patient_id=?",
            Integer.class, patientId);
        if (bound != 0) throw new ApiException(ApiErrorCode.PATIENT_ALREADY_BOUND);
    }

    private void revokeExhausted(long patientId) {
        // 过期/次数耗尽的待用记录不参与「当前待用」判断，但仍被部分唯一索引占用；先撤销再签发/重签。
        jdbc.update("""
            UPDATE identity_challenge SET revoked_at=?
            WHERE patient_id=? AND purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL
              AND (expires_at<=? OR failed_attempts>=5)
            """, Timestamp.from(clock.instant()), patientId, Timestamp.from(clock.instant()));
    }

    private void rejectIfActive(long patientId) {
        int active = jdbc.queryForObject(
            "SELECT count(*) FROM identity_challenge WHERE patient_id=? AND purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL",
            Integer.class, patientId);
        if (active != 0) throw new ApiException(ApiErrorCode.BINDING_TOKEN_ALREADY_ACTIVE);
    }

    private UUID insertChallenge(SessionService.Principal actor, long patientId, Request req, AtomicReference<String> token) {
        String plain = token();
        Instant expires = clock.instant().plus(Duration.ofMinutes(10));
        UUID challengeId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,created_at)
            VALUES(?,'patient_binding',?,?,?,?,?,?)
            """, challengeId, SessionService.hash(plain), patientId, actor.accountId(), req.branchId(),
            Timestamp.from(expires), Timestamp.from(clock.instant()));
        token.set(plain);
        return challengeId;
    }

    private IssueResult result(IdempotencyService.Outcome out, String token, long patientId, long branchId) {
        UUID challengeId;
        try { challengeId = UUID.fromString(out.resultRef()); }
        catch (RuntimeException e) { throw new ApiException(ApiErrorCode.INTERNAL_ERROR); }
        var rows = jdbc.queryForList(
            "SELECT patient_id,branch_id,expires_at,consumed_at,revoked_at,failed_attempts FROM identity_challenge WHERE challenge_id=?",
            challengeId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
        var row = rows.getFirst();
        // 重放防御：返回的凭证引用必须确属本次请求的患者与门店，杜绝跨患者/跨门店引用泄露。
        if (patientId != ((Number) row.get("patient_id")).longValue()
                || branchId != ((Number) row.get("branch_id")).longValue())
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        Instant expiresAt = ((Timestamp) row.get("expires_at")).toInstant();
        boolean consumed = row.get("consumed_at") != null;
        boolean revoked = row.get("revoked_at") != null;
        int failed = ((Number) row.get("failed_attempts")).intValue();
        String status = consumed ? "consumed" : revoked ? "revoked" : failed >= 5 ? "exhausted"
            : expiresAt.isAfter(clock.instant()) ? "active" : "expired";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("binding_token", token); // 首发为明文，重放为 null
        data.put("replayed", out.replayed());
        data.put("challenge_ref", challengeId.toString());
        data.put("expires_at", ISO.format(expiresAt));
        data.put("status", status);
        return new IssueResult(data, out.replayed());
    }

    private static Map<String, Object> contentMap(Request req, String previousRef) {
        var m = new LinkedHashMap<String, Object>();
        m.put("patient_id", req.patientId()); // 幂等摘要必须含患者，避免同名同电话的另一患者重放本患者结果
        m.put("branch_id", req.branchId());
        m.put("patient_name", req.name());
        m.put("patient_phone", req.phone());
        m.put("appointment_id", req.appointmentId()); // 可空，用 LinkedHashMap 而非 Map.of
        m.put("verification_method", req.verificationMethod());
        m.put("in_person", req.inPerson());
        if (previousRef != null) m.put("previous_challenge_ref", previousRef);
        return m;
    }

    private static Long parseOptionalId(String text) {
        if (text == null || text.isBlank()) return null;
        try { long v = Long.parseLong(text); if (v <= 0) throw new NumberFormatException(); return v; }
        catch (NumberFormatException e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }

    private static UUID parseUuid(String text) {
        try { return UUID.fromString(text); }
        catch (RuntimeException e) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED); }
    }

    private String token() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
