package cn.dental.shared.application;

import cn.dental.AdenDentalApplication;
import cn.dental.shared.repository.BusinessLockRepository;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.support.ConcurrentRunner;
import cn.dental.support.PostgresTestSupport;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DENTAL_TEST_DATABASE", matches = "dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotencyIntegrationTest {
    private ConfigurableApplicationContext app;
    private JdbcTemplate jdbc;
    private IdempotencyService idem;
    private AuditService audit;
    private BusinessLockRepository locks;
    private PlatformTransactionManager tx;

    private final Runnable allowAuth = () -> {};
    private final java.util.function.LongConsumer requireProbeVisible =
            id -> {
                int c = jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=?", Integer.class, id);
                if (c != 1) throw new ApiException(ApiErrorCode.NOT_FOUND);
            };

    @BeforeAll
    void start() throws Exception {
        var env = System.getenv();
        PostgresTestSupport.validateTarget(env);
        String schema = "idempotency_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = PostgresTestSupport.connect(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        app = new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local", "--spring.main.banner-mode=off", "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=" + env.get("TEST_DB_URL") + "?currentSchema=" + schema + ",public",
            "--spring.datasource.username=" + env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
            "--app.mock.wechat-enabled=false", "--app.seed.enabled=false");
        jdbc = app.getBean(JdbcTemplate.class);
        idem = app.getBean(IdempotencyService.class);
        audit = app.getBean(AuditService.class);
        locks = app.getBean(BusinessLockRepository.class);
        tx = app.getBean(PlatformTransactionManager.class);

        // 幂等测试账号（account FK 需 staff/patient 其一）
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(2,'合成牙医乙','牙医','牙医','DENT-2','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES(3,'合成牙医丙','牙医','牙医','DENT-3','在岗')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(2,'合成患者乙','00000000001','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'syn-a','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(2,2,'syn-b','NOT-A-LOGIN-HASH',true,false,now())");
        // 锁顺序测试目标行
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(1,1,'TEST-B1','合成门店','合成地址','00000000000','TEST-L1','09:00-18:00',1,'营业',1,now())");
        jdbc.update("INSERT INTO queue_counter(branch_id,queue_date,last_number) VALUES(1,'2026-11-01',0)");
        // 排班/资源/子记录 真实行锁 fixtures（无需预约模块实现）
        jdbc.update("INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,travel_buffer_minutes) VALUES(1,2,now(),now()+interval '30 minutes','初诊','草稿',1,5)");
        jdbc.update("INSERT INTO resource_category(category_id,category_code,name,enabled) VALUES(1,'CHAIR','牙椅',true)");
        jdbc.update("INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(1,'牙椅A','牙椅',1,'可用',1)");
        jdbc.update("INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(2,'牙椅B','牙椅',1,'可用',1)");
        jdbc.update("INSERT INTO service_category(category_id,code,name,display_order,enabled) VALUES(1,'GEN','通用',0,true)");
        jdbc.update("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(1,'综合检查','次',100.00,true,true,30,1)");
        jdbc.update("INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status) VALUES(1,1,1,24,180,3,30,'draft')");
        jdbc.update("INSERT INTO appointment(appointment_id,patient_id,starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id) VALUES(1,1,now(),now()+interval '30 minutes','初诊','合成测试','现场','待确认','无需协调','11111111-1111-1111-1111-111111111111','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',1,1,1)");
        jdbc.update("INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity) VALUES(1,1,1)");

        jdbc.execute("CREATE TABLE idem_probe(id bigint PRIMARY KEY, value varchar(40) NOT NULL)");
    }

    @AfterAll
    void stop() {
        if (app != null) app.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM idem_probe");
        jdbc.update("DELETE FROM operation_request");
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("UPDATE account SET enabled=true WHERE account_id IN (1,2)");
    }

    private IdempotencyService.BusinessResult businessProbe(long id, String value) {
        jdbc.update("INSERT INTO idem_probe(id,value) VALUES(?,?)", id, value);
        audit.user(1L, "probe", id, "创建探针", null, Map.of("state_to", "value=" + value), "合成原因");
        return new IdempotencyService.BusinessResult(id, "CREATED", "probe-" + id);
    }

    private static String content(Map<String, ?> values) {
        return IdempotencyService.canonicalize(values);
    }

    private Runnable accountEnabledCheck(long accountId, CountDownLatch firstPassed) {
        return () -> {
            boolean enabled = jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=?", Boolean.class, accountId);
            firstPassed.countDown();
            if (!enabled) throw new ApiException(ApiErrorCode.FORBIDDEN);
        };
    }

    @Test
    void sameKeySameContentReplaysWithoutDuplicatingBusinessOrAudit() {
        String c = content(Map.of("schedule_id", "10", "visit_type", "初诊"));
        var first = idem.execute(1, "create_probe", "k1", c, allowAuth, () -> businessProbe(100, "v1"), requireProbeVisible);
        assertFalse(first.replayed());
        assertEquals(100, first.targetId());

        var second = idem.execute(1, "create_probe", "k1", c, allowAuth,
                () -> { throw new IllegalStateException("重放不应再执行业务"); }, requireProbeVisible);
        assertTrue(second.replayed());
        assertEquals(first.targetId(), second.targetId());
        assertEquals(first.resultCode(), second.resultCode());
        assertEquals(first.resultRef(), second.resultRef());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=100", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='创建探针' AND target_id=100", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE actor_account_id=1 AND operation='create_probe' AND request_key='k1'", Integer.class));
    }

    @Test
    void sameKeyDifferentContentReturns409() {
        String c1 = content(Map.of("schedule_id", "10", "visit_type", "初诊"));
        idem.execute(1, "create_probe", "k2", c1, allowAuth, () -> businessProbe(101, "x"), requireProbeVisible);
        String c2 = content(Map.of("schedule_id", "10", "visit_type", "复诊")); // 内容不同
        var e = assertThrows(ApiException.class,
                () -> idem.execute(1, "create_probe", "k2", c2, allowAuth, () -> businessProbe(102, "y"), requireProbeVisible));
        assertEquals(ApiErrorCode.IDEMPOTENCY_CONFLICT, e.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe", Integer.class));
    }

    @Test
    void sameKeyAcrossAccountsAndOperationsDoesNotConflict() {
        String c = content(Map.of("schedule_id", "30"));
        idem.execute(1, "create_probe", "same-key", c, allowAuth, () -> businessProbe(110, "a"), requireProbeVisible);
        idem.execute(2, "create_probe", "same-key", c, allowAuth, () -> businessProbe(111, "b"), requireProbeVisible);
        idem.execute(1, "other_op", "same-key", c, allowAuth, () -> businessProbe(112, "c"), requireProbeVisible);
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM idem_probe", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
    }

    @Test
    void concurrentSameKeyProducesSingleBusinessEffect() throws Exception {
        String c = content(Map.of("schedule_id", "20"));
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(20),
                () -> idem.execute(1, "create_probe", "conc", c, allowAuth, () -> businessProbe(200, "conc"), requireProbeVisible));
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(IdempotencyService.Outcome::replayed).count());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=200", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='创建探针' AND target_id=200", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE request_key='conc'", Integer.class));
    }

    @Test
    void businessFailureRollsBackIdempotencyBusinessAndAudit() {
        String c = content(Map.of("x", "fail"));
        assertThrows(ApiException.class, () -> idem.execute(1, "create_probe", "fail-key", c, allowAuth, () -> {
            jdbc.update("INSERT INTO idem_probe(id,value) VALUES(300,'partial')");
            audit.user(1L, "probe", 300, "创建探针", null, Map.of("state_to", "value=partial"), "合成原因");
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }, requireProbeVisible));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=300", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=300", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE request_key='fail-key'", Integer.class));
    }

    @Test
    void retryAfterFailureWithSameKeySucceeds() {
        String c = content(Map.of("x", "retry"));
        var attempts = new AtomicInteger();
        assertThrows(ApiException.class, () -> idem.execute(1, "create_probe", "retry-key", c, allowAuth, () -> {
            attempts.getAndIncrement();
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }, requireProbeVisible));
        assertEquals(1, attempts.get());

        var outcome = idem.execute(1, "create_probe", "retry-key", c, allowAuth, () -> businessProbe(400, "v"), requireProbeVisible);
        assertFalse(outcome.replayed());
        assertEquals(400, outcome.targetId());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=400", Integer.class));
    }

    @Test
    void committedResultIsRecoveredByRetry() {
        String c = content(Map.of("x", "recover"));
        var first = idem.execute(1, "create_probe", "recover-key", c, allowAuth, () -> businessProbe(500, "v"), requireProbeVisible);
        assertFalse(first.replayed());
        var retry = idem.execute(1, "create_probe", "recover-key", c, allowAuth,
                () -> { throw new IllegalStateException("不应重跑业务"); }, requireProbeVisible);
        assertTrue(retry.replayed());
        assertEquals(first.targetId(), retry.targetId());
        assertEquals(first.resultCode(), retry.resultCode());
        assertEquals(first.resultRef(), retry.resultRef());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=500", Integer.class));
    }

    @Test
    void revokedAuthorizationCannotReplayOldResult() {
        String c = content(Map.of("x", "revoke"));
        idem.execute(1, "create_probe", "revoke-key", c, allowAuth, () -> businessProbe(600, "v"), requireProbeVisible);
        Runnable denied = () -> { throw new ApiException(ApiErrorCode.FORBIDDEN); };
        var e = assertThrows(ApiException.class,
                () -> idem.execute(1, "create_probe", "revoke-key", c, denied, () -> businessProbe(600, "v"), requireProbeVisible));
        assertEquals(ApiErrorCode.FORBIDDEN, e.code());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=600", Integer.class));
    }

    @Test
    void replayChecksTargetVisibility() {
        String c = content(Map.of("x", "visible"));
        idem.execute(1, "create_probe", "visible-key", c, allowAuth, () -> businessProbe(700, "v"), requireProbeVisible);
        jdbc.update("DELETE FROM idem_probe WHERE id=700"); // 目标已不可见
        var e = assertThrows(ApiException.class,
                () -> idem.execute(1, "create_probe", "visible-key", c, allowAuth, () -> businessProbe(700, "v"), requireProbeVisible));
        assertEquals(ApiErrorCode.NOT_FOUND, e.code());
    }

    @Test
    void idempotencyRecordStoresOnlyReferencesNotSensitiveContent() {
        String c = content(Map.of("patient_secret", "sensitive-body", "schedule_id", "40"));
        idem.execute(1, "create_probe", "secret-key", c, allowAuth, () -> businessProbe(800, "v"), requireProbeVisible);
        var row = jdbc.queryForMap("SELECT request_hash,target_id,result_code,result_ref FROM operation_request WHERE actor_account_id=1 AND operation='create_probe' AND request_key='secret-key'");
        assertEquals(800L, ((Number) row.get("target_id")).longValue());
        assertEquals("CREATED", row.get("result_code"));
        assertEquals("probe-800", row.get("result_ref"));
        assertTrue(((String) row.get("request_hash")).matches("[0-9a-f]{64}"));
        String all = row.toString();
        assertFalse(all.contains("sensitive-body"));
        assertFalse(all.contains("patient_secret"));
    }

    @Test
    void authorizationIsRevalidatedAfterAcquiringLock() throws Exception {
        String c = content(Map.of("x", "revoke-during-wait"));
        CountDownLatch aInBusiness = new CountDownLatch(1);
        CountDownLatch bPassedInitialAuth = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> {
                try {
                    idem.execute(1, "op", "revoke-during-wait", c, allowAuth, () -> {
                        aInBusiness.countDown(); // A 已持有同键 advisory 锁
                        try {
                            if (!releaseA.await(10, TimeUnit.SECONDS)) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
                        }
                        return businessProbe(900, "a");
                    }, requireProbeVisible);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            });
            assertTrue(aInBusiness.await(10, TimeUnit.SECONDS));

            Future<Boolean> b = pool.submit(() -> {
                try {
                    idem.execute(1, "op", "revoke-during-wait", c, accountEnabledCheck(1, bPassedInitialAuth),
                            () -> businessProbe(901, "b"), requireProbeVisible);
                    return false; // 不应成功
                } catch (ApiException e) {
                    return e.code() == ApiErrorCode.FORBIDDEN; // 等待期间撤权后必须拒绝
                }
            });
            assertTrue(bPassedInitialAuth.await(10, TimeUnit.SECONDS)); // B 已通过进锁前鉴权，正阻塞等待同键锁

            jdbc.update("UPDATE account SET enabled=false WHERE account_id=1"); // 撤权（独立提交）

            releaseA.countDown();
            assertTrue(a.get(20, TimeUnit.SECONDS));
            assertTrue(b.get(20, TimeUnit.SECONDS));
            // 撤权后不得返回旧结果，也不得写入新业务
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=900", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idem_probe WHERE id=901", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE request_key='revoke-during-wait'", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void auditAndLocksRejectOutsideTransaction() {
        assertThrows(IllegalTransactionStateException.class,
                () -> audit.user(1L, "probe", 999, "创建探针", null, Map.of("state_to", "x"), "合成"));
        assertThrows(IllegalTransactionStateException.class, () -> locks.lockPatient(1));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=999", Integer.class));
    }

    @Test
    void auditAllows300AddressButEnforcesEncodedCap() {
        var tpl = new TransactionTemplate(tx);
        tpl.execute(s -> {
            audit.user(1L, "branch", 77, "修改门店档案", null,
                Map.of("address", "地".repeat(300), "state_to", "停用"), "最大合法地址");
            return null;
        });
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=77", Integer.class));

        var tooLarge = new LinkedHashMap<String, String>();
        for (String key : List.of("enabled", "state_from", "state_to", "role_code", "branch_ids",
            "approval_basis", "schedule_starts_at", "schedule_ends_at", "visit_type", "queue_number",
            "verification_method", "in_person", "code", "name", "phone", "license_no")) {
            tooLarge.put(key, "x".repeat(200));
        }
        var rejected = assertThrows(ApiException.class, () -> tpl.execute(s -> {
            audit.user(1L, "branch", 78, "修改门店档案", null, tooLarge, "总体上限");
            return null;
        }));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, rejected.code());
    }

    @Test
    void auditRejectsInvalidSummary() {
        var tpl = new TransactionTemplate(tx);
        var e1 = assertThrows(ApiException.class, () -> tpl.execute(s -> {
            audit.user(1L, "probe", 1, "创建探针", null, Map.of("password", "secret"), "合成");
            return null;
        }));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, e1.code());
        var e2 = assertThrows(ApiException.class, () -> tpl.execute(s -> {
            audit.user(1L, "probe", 2, "创建探针", null, Map.of("state_to", "x".repeat(201)), "合成");
            return null;
        }));
        assertEquals(ApiErrorCode.VALIDATION_FAILED, e2.code());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    @Test
    void businessLocksFollowFixedOrderWithoutDeadlock() throws Exception {
        new TransactionTemplate(tx).execute(status -> {
            locks.lockPatient(1);
            locks.lockDentists(List.of(3L, 2L)); // 乱序输入，内部按 ID 升序锁定
            locks.lockQueueCounter(1, LocalDate.of(2026, 11, 1)); // 最后
            return null;
        });
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(15), () -> {
            new TransactionTemplate(tx).execute(status -> {
                locks.lockPatient(1);
                locks.lockDentists(List.of(2L, 3L));
                locks.lockQueueCounter(1, LocalDate.of(2026, 11, 1));
                return null;
            });
            return true;
        });
        assertEquals(2, results.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void oppositeDentistOrderAcrossDifferentPatientsDoesNotDeadlock() throws Exception {
        var idx = new AtomicInteger();
        var results = ConcurrentRunner.run(2, Duration.ofSeconds(15), () -> {
            int i = idx.getAndIncrement();
            return new TransactionTemplate(tx).execute(status -> {
                if (i == 0) {
                    locks.lockPatient(1);
                    locks.lockDentists(List.of(2L, 3L));
                } else {
                    locks.lockPatient(2);             // 不同患者，不提前串行化
                    locks.lockDentists(List.of(3L, 2L)); // 相反医生顺序
                }
                return true;
            });
        });
        assertEquals(2, results.stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void scheduleResourceAndChildLocksAreRealRowLocks() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<Boolean> holder = pool.submit(() -> new TransactionTemplate(tx).execute(status -> {
                locks.lockSchedule(1);
                locks.lockResources(List.of(1L, 2L));
                locks.lockChildRecords(1);
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ApiErrorCode.INTERNAL_ERROR);
                }
                return true;
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));

            Future<Boolean> wSchedule = pool.submit(() -> new TransactionTemplate(tx).execute(s -> {
                locks.lockSchedule(1);
                return true;
            }));
            Future<Boolean> wResource = pool.submit(() -> new TransactionTemplate(tx).execute(s -> {
                locks.lockResources(List.of(1L));
                return true;
            }));
            Future<Boolean> wChild = pool.submit(() -> new TransactionTemplate(tx).execute(s -> {
                locks.lockChildRecords(1);
                return true;
            }));
            // holder 持锁期间，三者都应被真实行锁阻塞
            assertThrows(TimeoutException.class, () -> wSchedule.get(1500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> wResource.get(1500, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> wChild.get(1500, TimeUnit.MILLISECONDS));

            release.countDown();
            assertTrue(holder.get(10, TimeUnit.SECONDS));
            assertTrue(wSchedule.get(10, TimeUnit.SECONDS));
            assertTrue(wResource.get(10, TimeUnit.SECONDS));
            assertTrue(wChild.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lockIdsRejectNullOrNonPositive() {
        var tpl = new TransactionTemplate(tx);
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockPatient(0); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockSchedule(-1); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockChildRecords(-5); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockDentists(Arrays.asList(3L, null)); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockDentists(List.of(0L)); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockResources(List.of(-2L)); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockDentists(null); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockQueueCounter(0, LocalDate.of(2026, 11, 1)); return null; }));
        assertThrows(IllegalArgumentException.class, () -> tpl.execute(s -> { locks.lockQueueCounter(1, null); return null; }));
    }

    @Test
    void incompatibleOuterTransactionsAreRejectedBeforeBusinessWrites() {
        for (int isolation : List.of(java.sql.Connection.TRANSACTION_REPEATABLE_READ, java.sql.Connection.TRANSACTION_SERIALIZABLE)) {
            var template = new TransactionTemplate(tx);
            template.setIsolationLevel(isolation);
            assertThrows(IllegalTransactionStateException.class, () -> template.execute(status ->
                idem.execute(1, "probe", "wrong-isolation", content(Map.of("x", "y")), allowAuth,
                    () -> businessProbe(9991, "bad"), requireProbeVisible)));
        }
        var readOnly = new TransactionTemplate(tx);
        readOnly.setReadOnly(true);
        assertThrows(IllegalTransactionStateException.class, () -> readOnly.execute(status ->
            idem.execute(1, "probe", "read-only", content(Map.of("x", "y")), allowAuth,
                () -> businessProbe(9992, "bad"), requireProbeVisible)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM operation_request", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idem_probe", Integer.class));
    }

    @Test
    void auditRejectsUnnecessaryPatientNames() {
        assertThrows(ApiException.class, () -> new TransactionTemplate(tx).execute(status -> {
            audit.user(1L, "probe", 1, "创建探针", null, Map.of("patient_name", "合成姓名"), "合成");
            return null;
        }));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }
}
