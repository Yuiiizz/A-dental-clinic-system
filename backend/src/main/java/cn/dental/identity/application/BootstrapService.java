package cn.dental.identity.application;
import cn.dental.shared.web.*;
import java.time.Clock;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Called only by the local interactive bootstrap command; never exposed as HTTP. */
@Service
public class BootstrapService {
    private final JdbcTemplate jdbc;private final StaffPassword passwords;private final Clock clock;
    public BootstrapService(JdbcTemplate jdbc,StaffPassword passwords,Clock clock){this.jdbc=jdbc;this.passwords=passwords;this.clock=clock;}
    @Transactional public long initialize(String name,String login,String password,String basis){
        if(name==null||name.isBlank()||name.length()>60||basis==null||basis.isBlank()||basis.length()>300)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String normalized=passwords.normalize(login);String hash=passwords.encode(password);
        jdbc.execute("SELECT pg_advisory_xact_lock(734192013)");
        // Never re-bootstrap merely because the last administrator was disabled or lost a role.
        if(jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id IS NOT NULL",Long.class)>0)throw new ApiException(ApiErrorCode.FORBIDDEN);
        long staff=jdbc.queryForObject("INSERT INTO staff(name,staff_type,job_title,employment_state) VALUES(?,'其他人员','系统管理员','在岗') RETURNING staff_id",Long.class,name.strip());
        long account=jdbc.queryForObject("INSERT INTO account(login_name,staff_id,enabled,password_hash,must_change_password,created_at) VALUES(?,?,true,?,true,?) RETURNING account_id",Long.class,normalized,staff,hash,Timestamp.from(clock.instant()));
        int granted=jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT ?,role_id,?,? FROM role WHERE code='sysadmin'",account,Timestamp.from(clock.instant()),basis);
        if(granted!=1)throw new IllegalStateException("Administrator role is missing");
        jdbc.update("INSERT INTO audit_log(actor_type,target_type,target_id,operation,reason,occurred_at) VALUES('系统','account',?,'首次管理员初始化',?,?)",account,basis,Timestamp.from(clock.instant()));
        return account;
    }
}
