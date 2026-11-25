package cn.dental.support;

import java.sql.*;
import java.util.Map;

/** Only the dedicated ephemeral runner can supply a permitted test target. */
public final class PostgresTestSupport {
    private PostgresTestSupport() {}
    public static void validateTarget(Map<String,String> env) {
        String database = env.getOrDefault("DENTAL_TEST_DATABASE", "");
        String port = env.getOrDefault("DENTAL_TEST_PORT", "");
        if (!database.matches("dental_test_[a-f0-9]{32}") || !port.matches("[0-9]{5}")
                || Integer.parseInt(port) < 1024 || Integer.parseInt(port) > 65535
                || !env.getOrDefault("TEST_DB_USERNAME", "").equals("dental_test_runner")
                || !env.getOrDefault("DENTAL_TEST_TOKEN", "").matches("[a-f0-9]{64}")
                || !env.getOrDefault("TEST_DB_URL", "").equals(
                    "jdbc:postgresql://127.0.0.1:" + port + "/" + database)) {
            throw new IllegalStateException("Refusing non-isolated test database configuration");
        }
    }
    public static Connection connect() throws SQLException {
        var env = System.getenv(); validateTarget(env);
        var properties = new java.util.Properties();
        properties.setProperty("user", env.get("TEST_DB_USERNAME"));
        properties.setProperty("password", env.get("TEST_DB_PASSWORD"));
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        Connection c = DriverManager.getConnection(env.get("TEST_DB_URL"), properties);
        try (var statement = c.prepareStatement(
                "SELECT current_database(), current_user, token FROM test_harness.identity")) {
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getString(1).equals(env.get("DENTAL_TEST_DATABASE"))
                        || !result.getString(2).equals("dental_test_runner")
                        || !result.getString(3).equals(env.get("DENTAL_TEST_TOKEN")) || result.next()) {
                    throw new SQLException("Test database identity check failed");
                }
            }
            return c;
        } catch (Exception failure) {
            c.close(); throw new SQLException("Refusing unverified test database", failure);
        }
    }
}
