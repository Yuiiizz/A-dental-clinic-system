package cn.dental.shared.web;

import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE",matches="dental_test_[a-f0-9]{32}")
class BusinessRollbackTest {
    public static class ProbeService {
        private final JdbcTemplate jdbc;
        public ProbeService(JdbcTemplate jdbc){this.jdbc=jdbc;}
        @Transactional public void writeThenFail(){
            jdbc.update("INSERT INTO rollback_probe(value) VALUES (1)");
            throw new ApiException(ApiErrorCode.SLOT_UNAVAILABLE);
        }
    }
    @Test void uncheckedBusinessFailureRollsBackThroughSpringProxy() throws Exception {
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        String schema="rollback_"+UUID.randomUUID().toString().replace("-","");
        try(var c=PostgresTestSupport.connect();var s=c.createStatement()){
            s.execute("CREATE SCHEMA "+schema);
            s.execute("CREATE TABLE "+schema+".rollback_probe(value integer NOT NULL)");
        }
        var source=new DriverManagerDataSource(env.get("TEST_DB_URL")+"?currentSchema="+schema,
            env.get("TEST_DB_USERNAME"),env.get("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        var proxy=new ProxyFactory(new ProbeService(jdbc));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service=(ProbeService)proxy.getProxy();
        assertThrows(ApiException.class,service::writeThenFail);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM rollback_probe",Integer.class));
    }
}
