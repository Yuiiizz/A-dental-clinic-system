package cn.dental.identity;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.SessionService;
import cn.dental.identity.application.WechatLoginThrottle;
import cn.dental.identity.integration.LocalWechatMock;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE",matches="dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WechatLoginIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;private JdbcTemplate jdbc;private String base;private JsonMapper json;
    private WechatLoginThrottle throttle;
    private final HttpClient client=HttpClient.newHttpClient();

    @BeforeAll void start()throws Exception {
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        String schema="wechat_"+UUID.randomUUID().toString().replace("-","");
        try(var c=PostgresTestSupport.connect();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        app=new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local","--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0",
            "--spring.datasource.url="+env.get("TEST_DB_URL")+"?currentSchema="+schema+",public",
            "--spring.datasource.username="+env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
            "--app.mock.wechat-enabled=true","--app.seed.enabled=false");
        sessions=app.getBean(SessionService.class);jdbc=app.getBean(JdbcTemplate.class);
        json=app.getBean(JsonMapper.class);throttle=app.getBean(WechatLoginThrottle.class);
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";
        // 合成已绑定患者（openid 对应 LocalWechatMock.BOUND_OPEN_ID）与一个合成员工账号（供跨端隔离）。
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(1,1,true,false,now())");
        jdbc.update("INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at) VALUES(1,'mock','"+LocalWechatMock.BOUND_OPEN_ID+"',now())");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(2,1,'staff-synthetic','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account))",Long.class);
    }
    @AfterAll void stop(){if(app!=null)app.close();}
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM auth_session");jdbc.update("DELETE FROM identity_challenge");
        jdbc.update("UPDATE account SET enabled=true WHERE account_id IN (1,2)");
    }

    private HttpResponse<String> request(String path,String method,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody());
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postWechat(String body,String... headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+"/auth/wechat")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(headers.length>0)b.headers(headers);
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void boundCodeReturnsValidPatientSession()throws Exception {
        var resp=postWechat("{\"code\":\""+LocalWechatMock.BOUND_CODE+"\"}");
        assertEquals(200,resp.statusCode(),resp.body());
        var data=json.readTree(resp.body()).get("data");
        assertEquals("session",data.get("kind").asText());
        String token=data.get("session_token").asText();
        assertFalse(data.has("registration_token"));
        assertDoesNotThrow(()->Instant.parse(data.get("expires_at").asText()));
        assertFalse(resp.body().contains("openid"));assertFalse(resp.body().contains("session_key"));
        var me=request("/auth/me","GET","Authorization","Bearer "+token);
        assertEquals(200,me.statusCode(),me.body());
        assertEquals("patient",json.readTree(me.body()).get("data").get("identity_type").asText());
    }

    @Test void unboundCodeReturnsRegistrationWithoutAccount()throws Exception {
        int accounts=jdbc.queryForObject("SELECT count(*) FROM account",Integer.class);
        int patients=jdbc.queryForObject("SELECT count(*) FROM patient",Integer.class);
        var resp=postWechat("{\"code\":\""+LocalWechatMock.UNBOUND_CODE+"\"}");
        assertEquals(200,resp.statusCode(),resp.body());
        var data=json.readTree(resp.body()).get("data");
        assertEquals("registration",data.get("kind").asText());
        String token=data.get("registration_token").asText();
        assertFalse(data.has("session_token"));
        Instant now=Instant.now(),expires=Instant.parse(data.get("registration_expires_at").asText());
        assertTrue(expires.isAfter(now.plus(Duration.ofMinutes(9))));
        assertTrue(expires.isBefore(now.plus(Duration.ofMinutes(11))));
        assertEquals(accounts,jdbc.queryForObject("SELECT count(*) FROM account",Integer.class));
        assertEquals(patients,jdbc.queryForObject("SELECT count(*) FROM patient",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM identity_challenge WHERE app_id='mock' AND open_id='"+LocalWechatMock.UNBOUND_OPEN_ID+"' AND purpose='wechat_registration' AND expires_at>now() AND expires_at<=now()+interval '11 minutes' AND created_at<expires_at",Integer.class));
        String hash=jdbc.queryForObject("SELECT token_hash FROM identity_challenge WHERE app_id='mock' AND open_id='"+LocalWechatMock.UNBOUND_OPEN_ID+"'",String.class);
        assertNotEquals(token,hash);
        assertEquals(SessionService.hash(token),hash);
    }

    @Test void disabledBindingRejectedNotRegistration()throws Exception {
        jdbc.update("UPDATE account SET enabled=false WHERE account_id=1");
        var resp=postWechat("{\"code\":\""+LocalWechatMock.BOUND_CODE+"\"}");
        assertEquals(403,resp.statusCode(),resp.body());
        assertTrue(resp.body().contains("FORBIDDEN"));
        assertFalse(resp.body().contains("\"kind\":\"registration\""));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM auth_session",Integer.class));
    }

    @Test void registrationTokenAsBearerRejected()throws Exception {
        var resp=postWechat("{\"code\":\""+LocalWechatMock.UNBOUND_CODE+"\"}");
        String token=json.readTree(resp.body()).get("data").get("registration_token").asText();
        assertEquals(401,request("/auth/me","GET","Authorization","Bearer "+token).statusCode());
    }

    @Test void patientSessionCannotAccessStaffEndpoint()throws Exception {
        var resp=postWechat("{\"code\":\""+LocalWechatMock.BOUND_CODE+"\"}");
        String token=json.readTree(resp.body()).get("data").get("session_token").asText();
        assertEquals(403,request("/staff/accounts","GET","Authorization","Bearer "+token).statusCode());
    }

    @Test void staffCookieDoesNotSubstituteWechatIdentity()throws Exception {
        var staff=sessions.issue(2,SessionService.STAFF);
        var resp=postWechat("{\"code\":\""+LocalWechatMock.UNBOUND_CODE+"\"}",
            "Cookie","A_CLINIC_SESSION="+staff.token(),"X-CSRF-Token",staff.csrfToken());
        assertEquals(200,resp.statusCode(),resp.body());
        assertEquals("registration",json.readTree(resp.body()).get("data").get("kind").asText());
    }

    @Test void unknownFieldRejected()throws Exception {
        var resp=postWechat("{\"code\":\""+LocalWechatMock.BOUND_CODE+"\",\"openid\":\"attacker\"}");
        assertEquals(422,resp.statusCode(),resp.body());
        assertTrue(resp.body().contains("UNKNOWN_FIELD"));
    }

    @Test void rateLimitEffective() {
        for(int i=0;i<30;i++)throttle.check("192.0.2.123");
        var e=assertThrows(ApiException.class,()->throttle.check("192.0.2.123"));
        assertEquals(ApiErrorCode.RATE_LIMITED,e.code());
    }
}
