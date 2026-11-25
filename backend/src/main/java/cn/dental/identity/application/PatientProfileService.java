package cn.dental.identity.application;

import cn.dental.shared.application.AuditService;
import cn.dental.shared.application.IdempotencyService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Patient-owned current profile. Historical clinical snapshots are never rewritten. */
@Service
public class PatientProfileService {
    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public PatientProfileService(JdbcTemplate jdbc, AuthorizationService authorization,
            IdempotencyService idempotency, AuditService audit) {
        this.jdbc=jdbc; this.authorization=authorization; this.idempotency=idempotency; this.audit=audit;
    }

    public record Change(String phone, String email, String emergencyName, String emergencyPhone,
                         String allergyStatus, String allergyNotes, int expectedVersion) {
        @Override public String toString() { return "PatientProfileChange[redacted]"; }
    }

    @Transactional(readOnly=true)
    public Map<String,Object> view(SessionService.Principal actor) {
        return profile(patient(actor));
    }

    @Transactional
    public Map<String,Object> update(SessionService.Principal actor, Change change, String key) {
        long patientId=patient(actor);
        if(change==null || change.expectedVersion()<1 || !List.of("未申报","无已知","有","不确定").contains(change.allergyStatus()))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String phone=optional(change.phone(),30);
        if(phone!=null && phone.isBlank()) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        String email=optional(change.email(),120);
        String emergencyName=optional(change.emergencyName(),60);
        String emergencyPhone=optional(change.emergencyPhone(),30);
        String allergyNotes=optional(change.allergyNotes(),1000);
        if(List.of("有","不确定").contains(change.allergyStatus()) && (allergyNotes==null || allergyNotes.isBlank()))
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        Map<String,Object> fingerprint=new LinkedHashMap<>();
        fingerprint.put("patient_id",patientId); fingerprint.put("expected_version",change.expectedVersion());
        fingerprint.put("phone",phone); fingerprint.put("email",email); fingerprint.put("emergency_name",emergencyName);
        fingerprint.put("emergency_phone",emergencyPhone); fingerprint.put("allergy_status",change.allergyStatus());
        fingerprint.put("allergy_notes",allergyNotes);
        idempotency.execute(actor.accountId(),"patient-profile-update",key,
            IdempotencyService.canonicalize(fingerprint),() -> requireSame(actor,patientId),() -> {
                Map<String,Object> old=jdbc.queryForMap("SELECT phone,email,emergency_name,emergency_phone,allergy_status,allergy_notes,version FROM patient WHERE patient_id=? FOR UPDATE",patientId);
                requireSame(actor,patientId);
                if(((Number)old.get("version")).intValue()!=change.expectedVersion()) throw new ApiException(ApiErrorCode.VERSION_CONFLICT);
                String nextNotes=List.of("有","不确定").contains(change.allergyStatus()) ? allergyNotes : null;
                jdbc.update("""
                    UPDATE patient SET phone=?,email=?,emergency_name=?,emergency_phone=?,allergy_status=?,allergy_notes=?,version=version+1
                    WHERE patient_id=?
                    """, phone==null?old.get("phone"):phone,
                    email==null?old.get("email"):emptyToNull(email),
                    emergencyName==null?old.get("emergency_name"):emptyToNull(emergencyName),
                    emergencyPhone==null?old.get("emergency_phone"):emptyToNull(emergencyPhone),
                    change.allergyStatus(),nextNotes,patientId);
                audit.user(actor.accountId(),"patient",patientId,"self_profile_update",null,null,null);
                return new IdempotencyService.BusinessResult(patientId,"updated",null);
            },target -> requireSame(actor,target));
        return profile(patientId);
    }

    private Map<String,Object> profile(long patientId) {
        return jdbc.queryForObject("""
            SELECT patient_id,name,birth_date,phone,email,emergency_name,emergency_phone,
                   primary_dentist_id,allergy_status,allergy_notes,version FROM patient WHERE patient_id=?
            """,(r,n) -> {
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("patient_id",Long.toString(r.getLong("patient_id")));
                m.put("name",r.getString("name"));
                Date birth=r.getDate("birth_date"); m.put("birth_date",birth==null?null:birth.toLocalDate().toString());
                m.put("phone",r.getString("phone")); m.put("email",r.getString("email"));
                m.put("emergency_name",r.getString("emergency_name")); m.put("emergency_phone",r.getString("emergency_phone"));
                long dentist=r.getLong("primary_dentist_id");
                m.put("primary_dentist_id",r.wasNull()?null:Long.toString(dentist));
                m.put("allergy_status",r.getString("allergy_status")); m.put("allergy_notes",r.getString("allergy_notes"));
                m.put("version",r.getInt("version")); return m;
            },patientId);
    }

    private long patient(SessionService.Principal actor) {
        authorization.requireCurrent(actor);
        if(actor.patientId()==null) throw new ApiException(ApiErrorCode.UNAUTHENTICATED);
        return actor.patientId();
    }
    private void requireSame(SessionService.Principal actor,long patientId) {
        if(patient(actor)!=patientId) throw new ApiException(ApiErrorCode.NOT_FOUND);
    }
    private String optional(String value,int max) {
        if(value==null) return null;
        String clean=value.strip();
        if(clean.length()>max) throw new ApiException(ApiErrorCode.VALIDATION_FAILED);
        return clean;
    }
    private String emptyToNull(String value) { return value.isBlank()?null:value; }
}
