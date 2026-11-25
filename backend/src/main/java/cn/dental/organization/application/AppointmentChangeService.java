package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Candidate and patient answer only. An accepted proposal does not reserve or release a slot. */
@Service
public class AppointmentChangeService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final BookingAvailabilityService availability;
    private final ResourceAllocationService resources;
    private final Clock clock;

    public AppointmentChangeService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit,
            BookingAvailabilityService availability, ResourceAllocationService resources, Clock clock) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency;
        this.audit=audit; this.availability=availability; this.resources=resources; this.clock=clock;
    }

    public record Candidate(long scheduleId,long itemId,String startsAt,long policyId) {}

    @Transactional
    public Map<String,Object> propose(SessionService.Principal actor,long originalId,Candidate candidate,
            String reason,Long absenceId,Long unavailableId,String key) {
        if(originalId<=0 || candidate==null || candidate.scheduleId()<=0 || candidate.itemId()<=0
                || candidate.policyId()<=0 || absenceId!=null && absenceId<=0
                || unavailableId!=null && unavailableId<=0 || absenceId!=null && unavailableId!=null
                || reason==null || reason.isBlank() || reason.strip().length()>300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Instant start=parse(candidate.startsAt());
        long sourceBranch=branchOfAppointment(originalId);
        long targetBranch=branchOfSchedule(candidate.scheduleId());
        Runnable authorized=()->{ requireStaff(actor,sourceBranch); requireStaff(actor,targetBranch); };
        Map<String,Object> content=new LinkedHashMap<>();
        content.put("original_id",originalId);content.put("schedule_id",candidate.scheduleId());
        content.put("item_id",candidate.itemId());content.put("starts_at",start.toString());
        content.put("policy_id",candidate.policyId());content.put("reason",reason.strip());
        content.put("absence_id",absenceId);content.put("unavailable_id",unavailableId);
        var outcome=idempotency.execute(actor.accountId(),"appointment-change-propose",key,
            IdempotencyService.canonicalize(content),authorized,()->{
                for(long branch:new TreeSet<>(List.of(sourceBranch,targetBranch)))
                    jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",branch);
                List<Long> schedules=jdbc.queryForList(
                    "SELECT schedule_id FROM appointment WHERE appointment_id=?",Long.class,originalId);
                TreeSet<Long> scheduleIds=new TreeSet<>();
                if(!schedules.isEmpty() && schedules.getFirst()!=null) scheduleIds.add(schedules.getFirst());
                scheduleIds.add(candidate.scheduleId());
                for(long schedule:scheduleIds)
                    jdbc.queryForList("SELECT schedule_id FROM schedule WHERE schedule_id=? FOR UPDATE",schedule);
                Map<String,Object> original=jdbc.queryForMap(
                    "SELECT * FROM appointment WHERE appointment_id=? AND branch_id=? FOR UPDATE",originalId,sourceBranch);
                authorized.run();
                if(!"已确认".equals(original.get("state")) ||
                        !((Timestamp)original.get("ends_at")).toInstant().isAfter(clock.instant()))
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                if(original.get("schedule_id")!=null && ((Number)original.get("schedule_id")).longValue()==candidate.scheduleId()
                        && ((Timestamp)original.get("starts_at")).toInstant().equals(start))
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                verifyCause(originalId,original,absenceId,unavailableId);
                int originalItems=jdbc.queryForObject("SELECT count(*) FROM appointment_item WHERE appointment_id=?",
                    Integer.class,originalId);
                int sameItem=jdbc.queryForObject("SELECT count(*) FROM appointment_item WHERE appointment_id=? AND item_id=?",
                    Integer.class,originalId,candidate.itemId());
                if(originalItems!=1 || sameItem!=1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
                var window=availability.requireAvailable(targetBranch,candidate.scheduleId(),candidate.itemId(),start,false);
                authorized.run();
                if(jdbc.queryForObject("""
                    SELECT count(*) FROM booking_policy WHERE policy_id=? AND branch_id=? AND status='published'
                    """,Integer.class,candidate.policyId(),targetBranch)!=1)
                    throw new ApiException(ApiErrorCode.POLICY_CHANGED);
                String uuid=UUID.randomUUID().toString();
                String hash=hash(IdempotencyService.canonicalize(content));
                long proposed=jdbc.queryForObject("""
                    INSERT INTO appointment(patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
                      starts_at,ends_at,visit_type,purpose,special_needs,channel,state,coordination_state,
                      previous_id,followup_plan_id,version,request_key,request_hash,requested_by,branch_id,policy_id)
                    VALUES(?,?,?,NULL,?,?,?,?,?,?,'待确认','待协调',?,?,1,?,?,?,?,?) RETURNING appointment_id
                    """,Long.class,original.get("patient_id"),candidate.scheduleId(),window.dentistId(),
                    Timestamp.from(window.startsAt()),Timestamp.from(window.endsAt()),original.get("visit_type"),
                    original.get("purpose"),original.get("special_needs"),original.get("channel"),
                    originalId,original.get("followup_plan_id"),uuid,hash,actor.accountId(),targetBranch,candidate.policyId());
                jdbc.update("INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity) VALUES(?,?,1)",
                    proposed,candidate.itemId());
                long change;
                try {
                    change=jdbc.queryForObject("""
                        INSERT INTO appointment_change(original_id,proposed_id,absence_id,unavailable_id,
                          proposed_by,proposed_at,reason,state,version)
                        VALUES(?,?,?,?,?,?,?,'待答复',1) RETURNING change_id
                        """,Long.class,originalId,proposed,absenceId,unavailableId,actor.staffId(),
                        Timestamp.from(clock.instant()),reason.strip());
                } catch(DataIntegrityViolationException ex) {
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                }
                if(!"待协调".equals(original.get("coordination_state")))
                    jdbc.update("UPDATE appointment SET coordination_state='待协调',version=version+1 WHERE appointment_id=?",originalId);
                audit.user(actor.accountId(),"appointment_change",change,"propose",null,
                    Map.of("state_to","待答复"),"拟定候选安排");
                return new IdempotencyService.BusinessResult(change,"PENDING",null);
            },id->{
                authorized.run();
                if(jdbc.queryForObject("SELECT count(*) FROM appointment_change WHERE change_id=? AND original_id=?",
                        Integer.class,id,originalId)!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return detail(outcome.targetId());
    }

    @Transactional
    public Map<String,Object> respondPatient(SessionService.Principal actor,long id,boolean accept,
            int expectedVersion,String key) {
        long patient=requirePatient(actor);
        return respond(actor,id,accept,expectedVersion,"患者端明确选择"+(accept?"同意":"拒绝"),key,patient,null);
    }

    @Transactional
    public Map<String,Object> respondStaff(SessionService.Principal actor,long id,boolean accept,
            int expectedVersion,String basis,String key) {
        if(basis==null || basis.isBlank() || basis.strip().length()>300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long branch=branchOfChange(id);
        return respond(actor,id,accept,expectedVersion,basis.strip(),key,null,branch);
    }

    private Map<String,Object> respond(SessionService.Principal actor,long id,boolean accept,int version,
            String basis,String key,Long patient,Long branch) {
        if(id<=0 || version<1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Runnable authorized=()->{
            if(patient!=null) requireOwned(actor,id,patient);
            else requireStaff(actor,branch);
        };
        var outcome=idempotency.execute(actor.accountId(),"appointment-change-response",key,
            IdempotencyService.canonicalize(Map.of("change_id",id,"accept",accept,
                "expected_version",version,"basis",basis)),authorized,()->{
                long source=branchOfChange(id);
                jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",source);
                List<Map<String,Object>> links=jdbc.queryForList(
                    "SELECT original_id,proposed_id FROM appointment_change WHERE change_id=?",id);
                if(links.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
                long originalId=((Number)links.getFirst().get("original_id")).longValue();
                long proposedId=((Number)links.getFirst().get("proposed_id")).longValue();
                Map<String,Object> original=jdbc.queryForMap("SELECT state FROM appointment WHERE appointment_id=? FOR UPDATE",
                    originalId);
                Map<String,Object> proposed=jdbc.queryForMap(
                    "SELECT state FROM appointment WHERE appointment_id=? FOR UPDATE",proposedId);
                Map<String,Object> change=jdbc.queryForMap("SELECT * FROM appointment_change WHERE change_id=? FOR UPDATE",id);
                authorized.run();
                if(((Number)change.get("version")).intValue()!=version)
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                if(!"待答复".equals(change.get("state")) || !"已确认".equals(original.get("state"))
                        || !"待确认".equals(proposed.get("state")))
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                Instant now=clock.instant();
                String state=accept?"已同意":"已拒绝";
                jdbc.update("""
                    UPDATE appointment_change SET state=?,responded_at=?,response_basis=?,version=version+1
                    WHERE change_id=?
                    """,state,Timestamp.from(now),basis,id);
                if(!accept) {
                    int cancelled=jdbc.update("""
                        UPDATE appointment SET state='已取消',cancelled_at=?,cancelled_by_type=?,
                          closure_reason='患者拒绝协调方案',version=version+1
                        WHERE appointment_id=? AND state='待确认'
                        """,Timestamp.from(now),patient!=null?"patient":"clinic",proposedId);
                    if(cancelled!=1) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                }
                audit.user(actor.accountId(),"appointment_change",id,"respond",
                    Map.of("state_from","待答复"),Map.of("state_to",state),"登记方案答复");
                return new IdempotencyService.BusinessResult(id,state,null);
            },target->authorized.run());
        return detail(outcome.targetId());
    }

    /** Withdrawal cancels only the unoccupied candidate; the original booking and any recorded answer remain facts. */
    @Transactional
    public Map<String,Object> withdraw(SessionService.Principal actor,long id,int expectedVersion,
            String reason,String key) {
        if(id<=0 || expectedVersion<1 || reason==null || reason.isBlank() || reason.strip().length()>300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long sourceBranch=branchOfChange(id);
        Runnable authorized=()->requireStaff(actor,sourceBranch);
        String content=IdempotencyService.canonicalize(Map.of("change_id",id,
            "expected_version",expectedVersion,"reason",reason.strip()));
        var outcome=idempotency.execute(actor.accountId(),"appointment-change-withdraw",key,content,
            authorized,()->{
                jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",sourceBranch);
                Map<String,Object> links=jdbc.queryForMap(
                    "SELECT original_id,proposed_id FROM appointment_change WHERE change_id=?",id);
                long originalId=((Number)links.get("original_id")).longValue();
                long proposedId=((Number)links.get("proposed_id")).longValue();
                jdbc.queryForMap("SELECT appointment_id FROM appointment WHERE appointment_id=? FOR UPDATE",originalId);
                Map<String,Object> proposed=jdbc.queryForMap(
                    "SELECT state FROM appointment WHERE appointment_id=? FOR UPDATE",proposedId);
                Map<String,Object> change=jdbc.queryForMap(
                    "SELECT state,version FROM appointment_change WHERE change_id=? FOR UPDATE",id);
                authorized.run();
                if(((Number)change.get("version")).intValue()!=expectedVersion)
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                String state=(String)change.get("state");
                if(!("待答复".equals(state) || "已同意".equals(state))
                        || !"待确认".equals(proposed.get("state")))
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                Instant now=clock.instant();
                jdbc.update("""
                    UPDATE appointment SET state='已取消',cancelled_at=?,cancelled_by_type='clinic',
                      closure_reason='协调方案撤回',version=version+1 WHERE appointment_id=?
                    """,Timestamp.from(now),proposedId);
                jdbc.update("UPDATE appointment_change SET state='已撤回',version=version+1 WHERE change_id=?",id);
                audit.user(actor.accountId(),"appointment_change",id,"withdraw",
                    Map.of("state_from",state),Map.of("state_to","已撤回"),reason.strip());
                return new IdempotencyService.BusinessResult(id,"WITHDRAWN",null);
            },target->{
                authorized.run();
                if(branchOfChange(target)!=sourceBranch) throw new ApiException(ApiErrorCode.NOT_FOUND);
            });
        return detail(outcome.targetId());
    }

    /** Acceptance is a recorded fact; only this operation converts the candidate into an occupied booking. */
    @Transactional
    public Map<String,Object> apply(SessionService.Principal actor,long id,int expectedVersion,String key) {
        if(id<=0 || expectedVersion<1) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long sourceBranch=branchOfChange(id);
        Runnable authorized=()->requireStaff(actor,sourceBranch);
        String content=IdempotencyService.canonicalize(Map.of("change_id",id,"expected_version",expectedVersion));
        var outcome=idempotency.execute(actor.accountId(),"appointment-change-apply",key,content,
            authorized,()->{
                Map<String,Object> links=jdbc.queryForMap("""
                    SELECT c.original_id,c.proposed_id,o.branch_id AS source_branch,
                           o.schedule_id AS source_schedule,p.branch_id AS target_branch,
                           p.schedule_id AS target_schedule
                    FROM appointment_change c JOIN appointment o ON o.appointment_id=c.original_id
                      JOIN appointment p ON p.appointment_id=c.proposed_id WHERE c.change_id=?
                    """,id);
                long originalId=((Number)links.get("original_id")).longValue();
                long proposedId=((Number)links.get("proposed_id")).longValue();
                long targetBranch=((Number)links.get("target_branch")).longValue();
                requireStaff(actor,targetBranch);
                for(long branch:new TreeSet<>(List.of(sourceBranch,targetBranch)))
                    jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",branch);
                TreeSet<Long> scheduleIds=new TreeSet<>();
                if(links.get("source_schedule")!=null)
                    scheduleIds.add(((Number)links.get("source_schedule")).longValue());
                scheduleIds.add(((Number)links.get("target_schedule")).longValue());
                for(long schedule:scheduleIds)
                    jdbc.queryForList("SELECT schedule_id FROM schedule WHERE schedule_id=? FOR UPDATE",schedule);
                Map<String,Object> original=jdbc.queryForMap(
                    "SELECT state,patient_id,branch_id FROM appointment WHERE appointment_id=? FOR UPDATE",originalId);
                Map<String,Object> proposed=jdbc.queryForMap(
                    "SELECT * FROM appointment WHERE appointment_id=? FOR UPDATE",proposedId);
                Map<String,Object> change=jdbc.queryForMap(
                    "SELECT state,version,response_basis FROM appointment_change WHERE change_id=? FOR UPDATE",id);
                authorized.run(); requireStaff(actor,targetBranch);
                if(((Number)change.get("version")).intValue()!=expectedVersion)
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                if(!"已同意".equals(change.get("state")) || !"已确认".equals(original.get("state"))
                        || !"待确认".equals(proposed.get("state"))
                        || ((Number)original.get("patient_id")).longValue()!=((Number)proposed.get("patient_id")).longValue()
                        || !originalIdEquals(proposed.get("previous_id"),originalId))
                    throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
                Instant start=((Timestamp)proposed.get("starts_at")).toInstant();
                long schedule=((Number)proposed.get("schedule_id")).longValue();
                long item=jdbc.queryForObject("SELECT item_id FROM appointment_item WHERE appointment_id=?",
                    Long.class,proposedId);
                var window=availability.requireAvailable(targetBranch,schedule,item,start,false);
                authorized.run(); requireStaff(actor,targetBranch);
                if(jdbc.queryForObject("""
                    SELECT count(*) FROM booking_policy WHERE policy_id=? AND branch_id=? AND status='published'
                    """,Integer.class,proposed.get("policy_id"),targetBranch)!=1)
                    throw new ApiException(ApiErrorCode.POLICY_CHANGED);
                if(!window.endsAt().equals(((Timestamp)proposed.get("ends_at")).toInstant())
                        || window.dentistId()!=((Number)proposed.get("intended_dentist_id")).longValue())
                    throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
                Instant now=clock.instant();
                jdbc.update("""
                    UPDATE appointment SET state='已改约',coordination_state='已处理',
                      cancelled_at=?,cancelled_by_type='clinic',closure_reason='协调方案已落实',version=version+1
                    WHERE appointment_id=?
                    """,Timestamp.from(now),originalId);
                resources.release(originalId);
                try {
                    jdbc.update("""
                        UPDATE appointment SET state='已确认',confirmed_dentist_id=?,confirmed_at=?,
                          coordination_state='已处理',consent_basis=?,version=version+1
                        WHERE appointment_id=?
                        """,window.dentistId(),Timestamp.from(now),change.get("response_basis"),proposedId);
                    resources.reserve(targetBranch,proposedId,Timestamp.from(window.startsAt()),
                        Timestamp.from(window.endsAt()));
                } catch(DataIntegrityViolationException ex) {
                    throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
                }
                jdbc.update("UPDATE appointment_change SET state='已落实',applied_at=?,version=version+1 WHERE change_id=?",
                    Timestamp.from(now),id);
                audit.user(actor.accountId(),"appointment_change",id,"apply",
                    Map.of("state_from","已同意"),Map.of("state_to","已落实"),"落实已同意协调方案");
                return new IdempotencyService.BusinessResult(id,"APPLIED",null);
            },target->{
                authorized.run();
                if(branchOfChange(target)!=sourceBranch) throw new ApiException(ApiErrorCode.NOT_FOUND);
                long targetBranch=jdbc.queryForObject("""
                    SELECT p.branch_id FROM appointment_change c
                    JOIN appointment p ON p.appointment_id=c.proposed_id WHERE c.change_id=?
                    """,Long.class,target);
                requireStaff(actor,targetBranch);
            });
        return detail(outcome.targetId());
    }

    private static boolean originalIdEquals(Object value,long originalId) {
        return value instanceof Number number && number.longValue()==originalId;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> patientList(SessionService.Principal actor) {
        long patient=requirePatient(actor);
        List<Long> ids=jdbc.queryForList("""
            SELECT c.change_id FROM appointment_change c JOIN appointment a ON a.appointment_id=c.original_id
            WHERE a.patient_id=? ORDER BY c.change_id DESC LIMIT 100
            """,Long.class,patient);
        return ids.stream().map(this::detail).toList();
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> staffList(SessionService.Principal actor,long branch) {
        requireStaff(actor,branch);
        List<Long> ids=jdbc.queryForList("""
            SELECT c.change_id FROM appointment_change c JOIN appointment a ON a.appointment_id=c.original_id
            WHERE a.branch_id=? ORDER BY c.change_id DESC LIMIT 100
            """,Long.class,branch);
        return ids.stream().map(this::detail).toList();
    }

    private Map<String,Object> detail(long id) {
        Map<String,Object> r=jdbc.queryForMap("""
            SELECT c.change_id,c.original_id,c.proposed_id,c.state,c.version,c.reason,c.responded_at,
                   p.branch_id,p.intended_dentist_id,p.starts_at,p.ends_at,p.state AS candidate_state
            FROM appointment_change c JOIN appointment p ON p.appointment_id=c.proposed_id
            WHERE c.change_id=?
            """,id);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("change_id",r.get("change_id").toString());
        out.put("original_id",r.get("original_id").toString());
        out.put("proposed_id",r.get("proposed_id").toString());
        out.put("state",r.get("state"));out.put("version",r.get("version"));
        out.put("reason",r.get("reason"));
        out.put("branch_id",r.get("branch_id").toString());
        out.put("dentist_id",r.get("intended_dentist_id").toString());
        out.put("starts_at",((Timestamp)r.get("starts_at")).toInstant().toString());
        out.put("ends_at",((Timestamp)r.get("ends_at")).toInstant().toString());
        out.put("candidate_state",r.get("candidate_state"));
        out.put("responded_at",r.get("responded_at")==null?null:
            ((Timestamp)r.get("responded_at")).toInstant().toString());
        out.put("applied", "已落实".equals(r.get("state")));
        return out;
    }

    private void verifyCause(long originalId,Map<String,Object> original,Long absenceId,Long unavailableId) {
        if(absenceId!=null && jdbc.queryForObject("""
            SELECT count(*) FROM dentist_unavailable WHERE absence_id=? AND dentist_id=? AND state='有效'
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """,Integer.class,absenceId,original.get("confirmed_dentist_id"),
            original.get("starts_at"),original.get("ends_at"))!=1)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        if(unavailableId!=null && jdbc.queryForObject("""
            SELECT count(*) FROM resource_unavailable u JOIN resource_booking rb ON rb.resource_id=u.resource_id
            WHERE u.unavailable_id=? AND rb.appointment_id=? AND rb.state='有效' AND u.state='有效'
              AND u.starts_at<?
            """,Integer.class,unavailableId,originalId,original.get("ends_at"))!=1)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
    }

    private void requireStaff(SessionService.Principal actor,long branch) {
        authorization.requireBranch(actor,"appointments.assist",branch);
        if(jdbc.queryForObject("""
            SELECT count(*) FROM staff_branch WHERE staff_id=? AND branch_id=?
              AND enabled=true AND verified_at IS NOT NULL
            """,Integer.class,actor.staffId(),branch)!=1) throw new ApiException(ApiErrorCode.FORBIDDEN);
    }
    private long requirePatient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if(actor.patientId()==null || !SessionService.PATIENT.equals(actor.clientType()))
            throw new ApiException(ApiErrorCode.FORBIDDEN);
        return actor.patientId();
    }
    private void requireOwned(SessionService.Principal actor,long changeId,long patient) {
        requirePatient(actor);
        if(jdbc.queryForObject("""
            SELECT count(*) FROM appointment_change c JOIN appointment a ON a.appointment_id=c.original_id
            WHERE c.change_id=? AND a.patient_id=?
            """,Integer.class,changeId,patient)!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }
    private long branchOfAppointment(long id) {
        return jdbc.query("SELECT branch_id FROM appointment WHERE appointment_id=?",
            (rs,n)->rs.getLong(1),id).stream().findFirst()
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }
    private long branchOfSchedule(long id) {
        return jdbc.query("SELECT branch_id FROM schedule WHERE schedule_id=?",
            (rs,n)->rs.getLong(1),id).stream().findFirst()
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }
    private long branchOfChange(long id) {
        return jdbc.query("""
            SELECT a.branch_id FROM appointment_change c JOIN appointment a ON a.appointment_id=c.original_id
            WHERE c.change_id=?
            """,(rs,n)->rs.getLong(1),id).stream().findFirst()
            .orElseThrow(()->new ApiException(ApiErrorCode.NOT_FOUND));
    }
    private static Instant parse(String raw) {
        try {return OffsetDateTime.parse(raw).toInstant();}
        catch(Exception ex){throw new ApiException(ApiErrorCode.VALIDATION_FAILED);}
    }
    private static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
}
