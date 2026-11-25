package cn.dental.identity.application;

import cn.dental.shared.web.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    public static final String STAFF="staff_web", PATIENT="patient_miniapp";
    private final JdbcTemplate jdbc;private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public SessionService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public record Principal(long sessionId,long accountId,Long patientId,Long staffId,String clientType,boolean mustChangePassword,String csrfHash) {
        @Override public String toString(){return "AuthenticatedPrincipal[redacted]";}
    }
    public record Issued(String token,String csrfToken,Instant expiresAt) {
        @Override public String toString(){return "IssuedSession[redacted]";}
    }
    public static String hash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private String secret(){byte[] b=new byte[32];random.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    /** Internal only: caller must first verify password/Wechat identity. No public account-id login. */
    @Transactional public Issued issue(long accountId,String clientType){
        if(!Set.of(STAFF,PATIENT).contains(clientType))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        var accounts=jdbc.queryForList("SELECT enabled,patient_id,staff_id,auth_version FROM account WHERE account_id=? FOR UPDATE",accountId);
        if(accounts.size()!=1)throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        var a=accounts.getFirst();
        if(!Boolean.TRUE.equals(a.get("enabled")) || (STAFF.equals(clientType)?a.get("staff_id")==null:a.get("patient_id")==null))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        String token=secret(),csrf=STAFF.equals(clientType)?secret():null;
        Instant now=clock.instant(),expires=now.plus(STAFF.equals(clientType)?Duration.ofHours(8):Duration.ofHours(2));
        jdbc.update("INSERT INTO auth_session(account_id,client_type,token_hash,csrf_token_hash,auth_version,created_at,last_seen_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",
            accountId,clientType,hash(token),csrf==null?null:hash(csrf),a.get("auth_version"),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),java.sql.Timestamp.from(expires));
        return new Issued(token,csrf,expires);
    }
    public Principal authenticate(String token,String clientType){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        var matches=jdbc.query("""
            SELECT s.session_id,a.account_id,a.patient_id,a.staff_id,s.client_type,a.must_change_password,s.csrf_token_hash
            FROM auth_session s JOIN account a ON a.account_id=s.account_id
            WHERE s.token_hash=? AND s.client_type=? AND s.revoked_at IS NULL AND s.expires_at>?
            AND a.enabled AND a.auth_version=s.auth_version
            AND ((s.client_type='staff_web' AND a.staff_id IS NOT NULL) OR (s.client_type='patient_miniapp' AND a.patient_id IS NOT NULL))
            """,(r,n)->new Principal(r.getLong(1),r.getLong(2),(Long)r.getObject(3),(Long)r.getObject(4),r.getString(5),r.getBoolean(6),r.getString(7)),hash(token),clientType,java.sql.Timestamp.from(clock.instant()));
        if(matches.size()!=1)throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return matches.getFirst();
    }
    public void requireCsrf(Principal principal,String csrf){
        if(csrf==null||!csrf.matches("[A-Za-z0-9_-]{43}")||principal.csrfHash()==null||
            !MessageDigest.isEqual(hash(csrf).getBytes(StandardCharsets.US_ASCII),principal.csrfHash().getBytes(StandardCharsets.US_ASCII)))throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    @Transactional public void revoke(Principal principal){jdbc.update("UPDATE auth_session SET revoked_at=? WHERE session_id=? AND account_id=? AND revoked_at IS NULL",java.sql.Timestamp.from(clock.instant()),principal.sessionId(),principal.accountId());}
}
