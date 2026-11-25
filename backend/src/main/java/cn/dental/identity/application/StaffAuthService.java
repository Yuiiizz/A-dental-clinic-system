package cn.dental.identity.application;
import cn.dental.shared.web.*;
import java.time.Clock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class StaffAuthService {
    private final JdbcTemplate jdbc; private final StaffPassword passwords; private final SessionService sessions; private final Clock clock;
    public StaffAuthService(JdbcTemplate jdbc,StaffPassword passwords,SessionService sessions,Clock clock){this.jdbc=jdbc;this.passwords=passwords;this.sessions=sessions;this.clock=clock;}
    public record LoginResult(SessionService.Issued session,boolean mustChangePassword){@Override public String toString(){return "LoginResult[redacted]";}}
    @Transactional public LoginResult login(String name,String password){
        String normalized=passwords.normalize(name);
        var rows=jdbc.queryForList("SELECT a.*,s.employment_state FROM account a JOIN staff s ON s.staff_id=a.staff_id WHERE a.login_name=? FOR UPDATE OF a",normalized);
        var row=rows.isEmpty()?null:rows.getFirst();
        boolean valid=passwords.matches(password,row==null?null:(String)row.get("password_hash"));
        if(!valid || row==null || !Boolean.TRUE.equals(row.get("enabled")) || !"在岗".equals(row.get("employment_state"))) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        long id=((Number)row.get("account_id")).longValue();
        var issued=sessions.issue(id,SessionService.STAFF);
        audit(id,"员工登录");
        return new LoginResult(issued,Boolean.TRUE.equals(row.get("must_change_password")));
    }
    @Transactional public LoginResult change(SessionService.Principal principal,String current,String replacement){
        if(!SessionService.STAFF.equals(principal.clientType()))throw new ApiException(ApiErrorCode.FORBIDDEN);
        passwords.validate(replacement);
        var row=jdbc.queryForMap("SELECT * FROM account WHERE account_id=? FOR UPDATE",principal.accountId());
        Integer live=jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE session_id=? AND account_id=? AND revoked_at IS NULL AND expires_at>? AND auth_version=?",Integer.class,principal.sessionId(),principal.accountId(),Timestamp.from(clock.instant()),row.get("auth_version"));
        if(!Boolean.TRUE.equals(row.get("enabled")) || live==null || live!=1 || !passwords.matches(current,(String)row.get("password_hash")))throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        if(passwords.matches(replacement,(String)row.get("password_hash")))throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.update("UPDATE account SET password_hash=?,must_change_password=false,auth_version=auth_version+1 WHERE account_id=?",passwords.encode(replacement),principal.accountId());
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),principal.accountId());
        var issued=sessions.issue(principal.accountId(),SessionService.STAFF);
        audit(principal.accountId(),"员工改密");
        return new LoginResult(issued,false);
    }
    private void audit(long id,String action){jdbc.update("INSERT INTO audit_log(actor_account_id,actor_type,target_type,target_id,operation,occurred_at) VALUES(?,'用户','account',?,?,?)",id,id,action,Timestamp.from(clock.instant()));}
}
