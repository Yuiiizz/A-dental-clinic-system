package cn.dental.identity.application;
import cn.dental.shared.web.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class AuthorizationService {
    private final JdbcTemplate jdbc; private final Clock clock;
    public AuthorizationService(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    public void requireCurrent(SessionService.Principal p){
        int found=jdbc.queryForObject("""
            SELECT count(*) FROM account a JOIN auth_session s ON s.account_id=a.account_id
            WHERE a.account_id=? AND s.session_id=? AND a.enabled AND a.auth_version=s.auth_version
            AND s.revoked_at IS NULL AND s.expires_at>?
            AND (a.staff_id IS NULL OR EXISTS(SELECT 1 FROM staff st WHERE st.staff_id=a.staff_id AND st.employment_state='在岗'))
            """,Integer.class,p.accountId(),p.sessionId(),Timestamp.from(clock.instant()));
        if(found!=1)throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
    }
    public void requireBranch(SessionService.Principal p,String permission,long branch){
        requireCurrent(p);
        if(!SessionService.STAFF.equals(p.clientType()) || p.mustChangePassword())throw new ApiException(ApiErrorCode.FORBIDDEN);
        int found=jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission perm ON perm.permission_id=rp.permission_id
            JOIN role_branch_scope bs ON bs.account_id=ar.account_id AND bs.role_id=ar.role_id
            JOIN staff_branch sb ON sb.staff_id=? AND sb.branch_id=bs.branch_id
                AND sb.enabled AND sb.verified_at IS NOT NULL
            WHERE ar.account_id=? AND perm.code=? AND bs.branch_id=?
            """,Integer.class,p.staffId(),p.accountId(),permission,branch);
        if(found==0)throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    /** Installation-level identity maintenance only; never a bypass for branch business access. */
    public void requireAccountAdmin(SessionService.Principal p){
        requireCurrent(p);
        if(!SessionService.STAFF.equals(p.clientType()) || p.mustChangePassword())throw new ApiException(ApiErrorCode.FORBIDDEN);
        int found=jdbc.queryForObject("SELECT count(*) FROM account_role ar JOIN role r USING(role_id) JOIN role_permission rp USING(role_id) JOIN permission pm USING(permission_id) WHERE ar.account_id=? AND r.code='sysadmin' AND pm.code='accounts.manage'",Integer.class,p.accountId());
        if(found!=1)throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    /** Global staff maintenance requires the unique responsible staff member with owner+staff.manage_global. */
    public void requireOrganizationOwner(SessionService.Principal p){
        requireCurrent(p);
        if(!SessionService.STAFF.equals(p.clientType()) || p.mustChangePassword() || p.staffId()==null)throw new ApiException(ApiErrorCode.FORBIDDEN);
        int allowed=jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role r ON r.role_id=ar.role_id AND r.code='owner'
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='staff.manage_global'
            WHERE ar.account_id=?
            """,Integer.class,p.accountId());
        if(allowed!=1)throw new ApiException(ApiErrorCode.FORBIDDEN);
        List<Map<String,Object>> orgs=jdbc.queryForList("SELECT organization_id,responsible_staff_id,state FROM organization");
        if(orgs.isEmpty())throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_NOT_CONFIGURED);
        if(orgs.size()>1)throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_AMBIGUOUS);
        Map<String,Object> org=orgs.getFirst();
        if("停用".equals(org.get("state")))throw new ApiException(ApiErrorCode.STAFF_ORGANIZATION_DISABLED);
        if(((Number)org.get("responsible_staff_id")).longValue()!=p.staffId())throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    /** Non-throwing counterpart for list/detail routing; zero/multi/disabled org is simply not global. */
    public boolean isOrganizationOwner(SessionService.Principal p){
        requireCurrent(p);
        if(!SessionService.STAFF.equals(p.clientType()) || p.mustChangePassword() || p.staffId()==null)return false;
        int allowed=jdbc.queryForObject("""
            SELECT count(*) FROM account_role ar
            JOIN role r ON r.role_id=ar.role_id AND r.code='owner'
            JOIN role_permission rp ON rp.role_id=ar.role_id
            JOIN permission pm ON pm.permission_id=rp.permission_id AND pm.code='staff.manage_global'
            WHERE ar.account_id=?
            """,Integer.class,p.accountId());
        if(allowed!=1)return false;
        List<Map<String,Object>> orgs=jdbc.queryForList("SELECT responsible_staff_id,state FROM organization");
        return orgs.size()==1 && !"停用".equals(orgs.getFirst().get("state"))
            && ((Number)orgs.getFirst().get("responsible_staff_id")).longValue()==p.staffId();
    }
    public Map<String,Object> me(SessionService.Principal p){
        requireCurrent(p);
        String name=jdbc.queryForObject("SELECT coalesce(s.name,pt.name) FROM account a LEFT JOIN staff s ON s.staff_id=a.staff_id LEFT JOIN patient pt ON pt.patient_id=a.patient_id WHERE a.account_id=?",String.class,p.accountId());
        var roles=new ArrayList<Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT r.role_id,r.code FROM account_role ar JOIN role r USING(role_id) WHERE ar.account_id=? ORDER BY r.code",p.accountId())){
            var branches=jdbc.query("SELECT branch_id FROM role_branch_scope WHERE account_id=? AND role_id=? ORDER BY branch_id",(rs,n)->Long.toString(rs.getLong(1)),p.accountId(),row.get("role_id"));
            roles.add(Map.of("role_code",row.get("code"),"branch_ids",branches));
        }
        return Map.of("account_id",Long.toString(p.accountId()),"name",name,"identity_type",p.staffId()==null?"patient":"staff","roles",roles,"must_change_password",p.mustChangePassword());
    }
}
