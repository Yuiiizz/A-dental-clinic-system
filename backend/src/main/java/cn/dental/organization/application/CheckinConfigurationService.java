package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckinConfigurationService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final Clock clock;

    public CheckinConfigurationService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit, Clock clock) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency;
        this.audit=audit; this.clock=clock;
    }

    public record Change(boolean selfCheckinEnabled, Integer earlyMinutes, Integer lateMinutes,
                         int slotStepMinutes, int expectedVersion) {}

    @Transactional(readOnly=true)
    public Map<String,Object> view(SessionService.Principal actor, long branchId) {
        requireAuthorized(actor,branchId);
        return current(branchId);
    }

    @Transactional
    public Map<String,Object> update(SessionService.Principal actor, long branchId, Change change, String key) {
        if (branchId<=0 || change==null || change.expectedVersion()<1 || change.slotStepMinutes()<1
                || change.earlyMinutes()!=null && change.earlyMinutes()<0
                || change.lateMinutes()!=null && change.lateMinutes()<0
                || change.selfCheckinEnabled() && (change.earlyMinutes()==null || change.lateMinutes()==null))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String,Object> content=new LinkedHashMap<>();
        content.put("branch_id",branchId);
        content.put("self_checkin_enabled",change.selfCheckinEnabled());
        content.put("early_minutes",change.earlyMinutes());
        content.put("late_minutes",change.lateMinutes());
        content.put("slot_step_minutes",change.slotStepMinutes());
        content.put("expected_version",change.expectedVersion());
        idempotency.execute(actor.accountId(),"branch-booking-config",key,
            IdempotencyService.canonicalize(content),
            ()->requireAuthorized(actor,branchId),
            ()->{
                jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",branchId);
                requireAuthorized(actor,branchId);
                List<Map<String,Object>> old=jdbc.queryForList("SELECT * FROM branch_booking_config WHERE branch_id=? FOR UPDATE",branchId);
                int version=old.isEmpty()?1:((Number)old.getFirst().get("version")).intValue();
                if (version!=change.expectedVersion()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                if (old.isEmpty()) {
                    jdbc.update("""
                        INSERT INTO branch_booking_config(branch_id,self_checkin_enabled,early_minutes,late_minutes,
                            slot_step_minutes,version,updated_by,updated_at) VALUES(?,?,?,?,?,1,?,?)
                        """,branchId,change.selfCheckinEnabled(),change.earlyMinutes(),change.lateMinutes(),
                        change.slotStepMinutes(),actor.staffId(),Timestamp.from(clock.instant()));
                    audit.user(actor.accountId(),"branch_booking_config",branchId,"create",null,summary(change),"设置签到窗口");
                } else if (!same(old.getFirst(),change)) {
                    Map<String,Object> before=old.getFirst();
                    jdbc.update("""
                        UPDATE branch_booking_config SET self_checkin_enabled=?,early_minutes=?,late_minutes=?,
                          slot_step_minutes=?,version=version+1,updated_by=?,updated_at=? WHERE branch_id=?
                        """,change.selfCheckinEnabled(),change.earlyMinutes(),change.lateMinutes(),
                        change.slotStepMinutes(),actor.staffId(),Timestamp.from(clock.instant()),branchId);
                    audit.user(actor.accountId(),"branch_booking_config",branchId,"update",summary(before),summary(change),"修改签到窗口");
                }
                return new IdempotencyService.BusinessResult(branchId,"CONFIGURED",null);
            }, id->requireAuthorized(actor,id));
        return current(branchId);
    }

    private void requireAuthorized(SessionService.Principal actor,long branchId) {
        authorization.requireBranch(actor,"branch.configure",branchId);
    }

    private Map<String,Object> current(long branchId) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT b.branch_id,c.self_checkin_enabled,c.early_minutes,c.late_minutes,
                   c.slot_step_minutes,c.version,c.updated_at
            FROM branch b LEFT JOIN branch_booking_config c ON c.branch_id=b.branch_id WHERE b.branch_id=?
            """,branchId);
        if (rows.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        Map<String,Object> row=rows.getFirst();
        boolean configured=row.get("version")!=null;
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("branch_id",Long.toString(branchId));
        result.put("configured",configured);
        result.put("self_checkin_enabled",configured && Boolean.TRUE.equals(row.get("self_checkin_enabled")));
        result.put("early_minutes",row.get("early_minutes"));
        result.put("late_minutes",row.get("late_minutes"));
        result.put("slot_step_minutes",configured?row.get("slot_step_minutes"):15);
        result.put("version",configured?row.get("version"):1);
        result.put("updated_at",row.get("updated_at")==null?null:((Timestamp)row.get("updated_at")).toInstant().toString());
        return result;
    }

    private static boolean same(Map<String,Object> row,Change c) {
        return Boolean.TRUE.equals(row.get("self_checkin_enabled"))==c.selfCheckinEnabled()
            && java.util.Objects.equals(row.get("early_minutes"),c.earlyMinutes())
            && java.util.Objects.equals(row.get("late_minutes"),c.lateMinutes())
            && ((Number)row.get("slot_step_minutes")).intValue()==c.slotStepMinutes();
    }
    private static Map<String,String> summary(Change c) {
        return Map.of("self_checkin_enabled",Boolean.toString(c.selfCheckinEnabled()),
            "early_minutes",String.valueOf(c.earlyMinutes()),"late_minutes",String.valueOf(c.lateMinutes()),
            "slot_step_minutes",Integer.toString(c.slotStepMinutes()));
    }
    private static Map<String,String> summary(Map<String,Object> row) {
        return Map.of("self_checkin_enabled",String.valueOf(row.get("self_checkin_enabled")),
            "early_minutes",String.valueOf(row.get("early_minutes")),"late_minutes",String.valueOf(row.get("late_minutes")),
            "slot_step_minutes",String.valueOf(row.get("slot_step_minutes")));
    }
}
