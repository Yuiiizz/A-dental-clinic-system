package cn.dental.organization;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
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
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CredentialIntegrationTest {
    private static final String STAFF = SessionService.STAFF;
    private static final String PATIENT = SessionService.PATIENT;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant BASE = Instant.parse("2026-10-26T02:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE, SHANGHAI);
    private static final Pattern CREDENTIAL_ID = Pattern.compile("\"credential_id\":\"(\\d+)\"");
    private static final Pattern REVIEW_ID = Pattern.compile("\"review_id\":\"(\\d+)\"");

    @Configuration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    private ConfigurableApplicationContext app;
    private SessionService sessions;
    private JdbcTemplate jdbc;
    private String schema;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    void start() throws Exception {
        var env = System.getenv();
        PostgresTestSupport.validateTarget(env);
        schema = "credential_" + UUID.randomUUID().toString().replace("-", "");
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
        sessions = app.getBean(SessionService.class);
        jdbc = app.getBean(JdbcTemplate.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";

        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES"
            + "(1,'合成资格负责人','其他人员','负责人',NULL,'在岗'),"
            + "(2,'合成分店负责人','其他人员','负责人',NULL,'在岗'),"
            + "(3,'合成管理员','其他人员','管理员',NULL,'在岗'),"
            + "(4,'合成前台','其他人员','前台',NULL,'在岗'),"
            + "(5,'合成待核验牙医','牙医','牙医','CRED-TARGET','在岗'),"
            + "(6,'合成未改密员工','其他人员','员工',NULL,'在岗')");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff))", Long.class);
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES"
            + "(10,1,'TEST-A','合成门店A','地址A','00000000000','LIC-A','09:00-18:00',1,'营业',1,now()),"
            + "(11,1,'TEST-B','合成门店B','地址B','00000000000','LIC-B','09:00-18:00',1,'营业',1,now())");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES"
            + "(1,1,'cred-owner','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(2,2,'cred-branch','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(3,3,'cred-admin','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(4,4,'cred-frontdesk','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(80,6,'cred-must-change','NOT-A-LOGIN-HASH',true,true,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(90,1,true,false,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(2,11,true,now()),(5,11,true,now()),(4,10,true,now())");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 1,role_id,now(),'合成' FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 2,role_id,now(),'合成' FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 3,role_id,now(),'合成' FROM role WHERE code='sysadmin'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 4,role_id,now(),'合成' FROM role WHERE code='frontdesk'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,10 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,11 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 2,role_id,11 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 4,role_id,10 FROM role WHERE code='frontdesk'");

        jdbc.execute("CREATE TABLE credential_fault(fail boolean NOT NULL)");
        jdbc.update("INSERT INTO credential_fault(fail) VALUES(false)");
        jdbc.execute("""
            CREATE FUNCTION credential_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF (SELECT fail FROM credential_fault LIMIT 1) THEN
                RAISE EXCEPTION 'injected credential audit failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("CREATE TRIGGER credential_fault_audit BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION credential_audit_fault()");
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @BeforeEach
    void reset() {
        CLOCK.set(BASE);
        jdbc.update("UPDATE credential_fault SET fail=false");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM credential_review");
        jdbc.update("DELETE FROM credential");
        jdbc.update("DELETE FROM staff WHERE staff_id>6");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("UPDATE organization SET responsible_staff_id=1,state='营业'");
        jdbc.update("UPDATE account SET enabled=true,must_change_password=false WHERE account_id IN (1,2,3,4,80,90)");
        jdbc.update("UPDATE staff SET employment_state='在岗'");
    }

    private SessionService.Issued session(long accountId, String type) {
        return sessions.issue(accountId, type);
    }

    private String cookie(SessionService.Issued s) {
        return "A_CLINIC_SESSION=" + s.token();
    }

    private HttpResponse<String> request(String method, String path, SessionService.Issued s) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Cookie", cookie(s));
        if (s.csrfToken() != null) b.header("X-CSRF-Token", s.csrfToken());
        return client.send(b.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, SessionService.Issued s, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("Cookie", cookie(s))
            .header("Idempotency-Key", key);
        if (s.csrfToken() != null) b.header("X-CSRF-Token", s.csrfToken());
        return client.send(b.method("POST", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String credentialBody(String type, String number, String from, String to, boolean noExpiry) {
        return "{\"credential_type\":\"" + type + "\",\"certificate_no\":\"" + number + "\""
            + (from == null ? "" : ",\"valid_from\":\"" + from + "\"")
            + (to == null ? "" : ",\"valid_to\":\"" + to + "\"")
            + ",\"no_expiry_confirmed\":" + noExpiry + "}";
    }

    private long credentialId(HttpResponse<String> response) {
        Matcher m = CREDENTIAL_ID.matcher(response.body());
        assertTrue(m.find(), response.body());
        return Long.parseLong(m.group(1));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int credentialVersion(String body, long credentialId) {
        Matcher m = Pattern.compile("\"credential_id\":\"" + credentialId + "\".*?\"version\":(\\d+)", Pattern.DOTALL)
            .matcher(body);
        assertTrue(m.find(), body);
        return Integer.parseInt(m.group(1));
    }

    private long latestReview(String body) {
        Matcher m = Pattern.compile("\"latest_review_id\":\"(\\d+)\"").matcher(body);
        assertTrue(m.find(), body);
        return Long.parseLong(m.group(1));
    }

    private long add(long staffId, String body, SessionService.Issued s) throws Exception {
        var r = post("/staff/staff-members/" + staffId + "/credentials", body, s,
            "credential-add-" + UUID.randomUUID());
        assertEquals(201, r.statusCode(), r.body());
        return credentialId(r);
    }

    @Test
    void credentialFactsRequireGlobalOwnerAndAuditOnlyMetadata() throws Exception {
        var owner = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "PRIVATE-CERT-123",
            "2026-01-01", "2030-01-01", false), owner);
        String path = "/staff/staff-members/5/credentials/" + id + "/facts";
        var facts = request("GET", path, owner);
        assertEquals(200, facts.statusCode(), facts.body());
        assertTrue(facts.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(facts.body().contains("\"certificate_no\":\"PRIVATE-CERT-123\""), facts.body());
        assertTrue(facts.body().contains("\"version\":1"), facts.body());
        assertTrue(facts.body().contains("\"valid_from\":\"2026-01-01\""), facts.body());

        var summary = request("GET", "/staff/staff-members/5", owner);
        assertEquals(200, summary.statusCode());
        assertFalse(summary.body().contains("PRIVATE-CERT-123"));
        assertEquals(403, request("GET", path, session(2, STAFF)).statusCode());
        assertEquals(403, request("GET", path, session(3, STAFF)).statusCode());
        assertEquals(403, request("GET", path, session(4, STAFF)).statusCode());
        assertEquals(401, request("GET", path, session(90, PATIENT)).statusCode());
        assertEquals(404, request("GET", "/staff/staff-members/4/credentials/" + id + "/facts", owner).statusCode());
        assertEquals(404, request("GET", "/staff/staff-members/5/credentials/999999/facts", owner).statusCode());
        assertEquals(1, count("SELECT count(*) FROM audit_log WHERE target_type='credential' AND target_id=? AND operation='查看资格证件事实' AND actor_account_id=1", id));
        assertEquals(0, count("SELECT count(*) FROM audit_log WHERE CAST(before_data AS text) LIKE '%PRIVATE-CERT-123%' OR CAST(after_data AS text) LIKE '%PRIVATE-CERT-123%' OR reason LIKE '%PRIVATE-CERT-123%'"));
    }

    @Test
    void credentialFactsFailClosedIfAuditWriteFails() throws Exception {
        var owner = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "AUDIT-FAIL-CERT",
            "2026-01-01", "2030-01-01", false), owner);
        jdbc.update("UPDATE credential_fault SET fail=true");
        var response = request("GET", "/staff/staff-members/5/credentials/" + id + "/facts", owner);
        assertTrue(response.statusCode() >= 500, response.body());
        assertFalse(response.body().contains("AUDIT-FAIL-CERT"), response.body());
    }

    @Test
    void addAndEmbeddedCreateWriteSubmitHistoryWithoutAccountOrRole() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "ADD-CERT", "2026-01-01", "2026-10-26", false), s);
        assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=? AND action='submit' AND from_state IS NULL AND to_state='pending' AND actor_staff_id=1 AND resulting_version=1", id));
        assertEquals(0, count("SELECT count(*) FROM account WHERE staff_id=5"));
        assertEquals(0, count("SELECT count(*) FROM account_role WHERE account_id IN (SELECT account_id FROM account WHERE staff_id=5)"));

        String staffJson = "{\"name\":\"内嵌资格员工\",\"staff_type\":\"牙医\",\"job_title\":\"牙医\",\"practice_no\":\"EMBED-P\","
            + "\"credential\":" + credentialBody("dentist_practice", "EMBED-CERT", "2026-01-01", "2030-01-01", false) + "}";
        var r = post("/staff/staff-members", staffJson, s, "credential-embedded-staff");
        assertEquals(201, r.statusCode(), r.body());
        long staffId = Long.parseLong(Pattern.compile("\"staff_id\":\"(\\d+)\"").matcher(r.body()).results().findFirst().orElseThrow().group(1));
        assertEquals(1, count("SELECT count(*) FROM credential_review r JOIN credential c USING(credential_id) WHERE c.staff_id=? AND r.action='submit' AND r.resulting_version=1", staffId));
        assertEquals(0, count("SELECT count(*) FROM account WHERE staff_id=?", staffId));
        assertEquals(0, count("SELECT count(*) FROM staff_branch WHERE staff_id=?", staffId));
        assertEquals(0, count("SELECT count(*) FROM account_role WHERE account_id IN (SELECT account_id FROM account WHERE staff_id=?)", staffId));
    }

    @Test
    void detailVersionSupportsSafeReviewAndOldVersionIsRejected() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "PAGE-VERSION", "2026-01-01", "2030-01-01", false), s);
        var before = request("GET", "/staff/staff-members/5", s);
        assertEquals(200, before.statusCode(), before.body());
        assertEquals(1, credentialVersion(before.body(), id));
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"页面重新打开\",\"expected_version\":1}", s, "credential-page-version").statusCode());
        var after = request("GET", "/staff/staff-members/5", s);
        assertEquals(2, credentialVersion(after.body(), id));
        assertEquals(409, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"rejected\",\"basis\":\"旧版本\",\"expected_version\":1}", s, "credential-old-version").statusCode());
    }

    @Test
    void idempotentCreateReplayReturnsCurrentVersionAndLatestReview() throws Exception {
        var s = session(1, STAFF);
        String body = credentialBody("dentist_practice", "REPLAY-VIEW", "2026-01-01", "2030-01-01", false);
        var created = post("/staff/staff-members/5/credentials", body, s, "credential-replay-view");
        assertEquals(201, created.statusCode(), created.body());
        long id = credentialId(created);
        assertEquals(1, credentialVersion(created.body(), id));
        long firstReview = latestReview(created.body());
        assertEquals(firstReview, jdbc.queryForObject("SELECT coalesce(max(review_id),0) FROM credential_review WHERE credential_id=?", Long.class, id));
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"重放当前视图\",\"expected_version\":1}", s, "credential-replay-review").statusCode());
        var replay = post("/staff/staff-members/5/credentials", body, s, "credential-replay-view");
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(2, credentialVersion(replay.body(), id));
        long replayReview = latestReview(replay.body());
        assertTrue(replayReview > firstReview, replay.body());
        assertEquals(replayReview, jdbc.queryForObject("SELECT coalesce(max(review_id),0) FROM credential_review WHERE credential_id=?", Long.class, id));
        assertEquals(2, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        assertEquals(1, count("SELECT count(*) FROM credential WHERE staff_id=5 AND certificate_no='REPLAY-VIEW'"));
    }

    @Test
    void duplicateCertificateDifferentKeyIsBusinessConflict() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "DUPLICATE-CERT", "2026-01-01", "2030-01-01", false), s);
        assertEquals(409, post("/staff/staff-members/5/credentials",
            credentialBody("dentist_practice", "DUPLICATE-CERT", "2026-01-01", "2030-01-01", false), s,
            "credential-duplicate-cert").statusCode());
        assertEquals(1, count("SELECT count(*) FROM credential WHERE credential_id=? AND certificate_no='DUPLICATE-CERT'", id));
    }

    @Test
    void reviewWaitingForCredentialLockUsesPostLockDateAndRejectsExpired() throws Exception {
        CLOCK.set(Instant.parse("2026-10-26T15:50:00Z")); // 23:50 Shanghai, before midnight
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "MIDNIGHT-WAIT", "2026-01-01", "2026-10-26", false), s);
        var pool = Executors.newSingleThreadExecutor();
        var lock = PostgresTestSupport.connect();
        Future<HttpResponse<String>> future = null;
        try {
            lock.setAutoCommit(false);
            try (var st = lock.createStatement()) {
                st.execute("SET search_path TO " + schema + ",public");
                st.execute("SELECT credential_id FROM credential WHERE credential_id=" + id + " FOR UPDATE");
            }
            future = pool.submit(() -> post("/staff/staff-members/5/credentials/" + id + "/review",
                "{\"decision\":\"verified\",\"basis\":\"跨午夜等待\",\"expected_version\":1}", s, "credential-midnight-wait"));
            boolean waiting = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type='Lock' AND query LIKE '%credential%'
                    """, Integer.class);
                if (count != null && count > 0) {
                    waiting = true;
                    break;
                }
                Thread.yield();
            }
            assertTrue(waiting, "review did not wait for credential row lock");
            CLOCK.advance(Duration.ofMinutes(20));
            lock.commit();
            var response = future.get(15, TimeUnit.SECONDS);
            assertEquals(422, response.statusCode(), response.body());
            assertEquals("pending", jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?", String.class, id));
            assertEquals(1, count("SELECT version FROM credential WHERE credential_id=?", id));
            assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        } finally {
            if (future != null) future.cancel(true);
            pool.shutdownNow();
            try {
                if (!lock.getAutoCommit()) lock.rollback();
            } finally {
                lock.close();
            }
        }
    }

    @Test
    void reviewWaitingForPermissionRevocationIsRejectedWithoutWrite() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "REVOKE-WAIT", "2026-01-01", "2030-01-01", false), s);
        var pool = Executors.newSingleThreadExecutor();
        var lock = PostgresTestSupport.connect();
        Future<HttpResponse<String>> future = null;
        try {
            lock.setAutoCommit(false);
            try (var st = lock.createStatement()) {
                st.execute("SET search_path TO " + schema + ",public");
                st.execute("SELECT credential_id FROM credential WHERE credential_id=" + id + " FOR UPDATE");
            }
            future = pool.submit(() -> post("/staff/staff-members/5/credentials/" + id + "/review",
                "{\"decision\":\"verified\",\"basis\":\"等锁撤权\",\"expected_version\":1}", s, "credential-revoke-wait"));
            boolean waiting = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type='Lock' AND query LIKE '%credential%'
                    """, Integer.class);
                if (count != null && count > 0) {
                    waiting = true;
                    break;
                }
                Thread.yield();
            }
            assertTrue(waiting, "review did not wait for credential row lock");
            jdbc.update("DELETE FROM role_permission WHERE role_id=(SELECT role_id FROM role WHERE code='owner') AND permission_id=(SELECT permission_id FROM permission WHERE code='staff.manage_global')");
            lock.commit();
            var response = future.get(15, TimeUnit.SECONDS);
            assertEquals(403, response.statusCode(), response.body());
            assertEquals("pending", jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?", String.class, id));
            assertEquals(1, count("SELECT version FROM credential WHERE credential_id=?", id));
            assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
            assertEquals(1, count("SELECT count(*) FROM audit_log"));
            assertEquals(1, count("SELECT count(*) FROM operation_request"));
        } finally {
            if (future != null) future.cancel(true);
            pool.shutdownNow();
            try {
                if (!lock.getAutoCommit()) lock.rollback();
            } finally {
                lock.close();
                jdbc.update("UPDATE organization SET responsible_staff_id=1 WHERE organization_id=1");
                jdbc.update("INSERT INTO role_permission(role_id,permission_id) SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='staff.manage_global' WHERE r.code='owner' ON CONFLICT DO NOTHING");
            }
        }
    }

    @Test
    void reviewReplayAfterPermissionRevocationIsRejectedWithoutNewWrite() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "REVOKE-REPLAY", "2026-01-01", "2030-01-01", false), s);
        String body = "{\"decision\":\"verified\",\"basis\":\"先成功后撤权\",\"expected_version\":1}";
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review", body, s, "credential-revoke-replay").statusCode());
        jdbc.update("DELETE FROM role_permission WHERE role_id=(SELECT role_id FROM role WHERE code='owner') AND permission_id=(SELECT permission_id FROM permission WHERE code='staff.manage_global')");
        try {
            assertEquals(403, post("/staff/staff-members/5/credentials/" + id + "/review", body, s, "credential-revoke-replay").statusCode());
            assertEquals(2, count("SELECT version FROM credential WHERE credential_id=?", id));
            assertEquals(2, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        } finally {
            jdbc.update("INSERT INTO role_permission(role_id,permission_id) SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='staff.manage_global' WHERE r.code='owner' ON CONFLICT DO NOTHING");
        }
    }

    @Test
    void verifySetsStateVersionHistoryAuditAndDateEffectiveness() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "VERIFY-CERT", "2026-01-01", "2026-10-26", false), s);
        var r = post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"核对原件\",\"expected_version\":1}", s, "credential-verify");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, count("SELECT count(*) FROM credential WHERE credential_id=? AND verification_state='verified' AND version=2 AND verified_by=1", id));
        assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=? AND action='verify' AND from_state='pending' AND to_state='verified' AND actor_staff_id=1 AND basis='核对原件' AND resulting_version=2", id));
        assertTrue(r.body().contains("\"effective\":true"), r.body());

        CLOCK.advance(Duration.ofDays(1));
        var nextDay = request("GET", "/staff/staff-members/5", session(1, STAFF));
        assertEquals(200, nextDay.statusCode(), nextDay.body());
        assertTrue(nextDay.body().contains("\"effective\":false"), nextDay.body());
    }

    @Test
    void rejectSetsReviewFactsAndNeverBecomesEffective() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "REJECT-CERT", "2026-01-01", "2026-10-26", false), s);
        var r = post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"rejected\",\"basis\":\"原件不清晰\",\"expected_version\":1}", s, "credential-reject");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, count("SELECT count(*) FROM credential WHERE credential_id=? AND verification_state='rejected' AND version=2 AND verified_by=1 AND verified_at IS NOT NULL", id));
        assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=? AND action='reject' AND to_state='rejected' AND resulting_version=2", id));
        assertTrue(r.body().contains("\"effective\":false"), r.body());
    }

    @Test
    void dateRulesAndNoExpiryAreEnforced() throws Exception {
        var s = session(1, STAFF);
        long expired = add(5, credentialBody("dentist_practice", "EXPIRED-CERT", "2025-01-01", "2026-10-25", false), s);
        assertEquals(422, post("/staff/staff-members/5/credentials/" + expired + "/review",
            "{\"decision\":\"verified\",\"basis\":\"过期\",\"expected_version\":1}", s, "credential-expired").statusCode());
        assertEquals("pending", jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?", String.class, expired));

        long future = add(5, credentialBody("dentist_practice", "FUTURE-CERT", "2026-11-01", "2026-12-01", false), s);
        var futureReview = post("/staff/staff-members/5/credentials/" + future + "/review",
            "{\"decision\":\"verified\",\"basis\":\"真实未来生效\",\"expected_version\":1}", s, "credential-future");
        assertEquals(200, futureReview.statusCode(), futureReview.body());
        assertTrue(futureReview.body().contains("\"effective\":false"), futureReview.body());

        long noExpiryEmpty = add(5, credentialBody("dentist_practice", "NO-EXPIRY-EMPTY", null, null, false), s);
        assertEquals(422, post("/staff/staff-members/5/credentials/" + noExpiryEmpty + "/review",
            "{\"decision\":\"verified\",\"basis\":\"没有期限\",\"expected_version\":1}", s, "credential-no-expiry-empty").statusCode());

        long noExpiry = add(5, credentialBody("dentist_practice", "NO-EXPIRY-OK", null, null, true), s);
        var noExpiryReview = post("/staff/staff-members/5/credentials/" + noExpiry + "/review",
            "{\"decision\":\"verified\",\"basis\":\"确认长期有效\",\"expected_version\":1}", s, "credential-no-expiry-ok");
        assertEquals(200, noExpiryReview.statusCode(), noExpiryReview.body());
        assertTrue(noExpiryReview.body().contains("\"effective\":true"), noExpiryReview.body());
    }

    @Test
    void permissionsCrossStaffAndForgedFieldsAreRejected() throws Exception {
        var owner = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "PERM-CERT", "2026-01-01", "2030-01-01", false), owner);
        for (var s : List.of(session(2, STAFF), session(3, STAFF), session(80, STAFF))) {
            assertEquals(403, post("/staff/staff-members/5/credentials", credentialBody("other", "DENIED-" + UUID.randomUUID(), null, null, false),
                s, "credential-denied-" + UUID.randomUUID()).statusCode());
            assertEquals(403, post("/staff/staff-members/5/credentials/" + id + "/review",
                "{\"decision\":\"verified\",\"basis\":\"越权\",\"expected_version\":1}", s, "credential-review-denied-" + UUID.randomUUID()).statusCode());
            assertEquals(403, request("GET", "/staff/staff-members/5/credentials/" + id + "/reviews", s).statusCode());
        }
        assertTrue(Set.of(401, 403).contains(request("GET", "/staff/staff-members/5/credentials/" + id + "/reviews", session(90, PATIENT)).statusCode()));
        assertEquals(404, request("GET", "/staff/staff-members/2/credentials/" + id + "/reviews", owner).statusCode());
        assertEquals(404, post("/staff/staff-members/2/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"跨员工\",\"expected_version\":1}", owner, "credential-cross-staff").statusCode());
        assertEquals(422, post("/staff/staff-members/5/credentials",
            "{\"credential_type\":\"other\",\"certificate_no\":\"FORGED\",\"no_expiry_confirmed\":false,\"verification_state\":\"verified\"}", owner, "credential-forged").statusCode());
        assertEquals(422, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"伪造\",\"expected_version\":1,\"verified_by\":\"1\"}", owner, "credential-review-forged").statusCode());
    }

    @Test
    void idempotencyAndConcurrentOppositeReviewsHaveOneWinner() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "CONCURRENT-CERT", "2026-01-01", "2030-01-01", false), s);
        String verifiedBody = "{\"decision\":\"verified\",\"basis\":\"通过\",\"expected_version\":1}";
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review", verifiedBody, s, "credential-review-key").statusCode());
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review", verifiedBody, s, "credential-review-key").statusCode());
        assertEquals(2, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        assertEquals(409, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"rejected\",\"basis\":\"不同\",\"expected_version\":1}", s, "credential-review-key").statusCode());
        assertEquals(409, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"rejected\",\"basis\":\"终态再审核\",\"expected_version\":2}", s, "credential-review-terminal").statusCode());

        long second = add(5, credentialBody("dentist_practice", "CONCURRENT-OPPOSITE", "2026-01-01", "2030-01-01", false), s);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> post("/staff/staff-members/5/credentials/" + second + "/review",
                "{\"decision\":\"verified\",\"basis\":\"并发通过\",\"expected_version\":1}", s, "credential-concurrent-a"));
            var f2 = pool.submit(() -> post("/staff/staff-members/5/credentials/" + second + "/review",
                "{\"decision\":\"rejected\",\"basis\":\"并发驳回\",\"expected_version\":1}", s, "credential-concurrent-b"));
            var statuses = new ArrayList<>(List.of(f1.get(15, TimeUnit.SECONDS).statusCode(),
                f2.get(15, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            assertEquals(2, count("SELECT count(*) FROM credential_review WHERE credential_id=?", second));
            assertEquals(2, count("SELECT version FROM credential WHERE credential_id=?", second));
            assertEquals(1, count("SELECT count(*) FROM operation_request WHERE request_key IN ('credential-concurrent-a','credential-concurrent-b')"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void historyIsPagedAndDoesNotExposeCertificate() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "HISTORY-CERT", "2026-01-01", "2030-01-01", false), s);
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review",
            "{\"decision\":\"verified\",\"basis\":\"历史依据\",\"expected_version\":1}", s, "credential-history-review").statusCode());
        Set<Long> ids = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            String path = "/staff/staff-members/5/credentials/" + id + "/reviews?limit=1"
                + (cursor == null ? "" : "&cursor=" + cursor);
            var r = request("GET", path, s);
            assertEquals(200, r.statusCode(), r.body());
            Matcher m = REVIEW_ID.matcher(r.body());
            assertTrue(m.find(), r.body());
            assertTrue(ids.add(Long.parseLong(m.group(1))), r.body());
            assertFalse(r.body().contains("HISTORY-CERT"), r.body());
            Matcher next = Pattern.compile("\"next_cursor\":\"(\\d+)\"").matcher(r.body());
            cursor = next.find() ? next.group(1) : null;
            if (cursor == null) break;
        }
        assertEquals(2, ids.size(), ids.toString());
    }

    @Test
    void auditFailureRollsBackCredentialHistoryAndIdempotencyAndRetryWorks() throws Exception {
        var s = session(1, STAFF);
        long id = add(5, credentialBody("dentist_practice", "ROLLBACK-REVIEW", "2026-01-01", "2030-01-01", false), s);
        String body = "{\"decision\":\"verified\",\"basis\":\"故障注入\",\"expected_version\":1}";
        jdbc.update("UPDATE credential_fault SET fail=true");
        assertEquals(500, post("/staff/staff-members/5/credentials/" + id + "/review", body, s, "credential-rollback-key").statusCode());
        assertEquals(1, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        assertEquals(1, count("SELECT version FROM credential WHERE credential_id=?", id));
        assertEquals("pending", jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?", String.class, id));
        assertEquals(1, count("SELECT count(*) FROM audit_log"));
        assertEquals(1, count("SELECT count(*) FROM operation_request"));

        jdbc.update("UPDATE credential_fault SET fail=false");
        assertEquals(200, post("/staff/staff-members/5/credentials/" + id + "/review", body, s, "credential-rollback-key").statusCode());
        assertEquals(2, count("SELECT count(*) FROM credential_review WHERE credential_id=?", id));
        assertEquals(2, count("SELECT count(*) FROM audit_log"));
        assertEquals(2, count("SELECT count(*) FROM operation_request"));
    }

    private long verifiedForRevoke(SessionService.Issued owner) throws Exception {
        long id=add(5,credentialBody("dentist_practice","REVOKE-"+UUID.randomUUID(),"2026-01-01","2030-01-01",false),owner);
        assertEquals(200,post("/staff/staff-members/5/credentials/"+id+"/review",
            "{\"decision\":\"verified\",\"basis\":\"合成核验\",\"expected_version\":1}",owner,UUID.randomUUID().toString()).statusCode());
        return id;
    }

    @Test void revokePreservesVerificationAndReplaysWithoutExtraWrites() throws Exception {
        var owner=session(1,STAFF);long id=verifiedForRevoke(owner);
        var before=jdbc.queryForMap("SELECT verified_by,verified_at,certificate_no FROM credential WHERE credential_id=?",id);
        String path="/staff/staff-members/5/credentials/"+id+"/revoke";
        String body="{\"basis\":\"合成撤销依据\",\"expected_version\":2}";
        CLOCK.set(BASE.plusSeconds(120));
        var result=post(path,body,owner,"revoke-one");assertEquals(200,result.statusCode(),result.body());
        assertTrue(result.body().contains("\"effective\":false"));
        assertEquals(before,jdbc.queryForMap("SELECT verified_by,verified_at,certificate_no FROM credential WHERE credential_id=?",id));
        assertEquals(3,count("SELECT version FROM credential WHERE credential_id=?",id));
        assertEquals(1,count("SELECT count(*) FROM credential_review WHERE credential_id=? AND action='revoke'",id));
        int audit=count("SELECT count(*) FROM audit_log");
        assertEquals(200,post(path,body,owner,"revoke-one").statusCode());
        assertEquals(audit,count("SELECT count(*) FROM audit_log"));
        assertEquals(409,post(path,body.replace("合成撤销依据","其他依据"),owner,"revoke-one").statusCode());
        assertEquals(409,post(path,body,owner,"revoke-other").statusCode());
        assertEquals(409,post(path,body.replace(":2",":3"),owner,"revoke-terminal").statusCode());
        assertEquals(200,request("GET","/auth/me",owner).statusCode());
    }

    @Test void revokeAuthorizationAndValidationHaveNoSideEffects() throws Exception {
        var owner=session(1,STAFF);long id=verifiedForRevoke(owner);
        String path="/staff/staff-members/5/credentials/"+id+"/revoke";
        String body="{\"basis\":\"合成\",\"expected_version\":2}";
        for(long account:List.of(2L,3L,4L)) assertEquals(403,post(path,body,session(account,STAFF),UUID.randomUUID().toString()).statusCode());
        assertEquals(404,post(path.replace("members/5/","members/2/"),body,owner,"wrong-target").statusCode());
        assertEquals(422,post(path,body.replace("合成",""),owner,"empty-basis").statusCode());
        assertEquals(422,post(path,body.replace("}",",\"verified_by\":1}"),owner,"forged").statusCode());
        assertEquals(2,count("SELECT version FROM credential WHERE credential_id=?",id));
        assertEquals(0,count("SELECT count(*) FROM credential_review WHERE action='revoke'"));
    }

    @Test void revokeAuditFailureRollsBackAndSameKeyRecovers() throws Exception {
        var owner=session(1,STAFF);long id=verifiedForRevoke(owner);
        String path="/staff/staff-members/5/credentials/"+id+"/revoke";
        String body="{\"basis\":\"合成故障\",\"expected_version\":2}";
        jdbc.update("UPDATE credential_fault SET fail=true");
        assertEquals(500,post(path,body,owner,"revoke-fault").statusCode());
        assertEquals("verified",jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?",String.class,id));
        assertEquals(2,count("SELECT version FROM credential WHERE credential_id=?",id));
        assertEquals(0,count("SELECT count(*) FROM credential_review WHERE action='revoke'"));
        assertEquals(0,count("SELECT count(*) FROM operation_request WHERE request_key='revoke-fault'"));
        jdbc.update("UPDATE credential_fault SET fail=false");
        assertEquals(200,post(path,body,owner,"revoke-fault").statusCode());
    }

    @Test void concurrentRevocationsHaveOnlyOneWinner() throws Exception {
        var owner=session(1,STAFF);long id=verifiedForRevoke(owner);
        String path="/staff/staff-members/5/credentials/"+id+"/revoke";
        String body="{\"basis\":\"合成并发\",\"expected_version\":2}";
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->post(path,body,owner,"revoke-a"));
            var b=pool.submit(()->post(path,body,owner,"revoke-b"));
            var statuses=new ArrayList<>(List.of(a.get(15,TimeUnit.SECONDS).statusCode(),b.get(15,TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(1,count("SELECT count(*) FROM credential_review WHERE action='revoke'"));
        } finally {pool.shutdownNow();}
    }


    private <T> T eligibilityTx(java.util.function.Supplier<T> work) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(
            app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        return tx.execute(status->work.get());
    }
    private cn.dental.organization.application.StaffEligibilityService eligibility() {
        return app.getBean(cn.dental.organization.application.StaffEligibilityService.class);
    }
    @Test void qualificationServiceRequiresTransactionAndInPostDentist() throws Exception {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
            ()->eligibility().isCurrentlyPracticeQualified(5));
        var owner=session(1,STAFF);verifiedForRevoke(owner);
        assertTrue(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(5)));
        assertFalse(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(1)));
        assertFalse(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(999999)));
        jdbc.update("UPDATE staff SET employment_state='离岗' WHERE staff_id=5");
        assertFalse(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(5)));
    }
    @Test void anotherValidCertificateSurvivesOneRevocation() throws Exception {
        var owner=session(1,STAFF);long first=verifiedForRevoke(owner),second=verifiedForRevoke(owner);
        String body="{\"basis\":\"合成撤销\",\"expected_version\":2}";
        assertEquals(200,post("/staff/staff-members/5/credentials/"+first+"/revoke",body,owner,"multi-revoke-one").statusCode());
        assertTrue(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(5)));
        assertEquals(200,post("/staff/staff-members/5/credentials/"+second+"/revoke",body,owner,"multi-revoke-two").statusCode());
        assertFalse(eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(5)));
    }
    @Test void qualificationMustCoverEntireFutureIntervalWithoutStitching() throws Exception {
        var owner=session(1,STAFF);
        long first=verifiedForRevoke(owner),second=verifiedForRevoke(owner);
        jdbc.update("UPDATE credential SET valid_from='2026-10-26',valid_to='2026-10-26' WHERE credential_id=?",first);
        jdbc.update("UPDATE credential SET valid_from='2026-10-27',valid_to='2026-10-27' WHERE credential_id=?",second);
        Instant start=Instant.parse("2026-10-26T15:00:00Z"),midnight=Instant.parse("2026-10-26T16:00:00Z");
        assertTrue(eligibilityTx(()->eligibility().isPracticeQualifiedForInterval(5,start,midnight)));
        assertFalse(eligibilityTx(()->eligibility().isPracticeQualifiedForInterval(5,start,midnight.plusSeconds(3600))));
        assertTrue(eligibilityTx(()->eligibility().isPracticeQualifiedForInterval(5,midnight,midnight.plusSeconds(3600))));
        jdbc.update("UPDATE credential SET valid_to='2026-10-27' WHERE credential_id=?",first);
        assertTrue(eligibilityTx(()->eligibility().isPracticeQualifiedForInterval(5,start,midnight.plusSeconds(3600))));
    }
    @Test void qualificationTakesClockAfterStaffLockWait() throws Exception {
        var owner=session(1,STAFF);long id=verifiedForRevoke(owner);
        jdbc.update("UPDATE credential SET valid_to='2026-10-26' WHERE credential_id=?",id);
        CLOCK.set(Instant.parse("2026-10-26T15:59:00Z"));
        var pool=Executors.newSingleThreadExecutor();
        try(var lock=PostgresTestSupport.connect()) {
            lock.setAutoCommit(false);
            try {
                try(var stmt=lock.createStatement()) {
                    stmt.execute("SET search_path TO "+schema+",public");
                    stmt.execute("SELECT staff_id FROM staff WHERE staff_id=5 FOR UPDATE");
                }
                var future=pool.submit(()->eligibilityTx(()->eligibility().isCurrentlyPracticeQualified(5)));
                boolean waiting=false;
                long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
                while(System.nanoTime()<deadline) {
                    if(count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT staff_type,employment_state FROM staff%' ")>0) {waiting=true;break;}
                    Thread.yield();
                }
                assertTrue(waiting,"qualification request must wait on staff row");
                CLOCK.advance(Duration.ofMinutes(2));lock.commit();
                assertFalse(future.get(15,TimeUnit.SECONDS));
                assertEquals("verified",jdbc.queryForObject("SELECT verification_state FROM credential WHERE credential_id=?",String.class,id));
            } finally {lock.rollback();}
        } finally {pool.shutdownNow();}
    }

}
