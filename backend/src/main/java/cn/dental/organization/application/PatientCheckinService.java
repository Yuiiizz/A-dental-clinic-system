package cn.dental.organization.application;

import cn.dental.identity.application.AuthorizationService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Patient scan check-in. Appointment lock serializes with frontdesk check-in. */
@Service
public class PatientCheckinService {
    private static final ZoneId SHANGHAI=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final CheckinTokenService codes;
    private final QueueTicketService tickets;
    private final AuditService audit;
    private final Clock clock;

    public PatientCheckinService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, CheckinTokenService codes, QueueTicketService tickets,
            AuditService audit, Clock clock) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency;
        this.codes=codes; this.tickets=tickets; this.audit=audit; this.clock=clock;
    }

    @Transactional
    public Map<String,Object> checkin(SessionService.Principal actor,long appointmentId,
            String code,long expectedVersion,String requestKey) {
        if(appointmentId<=0 || expectedVersion<1 || code==null || code.isBlank() || code.length()>300)
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        long patientId=requirePatient(actor);
        Map<String,Object> content=new LinkedHashMap<>();
        content.put("appointment_id",appointmentId);
        content.put("expected_version",expectedVersion);
        content.put("branch_checkin_token",code);
        var outcome=idempotency.execute(actor.accountId(),"patient-checkin",requestKey,
            IdempotencyService.canonicalize(content),
            ()->{requirePatient(actor);requireOwned(appointmentId,patientId);},
            ()->new IdempotencyService.BusinessResult(checkinInTx(actor,appointmentId,patientId,code,expectedVersion),"CHECKED_IN",null),
            ticketId->{requirePatient(actor);requireTicketVisible(appointmentId,patientId,ticketId);});
        return tickets.view(outcome.targetId());
    }

    private long checkinInTx(SessionService.Principal actor,long id,long patientId,String code,long version) {
        List<Long> branch=jdbc.queryForList("SELECT branch_id FROM appointment WHERE appointment_id=? AND patient_id=?",
            Long.class,id,patientId);
        if(branch.isEmpty()) throw new ApiException(ApiErrorCode.NOT_FOUND);
        jdbc.queryForList("SELECT branch_id FROM branch WHERE branch_id=? FOR UPDATE",branch.getFirst());
        Map<String,Object> appointment=jdbc.queryForMap("""
            SELECT a.branch_id,a.patient_id,a.state,a.starts_at,a.ends_at,a.version,sc.dentist_id
            FROM appointment a JOIN schedule sc ON sc.schedule_id=a.schedule_id
            WHERE a.appointment_id=? AND a.patient_id=? FOR UPDATE OF a
            """,id,patientId);
        requirePatient(actor);
        if(((Number)appointment.get("branch_id")).longValue()!=branch.getFirst())
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if("候诊中".equals(appointment.get("state"))) return tickets.existing(id);
        if(!"已确认".equals(appointment.get("state"))) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        if(((Number)appointment.get("version")).longValue()!=version) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
        List<Map<String,Object>> config=jdbc.queryForList("""
            SELECT b.state,b.verified_at,c.self_checkin_enabled,c.early_minutes,c.late_minutes
            FROM branch b LEFT JOIN branch_booking_config c ON c.branch_id=b.branch_id WHERE b.branch_id=?
            """,branch.getFirst());
        if(config.isEmpty() || !"营业".equals(config.getFirst().get("state")) || config.getFirst().get("verified_at")==null
                || !Boolean.TRUE.equals(config.getFirst().get("self_checkin_enabled"))
                || config.getFirst().get("early_minutes")==null || config.getFirst().get("late_minutes")==null)
            throw new ApiException(ApiErrorCode.CHECKIN_NOT_CONFIGURED);
        codes.verify(code,branch.getFirst());
        Instant now=clock.instant();
        Instant start=((Timestamp)appointment.get("starts_at")).toInstant();
        Instant end=((Timestamp)appointment.get("ends_at")).toInstant();
        LocalDate day=now.atZone(SHANGHAI).toLocalDate();
        if(!start.atZone(SHANGHAI).toLocalDate().equals(day)) throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        int early=((Number)config.getFirst().get("early_minutes")).intValue();
        int late=((Number)config.getFirst().get("late_minutes")).intValue();
        Instant windowEnd=start.plus(Duration.ofMinutes(late));
        if(windowEnd.isAfter(end)) windowEnd=end;
        if(now.isBefore(start.minus(Duration.ofMinutes(early))) || !now.isBefore(windowEnd))
            throw new ApiException(ApiErrorCode.BRANCH_STATE_CONFLICT);
        int absence=jdbc.queryForObject("""
            SELECT count(*) FROM dentist_unavailable WHERE dentist_id=? AND state='有效'
              AND tstzrange(starts_at,ends_at,'[)') && tstzrange(?,?,'[)')
            """,Integer.class,((Number)appointment.get("dentist_id")).longValue(),Timestamp.from(start),Timestamp.from(end));
        if(absence>0) throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        long ticketId=tickets.allocate(id,branch.getFirst(),day,null,now,"小程序",null,null);
        int queueNo=jdbc.queryForObject("SELECT queue_no FROM queue_ticket WHERE ticket_id=?",Integer.class,ticketId);
        audit.user(actor.accountId(),"queue_ticket",ticketId,"patient_checkin",null,
            Map.of("state_to","候诊","queue_number",Integer.toString(queueNo),"verification_method","qr"),"本人扫码签到");
        return ticketId;
    }

    private long requirePatient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if(!SessionService.PATIENT.equals(actor.clientType()) || actor.patientId()==null)
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return actor.patientId();
    }

    private void requireOwned(long id,long patientId) {
        if(jdbc.queryForObject("SELECT count(*) FROM appointment WHERE appointment_id=? AND patient_id=?",
                Integer.class,id,patientId)!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }

    private void requireTicketVisible(long appointmentId,long patientId,long ticketId) {
        if(jdbc.queryForObject("""
            SELECT count(*) FROM queue_ticket q JOIN appointment a ON a.appointment_id=q.appointment_id
            WHERE q.ticket_id=? AND a.appointment_id=? AND a.patient_id=?
            """,Integer.class,ticketId,appointmentId,patientId)!=1) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }
}
