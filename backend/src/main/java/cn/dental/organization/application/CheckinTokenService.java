package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Signed, branch-bound 60-second check-in code. No patient facts are encoded or logged. */
@Service
public class CheckinTokenService {
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final byte[] secret;
    private final SecureRandom random=new SecureRandom();

    public CheckinTokenService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, Clock clock,
            @Value("${app.checkin.signing-key:}") String configuredKey) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency; this.clock=clock;
        if (configuredKey==null || configuredKey.isBlank()) secret=null;
        else {
            byte[] decoded;
            try { decoded=Base64.getDecoder().decode(configuredKey); }
            catch (IllegalArgumentException ex) { throw new IllegalStateException("CHECKIN_SIGNING_KEY must be Base64"); }
            if (decoded.length<32) throw new IllegalStateException("CHECKIN_SIGNING_KEY must contain at least 32 bytes");
            secret=decoded;
        }
    }

    public record Code(String code, Instant expiresAt) {
        @Override public String toString() { return "CheckinCode[redacted]"; }
    }

    @Transactional
    public Code issue(SessionService.Principal actor,long branchId,String key) {
        if(branchId<=0) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if(secret==null) throw new ApiException(ApiErrorCode.CHECKIN_NOT_CONFIGURED);
        var outcome=idempotency.execute(actor.accountId(),"branch-checkin-code",key,
            IdempotencyService.canonicalize(Map.of("branch_id",branchId)),
            ()->requireIssuable(actor,branchId),
            ()->{
                byte[] nonce=new byte[16]; random.nextBytes(nonce);
                String ref=clock.instant().getEpochSecond()+":"+ENCODER.encodeToString(nonce);
                return new IdempotencyService.BusinessResult(branchId,"CODE_ISSUED",ref);
            }, id->requireIssuable(actor,id));
        String ref=outcome.resultRef();
        if(ref==null || !ref.matches("[0-9]{1,12}:[A-Za-z0-9_-]{22}")) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
        long issued=Long.parseLong(ref.substring(0,ref.indexOf(':')));
        Instant expires=Instant.ofEpochSecond(issued).plusSeconds(60);
        String payload="v1|"+branchId+"|"+issued+"|"+ref.substring(ref.indexOf(':')+1);
        String encoded=ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new Code(encoded+"."+ENCODER.encodeToString(sign(payload)),expires);
    }

    /** Called by the later patient endpoint; rejects malformed, foreign-branch and expired codes. */
    public void verify(String code,long expectedBranch) {
        if(secret==null) throw new ApiException(ApiErrorCode.CHECKIN_NOT_CONFIGURED);
        try {
            if(code==null || code.length()>300) throw new IllegalArgumentException();
            String[] parts=code.split("\\.",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            String payload=new String(DECODER.decode(parts[0]),StandardCharsets.UTF_8);
            byte[] provided=DECODER.decode(parts[1]);
            if(!MessageDigest.isEqual(sign(payload),provided)) throw new IllegalArgumentException();
            String[] fields=payload.split("\\|",-1);
            if(fields.length!=4 || !"v1".equals(fields[0]) || Long.parseLong(fields[1])!=expectedBranch
                    || !fields[3].matches("[A-Za-z0-9_-]{22}")) throw new IllegalArgumentException();
            long issued=Long.parseLong(fields[2]);
            long now=clock.instant().getEpochSecond();
            if(issued>now || now>=issued+60) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new ApiException(ApiErrorCode.CHECKIN_CODE_INVALID); }
    }

    private void requireIssuable(SessionService.Principal actor,long branchId) {
        authorization.requireBranch(actor,"appointments.assist",branchId);
        List<Integer> eligible=jdbc.queryForList("""
            SELECT 1 FROM branch b JOIN branch_booking_config c ON c.branch_id=b.branch_id
            JOIN staff_branch sb ON sb.branch_id=b.branch_id AND sb.staff_id=?
            WHERE b.branch_id=? AND b.state='营业' AND b.verified_at IS NOT NULL
              AND sb.enabled=true AND sb.verified_at IS NOT NULL AND c.self_checkin_enabled=true
              AND c.early_minutes IS NOT NULL AND c.late_minutes IS NOT NULL
            """,Integer.class,actor.staffId(),branchId);
        if(eligible.isEmpty()) throw new ApiException(ApiErrorCode.CHECKIN_NOT_CONFIGURED);
    }

    private byte[] sign(String payload) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch(Exception ex) { throw new IllegalStateException("HMAC unavailable",ex); }
    }
}
