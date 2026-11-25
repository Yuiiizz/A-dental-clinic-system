package cn.dental.identity;

import cn.dental.AdenDentalApplication;
import cn.dental.shared.application.AuditService;
import cn.dental.identity.application.PatientRegistrationChallengeService;
import cn.dental.identity.application.PatientRegistrationExecutor;
import cn.dental.identity.application.PatientRegistrationService;
import cn.dental.identity.application.PatientRegisterThrottle;
import cn.dental.identity.application.SessionService;
import cn.dental.identity.application.WechatLoginThrottle;
import cn.dental.identity.integration.LocalWechatMock;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatientRegistrationIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;
    private JdbcTemplate jdbc;
    private JsonMapper json;
    private PatientRegisterThrottle throttle;
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
        String schema = "patientreg_" + UUID.randomUUID().toString().replace("-", "");
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
        throttle = app.getBean(PatientRegisterThrottle.class);
        challenges = app.getBean(PatientRegistrationChallengeService.class);
        audit = app.getBean(AuditService.class);
        clock = app.getBean(Clock.class);
        tx = app.getBean(PlatformTransactionManager.class);
        base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";

        // 合成员工（供员工接口跨端隔离与 patient_binding 上下文 issued_by）。
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'syn-staff','NOT-A-LOGIN-HASH',true,false,now())");
        // 已绑定患者（启用）。
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成绑定患者','10000000001','无已知')");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(2,1,true,false,now())");
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(2,'mock','bound-fixture-openid',now())");
        // 已绑定患者（停用账号）。
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(2,'合成停用患者','10000000002','无已知')");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(3,2,false,false,now())");
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(3,'mock','disabled-fixture-openid',now())");
        // 仅手机号重复、无账号的患者（转人工依据）。
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(3,'合成重复手机患者','13900000000','无已知')");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient))", Long.class);
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account))", Long.class);
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @BeforeEach
    void reset() {
        throttle.reset(); // 注册入口预算独立计数，逐用例清空避免跨用例共享
        jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM identity_challenge");
        jdbc.update("DELETE FROM wechat_identity WHERE account_id NOT IN (2,3)");
        jdbc.update("DELETE FROM account WHERE account_id NOT IN (1,2,3)");
        jdbc.update("DELETE FROM patient WHERE patient_id NOT IN (1,2,3)");
        jdbc.update("UPDATE account SET enabled=true WHERE account_id IN (1,2)");
        jdbc.update("UPDATE account SET enabled=false WHERE account_id=3");
    }

    // ---- helpers ----

    private String token() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String insertChallenge(String purpose, String appId, String openId, Long patientId, Long issuedBy,
            Instant created, Instant expires, int failedAttempts, Instant consumedAt) {
        String token = token();
        jdbc.update("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,app_id,open_id,patient_id,issued_by,expires_at,consumed_at,failed_attempts,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(), purpose, SessionService.hash(token), appId, openId, patientId, issuedBy,
            Timestamp.from(expires), consumedAt == null ? null : Timestamp.from(consumedAt), failedAttempts, Timestamp.from(created));
        return token;
    }

    private String registrationToken(String openId) {
        return insertChallenge("wechat_registration", "mock", openId, null, null,
            Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), 0, null);
    }

    private String body(String token, String name, String phone, String birthDate, String allergyStatus, String notes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"registration_token\":\"").append(token).append("\",\"name\":\"").append(name)
          .append("\",\"phone\":\"").append(phone).append("\",\"allergy_status\":\"").append(allergyStatus).append("\"");
        if (birthDate != null) sb.append(",\"birth_date\":\"").append(birthDate).append("\"");
        if (notes != null) sb.append(",\"allergy_notes\":\"").append(notes).append("\"");
        return sb.append('}').toString();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).GET();
        if (headers.length > 0) b.headers(headers);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> register(String json) throws Exception {
        return post("/auth/patient-register", json);
    }

    // ---- 1. 正常创建 + /auth/me 访问 ----

    @Test
    void unboundContextCreatesPatientAndValidPatientSession() throws Exception {
        var wechat = post("/auth/wechat", "{\"code\":\"" + LocalWechatMock.UNBOUND_CODE + "\"}");
        assertEquals(200, wechat.statusCode(), wechat.body());
        String token = json.readTree(wechat.body()).get("data").get("registration_token").asText();

        String phone = "13800001111";
        var resp = register(body(token, "张测试", phone, "1990-05-01", "无已知", null));
        assertEquals(200, resp.statusCode(), resp.body());
        var data = json.readTree(resp.body()).get("data");
        String session = data.get("session_token").asText();
        long patientId = Long.parseLong(data.get("patient_id").asText());
        assertDoesNotThrow(() -> Instant.parse(data.get("expires_at").asText()));
        assertFalse(resp.body().contains("openid"));
        assertFalse(resp.body().contains("open_id"));
        assertFalse(resp.body().contains("session_key"));
        assertTrue(resp.headers().firstValue("Set-Cookie").isEmpty()); // 患者会话无员工 Cookie
        assertEquals("no-store", resp.headers().firstValue("Cache-Control").orElseThrow());

        var me = get("/auth/me", "Authorization", "Bearer " + session);
        assertEquals(200, me.statusCode(), me.body());
        assertEquals("patient", json.readTree(me.body()).get("data").get("identity_type").asText());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        long accountId = jdbc.queryForObject("SELECT account_id FROM account WHERE patient_id=?", Long.class, patientId);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=?", Integer.class, accountId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id=?", Integer.class, LocalWechatMock.UNBOUND_OPEN_ID));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(token)));
    }

    // ---- 2. 各类拒绝 ----

    @Test
    void wrongPurposeTokenRejected() throws Exception {
        // patient_binding 上下文：目的错误，安全拒绝且不计数。带 revoked_at 满足 V004 用途约束（历史撤销行）。
        String token = token();
        jdbc.update("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,revoked_at,expires_at,failed_attempts,created_at) VALUES(?,'patient_binding',?,?,?,?,?,0,?)",
            UUID.randomUUID(), SessionService.hash(token), 1L, 1L,
            Timestamp.from(Instant.now().minusSeconds(300)),
            Timestamp.from(Instant.now().plusSeconds(600)), Timestamp.from(Instant.now().minusSeconds(1)));
        var resp = register(body(token, "张测试", "13800002222", null, "无已知", null));
        assertEquals(401, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("REGISTRATION_TOKEN_INVALID"));
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    @Test
    void expiredTokenRejected() throws Exception {
        String token = insertChallenge("wechat_registration", "mock", "expired-openid", null, null,
            Instant.now().minusSeconds(1200), Instant.now().minusSeconds(600), 0, null);
        var resp = register(body(token, "张测试", "13800002223", null, "无已知", null));
        assertEquals(401, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("REGISTRATION_TOKEN_INVALID"));
    }

    @Test
    void consumedTokenRejected() throws Exception {
        String token = insertChallenge("wechat_registration", "mock", "consumed-openid", null, null,
            Instant.now().minusSeconds(600), Instant.now().plusSeconds(600), 0, Instant.now().minusSeconds(300));
        var resp = register(body(token, "张测试", "13800002224", null, "无已知", null));
        assertEquals(401, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("REGISTRATION_TOKEN_INVALID"));
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    @Test
    void fiveFailuresRejected() throws Exception {
        String token = insertChallenge("wechat_registration", "mock", "five-openid", null, null,
            Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), 5, null);
        var resp = register(body(token, "张测试", "13800002225", null, "无已知", null));
        assertEquals(401, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("REGISTRATION_TOKEN_INVALID"));
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(token)));
    }

    @Test
    void unknownTokenRejected() throws Exception {
        var resp = register(body(token(), "张测试", "13800002226", null, "无已知", null));
        assertEquals(401, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("REGISTRATION_TOKEN_INVALID"));
    }

    @Test
    void allergyRequiresNotes() throws Exception {
        String token = registrationToken("allergy-openid");
        var resp = register(body(token, "张测试", "13800002227", null, "有", null));
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("VALIDATION_FAILED"));
        assertEquals(1, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, "13800002227"));
    }

    @Test
    void unknownFieldRejected() throws Exception {
        String token = registrationToken("unknown-field-openid");
        String payload = "{\"registration_token\":\"" + token + "\",\"name\":\"张测试\",\"phone\":\"13800002228\",\"allergy_status\":\"无已知\",\"openid\":\"attacker\"}";
        var resp = register(payload);
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("UNKNOWN_FIELD"));
    }

    @Test
    void invalidAllergyStatusRejected() throws Exception {
        String token = registrationToken("bad-allergy-openid");
        var resp = register(body(token, "张测试", "13800002229", null, "有过敏", null));
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("VALIDATION_FAILED"));
    }

    @Test
    void invalidBirthDateRejectedWithoutPenalty() throws Exception {
        String token = registrationToken("bad-birth-openid");
        var resp = register(body(token, "张测试", "13800002230", "not-a-date", "无已知", null));
        assertEquals(422, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("VALIDATION_FAILED"));
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    // ---- 3. 并发 ----

    @Test
    void sameContextConcurrentOnlyOneSuccess() throws Exception {
        String token = registrationToken("conc-same-openid");
        String phone = "13800003301";
        String payload = body(token, "张测试", phone, null, "无已知", null);
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> register(payload).statusCode());
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(c -> c == 200).count());
        assertEquals(1, results.stream().filter(c -> c == 401).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=(SELECT patient_id FROM patient WHERE phone=?)", Integer.class, phone));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='conc-same-openid'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE account_id=(SELECT account_id FROM account WHERE patient_id=(SELECT patient_id FROM patient WHERE phone=?))", Integer.class, phone));
    }

    @Test
    void sameWechatDifferentContextConcurrentSingleRecord() throws Exception {
        String tokenA = registrationToken("conc-diff-openid");
        String tokenB = registrationToken("conc-diff-openid");
        String phoneA = "13800003302";
        String phoneB = "13800003303";
        var idx = new AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            // 两个不同上下文但同一 (app_id, open_id)。
            int i = idx.getAndIncrement();
            return register(i == 0 ? body(tokenA, "张测试A", phoneA, null, "无已知", null)
                                   : body(tokenB, "张测试B", phoneB, null, "无已知", null)).statusCode();
        });
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(c -> c == 200).count());
        assertEquals(1, results.stream().filter(c -> c == 409).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='conc-diff-openid'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone IN (?,?)", Integer.class, phoneA, phoneB));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id IN (SELECT patient_id FROM patient WHERE phone IN (?,?))", Integer.class, phoneA, phoneB));
    }

    @Test
    void differentWechatSamePhoneConcurrentOnlyOneRecord() throws Exception {
        // 不同微信、不同上下文并发相同手机号：手机号事务锁串行化，权威复查保证只建一份，其余安全转人工。
        String tokenA = registrationToken("phone-conc-openid-a");
        String tokenB = registrationToken("phone-conc-openid-b");
        String phone = "13800003310";
        var idx = new AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20), () -> {
            int i = idx.getAndIncrement();
            return register(i == 0 ? body(tokenA, "张测试A", phone, null, "无已知", null)
                                   : body(tokenB, "张测试B", phone, null, "无已知", null)).statusCode();
        });
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(c -> c == 200).count());
        assertEquals(1, results.stream().filter(c -> c == 409).count());
        // 无孤儿：仅一份 patient/account/wechat_identity。
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=(SELECT patient_id FROM patient WHERE phone=?)", Integer.class, phone));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id IN (?,?)", Integer.class, "phone-conc-openid-a", "phone-conc-openid-b"));
        // 恰好一个上下文被消费，另一个未消费且失败计数=1（手机号疑似重复，独立提交）。
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash IN (?,?) AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(tokenA), SessionService.hash(tokenB)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash IN (?,?) AND consumed_at IS NULL", Integer.class, SessionService.hash(tokenA), SessionService.hash(tokenB)));
        assertEquals(1, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash IN (?,?) AND consumed_at IS NULL", Integer.class, SessionService.hash(tokenA), SessionService.hash(tokenB)));
    }

    // ---- 4. 已绑定 / 停用 / 手机号重复 ----

    @Test
    void alreadyBoundRejectedNoRecreate() throws Exception {
        String token = registrationToken("bound-fixture-openid");
        int patientsBefore = jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class);
        var resp = register(body(token, "张测试", "13800004401", null, "无已知", null));
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("WECHAT_IDENTITY_ALREADY_BOUND"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='bound-fixture-openid'", Integer.class));
        assertEquals(patientsBefore, jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class));
    }

    @Test
    void disabledBoundNotReenabled() throws Exception {
        String token = registrationToken("disabled-fixture-openid");
        var resp = register(body(token, "张测试", "13800004402", null, "无已知", null));
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("WECHAT_IDENTITY_ALREADY_BOUND"));
        assertEquals(false, jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=3", Boolean.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=2", Integer.class));
    }

    @Test
    void phoneMatchSuspectedDuplicateNoLeak() throws Exception {
        String token = registrationToken("phone-dup-openid");
        int patientsBefore = jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class);
        var resp = register(body(token, "张测试", "13900000000", null, "无已知", null));
        assertEquals(409, resp.statusCode(), resp.body());
        assertTrue(resp.body().contains("PATIENT_DUPLICATE_SUSPECTED"));
        assertFalse(resp.body().contains("合成重复手机患者")); // 不泄漏既有患者姓名
        assertEquals(patientsBefore, jdbc.queryForObject("SELECT count(*) FROM patient", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id='phone-dup-openid'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    // ---- 5. 失败计数 ----

    @Test
    void failureCountPersistsAndValidRetrySucceeds() throws Exception {
        String token = registrationToken("count-persist-openid");
        var fail = register(body(token, "张测试", "13800005501", null, "有", null));
        assertEquals(422, fail.statusCode(), fail.body());
        assertEquals(1, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));

        var retry = register(body(token, "张测试", "13800005501", null, "有", "青霉素过敏"));
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, "13800005501"));
    }

    @Test
    void concurrentFailuresAccumulateWithoutLoss() throws Exception {
        String token = registrationToken("count-conc-openid");
        String payload = body(token, "张测试", "13800005502", null, "不确定", null);
        var results = ConcurrentRunner.run(3, Duration.ofSeconds(20), () -> register(payload).statusCode());
        assertEquals(3, results.stream().filter(c -> c == 422).count());
        assertEquals(3, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, "13800005502"));
    }

    @Test
    void reachingFiveRejectsSubsequentConsumption() throws Exception {
        String token = insertChallenge("wechat_registration", "mock", "count-five-openid", null, null,
            Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), 4, null);
        var fail = register(body(token, "张测试", "13800005503", null, "有", null));
        assertEquals(422, fail.statusCode(), fail.body());
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));

        var retry = register(body(token, "张测试", "13800005503", null, "有", "青霉素过敏"));
        assertEquals(401, retry.statusCode(), retry.body());
        assertTrue(retry.body().contains("REGISTRATION_TOKEN_INVALID"));
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, "13800005503"));
    }

    @Test
    void injectedSessionFailureRollsBackAtomicallyAndIsNotCounted() throws Exception {
        String openId = "rollback-openid";
        String phone = "13800005504";
        String token = registrationToken(openId);
        var ctx = challenges.find(SessionService.hash(token));
        assertNotNull(ctx);

        SessionService failingSessions = Mockito.mock(SessionService.class);
        Mockito.when(failingSessions.issue(Mockito.anyLong(), Mockito.eq(SessionService.PATIENT)))
            .thenThrow(new ApiException(ApiErrorCode.INTERNAL_ERROR));
        var executor = new PatientRegistrationExecutor(jdbc, failingSessions, audit, clock);

        var e = assertThrows(ApiException.class, () -> new TransactionTemplate(tx).execute(status -> {
            executor.execute(ctx, "张测试", phone, null, "无已知", null);
            return null;
        }));
        assertEquals(ApiErrorCode.INTERNAL_ERROR, e.code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, phone));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM account WHERE patient_id=(SELECT patient_id FROM patient WHERE phone=?)", Integer.class, phone));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM wechat_identity WHERE app_id='mock' AND open_id=?", Integer.class, openId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE token_hash=? AND consumed_at IS NOT NULL", Integer.class, SessionService.hash(token)));
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    // ---- 5b. recordFailure 权威条件 / 编排层事务边界 ----

    @Test
    void orchestratorRejectsOuterTransactionWithoutWrites() {
        var service = app.getBean(PatientRegistrationService.class);
        var tpl = new TransactionTemplate(tx);
        assertThrows(IllegalTransactionStateException.class, () -> tpl.execute(status -> {
            service.register("bogus-token", "张测试", "13800007701", null, "无已知", null, "127.0.0.1");
            return null;
        }));
        // 外层事务被拒绝，未留下任何写入。
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM patient WHERE phone=?", Integer.class, "13800007701"));
    }

    @Test
    void recordFailureSkipsExpiredContext() {
        String token = insertChallenge("wechat_registration", "mock", "rf-expired-openid", null, null,
            Instant.now().minusSeconds(1200), Instant.now().minusSeconds(600), 0, null);
        var ctx = challenges.find(SessionService.hash(token));
        assertNotNull(ctx);
        challenges.recordFailure(ctx.challengeId());
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    @Test
    void recordFailureSkipsConsumedContext() {
        String token = insertChallenge("wechat_registration", "mock", "rf-consumed-openid", null, null,
            Instant.now().minusSeconds(600), Instant.now().plusSeconds(600), 0, Instant.now().minusSeconds(300));
        var ctx = challenges.find(SessionService.hash(token));
        assertNotNull(ctx);
        challenges.recordFailure(ctx.challengeId());
        assertEquals(0, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    @Test
    void recordFailureCapsAtFiveUnderConcurrency() throws Exception {
        String token = insertChallenge("wechat_registration", "mock", "rf-cap-openid", null, null,
            Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), 4, null);
        var ctx = challenges.find(SessionService.hash(token));
        assertNotNull(ctx);
        var results = ConcurrentRunner.run(3, Duration.ofSeconds(20), () -> {
            challenges.recordFailure(ctx.challengeId());
            return true;
        });
        assertEquals(3, results.size());
        assertEquals(5, jdbc.queryForObject("SELECT failed_attempts FROM identity_challenge WHERE token_hash=?", Integer.class, SessionService.hash(token)));
    }

    @Test
    void registrationThrottleBudgetIsIndependentOfWechat() {
        var wechatThrottle = app.getBean(WechatLoginThrottle.class);
        assertNotSame(throttle, wechatThrottle);
        for (int i = 0; i < 30; i++) wechatThrottle.check("203.0.113.9");
        assertThrows(ApiException.class, () -> wechatThrottle.check("203.0.113.9"));
        assertDoesNotThrow(() -> throttle.check("203.0.113.9")); // 注册预算独立，不受微信入口耗尽影响
    }

    // ---- 6. 跨端隔离 ----

    @Test
    void patientSessionCannotAccessStaffEndpoint() throws Exception {
        String token = registrationToken("staff-iso-openid");
        var resp = register(body(token, "张测试", "13800006601", null, "无已知", null));
        assertEquals(200, resp.statusCode(), resp.body());
        String session = json.readTree(resp.body()).get("data").get("session_token").asText();
        assertEquals(403, get("/staff/accounts", "Authorization", "Bearer " + session).statusCode());
    }

    @Test
    void registrationTokenNotBusinessBearer() throws Exception {
        String token = registrationToken("bearer-iso-openid");
        assertEquals(401, get("/auth/me", "Authorization", "Bearer " + token).statusCode());
    }

    @Test
    void unknownTokenThrottleSeparateBudget() {
        for (int i = 0; i < 30; i++) throttle.check("198.51.100.77");
        var e = assertThrows(ApiException.class, () -> throttle.check("198.51.100.77"));
        assertEquals(ApiErrorCode.RATE_LIMITED, e.code());
    }
}
