package cn.dental.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE", matches="dental_test_[a-f0-9]{32}")
class PostgresIsolationTest {
    @Test void connectsToVerifiedPostgres18() throws Exception {
        try (var c=PostgresTestSupport.connect(); var s=c.createStatement(); var r=s.executeQuery("SHOW server_version_num")) {
            assertTrue(r.next()); assertEquals(18,r.getInt(1)/10000);
        }
    }
    @Test void independentTransactionsRollBack() throws Exception {
        var pids=ConcurrentRunner.run(4,Duration.ofSeconds(15), () -> {
            try (var c=PostgresTestSupport.connect()) {
                c.setAutoCommit(false);
                try (var s=c.createStatement()) {
                    s.execute("CREATE TEMP TABLE rollback_probe(id integer)");
                    s.executeUpdate("INSERT INTO rollback_probe VALUES (1)");
                    int pid; try (var r=s.executeQuery("SELECT pg_backend_pid()")) { r.next();pid=r.getInt(1); }
                    c.rollback();
                    try (var r=s.executeQuery("SELECT to_regclass('pg_temp.rollback_probe')")) { r.next();assertNull(r.getString(1)); }
                    c.rollback();return pid;
                }
            }
        });
        assertEquals(4,new HashSet<>(pids).size());
    }
}
