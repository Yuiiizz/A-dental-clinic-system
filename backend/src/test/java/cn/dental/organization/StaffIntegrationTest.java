package cn.dental.organization;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffIntegrationTest {
    private static final String STAFF = SessionService.STAFF;
    private static final String PATIENT = SessionService.PATIENT;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Pattern STAFF_ID = Pattern.compile("\"staff_id\":\"(\\d+)\"");

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
        String schema = "staff_" + UUID.randomUUID().toString().replace("-", "");
        this.schema = schema;
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

        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES"
            + "(1,'合成组织负责人','其他人员','负责人',NULL,'在岗'),"
            + "(2,'合成分店负责人','其他人员','负责人',NULL,'在岗'),"
            + "(3,'合成管理员','其他人员','管理员',NULL,'在岗'),"
            + "(4,'合成前台','其他人员','前台',NULL,'在岗'),"
            + "(5,'合成目标牙医','牙医','牙医','TARGET-PRACTICE','在岗'),"
            + "(6,'合成未改密员工','其他人员','员工',NULL,'在岗'),"
            + "(7,'合成双店负责人','其他人员','负责人',NULL,'在岗')");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff))", Long.class);
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES"
            + "(10,1,'TEST-A','合成门店A','地址A','00000000000','LIC-A','09:00-18:00',1,'营业',1,now()),"
            + "(11,1,'TEST-B','合成门店B','地址B','00000000000','LIC-B','09:00-18:00',1,'营业',1,now())");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('branch','branch_id'),(SELECT max(branch_id) FROM branch))", Long.class);
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES"
            + "(1,1,'owner-global','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(2,2,'owner-branch','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(3,3,'sysadmin','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(4,4,'frontdesk','NOT-A-LOGIN-HASH',true,false,now()),"
            + "(80,6,'must-change','NOT-A-LOGIN-HASH',true,true,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(7,7,'owner-both','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(90,1,true,false,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES"
            + "(2,10,true,now()),(2,11,true,now()),(5,10,true,now()),(5,11,true,now()),(4,10,true,now()),(7,10,true,now()),(7,11,true,now())");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 1,role_id,now(),'合成' FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 2,role_id,now(),'合成' FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 2,role_id,now(),'合成' FROM role WHERE code='frontdesk'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 3,role_id,now(),'合成' FROM role WHERE code='sysadmin'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 4,role_id,now(),'合成' FROM role WHERE code='frontdesk'");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 7,role_id,now(),'合成' FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,10 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,11 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 2,role_id,11 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 4,role_id,10 FROM role WHERE code='frontdesk'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 7,role_id,10 FROM role WHERE code='owner'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 7,role_id,11 FROM role WHERE code='owner'");

        jdbc.execute("CREATE TABLE staff_fault(fail boolean NOT NULL)");
        jdbc.update("INSERT INTO staff_fault(fail) VALUES(false)");
        jdbc.execute("""
            CREATE FUNCTION staff_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF (SELECT fail FROM staff_fault LIMIT 1) THEN
                RAISE EXCEPTION 'injected staff audit failure';
              END IF;
              RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("CREATE TRIGGER staff_fault_audit BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION staff_audit_fault()");
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("UPDATE staff_fault SET fail=false");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM credential_review");
        jdbc.update("DELETE FROM credential");
        jdbc.update("DELETE FROM staff_branch WHERE staff_id>7");
        jdbc.update("DELETE FROM account WHERE account_id>90");
        jdbc.update("DELETE FROM staff WHERE staff_id>7");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("UPDATE organization SET responsible_staff_id=1,state='营业'");
        jdbc.update("UPDATE account SET enabled=true,must_change_password=false WHERE account_id IN (1,2,3,4,7,80,90)");
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

    private String body(String name, String type, String title, String practice, String credentialJson) {
        return "{\"name\":\"" + name + "\",\"staff_type\":\"" + type + "\",\"job_title\":\"" + title + "\""
            + (practice == null ? "" : ",\"practice_no\":\"" + practice + "\"")
            + (credentialJson == null ? "" : ",\"credential\":" + credentialJson) + "}";
    }

    private String credential(String certificate, String from, String to, boolean noExpiry) {
        return "{\"credential_type\":\"dentist_practice\",\"certificate_no\":\"" + certificate + "\""
            + (from == null ? "" : ",\"valid_from\":\"" + from + "\"")
            + (to == null ? "" : ",\"valid_to\":\"" + to + "\"")
            + ",\"no_expiry_confirmed\":" + noExpiry + "}";
    }

    private long staffId(HttpResponse<String> response) {
        Matcher m = STAFF_ID.matcher(response.body());
        assertTrue(m.find(), response.body());
        return Long.parseLong(m.group(1));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void globalOwnerCreatesPendingStaffWithoutAccountRoleOrAssignment() throws Exception {
        var s = session(1, STAFF);
        var r = post("/staff/staff-members", body("新牙医", "牙医", "牙医", "NEW-P-1",
            credential("CERT-NEW-1", "2026-01-01", "2030-01-01", false)), s, "staff-create-global-1");
        assertEquals(201, r.statusCode(), r.body());
        long id = staffId(r);
        assertEquals(1, count("SELECT count(*) FROM staff WHERE staff_id=?", id));
        assertEquals(0, count("SELECT count(*) FROM account WHERE staff_id=?", id));
        assertEquals(0, count("SELECT count(*) FROM account_role WHERE account_id IN (SELECT account_id FROM account WHERE staff_id=?)", id));
        assertEquals(0, count("SELECT count(*) FROM staff_branch WHERE staff_id=?", id));
        assertEquals(1, count("SELECT version FROM staff WHERE staff_id=?", id));
        assertEquals(1, count("SELECT count(*) FROM credential WHERE staff_id=? AND verification_state='pending' AND verified_by IS NULL AND verified_at IS NULL", id));

        var d = request("GET", "/staff/staff-members/" + id, s);
        assertEquals(200, d.statusCode(), d.body());
        assertTrue(d.body().contains("\"verification_state\":\"pending\""), d.body());
        assertFalse(d.body().contains("certificate_no"), d.body());
        assertFalse(d.body().contains("account_id"), d.body());
        assertFalse(d.body().contains("role"), d.body());
    }

    @Test
    void onlyGlobalOwnerCanCreate() throws Exception {
        var global = session(1, STAFF);
        var branchOwner = session(2, STAFF);
        var sysadmin = session(3, STAFF);
        var frontdesk = session(4, STAFF);
        var ownerBoth = session(7, STAFF);
        var patient = session(90, PATIENT);
        var mustChange = session(80, STAFF);
        for (var s : List.of(branchOwner, ownerBoth, sysadmin, frontdesk, mustChange)) {
            assertEquals(403, post("/staff/staff-members", body("无权员工", "其他人员", "员工", null, null), s,
                "staff-create-denied-" + UUID.randomUUID()).statusCode());
        }
        assertTrue(Set.of(401, 403).contains(post("/staff/staff-members",
            body("无权患者", "其他人员", "员工", null, null), patient, "staff-create-patient").statusCode()));
        assertEquals(201, post("/staff/staff-members", body("合法员工", "其他人员", "员工", null, null), global,
            "staff-create-ok").statusCode());
    }

    @Test
    void listGlobalAndBranchIsolationAndNoDuplicateAcrossAssignments() throws Exception {
        var global = session(1, STAFF);
        var branchB = session(2, STAFF);
        var listGlobal = request("GET", "/staff/staff-members", global);
        assertEquals(200, listGlobal.statusCode(), listGlobal.body());
        assertTrue(listGlobal.body().contains("合成目标牙医"));
        assertTrue(listGlobal.body().contains("\"version\":1"));

        var listB = request("GET", "/staff/staff-members", branchB);
        assertEquals(200, listB.statusCode(), listB.body());
        assertTrue(listB.body().contains("合成目标牙医"));
        assertFalse(listB.body().contains("合成管理员"));
        assertEquals(1, listB.body().split("\"staff_id\":\"5\"", -1).length - 1, listB.body());

        var detailB = request("GET", "/staff/staff-members/5", branchB);
        assertEquals(200, detailB.statusCode(), detailB.body());
        assertTrue(detailB.body().contains("\"branch_id\":\"11\""), detailB.body());
        assertFalse(detailB.body().contains("\"branch_id\":\"10\""), detailB.body());
        assertFalse(detailB.body().contains("certificate_no"), detailB.body());
    }

    @Test
    void createWithoutAssignmentIsInvisibleToBranchButVisibleGlobally() throws Exception {
        var global = session(1, STAFF);
        var branchB = session(2, STAFF);
        var create = post("/staff/staff-members", body("无任职员工", "其他人员", "员工", null, null), global, "staff-unassigned");
        assertEquals(201, create.statusCode(), create.body());
        long id = staffId(create);
        assertTrue(create.body().contains("\"practice_no\":null"), create.body());
        var detail = request("GET", "/staff/staff-members/" + id, global);
        assertTrue(detail.body().contains("\"practice_no\":null"), detail.body());
        assertTrue(request("GET", "/staff/staff-members", global).body().contains("无任职员工"));
        assertFalse(request("GET", "/staff/staff-members", branchB).body().contains("无任职员工"));
        assertEquals(404, request("GET", "/staff/staff-members/" + id, branchB).statusCode());
        assertEquals(200, request("GET", "/staff/staff-members/" + id, global).statusCode());
    }

    @Test
    void idempotencyReplayConflictAndDuplicatePracticeNo() throws Exception {
        var s = session(1, STAFF);
        String body = body("幂等牙医", "牙医", "牙医", "IDEMP-P", null);
        var first = post("/staff/staff-members", body, s, "staff-idem-key");
        assertEquals(201, first.statusCode(), first.body());
        long id = staffId(first);
        var replay = post("/staff/staff-members", body, s, "staff-idem-key");
        assertEquals(201, replay.statusCode(), replay.body());
        assertEquals(id, staffId(replay));
        assertEquals(1, count("SELECT count(*) FROM staff WHERE practice_no='IDEMP-P'"));

        assertEquals(409, post("/staff/staff-members", body("改内容", "其他人员", "员工", null, null), s, "staff-idem-key").statusCode());
        assertEquals(409, post("/staff/staff-members", body("重复执业", "牙医", "牙医", "IDEMP-P", null), s, "staff-other-key").statusCode());
    }

    @Test
    void explicitNullCredentialDatesAreTreatedAsAbsentPendingValues() throws Exception {
        var s = session(1, STAFF);
        String body = "{\"name\":\"空日期资格\",\"staff_type\":\"其他人员\",\"job_title\":\"员工\","
            + "\"credential\":{\"credential_type\":\"other\",\"certificate_no\":\"NULL-DATES\","
            + "\"valid_from\":null,\"valid_to\":null,\"no_expiry_confirmed\":false}}";
        var r = post("/staff/staff-members", body, s, "staff-null-dates");
        assertEquals(201, r.statusCode(), r.body());
        long id = staffId(r);
        assertEquals(1, count("SELECT count(*) FROM credential WHERE staff_id=? AND certificate_no='NULL-DATES' AND verification_state='pending' AND valid_from IS NULL AND valid_to IS NULL AND no_expiry_confirmed=false", id));
        var detail = request("GET", "/staff/staff-members/" + id, s);
        assertEquals(200, detail.statusCode(), detail.body());
        assertTrue(detail.body().contains("\"effective\":false"), detail.body());
    }

    @Test
    void concurrentSameKeyCreatesOneStaffAndOneCredential() throws Exception {
        var s1 = session(1, STAFF);
        var s2 = session(1, STAFF);
        String body = body("并发同键", "牙医", "牙医", "CONCURRENT-SAME",
            credential("CONCURRENT-SAME-CERT", "2026-01-01", "2030-01-01", false));
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            Callable<HttpResponse<String>> task = () -> {
                ready.countDown();
                start.await();
                return post("/staff/staff-members", body, s1, "staff-concurrent-same");
            };
            Callable<HttpResponse<String>> task2 = () -> {
                ready.countDown();
                start.await();
                return post("/staff/staff-members", body, s2, "staff-concurrent-same");
            };
            var f1 = pool.submit(task);
            var f2 = pool.submit(task2);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var r1 = f1.get(15, TimeUnit.SECONDS);
            var r2 = f2.get(15, TimeUnit.SECONDS);
            assertEquals(201, r1.statusCode(), r1.body());
            assertEquals(201, r2.statusCode(), r2.body());
            assertEquals(staffId(r1), staffId(r2));
            assertEquals(1, count("SELECT count(*) FROM staff WHERE practice_no='CONCURRENT-SAME'"));
            assertEquals(1, count("SELECT count(*) FROM credential WHERE certificate_no='CONCURRENT-SAME-CERT'"));
            assertEquals(1, count("SELECT count(*) FROM operation_request WHERE request_key='staff-concurrent-same'"));
            assertEquals(2, count("SELECT count(*) FROM audit_log"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDifferentKeysSamePracticeNoHasOneWinnerAndNoOrphan() throws Exception {
        var s1 = session(1, STAFF);
        var s2 = session(1, STAFF);
        String body = body("并发不同键", "牙医", "牙医", "CONCURRENT-DIFFERENT", null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> post("/staff/staff-members", body, s1, "staff-concurrent-a"));
            var f2 = pool.submit(() -> post("/staff/staff-members", body, s2, "staff-concurrent-b"));
            var statuses = new ArrayList<>(List.of(f1.get(15, TimeUnit.SECONDS).statusCode(),
                f2.get(15, TimeUnit.SECONDS).statusCode()));
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
            assertEquals(1, count("SELECT count(*) FROM staff WHERE practice_no='CONCURRENT-DIFFERENT'"));
            assertEquals(1, count("SELECT count(*) FROM operation_request WHERE request_key IN ('staff-concurrent-a','staff-concurrent-b')"));
            assertEquals(0, count("SELECT count(*) FROM credential"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void realCursorPaginationDoesNotDuplicateAcrossVisibleBranches() throws Exception {
        var s = session(7, STAFF);
        LinkedHashMap<Long, Boolean> seen = new LinkedHashMap<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String path = "/staff/staff-members?limit=1" + (cursor == null ? "" : "&cursor=" + cursor);
            var r = request("GET", path, s);
            assertEquals(200, r.statusCode(), r.body());
            long id = staffId(r);
            assertFalse(seen.containsKey(id), "duplicate staff " + id);
            seen.put(id, true);
            Matcher m = Pattern.compile("\"next_cursor\":\"(\\d+)\"").matcher(r.body());
            cursor = m.find() ? m.group(1) : null;
            if (cursor == null) break;
        }
        assertTrue(seen.containsKey(2L), seen.keySet().toString());
        assertTrue(seen.containsKey(4L), seen.keySet().toString());
        assertTrue(seen.containsKey(5L), seen.keySet().toString());
        assertTrue(seen.containsKey(7L), seen.keySet().toString());
        assertEquals(4, seen.size(), seen.keySet().toString());
    }

    @Test
    void afterSuccessfulCreateRevokingGlobalPermissionMakesSameKeyReplayForbidden() throws Exception {
        var s = session(1, STAFF);
        var first = post("/staff/staff-members", body("撤权重放", "其他人员", "员工", null, null), s, "staff-revoke-replay");
        assertEquals(201, first.statusCode(), first.body());
        long id = staffId(first);
        jdbc.update("UPDATE organization SET responsible_staff_id=2 WHERE organization_id=1");
        assertEquals(403, post("/staff/staff-members", body("撤权重放", "其他人员", "员工", null, null), s, "staff-revoke-replay").statusCode());
        assertEquals(1, count("SELECT count(*) FROM staff WHERE staff_id=?", id));
        jdbc.update("UPDATE organization SET responsible_staff_id=1 WHERE organization_id=1");
    }

    @Test
    void rejectsInvalidMetadataAndUnknownFieldsWithoutCreatingRows() throws Exception {
        var s = session(1, STAFF);
        assertEquals(422, post("/staff/staff-members", body("无执业", "牙医", "牙医", null, null), s, "invalid-practice").statusCode());
        assertEquals(422, post("/staff/staff-members", body("错日期", "牙医", "牙医", "BAD-DATE", credential("CERT-D", "2030-01-01", "2029-01-01", false)), s, "invalid-dates").statusCode());
        assertEquals(422, post("/staff/staff-members", body("矛盾期限", "牙医", "牙医", "BAD-EXPIRY", credential("CERT-E", null, "2030-01-01", true)), s, "invalid-expiry").statusCode());
        assertEquals(422, post("/staff/staff-members", "{\"name\":\"未知字段\",\"staff_type\":\"其他人员\",\"job_title\":\"员工\",\"verified_by\":\"1\"}", s, "unknown-field").statusCode());
        assertEquals(422, post("/staff/staff-members", "{\"name\":\"" + "太".repeat(61) + "\",\"staff_type\":\"其他人员\",\"job_title\":\"员工\"}", s, "too-long").statusCode());
        assertEquals(0, count("SELECT count(*) FROM staff WHERE practice_no IN ('BAD-DATE','BAD-EXPIRY') OR name IN ('无执业','未知字段','" + "太".repeat(61) + "')"));
    }

    @Test
    void detailShowsCredentialStateAndDateEffectivenessWithoutCertificate() throws Exception {
        var s = session(1, STAFF);
        LocalDate today = LocalDate.now(SHANGHAI);
        long effectiveId = jdbc.queryForObject("""
            INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed)
            VALUES(5,'dentist_practice','TODAY-CERT',?,?,1,now(),'verified',false) RETURNING credential_id
            """, Long.class, java.sql.Date.valueOf(today.minusDays(1)), java.sql.Date.valueOf(today));
        long expiredId = jdbc.queryForObject("""
            INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed)
            VALUES(5,'other','PAST-CERT',?,?,1,now(),'verified',false) RETURNING credential_id
            """, Long.class, java.sql.Date.valueOf(today.minusDays(3)), java.sql.Date.valueOf(today.minusDays(1)));
        var r = request("GET", "/staff/staff-members/5", s);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(effectiveFor(r.body(), effectiveId), r.body());
        assertFalse(effectiveFor(r.body(), expiredId), r.body());
        assertFalse(r.body().contains("TODAY-CERT"), r.body());
        assertFalse(r.body().contains("PAST-CERT"), r.body());
    }

    private boolean effectiveFor(String body, long credentialId) {
        Matcher m = Pattern.compile("\"credential_id\":\"" + credentialId + "\".*?\"effective\":(true|false)", Pattern.DOTALL)
            .matcher(body);
        assertTrue(m.find(), "credential " + credentialId + " in " + body);
        return "true".equals(m.group(1));
    }

    @Test
    void invalidCursorLimitAndUnknownDetailReturnExpectedErrors() throws Exception {
        var s = session(1, STAFF);
        assertEquals(422, request("GET", "/staff/staff-members?cursor=bad", s).statusCode());
        assertEquals(422, request("GET", "/staff/staff-members?limit=0", s).statusCode());
        assertEquals(422, request("GET", "/staff/staff-members?limit=101", s).statusCode());
        assertEquals(404, request("GET", "/staff/staff-members/999999", s).statusCode());
        assertEquals(404, request("GET", "/staff/staff-members/5", session(4, STAFF)).statusCode());
    }

    @Test
    void auditFailureRollsBackStaffAndCredentialAndSameKeyCanRecover() throws Exception {
        var s = session(1, STAFF);
        String body = body("回滚牙医", "牙医", "牙医", "ROLLBACK-P", credential("ROLLBACK-CERT", null, null, false));
        jdbc.update("UPDATE staff_fault SET fail=true");
        assertEquals(500, post("/staff/staff-members", body, s, "staff-rollback-key").statusCode());
        assertEquals(0, count("SELECT count(*) FROM staff WHERE practice_no='ROLLBACK-P'"));
        assertEquals(0, count("SELECT count(*) FROM credential WHERE certificate_no='ROLLBACK-CERT'"));
        assertEquals(0, count("SELECT count(*) FROM audit_log"));
        assertEquals(0, count("SELECT count(*) FROM operation_request"));

        jdbc.update("UPDATE staff_fault SET fail=false");
        var retry = post("/staff/staff-members", body, s, "staff-rollback-key");
        assertEquals(201, retry.statusCode(), retry.body());
        assertEquals(1, count("SELECT count(*) FROM staff WHERE practice_no='ROLLBACK-P'"));
        assertEquals(2, count("SELECT count(*) FROM audit_log"));
        assertEquals(1, count("SELECT count(*) FROM operation_request"));
    }

    @Test
    void organizationAmbiguityAndOwnerMismatchAreRejected() throws Exception {
        var s = session(1, STAFF);
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(2,'第二主体','TEST-ORG-2','00000000000',2,'营业')");
        assertEquals(409, post("/staff/staff-members", body("多主体员工", "其他人员", "员工", null, null), s, "staff-multi-org").statusCode());
        jdbc.update("DELETE FROM organization WHERE organization_id=2");
        jdbc.update("UPDATE organization SET responsible_staff_id=2 WHERE organization_id=1");
        assertEquals(403, post("/staff/staff-members", body("非负责人员工", "其他人员", "员工", null, null), s, "staff-not-owner").statusCode());
        jdbc.update("UPDATE organization SET responsible_staff_id=1 WHERE organization_id=1");
        jdbc.update("UPDATE organization SET state='停用' WHERE organization_id=1");
        assertEquals(403, post("/staff/staff-members", body("停用主体员工", "其他人员", "员工", null, null), s, "staff-org-disabled").statusCode());
        jdbc.update("UPDATE organization SET state='营业' WHERE organization_id=1");
        assertEquals(201, post("/staff/staff-members", body("恢复负责人员工", "其他人员", "员工", null, null), session(1, STAFF), "staff-owner-restored").statusCode());
    }

    @Test
    void staffListRequiresCompleteRoleScopeAndAssignment() throws Exception {
        var s = session(2, STAFF);
        // Remove the actor's own active assignment for branch 11; role scope alone must not authorize.
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=2 AND branch_id=11");
        assertEquals(403, request("GET", "/staff/staff-members", s).statusCode());
        jdbc.update("UPDATE staff_branch SET enabled=true,verified_at=now() WHERE staff_id=2 AND branch_id=11");
        assertEquals(200, request("GET", "/staff/staff-members", s).statusCode());
    }

    @Test
    void permissionsAndScopesFromDifferentRolesAreNotStitched() throws Exception {
        var s = session(2, STAFF);
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=2 AND role_id=(SELECT role_id FROM role WHERE code='owner')");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 2,role_id,11 FROM role WHERE code='frontdesk'");
        assertEquals(403, request("GET", "/staff/staff-members", s).statusCode());
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=2 AND role_id=(SELECT role_id FROM role WHERE code='frontdesk')");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 2,role_id,11 FROM role WHERE code='owner'");
    }

    @Test
    void missingOrganizationRejectsGlobalCreate() throws Exception {
        var s = session(1, STAFF);
        try {
            jdbc.update("DELETE FROM role_branch_scope");
            jdbc.update("DELETE FROM staff_branch");
            jdbc.update("DELETE FROM branch");
            jdbc.update("DELETE FROM organization");
            assertEquals(422, post("/staff/staff-members", body("无组织员工", "其他人员", "员工", null, null),
                s, "staff-no-organization").statusCode());
        } finally {
            jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
            jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES"
                + "(10,1,'TEST-A','合成门店A','地址A','00000000000','LIC-A','09:00-18:00',1,'营业',1,now()),"
                + "(11,1,'TEST-B','合成门店B','地址B','00000000000','LIC-B','09:00-18:00',1,'营业',1,now())");
            jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES"
                + "(2,10,true,now()),(2,11,true,now()),(5,10,true,now()),(5,11,true,now()),(4,10,true,now()),(7,10,true,now()),(7,11,true,now())");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,10 FROM role WHERE code='owner'");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,11 FROM role WHERE code='owner'");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 2,role_id,11 FROM role WHERE code='owner'");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 4,role_id,10 FROM role WHERE code='frontdesk'");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 7,role_id,10 FROM role WHERE code='owner'");
            jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 7,role_id,11 FROM role WHERE code='owner'");
        }
    }

    @Test
    void ownerChangeWhileWaitingForOrganizationLockIsRejectedWithoutWrite() throws Exception {
        var s = session(1, STAFF);
        var pool = Executors.newSingleThreadExecutor();
        var lock = PostgresTestSupport.connect();
        Future<HttpResponse<String>> future = null;
        try {
            lock.setAutoCommit(false);
            try (var st = lock.createStatement()) {
                st.execute("SET search_path TO " + schema + ",public");
                st.execute("SELECT organization_id FROM organization WHERE organization_id=1 FOR UPDATE");
            }
            future = pool.submit(() -> post("/staff/staff-members",
                body("等锁员工", "其他人员", "员工", null, null), s, "staff-wait-owner-change"));
            boolean waiting = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type='Lock' AND query LIKE '%FROM organization%'
                    """, Integer.class);
                if (count != null && count > 0) {
                    waiting = true;
                    break;
                }
                Thread.yield();
            }
            assertTrue(waiting, "staff create did not wait for organization row lock");
            try (var st = lock.createStatement()) {
                st.execute("UPDATE organization SET responsible_staff_id=2 WHERE organization_id=1");
                lock.commit();
            }
            var response = future.get(15, TimeUnit.SECONDS);
            assertEquals(403, response.statusCode(), response.body());
            assertEquals(0, count("SELECT count(*) FROM staff WHERE name='等锁员工'"));
        } finally {
            if (future != null) future.cancel(true);
            pool.shutdownNow();
            try {
                if (!lock.getAutoCommit()) lock.rollback();
            } finally {
                lock.close();
                jdbc.update("UPDATE organization SET responsible_staff_id=1 WHERE organization_id=1");
            }
        }
    }
}
