package cn.dental.identity.application;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.*;
import java.time.Clock;
import cn.dental.organization.application.StaffEligibilityService;
import java.sql.Timestamp;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
@Service
public class AccountAdminService {
    private final JdbcTemplate jdbc; private final AuthorizationService authorization; private final StaffPassword passwords;
    private final IdempotencyService idempotency; private final AuditService audit; private final Clock clock;
    private final StaffEligibilityService eligibility;
    public AccountAdminService(JdbcTemplate jdbc,AuthorizationService authorization,StaffPassword passwords,IdempotencyService idempotency,AuditService audit,Clock clock,StaffEligibilityService eligibility){
        this.jdbc=jdbc;this.authorization=authorization;this.passwords=passwords;this.idempotency=idempotency;this.audit=audit;this.clock=clock;this.eligibility=eligibility;
    }
    @Transactional public void disable(SessionService.Principal actor,long target,String reason){
        if(target<=0 || reason==null || reason.isBlank() || reason.length()>300)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        // Serialize identity mutations so concurrent requests cannot remove the last two administrators.
        jdbc.execute("SELECT pg_advisory_xact_lock(734192014)");
        jdbc.queryForList("SELECT account_id FROM account WHERE account_id IN (?,?) ORDER BY account_id FOR UPDATE",actor.accountId(),target);
        authorization.requireAccountAdmin(actor);
        var rows=jdbc.queryForList("SELECT enabled,staff_id,password_hash FROM account WHERE account_id=?",target);
        if(rows.isEmpty() || rows.getFirst().get("staff_id")==null)throw new ApiException(ApiErrorCode.NOT_FOUND);
        if(rows.getFirst().get("password_hash")==null)throw new ApiException(ApiErrorCode.NOT_ACTIVATED); // 待激活账号拒绝停用，不能把停用当取消首次激活资格
        if(!Boolean.TRUE.equals(rows.getFirst().get("enabled")))return; // 已激活后停用：重复停用幂等，不再递增版本或写审计
        int admin=jdbc.queryForObject("SELECT count(*) FROM account_role ar JOIN role r USING(role_id) WHERE ar.account_id=? AND r.code='sysadmin'",Integer.class,target);
        if(admin>0){
            int others=jdbc.queryForObject("SELECT count(*) FROM account a JOIN staff s USING(staff_id) JOIN account_role ar USING(account_id) JOIN role r USING(role_id) WHERE r.code='sysadmin' AND a.enabled AND a.password_hash IS NOT NULL AND s.employment_state='在岗' AND a.account_id<>?",Integer.class,target);
            if(others==0)throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
        jdbc.update("UPDATE account SET enabled=false,auth_version=auth_version+1 WHERE account_id=?",target);
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),target);
        jdbc.update("INSERT INTO audit_log(actor_account_id,actor_type,target_type,target_id,operation,reason,occurred_at) VALUES(?,'用户','account',?,'停用员工账号',?,?)",actor.accountId(),target,reason,Timestamp.from(clock.instant()));
    }
    public PageResult<java.util.Map<String,Object>> list(SessionService.Principal actor,String cursor,Integer requestedLimit){
        authorization.requireAccountAdmin(actor);int limit=PageResult.validateLimit(requestedLimit);long after=0;
        if(cursor!=null){try{after=Long.parseLong(cursor);if(after<=0)throw new NumberFormatException();}catch(NumberFormatException e){throw new ApiException(ApiErrorCode.INVALID_REQUEST);}}
        var rows=jdbc.query("SELECT account_id,login_name,enabled,password_hash FROM account WHERE staff_id IS NOT NULL AND account_id>? ORDER BY account_id LIMIT ?",(r,n)->{boolean enabled=r.getBoolean(3);boolean activated=r.getString(4)!=null;return java.util.Map.<String,Object>of("account_id",Long.toString(r.getLong(1)),"login_name",r.getString(2)==null?"":r.getString(2),"enabled",enabled,"state",!activated?"pending_activation":(enabled?"active":"disabled"));},after,limit+1);
        boolean more=rows.size()>limit;var page=rows.subList(0,Math.min(limit,rows.size()));
        return new PageResult<>(page,more?(String)page.getLast().get("account_id"):null);
    }

    /**
     * 受控读取目标员工账号的角色门店授权。
     * 仅账号管理员可读；患者账号/不存在统一 NOT_FOUND，不泄漏区别。
     * REPEATABLE READ 单事务读取 version 与角色，保证二者来自同一快照；version 是
     * account.auth_version 的并发令牌，不是登录凭证。
     */
    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public java.util.Map<String,Object> roleScopes(SessionService.Principal actor,long target){
        if(target<=0)throw new ApiException(ApiErrorCode.NOT_FOUND);
        authorization.requireAccountAdmin(actor);
        var rows=jdbc.queryForList("SELECT enabled,password_hash,auth_version FROM account WHERE account_id=? AND staff_id IS NOT NULL",target);
        if(rows.isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND);
        var row=rows.getFirst();
        boolean activated=row.get("password_hash")!=null;
        boolean enabled=Boolean.TRUE.equals(row.get("enabled"));
        String state=!activated?"pending_activation":(enabled?"active":"disabled");
        var roles=new java.util.ArrayList<java.util.Map<String,Object>>();
        for(var role:jdbc.queryForList("SELECT r.role_id,r.code,ar.approval_basis FROM account_role ar JOIN role r USING(role_id) WHERE ar.account_id=? ORDER BY r.code",target)){
            var branches=jdbc.query("SELECT branch_id FROM role_branch_scope WHERE account_id=? AND role_id=? ORDER BY branch_id",(r,n)->Long.toString(r.getLong(1)),target,role.get("role_id"));
            var item=new java.util.LinkedHashMap<String,Object>();
            item.put("role_code",role.get("code"));
            item.put("branch_ids",branches);
            item.put("approval_basis",role.get("approval_basis"));
            roles.add(item);
        }
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("account_id",Long.toString(target));
        result.put("state",state);
        result.put("version",((Number)row.get("auth_version")).intValue());
        result.put("roles",roles);
        return result;
    }

    public record Grant(String role_code,java.util.List<String> branch_ids,String approval_basis){}
    @Transactional public java.util.Map<String,Object> setRoles(SessionService.Principal actor,long target,int expectedVersion,java.util.List<Grant> grants){
        if(target==actor.accountId())throw new ApiException(ApiErrorCode.FORBIDDEN);
        if(target<=0 || expectedVersion<=0)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        jdbc.execute("SELECT pg_advisory_xact_lock(734192014)");
        jdbc.queryForList("SELECT account_id FROM account WHERE account_id IN (?,?) ORDER BY account_id FOR UPDATE",actor.accountId(),target);
        authorization.requireAccountAdmin(actor);
        var targetRows=jdbc.queryForList("SELECT a.staff_id,s.staff_type,s.employment_state,a.auth_version FROM account a JOIN staff s USING(staff_id) WHERE account_id=?",target);
        if(targetRows.isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND);
        var employee=targetRows.getFirst();
        // 版本检查必须在任何角色/会话/审计写入之前；auth_version 也随改密、停用变化，
        // 因此这些变化后旧版本会保守地要求重新读取，而不是静默覆盖。
        int currentVersion=((Number)employee.get("auth_version")).intValue();
        if(expectedVersion!=currentVersion)throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        if(grants==null || grants.size()>7)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if(!"在岗".equals(employee.get("employment_state")))throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        var requested=new java.util.TreeMap<String,Grant>();
        for(var grant:grants){
            if(grant==null || grant.role_code()==null || !java.util.Set.of("sysadmin","owner","dentist","frontdesk","inventory","finance").contains(grant.role_code()) || grant.approval_basis()==null || grant.approval_basis().isBlank() || grant.approval_basis().length()>300 || grant.branch_ids()==null || grant.branch_ids().size()>100)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            var branches=new java.util.TreeSet<String>();
            for(String text:grant.branch_ids()){
                long branch;try{branch=Long.parseLong(text);if(branch<=0)throw new NumberFormatException();}catch(RuntimeException e){throw new ApiException(ApiErrorCode.VALIDATION_FAILED);}
                if(!branches.add(Long.toString(branch)))throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                int assigned=jdbc.queryForObject("SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=? AND enabled",Integer.class,employee.get("staff_id"),branch);
                if(assigned!=1)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
            if("sysadmin".equals(grant.role_code()) && !branches.isEmpty())throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            if("dentist".equals(grant.role_code())){
                // Existing maintenance/account locks precede the shared staff lock.
                // A role grant is not a clinical admission; future booking still checks eligibility.
                if(!eligibility.isCurrentlyPracticeQualified(((Number)employee.get("staff_id")).longValue()))
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
            }
            if(requested.put(grant.role_code(),new Grant(grant.role_code(),java.util.List.copyOf(branches),grant.approval_basis()))!=null)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        }
        var existing=new java.util.TreeMap<String,Grant>();
        for(var row:jdbc.queryForList("SELECT r.code,r.role_id,ar.approval_basis FROM account_role ar JOIN role r USING(role_id) WHERE ar.account_id=?",target)){
            var branches=jdbc.query("SELECT branch_id FROM role_branch_scope WHERE account_id=? AND role_id=?",(r,n)->Long.toString(r.getLong(1)),target,row.get("role_id"));branches.sort(String::compareTo);
            String code=(String)row.get("code");existing.put(code,new Grant(code,branches,(String)row.get("approval_basis")));
        }
        if(existing.equals(requested))return java.util.Map.of("account_id",Long.toString(target),"version",currentVersion);
        if(existing.containsKey("sysadmin") && !requested.containsKey("sysadmin")){
            int remaining=jdbc.queryForObject("SELECT count(*) FROM account a JOIN staff s USING(staff_id) JOIN account_role ar USING(account_id) JOIN role r USING(role_id) WHERE r.code='sysadmin' AND a.enabled AND a.password_hash IS NOT NULL AND s.employment_state='在岗' AND a.account_id<>?",Integer.class,target);
            if(remaining==0)throw new ApiException(ApiErrorCode.FORBIDDEN);
        }
        // Empty replacement is revocation only; it can never grant any privilege.
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=?",target);jdbc.update("DELETE FROM account_role WHERE account_id=?",target);
        for(var grant:requested.values()){
            long role=jdbc.queryForObject("SELECT role_id FROM role WHERE code=?",Long.class,grant.role_code());
            jdbc.update("INSERT INTO account_role(account_id,role_id,granted_by,granted_at,approval_basis) VALUES(?,?,?,?,?)",target,role,actor.accountId(),Timestamp.from(clock.instant()),grant.approval_basis());
            for(String branch:grant.branch_ids())jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(?,?,?)",target,role,Long.parseLong(branch));
        }
        int newVersion=jdbc.queryForObject("UPDATE account SET auth_version=auth_version+1 WHERE account_id=? RETURNING auth_version",Integer.class,target);
        jdbc.update("UPDATE auth_session SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),target);
        // Preserve grant approval references and old scopes in append-only audit, never credentials.
        jdbc.update("INSERT INTO audit_log(actor_account_id,actor_type,target_type,target_id,operation,before_data,after_data,occurred_at) VALUES(?,'用户','account',?,'替换角色门店范围',?,?,?)",actor.accountId(),target,existing.toString(),requested.toString(),Timestamp.from(clock.instant()));
        return java.util.Map.of("account_id",Long.toString(target),"version",newVersion);
    }

    public record CreateOutcome(java.util.Map<String,Object> account, boolean replayed) {}

    /**
     * 幂等创建待激活员工账号。锁序固定：账户维护锁 734192014 → 幂等键 advisory 锁 → 目标员工/账号行锁；
     * 绝不在取得行锁后再反向取 734192014，与 disable/setRoles 的维护锁先行顺序一致。仅有效且已完成强制
     * 改密的 sysadmin 可调用（requireAccountAdmin 进锁前与取得幂等锁后各验一次）；目标须在岗且存在有效
     * 已核验 staff_branch 任职；不自动授予任何角色；不生成默认密码、不把明文写响应/幂等记录。
     * 幂等作用域（账号+操作+键）：同键同内容重放只重建最小白名单响应并核验目标可见；同键不同内容 409；
     * 不同键同员工/同登录名统一转换为业务 409（不泄漏他人身份）。授权与提交真正并发的撤权仍属既有窗口，
     * 由账号维护锁 734192014 与本方法对账号行的 FOR UPDATE 协同，不声称彻底消除所有竞争。
     */
    @Transactional public CreateOutcome create(SessionService.Principal actor,String staffIdText,String loginName,String idempotencyKey){
        long staffId=parseStaffId(staffIdText);
        String normalized=passwords.normalize(loginName);
        String content=IdempotencyService.canonicalize(java.util.Map.of("staff_id",staffId,"login_name",normalized));
        jdbc.execute("SELECT pg_advisory_xact_lock(734192014)");
        IdempotencyService.Outcome out=idempotency.execute(
            actor.accountId(),"staff-account-create",idempotencyKey,content,
            ()->authorization.requireAccountAdmin(actor),
            ()->doCreate(actor,staffId,normalized),
            targetId->requireAccountVisible(staffId,targetId));
        return new CreateOutcome(summary(out.targetId(),normalized,staffId),out.replayed());
    }

    private long parseStaffId(String text){
        long staffId;
        try{staffId=Long.parseLong(text);}catch(RuntimeException e){throw new ApiException(ApiErrorCode.VALIDATION_FAILED);}
        if(staffId<=0)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return staffId;
    }

    private IdempotencyService.BusinessResult doCreate(SessionService.Principal actor,long staffId,String normalized){
        var staffRows=jdbc.queryForList("SELECT employment_state FROM staff WHERE staff_id=? FOR UPDATE",staffId);
        if(staffRows.isEmpty())throw new ApiException(ApiErrorCode.NOT_FOUND);
        if(!"在岗".equals(staffRows.getFirst().get("employment_state")))throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        int assigned=jdbc.queryForObject("SELECT count(*) FROM staff_branch WHERE staff_id=? AND enabled AND verified_at IS NOT NULL",Integer.class,staffId);
        if(assigned==0)throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if(!jdbc.queryForList("SELECT account_id FROM account WHERE staff_id=? FOR UPDATE",staffId).isEmpty())throw new ApiException(ApiErrorCode.ACCOUNT_EXISTS);
        long accountId;
        try{
            accountId=jdbc.queryForObject("INSERT INTO account(login_name,staff_id,enabled,password_hash,must_change_password,created_at) VALUES(?,?,false,NULL,true,?) RETURNING account_id",Long.class,normalized,staffId,Timestamp.from(clock.instant()));
        }catch(DuplicateKeyException e){
            throw new ApiException(ApiErrorCode.ACCOUNT_EXISTS); // 登录名已被另一员工占用，不泄漏其身份
        }
        audit.user(actor.accountId(),"account",accountId,"创建员工账号",null,java.util.Map.of("enabled","false"),null);
        return new IdempotencyService.BusinessResult(accountId,"CREATED",Long.toString(accountId));
    }

    private void requireAccountVisible(long staffId,long accountId){
        if(jdbc.queryForObject("SELECT count(*) FROM account WHERE account_id=? AND staff_id=?",Integer.class,accountId,staffId)!=1)throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private java.util.Map<String,Object> summary(long accountId,String normalized,long staffId){
        var row=jdbc.queryForMap("SELECT enabled,must_change_password,password_hash FROM account WHERE account_id=?",accountId);
        boolean enabled=Boolean.TRUE.equals(row.get("enabled"));
        boolean activated=row.get("password_hash")!=null; // 不返回 password_hash，仅用于区分待激活/已激活后停用
        return java.util.Map.of("account_id",Long.toString(accountId),"login_name",normalized,"staff_id",Long.toString(staffId),
            "enabled",enabled,"must_change_password",Boolean.TRUE.equals(row.get("must_change_password")),
            "state",!activated?"pending_activation":(enabled?"active":"disabled"));
    }
}
