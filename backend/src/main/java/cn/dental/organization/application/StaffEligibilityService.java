package cn.dental.organization.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared qualification check. Caller must hold an actual transaction.
 * Acquires staff row lock, then reads current qualification facts; revocation uses the same lock.
 * Caller must acquire any preceding branch/patient/account locks BEFORE this method.
 * Does not check branch assignment, service, schedule, unavailability, resources or patient consent.
 * Does not modify roles, certificates or appointments. Scheduling consumers are not yet implemented.
 */
@Service
public class StaffEligibilityService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public StaffEligibilityService(JdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Transactional(propagation=Propagation.MANDATORY)
    public boolean isCurrentlyPracticeQualified(long staffId) {
        if (!lockDentist(staffId)) return false;
        LocalDate today=clock.instant().atZone(CredentialValidity.ZONE).toLocalDate();
        return facts(staffId).stream().anyMatch(c -> CredentialValidity.onDate(c.state(),c.from(),c.to(),c.noExpiry(),today));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public boolean isPracticeQualifiedForInterval(long staffId, Instant startsAt, Instant endsAt) {
        if (startsAt==null || endsAt==null || !startsAt.isBefore(endsAt))
            throw new IllegalArgumentException("Interval must be nonempty");
        if (!lockDentist(staffId)) return false;
        // One complete certificate must cover the entire interval; never stitch partial certificates.
        return facts(staffId).stream().anyMatch(c -> CredentialValidity.coversInterval(c.state(),c.from(),c.to(),c.noExpiry(),startsAt,endsAt));
    }

    private boolean lockDentist(long staffId) {
        if(staffId<=0) throw new IllegalArgumentException("Positive staff id required");
        var rows=jdbc.queryForList("SELECT staff_type,employment_state FROM staff WHERE staff_id=? FOR UPDATE",staffId);
        return !rows.isEmpty() && "牙医".equals(rows.getFirst().get("staff_type"))
            && "在岗".equals(rows.getFirst().get("employment_state"));
    }
    private record Fact(String state,LocalDate from,LocalDate to,boolean noExpiry) {}
    private List<Fact> facts(long staffId) {
        return jdbc.query("SELECT verification_state,valid_from,valid_to,no_expiry_confirmed FROM credential WHERE staff_id=? AND credential_type='dentist_practice'",
            (r,n)->new Fact(r.getString(1),r.getDate(2)==null?null:r.getDate(2).toLocalDate(),
                r.getDate(3)==null?null:r.getDate(3).toLocalDate(),r.getBoolean(4)),staffId);
    }
}
