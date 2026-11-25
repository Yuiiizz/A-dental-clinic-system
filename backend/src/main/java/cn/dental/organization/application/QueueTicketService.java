package cn.dental.organization.application;

import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Both check-in channels use the same counter and ticket transaction. Caller locks branch, then appointment. */
@Service
public class QueueTicketService {
    private final JdbcTemplate jdbc;
    public QueueTicketService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(propagation=Propagation.MANDATORY)
    public long existing(long appointmentId) {
        List<Long> tickets=jdbc.queryForList("SELECT ticket_id FROM queue_ticket WHERE appointment_id=?",Long.class,appointmentId);
        if(tickets.size()!=1) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
        return tickets.getFirst();
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public long allocate(long appointmentId,long branchId,LocalDate day,Long staffId,Instant now,
            String channel,String basis,String exceptionReason) {
        int next=jdbc.queryForObject("""
            INSERT INTO queue_counter(branch_id,queue_date,last_number)
            SELECT ?,?,coalesce(max(queue_no),0)+1 FROM queue_ticket WHERE branch_id=? AND queue_date=?
            ON CONFLICT (branch_id,queue_date) DO UPDATE
            SET last_number=queue_counter.last_number+1 RETURNING last_number
            """,Integer.class,branchId,java.sql.Date.valueOf(day),branchId,java.sql.Date.valueOf(day));
        jdbc.update("UPDATE appointment SET state='候诊中',checked_in_at=?,version=version+1 WHERE appointment_id=?",
            Timestamp.from(now),appointmentId);
        return jdbc.queryForObject("""
            INSERT INTO queue_ticket(appointment_id,queue_date,queue_no,registered_by,registered_at,state,priority,
                                     branch_id,checkin_channel,verification_basis,exception_reason)
            VALUES (?,?,?,?,?,'候诊',0,?,?,?,?) RETURNING ticket_id
            """,Long.class,appointmentId,java.sql.Date.valueOf(day),next,staffId,Timestamp.from(now),branchId,channel,basis,exceptionReason);
    }

    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public Map<String,Object> view(long ticketId) {
        return jdbc.queryForObject("""
            SELECT ticket_id,appointment_id,queue_date,queue_no,state,checkin_channel
            FROM queue_ticket WHERE ticket_id=?
            """,(r,n)->Map.of(
                "ticket_id",Long.toString(r.getLong("ticket_id")),
                "appointment_id",Long.toString(r.getLong("appointment_id")),
                "queue_date",r.getDate("queue_date").toLocalDate().toString(),
                "queue_no",r.getInt("queue_no"),
                "state",r.getString("state"),
                "checkin_channel",r.getString("checkin_channel")),ticketId);
    }
}
