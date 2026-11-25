package cn.dental.identity;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.PatientBindingExecutor;
import cn.dental.identity.application.PatientBindingThrottle;
import cn.dental.identity.application.PatientBindingTokenService;
import cn.dental.identity.application.PatientRegisterThrottle;
import cn.dental.identity.application.PatientRegistrationChallengeService;
import cn.dental.identity.application.SessionService;
import cn.dental.identity.integration.LocalWechatMock;
import cn.dental.shared.application.AuditService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.support.ConcurrentRunner;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientBindingIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;
    private JdbcTemplate jdbc;
    private JsonMapper json;
    private PatientBindingThrottle bindingThrottle;
    private PatientRegisterThrottle registerThrottle;
    private PatientRegistrationChallengeService challenges;
    private AuditService audit;
    private Clock clock;
    private PlatformTransactionManager tx;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    @BeforeAll
    void start() throws Exception {
        var env = System.getenv();
        PostgresTestSupport.validateTarget(env);
        String schema = "patientbind_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = PostgresTestSupport.connect(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        app = new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local", "--spring.main.banner-mode=off", "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + env.get("TEST_DB_URL") + "?currentSchema=" + schema + ",public",
            "--spring.datasource.username=" + env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--app.mock.wechat-enabled=true", "--app.seed.enabled=false");
        sessions = app.getBean(SessionService.class);
        jdbc = app.getBean(JdbcTemplate.class);
        json = app.getBean(JsonMapper.class);
        bindingThrottle = app.getBean(PatientBindingThrottle.class);
        registerThrottle = app.getBean(PatientRegisterThrottle.class);
        challenges = app.getBean(PatientRegistrationChallengeService.class);
        audit = app.getBean(AuditService.class);
        clock = app.getBean(Clock.class);
        tx = app.getBean(PlatformTransactionManager.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @BeforeEach
    void reset() {
        bindingThrottle.reset();
        registerThrottle.reset();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM identity_challenge");
        jdbc.update("DELETE FROM wechat_identity");
        jdbc.update("DELETE FROM role_branch_scope");
        jdbc.update("DELETE FROM account_role");
        jdbc.update("DELETE FROM staff_branch");
        jdbc.update("DELETE FROM account");
        jdbc.update("DELETE FROM patient");
        jdbc.update("DELETE FROM branch");
        jdbc.update("DELETE FROM organization");
        jdbc.update("DELETE FROM staff");
        fixture();
    }

    private void fixture() {
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成前台','其他人员','前台','在岗'),(2,'合成无角色员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(1,1,'TEST-B1','合成门店','合成地址','00000000000','TEST-L1','09:00-18:00',1,'营业',1,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'syn-frontdesk','NOT-A-LOGIN-HASH',true,false,now()),(2,2,'syn-norole','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,1,true,now())");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 1,role_id,now(),'合成批准依据' FROM role WHERE code='frontdesk'");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,1 FROM role WHERE code='frontdesk'");
        // 1=未绑定(无账号)、2=未绑定(无账号)、3=已绑定、4=账号停用但未绑定微信
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成待绑定患者','13800009999','无已知'),(2,'合成重签患者','13800009998','无已知'),(3,'合成已绑定患者','13800009997','无已知'),(4,'合成停用患者','13800009996','无已知')");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(3,3,true,false,now()),(4,4,false,false,now())");
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(3,'mock','mock-openid-bound-fixture',now())");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient))", Long.class);
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account))", Long.class);
    }

    // ---- helpers ----

    private String token() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private SessionService.Issued staffSession(long accountId) {
        return sessions.issue(accountId, SessionService.STAFF);
    }

    private String registrationToken(String openId) {
        String t = token();
        jdbc.update("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,app_id,open_id,expires_at,failed_attempts,created_at) VALUES(?,'wechat_registration',?,?,?,?,0,?)",
            UUID.randomUUID(), SessionService.hash(t), "mock", openId, Timestamp.from(Instant.now().plusSeconds(600)), Timestamp.from(Instant.now()));
        return t;
    }

    private String wechatRegistrationToken() throws Exception {
        var resp = post("/auth/wechat", "{\"code\":\"" + LocalWechatMock.UNBOUND_CODE + "\"}");
        assertEquals(200, resp.statusCode(), resp.body());
        return json.readTree(resp.body()).get("data").get("registration_token").asText();
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> staffPost(String path, SessionService.Issued session, String idempotencyKey, String jsonBody, boolean withCsrf) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Cookie", "A_CLINIC_SESSION=" + session.token())
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (withCsrf) b.header("X-CSRF-Token", session.csrfToken());
        if (idempotencyKey != null) b.header("Idempotency-Key", idempotencyKey);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String issueBody(String name, String phone, String method, boolean inPerson) {
        return "{\"branch_id\":\"1\",\"patient_name\":\"" + name + "\",\"patient_phone\":\"" + phone + "\",\"verification_method\":\"" + method + "\",\"in_person\":" + inPerson + "}";
    }

    private String reissueBody(String name, String phone, String method, String previousRef) {
        return "{\"branch_id\":\"1\",\"patient_name\":\"" + name + "\",\"patient_phone\":\"" + phone + "\",\"verification_method\":\"" + method + "\",\"in_person\":true,\"previous_challenge_ref\":\"" + previousRef + "\"}";
    }

    private JsonNode issue(long patientId, String idempotencyKey) throws Exception {
        return issueAs(patientId, "合成待绑定患者", "13800009999", idempotencyKey);
    }

    private JsonNode issueAs(long patientId, String name, String phone, String idempotencyKey) throws Exception {
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/" + patientId + "/binding-token", s, idempotencyKey, issueBody(name, phone, "id_document", true), true);
        assertEquals(200, resp.statusCode(), resp.body());
        return json.readTree(resp.body()).get("data");
    }

    private SessionService.Principal staffActor(long accountId) {
        var issued = staffSession(accountId);
        return sessions.authenticate(issued.token(), SessionService.STAFF);
    }

    private HttpResponse<String> bind(String registration, String binding) throws Exception {
        return post("/auth/patient-bind", "{\"registration_token\":\"" + registration + "\",\"binding_token\":\"" + binding + "\"}");
    }

    // ---- 签发 + 绑定 ----

    @Test
    void issueAndBindHappyPath() throws Exception {
        var issued = issue(1, "happy-issue-1");
        String bindingToken = issued.get("binding_token").asText();
        assertFalse(issued.get("replayed").asBoolean());
        assertEquals("active", issued.get("status").asText());
        assertDoesNotThrow(() -> UUID.fromString(issued.get("challenge_ref").asText()));

        String reg = wechatRegistrationToken();
        var resp = bind(reg, bindingToken);
        assertEquals(200, resp.statusCode(), resp.body());
        var data = json.readTree(resp.body()).get("data");
        long patientId = Long.parseLong(data.get("patient_id").asText());
        assertEquals(1, patientId);
        assertFalse(resp.body().contains("openid"));
        assertFalse(resp.body().contains("open_id"));

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id=?", Integer.class, LocalWechatMock.UNBOUND_OPEN_ID));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(bindingToken)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(reg)));
    }

    @Test
    void issueReplayReturnsNullTokenSameRef() throws Exception {
        var first = issue(1, "replay-key-1");
        assertFalse(first.get("replayed").asBoolean());
        assertTrue(first.get("binding_token").asText().length() >= 43);

        var second = issue(1, "replay-key-1");
        assertTrue(second.get("replayed").asBoolean());
        assertTrue(second.get("binding_token").isNull());
        assertEquals(first.get("challenge_ref").asText(), second.get("challenge_ref").asText());
        assertEquals("active", second.get("status").asText());
    }

    @Test
    void sameKeyDifferentContentConflicts() throws Exception {
        issue(1, "conflict-key-1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "conflict-key-1", issueBody("合成待绑定患者", "13800009999", "visit_evidence", true), true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("IDEMPOTENCY_CONFLICT"));
    }

    // ---- 重签 ----

    @Test
    void reissueRevokesOldAndNewWorks() throws Exception {
        var first = issue(1, "reissue-issue-1");
        String oldToken = first.get("binding_token").asText();
        String oldRef = first.get("challenge_ref").asText();

        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token/reissue", s, "reissue-key-1", reissueBody("合成待绑定患者", "13800009999", "id_document", oldRef), true);
        assertEquals(200, resp.statusCode(), resp.body());
        var second = json.readTree(resp.body()).get("data");
        assertFalse(second.get("replayed").asBoolean());
        String newToken = second.get("binding_token").asText();
        String newRef = second.get("challenge_ref").asText();
        assertNotEquals(oldRef, newRef);

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE challenge_id=? AND revoked_at IS NOT NULL", Integer.class, UUID.fromString(oldRef)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE challenge_id=? AND revoked_at IS NULL AND consumed_at IS NULL", Integer.class, UUID.fromString(newRef)));

        String reg = wechatRegistrationToken();
        assertEquals(401, bind(reg, oldToken).statusCode()); // 旧凭证已被撤销
        assertEquals(200, bind(reg, newToken).statusCode());   // 新凭证可用（消费后旧 openid 已绑定）
    }

    @Test
    void staleReissueRejected() throws Exception {
        issue(1, "stale-issue-1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token/reissue", s, "stale-key-1", reissueBody("合成待绑定患者", "13800009999", "id_document", UUID.randomUUID().toString()), true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("STALE_BINDING_REISSUE"));
    }

    @Test
    void bindThenReissueIsStale() throws Exception {
        var issued = issue(1, "bind-then-reissue-issue");
        String bindingToken = issued.get("binding_token").asText();
        String ref = issued.get("challenge_ref").asText();
        String reg = wechatRegistrationToken();
        assertEquals(200, bind(reg, bindingToken).statusCode());

        // 绑定已消费凭证；重签引用已消费凭证 → 过时 409（或已绑定 409）。
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token/reissue", s, "bind-then-reissue-key", reissueBody("合成待绑定患者", "13800009999", "id_document", ref), true);
        assertEquals(409, resp.statusCode(), resp.body());
    }

    // ---- 活跃凭证 / 已绑定 ----

    @Test
    void secondIssueWhileActiveRejected() throws Exception {
        issue(1, "active-key-a");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "active-key-b", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("BINDING_TOKEN_ALREADY_ACTIVE"));
    }

    @Test
    void alreadyBoundPatientIssueRejected() throws Exception {
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/3/binding-token", s, "bound-key-1", issueBody("合成已绑定患者", "13800009997", "id_document", true), true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("PATIENT_ALREADY_BOUND"));
    }

    @Test
    void disabledPatientAccountBindRejected() throws Exception {
        // 停用账号但未绑定微信：签发照常（不重绑/不启用），绑定落账时 403。
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/4/binding-token", s, "disabled-key-1", issueBody("合成停用患者", "13800009996", "id_document", true), true);
        assertEquals(200, resp.statusCode(), resp.body());
        String bindingToken = json.readTree(resp.body()).get("data").get("binding_token").asText();
        String reg = registrationToken("disabled-openid");
        var bound = bind(reg, bindingToken);
        assertEquals(403, bound.statusCode(), bound.body());
        assertTrue(bound.body().contains("PATIENT_ACCOUNT_DISABLED"));
        assertEquals(false, jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=4", Boolean.class));
    }

    // ---- 核验依据 / 权限 / CSRF ----

    @Test
    void basisMismatchRejected() throws Exception {
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "basis-key-1", issueBody("合成待绑定患者", "13800000000", "id_document", true), true);
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("BUSINESS_BASIS_REQUIRED"));
    }

    @Test
    void inPersonRequiredRejected() throws Exception {
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "inperson-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", false), true);
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("BUSINESS_BASIS_REQUIRED"));
    }

    @Test
    void appointmentMismatchRejected() throws Exception {
        var s = staffSession(1);
        String body = "{\"branch_id\":\"1\",\"patient_name\":\"合成待绑定患者\",\"patient_phone\":\"13800009999\",\"appointment_id\":\"999\",\"verification_method\":\"id_document\",\"in_person\":true}";
        var resp = staffPost("/staff/patients/1/binding-token", s, "appt-key-1", body, true);
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("BUSINESS_BASIS_REQUIRED"));
    }

    @Test
    void missingCsrfRejected() throws Exception {
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "csrf-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", true), false);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void staffWithoutRoleForbidden() throws Exception {
        var s = staffSession(2);
        var resp = staffPost("/staff/patients/1/binding-token", s, "norole-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void patientSessionForbidden() throws Exception {
        var s = sessions.issue(3, SessionService.PATIENT);
        var b = HttpRequest.newBuilder(URI.create(base + "/staff/patients/1/binding-token")).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + s.token())
            .POST(HttpRequest.BodyPublishers.ofString(issueBody("合成待绑定患者", "13800009999", "id_document", true)))
            .build();
        var resp = client.send(b, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode(), resp.body());
    }

    // ---- 过期/耗尽待用记录被撤销后重签 ----

    @Test
    void exhaustedCredentialRevokedBeforeIssue() throws Exception {
        jdbc.update("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(?,'patient_binding',?,?,?,1,?,5,?)",
            UUID.randomUUID(), SessionService.hash(token()), 1L, 1L, Timestamp.from(Instant.now().plusSeconds(600)), Timestamp.from(Instant.now()));
        var issued = issue(1, "exhausted-key-1");
        assertEquals("active", issued.get("status").asText());
        assertTrue(issued.get("binding_token").asText().length() >= 43);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE patient_id=1 AND purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL AND failed_attempts>=5", Integer.class));
    }

    @Test
    void expiredCredentialRevokedBeforeIssue() throws Exception {
        jdbc.update("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(?,'patient_binding',?,?,?,1,?,0,?)",
            UUID.randomUUID(), SessionService.hash(token()), 1L, 1L, Timestamp.from(Instant.now().minusSeconds(60)), Timestamp.from(Instant.now().minusSeconds(600)));
        var issued = issue(1, "expired-key-1");
        assertEquals("active", issued.get("status").asText());
    }

    // ---- 并发 ----

    @Test
    void differentWechatSamePatientConcurrentSingleBind() throws Exception {
        // 同一绑定凭证 + 两个不同微信：恰好一个绑定成功，另一个看到凭证已消费（401）。
        var issued = issue(1, "conc-issue");
        String bindingToken = issued.get("binding_token").asText();
        String regA = registrationToken("conc-patient-openid-a");
        String regB = registrationToken("conc-patient-openid-b");

        var idx = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            int i = idx.getAndIncrement();
            try {
                return bind(i == 0 ? regA : regB, bindingToken).statusCode();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(1, results.stream().filter(c -> c == 200).count(), results.toString());
        assertEquals(1, results.stream().filter(c -> c == 401).count(), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id IN (?,?)", Integer.class, "conc-patient-openid-a", "conc-patient-openid-b"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=1", Integer.class));
    }

    @Test
    void bindingVsReissueConcurrentExactlyOneWinner() throws Exception {
        var issued = issue(1, "race-issue-key");
        String bindingToken = issued.get("binding_token").asText();
        String ref = issued.get("challenge_ref").asText();
        String reg = registrationToken("race-openid");

        var idx = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(25), () -> {
            int i = idx.getAndIncrement();
            if (i == 0) return bind(reg, bindingToken).statusCode();
            var s = staffSession(1);
            try {
                return staffPost("/staff/patients/1/binding-token/reissue", s, "race-reissue-key", reissueBody("合成待绑定患者", "13800009999", "id_document", ref), true).statusCode();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(1, results.stream().filter(c -> c == 200).count(), results.toString());
        assertTrue(results.stream().filter(c -> c != 200).allMatch(c -> c == 401 || c == 409), results.toString());
    }

    // ---- 双凭证消费回滚 ----

    @Test
    void injectedSessionFailureRollsBackBindingAtomically() throws Exception {
        var issued = issue(1, "rollback-issue-1");
        String bindingToken = issued.get("binding_token").asText();
        String reg = registrationToken("rollback-openid");
        var regCtx = challenges.find(SessionService.hash(reg));
        var bindCtx = challenges.find(SessionService.hash(bindingToken));
        assertNotNull(regCtx);
        assertNotNull(bindCtx);

        SessionService failingSessions = Mockito.mock(SessionService.class);
        Mockito.when(failingSessions.issue(Mockito.anyLong(), Mockito.eq(SessionService.PATIENT)))
            .thenThrow(new ApiException(ApiErrorCode.INTERNAL_ERROR));
        var executor = new PatientBindingExecutor(jdbc, failingSessions, audit, clock);

        var e = assertThrows(ApiException.class, () -> new TransactionTemplate(tx).execute(status -> {
            executor.execute(regCtx, bindCtx);
            return null;
        }));
        assertEquals(ApiErrorCode.INTERNAL_ERROR, e.code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='rollback-openid'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash IN (?,?) AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(reg), SessionService.hash(bindingToken)));
    }

    // ---- 幂等作用域含患者 / 跨患者与跨门店重放 ----

    @Test
    void sameKeySameNamePhoneTwoPatientsConflict() throws Exception {
        // 同名同电话的第二位患者：同键但 patient_id 不同，不得重放第一位患者的结果。
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(5,'合成待绑定患者','13800009999','无已知')");
        issue(1, "same-name-key");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/5/binding-token", s, "same-name-key", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void crossBranchReplayRejected() throws Exception {
        // 员工1同时具备门店2前台权限：门店1签发后，跨门店同键重放应被拒绝（branch_id 在幂等摘要内）。
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(2,1,'TEST-B2','合成门店二','合成地址','00000000000','TEST-L2','09:00-18:00',1,'营业',1,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,2,true,now())");
        jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT 1,role_id,2 FROM role WHERE code='frontdesk'");
        issue(1, "cross-branch-key");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "cross-branch-key",
            "{\"branch_id\":\"2\",\"patient_name\":\"合成待绑定患者\",\"patient_phone\":\"13800009999\",\"verification_method\":\"id_document\",\"in_person\":true}", true);
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("IDEMPOTENCY_CONFLICT"));
    }

    // ---- 门店营业 / 核验 / 任职 / 撤权（签发重签专用业务鉴权）----

    @Test
    void closedBranchIssueRejected() throws Exception {
        jdbc.update("UPDATE branch SET state='停用' WHERE branch_id=1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "closed-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void unverifiedBranchIssueRejected() throws Exception {
        // 未核验门店：state='待核验' 且 verified_at 为空（满足 ck_branch_verified）。
        jdbc.update("UPDATE branch SET state='待核验', verified_by=NULL, verified_at=NULL WHERE branch_id=1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "unverified-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void disabledEmploymentIssueRejected() throws Exception {
        // 无效任职：staff_branch.enabled=false。
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=1 AND branch_id=1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "disemp-key-1", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void sameRoleDifferentBranchNotSpliced() throws Exception {
        // 同角色门店不能拼接：前台角色只授权门店1，门店2虽存在且营业，也不能借门店1的范围授权门店2。
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(2,1,'TEST-B2','合成门店二','合成地址','00000000000','TEST-L2','09:00-18:00',1,'营业',1,now())");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "splice-key-1",
            "{\"branch_id\":\"2\",\"patient_name\":\"合成待绑定患者\",\"patient_phone\":\"13800009999\",\"verification_method\":\"id_document\",\"in_person\":true}", true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    @Test
    void revokedThenReplayRejected() throws Exception {
        issue(1, "revoke-replay-key");
        // 撤权：删除前台在门店1的角色范围后，同键重放必须重新鉴权而被拒绝。
        jdbc.update("DELETE FROM role_branch_scope WHERE account_id=1");
        var s = staffSession(1);
        var resp = staffPost("/staff/patients/1/binding-token", s, "revoke-replay-key", issueBody("合成待绑定患者", "13800009999", "id_document", true), true);
        assertEquals(403, resp.statusCode(), resp.body());
    }

    // ---- 脱敏 ----

    @Test
    void issueDoesNotPersistPlaintextAndResultToStringRedacted() throws Exception {
        var actor = staffActor(1);
        var service = app.getBean(PatientBindingTokenService.class);
        var result = service.issue(actor, 1L, 1L, "合成待绑定患者", "13800009999", null, "id_document", true, "redact-key-1");
        String token = (String) result.data().get("binding_token");
        assertTrue(token.length() >= 43);
        // IssueResult.toString 固定脱敏，不含明文令牌。
        assertTrue(result.toString().contains("redacted"));
        assertFalse(result.toString().contains(token));
        // 审计 before/after 与幂等 request_hash 均不得出现凭证明文或姓名电话。
        for (var row : jdbc.queryForList("SELECT before_data,after_data FROM audit_log")) {
            for (var s : new String[]{(String) row.get("before_data"), (String) row.get("after_data")}) {
                if (s == null) continue;
                assertFalse(s.contains(token), "audit 泄漏令牌");
                assertFalse(s.contains("合成待绑定患者"), "audit 泄漏姓名");
                assertFalse(s.contains("13800009999"), "audit 泄漏电话");
            }
        }
        String hash = jdbc.queryForObject("SELECT request_hash FROM operation_request WHERE request_key='redact-key-1'", String.class);
        assertFalse(hash.contains("合成待绑定患者"));
        assertFalse(hash.contains("13800009999"));
        assertFalse(hash.contains(token));
    }

    // ---- 同微信注册对绑定 / 同凭证并发消费 ----

    @Test
    void sameWechatRegistrationConcurrentSingleBind() throws Exception {
        // 同一微信注册凭证（同一 openid）对两个不同患者并发绑定：openid 只能绑定一次。
        String b1 = issue(1, "same-reg-issue-1").get("binding_token").asText();
        String b2 = issueAs(2, "合成重签患者", "13800009998", "same-reg-issue-2").get("binding_token").asText();
        String reg = registrationToken("same-reg-openid");

        var idx = new java.util.concurrent.atomic.AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            int i = idx.getAndIncrement();
            try { return bind(reg, i == 0 ? b1 : b2).statusCode(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(1, results.stream().filter(c -> c == 200).count(), results.toString());
        assertEquals(1, results.stream().filter(c -> c == 401).count(), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='same-reg-openid'", Integer.class));
    }

    @Test
    void sameBindingCredentialConcurrentConsume() throws Exception {
        // 同一绑定凭证 + 同一注册凭证（完全相同的一对）并发提交：恰一个消费成功，另一个看到已消费。
        String bindingToken = issue(1, "same-pair-issue").get("binding_token").asText();
        String reg = registrationToken("same-pair-openid");
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            try { return bind(reg, bindingToken).statusCode(); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(1, results.stream().filter(c -> c == 200).count(), results.toString());
        assertEquals(1, results.stream().filter(c -> c == 401).count(), results.toString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='same-pair-openid'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=1", Integer.class));
    }

    // ---- 停用与绑定竞争（独立事务夹具，不声称已测生产患者停用接口）----

    @Test
    void disableVsBindConcurrentDisableFirstRejected() throws Exception {
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(5,'合成竞争患者','13800008888','无已知')");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(5,5,true,false,now())");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient))", Long.class);
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account))", Long.class);
        String bindingToken = issueAs(5, "合成竞争患者", "13800008888", "disable-race-issue").get("binding_token").asText();
        String reg = registrationToken("disable-race-openid");

        // 独立事务夹具：显式 BEGIN → 锁 account 行 FOR UPDATE → 停用 → COMMIT。仅验证并发停用竞争，不声称已测生产患者停用接口。
        var disableHoldsLock = new CountDownLatch(1);
        var allowDisableCommit = new CountDownLatch(1);
        var disableError = new AtomicReference<Throwable>();
        Thread disabler = new Thread(() -> {
            try {
                new TransactionTemplate(tx).execute(status -> {
                    jdbc.queryForObject("SELECT account_id FROM account WHERE account_id=5 FOR UPDATE", Long.class);
                    disableHoldsLock.countDown();
                    try { if (!allowDisableCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    jdbc.update("UPDATE account SET enabled=false WHERE account_id=5");
                    return null;
                });
            } catch (Throwable t) { disableError.set(t); }
        });
        disabler.start();
        assertTrue(disableHoldsLock.await(10, TimeUnit.SECONDS));

        var bindStatus = new AtomicReference<Integer>();
        Thread binder = new Thread(() -> {
            try { bindStatus.set(bind(reg, bindingToken).statusCode()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        binder.start();
        Thread.sleep(500); // 让绑定到达 account 行锁并阻塞
        allowDisableCommit.countDown();
        disabler.join(15000);
        assertNull(disableError.get(), String.valueOf(disableError.get()));
        binder.join(15000);
        assertEquals(403, bindStatus.get().intValue());
        assertEquals(false, jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=5", Boolean.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='disable-race-openid'", Integer.class));
    }

    // ---- 重签 vs 绑定：屏障确定两种提交顺序 ----

    @Test
    void reissueFirstThenBindFails() throws Exception {
        var issued = issue(1, "reissue-first-issue");
        String oldToken = issued.get("binding_token").asText();
        String ref = issued.get("challenge_ref").asText();
        String reg = registrationToken("reissue-first-openid");
        var service = app.getBean(PatientBindingTokenService.class);
        var actor = staffActor(1);

        var reissueWorkDone = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var reissueError = new AtomicReference<Throwable>();
        Thread reissueThread = new Thread(() -> {
            try {
                new TransactionTemplate(tx).execute(status -> {
                    service.reissue(actor, 1L, 1L, "合成待绑定患者", "13800009999", null, "id_document", true, ref, "reissue-first-key");
                    reissueWorkDone.countDown();
                    try { if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return null;
                });
            } catch (Throwable t) { reissueError.set(t); }
        });
        reissueThread.start();
        assertTrue(reissueWorkDone.await(10, TimeUnit.SECONDS));

        var bindStatus = new AtomicReference<Integer>();
        Thread binder = new Thread(() -> {
            try { bindStatus.set(bind(reg, oldToken).statusCode()); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        binder.start();
        Thread.sleep(500); // 绑定在患者锁上阻塞
        allowCommit.countDown();
        reissueThread.join(15000);
        assertNull(reissueError.get(), String.valueOf(reissueError.get()));
        binder.join(15000);
        assertEquals(401, bindStatus.get().intValue()); // 旧凭证已被重签撤销
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND revoked_at IS NOT NULL", Integer.class, SessionService.hash(oldToken)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(reg)));
    }

    @Test
    void bindFirstThenReissueRejected() throws Exception {
        var issued = issue(1, "bind-first-issue");
        String bindingToken = issued.get("binding_token").asText();
        String ref = issued.get("challenge_ref").asText();
        String reg = registrationToken("bind-first-openid");
        var regCtx = challenges.find(SessionService.hash(reg));
        var bindCtx = challenges.find(SessionService.hash(bindingToken));
        var executor = app.getBean(PatientBindingExecutor.class);
        var service = app.getBean(PatientBindingTokenService.class);
        var actor = staffActor(1);

        var bindWorkDone = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var bindError = new AtomicReference<Throwable>();
        Thread bindThread = new Thread(() -> {
            try {
                new TransactionTemplate(tx).execute(status -> {
                    executor.execute(regCtx, bindCtx);
                    bindWorkDone.countDown();
                    try { if (!allowCommit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                    return null;
                });
            } catch (Throwable t) { bindError.set(t); }
        });
        bindThread.start();
        assertTrue(bindWorkDone.await(10, TimeUnit.SECONDS));

        var reissueStatus = new AtomicReference<Integer>();
        var reissueError = new AtomicReference<Throwable>();
        Thread reissueThread = new Thread(() -> {
            try {
                service.reissue(actor, 1L, 1L, "合成待绑定患者", "13800009999", null, "id_document", true, ref, "bind-first-reissue-key");
                reissueStatus.set(200);
            } catch (ApiException e) { reissueStatus.set(e.code().status); }
            catch (Throwable t) { reissueError.set(t); }
        });
        reissueThread.start();
        Thread.sleep(500); // 重签在患者锁上阻塞
        allowCommit.countDown();
        bindThread.join(15000);
        assertNull(bindError.get(), String.valueOf(bindError.get()));
        reissueThread.join(15000);
        assertNull(reissueError.get(), String.valueOf(reissueError.get()));
        assertEquals(409, reissueStatus.get().intValue()); // 绑定已消费凭证并绑定 → 重签被拒绝
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='bind-first-openid'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash IN (?,?) AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(reg), SessionService.hash(bindingToken)));
    }
}
