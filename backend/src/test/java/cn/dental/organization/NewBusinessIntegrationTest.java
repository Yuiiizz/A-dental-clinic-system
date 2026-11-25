package cn.dental.organization;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
import cn.dental.organization.application.BookingAvailabilityService;
import cn.dental.support.MutableClock;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewBusinessIntegrationTest {
    private static final MutableClock CLOCK = new MutableClock(
        Instant.parse("2030-01-02T00:59:30Z"), ZoneId.of("Asia/Shanghai"));

    @Configuration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() { return CLOCK; }
    }

    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private SessionService sessions;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();
    private String schema;

    @BeforeAll void start() throws Exception {
        var env = System.getenv();
        PostgresTestSupport.validateTarget(env);
        schema = "business_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = PostgresTestSupport.connect(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        app = new SpringApplication(AdenDentalApplication.class, FixedClockConfig.class).run(
            "--spring.profiles.active=local", "--spring.main.banner-mode=off",
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + env.get("TEST_DB_URL") + "?currentSchema=" + schema + ",public",
            "--spring.datasource.username=" + env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--app.mock.wechat-enabled=false", "--app.seed.enabled=false");
        jdbc = app.getBean(JdbcTemplate.class);
        sessions = app.getBean(SessionService.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        seed();
    }

    private void seed() {
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(1,'合成牙医','牙医','牙医','SYNTH-DENTIST-1','在岗'),(2,'合成前台','其他人员','前台',NULL,'在岗'),(3,'合成负责人','其他人员','负责人',NULL,'在岗'),(5,'合成财务','其他人员','财务',NULL,'在岗')");
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成机构','SYNTH-ORG','00000000000',3,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(1,1,'SYNTH-A','合成一店','地址','00000000000','SYNTH-A','09:00-18:00',3,'营业',3,now()),(2,1,'SYNTH-B','合成二店','地址','00000000000','SYNTH-B','09:00-18:00',3,'营业',3,now())");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者甲','00000000001','未申报'),(2,'合成患者乙','00000000002','未申报')");
        jdbc.execute("SELECT setval(pg_get_serial_sequence('patient','patient_id'), (SELECT max(patient_id) FROM patient))");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'dentist-test','NOT-A-LOGIN-HASH',true,false,now()),(2,2,'frontdesk-test','NOT-A-LOGIN-HASH',true,false,now()),(3,3,'owner-test','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(4,2,true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(6,5,'finance-test','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(7,1,true,false,now())");
        for (String role : List.of("dentist", "frontdesk", "owner")) {
            Long roleId = jdbc.queryForObject("SELECT role_id FROM role WHERE code=?", Long.class, role);
            long account = role.equals("dentist") ? 1 : role.equals("frontdesk") ? 2 : 3;
            jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(?,?,now(),'合成测试授权')", account, roleId);
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(?,?,1)", account, roleId);
        }
        Long financeRole = jdbc.queryForObject("SELECT role_id FROM role WHERE code='finance'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(6,?,now(),'合成财务授权')", financeRole);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(6,?,1)", financeRole);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(5,1,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,1,true,now()),(2,1,true,now()),(3,1,true,now()),(1,2,true,now())");
        jdbc.update("INSERT INTO service_category(category_id,code,name,display_order,enabled) VALUES(1,'CHECK','检查评估',1,true)");
        jdbc.update("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(1,'初诊检查','次',8000,true,true,30,1)");
        jdbc.update("INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,effective_at,approved_by,approved_at) VALUES(1,1,1,24,7,2,7,'published','2029-01-01T00:00:00Z',3,'2029-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO resource_category(category_id,category_code,name,enabled) VALUES(1,'CHAIR','牙椅',true)");
        jdbc.update("INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(1,'合成牙椅','牙椅',1,'可用',1)");
        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(1,1,'2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','两者','已发布',1,now(),0)");
        resetEligibility();
    }

    /** Baseline complete eligibility fixture; tests may mutate it, and it resets before every test. */
    private void resetEligibility() {
        jdbc.update("DELETE FROM dentist_unavailable");
        jdbc.update("DELETE FROM resource_unavailable");
        jdbc.update("DELETE FROM resource WHERE resource_id=2");
        jdbc.update("DELETE FROM branch_opening_interval");
        jdbc.update("DELETE FROM schedule_service");
        jdbc.update("DELETE FROM dentist_service");
        jdbc.update("DELETE FROM branch_service");
        jdbc.update("DELETE FROM credential");
        jdbc.update("UPDATE branch SET state='营业',verified_by=3,verified_at=now() WHERE branch_id IN (1,2)");
        jdbc.update("UPDATE staff SET employment_state='在岗',staff_type='牙医' WHERE staff_id=1");
        jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=1 AND branch_id=1");
        jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=2 AND branch_id=1");
        jdbc.update("UPDATE schedule SET state='已发布',starts_at='2030-01-02T01:00:00Z',ends_at='2030-01-02T10:00:00Z' WHERE schedule_id=1");
        jdbc.update("INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed,version) VALUES(1,'dentist_practice','SYNTH-CRED-1','2029-01-01','2031-12-31',3,now(),'verified',false,1)");
        jdbc.update("INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable) VALUES(1,1,8000,true,true)");
        jdbc.update("INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at) VALUES(1,1,true,now())");
        jdbc.update("INSERT INTO schedule_service(schedule_id,item_id,duration_minutes) VALUES(1,1,30)");
        jdbc.update("INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version) VALUES(1,'2030-01-02','2030-01-02T01:00:00Z','2030-01-02T10:00:00Z',1)");
    }

    @BeforeEach void cleanBusinessData() {
        CLOCK.set(Instant.parse("2030-01-02T00:59:30Z"));
        jdbc.update("UPDATE patient SET phone='00000000001',allergy_status='未申报',allergy_notes=NULL,version=1 WHERE patient_id=1");
        testSelectionTokens.clear();
        Long inventoryRole=jdbc.queryForObject("SELECT role_id FROM role WHERE code='inventory'",Long.class);
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=6 AND role_id=?",inventoryRole);
        jdbc.update("DELETE FROM account_role WHERE account_id=6 AND role_id=?",inventoryRole);
        // Test-only isolated schema reset: production ledger DELETE is intentionally forbidden.
        jdbc.execute("TRUNCATE stock_movement,purchase_receipt_line,purchase_receipt,stock_batch,warehouse,stock_item RESTART IDENTITY");
        jdbc.update("DELETE FROM audit_log WHERE target_type IN ('stock_item','warehouse','purchase_receipt')");
        jdbc.update("DELETE FROM internal_staff_message");
        jdbc.update("DELETE FROM doctor_preference");
        jdbc.update("DELETE FROM supplier");
        jdbc.update("DELETE FROM audit_log WHERE target_type='supplier'");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM audit_log WHERE target_type='queue_ticket'");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM refund");
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM bill_item");
        jdbc.update("DELETE FROM bill");
        jdbc.update("DELETE FROM treatment_plan_item");
        jdbc.update("DELETE FROM treatment_plan");
        jdbc.update("UPDATE appointment SET followup_plan_id=NULL WHERE followup_plan_id IS NOT NULL");
        jdbc.update("DELETE FROM followup_plan");
        jdbc.update("DELETE FROM visit_record");
        jdbc.update("DELETE FROM queue_ticket");
        jdbc.update("DELETE FROM queue_counter");
        jdbc.update("DELETE FROM branch_booking_config");
        jdbc.update("DELETE FROM resource_booking");
        jdbc.update("DELETE FROM contact_record WHERE appointment_id IS NOT NULL");
        jdbc.update("DELETE FROM patient_selection");
        jdbc.update("DELETE FROM appointment_change");
        jdbc.update("DELETE FROM appointment_item");
        jdbc.update("DELETE FROM appointment");
        resetEligibility();
    }

    private SessionService.Issued session(long accountId) { return sessions.issue(accountId, SessionService.STAFF); }
    private SessionService.Issued inventorySession() {
        Long role=jdbc.queryForObject("SELECT role_id FROM role WHERE code='inventory'",Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(6,?,now(),'合成库管授权') ON CONFLICT DO NOTHING",role);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(6,?,1) ON CONFLICT DO NOTHING",role);
        return session(6);
    }
    private SessionService.Issued patientSession(long accountId) { return sessions.issue(accountId, SessionService.PATIENT); }

    private HttpResponse<String> call(String method, String path, String body, SessionService.Issued issued) throws Exception {
        return callKey(method, path, body, issued, java.util.UUID.randomUUID().toString());
    }
    private HttpResponse<String> callKey(String method, String path, String body, SessionService.Issued issued, String key) throws Exception {
        if ("POST".equals(method) && "/staff/appointments".equals(path) && body!=null && !body.contains("consent_basis"))
            body=body.substring(0,body.length()-1)+",\"consent_basis\":\"合成患者电话确认\"}";
        if ("POST".equals(method) && "/staff/appointments".equals(path) && body != null
                && !body.contains("patient_selection_token")) {
            String selectionBody = body;
            String token = testSelectionTokens.computeIfAbsent(key, k -> createTestSelection(selectionBody));
            body = body.substring(0, body.length() - 1) + ",\"patient_selection_token\":\"" + token + "\"}";
        }
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Idempotency-Key", key).header("Cookie", "A_CLINIC_SESSION=" + issued.token()).header("Content-Type", "application/json");
        if (issued.csrfToken() != null) b.header("X-CSRF-Token", issued.csrfToken());
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private final Map<String, String> testSelectionTokens = new java.util.concurrent.ConcurrentHashMap<>();

    private String createTestSelection(String body) {
        long patientId = Long.parseLong(body.replaceAll("(?s).*\"patient_id\":(\\d+).*", "$1"));
        String token = "test-selection-" + UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Instant expires = CLOCK.instant().plusSeconds(600);
        jdbc.update("""
            INSERT INTO patient_selection(selection_id,token_hash,account_id,staff_id,branch_id,patient_id,
                source,verification_method,verification_result,resolution,in_person,expires_at,created_at)
            VALUES(?,?,2,2,1,?,'lookup','id_document','confirmed','single_exact',true,?,?)
            """, id, SessionService.hash(token), patientId, java.sql.Timestamp.from(expires), java.sql.Timestamp.from(CLOCK.instant()));
        return token;
    }

    private HttpResponse<String> callPatient(String method, String path, String body, SessionService.Issued issued) throws Exception {
        return callPatientKey(method, path, body, issued, "patient-test-" + Integer.toUnsignedString(Objects.hash(path, body, issued.token())));
    }
    private HttpResponse<String> callPatientKey(String method, String path, String body, SessionService.Issued issued, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Idempotency-Key", key)
            .header("Authorization", "Bearer " + issued.token()).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String bookingBody(long patientId, String startsAt) {
        return "{\"patient_id\":" + patientId + ",\"schedule_id\":1,\"item_id\":1,\"starts_at\":\""
            + startsAt + "\",\"visit_type\":\"初诊\",\"channel\":\"电话\",\"purpose\":\"可用性测试\"}";
    }

    private void setOpening(String... pairs) {
        jdbc.update("DELETE FROM branch_opening_interval WHERE branch_id=1");
        for (String pair : pairs) {
            String[] p = pair.split("\\|");
            jdbc.update("INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version) VALUES(1,'2030-01-02',?,?,1)",
                java.sql.Timestamp.from(java.time.Instant.parse(p[0])), java.sql.Timestamp.from(java.time.Instant.parse(p[1])));
        }
    }

    @Test void doctorServiceScopeCannotRemoveAnItemUsedByFutureSchedule() throws Exception {
        var owner = session(3);
        jdbc.update("DELETE FROM audit_log WHERE target_type='dentist_service'");
        String path = "/staff/scheduling/staff/1/services";
        assertEquals(422, call("PUT", path, "{}", owner).statusCode());
        assertEquals(409, call("PUT", path, "{\"item_ids\":[],\"approval_basis\":\"合成核实\"}", owner).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=1 AND item_id=1 AND enabled=true", Integer.class));

        jdbc.update("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(92341,'合成新项目','次',100,true,true,30,1)");
        try {
            String add = "{\"item_ids\":[1,92341],\"approval_basis\":\"合成核实\"}";
            jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_scope_audit_failure CHECK (target_type <> 'dentist_service')");
            try {
                assertEquals(500, call("PUT", path, add, owner).statusCode());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=1 AND item_id=92341", Integer.class));
            } finally {
                jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT test_scope_audit_failure");
            }
            assertEquals(200, call("PUT", path, add, owner).statusCode());
            assertEquals(200, call("PUT", path, add, owner).statusCode());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=1 AND enabled=true", Integer.class));
            assertEquals(200, call("PUT", path, "{\"item_ids\":[1],\"approval_basis\":\"合成核实\"}", owner).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=1 AND item_id=92341 AND enabled=true", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='dentist_service' AND target_id=1", Integer.class));
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE target_type='dentist_service'");
            jdbc.update("DELETE FROM dentist_service WHERE dentist_id=1 AND item_id=92341");
            jdbc.update("DELETE FROM service_item WHERE item_id=92341");
        }
    }

    @Test void doctorServiceScopeWaitsForBranchBeforeLockingDentist() throws Exception {
        var owner = session(3);
        jdbc.update("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(92342,'合成并发项目','次',100,true,true,30,1)");
        try (var hold = app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() -> call("PUT", "/staff/scheduling/staff/1/services",
                    "{\"item_ids\":[1,92342],\"approval_basis\":\"合成核实\"}", owner));
                awaitBranchLockWait();
                try (var probe = app.getBean(DataSource.class).getConnection()) {
                    probe.setAutoCommit(false);
                    try (var st = probe.createStatement()) {
                        st.execute("SELECT staff_id FROM staff WHERE staff_id=1 FOR UPDATE NOWAIT");
                    }
                    probe.rollback();
                }
                hold.commit();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dentist_service WHERE dentist_id=1 AND item_id=92342 AND enabled=true", Integer.class));
            } finally {
                hold.rollback();
                pool.shutdownNow();
            }
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE target_type='dentist_service' AND target_id=1");
            jdbc.update("DELETE FROM dentist_service WHERE dentist_id=1 AND item_id=92342");
            jdbc.update("DELETE FROM service_item WHERE item_id=92342");
        }
    }

    @Test void publicDirectoryShowsOnlyVerifiedCliniciansAndBranchesWithoutLogin() throws Exception {
        jdbc.update("UPDATE branch SET license_no_expiry_confirmed=true WHERE branch_id=1");
        var branches = client.send(HttpRequest.newBuilder(URI.create(base + "/public/branches"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, branches.statusCode(), branches.body());
        assertTrue(branches.body().contains("合成一店"));
        assertFalse(branches.body().contains("合成二店"));
        var doctors = client.send(HttpRequest.newBuilder(URI.create(base + "/public/doctors?branch_id=1"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, doctors.statusCode(), doctors.body());
        assertTrue(doctors.body().contains("合成牙医"));
        assertFalse(doctors.body().contains("SYNTH-CRED"));
        assertFalse(doctors.body().contains("合成患者"));
        jdbc.update("UPDATE credential SET verification_state='rejected' WHERE staff_id=1");
        var hidden = client.send(HttpRequest.newBuilder(URI.create(base + "/public/doctors?branch_id=1"))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hidden.statusCode(), hidden.body());
        assertFalse(hidden.body().contains("合成牙医"));
    }

    @Test void publicSlotsFollowBookableFactsAndValidateRange() throws Exception {
        jdbc.update("UPDATE branch SET license_no_expiry_confirmed=true WHERE branch_id=1");
        String path = "/public/slots?branch_id=1&item_id=1&visit_type=初诊&from=2030-01-02&to=2030-01-02";
        var available = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, available.statusCode(), available.body());
        assertTrue(available.body().contains("\"schedule_id\":\"1\""));
        assertTrue(available.body().contains("2030-01-02T01:00:00Z"));
        assertTrue(available.body().contains("\"policy_id\":\"1\""));
        assertFalse(available.body().contains("合成患者"));
        jdbc.update("UPDATE branch_service SET auto_bookable=false WHERE branch_id=1 AND item_id=1");
        var disabled = client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertTrue(disabled.body().contains("\"items\":[]"));
        assertEquals(422, client.send(HttpRequest.newBuilder(URI.create(base
            + "/public/slots?branch_id=1&item_id=1&visit_type=初诊&from=2030-01-02&to=2030-02-02"))
            .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void doctorPreferencesArePrivateIdempotentAndOnlyExposePublicDoctors() throws Exception {
        jdbc.update("UPDATE branch SET license_no_expiry_confirmed=true WHERE branch_id=1");
        var mine = patientSession(7);
        var other = patientSession(4);
        assertEquals(200, callPatient("PUT", "/me/doctor-preferences/1", "{\"source\":\"本人选择\"}", mine).statusCode());
        assertEquals(200, callPatient("PUT", "/me/doctor-preferences/1", "{\"source\":\"本人选择\"}", mine).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM doctor_preference WHERE patient_id=1", Integer.class));
        assertEquals(200, callPatient("PUT", "/me/doctor-preferences/1", "{\"source\":\"熟人推荐\"}", mine).statusCode());
        var list = callPatient("GET", "/me/doctor-preferences?limit=1", null, mine);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("熟人推荐"));
        assertTrue(list.body().contains("\"next_cursor\":null"));
        assertFalse(list.body().contains("合成患者"));
        assertFalse(callPatient("GET", "/me/doctor-preferences", null, other).body().contains("熟人推荐"));
        assertEquals(422, callPatient("PUT", "/me/doctor-preferences/1", "{\"source\":\"本人选择\",\"patient_id\":2}", mine).statusCode());
        assertEquals(404, callPatient("PUT", "/me/doctor-preferences/999", "{\"source\":\"本人选择\"}", mine).statusCode());
        jdbc.update("UPDATE credential SET verification_state='rejected' WHERE staff_id=1");
        assertFalse(callPatient("GET", "/me/doctor-preferences", null, mine).body().contains("熟人推荐"));
        assertEquals(404, callPatient("PUT", "/me/doctor-preferences/1", "{\"source\":\"本人选择\"}", mine).statusCode());
        assertEquals(204, callPatient("DELETE", "/me/doctor-preferences/1", null, mine).statusCode());
        assertEquals(204, callPatient("DELETE", "/me/doctor-preferences/1", null, mine).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM doctor_preference WHERE patient_id=1", Integer.class));
    }

    @Test void patientProfileUpdateKeepsHistoryAndHonorsVersionAndIdempotency() throws Exception {
        var mine=patientSession(7);
        var other=patientSession(4);
        var initial=callPatient("GET","/me/profile",null,mine);
        assertEquals(200,initial.statusCode(),initial.body());
        assertTrue(initial.body().contains("合成患者甲"));
        assertFalse(initial.body().contains("合成患者乙"));
        assertTrue(callPatient("GET","/me/profile",null,other).body().contains("合成患者乙"));
        String body="{\"phone\":\"00000000009\",\"allergy_status\":\"有\",\"allergy_notes\":\"合成青霉素过敏\",\"expected_version\":1}";
        assertEquals(422,callPatient("POST","/me/profile",body.replace("合成青霉素过敏",""),mine).statusCode());
        var updated=callPatientKey("POST","/me/profile",body,mine,"profile-key");
        assertEquals(200,updated.statusCode(),updated.body());
        assertTrue(updated.body().contains("\"version\":2"));
        assertEquals(200,callPatientKey("POST","/me/profile",body,mine,"profile-key").statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT version FROM patient WHERE patient_id=1",Integer.class));
        assertEquals(409,callPatientKey("POST","/me/profile",body.replace("00000000009","00000000008"),mine,"profile-key").statusCode());
        assertEquals(409,callPatientKey("POST","/me/profile",body,mine,"profile-new-key").statusCode());
        assertEquals(422,callPatient("POST","/me/profile",body.substring(0,body.length()-1)+",\"patient_id\":2}",mine).statusCode());
        assertEquals("00000000002",jdbc.queryForObject("SELECT phone FROM patient WHERE patient_id=2",String.class));
        assertEquals(200,callPatientKey("POST","/me/profile",
            "{\"allergy_status\":\"无已知\",\"expected_version\":2}",mine,"profile-clear-key").statusCode());
        assertNull(jdbc.queryForObject("SELECT allergy_notes FROM patient WHERE patient_id=1",String.class));
    }

    @Test void internalMessagesRequireSameBranchRolesAndReplayOnlyOnce() throws Exception {
        var frontdesk = session(2);
        var dentist = session(1);
        var owner = session(3);
        var contacts = call("GET", "/staff/internal-messages/contacts", null, frontdesk);
        assertEquals(200, contacts.statusCode(), contacts.body());
        assertTrue(contacts.body().contains("合成牙医"));
        String body = "{\"branch_id\":1,\"peer_staff_id\":1,\"body\":\"请核对预约 123 的排班\"}";
        var first = callKey("POST", "/staff/internal-messages", body, frontdesk, "message-key-1");
        assertEquals(201, first.statusCode(), first.body());
        var replay = callKey("POST", "/staff/internal-messages", body, frontdesk, "message-key-1");
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM internal_staff_message", Integer.class));
        assertEquals(409, callKey("POST", "/staff/internal-messages",
            body.replace("预约 123", "预约 124"), frontdesk, "message-key-1").statusCode());
        var reply = call("POST", "/staff/internal-messages",
            "{\"branch_id\":1,\"peer_staff_id\":2,\"body\":\"排班已核对，请按正式流程办理\"}", dentist);
        assertEquals(201, reply.statusCode(), reply.body());
        var thread = call("GET", "/staff/internal-messages?branch_id=1&peer_staff_id=1", null, frontdesk);
        assertEquals(200, thread.statusCode(), thread.body());
        assertTrue(thread.body().contains("排班已核对"));
        assertEquals(404, call("GET", "/staff/internal-messages?branch_id=2&peer_staff_id=1", null, frontdesk).statusCode());
        assertEquals(404, call("GET", "/staff/internal-messages?branch_id=1&peer_staff_id=1", null, owner).statusCode());
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=2 AND branch_id=1");
        assertEquals(404, call("GET", "/staff/internal-messages?branch_id=1&peer_staff_id=1", null, frontdesk).statusCode());
    }

    @Test void supplierCreateIsAuthorizedAuditedAndIdempotent() throws Exception {
        var owner = session(3);
        var frontdesk = session(2);
        String body = "{\"name\":\"合成器材公司\",\"credit_code\":\"SYNTH-SUP-001\","
            + "\"qualification_ref\":\"人工核对营业执照与经营资质\",\"valid_to\":\"2031-12-31\"}";
        assertEquals(403, call("POST", "/staff/suppliers", body, frontdesk).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM supplier", Integer.class));
        String key = "supplier-test-create";
        var first = callKey("POST", "/staff/suppliers", body, owner, key);
        assertEquals(201, first.statusCode(), first.body());
        assertTrue(first.body().contains("合成器材公司"));
        assertEquals(201, callKey("POST", "/staff/suppliers", body, owner, key).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM supplier", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='supplier' AND operation='create'", Integer.class));
        assertEquals(409, callKey("POST", "/staff/suppliers", body.replace("合成器材公司", "另一家公司"), owner, key).statusCode());
        assertEquals(409, call("POST", "/staff/suppliers", body, owner).statusCode());
        var page = call("GET", "/staff/suppliers?limit=1", null, owner);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(page.body().contains("SYNTH-SUP-001"));
        assertFalse(page.body().contains("qualification_ref"));
        assertEquals(403, call("GET", "/staff/suppliers", null, frontdesk).statusCode());
        long inventoryRole = jdbc.queryForObject("SELECT role_id FROM role WHERE code='inventory'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(6,?,now(),'合成库管授权')", inventoryRole);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(6,?,1)", inventoryRole);
        try {
            var inventory = session(6);
            assertEquals(200, call("GET", "/staff/suppliers", null, inventory).statusCode());
            jdbc.update("DELETE FROM role_branch_scope WHERE account_id=6 AND role_id=?", inventoryRole);
            assertEquals(403, call("GET", "/staff/suppliers", null, inventory).statusCode());
        } finally {
            jdbc.update("DELETE FROM role_branch_scope WHERE account_id=6 AND role_id=?", inventoryRole);
            jdbc.update("DELETE FROM account_role WHERE account_id=6 AND role_id=?", inventoryRole);
        }
    }

    @Test void supplierLegacyNullExpiryAndCrossDayReplayRemainReadable() throws Exception {
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            + "VALUES('合成旧供应商','SYNTH-LEGACY','历史核验依据',NULL,3,now(),false)");
        long legacyId = jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-LEGACY'", Long.class);
        var actor = session(3);
        var legacy = call("GET", "/staff/suppliers/" + legacyId, null, actor);
        assertEquals(200, legacy.statusCode(), legacy.body());
        assertTrue(legacy.body().contains("\"valid_to\":null"), legacy.body());
        String body = "{\"name\":\"跨日测试\",\"credit_code\":\"SYNTH-DAY\","
            + "\"qualification_ref\":\"人工核验\",\"valid_to\":\"2030-01-02\"}";
        assertEquals(201, callKey("POST", "/staff/suppliers", body, actor, "supplier-cross-day").statusCode());
        CLOCK.set(Instant.parse("2030-01-02T16:01:00Z")); // Shanghai: next business day
        var nextSession = session(3);
        assertEquals(201, callKey("POST", "/staff/suppliers", body, nextSession, "supplier-cross-day").statusCode());
        assertEquals(422, callKey("POST", "/staff/suppliers", body, nextSession, "supplier-new-key").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM supplier", Integer.class));
    }

    private long responseId(HttpResponse<String> response,String field) {
        var matcher=java.util.regex.Pattern.compile("\\\""+field+"\\\"\\s*:\\s*\\\"?(\\d+)\\\"?").matcher(response.body());
        assertTrue(matcher.find(),response.body());
        return Long.parseLong(matcher.group(1));
    }

    @Test void ordinaryConsumablesReceiveAndBalanceAreRealAndIdempotent() throws Exception {
        var owner=session(3);
        var inventory=inventorySession();
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成耗材供应商','SYNTH-STOCK-SUP','合成资质','2031-12-31',3,now(),true)");
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-STOCK-SUP'",Long.class);
        var item=callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-GLOVE\",\"name\":\"合成检查手套\",\"specification\":\"M\",\"base_unit\":\"只\",\"storage_condition\":\"常温\"}",
            owner,"item-first");
        assertEquals(201,item.statusCode(),item.body());
        long itemId=responseId(item,"stock_item_id");
        assertEquals(201,callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-GLOVE\",\"name\":\"合成检查手套\",\"specification\":\"M\",\"base_unit\":\"只\",\"storage_condition\":\"常温\"}",
            owner,"item-first").statusCode());
        var warehouse=callKey("POST","/staff/warehouses","{\"branch_id\":1,\"name\":\"合成一店耗材库\"}",
            owner,"wh-first");
        assertEquals(201,warehouse.statusCode(),warehouse.body());
        long warehouseId=responseId(warehouse,"warehouse_id");
        String first=receiptBody(supplier,itemId,warehouseId,"SYNTH-REC-1","2.000");
        assertEquals(403,callKey("POST","/staff/purchase-receipts",first,owner,"rec-owner").statusCode());
        var posted=callKey("POST","/staff/purchase-receipts",first,inventory,"rec-first");
        assertEquals(201,posted.statusCode(),posted.body());
        long receiptId=responseId(posted,"receipt_id");
        assertTrue(posted.body().contains("\"state\":\"已过账\""),posted.body());
        assertTrue(posted.body().contains("\"acquisition_cost_total\":6.50"),posted.body());
        assertEquals(201,callKey("POST","/staff/purchase-receipts",first,inventory,"rec-first").statusCode());
        assertEquals(409,callKey("POST","/staff/purchase-receipts",
            receiptBody(supplier,itemId,warehouseId,"SYNTH-REC-1","3.000"),inventory,"rec-first").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
        assertEquals(200,call("GET","/staff/purchase-receipts/"+receiptId,null,owner).statusCode());
        var balance=call("GET","/staff/stock-balances?branch_id=1",null,inventory);
        assertEquals(200,balance.statusCode(),balance.body());
        assertTrue(balance.body().contains("\"quantity\":2.000"),balance.body());
        assertEquals(201,callKey("POST","/staff/purchase-receipts",
            receiptBody(supplier,itemId,warehouseId,"SYNTH-REC-2","1.500"),inventory,"rec-second").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_batch",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
        assertTrue(call("GET","/staff/stock-balances?branch_id=1",null,inventory).body()
            .contains("\"quantity\":3.500"));
    }

    @Test void receivingRejectsBadFactsWithoutAnyPostedOrOrphanRows() throws Exception {
        var owner=session(3);
        var inventory=inventorySession();
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成过期供应商','SYNTH-EXPIRED','合成资质','2029-12-31',3,now(),true)");
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-EXPIRED'",Long.class);
        long item=responseId(callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-MASK\",\"name\":\"合成口罩\",\"specification\":\"普通\",\"base_unit\":\"只\",\"storage_condition\":\"常温\"}",
            owner,"item-mask"),"stock_item_id");
        long warehouse=responseId(callKey("POST","/staff/warehouses",
            "{\"branch_id\":1,\"name\":\"合成验收库\"}",owner,"wh-test"),"warehouse_id");
        String body=receiptBody(supplier,item,warehouse,"SYNTH-FAIL","2.000");
        assertEquals(409,callKey("POST","/staff/purchase-receipts",body,inventory,"rec-fail").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_batch",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
        jdbc.update("UPDATE supplier SET valid_to='2031-12-31' WHERE supplier_id=?",supplier);
        assertEquals(201,callKey("POST","/staff/purchase-receipts",body,inventory,"rec-fail").statusCode());
        assertEquals(422,callKey("POST","/staff/purchase-receipts",
            receiptBody(supplier,item,warehouse,"SYNTH-SCALE","1.0001"),inventory,"scale").statusCode());
        assertEquals(422,callKey("POST","/staff/purchase-receipts",
            body.substring(0,body.length()-1)+",\"unknown\":true}",inventory,"unknown").statusCode());
        assertEquals(409,callKey("POST","/staff/purchase-receipts",
            receiptBody(supplier,item,warehouse,"SYNTH-FAIL","2.000"),inventory,"different-key").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
    }

    @Test void inventoryQueriesEnforceRoleAndBranchAndDoNotExposeDraft() throws Exception {
        var inventory=inventorySession();
        var frontdesk=session(2);
        var owner=session(3);
        assertEquals(403,call("GET","/staff/stock-items",null,frontdesk).statusCode());
        assertEquals(403,call("GET","/staff/warehouses?branch_id=2",null,inventory).statusCode());
        assertEquals(403,call("GET","/staff/stock-balances?branch_id=2",null,inventory).statusCode());
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成供应商','SYNTH-DRAFT','合成资质','2031-12-31',3,now(),true)");
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-DRAFT'",Long.class);
        jdbc.update("INSERT INTO purchase_receipt(branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state,request_key) "
            +"VALUES(1,?,'SYNTH-DRAFT-R','SYNTH-D',now(),5,'草稿',?)",supplier,UUID.randomUUID().toString());
        long receipt=jdbc.queryForObject("SELECT receipt_id FROM purchase_receipt WHERE document_no='SYNTH-DRAFT-R'",Long.class);
        assertEquals(404,call("GET","/staff/purchase-receipts/"+receipt,null,owner).statusCode());
        assertTrue(call("GET","/staff/purchase-receipts?branch_id=1",null,owner).body().contains("\"items\":[]"));
    }

    @Test void concurrentReceivingUsesOneBatchAndOneEffectPerIdempotencyKey() throws Exception {
        var owner=session(3);
        var inventory=inventorySession();
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成并发供应商','SYNTH-CONCURRENT','合成资质','2031-12-31',3,now(),true)");
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-CONCURRENT'",Long.class);
        long item=responseId(callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-CUP\",\"name\":\"合成纸杯\",\"specification\":\"普通\",\"base_unit\":\"只\",\"storage_condition\":\"常温\"}",
            owner,"item-cup"),"stock_item_id");
        long warehouse=responseId(callKey("POST","/staff/warehouses",
            "{\"branch_id\":1,\"name\":\"合成并发库\"}",owner,"wh-concurrent"),"warehouse_id");
        String first=receiptBody(supplier,item,warehouse,"SYNTH-CON-1","2.000");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->callKey("POST","/staff/purchase-receipts",first,inventory,"same-receive"));
            var b=pool.submit(()->callKey("POST","/staff/purchase-receipts",first,inventory,"same-receive"));
            assertEquals(201,a.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals(201,b.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
            var c=pool.submit(()->callKey("POST","/staff/purchase-receipts",
                receiptBody(supplier,item,warehouse,"SYNTH-CON-2","1.000"),inventory,"different-receive-2"));
            var d=pool.submit(()->callKey("POST","/staff/purchase-receipts",
                receiptBody(supplier,item,warehouse,"SYNTH-CON-3","1.500"),inventory,"different-receive-3"));
            assertEquals(201,c.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals(201,d.get(15,TimeUnit.SECONDS).statusCode());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_batch",Integer.class));
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void receivingAuditFailureRollsBackBatchLedgerAndIdempotency() throws Exception {
        var owner=session(3);
        var inventory=inventorySession();
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成故障供应商','SYNTH-FAULT','合成资质','2031-12-31',3,now(),true)");
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-FAULT'",Long.class);
        long item=responseId(callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-SYRINGE\",\"name\":\"合成冲洗器\",\"specification\":\"普通\",\"base_unit\":\"支\",\"storage_condition\":\"常温\"}",
            owner,"item-fault"),"stock_item_id");
        long warehouse=responseId(callKey("POST","/staff/warehouses",
            "{\"branch_id\":1,\"name\":\"合成故障库\"}",owner,"wh-fault"),"warehouse_id");
        String body=receiptBody(supplier,item,warehouse,"SYNTH-AUDIT-FAIL","2.000");
        jdbc.execute("""
            CREATE FUNCTION synthetic_inventory_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF NEW.target_type='purchase_receipt' THEN RAISE EXCEPTION 'synthetic audit failure'; END IF;
              RETURN NEW;
            END $$
            """);
        jdbc.execute("CREATE TRIGGER synthetic_inventory_audit_failure BEFORE INSERT ON audit_log "
            +"FOR EACH ROW EXECUTE FUNCTION synthetic_inventory_audit_failure()");
        try {
            assertEquals(500,callKey("POST","/staff/purchase-receipts",body,inventory,"audit-retry").statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt_line",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_batch",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE operation='purchase-receive'",Integer.class));
        } finally {
            jdbc.execute("DROP TRIGGER synthetic_inventory_audit_failure ON audit_log");
            jdbc.execute("DROP FUNCTION synthetic_inventory_audit_failure()");
        }
        assertEquals(201,callKey("POST","/staff/purchase-receipts",body,inventory,"audit-retry").statusCode());
    }

    private long[] receivingFixture(String supplierExpiry) throws Exception {
        var owner=session(3);
        jdbc.update("INSERT INTO supplier(name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            +"VALUES('合成边界供应商','SYNTH-EDGE','合成资质',?::date,3,now(),true)",supplierExpiry);
        long supplier=jdbc.queryForObject("SELECT supplier_id FROM supplier WHERE credit_code='SYNTH-EDGE'",Long.class);
        long item=responseId(callKey("POST","/staff/stock-items",
            "{\"code\":\"SYNTH-EDGE-ITEM\",\"name\":\"合成边界耗材\",\"specification\":\"普通\",\"base_unit\":\"只\",\"storage_condition\":\"常温\"}",
            owner,"item-edge"),"stock_item_id");
        long warehouse=responseId(callKey("POST","/staff/warehouses",
            "{\"branch_id\":1,\"name\":\"合成边界库\"}",owner,"wh-edge"),"warehouse_id");
        return new long[]{supplier,item,warehouse};
    }

    @Test void receivingRechecksDateAndPermissionAfterDeterministicBranchWait() throws Exception {
        CLOCK.set(Instant.parse("2030-01-02T15:59:50Z")); // Shanghai Jan 2 23:59:50
        var inventory=inventorySession();
        var ids=receivingFixture("2030-01-05");
        String body=receiptBody(ids[0],ids[1],ids[2],"SYNTH-WAIT-DATE","2.000")
            .replace("2031-01-01","2030-01-02");
        try(var hold=app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try(var st=hold.createStatement()) { st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE"); }
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending=pool.submit(()->callKey("POST","/staff/purchase-receipts",
                    body,inventory,"inventory-wait-date"));
                awaitBranchLockWait();
                CLOCK.set(Instant.parse("2030-01-02T16:00:01Z")); // Jan 3 in Shanghai
                hold.commit();
                assertEquals(409,pending.get(15,TimeUnit.SECONDS).statusCode());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_batch",Integer.class));
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
            } finally { pool.shutdownNow(); }
        }
        CLOCK.set(Instant.parse("2030-01-02T00:59:30Z"));
        var fresh=inventorySession();
        try(var hold=app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try(var st=hold.createStatement()) { st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE"); }
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending=pool.submit(()->callKey("POST","/staff/purchase-receipts",
                    receiptBody(ids[0],ids[1],ids[2],"SYNTH-WAIT-ROLE","2.000"),fresh,"inventory-wait-role"));
                awaitBranchLockWait();
                jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=5 AND branch_id=1");
                hold.commit();
                assertEquals(403,pending.get(15,TimeUnit.SECONDS).statusCode());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
            } finally {
                pool.shutdownNow();
                jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=5 AND branch_id=1");
            }
        }
    }

    @Test void receivingRejectsDisabledAndQuarantinedInputsWithoutWriting() throws Exception {
        var inventory=inventorySession();
        var ids=receivingFixture("2031-12-31");
        String body=receiptBody(ids[0],ids[1],ids[2],"SYNTH-STATE-1","2.000");
        jdbc.update("UPDATE warehouse SET enabled=false WHERE warehouse_id=?",ids[2]);
        assertEquals(409,callKey("POST","/staff/purchase-receipts",body,inventory,"disabled-wh").statusCode());
        jdbc.update("UPDATE warehouse SET enabled=true WHERE warehouse_id=?",ids[2]);
        jdbc.update("UPDATE stock_item SET enabled=false WHERE stock_item_id=?",ids[1]);
        assertEquals(409,callKey("POST","/staff/purchase-receipts",body,inventory,"disabled-item").statusCode());
        jdbc.update("UPDATE stock_item SET enabled=true WHERE stock_item_id=?",ids[1]);
        assertEquals(201,callKey("POST","/staff/purchase-receipts",body,inventory,"state-first").statusCode());
        jdbc.update("UPDATE stock_batch SET state='隔离'");
        assertEquals(409,callKey("POST","/staff/purchase-receipts",
            receiptBody(ids[0],ids[1],ids[2],"SYNTH-STATE-2","2.000"),inventory,"quarantined").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM purchase_receipt",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM stock_movement",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE request_key='quarantined'",Integer.class));
    }

    private String receiptBody(long supplier,long item,long warehouse,String document,String quantity) {
        return """
            {"branch_id":1,"supplier_id":%d,"document_no":"%s","supplier_document":"SYNTH-SUP-DOC",
             "lines":[{"line_no":1,"stock_item_id":%d,"warehouse_id":%d,"manufacturer":"合成厂家",
             "batch_no":"LOT-1","expires_on":"2031-01-01","quantity":%s,"unit_cost":3.2500}]}
            """.formatted(supplier,document,item,warehouse,quantity);
    }

    @Test void completeAvailabilityConfigurationAllowsAppointment() throws Exception {
        var response = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2));
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test void unverifiedBranchRejectsAppointment() throws Exception {
        jdbc.update("UPDATE branch SET state='待核验',verified_by=NULL,verified_at=NULL WHERE branch_id=1");
        var response = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2));
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
    }

    @Test void expiredCredentialForAppointmentDateRejects() throws Exception {
        jdbc.update("UPDATE credential SET valid_to='2030-01-01' WHERE staff_id=1 AND credential_type='dentist_practice'");
        var response = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2));
        assertEquals(409, response.statusCode(), response.body());
    }

    @Test void lunchGapRejectsButAdjacentIntervalsAllow() throws Exception {
        setOpening("2030-01-02T01:00:00Z|2030-01-02T04:00:00Z", "2030-01-02T05:00:00Z|2030-01-02T10:00:00Z");
        var gap = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T12:00:00+08:00"), session(2));
        assertEquals(409, gap.statusCode(), gap.body());
        setOpening("2030-01-02T01:00:00Z|2030-01-02T04:00:00Z", "2030-01-02T04:00:00Z|2030-01-02T10:00:00Z");
        var adjacent = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T12:00:00+08:00"), session(2));
        assertEquals(201, adjacent.statusCode(), adjacent.body());
    }

    @Test void dentistAbsenceRejectsFullInterval() throws Exception {
        jdbc.update("INSERT INTO dentist_unavailable(dentist_id,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'2030-01-02T01:00:00Z','2030-01-02T02:00:00Z','合成请假','有效',3,now())");
        var response = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:30:00+08:00"), session(2));
        assertEquals(409, response.statusCode(), response.body());
    }

    @Test void patientSelfCannotBookNonAutoServiceWhileFrontdeskCan() throws Exception {
        jdbc.update("UPDATE branch_service SET auto_bookable=false WHERE branch_id=1 AND item_id=1");
        var patient = callPatient("POST", "/me/appointments",
            "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:30:00+08:00\",\"visit_type\":\"初诊\",\"purpose\":\"患者测试\"}",
            patientSession(4));
        assertEquals(409, patient.statusCode(), patient.body());
        var staff = call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:30:00+08:00"), session(2));
        assertEquals(201, staff.statusCode(), staff.body());
    }

    @Test void failedRescheduleKeepsOriginalAndResource() throws Exception {
        var patient = patientSession(4);
        var created = callPatient("POST", "/me/appointments",
            "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"purpose\":\"改约测试\"}",
            patient);
        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll("(?s).*\"appointment_id\":\"(\\d+)\".*", "$1");
        jdbc.update("UPDATE branch SET state='待核验',verified_by=NULL,verified_at=NULL WHERE branch_id=1");
        var failed = callPatient("POST", "/me/appointments/" + id + "/reschedule",
            "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:30:00+08:00\",\"expected_version\":1,\"reason\":\"改约失败测试\"}",
            patient);
        assertEquals(409, failed.statusCode(), failed.body());
        assertEquals("已确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?", String.class, Long.parseLong(id)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'", Integer.class, Long.parseLong(id)));
    }

    @Test void idempotentStaffReplaySurvivesLaterConfigurationFailure() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = callKey("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2), key);
        assertEquals(201, first.statusCode(), first.body());
        jdbc.update("UPDATE branch SET state='停用' WHERE branch_id=1");
        var replay = callKey("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2), key);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
    }

    @Test void unverifiedDentistBranchRejects() throws Exception {
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=1 AND branch_id=1");
        assertEquals(409, call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2)).statusCode());
    }

    @Test void closedDentistOrBranchServiceRejects() throws Exception {
        jdbc.update("UPDATE dentist_service SET enabled=false WHERE dentist_id=1 AND item_id=1");
        assertEquals(409, call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2)).statusCode());
        jdbc.update("UPDATE dentist_service SET enabled=true WHERE dentist_id=1 AND item_id=1");
        jdbc.update("UPDATE branch_service SET enabled=false WHERE branch_id=1 AND item_id=1");
        assertEquals(409, call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2)).statusCode());
    }

    @Test void missingScheduleServiceRejects() throws Exception {
        jdbc.update("DELETE FROM schedule_service WHERE schedule_id=1 AND item_id=1");
        assertEquals(409, call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2)).statusCode());
    }

    @Test void effectiveResourceMaintenanceRejectsBeforeWrite() throws Exception {
        jdbc.update("INSERT INTO resource_unavailable(resource_id,kind,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'维护','2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','合成维护','有效',3,now())");
        assertEquals(409, call("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2)).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_booking", Integer.class));
    }

    private void awaitBranchLockWait() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname=current_database() AND pid<>pg_backend_pid()
                  AND wait_event_type='Lock'
                  AND query ILIKE '%FROM branch WHERE branch_id=%FOR UPDATE%'
                """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("request did not wait on the branch row lock");
    }

    @Test void waitingRequestRechecksTimeAfterLock() throws Exception {
        var issued = session(2);
        testSelectionTokens.put("wait-time-key", createTestSelection(bookingBody(1, "2030-01-02T09:00:00+08:00")));
        try (var hold = app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) { st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE"); }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() -> callKey("POST", "/staff/appointments",
                    bookingBody(1, "2030-01-02T09:00:00+08:00"), issued, "wait-time-key"));
                awaitBranchLockWait();
                CLOCK.set(Instant.parse("2030-01-02T01:00:01Z"));
                hold.commit();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertEquals(409, response.statusCode(), response.body());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_booking", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void waitingRequestRechecksPermissionAfterLock() throws Exception {
        var issued = session(2);
        testSelectionTokens.put("wait-permission-key", createTestSelection(bookingBody(1, "2030-01-02T09:00:00+08:00")));
        try (var hold = app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) { st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE"); }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() -> callKey("POST", "/staff/appointments",
                    bookingBody(1, "2030-01-02T09:00:00+08:00"), issued, "wait-permission-key"));
                awaitBranchLockWait();
                jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=2 AND branch_id=1");
                hold.commit();
                var response = pending.get(15, TimeUnit.SECONDS);
                assertEquals(403, response.statusCode(), response.body());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_booking", Integer.class));
            } finally {
                pool.shutdownNow();
                jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=2 AND branch_id=1");
            }
        }
    }

    @Test void invalidScheduleIdIsNormalBusinessError() throws Exception {
        var response = callKey("POST", "/staff/appointments",
            "{\"patient_id\":1,\"schedule_id\":999999,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"channel\":\"电话\",\"purpose\":\"无效目标\"}",
            session(2), "invalid-target-key");
        assertTrue(response.statusCode() == 404 || response.statusCode() == 409 || response.statusCode() == 422,
            response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
    }

    @Test void availabilityServiceRequiresCallerTransaction() {
        var service = app.getBean(BookingAvailabilityService.class);
        assertThrows(IllegalTransactionStateException.class,
            () -> service.requireAvailable(1, 1, 1, Instant.parse("2030-01-02T01:00:00Z"), false));
    }

    @Test void overviewsExposeBackendGeneratedSlots() throws Exception {
        var patient = callPatient("GET", "/me/booking", null, patientSession(4));
        assertEquals(200, patient.statusCode(), patient.body());
        assertTrue(patient.body().contains("\"availability\""));
        assertTrue(patient.body().contains("2030-01-02T01:00:00Z"));
        var staff = call("GET", "/staff/booking", null, session(2));
        assertEquals(200, staff.statusCode(), staff.body());
        assertTrue(staff.body().contains("\"availability\""));
        assertTrue(staff.body().contains("2030-01-02T01:00:00Z"));
    }

    @Test void frontdeskAssistantShiftsAreScopedToAuthorizedBranchAndAbsentFromPatientOverview() throws Exception {
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(20,'合成助手一','其他人员','诊疗助手','在岗'),(21,'合成助手二','其他人员','诊疗助手','在岗')");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(20,1,true,now()),(21,2,true,now())");
        jdbc.update("INSERT INTO assistant_shift(staff_id,branch_id,starts_at,ends_at) VALUES(20,1,'2030-01-02T01:00:00Z','2030-01-02T04:00:00Z'),(21,2,'2030-01-02T01:00:00Z','2030-01-02T04:00:00Z')");
        try {
            var staff = call("GET", "/staff/booking", null, session(2));
            assertEquals(200, staff.statusCode(), staff.body());
            assertTrue(staff.body().contains("合成助手一"));
            assertFalse(staff.body().contains("合成助手二"));
            var patient = callPatient("GET", "/me/booking", null, patientSession(4));
            assertEquals(200, patient.statusCode(), patient.body());
            assertFalse(patient.body().contains("assistant_shifts"));
        } finally {
            jdbc.update("DELETE FROM assistant_shift WHERE staff_id IN (20,21)");
            jdbc.update("DELETE FROM staff_branch WHERE staff_id IN (20,21)");
            jdbc.update("DELETE FROM staff WHERE staff_id IN (20,21)");
        }
    }

    @Test void patientPreviewExcludesOwnActiveAppointmentAcrossOtherDentistAndBranch() throws Exception {
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(4,'合成牙医二','牙医','牙医','SYNTH-DENTIST-2','在岗')");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(4,2,true,now())");
        jdbc.update("INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed,version) VALUES(4,'dentist_practice','SYNTH-CRED-2','2029-01-01','2031-12-31',3,now(),'verified',false,1)");
        jdbc.update("INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at) VALUES(4,1,true,now())");
        jdbc.update("INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable) VALUES(2,1,8000,true,true)");
        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(2,4,'2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','两者','已发布',2,now(),0)");
        jdbc.update("INSERT INTO schedule_service(schedule_id,item_id,duration_minutes) VALUES(2,1,30)");
        jdbc.update("INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(2,'合成二店牙椅','牙椅',1,'可用',2)");
        jdbc.update("INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version) VALUES(2,'2030-01-02','2030-01-02T01:00:00Z','2030-01-02T10:00:00Z',1)");
        try {
            long id = confirmedForCheckin(2, "2030-01-02T01:00:00Z", "2030-01-02T01:30:00Z");
            assertEquals(2, jdbc.queryForObject("SELECT patient_id FROM appointment WHERE appointment_id=?", Long.class, id));
            var body = callPatient("GET", "/me/booking", null, patientSession(4)).body();
            assertFalse(body.contains("\"slots\":[\"2030-01-02T01:00:00Z\""), body);
        } finally {
            jdbc.update("DELETE FROM schedule_service WHERE schedule_id=2");
            jdbc.update("DELETE FROM schedule WHERE schedule_id=2");
            jdbc.update("DELETE FROM resource WHERE resource_id=2");
            jdbc.update("DELETE FROM dentist_service WHERE dentist_id=4");
            jdbc.update("DELETE FROM credential WHERE staff_id=4");
            jdbc.update("DELETE FROM staff_branch WHERE staff_id=4");
            jdbc.update("DELETE FROM staff WHERE staff_id=4");
            jdbc.update("DELETE FROM branch_service WHERE branch_id=2 AND item_id=1");
            jdbc.update("DELETE FROM branch_opening_interval WHERE branch_id=2");
        }
    }

    @Test void patientOwnConflictReturnsSlotUnavailableCode() throws Exception {
        var actor = patientSession(4);
        long id = createPatientAppointment(actor);
        var duplicate = callPatient("POST", "/me/appointments",
            "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"purpose\":\"同一患者冲突\"}",
            actor);
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertTrue(duplicate.body().contains("\"code\":\"SLOT_UNAVAILABLE\""), duplicate.body());
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM appointment WHERE appointment_id=?", Integer.class, id) == 1);
    }

    @Test void patientPreviewRestoresCancelledSlot() throws Exception {
        var actor = patientSession(4);
        long id = createPatientAppointment(actor);
        assertEquals(200, callPatient("POST", "/me/appointments/" + id + "/cancel", "{}", actor).statusCode());
        var afterCancel = callPatient("GET", "/me/booking", null, actor);
        assertEquals(200, afterCancel.statusCode(), afterCancel.body());
        assertTrue(afterCancel.body().contains("\"slots\":[\"2030-01-02T01:00:00Z\""), afterCancel.body());
    }

    @Test void patientPreviewExcludesRescheduledTargetButRestoresSourceTime() throws Exception {
        var actor = patientSession(4);
        long id = createPatientAppointment(actor);
        String key = UUID.randomUUID().toString();
        var moved = callPatientKey("POST", "/me/appointments/" + id + "/reschedule",
            moveBody("10:00", 1), actor, key);
        assertEquals(200, moved.statusCode(), moved.body());
        var view = callPatient("GET", "/me/booking", null, actor);
        assertEquals(200, view.statusCode(), view.body());
        assertTrue(view.body().contains("\"slots\":[\"2030-01-02T01:00:00Z\""), view.body());
        assertFalse(view.body().contains("\"slots\":[\"2030-01-02T02:00:00Z\""), view.body());
    }

    @Test void patientPreviewDoesNotBlockPendingAppointment() throws Exception {
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,
                starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(52,2,1,1,1,'2030-01-02T01:00:00Z','2030-01-02T01:30:00Z','初诊','患者待确认','现场','待确认','无需协调',
                gen_random_uuid(),repeat('f',64),2,1,1)
            """);
        var view = callPatient("GET", "/me/booking", null, patientSession(4));
        assertEquals(200, view.statusCode(), view.body());
        assertTrue(view.body().contains("\"slots\":[\"2030-01-02T01:00:00Z\""), view.body());
    }

    @Test void backendSlotGridDoesNotCrossLunchGap() throws Exception {
        setOpening("2030-01-02T01:00:00Z|2030-01-02T04:00:00Z", "2030-01-02T05:00:00Z|2030-01-02T10:00:00Z");
        // The fixture schedule is 2030-01-02 09:00-18:00 Shanghai; availability must omit 12:00/12:30.
        var patient = callPatient("GET", "/me/booking", null, patientSession(4));
        assertEquals(200, patient.statusCode(), patient.body());
        assertTrue(patient.body().contains("2030-01-02T03:30:00Z"));
        assertTrue(patient.body().contains("2030-01-02T05:00:00Z"));
        assertFalse(patient.body().contains("2030-01-02T04:00:00Z"));
        assertFalse(patient.body().contains("2030-01-02T04:30:00Z"));
    }

    @Test void staffPatientListOnlyContainsPatientsFromAuthorizedBranches() throws Exception {
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(900,'E2E 跨店患者','13600000003','无已知')");
        jdbc.update("INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,effective_at,approved_by,approved_at) VALUES(2,2,1,24,7,2,7,'published','2029-01-01T00:00:00Z',3,'2029-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO appointment(appointment_id,patient_id,state,starts_at,ends_at,visit_type,purpose,channel,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id,cancelled_at,cancelled_by_type,closure_reason) VALUES(900,900,'已取消','2030-01-03T01:00:00Z','2030-01-03T01:30:00Z','初诊','跨店测试','现场','无需协调',gen_random_uuid(),repeat('b',64),2,2,2,now(),'clinic','跨店测试')");
        var response = call("GET", "/staff/booking", null, session(2));
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("E2E 跨店患者"));
        assertFalse(response.body().contains("13600000003"));
    }

    @Test void otherRoleBranchScopeDoesNotBreakFrontdeskBookingReads() throws Exception {
        Long ownerRole = jdbc.queryForObject("SELECT role_id FROM role WHERE code='owner'", Long.class);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(2,2,true,now()) ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(2,?,now(),'合成多角色授权')", ownerRole);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(2,?,2)", ownerRole);
        try {
            var frontdesk = session(2);
            var list = call("GET", "/staff/appointments?date=2030-01-02", null, frontdesk);
            assertEquals(200, list.statusCode(), list.body());
            var overview = call("GET", "/staff/booking", null, frontdesk);
            assertEquals(200, overview.statusCode(), overview.body());
            assertTrue(overview.body().contains("合成一店"));
            assertFalse(overview.body().contains("合成二店"));
        } finally {
            jdbc.update("DELETE FROM role_branch_scope WHERE account_id=2 AND role_id=? AND branch_id=2", ownerRole);
            jdbc.update("DELETE FROM account_role WHERE account_id=2 AND role_id=?", ownerRole);
            jdbc.update("DELETE FROM staff_branch WHERE staff_id=2 AND branch_id=2");
        }
    }

    private String availabilityPart(String body) {
        int start = body.indexOf("\"availability\"");
        if (start < 0) return "";
        int end = body.indexOf(",\"appointments\"", start);
        return body.substring(start, end < 0 ? body.length() : end);
    }

    @Test void occupiedSlotDisappearsAndCancellationRestoresIt() throws Exception {
        var frontdesk = session(2);
        var created = callKey("POST", "/staff/appointments", bookingBody(1, "2030-01-02T09:00:00+08:00"),
            frontdesk, "occupied-slot-create");
        assertEquals(201, created.statusCode(), created.body());
        String id = created.body().replaceAll("(?s).*\"appointment_id\":\"(\\d+)\".*", "$1");
        var afterCreate = call("GET", "/staff/booking", null, frontdesk);
        assertFalse(availabilityPart(afterCreate.body()).contains("2030-01-02T01:00:00Z"));
        assertTrue(availabilityPart(afterCreate.body()).contains("2030-01-02T01:30:00Z"));
        var cancelled = call("POST", "/staff/appointments/" + id + "/cancel", "{\"reason\":\"E2E 取消\"}", frontdesk);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        var afterCancel = call("GET", "/staff/booking", null, frontdesk);
        assertTrue(availabilityPart(afterCancel.body()).contains("2030-01-02T01:00:00Z"));
    }

    @Test void allChairsUnderMaintenanceHideSlotsButOneFreeChairKeepsThem() throws Exception {
        jdbc.update("INSERT INTO resource_unavailable(resource_id,kind,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'维护','2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','合成维护','有效',3,now())");
        var blocked = call("GET", "/staff/booking", null, session(2));
        assertFalse(availabilityPart(blocked.body()).contains("2030-01-02T01:00:00Z"));
        jdbc.update("INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(2,'E2E 牙椅 2','牙椅',1,'可用',1)");
        var free = call("GET", "/staff/booking", null, session(2));
        assertTrue(availabilityPart(free.body()).contains("2030-01-02T01:00:00Z"));
    }

    @Test void pendingAppointmentsDoNotBlockCandidatesOrConfirmation() throws Exception {
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,
                starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(50,1,1,1,1,'2030-01-02T01:00:00Z','2030-01-02T01:30:00Z','初诊','待确认','现场','待确认','无需协调',
                gen_random_uuid(),repeat('d',64),2,1,1)
            """);
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,
                starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(51,2,1,1,1,'2030-01-02T01:00:00Z','2030-01-02T01:30:00Z','初诊','待确认二','现场','待确认','无需协调',
                gen_random_uuid(),repeat('e',64),2,1,1)
            """);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE state='待确认'", Integer.class));
        var view = call("GET", "/staff/booking", null, session(2));
        assertTrue(availabilityPart(view.body()).contains("2030-01-02T01:00:00Z"));
        var created = callKey("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), session(2), "pending-does-not-block-confirm");
        assertEquals(201, created.statusCode(), created.body());
        var second = callKey("POST", "/staff/appointments",
            bookingBody(2, "2030-01-02T09:00:00+08:00"), session(2), "pending-active-conflict");
        assertEquals(409, second.statusCode(), second.body());
        assertEquals(1, jdbc.queryForObject("""
            SELECT count(*) FROM appointment WHERE state IN ('已确认','候诊中','接诊中')
              AND confirmed_dentist_id=1 AND tstzrange(starts_at,ends_at,'[)') && tstzrange('2030-01-02T01:00:00Z','2030-01-02T01:30:00Z','[)')
            """, Integer.class));
    }

    @Test void staffReceptionSearchCreateSelectAndBook() throws Exception {
        var search = call("GET", "/staff/patients?branch_id=1&name="
            + java.net.URLEncoder.encode("合成患者甲", java.nio.charset.StandardCharsets.UTF_8)
            + "&phone=00000000001", null, session(2));
        assertEquals(200, search.statusCode(), search.body());
        assertTrue(search.body().contains("\"match_type\":\"exact_name_phone\""));
        assertFalse(search.body().contains("00000000001\""));

        String name = "Reception New " + UUID.randomUUID();
        String phone = "139" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100000000);
        var created = callKey("POST", "/staff/patients", patientCreateBody(name, phone),
            session(2), "reception-create-1");
        assertEquals(201, created.statusCode(), created.body());
        long patientId = Long.parseLong(created.body().replaceAll("(?s).*\"patient_id\":\"(\\d+)\".*", "$1"));
        String token = tokenOf(created.body());
        assertNotNull(token);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=?", Integer.class, patientId));

        var booked = callKey("POST", "/staff/appointments",
            appointmentBody(patientId, token, "2030-01-02T09:00:00+08:00"), session(2), "reception-book-1");
        assertEquals(201, booked.statusCode(), booked.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE patient_id=?", Integer.class, patientId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient_selection WHERE patient_id=? AND consumed_at IS NOT NULL", Integer.class, patientId));
    }

    @Test void patientSummaryUsesRecordedSourceAndCompletedActualDentistWithinBranch() throws Exception {
        var desk = session(2);
        assertEquals(404, call("GET", "/staff/patients/1/summary?branch_id=1", null, desk).statusCode());
        assertEquals(404, call("GET", "/staff/patients/1/reception-summary?branch_id=1", null, desk).statusCode());
        assertEquals(403, call("GET", "/staff/patients/1/summary?branch_id=1", null, session(3)).statusCode());
        assertEquals(403, call("GET", "/staff/patients/1/reception-summary?branch_id=1", null, session(3)).statusCode());
        String name = "Summary " + UUID.randomUUID();
        String phone = "137" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100000000);
        String body = patientCreateBody(name, phone).replace("\"allergy_status\"", "\"source_channel\":\"转介绍\",\"allergy_status\"");
        var created = call("POST", "/staff/patients", body, desk);
        assertEquals(201, created.statusCode(), created.body());
        long patientId = Long.parseLong(created.body().replaceAll("(?s).*\"patient_id\":\"(\\d+)\".*", "$1"));
        var summary = call("GET", "/staff/patients/" + patientId + "/summary?branch_id=1", null, desk);
        assertEquals(200, summary.statusCode(), summary.body());
        assertTrue(summary.body().contains("\"source_channel\":\"转介绍\""));
        assertTrue(summary.body().contains("\"last_visit_dentist\":null"));
        assertFalse(summary.body().contains(phone));
        assertEquals(403, call("GET", "/staff/patients/" + patientId + "/summary?branch_id=2", null, desk).statusCode());

        var booked = call("POST", "/staff/appointments", bookingBody(patientId, "2030-01-02T09:00:00+08:00"), desk);
        assertEquals(201, booked.statusCode(), booked.body());
        var reception = call("GET", "/staff/patients/" + patientId + "/reception-summary?branch_id=1", null, desk);
        assertEquals(200, reception.statusCode(), reception.body());
        assertTrue(reception.body().contains(phone));
        assertTrue(reception.body().contains("\"appointments\""));
        assertEquals(403, call("GET", "/staff/patients/" + patientId + "/reception-summary?branch_id=2", null, desk).statusCode());
        long appointmentId = jdbc.queryForObject("SELECT max(appointment_id) FROM appointment WHERE patient_id=?", Long.class, patientId);
        jdbc.update("INSERT INTO visit_record(appointment_id,dentist_id,allergy_result,clinical_notes,started_at,completed_at) VALUES(?,1,'无已知','仅医生可见的合成笔记','2030-01-02T01:00:00Z','2030-01-02T01:25:00Z')", appointmentId);
        var after = call("GET", "/staff/patients/" + patientId + "/summary?branch_id=1", null, desk);
        assertEquals(200, after.statusCode(), after.body());
        assertTrue(after.body().contains("\"last_visit_dentist\":\"合成牙医\""));
        var afterReception = call("GET", "/staff/patients/" + patientId + "/reception-summary?branch_id=1", null, desk);
        assertEquals(200, afterReception.statusCode(), afterReception.body());
        assertTrue(afterReception.body().contains("\"visits\""));
        assertTrue(afterReception.body().contains("合成牙医"));
        assertFalse(afterReception.body().contains("仅医生可见的合成笔记"));
    }

    @Test void doctorResolutionRejectsPartialDatabaseState() {
        jdbc.update("INSERT INTO dentist_unavailable(dentist_id,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'2030-01-02T02:00:00Z','2030-01-02T03:00:00Z','合成停诊','有效',3,now())");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            () -> jdbc.update("UPDATE dentist_unavailable SET state='已撤销'"));
        assertEquals("有效", jdbc.queryForObject("SELECT state FROM dentist_unavailable LIMIT 1", String.class));
    }

    @Test void selectionTokenRejectsCrossActorBranchAndPatient() throws Exception {
        String token = selectionToken(1, "合成患者甲", "00000000001");
        addSecondFrontdesk();
        var crossActor = callKey("POST", "/staff/appointments",
            appointmentBody(1, token, "2030-01-02T09:00:00+08:00"), session(5), "cross-actor");
        assertEquals(404, crossActor.statusCode(), crossActor.body());

        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(2,1,'2030-01-03T01:00:00Z','2030-01-03T10:00:00Z','两者','已发布',2,now(),0)");
        String branchBoundToken = insertSelection(5, 4, 1, "合成患者甲", "00000000001", CLOCK.instant().plusSeconds(600));
        var crossBranch = callKey("POST", "/staff/appointments",
            appointmentBody(1, branchBoundToken, "2030-01-02T09:00:00+08:00", 2), session(5), "cross-branch");
        assertEquals(404, crossBranch.statusCode(), crossBranch.body());

        var crossPatient = callKey("POST", "/staff/appointments",
            appointmentBody(2, token, "2030-01-02T09:00:00+08:00"), session(2), "cross-patient");
        assertEquals(404, crossPatient.statusCode(), crossPatient.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
    }

    @Test void staffAppointmentRequiresSelectionToken() throws Exception {
        String body = "{\"patient_id\":1,\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"channel\":\"现场\",\"purpose\":\"无凭证\",\"consent_basis\":\"合成\",\"patient_selection_token\":\"\"}";
        var response = callKey("POST", "/staff/appointments", body, session(2), "missing-selection");
        assertEquals(422, response.statusCode(), response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
    }

    @Test void selectionTokenExpiryAndConcurrentConsumption() throws Exception {
        String expired = insertSelection(1, "合成患者甲", "00000000001", CLOCK.instant().minusSeconds(1));
        var expiredResponse = callKey("POST", "/staff/appointments",
            appointmentBody(1, expired, "2030-01-02T09:00:00+08:00"), session(2), "expired-selection");
        assertEquals(409, expiredResponse.statusCode(), expiredResponse.body());

        String token = selectionToken(1, "合成患者甲", "00000000001");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var latch = new CountDownLatch(1);
            Callable<Integer> one = () -> { latch.await(); return callKey("POST", "/staff/appointments",
                appointmentBody(1, token, "2030-01-02T09:00:00+08:00"), session(2), "shared-token-a").statusCode(); };
            Callable<Integer> two = () -> { latch.await(); return callKey("POST", "/staff/appointments",
                appointmentBody(1, token, "2030-01-02T09:00:00+08:00"), session(2), "shared-token-b").statusCode(); };
            var a = pool.submit(one); var b = pool.submit(two);
            latch.countDown();
            var statuses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(statuses.contains(201) && statuses.contains(409), statuses.toString());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void selectionResolutionIsServerDerived() throws Exception {
        long beforeAudit = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='patient-select'", Long.class);
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(901,'合成患者甲','00000000001','未申报')");
        try {
            var forged = call("POST", "/staff/patients/1/selection", selectionRequest(1, "single_exact"), session(2));
            assertEquals(409, forged.statusCode(), forged.body());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient_selection", Integer.class));
            assertEquals(beforeAudit, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='patient-select'", Long.class));
            var manual = call("POST", "/staff/patients/1/selection", selectionRequest(1, "manual_candidate"), session(2));
            assertEquals(200, manual.statusCode(), manual.body());
            assertNotNull(tokenOf(manual.body()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient_selection", Integer.class));
            assertEquals(beforeAudit + 1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='patient-select'", Long.class));
        } finally {
            jdbc.update("DELETE FROM patient_selection");
            jdbc.update("DELETE FROM patient WHERE patient_id=901");
        }
    }

    @Test void sharedPhoneConflictCannotMasqueradeAsSingleExact() throws Exception {
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(902,'共享电话家属','00000000001','未申报')");
        try {
            var forged = call("POST", "/staff/patients/1/selection", selectionRequest(1, "single_exact"), session(2));
            assertEquals(409, forged.statusCode(), forged.body());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient_selection", Integer.class));
            var shared = call("POST", "/staff/patients/1/selection", selectionRequest(1, "shared_phone"), session(2));
            assertEquals(200, shared.statusCode(), shared.body());
            assertNotNull(tokenOf(shared.body()));
        } finally {
            jdbc.update("DELETE FROM patient_selection");
            jdbc.update("DELETE FROM patient WHERE patient_id=902");
        }
    }

    @Test void createRejectsDuplicateResolutionWithoutActualConflict() throws Exception {
        String name = "Reception Mismatch " + UUID.randomUUID();
        String phone = "136" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100000000);
        String body = patientCreateBody(name, phone).replace("}", ",\"duplicate_resolution\":\"shared_phone\"}");
        var response = callKey("POST", "/staff/patients", body, session(2), "duplicate-resolution-mismatch");
        assertEquals(409, response.statusCode(), response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
    }

    @Test void failedAppointmentDoesNotConsumeSelectionToken() throws Exception {
        String token = selectionToken(1, "合成患者甲", "00000000001");
        var failed = callKey("POST", "/staff/appointments",
            appointmentBody(1, token, "2030-01-02T08:00:00+08:00"), session(2), "selection-rollback-fail");
        assertEquals(409, failed.statusCode(), failed.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient_selection WHERE consumed_at IS NOT NULL", Integer.class));
        var success = callKey("POST", "/staff/appointments",
            appointmentBody(1, token, "2030-01-02T09:00:00+08:00"), session(2), "selection-rollback-success");
        assertEquals(201, success.statusCode(), success.body());
    }

    @Test void successfulReplayChecksRevokedPermissionButDoesNotNeedTokenAgain() throws Exception {
        String token = selectionToken(1, "合成患者甲", "00000000001");
        String body = appointmentBody(1, token, "2030-01-02T09:00:00+08:00");
        var first = callKey("POST", "/staff/appointments", body, session(2), "replay-selection");
        assertEquals(201, first.statusCode(), first.body());
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=2 AND branch_id=1");
        try {
            var revoked = callKey("POST", "/staff/appointments", body, session(2), "replay-selection");
            assertEquals(403, revoked.statusCode(), revoked.body());
        } finally {
            jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=2 AND branch_id=1");
        }
    }

    @Test void concurrentPatientCreateHasOneWinner() throws Exception {
        String name = "Concurrent Reception " + UUID.randomUUID();
        String phone = "137" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100000000);
        String body = patientCreateBody(name, phone);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var latch = new CountDownLatch(1);
            Callable<Integer> one = () -> { latch.await(); return callKey("POST", "/staff/patients", body, session(2), "create-a").statusCode(); };
            Callable<Integer> two = () -> { latch.await(); return callKey("POST", "/staff/patients", body, session(2), "create-b").statusCode(); };
            var a = pool.submit(one); var b = pool.submit(two);
            latch.countDown();
            var statuses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertTrue(statuses.contains(201) && statuses.contains(409), statuses.toString());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        } finally { pool.shutdownNow(); }
    }

    @Test void patientCreateReplayDoesNotRevealTokenAndDuplicateIsRejected() throws Exception {
        String name = "Reception Replay " + UUID.randomUUID();
        String phone = "138" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100000000);
        String body = patientCreateBody(name, phone);
        var first = callKey("POST", "/staff/patients", body, session(2), "reception-replay");
        assertEquals(201, first.statusCode(), first.body());
        String patientId = first.body().replaceAll("(?s).*\"patient_id\":\"(\\d+)\".*", "$1");
        assertTrue(first.body().contains("\"selection_token\""));
        var replay = callKey("POST", "/staff/patients", body, session(2), "reception-replay");
        assertEquals(201, replay.statusCode(), replay.body());
        assertFalse(replay.body().contains("\"selection_token\""));
        assertTrue(replay.body().contains("\"token_shown_once\":true"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        var duplicate = callKey("POST", "/staff/patients", body, session(2), "reception-duplicate");
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
    }

    private String selectionToken(long patientId, String name, String phone) throws Exception {
        var response = call("POST", "/staff/patients/" + patientId + "/selection",
            selectionRequest(name, phone, "single_exact"), session(2));
        assertEquals(200, response.statusCode(), response.body());
        return tokenOf(response.body());
    }

    private String selectionRequest(long patientId, String resolution) {
        return selectionRequest("合成患者甲", "00000000001", resolution);
    }

    private String selectionRequest(String name, String phone, String resolution) {
        return "{\"branch_id\":\"1\",\"name\":\"" + name + "\",\"phone\":\"" + phone
            + "\",\"verification_method\":\"id_document\",\"verification_result\":\"confirmed\",\"in_person\":true,\"resolution\":\"" + resolution + "\"}";
    }

    private String insertSelection(long patientId, String name, String phone, Instant expires) {
        return insertSelection(2, 2, patientId, name, phone, expires);
    }

    private String insertSelection(long accountId, long staffId, long patientId, String name, String phone, Instant expires) {
        String token = "test-selection-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO patient_selection(selection_id,token_hash,account_id,staff_id,branch_id,patient_id,
                source,verification_method,verification_result,resolution,in_person,expires_at,created_at)
            VALUES(?,?,?, ?,1,?,'lookup','id_document','confirmed','single_exact',true,?,?)
            """, UUID.randomUUID(), SessionService.hash(token), accountId, staffId, patientId,
            java.sql.Timestamp.from(expires), java.sql.Timestamp.from(CLOCK.instant()));
        return token;
    }

    private String patientCreateBody(String name, String phone) {
        return "{\"branch_id\":\"1\",\"name\":\"" + name + "\",\"phone\":\"" + phone
            + "\",\"allergy_status\":\"无已知\",\"verification_method\":\"id_document\","
            + "\"verification_result\":\"confirmed\",\"in_person\":true}";
    }

    private String appointmentBody(long patientId, String token, String startsAt) {
        return appointmentBody(patientId, token, startsAt, 1);
    }

    private String appointmentBody(long patientId, String token, String startsAt, long scheduleId) {
        return "{\"patient_id\":" + patientId + ",\"schedule_id\":" + scheduleId
            + ",\"item_id\":1,\"starts_at\":\"" + startsAt
            + "\",\"visit_type\":\"初诊\",\"channel\":\"现场\",\"purpose\":\"接待测试\","
            + "\"consent_basis\":\"合成核验\",\"patient_selection_token\":\"" + token + "\"}";
    }

    private String tokenOf(String body) {
        var matcher = java.util.regex.Pattern.compile("\"selection_token\":\"([^\"]+)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void addSecondFrontdesk() {
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(4,'第二前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(5,4,'frontdesk2-test','NOT-A-LOGIN-HASH',true,false,now())");
        long role = jdbc.queryForObject("SELECT role_id FROM role WHERE code='frontdesk'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(5,?,now(),'合成测试授权')", role);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(5,?,1),(5,?,2)", role, role);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(4,1,true,now()),(4,2,true,now())");
    }

    private long insertVisit() {
        jdbc.update("INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,request_key,request_hash,requested_by,branch_id,policy_id) VALUES(1,1,1,1,1,'2026-10-27T01:00:00Z','2026-10-27T01:30:00Z','初诊','测试','现场','候诊中','无需协调',now(),gen_random_uuid(),repeat('a',64),2,1,1)");
        jdbc.update("INSERT INTO visit_record(visit_id,appointment_id,dentist_id,allergy_result,clinical_notes,started_at,completed_at) VALUES(1,1,1,'无已知','测试', '2026-10-27T01:00:00Z','2026-10-27T02:00:00Z')");
        return 1;
    }

    private void seedPatientFollowup() {
        insertVisit();
        jdbc.execute("SELECT setval(pg_get_serial_sequence('visit_record','visit_id'),greatest(100,(SELECT max(visit_id) FROM visit_record))+100,true)");
        jdbc.update("UPDATE appointment SET patient_id=2,state='已完成' WHERE appointment_id=1");
        jdbc.execute("SELECT setval(pg_get_serial_sequence('appointment','appointment_id'),greatest(100,(SELECT max(appointment_id) FROM appointment))+100,true)");
        jdbc.update("INSERT INTO followup_plan(plan_id,source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,state) VALUES(1,1,1,'2030-01-01','2030-01-10',30,'复诊目标','待协调')");
    }

    private String followupBody(String startsAt) {
        return "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"" + startsAt
            + "\",\"visit_type\":\"复诊\",\"purpose\":\"复诊目标\",\"followup_plan_id\":1}";
    }

    @Test void patientFollowupBookingKeepsHistoryAndReopensAfterCancellation() throws Exception {
        seedPatientFollowup();
        var patient = patientSession(4);
        var otherPatient = patientSession(7);
        assertEquals(404, callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), otherPatient).statusCode());
        String key = UUID.randomUUID().toString();
        var created = callPatientKey("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient, key);
        assertEquals(201, created.statusCode(), created.body());
        Long firstId = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Long.class);
        assertNotNull(firstId);
        assertEquals("已预约", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
        var replay = callPatientKey("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient, key);
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1", Integer.class));
        assertEquals(409, callPatient("POST", "/me/appointments", followupBody("2030-01-02T02:00:00Z"), patient).statusCode());
        var cancelled = callPatient("POST", "/me/appointments/" + firstId + "/cancel", "{\"reason\":\"患者调整复诊\"}", patient);
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("待协调", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
        var rebooked = callPatient("POST", "/me/appointments", followupBody("2030-01-02T02:00:00Z"), patient);
        assertEquals(201, rebooked.statusCode(), rebooked.body());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Integer.class));
        Long secondId = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Long.class);
        assertEquals(200, call("POST", "/staff/appointments/" + secondId + "/cancel", "{\"reason\":\"门店协调复诊\"}", session(2)).statusCode());
        assertEquals("待协调", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
    }

    @Test void concurrentPatientFollowupSubmissionsHaveOneActiveAppointment() throws Exception {
        seedPatientFollowup();
        var patient = patientSession(4);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { gate.await(); return callPatientKey("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient, UUID.randomUUID().toString()).statusCode(); });
            var second = pool.submit(() -> { gate.await(); return callPatientKey("POST", "/me/appointments", followupBody("2030-01-02T02:00:00Z"), patient, UUID.randomUUID().toString()).statusCode(); });
            gate.countDown();
            var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertTrue(statuses.contains(201) && statuses.contains(409), statuses.toString());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Integer.class));
            assertEquals("已预约", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void patientFollowupRescheduleRejectsShorterSlotWithoutChangingOriginal() throws Exception {
        seedPatientFollowup();
        jdbc.update("UPDATE followup_plan SET duration_minutes=60 WHERE plan_id=1");
        jdbc.update("UPDATE schedule_service SET duration_minutes=60 WHERE schedule_id=1 AND item_id=1");
        var patient = patientSession(4);
        var first = callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient);
        assertEquals(201, first.statusCode(), first.body());
        long appointmentId = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Long.class);
        jdbc.update("UPDATE schedule_service SET duration_minutes=30 WHERE schedule_id=1 AND item_id=1");
        String body = "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T03:00:00Z\",\"expected_version\":1,\"reason\":\"调整复诊时间\"}";
        var rejected = callPatient("POST", "/me/appointments/" + appointmentId + "/reschedule", body, patient);
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertEquals("已确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?", String.class, appointmentId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1", Integer.class));
        assertEquals("已预约", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
    }

    @Test void dentistMustConfirmFollowupGoalWhenCompletingLinkedVisit() throws Exception {
        seedPatientFollowup();
        var patient = patientSession(4);
        var booked = callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient);
        assertEquals(201, booked.statusCode(), booked.body());
        long id = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Long.class);
        jdbc.update("UPDATE appointment SET state='接诊中',actual_started_at=now(),actual_dentist_id=1 WHERE appointment_id=?", id);
        var dentist = session(1);
        int auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='followup_plan' AND target_id=1 AND operation='visit-outcome'", Integer.class);
        String base = "{\"appointment_id\":" + id + ",\"allergy_result\":\"无已知\",\"clinical_notes\":\"目标已达到\"";
        assertEquals(422, call("POST", "/staff/visits", base + "}", dentist).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM visit_record WHERE appointment_id=?", Integer.class, id));
        assertEquals(auditBefore, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='followup_plan' AND target_id=1 AND operation='visit-outcome'", Integer.class));
        var completed = call("POST", "/staff/visits", base + ",\"followup_goal_achieved\":true}", dentist);
        assertEquals(201, completed.statusCode(), completed.body());
        assertEquals("已完成", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
        assertEquals(auditBefore + 1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='followup_plan' AND target_id=1 AND operation='visit-outcome'", Integer.class));
        assertTrue(callPatient("GET", "/me/records", null, patient).body().contains("\"state\":\"已完成\""));
    }

    @Test void unfinishedFollowupGoalReturnsToCoordinationAfterVisit() throws Exception {
        seedPatientFollowup();
        var patient = patientSession(4);
        var booked = callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patient);
        assertEquals(201, booked.statusCode(), booked.body());
        long id = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE followup_plan_id=1 AND state='已确认'", Long.class);
        jdbc.update("UPDATE appointment SET state='接诊中',actual_started_at=now(),actual_dentist_id=1 WHERE appointment_id=?", id);
        var completed = call("POST", "/staff/visits", "{\"appointment_id\":" + id
            + ",\"allergy_result\":\"无已知\",\"clinical_notes\":\"复诊目标尚未达到，继续安排\",\"followup_goal_achieved\":false}", session(1));
        assertEquals(201, completed.statusCode(), completed.body());
        assertEquals("待协调", jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1", String.class));
        assertEquals(201, callPatient("POST", "/me/appointments", followupBody("2030-01-02T02:30:00Z"), patient).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1", Integer.class));
    }

    private long insertAcceptedPlan() {
        insertVisit();
        jdbc.update("INSERT INTO treatment_plan(plan_id,patient_id,branch_id,dentist_id,source_visit_id,title,state,estimated_total_cents,version,created_by,created_at,accepted_at,acceptance_basis) VALUES(1,1,1,1,1,'测试治疗计划','已接受',10000,1,1,now(),now(),'患者确认')");
        jdbc.update("INSERT INTO treatment_plan_item(item_id,plan_id,name,quantity,unit_price_cents,amount_cents,status,sort_order) VALUES(1,1,'测试项目',1,10000,10000,'计划',0)");
        return 1;
    }

    private long insertPaidBill() {
        jdbc.update("INSERT INTO bill(bill_id,patient_id,branch_id,total_cents,paid_cents,status,version,created_at) VALUES(1,1,1,60000,60000,'已收款',1,now())");
        jdbc.update("INSERT INTO bill_item(item_id,bill_id,name,quantity,unit_price_cents,amount_cents) VALUES(1,1,'测试项目',1,60000,60000)");
        return 1;
    }

    @Test void frontdeskCannotRecordClinicalButDentistCan() throws Exception {
        long appointment = insertVisit();
        // remove visit so the dentist can complete the appointment
        jdbc.update("DELETE FROM visit_record WHERE visit_id=1");
        var frontdesk = session(2);
        var denied = call("POST", "/staff/visits", "{\"appointment_id\":" + appointment + ",\"allergy_result\":\"无已知\"}", frontdesk);
        assertEquals(403, denied.statusCode());
        var dentist = session(1);
        assertEquals(200, call("POST", "/staff/appointments/1/start-visit", "{}", dentist).statusCode());
        var actual = jdbc.queryForObject("SELECT actual_started_at FROM appointment WHERE appointment_id=1", java.sql.Timestamp.class);
        assertEquals(200, call("POST", "/staff/appointments/1/start-visit", "{}", dentist).statusCode());
        assertEquals(actual, jdbc.queryForObject("SELECT actual_started_at FROM appointment WHERE appointment_id=1", java.sql.Timestamp.class));
        var ok = call("POST", "/staff/visits", "{\"appointment_id\":" + appointment + ",\"allergy_result\":\"无已知\",\"clinical_notes\":\"合成完成\"}", dentist);
        assertEquals(201, ok.statusCode(), ok.body());
    }

    @Test void clinicalQueueShowsOnlyTheSignedInDentistsAppointments() throws Exception {
        insertVisit();
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(11,'合成第二牙医','牙医','牙医','SYNTH-DENTIST-11','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(11,11,'dentist11-test','NOT-A-LOGIN-HASH',true,false,now())");
        long role = jdbc.queryForObject("SELECT role_id FROM role WHERE code='dentist'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(11,?,now(),'合成测试授权')", role);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(11,?,1)", role);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(11,1,true,now())");
        var beforeAssignment = call("GET", "/staff/clinical", null, session(11));
        assertEquals(200, beforeAssignment.statusCode(), beforeAssignment.body());
        assertTrue(beforeAssignment.body().contains("\"visits\":[]"), beforeAssignment.body());
        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(11,11,'2026-10-27T01:00:00Z','2026-10-27T02:00:00Z','两者','已发布',1,now(),0)");
        jdbc.update("UPDATE appointment SET schedule_id=11,confirmed_dentist_id=11,intended_dentist_id=11 WHERE appointment_id=1");
        var firstDoctor = call("GET", "/staff/clinical", null, session(1));
        assertEquals(200, firstDoctor.statusCode(), firstDoctor.body());
        assertTrue(firstDoctor.body().contains("\"appointments\":[]"), firstDoctor.body());
        var secondDoctor = call("GET", "/staff/clinical", null, session(11));
        assertEquals(200, secondDoctor.statusCode(), secondDoctor.body());
        assertTrue(secondDoctor.body().contains("\"appointments\":[{\"appointment_id\":\"1\""), secondDoctor.body());
        assertTrue(secondDoctor.body().contains("\"visits\":[{\"visit_id\":\"1\""), secondDoctor.body());
    }

    @Test void sameBranchDoctorCannotReadOrChangeUnrelatedTreatmentPlan() throws Exception {
        insertAcceptedPlan();
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(11,'合成第二牙医','牙医','牙医','SYNTH-DENTIST-11','在岗') ON CONFLICT (staff_id) DO NOTHING");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(11,11,'dentist11-test','NOT-A-LOGIN-HASH',true,false,now()) ON CONFLICT (account_id) DO NOTHING");
        long role = jdbc.queryForObject("SELECT role_id FROM role WHERE code='dentist'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(11,?,now(),'合成测试授权') ON CONFLICT DO NOTHING", role);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(11,?,1) ON CONFLICT DO NOTHING", role);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(11,1,true,now()) ON CONFLICT DO NOTHING");
        var other = session(11);
        var overview = call("GET", "/staff/treatment-plans", null, other);
        assertEquals(200, overview.statusCode(), overview.body());
        assertTrue(overview.body().contains("\"plans\":[]"), overview.body());
        assertTrue(overview.body().contains("\"visits\":[]"), overview.body());
        assertEquals(403, call("POST", "/staff/treatment-plans",
            "{\"source_visit_id\":1,\"title\":\"越权计划\",\"items\":[{\"service_item_id\":1,\"name\":\"项目\",\"quantity\":1,\"unit_price_cents\":10000}]}", other).statusCode());
        assertEquals(403, call("POST", "/staff/treatment-plans/1/submit", "{}", other).statusCode());
        assertEquals(403, call("POST", "/staff/treatment-plans/1/items/1/status", "{\"status\":\"已完成\"}", other).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM treatment_plan", Integer.class));
        assertEquals("计划", jdbc.queryForObject("SELECT status FROM treatment_plan_item WHERE item_id=1", String.class));
    }

    @Test void onlyActualVisitDentistCanCreateFollowupPlan() throws Exception {
        insertVisit();
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(11,'合成第二牙医','牙医','牙医','SYNTH-DENTIST-11','在岗') ON CONFLICT (staff_id) DO NOTHING");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(11,11,'dentist11-test','NOT-A-LOGIN-HASH',true,false,now()) ON CONFLICT (account_id) DO NOTHING");
        long role = jdbc.queryForObject("SELECT role_id FROM role WHERE code='dentist'", Long.class);
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) VALUES(11,?,now(),'合成测试授权') ON CONFLICT DO NOTHING", role);
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(11,?,1) ON CONFLICT DO NOTHING", role);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(11,1,true,now()) ON CONFLICT DO NOTHING");
        String body = "{\"visit_id\":1,\"suggested_from\":\"2030-01-03\",\"suggested_to\":\"2030-01-10\",\"duration_minutes\":30,\"purpose\":\"复诊评估\"}";
        var denied = call("POST", "/staff/followup-plans", body, session(11));
        assertEquals(403, denied.statusCode(), denied.body());
        var otherDoctorOverview = call("GET", "/staff/clinical", null, session(11));
        assertEquals(200, otherDoctorOverview.statusCode(), otherDoctorOverview.body());
        assertTrue(otherDoctorOverview.body().contains("\"plan_source_visits\":[]"), otherDoctorOverview.body());
        var sourceDoctorOverview = call("GET", "/staff/clinical", null, session(1));
        assertTrue(sourceDoctorOverview.body().contains("\"plan_source_visits\":[{\"visit_id\":\"1\""), sourceDoctorOverview.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM followup_plan WHERE source_visit_id=1", Integer.class));
        var created = call("POST", "/staff/followup-plans", body, session(1));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(1L, jdbc.queryForObject("SELECT dentist_id FROM followup_plan WHERE source_visit_id=1", Long.class));
        var duplicate = call("POST", "/staff/followup-plans", body, session(1));
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertTrue(duplicate.body().contains("FOLLOWUP_PLAN_ALREADY_ACTIVE"), duplicate.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM followup_plan WHERE source_visit_id=1", Integer.class));
    }

    @Test void followupServiceItemIsPersistedAndMustMatchPatientBooking() throws Exception {
        seedPatientFollowup();
        jdbc.update("UPDATE followup_plan SET service_item_id=1 WHERE plan_id=1");
        var records = callPatient("GET", "/me/records", null, patientSession(4));
        assertEquals(200, records.statusCode(), records.body());
        assertTrue(records.body().contains("\"service_item_id\":\"1\""), records.body());
        jdbc.update("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(2,'合成其他项目','次',9000,true,true,30,1)");
        jdbc.update("UPDATE followup_plan SET service_item_id=2 WHERE plan_id=1");
        var mismatch = callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patientSession(4));
        assertEquals(409, mismatch.statusCode(), mismatch.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1", Integer.class));
        jdbc.update("UPDATE followup_plan SET service_item_id=1 WHERE plan_id=1");
        var matched = callPatient("POST", "/me/appointments", followupBody("2030-01-02T01:30:00Z"), patientSession(4));
        assertEquals(201, matched.statusCode(), matched.body());
    }

    @Test void doctorMayCreatePlanWithOptionalOwnedServiceItem() throws Exception {
        insertVisit();
        var overview = call("GET", "/staff/clinical", null, session(1));
        assertEquals(200, overview.statusCode(), overview.body());
        assertTrue(overview.body().contains("\"plan_services\":[{"), overview.body());
        assertTrue(overview.body().contains("\"item_id\":\"1\""), overview.body());
        String prefix = "{\"visit_id\":1,\"suggested_from\":\"2030-01-03\",\"suggested_to\":\"2030-01-10\",\"duration_minutes\":30,\"purpose\":\"复诊评估\",\"service_item_id\":";
        assertEquals(422, call("POST", "/staff/followup-plans", prefix + "999}", session(1)).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM followup_plan", Integer.class));
        var created = call("POST", "/staff/followup-plans", prefix + "1}", session(1));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(1L, jdbc.queryForObject("SELECT service_item_id FROM followup_plan WHERE source_visit_id=1", Long.class));
        assertTrue(created.body().contains("\"service_item_id\":\"1\""), created.body());
    }

    @Test void concurrentFollowupPlanCreationKeepsOneActivePlan() throws Exception {
        insertVisit();
        var dentist = session(1);
        String body = "{\"visit_id\":1,\"suggested_from\":\"2030-01-03\",\"suggested_to\":\"2030-01-10\",\"duration_minutes\":30,\"purpose\":\"复诊评估\"}";
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> request = () -> {
                start.await();
                return call("POST", "/staff/followup-plans", body, dentist).statusCode();
            };
            var first = pool.submit(request);
            var second = pool.submit(request);
            start.countDown();
            assertEquals(Set.of(201, 409), Set.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM followup_plan WHERE source_visit_id=1 AND state IN ('待协调','已预约')", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void ownerDoesNotInheritBillingPermission() throws Exception {
        assertEquals(403, call("GET", "/staff/billing", null, session(3)).statusCode());
        assertEquals(200, call("GET", "/staff/billing", null, session(2)).statusCode());
    }

    @Test void planBillIsCreatedOnlyOnce() throws Exception {
        long plan = insertAcceptedPlan();
        var frontdesk = session(2);
        var first = call("POST", "/staff/treatment-plans/" + plan + "/bill", "{}", frontdesk);
        assertEquals(201, first.statusCode(), first.body());
        var second = call("POST", "/staff/treatment-plans/" + plan + "/bill", "{}", frontdesk);
        assertEquals(409, second.statusCode(), second.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM bill WHERE plan_id=?", Integer.class, plan));
    }

    @Test void redRequestWaitsForCompletionAndRequiresBasis() throws Exception {
        insertPaidBill();
        jdbc.update("""
            INSERT INTO invoice(invoice_id,bill_id,title,amount_cents,status,requested_by,requested_at,
              invoice_type,buyer_type,buyer_tax_no,tax_treatment,tax_rate_bps,net_amount_cents,tax_amount_cents,issued_at,issued_by,invoice_no)
            VALUES(1,1,'合成单位',60000,'已开票',2,now(),'增值税普通发票','单位','91330000TEST0001','应税',600,56604,3396,now(),2,'ORIG-1')
            """);
        jdbc.execute("SELECT setval(pg_get_serial_sequence('invoice','invoice_id'), 1, true)");
        var frontdesk = session(2);
        var red = call("POST", "/staff/invoices/1/red", "{}", frontdesk);
        assertEquals(201, red.statusCode(), red.body());
        assertEquals("已开票", jdbc.queryForObject("SELECT status FROM invoice WHERE invoice_id=1", String.class));
        long redId = Long.parseLong(jdbc.queryForObject("SELECT invoice_id FROM invoice WHERE red_of_id=1", Long.class).toString());
        var noBasis = call("POST", "/staff/invoices/" + redId + "/issue", "{\"invoice_no\":\"RED-1\"}", frontdesk);
        assertEquals(422, noBasis.statusCode(), noBasis.body());
        var completed = call("POST", "/staff/invoices/" + redId + "/issue", "{\"invoice_no\":\"RED-1\",\"basis\":\"已核对并登记红冲完成\"}", frontdesk);
        assertEquals(200, completed.statusCode(), completed.body());
        assertEquals("已红冲", jdbc.queryForObject("SELECT status FROM invoice WHERE invoice_id=1", String.class));
        assertEquals(409, call("POST", "/staff/invoices/1/red", "{}", frontdesk).statusCode());
    }

    @Test void frontdeskCannotBillAnotherBranch() throws Exception {
        long plan = insertAcceptedPlan();
        jdbc.update("UPDATE treatment_plan SET branch_id=2 WHERE plan_id=?", plan);
        var response = call("POST", "/staff/treatment-plans/" + plan + "/bill", "{}", session(2));
        assertEquals(403, response.statusCode(), response.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM bill WHERE plan_id=?", Integer.class, plan));
    }

    @Test void patientCannotAcceptAnotherPatientsPlan() throws Exception {
        insertVisit();
        jdbc.update("INSERT INTO treatment_plan(plan_id,patient_id,branch_id,dentist_id,source_visit_id,title,state,estimated_total_cents,version,created_by,created_at) VALUES(1,1,1,1,1,'他人计划','待患者确认',10000,1,1,now())");
        jdbc.update("INSERT INTO treatment_plan_item(item_id,plan_id,name,quantity,unit_price_cents,amount_cents,status,sort_order) VALUES(1,1,'项目',1,10000,10000,'计划',0)");
        var response = callPatient("POST", "/me/treatment-plans/1/accept", "{\"basis\":\"越权测试\"}", patientSession(4));
        assertEquals(404, response.statusCode(), response.body());
    }

    @Test void individualExemptInvoiceNeedsNoTaxNumber() throws Exception {
        insertPaidBill();
        var body = "{\"bill_id\":1,\"title\":\"个人免税\",\"invoice_type\":\"增值税普通发票\",\"buyer_type\":\"个人\",\"tax_treatment\":\"免税\",\"tax_rate_bps\":0,\"net_amount_cents\":60000,\"tax_amount_cents\":0}";
        var response = call("POST", "/staff/invoices", body, session(2));
        assertEquals(201, response.statusCode(), response.body());
    }

    @Test void overpaymentIsRejectedAndDuplicatePaymentIsBlocked() throws Exception {
        jdbc.update("INSERT INTO bill(bill_id,patient_id,branch_id,total_cents,paid_cents,status,version,created_at) VALUES(2,1,1,1000,0,'待收款',1,now())");
        var frontdesk = session(2);
        var over = call("POST", "/staff/bills/2/payments", "{\"amount_cents\":2000,\"method\":\"现金\"}", frontdesk);
        assertEquals(422, over.statusCode(), over.body());
        assertEquals(0, jdbc.queryForObject("SELECT paid_cents FROM bill WHERE bill_id=2", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM payment WHERE bill_id=2", Integer.class));
        var paid = call("POST", "/staff/bills/2/payments", "{\"amount_cents\":1000,\"method\":\"现金\"}", frontdesk);
        assertEquals(200, paid.statusCode(), paid.body());
        var duplicate = call("POST", "/staff/bills/2/payments", "{\"amount_cents\":1000,\"method\":\"现金\"}", frontdesk);
        assertEquals(409, duplicate.statusCode(), duplicate.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment WHERE bill_id=2", Integer.class));
    }

    @Test void concurrentAppointmentHasOneWinner() throws Exception {
        var issued = session(2);
        var latch = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> a = () -> { latch.await(); return call("POST", "/staff/appointments",
            "{\"patient_id\":1,\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"channel\":\"电话\",\"purpose\":\"并发甲\"}", issued).statusCode(); };
        Callable<Integer> b = () -> { latch.await(); return call("POST", "/staff/appointments",
            "{\"patient_id\":2,\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"channel\":\"电话\",\"purpose\":\"并发乙\"}", issued).statusCode(); };
        var fa = pool.submit(a); var fb = pool.submit(b);
        latch.countDown();
        List<Integer> statuses = List.of(fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertTrue(statuses.contains(201) && statuses.contains(409), statuses.toString());
    }

    @Test void refundReservationsCannotExceedCollectedAmount() throws Exception {
        insertPaidBill();
        var actor = session(2);
        String first = "{\"bill_id\":1,\"payment_id\":0,\"amount_cents\":40000,\"reason\":\"测试申请\"}";
        assertEquals(201, call("POST", "/staff/refunds", first, actor).statusCode());
        assertEquals(422, call("POST", "/staff/refunds", first, actor).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund", Integer.class));
        jdbc.update("UPDATE refund SET status='已拒绝',processed_by=5,processed_at=now(),processing_basis='合成拒绝依据'");
        assertEquals(201, call("POST", "/staff/refunds", first, actor).statusCode());
    }

    @Test void financeProcessesRefundAndPatientSeesOutcome() throws Exception {
        insertPaidBill();
        var frontdesk = session(2);
        var finance = session(6);
        var requested = call("POST", "/staff/refunds", "{\"bill_id\":1,\"payment_id\":0,\"amount_cents\":20000,\"reason\":\"多收款\"}", frontdesk);
        assertEquals(201, requested.statusCode(), requested.body());
        long id = jdbc.queryForObject("SELECT refund_id FROM refund", Long.class);
        String path = "/staff/refunds/" + id + "/process";
        String decision = "{\"decision\":\"已退款\",\"basis\":\"线下退款凭证已核实\"}";
        assertEquals(403, call("POST", path, decision, frontdesk).statusCode());
        assertEquals(422, call("POST", path, "{\"decision\":\"已退款\",\"basis\":\" \"}", finance).statusCode());
        assertEquals(200, call("POST", path, decision, finance).statusCode());
        assertEquals(409, call("POST", path, decision, finance).statusCode());
        assertEquals("部分退款", jdbc.queryForObject("SELECT status FROM bill WHERE bill_id=1", String.class));
        assertEquals(60000, jdbc.queryForObject("SELECT paid_cents FROM bill WHERE bill_id=1", Integer.class));
        var ownBilling = callPatient("GET", "/me/billing", null, patientSession(7));
        assertEquals(200, ownBilling.statusCode(), ownBilling.body());
        assertTrue(ownBilling.body().contains("部分退款"));
        assertTrue(ownBilling.body().contains("已退款"));
    }

    @Test void rejectedRefundReleasesReservationAndFullRefundKeepsInvoiceIndependent() throws Exception {
        insertPaidBill();
        var frontdesk = session(2);
        var finance = session(6);
        String request = "{\"bill_id\":1,\"payment_id\":0,\"amount_cents\":60000,\"reason\":\"合成全额申请\"}";
        assertEquals(201, call("POST", "/staff/refunds", request, frontdesk).statusCode());
        long firstId = jdbc.queryForObject("SELECT max(refund_id) FROM refund", Long.class);
        assertEquals(200, call("POST", "/staff/refunds/" + firstId + "/process",
            "{\"decision\":\"已拒绝\",\"basis\":\"凭证不足\"}", finance).statusCode());
        assertEquals("已收款", jdbc.queryForObject("SELECT status FROM bill WHERE bill_id=1", String.class));
        assertEquals(201, call("POST", "/staff/refunds", request, frontdesk).statusCode());
        long secondId = jdbc.queryForObject("SELECT max(refund_id) FROM refund", Long.class);
        assertEquals(200, call("POST", "/staff/refunds/" + secondId + "/process",
            "{\"decision\":\"已退款\",\"basis\":\"已核对线下退款凭证\"}", finance).statusCode());
        assertEquals("已退款", jdbc.queryForObject("SELECT status FROM bill WHERE bill_id=1", String.class));
        assertEquals(422, call("POST", "/staff/refunds", request, frontdesk).statusCode());
    }

    @Test void refundPaymentMustBelongToBillAndHaveRemainingAmount() throws Exception {
        insertPaidBill();
        jdbc.update("INSERT INTO bill(bill_id,patient_id,branch_id,total_cents,paid_cents,status,version,created_at) VALUES(2,1,1,1000,1000,'已收款',1,now())");
        jdbc.update("INSERT INTO payment(payment_id,bill_id,amount_cents,method,state,received_by,received_at) VALUES(10,2,1000,'现金','已登记',2,now()),(11,1,1000,'现金','已登记',2,now())");
        var actor = session(2);
        assertEquals(422, call("POST", "/staff/refunds", "{\"bill_id\":1,\"payment_id\":10,\"amount_cents\":500,\"reason\":\"错误关联\"}", actor).statusCode());
        String body = "{\"bill_id\":1,\"payment_id\":11,\"amount_cents\":600,\"reason\":\"测试\"}";
        assertEquals(201, call("POST", "/staff/refunds", body, actor).statusCode());
        assertEquals(422, call("POST", "/staff/refunds", body, actor).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM refund", Integer.class));
    }

    @Test void partialPaymentRetryDoesNotCollectTwice() throws Exception {
        jdbc.update("INSERT INTO bill(bill_id,patient_id,branch_id,total_cents,paid_cents,status,version,created_at) VALUES(2,1,1,1000,0,'待收款',1,now())");
        var actor = session(2);
        String body = "{\"amount_cents\":300,\"method\":\"现金\"}";
        assertEquals(200, callKey("POST", "/staff/bills/2/payments", body, actor, "partial-test").statusCode());
        assertEquals(200, callKey("POST", "/staff/bills/2/payments", body, actor, "partial-test").statusCode());
        assertEquals(409, callKey("POST", "/staff/bills/2/payments", body.replace("300", "400"), actor, "partial-test").statusCode());
        assertEquals(300, jdbc.queryForObject("SELECT paid_cents FROM bill WHERE bill_id=2", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payment WHERE bill_id=2", Integer.class));
    }

    @Test void staffBookingRetryKeepsOneAppointmentAndConsent() throws Exception {
        var actor=session(2);
        String body="{\"patient_id\":1,\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\",\"visit_type\":\"初诊\",\"channel\":\"电话\",\"purpose\":\"测试重试\"}";
        assertEquals(201,callKey("POST","/staff/appointments",body,actor,"booking-retry").statusCode());
        assertEquals(201,callKey("POST","/staff/appointments",body,actor,"booking-retry").statusCode());
        assertEquals(409,callKey("POST","/staff/appointments",body.replace("测试重试","改变内容"),actor,"booking-retry").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM appointment",Integer.class));
        assertEquals("合成患者电话确认",jdbc.queryForObject("SELECT consent_basis FROM appointment",String.class));
    }

    @Test void patientCannotCancelClinicalOrQueueStates() throws Exception {
        insertVisit();
        jdbc.update("UPDATE appointment SET patient_id=2 WHERE appointment_id=1");
        var actor = patientSession(4);
        for (String state : List.of("候诊中", "接诊中", "已完成")) {
            jdbc.update("UPDATE appointment SET state=? WHERE appointment_id=1", state);
            assertEquals(409, callPatient("POST", "/me/appointments/1/cancel", "{}", actor).statusCode());
            assertEquals(state, jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=1", String.class));
        }
        jdbc.update("UPDATE appointment SET state='已确认' WHERE appointment_id=1");
        assertEquals(200, callPatient("POST", "/me/appointments/1/cancel", "{}", actor).statusCode());
        assertEquals(409, callPatient("POST", "/me/appointments/1/cancel", "{}", actor).statusCode());
    }

    @Test void patientCannotBackdateAndFailedReschedulePreservesAppointment() throws Exception {
        insertVisit();
        jdbc.update("UPDATE appointment SET patient_id=2,state='已确认' WHERE appointment_id=1");
        var before = jdbc.queryForMap("SELECT schedule_id,starts_at,ends_at,version FROM appointment WHERE appointment_id=1");
        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(99,1,'2020-01-02T01:00:00Z','2020-01-02T10:00:00Z','两者','已发布',1,now(),0)");
        try {
            var actor = patientSession(4);
            String body = "{\"schedule_id\":99,\"item_id\":1,\"starts_at\":\"2020-01-02T09:00:00+08:00\"}";
            assertEquals(409, callPatient("POST", "/me/appointments", body, actor).statusCode());
            assertEquals(409, callPatient("POST", "/me/appointments/1/reschedule", body.substring(0,body.length()-1)+",\"expected_version\":1,\"reason\":\"测试\"}", actor).statusCode());
            assertEquals(before, jdbc.queryForMap("SELECT schedule_id,starts_at,ends_at,version FROM appointment WHERE appointment_id=1"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));
            assertEquals(404, callPatient("POST", "/me/appointments/999999/cancel", "{}", actor).statusCode());
            jdbc.update("UPDATE appointment SET patient_id=1 WHERE appointment_id=1");
            assertEquals(404, callPatient("POST", "/me/appointments/1/cancel", "{}", actor).statusCode());
        } finally { jdbc.update("DELETE FROM schedule WHERE schedule_id=99"); }
    }

    @Test void patientCreationRetryReturnsSameAppointment() throws Exception {
        var actor = patientSession(4);
        String body = "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\"}";
        var first = callPatient("POST", "/me/appointments", body, actor);
        assertEquals(201, first.statusCode(), first.body());
        var second = callPatient("POST", "/me/appointments", body, actor);
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM appointment", Integer.class));

    }

    private long createPatientAppointment(SessionService.Issued actor) throws Exception {
        var response = callPatientKey("POST", "/me/appointments",
            "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\"}", actor, UUID.randomUUID().toString());
        assertEquals(201, response.statusCode(), response.body());
        return jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=2 AND state='已确认'", Long.class);
    }

    private String moveBody(String time, long version) {
        return "{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T"+time+":00+08:00\",\"expected_version\":"+version+",\"reason\":\"患者调整时间\"}";
    }

    @Test void reschedulePreservesHistoryAndReplayDoesNotCreateAnotherSuccessor() throws Exception {
        var actor=patientSession(4);
        long id=createPatientAppointment(actor);
        var old=jdbc.queryForMap("SELECT starts_at,ends_at,confirmed_dentist_id FROM appointment WHERE appointment_id=?",id);
        String path="/me/appointments/"+id+"/reschedule";
        String key=UUID.randomUUID().toString();
        var first=callPatientKey("POST",path,moveBody("10:00",1),actor,key);
        assertEquals(200,first.statusCode(),first.body());
        assertTrue(first.body().contains("original") && first.body().contains("replacement"));
        long next=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE previous_id=?",Long.class,id);
        assertNotEquals(id,next);
        assertEquals(old,jdbc.queryForMap("SELECT starts_at,ends_at,confirmed_dentist_id FROM appointment WHERE appointment_id=?",id));
        assertEquals("已改约",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,next));
        assertEquals(200,callPatientKey("POST",path,moveBody("10:00",1),actor,key).statusCode());
        assertEquals(409,callPatientKey("POST",path,moveBody("11:00",1),actor,key).statusCode());
        assertEquals(409,callPatientKey("POST",path,moveBody("10:00",1),actor,UUID.randomUUID().toString()).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE previous_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=? AND operation='patient-reschedule'",Integer.class,id));
    }

    @Test void rescheduleResourceFailureRollsBackAndSameKeyCanRecover() throws Exception {
        var actor=patientSession(4);long id=createPatientAppointment(actor);
        var before=jdbc.queryForMap("SELECT state,version,starts_at,ends_at FROM appointment WHERE appointment_id=?",id);
        String path="/me/appointments/"+id+"/reschedule",key=UUID.randomUUID().toString();
        jdbc.update("UPDATE resource SET operating_state='停用' WHERE resource_id=1");
        try {
            assertEquals(409,callPatientKey("POST",path,moveBody("10:00",1),actor,key).statusCode());
            assertEquals(before,jdbc.queryForMap("SELECT state,version,starts_at,ends_at FROM appointment WHERE appointment_id=?",id));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE previous_id=?",Integer.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE request_key=?",Integer.class,key));
        } finally { jdbc.update("UPDATE resource SET operating_state='可用' WHERE resource_id=1"); }
        assertEquals(200,callPatientKey("POST",path,moveBody("10:00",1),actor,key).statusCode());
    }

    @Test void rescheduleCarriesFollowupWithoutCompletingGoal() throws Exception {
        insertVisit();
        jdbc.update("UPDATE appointment SET patient_id=2 WHERE appointment_id=1");
        jdbc.execute("SELECT setval(pg_get_serial_sequence('appointment','appointment_id'),greatest(100,(SELECT last_value FROM appointment_appointment_id_seq))+100,true)");
        jdbc.update("INSERT INTO followup_plan(plan_id,source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,state) VALUES(1,1,1,'2030-01-01','2030-01-10',30,'复诊目标','已预约')");
        var actor=patientSession(4);long id=createPatientAppointment(actor);
        jdbc.update("UPDATE appointment SET followup_plan_id=1,visit_type='复诊' WHERE appointment_id=?",id);
        var result=callPatientKey("POST","/me/appointments/"+id+"/reschedule",moveBody("10:00",1),actor,UUID.randomUUID().toString());
        assertEquals(200,result.statusCode(),result.body());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE followup_plan_id=1 AND state='已确认'",Integer.class));
        assertEquals("已预约",jdbc.queryForObject("SELECT state FROM followup_plan WHERE plan_id=1",String.class));
    }

    @Test void concurrentReschedulesHaveOnlyOneSuccessor() throws Exception {
        var actor=patientSession(4);long id=createPatientAppointment(actor);
        String path="/me/appointments/"+id+"/reschedule";
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{gate.await();return callPatientKey("POST",path,moveBody("10:00",1),actor,UUID.randomUUID().toString()).statusCode();});
            var b=pool.submit(()->{gate.await();return callPatientKey("POST",path,moveBody("11:00",1),actor,UUID.randomUUID().toString()).statusCode();});
            gate.countDown();var statuses=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertTrue(statuses.contains(200)&&statuses.contains(409),statuses.toString());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE previous_id=?",Integer.class,id));
        } finally {pool.shutdownNow();}
    }

    @Test void sameKeyConcurrentRescheduleReplaysAndOtherPatientCannotReadIt() throws Exception {
        var actor=patientSession(4);long id=createPatientAppointment(actor);
        String path="/me/appointments/"+id+"/reschedule",key=UUID.randomUUID().toString();
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task=()->{gate.await();return callPatientKey("POST",path,moveBody("10:00",1),actor,key).statusCode();};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();
            assertEquals(200,a.get(20,TimeUnit.SECONDS));assertEquals(200,b.get(20,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM appointment WHERE previous_id=?",Integer.class,id));
        } finally {pool.shutdownNow();}
        jdbc.update("UPDATE appointment SET patient_id=1 WHERE appointment_id=?",id);
        assertEquals(404,callPatientKey("POST",path,moveBody("10:00",1),actor,key).statusCode());
    }

    @Test void unresolvedMaintenanceBlocksBookingEvenAfterEstimatedEnd() throws Exception {
        jdbc.update("INSERT INTO resource_unavailable(resource_id,kind,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'维护','2029-01-01T00:00:00Z','2029-01-02T00:00:00Z','合成维护','有效',3,now())");
        var actor=patientSession(4);
        String body="{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\"2030-01-02T09:00:00+08:00\"}";
        try {
            assertEquals(409,callPatient("POST","/me/appointments",body,actor).statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM appointment",Integer.class));
            jdbc.update("UPDATE resource_unavailable SET state='已撤销',resolved_at=now(),resolved_by=3,resolve_type='已修复',resolve_basis='合成恢复'");
            assertEquals(201,callPatient("POST","/me/appointments",body,actor).statusCode());
        } finally {jdbc.update("DELETE FROM resource_unavailable");}
    }

    private long confirmedForCheckin(long patientId, String start, String end) {
        return jdbc.queryForObject("""
            INSERT INTO appointment(patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,
                starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,
                request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES (?,1,1,1,?::timestamptz,?::timestamptz,'初诊','合成签到','现场','已确认',
                '无需协调',now(),gen_random_uuid(),repeat('a',64),2,1,1)
            RETURNING appointment_id
            """, Long.class, patientId, start, end);
    }

    private static String checkinBody(long version, String exceptionReason) {
        return "{\"expected_version\":" + version + ",\"verification_basis\":\"现场核对就诊凭据\"" +
            (exceptionReason == null ? "" : ",\"exception_reason\":\"" + exceptionReason + "\"") + "}";
    }

    @Test void frontdeskCheckinIsRepeatableAndRejectsOtherDay() throws Exception {
        var actor=session(2);
        long today=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        long tomorrow=confirmedForCheckin(2,"2030-01-03T01:00:00Z","2030-01-03T01:30:00Z");
        assertEquals(409,call("POST","/staff/appointments/"+tomorrow+"/checkin",checkinBody(1,"人工确认跨日"),actor).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM queue_ticket",Integer.class));
        var first=call("POST","/staff/appointments/"+today+"/checkin",checkinBody(1,"现场人工超窗核验"),actor);
        assertEquals(200,first.statusCode());
        assertTrue(first.body().contains("\"queue_no\":1"));
        assertEquals(200,call("POST","/staff/appointments/"+today+"/checkin",checkinBody(1,"现场人工超窗核验"),actor).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM queue_ticket WHERE appointment_id=?",Integer.class,today));
        assertEquals(1,jdbc.queryForObject("SELECT last_number FROM queue_counter WHERE branch_id=1 AND queue_date='2030-01-02'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT version FROM appointment WHERE appointment_id=?",Integer.class,today));
    }

    @Test void concurrentFrontdeskCheckinsGetDistinctMonotonicNumbers() throws Exception {
        var actor=session(2);
        long first=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        long second=confirmedForCheckin(2,"2030-01-02T01:30:00Z","2030-01-02T02:00:00Z");
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{gate.await();return call("POST","/staff/appointments/"+first+"/checkin",checkinBody(1,"现场人工超窗核验"),actor).statusCode();});
            var b=pool.submit(()->{gate.await();return call("POST","/staff/appointments/"+second+"/checkin",checkinBody(1,"现场人工超窗核验"),actor).statusCode();});
            gate.countDown();
            assertEquals(200,a.get(20,TimeUnit.SECONDS));
            assertEquals(200,b.get(20,TimeUnit.SECONDS));
            assertEquals(List.of(1,2),jdbc.queryForList("SELECT queue_no FROM queue_ticket WHERE branch_id=1 ORDER BY queue_no",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT last_number FROM queue_counter WHERE branch_id=1 AND queue_date='2030-01-02'",Integer.class));
        } finally {pool.shutdownNow();}
    }

    @Test void firstCounterContinuesExistingTicketNumbers() throws Exception {
        long prior=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        long next=confirmedForCheckin(2,"2030-01-02T01:30:00Z","2030-01-02T02:00:00Z");
        jdbc.update("UPDATE appointment SET state='候诊中',checked_in_at=now() WHERE appointment_id=?",prior);
        jdbc.update("""
            INSERT INTO queue_ticket(appointment_id,queue_date,queue_no,registered_by,registered_at,
                state,priority,branch_id,checkin_channel)
            VALUES (?,'2030-01-02',4,2,now(),'候诊',0,1,'前台')
            """,prior);
        assertEquals(200,call("POST","/staff/appointments/"+next+"/checkin",checkinBody(1,"现场人工超窗核验"),session(2)).statusCode());
        assertEquals(5,jdbc.queryForObject("SELECT queue_no FROM queue_ticket WHERE appointment_id=?",Integer.class,next));
        assertEquals(5,jdbc.queryForObject("SELECT last_number FROM queue_counter WHERE branch_id=1 AND queue_date='2030-01-02'",Integer.class));
    }

    @Test void frontdeskCheckinRequiresVersionEvidenceAndManualReasonWithoutWindow() throws Exception {
        long id=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        var actor=session(2);
        String path="/staff/appointments/"+id+"/checkin";
        assertEquals(422,call("POST",path,"{}",actor).statusCode());
        assertEquals(409,call("POST",path,checkinBody(2,"人工核实"),actor).statusCode());
        assertEquals(422,call("POST",path,checkinBody(1,null),actor).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM queue_ticket",Integer.class));
        var result=call("POST",path,checkinBody(1,"现场人工确认提前到店"),actor);
        assertEquals(200,result.statusCode());
        assertTrue(result.body().contains("\"checkin_channel\":\"前台\""));
        assertEquals("现场核对就诊凭据",jdbc.queryForObject("SELECT verification_basis FROM queue_ticket WHERE appointment_id=?",String.class,id));
        assertEquals("现场人工确认提前到店",jdbc.queryForObject("SELECT exception_reason FROM queue_ticket WHERE appointment_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='queue_ticket' AND operation='frontdesk_checkin'",Integer.class));
    }

    @Test void configuredWindowAllowsOrdinaryCheckinAndDentistStopBlocksManualOverride() throws Exception {
        jdbc.update("INSERT INTO branch_booking_config(branch_id,early_minutes,late_minutes,slot_step_minutes,version,updated_by,updated_at) VALUES(1,1,15,15,1,2,now())");
        long id=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        var actor=session(2);
        String path="/staff/appointments/"+id+"/checkin";
        jdbc.update("INSERT INTO dentist_unavailable(dentist_id,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,'2030-01-02T01:00:00Z','2030-01-02T01:30:00Z','合成停诊','有效',3,now())");
        assertEquals(409,call("POST",path,checkinBody(1,"人工确认"),actor).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM queue_ticket",Integer.class));
        jdbc.update("UPDATE dentist_unavailable SET state='已撤销',resolved_at=greatest(now(),recorded_at),resolved_by=3,resolve_basis='合成测试撤销'");
        String key=UUID.randomUUID().toString();
        var first=callKey("POST",path,checkinBody(1,null),actor,key);
        assertEquals(200,first.statusCode());
        assertEquals(200,callKey("POST",path,checkinBody(1,null),actor,key).statusCode());
        assertEquals(409,callKey("POST",path,checkinBody(1,"变更内容"),actor,key).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM queue_ticket WHERE appointment_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE operation='staff-checkin'",Integer.class));
    }

    @Test void queueIsMinimalAndScopedToFrontdeskBranchOrOwnDentist() throws Exception {
        long own=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        assertEquals(200,call("POST","/staff/appointments/"+own+"/checkin",
            checkinBody(1,"现场人工超窗核验"),session(2)).statusCode());
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(8,'合成其他牙医','牙医','牙医','SYNTH-DENTIST-8','在岗')");
        try {
            jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes) VALUES(8,8,'2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','两者','已发布',1,now(),0)");
            long other=jdbc.queryForObject("""
                INSERT INTO appointment(patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,
                    starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,
                    checked_in_at,request_key,request_hash,requested_by,branch_id,policy_id)
                VALUES (2,8,8,8,'2030-01-02T02:00:00Z','2030-01-02T02:30:00Z','初诊','合成其他签到',
                    '现场','候诊中','无需协调',now(),now(),gen_random_uuid(),repeat('b',64),2,1,1)
                RETURNING appointment_id
                """, Long.class);
            jdbc.update("INSERT INTO queue_ticket(appointment_id,queue_date,queue_no,registered_by,registered_at,state,priority,branch_id,checkin_channel) VALUES (?,'2030-01-02',7,2,now(),'候诊',0,1,'前台')",other);
            String path="/staff/queue?branch_id=1&date=2030-01-02";
            var frontdesk=call("GET",path,null,session(2));
            assertEquals(200,frontdesk.statusCode());
            assertTrue(frontdesk.body().contains("\"queue_no\":7"));
            assertTrue(frontdesk.body().contains("\"queue_no\":1"));
            assertFalse(frontdesk.body().contains("合成患者"));
            assertFalse(frontdesk.body().contains("phone"));
            var dentist=call("GET",path,null,session(1));
            assertEquals(200,dentist.statusCode());
            assertTrue(dentist.body().contains("\"queue_no\":1"));
            assertFalse(dentist.body().contains("\"queue_no\":7"));
            assertEquals(403,call("GET","/staff/queue?branch_id=2&date=2030-01-02",null,session(2)).statusCode());
            assertEquals(403,callPatient("GET",path,null,patientSession(4)).statusCode());
            assertEquals(422,call("GET","/staff/queue?branch_id=1&date=not-a-date",null,session(2)).statusCode());
        } finally {
            jdbc.update("DELETE FROM queue_ticket WHERE appointment_id IN (SELECT appointment_id FROM appointment WHERE schedule_id=8)");
            jdbc.update("DELETE FROM appointment WHERE schedule_id=8");
            jdbc.update("DELETE FROM schedule WHERE schedule_id=8");
            jdbc.update("DELETE FROM staff WHERE staff_id=8");
        }
    }

    @Test void checkinConfigurationRequiresOwnerVersionAndEnablesShortLivedCode() throws Exception {
        String configPath="/staff/branches/1/booking-config";
        String codePath="/staff/branches/1/checkin-code";
        var owner=session(3);
        var frontdesk=session(2);
        assertEquals(200,call("GET",configPath,null,owner).statusCode());
        assertEquals(403,call("PUT",configPath,"{\"self_checkin_enabled\":true,\"early_minutes\":30,\"late_minutes\":15,\"slot_step_minutes\":15,\"expected_version\":1}",frontdesk).statusCode());
        assertEquals(503,call("POST",codePath,null,frontdesk).statusCode());
        assertEquals(422,call("PUT",configPath,"{\"self_checkin_enabled\":true,\"slot_step_minutes\":15,\"expected_version\":1}",owner).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM branch_booking_config WHERE branch_id=1",Integer.class));
        String initial="{\"self_checkin_enabled\":true,\"early_minutes\":30,\"late_minutes\":15,\"slot_step_minutes\":15,\"expected_version\":1}";
        String key=UUID.randomUUID().toString();
        assertEquals(200,callKey("PUT",configPath,initial,owner,key).statusCode());
        assertEquals(200,callKey("PUT",configPath,initial,owner,key).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT version FROM branch_booking_config WHERE branch_id=1",Integer.class));
        assertEquals(200,call("PUT",configPath,initial,owner).statusCode()); // same-value update leaves version unchanged
        assertEquals(1,jdbc.queryForObject("SELECT version FROM branch_booking_config WHERE branch_id=1",Integer.class));
        String changed="{\"self_checkin_enabled\":true,\"early_minutes\":20,\"late_minutes\":15,\"slot_step_minutes\":15,\"expected_version\":1}";
        assertEquals(200,call("PUT",configPath,changed,owner).statusCode());
        assertEquals(2,jdbc.queryForObject("SELECT version FROM branch_booking_config WHERE branch_id=1",Integer.class));
        assertEquals(409,call("PUT",configPath,changed,owner).statusCode());
        String codeKey=UUID.randomUUID().toString();
        var first=callKey("POST",codePath,null,frontdesk,codeKey);
        var replay=callKey("POST",codePath,null,frontdesk,codeKey);
        assertEquals(200,first.statusCode());
        assertEquals(200,replay.statusCode());
        String code=first.body().replaceAll("(?s).*\\\"code\\\":\\\"([^\\\"]+)\\\".*","$1");
        assertEquals(code,replay.body().replaceAll("(?s).*\\\"code\\\":\\\"([^\\\"]+)\\\".*","$1"));
        var signer=app.getBean(cn.dental.organization.application.CheckinTokenService.class);
        signer.verify(code,1);
        assertThrows(cn.dental.shared.web.ApiException.class,()->signer.verify(code,2));
        CLOCK.set(CLOCK.instant().plusSeconds(60));
        assertThrows(cn.dental.shared.web.ApiException.class,()->signer.verify(code,1));
        assertFalse(first.body().contains("合成患者"));
        assertEquals(403,call("POST","/staff/branches/2/checkin-code",null,frontdesk).statusCode());
    }

    private String enabledCheckinCode() throws Exception {
        jdbc.update("INSERT INTO branch_booking_config(branch_id,self_checkin_enabled,early_minutes,late_minutes,slot_step_minutes,version,updated_by,updated_at) VALUES(1,true,5,15,15,1,3,now())");
        var issued=call("POST","/staff/branches/1/checkin-code",null,session(2));
        assertEquals(200,issued.statusCode(),issued.body());
        return issued.body().replaceAll("(?s).*\\\"code\\\":\\\"([^\\\"]+)\\\".*","$1");
    }

    private static String patientCheckinBody(String code) {
        return "{\"branch_checkin_token\":\""+code+"\",\"expected_version\":1}";
    }

    @Test void patientScanCheckinCreatesOneVisibleTicketAndRejectsInvalidScan() throws Exception {
        String code=enabledCheckinCode();
        long id=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        String path="/me/appointments/"+id+"/checkin";
        var owner=patientSession(7);
        assertEquals(404,callPatient("POST",path,patientCheckinBody(code),patientSession(4)).statusCode());
        assertEquals(409,callPatient("POST",path,patientCheckinBody("not-a-code"),owner).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM queue_ticket",Integer.class));
        String key=UUID.randomUUID().toString();
        var first=callPatientKey("POST",path,patientCheckinBody(code),owner,key);
        assertEquals(200,first.statusCode(),first.body());
        assertTrue(first.body().contains("\"queue_no\":1"));
        assertTrue(first.body().contains("\"checkin_channel\":\"小程序\""));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM queue_ticket WHERE registered_by IS NOT NULL",Integer.class));
        assertTrue(callPatient("GET","/me/booking",null,owner).body().contains("\"queue_no\":1"));
        CLOCK.set(CLOCK.instant().plusSeconds(60));
        assertEquals(200,callPatientKey("POST",path,patientCheckinBody(code),owner,key).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM queue_ticket",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT last_number FROM queue_counter WHERE branch_id=1",Integer.class));
    }

    @Test void patientAndFrontdeskConcurrentCheckinShareOneTicket() throws Exception {
        String code=enabledCheckinCode();
        long id=confirmedForCheckin(1,"2030-01-02T01:00:00Z","2030-01-02T01:30:00Z");
        var patient=patientSession(7); var frontdesk=session(2);
        var gate=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{gate.await();return callPatient("POST","/me/appointments/"+id+"/checkin",patientCheckinBody(code),patient);});
            var b=pool.submit(()->{gate.await();return call("POST","/staff/appointments/"+id+"/checkin",checkinBody(1,null),frontdesk);});
            gate.countDown();
            var patientResult=a.get(20,TimeUnit.SECONDS); var deskResult=b.get(20,TimeUnit.SECONDS);
            assertEquals(200,patientResult.statusCode(),patientResult.body());
            assertEquals(200,deskResult.statusCode(),deskResult.body());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM queue_ticket WHERE appointment_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT last_number FROM queue_counter WHERE branch_id=1",Integer.class));
            assertEquals(2,jdbc.queryForObject("SELECT version FROM appointment WHERE appointment_id=?",Integer.class,id));
        } finally { pool.shutdownNow(); }
    }

    @Test void doctorAbsenceImmediatelyClosesNewBookingWithoutCancellingExistingAppointment() throws Exception {
        var frontdesk = session(2);
        var owner = session(3);
        var existing = call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk);
        assertEquals(201, existing.statusCode(), existing.body());
        long appointmentId = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1", Long.class);
        String body = "{\"doctor_id\":\"1\",\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T11:00:00+08:00\",\"reason\":\"合成临时停诊\"}";
        assertEquals(403, call("POST", "/staff/dentist-unavailability", body, frontdesk).statusCode());
        String key = "doctor-stop-" + UUID.randomUUID();
        var first = callKey("POST", "/staff/dentist-unavailability", body, owner, key);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals(201, callKey("POST", "/staff/dentist-unavailability", body, owner, key).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM dentist_unavailable", Integer.class));
        assertEquals(409, call("POST", "/staff/appointments",
            bookingBody(2, "2030-01-02T09:30:00+08:00"), frontdesk).statusCode());
        assertEquals("已确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",
            String.class, appointmentId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",
            Integer.class, appointmentId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_record WHERE appointment_id=?",
            Integer.class, appointmentId));
    }

    @Test void doctorUnavailabilityListShowsAffectedSummaryAndResolveDoesNotTouchAppointment() throws Exception {
        var frontdesk = session(2);
        var owner = session(3);
        assertEquals(201, call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk).statusCode());
        long appointmentId = jdbc.queryForObject(
            "SELECT appointment_id FROM appointment WHERE patient_id=1", Long.class);
        int version = jdbc.queryForObject(
            "SELECT version FROM appointment WHERE appointment_id=?", Integer.class, appointmentId);
        String stop = "{\"doctor_id\":\"1\",\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成停诊列表\"}";
        assertEquals(403, call("POST", "/staff/dentist-unavailability", stop, frontdesk).statusCode());
        assertEquals(403, call("GET", "/staff/dentist-unavailability?state=有效", null, frontdesk).statusCode());
        assertEquals(403, call("GET", "/staff/resource-unavailability?branch_id=1", null, frontdesk).statusCode());
        assertEquals(201, call("POST", "/staff/dentist-unavailability", stop, owner).statusCode());
        var list = call("GET", "/staff/dentist-unavailability?state=有效", null, owner);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"affected_count\":1"), list.body());
        assertTrue(list.body().contains("\"appointment_id\":\"" + appointmentId + "\""), list.body());
        assertTrue(list.body().contains("\"coordination_state\""), list.body());
        assertFalse(list.body().contains("合成患者甲"), list.body());
        assertEquals(403, call("GET", "/staff/resource-unavailability?branch_id=2", null, owner).statusCode());
        assertEquals(403, call("GET", "/staff/resources?branch_id=2", null, owner).statusCode());

        long absenceId = jdbc.queryForObject(
            "SELECT absence_id FROM dentist_unavailable ORDER BY absence_id DESC LIMIT 1", Long.class);
        String resolve = "{\"resolve_basis\":\"合成核实后解除停诊\"}";
        String key = "doctor-resolve-" + UUID.randomUUID();
        assertEquals(200, callKey("POST", "/staff/dentist-unavailability/" + absenceId + "/resolve",
            resolve, owner, key).statusCode());
        assertEquals(200, callKey("POST", "/staff/dentist-unavailability/" + absenceId + "/resolve",
            resolve, owner, key).statusCode());
        assertEquals(409, call("POST", "/staff/dentist-unavailability/" + absenceId + "/resolve",
            resolve, owner).statusCode());
        var resolved = call("GET", "/staff/dentist-unavailability?state=已撤销", null, owner);
        assertEquals(200, resolved.statusCode(), resolved.body());
        assertTrue(resolved.body().contains("\"state\":\"已撤销\""), resolved.body());
        assertTrue(resolved.body().contains("\"affected_count\":0"), resolved.body());
        assertTrue(resolved.body().contains("\"resolved_by\":\"3\""), resolved.body());
        assertEquals("已确认", jdbc.queryForObject(
            "SELECT state FROM appointment WHERE appointment_id=?", String.class, appointmentId));
        assertEquals(version, jdbc.queryForObject(
            "SELECT version FROM appointment WHERE appointment_id=?", Integer.class, appointmentId));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",
            Integer.class, appointmentId));
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM contact_record WHERE appointment_id=?", Integer.class, appointmentId));
    }

    @Test void resourceUnavailabilityListShowsAffectedSummaryAndOptionsStayBranchScoped() throws Exception {
        var frontdesk = session(2);
        var owner = session(3);
        assertEquals(201, call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk).statusCode());
        long appointmentId = jdbc.queryForObject(
            "SELECT appointment_id FROM appointment WHERE patient_id=1", Long.class);
        int version = jdbc.queryForObject(
            "SELECT version FROM appointment WHERE appointment_id=?", Integer.class, appointmentId);
        String stop = "{\"resource_id\":\"1\",\"kind\":\"维护\","
            + "\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成维护列表\"}";
        assertEquals(403, call("POST", "/staff/resource-unavailability", stop, frontdesk).statusCode());
        assertEquals(201, call("POST", "/staff/resource-unavailability", stop, owner).statusCode());
        var list = call("GET", "/staff/resource-unavailability?branch_id=1&state=有效", null, owner);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"affected_count\":1"), list.body());
        assertTrue(list.body().contains("\"appointment_id\":\"" + appointmentId + "\""), list.body());
        assertTrue(list.body().contains("\"resource_name\":\"合成牙椅\""), list.body());
        var options = call("GET", "/staff/resources?branch_id=1", null, owner);
        assertEquals(200, options.statusCode(), options.body());
        assertTrue(options.body().contains("\"resource_id\":\"1\""), options.body());
        assertEquals(403, call("GET", "/staff/resource-unavailability?branch_id=2", null, owner).statusCode());
        assertEquals(403, call("GET", "/staff/resources?branch_id=2", null, owner).statusCode());

        long unavailableId = jdbc.queryForObject(
            "SELECT unavailable_id FROM resource_unavailable ORDER BY unavailable_id DESC LIMIT 1", Long.class);
        String resolve = "{\"resolve_type\":\"已修复\",\"resolve_basis\":\"合成现场检查\"}";
        assertEquals(200, call("POST", "/staff/resource-unavailability/" + unavailableId + "/resolve",
            resolve, owner).statusCode());
        var resolved = call("GET", "/staff/resource-unavailability?branch_id=1&state=已撤销", null, owner);
        assertEquals(200, resolved.statusCode(), resolved.body());
        assertTrue(resolved.body().contains("\"affected_count\":0"), resolved.body());
        assertEquals("已确认", jdbc.queryForObject(
            "SELECT state FROM appointment WHERE appointment_id=?", String.class, appointmentId));
        assertEquals(version, jdbc.queryForObject(
            "SELECT version FROM appointment WHERE appointment_id=?", Integer.class, appointmentId));
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",
            Integer.class, appointmentId));
    }

    @Test void resourceResolutionIsAuditedAndOtherActiveClosureStillBlocksBooking() throws Exception {
        var owner = session(3);
        var frontdesk = session(2);
        String body = "{\"resource_id\":\"1\",\"kind\":\"维护\","
            + "\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成设备维护\"}";
        assertEquals(403, call("POST", "/staff/resource-unavailability", body, frontdesk).statusCode());
        var first = call("POST", "/staff/resource-unavailability", body, owner);
        assertEquals(201, first.statusCode(), first.body());
        var second = call("POST", "/staff/resource-unavailability", body.replace("设备维护", "设备检查"), owner);
        assertEquals(201, second.statusCode(), second.body());
        var ids = jdbc.queryForList("SELECT unavailable_id FROM resource_unavailable ORDER BY unavailable_id", Long.class);
        assertEquals(2, ids.size());
        String resolve = "{\"resolve_type\":\"已修复\",\"resolve_basis\":\"现场检查通过\"}";
        String path = "/staff/resource-unavailability/" + ids.getFirst() + "/resolve";
        String key = "resolve-" + UUID.randomUUID();
        assertEquals(200, callKey("POST", path, resolve, owner, key).statusCode());
        assertEquals(200, callKey("POST", path, resolve, owner, key).statusCode());
        assertEquals(409, call("POST", path, resolve, owner).statusCode());
        assertEquals(3L, jdbc.queryForObject("SELECT resolved_by FROM resource_unavailable WHERE unavailable_id=?",
            Long.class, ids.getFirst()));
        assertEquals(3L, jdbc.queryForObject("SELECT recorded_by FROM resource_unavailable WHERE unavailable_id=?",
            Long.class, ids.getFirst()));
        assertEquals(409, call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk).statusCode());
        assertEquals(200, call("POST", "/staff/resource-unavailability/" + ids.get(1) + "/resolve",
            resolve, owner).statusCode());
        assertEquals(201, call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='resource_unavailable' AND operation='resolve'",
            Integer.class));
    }

    @Test void resourceClosureWaiterRechecksRevokedBranchRole() throws Exception {
        var owner = session(3);
        String body = "{\"resource_id\":\"1\",\"kind\":\"故障\","
            + "\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成故障\"}";
        long roleId = jdbc.queryForObject("SELECT role_id FROM role WHERE code='owner'", Long.class);
        try (var hold = app.getBean(DataSource.class).getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("SELECT branch_id FROM branch WHERE branch_id=1 FOR UPDATE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() ->
                    call("POST", "/staff/resource-unavailability", body, owner));
                awaitBranchLockWait();
                jdbc.update("DELETE FROM role_branch_scope WHERE account_id=3 AND role_id=? AND branch_id=1", roleId);
                hold.commit();
                var result = pending.get(15, TimeUnit.SECONDS);
                assertEquals(403, result.statusCode(), result.body());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM resource_unavailable", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE operation='resource-unavailability-create'", Integer.class));
            } finally {
                pool.shutdownNow();
                jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) VALUES(3,?,1) ON CONFLICT DO NOTHING", roleId);
            }
        }
    }

    @Test void existingBookingShowsClosureAlertAndCannotCheckIn() throws Exception {
        var frontdesk = session(2);
        var owner = session(3);
        var created = call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk);
        assertEquals(201, created.statusCode(), created.body());
        long id = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1", Long.class);
        String body = "{\"resource_id\":\"1\",\"kind\":\"故障\","
            + "\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成临时故障\"}";
        assertEquals(201, call("POST", "/staff/resource-unavailability", body, owner).statusCode());
        var list = call("GET", "/staff/appointments", null, frontdesk);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("\"unavailability_alert\":true"), list.body());
        assertEquals(409, call("POST", "/staff/appointments/" + id + "/checkin",
            checkinBody(1, "现场核验"), frontdesk).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM queue_ticket WHERE appointment_id=?",
            Integer.class, id));
        assertEquals("已确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",
            String.class, id));
    }

    @Test void manualOutageContactRequiresActualImpactAndKeepsAcknowledgmentSeparate() throws Exception {
        var frontdesk = session(2);
        var owner = session(3);
        assertEquals(201, call("POST", "/staff/appointments",
            bookingBody(1, "2030-01-02T09:00:00+08:00"), frontdesk).statusCode());
        long id = jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1", Long.class);
        String contact = "{\"kind\":\"人工联系\",\"channel\":\"电话\","
            + "\"appointment_id\":\"" + id + "\",\"result\":\"拨打电话未接通\","
            + "\"next_contact_at\":\"2030-01-02T10:00:00+08:00\"}";
        assertEquals(409, call("POST", "/staff/contacts", contact, frontdesk).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_record", Integer.class));
        String absence = "{\"doctor_id\":\"1\",\"starts_at\":\"2030-01-02T09:00:00+08:00\","
            + "\"ends_at\":\"2030-01-02T10:00:00+08:00\",\"reason\":\"合成临时停诊\"}";
        assertEquals(201, call("POST", "/staff/dentist-unavailability", absence, owner).statusCode());
        assertEquals(403, call("POST", "/staff/contacts", contact, owner).statusCode());
        String key = "contact-" + UUID.randomUUID();
        var first = callKey("POST", "/staff/contacts", contact, frontdesk, key);
        assertEquals(201, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"acknowledged\":false"), first.body());
        assertEquals(201, callKey("POST", "/staff/contacts", contact, frontdesk, key).statusCode());
        assertEquals(409, callKey("POST", "/staff/contacts",
            contact.replace("未接通", "已接通"), frontdesk, key).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM contact_record WHERE appointment_id=?",
            Integer.class, id));
        assertEquals("待协调", jdbc.queryForObject("SELECT coordination_state FROM appointment WHERE appointment_id=?",
            String.class, id));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM appointment WHERE appointment_id=?",
            Integer.class, id));
        assertEquals("已确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",
            String.class, id));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",
            Integer.class, id));
        var list = call("GET", "/staff/contacts?branch_id=1", null, frontdesk);
        assertEquals(200, list.statusCode(), list.body());
        assertTrue(list.body().contains("拨打电话未接通"), list.body());
        assertEquals(403, call("GET", "/staff/contacts?branch_id=2", null, frontdesk).statusCode());
    }

    @Test void manualContactCannotForgeReceiptOrWriteNotificationState() throws Exception {
        var frontdesk = session(2);
        assertEquals(422, call("POST", "/staff/contacts",
            "{\"kind\":\"到诊提醒\",\"channel\":\"系统通知\",\"appointment_id\":\"1\",\"result\":\"已送达\"}",
            frontdesk).statusCode());
        assertEquals(422, call("POST", "/staff/contacts",
            "{\"kind\":\"人工联系\",\"channel\":\"电话\",\"appointment_id\":\"1\",\"result\":\"已联系\",\"acknowledged_at\":\"2030-01-02T01:00:00Z\"}",
            frontdesk).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contact_record", Integer.class));
    }

    @Test void affectedAppointmentAppearsInDueQueueUntilFollowupOrAcknowledgment() throws Exception {
        var desk=session(2); var owner=session(3);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long id=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        String due="/staff/contacts/due?branch_id=1";
        assertEquals(200,call("GET",due,null,desk).statusCode());
        assertFalse(call("GET",due,null,desk).body().contains("\"appointment_id\":\""+id+"\""));
        assertEquals(201,call("POST","/staff/dentist-unavailability",
            "{\"doctor_id\":\"1\",\"starts_at\":\"2030-01-02T09:00:00+08:00\","
              + "\"ends_at\":\"2030-01-02T09:30:00+08:00\",\"reason\":\"合成停诊\"}",owner).statusCode());
        var first=call("GET",due,null,desk);
        assertEquals(200,first.statusCode(),first.body());
        assertTrue(first.body().contains("\"appointment_id\":\""+id+"\""),first.body());
        assertFalse(first.body().contains("合成患者甲"));
        assertFalse(first.body().contains("00000000001"));
        assertEquals(403,call("GET",due,null,owner).statusCode());
        assertEquals(403,call("GET","/staff/contacts/due?branch_id=2",null,desk).statusCode());
        String contact="{\"kind\":\"人工联系\",\"channel\":\"电话\",\"appointment_id\":\""+id
            +"\",\"result\":\"未接通\",\"next_contact_at\":\"2030-01-02T09:05:00+08:00\"}";
        assertEquals(201,call("POST","/staff/contacts",contact,desk).statusCode());
        assertFalse(call("GET",due,null,desk).body().contains("\"appointment_id\":\""+id+"\""));
        CLOCK.set(Instant.parse("2030-01-02T01:05:00Z"));
        assertTrue(call("GET",due,null,desk).body().contains("\"appointment_id\":\""+id+"\""));
        assertEquals(201,call("POST","/staff/contacts",
            "{\"kind\":\"人工联系\",\"channel\":\"电话\",\"appointment_id\":\""+id
              +"\",\"result\":\"患者已知晓\",\"acknowledgment_basis\":\"患者回电确认\"}",desk).statusCode());
        assertFalse(call("GET",due,null,desk).body().contains("\"appointment_id\":\""+id+"\""));
        jdbc.update("UPDATE dentist_unavailable SET state='已撤销',resolved_at=greatest(now(),recorded_at),resolved_by=3,resolve_basis='合成测试撤销'");
        assertFalse(call("GET",due,null,desk).body().contains("\"appointment_id\":\""+id+"\""));
    }

    private String changeProposalBody(String start) {
        return "{\"candidate\":{\"schedule_id\":1,\"item_id\":1,\"starts_at\":\""
            + start + "\",\"policy_id\":1},\"reason\":\"患者确认后调整时间\"}";
    }

    @Test void coordinationCannotSilentlyChangeOriginalService() throws Exception {
        var desk=session(2);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long original=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        String changedService=changeProposalBody("2030-01-02T09:30:00+08:00")
            .replace("\"item_id\":1","\"item_id\":2");
        assertEquals(422,call("POST","/staff/appointments/"+original+"/changes",changedService,desk).statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM appointment_change WHERE original_id=?",Integer.class,original));
    }

    @Test void acceptedCandidateOnlyOccupiesSlotAfterExplicitApply() throws Exception {
        var desk=session(2); var patient=patientSession(7);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long original=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        String path="/staff/appointments/"+original+"/changes";
        String proposal=changeProposalBody("2030-01-02T09:30:00+08:00");
        String proposalKey="change-"+UUID.randomUUID();
        var created=callKey("POST",path,proposal,desk,proposalKey);
        assertEquals(201,created.statusCode(),created.body());
        long change=jdbc.queryForObject("SELECT change_id FROM appointment_change WHERE original_id=?",Long.class,original);
        long candidate=jdbc.queryForObject("SELECT proposed_id FROM appointment_change WHERE change_id=?",Long.class,change);
        assertEquals(201,callKey("POST",path,proposal,desk,proposalKey).statusCode());
        assertEquals(409,call("POST",path,proposal,desk).statusCode());
        assertEquals("待确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,candidate));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=?",Integer.class,candidate));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,original));
        assertEquals(200,callPatient("GET","/me/changes",null,patient).statusCode());
        assertEquals(200,call("GET","/staff/changes?branch_id=1",null,desk).statusCode());
        assertEquals(404,callPatient("POST","/me/changes/"+change+"/response",
            "{\"accept\":true,\"expected_version\":1}",patientSession(4)).statusCode());
        var accepted=callPatient("POST","/me/changes/"+change+"/response",
            "{\"accept\":true,\"expected_version\":1}",patient);
        assertEquals(200,accepted.statusCode(),accepted.body());
        assertTrue(accepted.body().contains("\"applied\":false"));
        assertEquals("已确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,original));
        assertEquals("待确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,candidate));
        String apply="{\"expected_version\":2}";
        String applyPath="/staff/changes/"+change+"/apply";
        String applyKey="apply-"+UUID.randomUUID();
        var applied=callKey("POST",applyPath,apply,desk,applyKey);
        assertEquals(200,applied.statusCode(),applied.body());
        assertTrue(applied.body().contains("\"applied\":true"));
        assertEquals("已改约",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,original));
        assertEquals("已确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,candidate));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,candidate));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,original));
        assertEquals(200,callKey("POST",applyPath,apply,desk,applyKey).statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='appointment_change' AND operation='apply'",Integer.class));
    }

    @Test void applyConflictPreservesOriginalBookingAndRecordedConsent() throws Exception {
        var desk=session(2); var patient=patientSession(7);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long original=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        assertEquals(201,call("POST","/staff/appointments/"+original+"/changes",
            changeProposalBody("2030-01-02T09:30:00+08:00"),desk).statusCode());
        long change=jdbc.queryForObject("SELECT change_id FROM appointment_change WHERE original_id=?",Long.class,original);
        assertEquals(200,callPatient("POST","/me/changes/"+change+"/response",
            "{\"accept\":true,\"expected_version\":1}",patient).statusCode());
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(2,"2030-01-02T09:30:00+08:00"),desk).statusCode());
        assertEquals(409,call("POST","/staff/changes/"+change+"/apply",
            "{\"expected_version\":2}",desk).statusCode());
        assertEquals("已确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,original));
        assertEquals("已同意",jdbc.queryForObject("SELECT state FROM appointment_change WHERE change_id=?",String.class,change));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,original));
    }

    @Test void rejectedProposalCancelsOnlyCandidateAndNeedsActualAnswer() throws Exception {
        var desk=session(2);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long original=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        assertEquals(201,call("POST","/staff/appointments/"+original+"/changes",
            changeProposalBody("2030-01-02T09:30:00+08:00"),desk).statusCode());
        long change=jdbc.queryForObject("SELECT change_id FROM appointment_change WHERE original_id=?",Long.class,original);
        assertEquals(422,call("POST","/staff/changes/"+change+"/response",
            "{\"accept\":false,\"expected_version\":1,\"confirmation_basis\":\"\"}",desk).statusCode());
        assertEquals(200,call("POST","/staff/changes/"+change+"/response",
            "{\"accept\":false,\"expected_version\":1,\"confirmation_basis\":\"患者电话明确拒绝\"}",desk).statusCode());
        assertEquals("已拒绝",jdbc.queryForObject("SELECT state FROM appointment_change WHERE change_id=?",String.class,change));
        assertEquals("已确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,original));
        assertEquals("已取消",jdbc.queryForObject("SELECT a.state FROM appointment a JOIN appointment_change c ON c.proposed_id=a.appointment_id WHERE c.change_id=?",String.class,change));
    }

    @Test void withdrawalPreservesOriginalAndRecordedConsentAndCanBeReplayed() throws Exception {
        var desk=session(2); var patient=patientSession(7);
        assertEquals(201,call("POST","/staff/appointments",
            bookingBody(1,"2030-01-02T09:00:00+08:00"),desk).statusCode());
        long original=jdbc.queryForObject("SELECT appointment_id FROM appointment WHERE patient_id=1",Long.class);
        String proposalPath="/staff/appointments/"+original+"/changes";
        String proposal=changeProposalBody("2030-01-02T09:30:00+08:00");
        assertEquals(201,call("POST",proposalPath,proposal,desk).statusCode());
        long first=jdbc.queryForObject("SELECT change_id FROM appointment_change WHERE original_id=?",Long.class,original);
        String firstPath="/staff/changes/"+first+"/withdraw";
        String firstBody="{\"expected_version\":1,\"reason\":\"候选医生无法接诊\"}";
        assertEquals(403,call("POST",firstPath,firstBody,session(3)).statusCode());
        assertEquals(403,callPatient("POST",firstPath,firstBody,patient).statusCode());
        assertEquals(422,call("POST",firstPath,"{\"expected_version\":1,\"reason\":\"\"}",desk).statusCode());
        String key="withdraw-"+UUID.randomUUID();
        var withdrawn=callKey("POST",firstPath,firstBody,desk,key);
        assertEquals(200,withdrawn.statusCode(),withdrawn.body());
        assertTrue(withdrawn.body().contains("已撤回"));
        assertEquals(200,callKey("POST",firstPath,firstBody,desk,key).statusCode());
        assertEquals(409,call("POST",firstPath,firstBody,desk).statusCode());
        assertEquals(409,callPatient("POST","/me/changes/"+first+"/response",
            "{\"accept\":true,\"expected_version\":2}",patient).statusCode());
        assertEquals("已取消",jdbc.queryForObject("""
            SELECT p.state FROM appointment_change c JOIN appointment p ON p.appointment_id=c.proposed_id
            WHERE c.change_id=?
            """,String.class,first));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_type='appointment_change' AND operation='withdraw'",Integer.class));

        assertEquals(201,call("POST",proposalPath,proposal,desk).statusCode());
        long second=jdbc.queryForObject("SELECT max(change_id) FROM appointment_change WHERE original_id=?",Long.class,original);
        assertEquals(200,callPatient("POST","/me/changes/"+second+"/response",
            "{\"accept\":true,\"expected_version\":1}",patient).statusCode());
        String secondPath="/staff/changes/"+second+"/withdraw";
        assertEquals(200,call("POST",secondPath,
            "{\"expected_version\":2,\"reason\":\"候选设备故障，需重新联系患者\"}",desk).statusCode());
        assertEquals("患者端明确选择同意",jdbc.queryForObject(
            "SELECT response_basis FROM appointment_change WHERE change_id=?",String.class,second));
        assertEquals(409,call("POST","/staff/changes/"+second+"/apply",
            "{\"expected_version\":3}",desk).statusCode());
        assertEquals("已确认",jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=?",String.class,original));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM resource_booking WHERE appointment_id=? AND state='有效'",Integer.class,original));
        assertEquals(0,jdbc.queryForObject("""
            SELECT count(*) FROM resource_booking rb JOIN appointment_change c ON c.proposed_id=rb.appointment_id
            WHERE c.original_id=? AND rb.state='有效'
            """,Integer.class,original));
    }

    @AfterAll void stop() { if (app != null) app.close(); }
}
