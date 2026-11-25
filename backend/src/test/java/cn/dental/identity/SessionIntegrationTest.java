package cn.dental.identity;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiException;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE",matches="dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;private JdbcTemplate jdbc;private String base;
    private final HttpClient client=HttpClient.newHttpClient();
    @BeforeAll void start()throws Exception {
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        String schema="sessions_"+UUID.randomUUID().toString().replace("-","");
        try(var c=PostgresTestSupport.connect();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        app=new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local","--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0",
            "--spring.datasource.url="+env.get("TEST_DB_URL")+"?currentSchema="+schema+",public",
            "--spring.datasource.username="+env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
            "--app.mock.wechat-enabled=false","--app.seed.enabled=false");
        sessions=app.getBean(SessionService.class);jdbc=app.getBean(JdbcTemplate.class);
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'synthetic','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(2,1,true,false,now())");
    }
    @AfterAll void stop(){if(app!=null)app.close();}
    @BeforeEach void reset(){jdbc.update("UPDATE account SET enabled=true,auth_version=1,must_change_password=false");jdbc.update("DELETE FROM auth_session");}
    private HttpResponse<String> request(String path,String method,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody());
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void storesOnlyDigestsAndRejectsCrossClientTokens(){
        var staff=sessions.issue(1,SessionService.STAFF);var patient=sessions.issue(2,SessionService.PATIENT);
        assertEquals(1,sessions.authenticate(staff.token(),SessionService.STAFF).accountId());
        assertEquals(2,sessions.authenticate(patient.token(),SessionService.PATIENT).accountId());
        assertThrows(ApiException.class,()->sessions.authenticate(staff.token(),SessionService.PATIENT));
        assertThrows(ApiException.class,()->sessions.authenticate(patient.token(),SessionService.STAFF));
        assertThrows(ApiException.class,()->sessions.issue(1,SessionService.PATIENT));
        var row=jdbc.queryForMap("SELECT token_hash,csrf_token_hash FROM auth_session WHERE account_id=1");
        assertEquals(SessionService.hash(staff.token()),row.get("token_hash"));assertEquals(SessionService.hash(staff.csrfToken()),row.get("csrf_token_hash"));
        assertFalse(staff.toString().contains(staff.token()));
    }
    @Test void disablingAndChangingAuthorizationImmediatelyInvalidatesSessions(){
        var first=sessions.issue(1,SessionService.STAFF);
        jdbc.update("UPDATE account SET enabled=false WHERE account_id=1");
        assertThrows(ApiException.class,()->sessions.authenticate(first.token(),SessionService.STAFF));
        jdbc.update("UPDATE account SET enabled=true,auth_version=2 WHERE account_id=1");
        assertThrows(ApiException.class,()->sessions.authenticate(first.token(),SessionService.STAFF));
    }
    @Test void expiredOrRevokedTokensAreRejected(){
        var session=sessions.issue(2,SessionService.PATIENT);
        sessions.revoke(sessions.authenticate(session.token(),SessionService.PATIENT));
        assertThrows(ApiException.class,()->sessions.authenticate(session.token(),SessionService.PATIENT));
        var expired=sessions.issue(2,SessionService.PATIENT);
        jdbc.update("UPDATE auth_session SET created_at=now()-interval '3 hours',last_seen_at=now()-interval '3 hours',expires_at=now()-interval '1 minute' WHERE token_hash=?",SessionService.hash(expired.token()));
        assertThrows(ApiException.class,()->sessions.authenticate(expired.token(),SessionService.PATIENT));
    }
    @Test void staffLogoutRequiresCorrectCsrfAndRevokesCookie()throws Exception {
        var s=sessions.issue(1,SessionService.STAFF);String cookie="A_CLINIC_SESSION="+s.token();
        assertEquals(403,request("/auth/logout","POST","Cookie",cookie).statusCode());
        assertEquals(403,request("/auth/logout","POST","Cookie",cookie,"X-CSRF-Token","wrong").statusCode());
        var response=request("/auth/logout","POST","Cookie",cookie,"X-CSRF-Token",s.csrfToken());
        assertEquals(204,response.statusCode());assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        assertThrows(ApiException.class,()->sessions.authenticate(s.token(),SessionService.STAFF));
    }
    @Test void patientBearerLogoutWorksButCookieAndMixedCredentialsDoNot()throws Exception {
        var p=sessions.issue(2,SessionService.PATIENT);var s=sessions.issue(1,SessionService.STAFF);
        assertEquals(401,request("/auth/logout","POST","Cookie","A_CLINIC_SESSION="+p.token()).statusCode());
        assertEquals(401,request("/auth/logout","POST","Authorization","Bearer "+s.token()).statusCode());
        assertEquals(401,request("/auth/logout","POST","Authorization","Bearer "+p.token(),"Cookie","A_CLINIC_SESSION="+s.token()).statusCode());
        assertEquals(204,request("/auth/logout","POST","Authorization","Bearer "+p.token()).statusCode());
    }
    @Test void defaultDenyAndSafeAuthenticationErrors()throws Exception {
        var response=request("/staff/accounts","GET");
        assertEquals(401,response.statusCode());assertTrue(response.body().contains("UNAUTHENTICATED"));
        assertTrue(response.headers().firstValue("X-Request-Id").isPresent());
        assertFalse(response.body().contains("password"));
        var staff=sessions.issue(1,SessionService.STAFF);
        assertEquals(403,request("/staff/accounts","GET","Cookie","A_CLINIC_SESSION="+staff.token()).statusCode());
    }

    private HttpResponse<String> postJson(String path,String json,String...headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if(headers.length>0)b.headers(headers);
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void loginAndPasswordChangeRotateEveryOldSession()throws Exception {
        var passwords=app.getBean(cn.dental.identity.application.StaffPassword.class);
        jdbc.update("UPDATE account SET password_hash=?,must_change_password=true WHERE account_id=1",passwords.encode("Initial-synthetic-2026"));
        var login=postJson("/auth/staff-login","{\"login_name\":\"Synthetic\",\"password\":\"Initial-synthetic-2026\"}","Cookie","A_CLINIC_SESSION=expired-cookie");
        assertEquals(200,login.statusCode(),login.body());
        String cookie=login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("Secure"));assertTrue(cookie.contains("SameSite=Lax"));
        String token=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
        assertFalse(login.body().contains(token));assertEquals("no-store",login.headers().firstValue("Cache-Control").orElseThrow());
        var json=app.getBean(tools.jackson.databind.json.JsonMapper.class).readTree(login.body());
        String csrf=json.get("data").get("csrf_token").asText();
        assertTrue(json.get("data").get("must_change_password").asBoolean());
        assertEquals(403,request("/staff/accounts","GET","Cookie","A_CLINIC_SESSION="+token).statusCode());
        var another=sessions.issue(1,SessionService.STAFF);
        String body="{\"current_password\":\"Initial-synthetic-2026\",\"new_password\":\"Replacement-synthetic-2026\"}";
        assertEquals(403,postJson("/auth/password-change",body,"Cookie","A_CLINIC_SESSION="+token).statusCode());
        var changed=postJson("/auth/password-change",body,"Cookie","A_CLINIC_SESSION="+token,"X-CSRF-Token",csrf);
        assertEquals(200,changed.statusCode(),changed.body());
        assertThrows(ApiException.class,()->sessions.authenticate(token,SessionService.STAFF));
        assertThrows(ApiException.class,()->sessions.authenticate(another.token(),SessionService.STAFF));
        var auth=app.getBean(cn.dental.identity.application.StaffAuthService.class);
        assertThrows(ApiException.class,()->auth.login("synthetic","Initial-synthetic-2026"));
        assertFalse(auth.login("synthetic","Replacement-synthetic-2026").mustChangePassword());
    }
    @Test void failedPasswordChangePreservesExistingSession(){
        var passwords=app.getBean(cn.dental.identity.application.StaffPassword.class);
        var auth=app.getBean(cn.dental.identity.application.StaffAuthService.class);
        jdbc.update("UPDATE account SET password_hash=? WHERE account_id=1",passwords.encode("Initial-synthetic-2026"));
        assertThrows(ApiException.class,()->auth.login("synthetic","wrong"));
        assertThrows(ApiException.class,()->auth.login("missing","wrong"));
        jdbc.update("UPDATE account SET enabled=false WHERE account_id=1");
        assertThrows(ApiException.class,()->auth.login("synthetic","Initial-synthetic-2026"));
        jdbc.update("UPDATE account SET enabled=true WHERE account_id=1");
        var session=auth.login("synthetic","Initial-synthetic-2026").session();
        var principal=sessions.authenticate(session.token(),SessionService.STAFF);
        assertThrows(ApiException.class,()->auth.change(principal,"wrong","Replacement-synthetic-2026"));
        assertThrows(ApiException.class,()->auth.change(principal,"Initial-synthetic-2026","short"));
        assertEquals(1,sessions.authenticate(session.token(),SessionService.STAFF).accountId());
    }
    @Test void bootstrapRejectsExistingInstallation(){
        var bootstrap=app.getBean(cn.dental.identity.application.BootstrapService.class);
        assertThrows(ApiException.class,()->bootstrap.initialize("合成管理员","new-admin","Initial-synthetic-2026","合成批准依据"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account_role",Integer.class));
    }

    @Test void bootstrapCreatesOnlyOneAdministratorAndAuditAtomically(){
        var tx=new org.springframework.transaction.support.TransactionTemplate(app.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(status->{
            jdbc.update("DELETE FROM audit_log");jdbc.update("DELETE FROM auth_session");jdbc.update("DELETE FROM account");jdbc.update("DELETE FROM staff");
            var bootstrap=app.getBean(cn.dental.identity.application.BootstrapService.class);
            long id=bootstrap.initialize("合成管理员","bootstrap-admin","Initial-synthetic-2026","负责人合成测试批准");
            assertTrue(jdbc.queryForObject("SELECT must_change_password FROM account WHERE account_id=?",Boolean.class,id));
            assertEquals("sysadmin",jdbc.queryForObject("SELECT r.code FROM account_role ar JOIN role r USING(role_id) WHERE ar.account_id=?",String.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM role_branch_scope WHERE account_id=?",Integer.class,id));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=?",Integer.class,id));
            assertThrows(ApiException.class,()->bootstrap.initialize("另一个人","second-admin","Initial-synthetic-2026","重复初始化"));
            status.setRollbackOnly();
        });
    }
}
