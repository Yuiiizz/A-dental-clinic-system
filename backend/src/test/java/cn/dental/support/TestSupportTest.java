package cn.dental.support;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestSupportTest {
    private Map<String,String> valid() {
        var e = new HashMap<String,String>();
        e.put("DENTAL_TEST_DATABASE", "dental_test_"+"a".repeat(32)); e.put("DENTAL_TEST_PORT", "55432");
        e.put("TEST_DB_USERNAME", "dental_test_runner"); e.put("DENTAL_TEST_TOKEN", "b".repeat(64));
        e.put("TEST_DB_URL", "jdbc:postgresql://127.0.0.1:55432/"+e.get("DENTAL_TEST_DATABASE")); return e;
    }
    @Test void permitsDedicatedTarget() { assertDoesNotThrow(() -> PostgresTestSupport.validateTarget(valid())); }
    @Test void rejectsOtherTargetsBeforeConnection() {
        for (String url : List.of("jdbc:postgresql://localhost:5432/a_dental_dev", "jdbc:postgresql://remote/prod", valid().get("TEST_DB_URL")+"?options=unsafe")) {
            var e = valid(); e.put("TEST_DB_URL",url);
            assertThrows(IllegalStateException.class, () -> PostgresTestSupport.validateTarget(e));
        }
        assertThrows(IllegalStateException.class, () -> PostgresTestSupport.validateTarget(Map.of()));
    }
    @Test void clockSharesInstantAcrossZones() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var local = clock.withZone(ZoneId.of("Asia/Shanghai")); clock.advance(Duration.ofMinutes(1));
        assertEquals(Instant.parse("2026-01-01T00:01:00Z"),local.instant());
    }
    @Test void startsRealConcurrentWorkers() throws Exception {
        var barrier = new CyclicBarrier(4);
        var results = ConcurrentRunner.run(4, Duration.ofSeconds(10), () -> { barrier.await(3,TimeUnit.SECONDS); return Thread.currentThread().threadId(); });
        assertEquals(4,new HashSet<>(results).size());
    }
    @Test void propagatesWorkerFailure() {
        assertThrows(ExecutionException.class, () -> ConcurrentRunner.run(2,Duration.ofSeconds(3), () -> { throw new IllegalArgumentException(); }));
    }
}
