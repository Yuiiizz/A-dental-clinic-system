package cn.dental.identity.application;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务器本地非Web首次激活（仅 AccountActivationCommand 调用，不提供 HTTP 接口）。
 *
 * 只能激活尚无 password_hash 的员工待激活账号；禁止借此重置密码或重新启用已激活后停用的账号
 * （只要 password_hash 已存在即拒绝，不因 enabled 反复变化绕过）。验证在岗及有效已核验 staff_branch
 * 任职，以行锁保护；写摘要、启用、强制改密与审计同事务；不自动授予角色；不把密码写命令行、日志或审计。
 *
 * 锁序与账号维护一致：账户维护锁 734192014 → 目标账号行锁（FOR UPDATE OF a）；不与预约业务锁混淆。
 */
@Service
public class AccountActivationService {
    private final JdbcTemplate jdbc; private final StaffPassword passwords; private final AuditService audit; private final Clock clock;
    public AccountActivationService(JdbcTemplate jdbc,StaffPassword passwords,AuditService audit,Clock clock){this.jdbc=jdbc;this.passwords=passwords;this.audit=audit;this.clock=clock;}

    @Transactional public long activate(String loginName,String password,String basis){
        if(basis==null||basis.isBlank()||basis.length()>300)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String normalized=passwords.normalize(loginName);
        String hash=passwords.encode(password); // 复用 StaffPassword 规则：≥12 码点、≤72 UTF-8 字节
        jdbc.execute("SELECT pg_advisory_xact_lock(734192014)");
        var rows=jdbc.queryForList("""
            SELECT a.account_id,a.staff_id,a.password_hash,s.employment_state
            FROM account a JOIN staff s ON s.staff_id=a.staff_id
            WHERE a.login_name=? FOR UPDATE OF a
            """,normalized);
        if(rows.isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND); // 不存在或为患者账号（staff_id 为空不参与连接）
        var row=rows.getFirst();
        if(row.get("password_hash")!=null)throw new ApiException(ApiErrorCode.FORBIDDEN); // 已激活或已停用旧账号，禁止借此重置/重新启用
        if(!"在岗".equals(row.get("employment_state")))throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        int assigned=jdbc.queryForObject("SELECT count(*) FROM staff_branch WHERE staff_id=? AND enabled AND verified_at IS NOT NULL",Integer.class,row.get("staff_id"));
        if(assigned==0)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long accountId=((Number)row.get("account_id")).longValue();
        jdbc.update("UPDATE account SET password_hash=?,enabled=true,must_change_password=true,auth_version=auth_version+1 WHERE account_id=?",hash,accountId);
        audit.system("account",accountId,"员工账号首次激活",Map.of("enabled","false"),Map.of("enabled","true"),basis);
        return accountId;
    }
}
