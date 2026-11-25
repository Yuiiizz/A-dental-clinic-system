package cn.dental.organization;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
import cn.dental.support.ConcurrentRunner;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE",matches="dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BranchIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;
    private JdbcTemplate jdbc;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll void start() throws Exception {
        var env = System.getenv();
        PostgresTestSupport.validateTarget(env);
        String schema = "branch_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = PostgresTestSupport.connect(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        app = new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local", "--spring.main.banner-mode=off",
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + env.get("TEST_DB_URL") + "?currentSchema=" + schema + ",public",
            "--spring.datasource.username=" + env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--app.mock.wechat-enabled=false", "--app.seed.enabled=false");
        sessions = app.getBean(SessionService.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成负责人','其他人员','负责人','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(2,'合成另一负责人','其他人员','负责人','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(3,'合成管理员','其他人员','管理员','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(4,'合成前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'owner-a','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(2,2,'owner-b','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(3,3,'sysadmin','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(4,4,'frontdesk','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(5,1,true,false,now())");
        // Test-only fault injection: fail after branch/staff_branch/scope/audit writes, at session revocation.
        jdbc.execute("CREATE TABLE branch_fault_injection(fail boolean NOT NULL)");
        jdbc.update("INSERT INTO branch_fault_injection(fail) VALUES(false)");
        jdbc.execute("""
            CREATE FUNCTION branch_fault() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF (SELECT fail FROM branch_fault_injection LIMIT 1) THEN
                RAISE EXCEPTION 'injected branch create failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("CREATE TRIGGER branch_fault_auth_session BEFORE UPDATE ON auth_session FOR EACH ROW EXECUTE FUNCTION branch_fault()");
        jdbc.execute("""
            CREATE FUNCTION branch_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF (SELECT fail FROM branch_fault_injection LIMIT 1) THEN
                RAISE EXCEPTION 'injected branch audit failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("CREATE TRIGGER branch_fault_audit BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION branch_audit_fault()");
    }

    @AfterAll void stop() {
        if (app != null) app.close();
    }

    @BeforeEach void reset() {
        jdbc.update("UPDATE branch_fault_injection SET fail=false");
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM schedule");
        jdbc.update("DELETE FROM booking_policy");
        jdbc.update("DELETE FROM branch_opening_interval");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM role_branch_scope");
        jdbc.update("DELETE FROM staff_branch");
        jdbc.update("DELETE FROM branch");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE FROM account_role");
        jdbc.update("UPDATE account SET enabled=true,auth_version=1,must_change_password=false WHERE account_id IN (1,2,3,4)");
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        for (long branch = 10; branch <= 11; branch++) {
            jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(?,1,?,'合成门店','合成地址','00000000000',?,'09:00-18:00',1,'营业',1,now())",
                branch, "TEST-B" + branch, "TEST-L" + branch);
        }
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('branch','branch_id'),(SELECT max(branch_id) FROM branch))", Long.class);
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,10,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,11,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(2,11,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(4,10,true,now())");
        role(1, "owner"); scope(1, "owner", 10);
        role(2, "owner"); scope(2, "owner", 11);
        role(3, "sysadmin");
        role(4, "frontdesk");
    }

    private void role(long account, String code) {
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT ?,role_id,now(),'合成批准依据' FROM role WHERE code=?", account, code);
    }

    private void scope(long account, String role, long branch) {
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT ?,role_id,? FROM role WHERE code=?", account, branch, role);
    }

    private String cookie(SessionService.Issued s) {
        return "A_CLINIC_SESSION=" + s.token();
    }

    private HttpResponse<String> request(String method, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, SessionService.Issued s) throws Exception {
        return postWithKey(path, body, s, "branch-key-" + UUID.randomUUID());
    }

    private HttpResponse<String> postWithKey(String path, String body, SessionService.Issued s, String key) throws Exception {
        return json("POST", path, body, s, key);
    }

    private HttpResponse<String> json(String method, String path, String body, SessionService.Issued s, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie(s))
            .header("Idempotency-Key", key);
        if (s.csrfToken() != null) builder.header("X-CSRF-Token", s.csrfToken());
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private long draftBranch(long branchId, String code, String licenseValidTo) {
        jdbc.update("""
            INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,license_valid_to,
                opening_hours,manager_id,state,version)
            VALUES(?,1,?,'合成草稿店','地址','00000000000','TEST-LIC',?,'09:00-18:00',1,'草稿',1)
            """, branchId, code, licenseValidTo == null ? null : java.sql.Date.valueOf(licenseValidTo));
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,?,true,now())", branchId);
        scope(1, "owner", branchId);
        return branchId;
    }

    private void policy() {
        jdbc.update("""
            INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,
                restriction_days,status)
            VALUES(1,10,1,24,7,2,7,'draft')
            """);
    }

    private void schedule(String state, int startOffsetMinutes, int endOffsetMinutes) {
        jdbc.update("""
            INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,
                version,travel_buffer_minutes)
            VALUES(1,1,now() + (? * interval '1 minute'),now() + (? * interval '1 minute'),'初诊',?,10,1,0)
            """, startOffsetMinutes, endOffsetMinutes, state);
    }

    private void appointment(String state) {
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,starts_at,ends_at,visit_type,purpose,
                channel,state,coordination_state,version,request_key,request_hash,requested_by,branch_id,policy_id,
                cancelled_by_type,closure_reason,cancelled_at)
            VALUES(1,1,NULL,now()+interval '1 day',now()+interval '1 day 1 hour','初诊','合成预约',
                '患者端',?,'无需协调',1,?,repeat('a',64),1,10,1,
                CASE WHEN ?='已取消' THEN 'clinic' ELSE NULL END,
                CASE WHEN ?='已取消' THEN '合成取消' ELSE NULL END,
                CASE WHEN ?='已取消' THEN now() ELSE NULL END)
            """, state, UUID.randomUUID().toString(), state, state, state);
    }

    private void awaitAdvisoryWait() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted "
                    + "AND database=(SELECT oid FROM pg_database WHERE datname=current_database()) "
                    + "AND classid=0 AND objid=734192014 AND objsubid=1", Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("request did not reach the account maintenance lock");
    }

    private void awaitBranchTupleWait() throws Exception {
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
        fail("request did not reach the branch row lock");
    }

    private void awaitOpeningReadLock() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname=current_database() AND pid<>pg_backend_pid()
                  AND wait_event_type='Lock'
                  AND query ILIKE '%LEFT JOIN branch_opening_interval%'
                """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        fail("GET did not reach the opening interval snapshot query");
    }

    private Object openingServiceTarget() {
        var service = app.getBean(cn.dental.organization.application.OpeningIntervalService.class);
        Object target = org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(service);
        return target == null ? service : target;
    }

    private String createBody(String code, String manager) {
        return "{\"code\":\"" + code + "\",\"name\":\"合成新店\",\"address\":\"合成地址\",\"phone\":\"00000000000\",\"license_no\":\"TEST-LIC\",\"opening_hours\":\"09:00-18:00\",\"manager_id\":\"" + manager + "\"}";
    }

    private String openingBody(java.time.LocalDate from, java.time.LocalDate to, int version, String intervalsJson) {
        return "{\"from_date\":\"" + from + "\",\"to_date\":\"" + to + "\",\"expected_version\":" + version
            + ",\"intervals\":" + intervalsJson + "}";
    }

    private String openingInterval(java.time.LocalDate date, String start, String end) {
        return "{\"valid_on\":\"" + date + "\",\"starts_at\":\"" + date + "T" + start + ":00+08:00\","
            + "\"ends_at\":\"" + date + "T" + end + ":00+08:00\"}";
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class);
    }

    @Test void createsDraftAndLimitedStartupGrantThenRevokesSession() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        var response = post("/staff/branches", createBody("NEW-1", "1"), s);
        assertEquals(201, response.statusCode(), response.body());
        var branchId = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code='NEW-1'", Long.class);
        var row = jdbc.queryForMap("SELECT state,version,manager_id,license_no_expiry_confirmed,verified_by,verified_at FROM branch WHERE branch_id=?", branchId);
        assertEquals("草稿", row.get("state"));
        assertEquals(1, ((Number) row.get("version")).intValue());
        assertEquals(1L, ((Number) row.get("manager_id")).longValue());
        assertEquals(false, row.get("license_no_expiry_confirmed"));
        assertNull(row.get("verified_by"));
        assertNull(row.get("verified_at"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_branch WHERE staff_id=1 AND branch_id=? AND enabled AND verified_at IS NOT NULL", Integer.class, branchId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope rbs JOIN role r USING(role_id) WHERE rbs.account_id=1 AND rbs.branch_id=? AND r.code='owner'", Integer.class, branchId));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(1, auditCount());
        assertTrue(response.body().contains("\"reauthentication_required\":true"));
        assertEquals(401, request("GET", "/auth/me", "Cookie", cookie(s)).statusCode());

        var relogin = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, request("GET", "/auth/me", "Cookie", cookie(relogin)).statusCode());
    }

    @Test void rejectsNonResponsibleOwnerAndWrongRoles() throws Exception {
        var ownerB = sessions.issue(2, SessionService.STAFF);
        assertEquals(403, post("/staff/branches", createBody("NEW-2", "2"), ownerB).statusCode());
        var sysadmin = sessions.issue(3, SessionService.STAFF);
        assertEquals(403, post("/staff/branches", createBody("NEW-2", "3"), sysadmin).statusCode());
        var frontdesk = sessions.issue(4, SessionService.STAFF);
        assertEquals(403, post("/staff/branches", createBody("NEW-2", "4"), frontdesk).statusCode());
        var patient = sessions.issue(5, SessionService.PATIENT);
        int status = post("/staff/branches", createBody("NEW-2", "1"), patient).statusCode();
        assertTrue(Set.of(401, 403).contains(status));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='NEW-2'", Integer.class));
    }

    @Test void rejectsMustChangePasswordAndInvalidManagerUnknownFieldAndDuplicateCode() throws Exception {
        jdbc.update("UPDATE account SET must_change_password=true WHERE account_id=1");
        var mustChange = sessions.issue(1, SessionService.STAFF);
        assertEquals(403, post("/staff/branches", createBody("NEW-3", "1"), mustChange).statusCode());
        jdbc.update("UPDATE account SET must_change_password=false WHERE account_id=1");

        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(422, post("/staff/branches", createBody("NEW-3", "2"), s).statusCode());
        var unknown = "{\"code\":\"NEW-3\",\"name\":\"合成\",\"address\":\"地址\",\"phone\":\"00000000000\",\"license_no\":\"L\",\"opening_hours\":\"09:00-18:00\",\"manager_id\":\"1\",\"state\":\"草稿\"}";
        assertEquals(422, post("/staff/branches", unknown, s).statusCode());
        assertEquals(409, post("/staff/branches", createBody("TEST-B10", "1"), s).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='TEST-B10'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
    }

    @Test void organizationMustBeExactlyOneAndNotDisabled() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        jdbc.update("DELETE FROM staff_branch");
        jdbc.update("DELETE FROM role_branch_scope");
        jdbc.update("DELETE FROM branch");
        jdbc.update("DELETE FROM organization");
        assertEquals(422, post("/staff/branches", createBody("NEW-4", "1"), s).statusCode());

        reset();
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(2,'第二个主体','TEST-ORG-2','00000000000',1,'营业')");
        s = sessions.issue(1, SessionService.STAFF);
        assertEquals(409, post("/staff/branches", createBody("NEW-4", "1"), s).statusCode());

        reset();
        jdbc.update("UPDATE organization SET state='停用' WHERE organization_id=1");
        s = sessions.issue(1, SessionService.STAFF);
        assertEquals(403, post("/staff/branches", createBody("NEW-4", "1"), s).statusCode());
    }

    @Test void listDetailAreScopedBySameRoleBranchAndVerifiedAssignment() throws Exception {
        var ownerA = sessions.issue(1, SessionService.STAFF);
        var list = request("GET", "/staff/branches", "Cookie", cookie(ownerA));
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"10\""));
        assertFalse(list.body().contains("\"11\""));
        var detail = request("GET", "/staff/branches/10", "Cookie", cookie(ownerA));
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("\"license_no_expiry_confirmed\":false"));
        assertFalse(detail.body().contains("patient_id"));
        assertFalse(detail.body().contains("allergy"));
        assertEquals(404, request("GET", "/staff/branches/11", "Cookie", cookie(ownerA)).statusCode());
        assertEquals(404, request("GET", "/staff/branches/999", "Cookie", cookie(ownerA)).statusCode());

        var ownerB = sessions.issue(2, SessionService.STAFF);
        var listB = request("GET", "/staff/branches", "Cookie", cookie(ownerB));
        assertTrue(listB.body().contains("\"11\""));
        assertFalse(listB.body().contains("\"10\""));
        assertEquals(404, request("GET", "/staff/branches/10", "Cookie", cookie(ownerB)).statusCode());

        var sysadmin = sessions.issue(3, SessionService.STAFF);
        assertEquals(403, request("GET", "/staff/branches", "Cookie", cookie(sysadmin)).statusCode());
        var frontdesk = sessions.issue(4, SessionService.STAFF);
        assertEquals(403, request("GET", "/staff/branches", "Cookie", cookie(frontdesk)).statusCode());
        var patient = sessions.issue(5, SessionService.PATIENT);
        assertTrue(Set.of(401, 403).contains(request("GET", "/staff/branches", "Cookie", cookie(patient)).statusCode()));
    }

    @Test void listUsesBoundedCursorPagination() throws Exception {
        for (long branch = 12; branch <= 13; branch++) {
            jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,version) VALUES(?,1,?,'合成门店','地址','00000000000','L','09:00-18:00',1,'草稿',1)", branch, "TEST-B" + branch);
            jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,?,true,now())", branch);
            scope(1, "owner", branch);
        }
        var s = sessions.issue(1, SessionService.STAFF);
        var first = request("GET", "/staff/branches?limit=1", "Cookie", cookie(s));
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"next_cursor\":\"10\""));
        var second = request("GET", "/staff/branches?limit=2&cursor=10", "Cookie", cookie(s));
        assertEquals(200, second.statusCode(), second.body());
        assertTrue(second.body().contains("\"branch_id\":\"12\""));
        assertTrue(second.body().contains("\"branch_id\":\"13\""));
        assertFalse(second.body().contains("\"branch_id\":\"11\""));
        assertEquals(422, request("GET", "/staff/branches?cursor=bad", "Cookie", cookie(s)).statusCode());
    }

    @Test void unauthorizedRequestDoesNotWaitForBranchRowLock() throws Exception {
        var ds = app.getBean(javax.sql.DataSource.class);
        try (var hold = ds.getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("SELECT branch_id FROM branch WHERE branch_id=10 FOR UPDATE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var s = sessions.issue(2, SessionService.STAFF); // 有员工身份但只拥有 11 店
                Future<HttpResponse<String>> pending = pool.submit(() -> json("PATCH", "/staff/branches/10",
                    "{\"expected_version\":1,\"name\":\"无权\"}", s, "unauthorized-lock"));
                var response = pending.get(2, TimeUnit.SECONDS);
                assertEquals(404, response.statusCode(), response.body());
            } finally {
                hold.rollback();
                pool.shutdownNow();
            }
        }
    }

    @Test void authorizedRequestWaitsThenRevokedScopeCannotChange() throws Exception {
        var ds = app.getBean(javax.sql.DataSource.class);
        try (var hold = ds.getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("SELECT branch_id FROM branch WHERE branch_id=10 FOR UPDATE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var s = sessions.issue(1, SessionService.STAFF);
                Future<HttpResponse<String>> pending = pool.submit(() -> json("PATCH", "/staff/branches/10",
                    "{\"expected_version\":1,\"name\":\"等锁撤权\"}", s, "authorized-lock"));
                awaitBranchTupleWait();
                try (var st = hold.createStatement()) {
                    st.execute("DELETE FROM role_branch_scope WHERE account_id=1 AND branch_id=10");
                }
                hold.commit();
                var response = pending.get(10, TimeUnit.SECONDS);
                assertEquals(404, response.statusCode(), response.body());
                assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
                assertEquals("合成门店", jdbc.queryForObject("SELECT name FROM branch WHERE branch_id=10", String.class));
                assertEquals(0, auditCount());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void patchAuditRecordsAddressCodeAndClearedVerificationFacts() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"合成停用\"}", s, "audit-disable").statusCode());
        String address = "地".repeat(300);
        var response = json("PATCH", "/staff/branches/10",
            "{\"expected_version\":2,\"code\":\"AUDIT-CODE\",\"address\":\"" + address + "\",\"license_no\":\"NEW-LIC\",\"license_valid_to\":null}",
            s, "audit-patch");
        assertEquals(200, response.statusCode(), response.body());
        var audit = jdbc.queryForMap("SELECT before_data,after_data FROM audit_log WHERE operation='修改门店档案' ORDER BY audit_id DESC LIMIT 1");
        String after = (String) audit.get("after_data");
        assertTrue(after.contains("\"address\":\"" + address + "\""), "address max length must be recorded");
        assertTrue(after.contains("\"code\":\"AUDIT-CODE\""));
        assertTrue(after.contains("\"license_no\":\"NEW-LIC\""));
        assertTrue(after.contains("\"license_valid_to\":\"\""));
        assertTrue(after.contains("\"license_no_expiry_confirmed\":\"false\""));
        assertTrue(after.contains("\"verified_by\":\"\""));
        assertTrue(after.contains("\"verified_at\":\"\""));
        assertTrue(after.length() < 2000);
    }

    @Test void verifyAuditKeepsLicenseVersionAndClockFacts() throws Exception {
        draftBranch(18, "VERIFY-AUDIT", "2030-12-31");
        var s = sessions.issue(1, SessionService.STAFF);
        postWithKey("/staff/branches/18/submit", "{\"expected_version\":1}", s, "verify-audit-submit");
        assertEquals(200, postWithKey("/staff/branches/18/verify",
            "{\"expected_version\":2,\"verification_basis\":\"现场核对\",\"license_no_expiry_confirmed\":false}", s, "verify-audit").statusCode());
        var audit = jdbc.queryForMap("SELECT after_data FROM audit_log WHERE operation='核验门店' ORDER BY audit_id DESC LIMIT 1");
        String after = (String) audit.get("after_data");
        assertTrue(after.contains("\"license_no\":\"TEST-LIC\""));
        assertTrue(after.contains("\"license_valid_to\":\"2030-12-31\""));
        assertTrue(after.contains("\"license_no_expiry_confirmed\":\"false\""));
        assertTrue(after.contains("\"verified_by\":\"1\""));
        assertTrue(after.contains("\"verified_at\":\""));
    }

    @Test void stateActionReplayReturnsOriginalResultAndDoesNotRewind() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"重放停用\"}", s, "replay-disable").statusCode());
        assertEquals(200, postWithKey("/staff/branches/10/reopen", "{\"expected_version\":2}", s, "replay-reopen").statusCode());
        var reopenReplay = postWithKey("/staff/branches/10/reopen", "{\"expected_version\":2}", s, "replay-reopen");
        assertEquals(200, reopenReplay.statusCode(), reopenReplay.body());
        assertTrue(reopenReplay.body().contains("\"state\":\"待核验\""));
        assertTrue(reopenReplay.body().contains("\"version\":3"));
        var replay = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"重放停用\"}", s, "replay-disable");
        assertEquals(200, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("\"state\":\"停用\""));
        assertTrue(replay.body().contains("\"version\":2"));
        var current = request("GET", "/staff/branches/10", "Cookie", cookie(s));
        assertTrue(current.body().contains("\"state\":\"待核验\""));
        assertTrue(current.body().contains("\"version\":3"));

        draftBranch(19, "REPLAY-SUBMIT", "2030-12-31");
        assertEquals(200, postWithKey("/staff/branches/19/submit", "{\"expected_version\":1}", s, "replay-submit").statusCode());
        assertEquals(200, postWithKey("/staff/branches/19/verify",
            "{\"expected_version\":2,\"verification_basis\":\"现场核对\",\"license_no_expiry_confirmed\":false}", s, "replay-verify").statusCode());
        var submitReplay = postWithKey("/staff/branches/19/submit", "{\"expected_version\":1}", s, "replay-submit");
        assertEquals(200, submitReplay.statusCode(), submitReplay.body());
        assertTrue(submitReplay.body().contains("\"state\":\"待核验\""));
        assertTrue(submitReplay.body().contains("\"version\":2"));
        var current19 = request("GET", "/staff/branches/19", "Cookie", cookie(s));
        assertTrue(current19.body().contains("\"state\":\"营业\""));
        assertTrue(current19.body().contains("\"version\":3"));
        var verifyReplay = postWithKey("/staff/branches/19/verify",
            "{\"expected_version\":2,\"verification_basis\":\"现场核对\",\"license_no_expiry_confirmed\":false}", s, "replay-verify");
        assertEquals(200, verifyReplay.statusCode(), verifyReplay.body());
        assertTrue(verifyReplay.body().contains("\"state\":\"营业\""));
        assertTrue(verifyReplay.body().contains("\"version\":3"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=19 AND operation='提交门店核验'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=19 AND operation='核验门店'", Integer.class));
    }

    @Test void verifyRejectsForgedFieldsMissingBasisAndInactiveAssignment() throws Exception {
        draftBranch(20, "VERIFY-REJECT", "2030-12-31");
        var s = sessions.issue(1, SessionService.STAFF);
        postWithKey("/staff/branches/20/submit", "{\"expected_version\":1}", s, "reject-submit");
        assertEquals(422, postWithKey("/staff/branches/20/verify",
            "{\"expected_version\":2,\"verification_basis\":\"依据\",\"verified_by\":\"1\"}", s, "forged").statusCode());
        assertEquals(422, postWithKey("/staff/branches/20/verify",
            "{\"expected_version\":2,\"verification_basis\":\"依据\",\"verified_at\":\"2026-10-25T00:00:00Z\"}", s, "forged-at").statusCode());
        assertEquals(422, postWithKey("/staff/branches/20/verify",
            "{\"expected_version\":2}", s, "no-basis").statusCode());
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=1 AND branch_id=20");
        assertEquals(404, postWithKey("/staff/branches/20/verify",
            "{\"expected_version\":2,\"verification_basis\":\"依据\",\"license_no_expiry_confirmed\":false}", s, "inactive").statusCode());
        assertEquals("待核验", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=20", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=20", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=20", Integer.class));
    }

    @Test void verificationUsesShanghaiDateBoundary() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        draftBranch(21, "BOUNDARY-TODAY", today.toString());
        draftBranch(22, "BOUNDARY-YESTERDAY", today.minusDays(1).toString());
        var s = sessions.issue(1, SessionService.STAFF);
        postWithKey("/staff/branches/21/submit", "{\"expected_version\":1}", s, "boundary-21-submit");
        assertEquals(200, postWithKey("/staff/branches/21/verify",
            "{\"expected_version\":2,\"verification_basis\":\"当天有效\",\"license_no_expiry_confirmed\":false}", s, "boundary-21").statusCode());
        postWithKey("/staff/branches/22/submit", "{\"expected_version\":1}", s, "boundary-22-submit");
        assertEquals(422, postWithKey("/staff/branches/22/verify",
            "{\"expected_version\":2,\"verification_basis\":\"前一天过期\",\"license_no_expiry_confirmed\":false}", s, "boundary-22").statusCode());
    }


    @Test void openingValidationUsesOneClockInstant() throws Exception {
        Object target = openingServiceTarget();
        var field = target.getClass().getDeclaredField("clock");
        field.setAccessible(true);
        Object original = field.get(target);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var advancing = new java.time.Clock() {
            public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            public java.time.Instant instant() {
                return java.time.Instant.parse(calls.getAndIncrement() == 0
                    ? "2026-10-25T15:59:59Z" : "2026-10-25T16:00:01Z");
            }
        };
        field.set(target, advancing);
        try {
            var s = sessions.issue(1, SessionService.STAFF);
            var day = java.time.LocalDate.of(2026,10,25);
            var response = json("PUT", "/staff/branches/10/opening-intervals",
                openingBody(day, day, 1, "[]"), s, "one-clock");
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, calls.get());
            assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        } finally { field.set(target, original); }
    }

    @Test void openingPrecisionRejectsFractionsAndPreservesStoredValues() throws Exception {
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        String interval = openingInterval(day, "09:00", "10:00");
        for (String fraction : new String[]{".500", ".000001", ".000000001"}) {
            String bad = interval.replace("09:00:00+", "09:00:00" + fraction + "+");
            assertNotEquals(interval, bad);
            var response = json("PUT", "/staff/branches/10/opening-intervals",
                openingBody(day, day, 1, "[" + bad + "]"), s, "fraction-" + fraction);
            assertEquals(422, response.statusCode(), response.body());
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        assertEquals(0, auditCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
        String zero = interval.replace("09:00:00+", "09:00:00.000+");
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(day,day,1,"["+zero+"]"),s,"zero-fraction").statusCode());
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(day,day,1,"["+interval+"]"),s,"zero-fraction").statusCode());
        var read = request("GET", "/staff/branches/10/opening-intervals?from_date="+day+"&to_date="+day,"Cookie",cookie(s));
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains(day+"T09:00:00+08:00"));
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(day,day,2,"["+interval+"]"),s,"same-value").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        jdbc.update("UPDATE branch_opening_interval SET starts_at=starts_at+interval '0.5 second' WHERE branch_id=10");
        var historical = request("GET", "/staff/branches/10/opening-intervals?from_date="+day+"&to_date="+day,"Cookie",cookie(s));
        assertEquals(200,historical.statusCode());
        assertTrue(historical.body().contains(day+"T09:00:00.5+08:00"), historical.body());
    }

    @Test void openingAdjacentIntervalsAndOutsideRangeStayStable() throws Exception {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var tomorrow = today.plusDays(1);
        var after = tomorrow.plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "09:00", "10:00") + ","
                + openingInterval(tomorrow, "10:00", "11:00") + "]"), s, "adjacent-open").statusCode());
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(after, after, 2, "[" + openingInterval(after, "13:00", "14:00") + "]"), s, "outside-open").statusCode());
        var get = request("GET", "/staff/branches/10/opening-intervals?from_date=" + tomorrow + "&to_date=" + after,
            "Cookie", cookie(s));
        assertEquals(200, get.statusCode(), get.body());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        int first = get.body().indexOf("\"valid_on\":\"" + tomorrow + "\"");
        int second = get.body().indexOf("\"valid_on\":\"" + tomorrow + "\"", first + 1);
        int later = get.body().indexOf("\"valid_on\":\"" + after + "\"");
        assertTrue(first >= 0 && second > first && later > second, get.body());
    }

    @Test void openingCrossMidnightWaitRejectsNewWrite() throws Exception {
        Object target = openingServiceTarget();
        var clockField = target.getClass().getDeclaredField("clock");
        clockField.setAccessible(true);
        var original = (java.time.Clock) clockField.get(target);
        var clock = new MutableClock("2026-10-25T15:59:50Z");
        clockField.set(target, clock);
        try {
            var ds = app.getBean(javax.sql.DataSource.class);
            try (var hold = ds.getConnection()) {
                hold.setAutoCommit(false);
                try (var st = hold.createStatement()) {
                    st.execute("SELECT branch_id FROM branch WHERE branch_id=10 FOR UPDATE");
                }
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    var s = sessions.issue(1, SessionService.STAFF);
                    java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 25);
                    java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 26);
                    String body = openingBody(from, to, 1,
                        "[" + openingInterval(to, "09:00", "10:00") + "]");
                    Future<HttpResponse<String>> pending = pool.submit(() -> json("PUT",
                        "/staff/branches/10/opening-intervals", body, s, "cross-midnight-wait"));
                    awaitBranchTupleWait();
                    clock.set("2026-10-25T16:00:01Z"); // 次日 00:00:01 Asia/Shanghai
                    hold.commit();
                    var response = pending.get(10, TimeUnit.SECONDS);
                    assertEquals(409, response.statusCode(), response.body());
                    assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
                    assertEquals(0, auditCount());
                } finally {
                    pool.shutdownNow();
                }
            }
        } finally {
            clockField.set(target, original);
        }
    }

    @Test void openingSuccessfulRequestReplaysNextDayWithoutReapplying() throws Exception {
        Object target = openingServiceTarget();
        var clockField = target.getClass().getDeclaredField("clock");
        clockField.setAccessible(true);
        var original = (java.time.Clock) clockField.get(target);
        var clock = new MutableClock("2026-10-25T02:00:00Z");
        clockField.set(target, clock);
        try {
            var s = sessions.issue(1, SessionService.STAFF);
            java.time.LocalDate from = java.time.LocalDate.of(2026, 10, 25);
            java.time.LocalDate to = java.time.LocalDate.of(2026, 10, 26);
            String body = openingBody(from, to, 1, "[" + openingInterval(to, "09:00", "10:00") + "]");
            assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals", body, s, "next-day-key").statusCode());
            assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
            clock.set("2026-10-25T16:00:01Z");
            var replay = json("PUT", "/staff/branches/10/opening-intervals", body, s, "next-day-key");
            assertEquals(200, replay.statusCode(), replay.body());
            assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
            assertEquals(1, auditCount());
            assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals", body, s, "next-day-new").statusCode());
        } finally {
            clockField.set(target, original);
        }
    }

    static final class MutableClock extends java.time.Clock {
        private volatile java.time.Instant instant;
        private final java.time.ZoneId zone;
        MutableClock(String instant) {
            this(java.time.Instant.parse(instant), java.time.ZoneId.of("Asia/Shanghai"));
        }
        MutableClock(java.time.Instant instant, java.time.ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }
        void set(String value) {
            instant = java.time.Instant.parse(value);
        }
        @Override public java.time.ZoneId getZone() { return zone; }
        @Override public java.time.Clock withZone(java.time.ZoneId newZone) { return new MutableClock(instant, newZone); }
        @Override public java.time.Instant instant() { return instant; }
    }

    @Test void openingIntervalRangeValidationAndTodayProtection() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var tomorrow = today.plusDays(1);

        var empty = request("GET", "/staff/branches/10/opening-intervals?from_date=" + today + "&to_date=" + today,
            "Cookie", cookie(s));
        assertEquals(200, empty.statusCode(), empty.body());
        assertTrue(empty.body().contains("\"version\":1"));
        assertTrue(empty.body().contains("\"intervals\":[]"));

        // Seed an already-started fact directly, as if it existed before this request.
        jdbc.update("""
            INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
            VALUES(10,?,?::timestamptz,?::timestamptz,1)
            """, today, today + "T00:00:00+08:00", today + "T01:00:00+08:00");

        String startedAndFuture = "[" + openingInterval(today, "00:00", "01:00") + ","
            + openingInterval(tomorrow, "09:00", "12:00") + "]";
        var created = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, tomorrow, 1, startedAndFuture), s, "open-today");
        assertEquals(200, created.statusCode(), created.body());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());

        var same = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, tomorrow, 2, startedAndFuture), s, "open-today-same");
        assertEquals(200, same.statusCode(), same.body());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());

        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, tomorrow, 2, "[" + openingInterval(today, "00:00", "00:59") + ","
                + openingInterval(tomorrow, "09:00", "12:00") + "]"), s, "open-today-change").statusCode());
        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, tomorrow, 2, "[]"), s, "open-today-empty").statusCode());
        // A fixed 02:00–03:00 interval is not past when this suite runs after midnight.
        // Cross-midnight and history rejection are covered by the injected-clock tests above.
        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today.minusDays(1), today, 2, "[" + openingInterval(today, "00:00", "01:00") + "]"), s, "open-history").statusCode());

        var far = today.plusDays(31);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, far, 2, "[" + openingInterval(tomorrow, "09:30", "12:00") + "]"), s, "open-31").statusCode());
        assertEquals(422, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, today.plusDays(32), 3, "[]"), s, "open-32").statusCode());
        assertEquals(422, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + openingInterval(today.plusDays(2), "09:00", "10:00") + "]"), s, "open-outside").statusCode());
        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + openingInterval(tomorrow, "09:00", "12:00") + ","
                + openingInterval(tomorrow, "11:00", "13:00") + "]"), s, "open-overlap").statusCode());
        assertEquals(422, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[{\"valid_on\":\"" + tomorrow + "\",\"starts_at\":\"" + tomorrow
                + "T22:00:00+08:00\",\"ends_at\":\"" + tomorrow + "T23:59:00+08:00\",\"unknown\":true}]"), s, "open-unknown").statusCode());
        var crossMidnight = "{\"valid_on\":\"" + tomorrow + "\",\"starts_at\":\"" + tomorrow
            + "T22:00:00+08:00\",\"ends_at\":\"" + tomorrow.plusDays(1) + "T00:00:00+08:00\"}";
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + crossMidnight + "]"), s, "open-midnight").statusCode());
        var badMidnight = "{\"valid_on\":\"" + tomorrow + "\",\"starts_at\":\"" + tomorrow
            + "T22:00:00+08:00\",\"ends_at\":\"" + tomorrow.plusDays(1) + "T01:00:00+08:00\"}";
        assertEquals(422, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 4, "[" + badMidnight + "]"), s, "open-bad-midnight").statusCode());

        var bounded = request("GET", "/staff/branches/10/opening-intervals?from_date=" + tomorrow + "&to_date=" + far,
            "Cookie", cookie(s));
        assertEquals(200, bounded.statusCode(), bounded.body());
        assertEquals(422, request("GET", "/staff/branches/10/opening-intervals?from_date=" + today + "&to_date=" + today.plusDays(32),
            "Cookie", cookie(s)).statusCode());
    }

    @Test void openingCoverageUsesAfterStateAndRejectsGaps() throws Exception {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var tomorrow = today.plusDays(1);
        policy();
        jdbc.update("""
            INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,version,travel_buffer_minutes)
            VALUES(1,1,?::timestamptz,?::timestamptz,'初诊','已发布',10,1,0)
            """, tomorrow + "T09:00:00+08:00", tomorrow + "T10:00:00+08:00");
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,starts_at,ends_at,visit_type,purpose,channel,
                state,coordination_state,confirmed_at,version,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(1,1,1,1,?::timestamptz,?::timestamptz,'初诊','待协调合成','患者端','已确认','待协调',now(),1,?,repeat('c',64),1,10,1)
            """, tomorrow + "T10:00:00+08:00", tomorrow + "T11:00:00+08:00", UUID.randomUUID().toString());

        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "00:00", "23:59") + "]"), s, "cover-initial").statusCode());
        var narrowed = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "12:00", "13:00") + "]"), s, "cover-narrow");
        assertEquals(409, narrowed.statusCode(), narrowed.body());
        assertTrue(narrowed.body().contains("\"schedule_count\":1"));
        assertTrue(narrowed.body().contains("\"appointment_count\":1"));
        assertFalse(narrowed.body().contains("patient"));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));

        var scheduleOnly = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "09:00", "10:00") + "]"), s, "cover-schedule");
        assertEquals(409, scheduleOnly.statusCode(), scheduleOnly.body());
        assertTrue(scheduleOnly.body().contains("\"appointment_count\":1"));
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "09:00", "11:00") + "]"), s, "cover-both").statusCode());
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));

        var same = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + openingInterval(tomorrow, "09:00", "11:00") + "]"), s, "cover-same");
        assertEquals(200, same.statusCode(), same.body());
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));

        jdbc.update("UPDATE schedule SET starts_at=?::timestamptz,ends_at=?::timestamptz WHERE schedule_id=1",
            tomorrow + "T11:00:00+08:00", tomorrow + "T15:00:00+08:00");
        var lunchGap = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + openingInterval(tomorrow, "09:00", "12:00") + ","
                + openingInterval(tomorrow, "13:00", "16:00") + "]"), s, "cover-lunch-gap");
        assertEquals(409, lunchGap.statusCode(), lunchGap.body());
        assertTrue(lunchGap.body().contains("\"schedule_count\":1"));

        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 3, "[" + openingInterval(tomorrow, "08:00", "18:00") + "]"), s, "cover-expand").statusCode());
    }

    @Test void openingPendingAppointmentDoesNotBlockRangeChanges() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        policy();
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,starts_at,ends_at,visit_type,purpose,channel,
                state,coordination_state,version,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(1,1,NULL,?::timestamptz,?::timestamptz,'初诊','仅待确认','患者端','待确认','无需协调',1,?,repeat('d',64),1,10,1)
            """, tomorrow + "T10:00:00+08:00", tomorrow + "T11:00:00+08:00", UUID.randomUUID().toString());
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "00:00", "23:59") + "]"), s, "pending-full").statusCode());
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "12:00", "13:00") + "]"), s, "pending-narrow").statusCode());
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals("待确认", jdbc.queryForObject("SELECT state FROM appointment WHERE appointment_id=1", String.class));
    }

    @Test void openingConfirmedCoordinatedAppointmentAloneBlocksRangeChanges() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        policy();
        jdbc.update("""
            INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,version,travel_buffer_minutes)
            VALUES(1,1,?::timestamptz,?::timestamptz,'初诊','草稿',10,1,0)
            """, tomorrow + "T10:00:00+08:00", tomorrow + "T11:00:00+08:00");
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,starts_at,ends_at,visit_type,purpose,channel,
                state,coordination_state,confirmed_at,version,request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(1,1,1,1,?::timestamptz,?::timestamptz,'初诊','已确认待协调','患者端','已确认','待协调',now(),1,?,repeat('e',64),1,10,1)
            """, tomorrow + "T10:00:00+08:00", tomorrow + "T11:00:00+08:00", UUID.randomUUID().toString());
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "00:00", "23:59") + "]"), s, "confirmed-full").statusCode());
        var blocked = json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "12:00", "13:00") + "]"), s, "confirmed-narrow");
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertTrue(blocked.body().contains("\"appointment_count\":1"));
        assertTrue(blocked.body().contains("\"schedule_count\":0"));
    }

    @Test void openingReplayReturnsCurrentAuthoritativeView() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        String aBody = openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "09:00", "10:00") + "]");
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals", aBody, s, "replay-open-a").statusCode());
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, "10:00", "11:00") + "]"), s, "replay-open-b").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        var replay = json("PUT", "/staff/branches/10/opening-intervals", aBody, s, "replay-open-a");
        assertEquals(200, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("\"version\":3"));
        assertTrue(replay.body().contains("\"starts_at\":\"" + tomorrow + "T10:00:00+08:00\""));
        if (replay.body().contains("\"starts_at\":\"" + tomorrow + "T09:00:00+08:00\"")) {
            fail("old interval remained: " + replay.body());
        }
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(2, auditCount());
    }

    @Test void openingGetUsesOneSnapshotWhenWriterCommits() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "09:00", "10:00") + "]"), s, "snapshot-initial").statusCode());
        var ds = app.getBean(javax.sql.DataSource.class);
        try (var hold = ds.getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("UPDATE branch SET version=version+1 WHERE branch_id=10");
                st.execute("UPDATE branch_opening_interval SET starts_at='" + tomorrow
                    + "T10:00:00+08:00'::timestamptz,ends_at='" + tomorrow
                    + "T11:00:00+08:00'::timestamptz WHERE branch_id=10");
                st.execute("LOCK TABLE branch_opening_interval IN ACCESS EXCLUSIVE MODE");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() -> request("GET",
                    "/staff/branches/10/opening-intervals?from_date=" + tomorrow + "&to_date=" + tomorrow,
                    "Cookie", cookie(s)));
                awaitOpeningReadLock();
                hold.commit();
                var response = pending.get(10, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("\"version\":3"));
                assertTrue(response.body().contains("\"starts_at\":\"" + tomorrow + "T10:00:00+08:00\""));
                if (response.body().contains("\"starts_at\":\"" + tomorrow + "T09:00:00+08:00\"")) {
                    fail("old interval remained in GET: " + response.body());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test void openingVersionIdempotencyConcurrencyAndRevocation() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        String body = openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "09:00", "10:00") + "]");
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals", body, s, "opening-idem").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals", body, s, "opening-idem").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "10:00", "11:00") + "]"), s, "opening-idem-diff").statusCode());
        assertEquals(409, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "11:00", "12:00") + "]"), s, "opening-stale").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));

        var s1 = sessions.issue(1, SessionService.STAFF);
        var s2 = sessions.issue(1, SessionService.STAFF);
        var index = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            int i = index.getAndIncrement();
            var session = i == 0 ? s1 : s2;
            String value = openingBody(tomorrow, tomorrow, 2, "[" + openingInterval(tomorrow, i == 0 ? "09:30" : "10:30", i == 0 ? "10:30" : "11:30") + "]");
            return json("PUT", "/staff/branches/10/opening-intervals", value, session, "opening-concurrent-" + i).statusCode();
        });
        assertEquals(1, results.stream().filter(code -> code == 200).count(), results.toString());
        assertEquals(1, results.stream().filter(code -> code == 409).count(), results.toString());
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        assertEquals(2, auditCount());

        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=1 AND branch_id=10");
        var afterRevoke = sessions.issue(1, SessionService.STAFF);
        assertEquals(404, json("PUT", "/staff/branches/10/opening-intervals", body, afterRevoke, "opening-idem").statusCode());
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(404, request("GET", "/staff/branches/10/opening-intervals?from_date=" + tomorrow + "&to_date=" + tomorrow,
            "Cookie", cookie(afterRevoke)).statusCode());
    }

    @Test void openingFaultRollsBackReplacementAndRetriesSameKey() throws Exception {
        var tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1);
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(tomorrow, tomorrow, 1, "[" + openingInterval(tomorrow, "09:00", "10:00") + "]"), s, "fault-open-initial").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        jdbc.update("UPDATE branch_fault_injection SET fail=true");
        String body = openingBody(tomorrow, tomorrow, 2, "[]");
        assertEquals(500, json("PUT", "/staff/branches/10/opening-intervals", body, s, "fault-open").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
        jdbc.update("UPDATE branch_fault_injection SET fail=false");
        assertEquals(200, json("PUT", "/staff/branches/10/opening-intervals", body, s, "fault-open").statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM branch_opening_interval WHERE branch_id=10", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(2, auditCount());
    }

    @Test void openingPermissionsRebuffCrossStoreRolesAndMustChange() throws Exception {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        var ownerB = sessions.issue(2, SessionService.STAFF);
        assertEquals(404, request("GET", "/staff/branches/10/opening-intervals?from_date=" + today + "&to_date=" + today,
            "Cookie", cookie(ownerB)).statusCode());
        assertEquals(404, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, today, 1, "[]"), ownerB, "open-cross").statusCode());
        var frontdesk = sessions.issue(4, SessionService.STAFF);
        assertEquals(404, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, today, 1, "[]"), frontdesk, "open-frontdesk").statusCode());
        var patient = sessions.issue(5, SessionService.PATIENT);
        assertTrue(Set.of(401, 403).contains(json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, today, 1, "[]"), patient, "open-patient").statusCode()));
        jdbc.update("UPDATE account SET must_change_password=true WHERE account_id=1");
        var mustChange = sessions.issue(1, SessionService.STAFF);
        assertEquals(403, json("PUT", "/staff/branches/10/opening-intervals",
            openingBody(today, today, 1, "[]"), mustChange, "open-must-change").statusCode());
    }

    @Test void patchRulesRejectNullUnknownAndActiveVerificationFacts() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);

        var renamed = json("PATCH", "/staff/branches/10",
            "{\"expected_version\":1,\"name\":\"合成新名称\",\"phone\":\"0731-1\"}", s, "patch-1");
        assertEquals(200, renamed.statusCode(), renamed.body());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals("合成新名称", jdbc.queryForObject("SELECT name FROM branch WHERE branch_id=10", String.class));
        assertNotNull(jdbc.queryForObject("SELECT verified_at FROM branch WHERE branch_id=10", java.sql.Timestamp.class));

        assertEquals(422, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":2,\"address\":\"另一地址\"}", s, "patch-2").statusCode());
        assertEquals(422, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":2,\"license_no_expiry_confirmed\":true}", s, "patch-3").statusCode());
        assertEquals(422, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":2,\"name\":null}", s, "patch-4").statusCode());
        assertEquals(422, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":2,\"unknown\":\"x\"}", s, "patch-5").statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
    }

    @Test void lifecycleTransitionsAndVerificationRules() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        draftBranch(12, "DRAFT-1", "2030-12-31");

        assertEquals(409, postWithKey("/staff/branches/12/verify",
            "{\"expected_version\":1,\"verification_basis\":\"现场核对\",\"license_no_expiry_confirmed\":false}", s, "v-draft").statusCode());
        assertEquals(409, postWithKey("/staff/branches/12/reopen", "{\"expected_version\":1}", s, "reopen-draft").statusCode());
        assertEquals(200, postWithKey("/staff/branches/12/submit", "{\"expected_version\":1}", s, "submit-1").statusCode());
        assertEquals(409, postWithKey("/staff/branches/12/submit", "{\"expected_version\":2}", s, "submit-again").statusCode());
        assertEquals(409, postWithKey("/staff/branches/12/disable",
            "{\"expected_version\":2,\"reason\":\"待核验不能停用\"}", s, "disable-pending").statusCode());
        assertEquals(200, postWithKey("/staff/branches/12/verify",
            "{\"expected_version\":2,\"verification_basis\":\"现场核对许可证\",\"license_no_expiry_confirmed\":false}", s, "verify-1").statusCode());
        assertEquals("营业", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=12", String.class));
        assertEquals(1L, ((Number) jdbc.queryForObject("SELECT verified_by FROM branch WHERE branch_id=12", Object.class)).longValue());
        assertEquals(409, postWithKey("/staff/branches/12/reopen", "{\"expected_version\":3}", s, "reopen-active").statusCode());

        assertEquals(200, postWithKey("/staff/branches/12/disable",
            "{\"expected_version\":3,\"reason\":\"合成停用\"}", s, "disable-1").statusCode());
        assertEquals(200, postWithKey("/staff/branches/12/reopen", "{\"expected_version\":4}", s, "reopen-1").statusCode());
        assertEquals("待核验", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=12", String.class));
        assertEquals(200, postWithKey("/staff/branches/12/verify",
            "{\"expected_version\":5,\"verification_basis\":\"重新核验\",\"license_no_expiry_confirmed\":false}", s, "verify-2").statusCode());

        draftBranch(13, "DRAFT-EXPIRED", "2020-01-01");
        postWithKey("/staff/branches/13/submit", "{\"expected_version\":1}", s, "submit-13");
        assertEquals(422, postWithKey("/staff/branches/13/verify",
            "{\"expected_version\":2,\"verification_basis\":\"过期证照\",\"license_no_expiry_confirmed\":false}", s, "verify-13").statusCode());

        draftBranch(14, "DRAFT-NOEXPIRY", null);
        postWithKey("/staff/branches/14/submit", "{\"expected_version\":1}", s, "submit-14");
        assertEquals(422, postWithKey("/staff/branches/14/verify",
            "{\"expected_version\":2,\"verification_basis\":\"无期限\",\"license_no_expiry_confirmed\":false}", s, "verify-14a").statusCode());
        assertEquals(200, postWithKey("/staff/branches/14/verify",
            "{\"expected_version\":2,\"verification_basis\":\"现场确认无期限\",\"license_no_expiry_confirmed\":true}", s, "verify-14b").statusCode());
        assertEquals(true, jdbc.queryForObject("SELECT license_no_expiry_confirmed FROM branch WHERE branch_id=14", Boolean.class));
    }

    @Test void patchRejectsCrossStoreRolesPatientAndMustChange() throws Exception {
        var ownerB = sessions.issue(2, SessionService.STAFF);
        assertEquals(404, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":1,\"name\":\"越权\"}", ownerB, "cross").statusCode());
        var frontdesk = sessions.issue(4, SessionService.STAFF);
        assertEquals(404, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":1,\"name\":\"越权\"}", frontdesk, "frontdesk").statusCode());
        var patient = sessions.issue(5, SessionService.PATIENT);
        assertTrue(Set.of(401, 403).contains(json("PATCH", "/staff/branches/10",
            "{\"expected_version\":1,\"name\":\"越权\"}", patient, "patient").statusCode()));
        jdbc.update("UPDATE account SET must_change_password=true WHERE account_id=1");
        var mustChange = sessions.issue(1, SessionService.STAFF);
        assertEquals(403, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":1,\"name\":\"越权\"}", mustChange, "must-change").statusCode());
    }

    @Test void patchVersionConflictAndConcurrentSameVersion() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        assertEquals(409, json("PATCH", "/staff/branches/10",
            "{\"expected_version\":99,\"name\":\"冲突\"}", s, "stale").statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(0, auditCount());

        var s1 = sessions.issue(1, SessionService.STAFF);
        var s2 = sessions.issue(1, SessionService.STAFF);
        var patchIndex = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            int index = patchIndex.getAndIncrement();
            var active = index == 0 ? s1 : s2;
            return json("PATCH", "/staff/branches/10",
                "{\"expected_version\":1,\"name\":\"" + (index == 0 ? "并发A" : "并发B") + "\"}",
                active, "concurrent-patch-" + index).statusCode();
        });
        assertTrue(results.stream().allMatch(code -> code == 200 || code == 409), results.toString());
        assertEquals(1, results.stream().filter(code -> code == 200).count(), results.toString());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
    }

    @Test void disableBlocksOngoingScheduleAndActiveAppointmentThenAllowsEnded() throws Exception {
        policy();
        schedule("已发布", -60, 60);
        appointment("待确认");
        var s = sessions.issue(1, SessionService.STAFF);
        var blocked = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"合成停用\"}", s, "disable-blocked");
        assertEquals(409, blocked.statusCode(), blocked.body());
        assertTrue(blocked.body().contains("\"schedule_count\":1"));
        assertTrue(blocked.body().contains("\"appointment_count\":1"));
        assertEquals("营业", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=10", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(0, auditCount());

        jdbc.update("""
            UPDATE schedule SET starts_at=now() + interval '1 hour',ends_at=now() + interval '2 hours'
            WHERE schedule_id=1
            """);
        var futureBlocked = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"未来班次\"}", s, "disable-future");
        assertEquals(409, futureBlocked.statusCode(), futureBlocked.body());
        assertTrue(futureBlocked.body().contains("\"schedule_count\":1"));

        jdbc.update("""
            UPDATE appointment SET state='已确认',schedule_id=1,confirmed_dentist_id=1,confirmed_at=now()
            WHERE appointment_id=1
            """);
        var confirmedBlocked = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"合成停用\"}", s, "disable-confirmed");
        assertEquals(409, confirmedBlocked.statusCode(), confirmedBlocked.body());
        assertEquals("营业", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=10", String.class));

        jdbc.update("UPDATE appointment SET coordination_state='待协调' WHERE appointment_id=1");
        var coordinatedBlocked = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"合成停用\"}", s, "disable-coordinated");
        assertEquals(409, coordinatedBlocked.statusCode(), coordinatedBlocked.body());
        assertTrue(coordinatedBlocked.body().contains("\"appointment_count\":1"));
        assertEquals(0, auditCount());

        jdbc.update("UPDATE schedule SET state='已结束' WHERE schedule_id=1");
        jdbc.update("UPDATE appointment SET state='已取消',cancelled_by_type='clinic',closure_reason='合成取消',cancelled_at=now() WHERE appointment_id=1");
        var allowed = postWithKey("/staff/branches/10/disable",
            "{\"expected_version\":1,\"reason\":\"合成停用\"}", s, "disable-allowed");
        assertEquals(200, allowed.statusCode(), allowed.body());
        assertEquals("停用", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=10", String.class));
    }

    @Test void disableAllowsCompletedAppointment() throws Exception {
        jdbc.update("""
            INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,
                restriction_days,status)
            VALUES(2,11,1,24,7,2,7,'draft')
            """);
        jdbc.update("""
            INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,
                version,travel_buffer_minutes)
            VALUES(2,2,now() - interval '2 hours',now() - interval '1 hour','初诊','已结束',11,1,0)
            """);
        jdbc.update("""
            INSERT INTO appointment(appointment_id,patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
                starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,version,
                request_key,request_hash,requested_by,branch_id,policy_id)
            VALUES(2,1,2,2,2,now() - interval '2 hours',now() - interval '1 hour','初诊','已完成预约',
                '患者端','已完成','无需协调',now(),1,?,repeat('b',64),2,11,2)
            """, UUID.randomUUID().toString());
        var ownerB = sessions.issue(2, SessionService.STAFF);
        var response = postWithKey("/staff/branches/11/disable",
            "{\"expected_version\":1,\"reason\":\"已完成不阻止\"}", ownerB, "disable-completed");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("停用", jdbc.queryForObject("SELECT state FROM branch WHERE branch_id=11", String.class));
    }

    @Test void patchIdempotencyReplayAndRevokedScopeReplayDoNotChangeState() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        String key = "patch-idem";
        String body = "{\"expected_version\":1,\"name\":\"幂等名称\"}";
        assertEquals(200, json("PATCH", "/staff/branches/10", body, s, key).statusCode());
        var relogin = sessions.issue(1, SessionService.STAFF);
        var replay = json("PATCH", "/staff/branches/10", body, relogin, key);
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(409, json("PATCH", "/staff/branches/10", "{\"expected_version\":1,\"name\":\"异内容\"}", relogin, key).statusCode());

        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=1 AND branch_id=10");
        var afterRevoke = sessions.issue(1, SessionService.STAFF);
        var denied = json("PATCH", "/staff/branches/10", body, afterRevoke, key);
        assertEquals(404, denied.statusCode(), denied.body());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals("幂等名称", jdbc.queryForObject("SELECT name FROM branch WHERE branch_id=10", String.class));
    }

    @Test void patchMidTransactionFailureRollsBackAndSameKeyRetries() throws Exception {
        String key = "patch-fault";
        String body = "{\"expected_version\":1,\"name\":\"故障名称\"}";
        var s = sessions.issue(1, SessionService.STAFF);
        jdbc.update("UPDATE branch_fault_injection SET fail=true");
        assertEquals(500, json("PATCH", "/staff/branches/10", body, s, key).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals(0, auditCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
        jdbc.update("UPDATE branch_fault_injection SET fail=false");
        assertEquals(200, json("PATCH", "/staff/branches/10", body, s, key).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT version FROM branch WHERE branch_id=10", Integer.class));
        assertEquals("故障名称", jdbc.queryForObject("SELECT name FROM branch WHERE branch_id=10", String.class));
    }

    @Test void unauthenticatedAndUnverifiedAssignmentAreNotVisible() throws Exception {
        assertEquals(401, request("GET", "/staff/branches").statusCode());
        assertEquals(401, request("POST", "/staff/branches", "Content-Type", "application/json").statusCode());
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=1 AND branch_id=10");
        var s = sessions.issue(1, SessionService.STAFF);
        var list = request("GET", "/staff/branches", "Cookie", cookie(s));
        assertEquals(200, list.statusCode());
        assertFalse(list.body().contains("\"10\""));
        assertEquals(404, request("GET", "/staff/branches/10", "Cookie", cookie(s)).statusCode());
    }

    @Test void idempotentReplayDoesNotRegrantOrRevokeNewSession() throws Exception {
        var first = sessions.issue(1, SessionService.STAFF);
        var key = "fixed-branch-key";
        var body = createBody("REPLAY-1", "1");
        var response = client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
            .header("Content-Type", "application/json").header("Cookie", cookie(first))
            .header("X-CSRF-Token", first.csrfToken()).header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        long branchId = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code='REPLAY-1'", Long.class);

        var relogin = sessions.issue(1, SessionService.STAFF);
        var replay = client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
            .header("Content-Type", "application/json").header("Cookie", cookie(relogin))
            .header("X-CSRF-Token", relogin.csrfToken()).header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("\"reauthentication_required\":false"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='REPLAY-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM staff_branch WHERE staff_id=1 AND branch_id=?", Integer.class, branchId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=1 AND branch_id=?", Integer.class, branchId));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(200, request("GET", "/auth/me", "Cookie", cookie(relogin)).statusCode());
    }

    @Test void idempotencyConflictAndDifferentKeySameCodeAreSafe() throws Exception {
        var first = sessions.issue(1, SessionService.STAFF);
        var key = "conflict-key";
        var body = createBody("CONFLICT-1", "1");
        var created = client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
            .header("Content-Type", "application/json").header("Cookie", cookie(first))
            .header("X-CSRF-Token", first.csrfToken()).header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());

        var relogin = sessions.issue(1, SessionService.STAFF);
        var changed = body.replace("CONFLICT-1", "CONFLICT-2");
        var conflict = client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
            .header("Content-Type", "application/json").header("Cookie", cookie(relogin))
            .header("X-CSRF-Token", relogin.csrfToken()).header("Idempotency-Key", key)
            .POST(HttpRequest.BodyPublishers.ofString(changed)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, conflict.statusCode(), conflict.body());

        var differentKey = client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
            .header("Content-Type", "application/json").header("Cookie", cookie(relogin))
            .header("X-CSRF-Token", relogin.csrfToken()).header("Idempotency-Key", "other-key")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, differentKey.statusCode(), differentKey.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='CONFLICT-1'", Integer.class));
    }

    @Test void concurrentSameKeyCreatesOneBranchAndDoesNotReviveScope() throws Exception {
        var s1 = sessions.issue(1, SessionService.STAFF);
        var s2 = sessions.issue(1, SessionService.STAFF);
        String body = createBody("CONCURRENT-1", "1");
        var threadIndex = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            var s = threadIndex.getAndIncrement() == 0 ? s1 : s2;
            return client.send(HttpRequest.newBuilder(URI.create(base + "/staff/branches"))
                .header("Content-Type", "application/json").header("Cookie", cookie(s))
                .header("X-CSRF-Token", s.csrfToken()).header("Idempotency-Key", "concurrent-key")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        });
        assertEquals(1, results.stream().filter(code -> code == 201).count(), results.toString());
        assertEquals(1, results.stream().filter(code -> code == 401).count(), results.toString());
        assertTrue(results.stream().allMatch(code -> code == 201 || code == 401), results.toString());
        long branchId = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code='CONCURRENT-1'", Long.class);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='CONCURRENT-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=1 AND branch_id=?", Integer.class, branchId));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));

        var relogin = sessions.issue(1, SessionService.STAFF);
        var replay = postWithKey("/staff/branches", body, relogin, "concurrent-key");
        assertEquals(201, replay.statusCode(), replay.body());
        assertTrue(replay.body().contains("\"reauthentication_required\":false"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='CONCURRENT-1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=1 AND branch_id=?", Integer.class, branchId));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
    }

    @Test void midTransactionFailureRollsBackBranchAssignmentScopeAuditAndIdempotency() throws Exception {
        String key = "fault-key";
        String body = createBody("FAULT-1", "1");
        var s = sessions.issue(1, SessionService.STAFF);
        jdbc.update("UPDATE branch_fault_injection SET fail=true");

        var failed = postWithKey("/staff/branches", body, s, key);
        assertEquals(500, failed.statusCode(), failed.body());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='FAULT-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM branch", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM staff_branch", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope", Integer.class));
        assertEquals(0, auditCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(200, request("GET", "/auth/me", "Cookie", cookie(s)).statusCode());

        jdbc.update("UPDATE branch_fault_injection SET fail=false");
        var retried = postWithKey("/staff/branches", body, s, key);
        assertEquals(201, retried.statusCode(), retried.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='FAULT-1'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
    }

    @Test void replayAfterScopeRevokedIsRejectedAndDoesNotRestoreGrant() throws Exception {
        String key = "revoked-scope-key";
        String body = createBody("REVOKED-1", "1");
        var first = sessions.issue(1, SessionService.STAFF);
        assertEquals(201, postWithKey("/staff/branches", body, first, key).statusCode());
        long branchId = jdbc.queryForObject("SELECT branch_id FROM branch WHERE code='REVOKED-1'", Long.class);
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=1 AND branch_id=?", branchId);

        var relogin = sessions.issue(1, SessionService.STAFF);
        var replay = postWithKey("/staff/branches", body, relogin, key);
        assertEquals(404, replay.statusCode(), replay.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='REVOKED-1'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=1 AND branch_id=?", Integer.class, branchId));
        assertEquals(2, jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=1", Integer.class));
        assertEquals(1, auditCount());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
    }

    @Test void waitingAccountLockRevokeCannotCreateOrRestoreScope() throws Exception {
        var s = sessions.issue(1, SessionService.STAFF);
        var ds = app.getBean(javax.sql.DataSource.class);
        try (var hold = ds.getConnection()) {
            hold.setAutoCommit(false);
            try (var st = hold.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(734192014)");
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<HttpResponse<String>> pending = pool.submit(() -> post("/staff/branches", createBody("LOCKED-1", "1"), s));
                awaitAdvisoryWait();
                try (var st = hold.createStatement()) {
                    st.execute("DELETE FROM role_branch_scope WHERE account_id=1 AND role_id=(SELECT role_id FROM role WHERE code='owner')");
                    st.execute("DELETE FROM account_role WHERE account_id=1 AND role_id=(SELECT role_id FROM role WHERE code='owner')");
                }
                hold.commit();
                var response = pending.get(20, TimeUnit.SECONDS);
                assertEquals(403, response.statusCode(), response.body());
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM branch WHERE code='LOCKED-1'", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=1 AND branch_id NOT IN (10)", Integer.class));
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
                assertEquals(0, auditCount());
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
