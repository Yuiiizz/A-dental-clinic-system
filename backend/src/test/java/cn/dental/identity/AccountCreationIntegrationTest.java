package cn.dental.identity;

import cn.dental.AdenDentalApplication;
import cn.dental.identity.application.AccountActivationService;
import cn.dental.identity.application.AccountAdminService;
import cn.dental.identity.application.SessionService;
import cn.dental.shared.web.ApiErrorCode;
import cn.dental.shared.web.ApiException;
import cn.dental.support.ConcurrentRunner;
import cn.dental.support.PostgresTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE",matches="dental_test_[a-f0-9]{32}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountCreationIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;private JdbcTemplate jdbc;private String base;private JsonMapper json;
    private AccountAdminService accounts;private AccountActivationService activation;
    private final HttpClient client=HttpClient.newHttpClient();

    @BeforeAll void start()throws Exception {
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        String schema="accounts_"+UUID.randomUUID().toString().replace("-","");
        try(var c=PostgresTestSupport.connect();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        app=new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local","--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0",
            "--spring.datasource.url="+env.get("TEST_DB_URL")+"?currentSchema="+schema+",public",
            "--spring.datasource.username="+env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
            "--app.mock.wechat-enabled=false","--app.seed.enabled=false");
        sessions=app.getBean(SessionService.class);jdbc=app.getBean(JdbcTemplate.class);
        json=app.getBean(JsonMapper.class);accounts=app.getBean(AccountAdminService.class);activation=app.getBean(AccountActivationService.class);
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";
        // 先插入 staff/patient，再插入引用它们的 organization/branch/account。
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成管理员','其他人员','系统管理员','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(2,'合成员工甲','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(3,'离岗员工','其他人员','前台','离岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(4,'未核验员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(5,'冲突员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(6,'普通员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'admin-synthetic','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(6,6,'plain-synthetic','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,login_name,enabled,must_change_password,created_at) VALUES(2,1,'patient-synthetic',true,false,now())");
        jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT 1,role_id,now(),'合成批准' FROM role WHERE code='sysadmin'");
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(1,1,'TEST-B1','合成门店','合成地址','00000000000','TEST-L1','09:00-18:00',1,'营业',1,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(1,1,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(2,1,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(3,1,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(4,1,false,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(5,1,true,now())");
        jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(6,1,true,now())");
        // 显式指定账号编号不会推进 identity 序列，须同步，避免创建新账号时与 1/2/6 冲突。
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account))",Long.class);
    }
    @AfterAll void stop(){if(app!=null)app.close();}
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM audit_log");jdbc.update("DELETE FROM operation_request");jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM role_branch_scope");jdbc.update("DELETE FROM account_role WHERE account_id NOT IN (1,6)");
        jdbc.update("DELETE FROM account WHERE staff_id IN (2,3,4,5)");
        jdbc.update("UPDATE staff SET employment_state='在岗' WHERE staff_id IN (1,2,4,5,6)");
        jdbc.update("UPDATE staff SET employment_state='离岗' WHERE staff_id=3");
        jdbc.update("UPDATE staff_branch SET enabled=true WHERE staff_id IN (1,2,3,5,6)");
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=4");
    }

    private SessionService.Issued adminSession(){return sessions.issue(1,SessionService.STAFF);}
    private SessionService.Principal adminPrincipal(){var s=adminSession();return sessions.authenticate(s.token(),SessionService.STAFF);}
    private long insertPending(long staffId,String login){
        return jdbc.queryForObject("INSERT INTO account(login_name,staff_id,enabled,password_hash,must_change_password,created_at) VALUES(?,?,false,NULL,true,now()) RETURNING account_id",Long.class,login,staffId);
    }

    private HttpResponse<String> request(String path,String method,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody());
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postJson(String path,String body,String... headers)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(headers.length>0)b.headers(headers);
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> createAccount(String token,String csrf,String idemKey,String staffId,String loginName)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+"/staff/accounts"))
            .header("Content-Type","application/json").header("Cookie","A_CLINIC_SESSION="+token)
            .header("X-CSRF-Token",csrf).header("Idempotency-Key",idemKey)
            .POST(HttpRequest.BodyPublishers.ofString("{\"staff_id\":\""+staffId+"\",\"login_name\":\""+loginName+"\"}"));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String tokenOf(HttpResponse<String> r){String c=r.headers().firstValue("Set-Cookie").orElseThrow();return c.substring(c.indexOf('=')+1,c.indexOf(';'));}
    private String csrfOf(HttpResponse<String> r)throws Exception{return json.readTree(r.body()).get("data").get("csrf_token").asText();}
    private HttpResponse<String> disableAccount(String token,String csrf,long id,String reason)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+"/staff/accounts/"+id+"/disable"))
            .header("Content-Type","application/json").header("Cookie","A_CLINIC_SESSION="+token)
            .header("X-CSRF-Token",csrf)
            .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\""+reason+"\"}"));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void unauthenticatedMissingCsrfAndNonAdminAreRejected()throws Exception {
        assertEquals(401,createAccount("expired-cookie","x","k","2","alice").statusCode());
        var admin=adminSession();
        assertEquals(403,createAccount(admin.token(),"wrong-csrf","k","2","alice").statusCode());
        var plain=sessions.issue(6,SessionService.STAFF);
        assertEquals(403,createAccount(plain.token(),plain.csrfToken(),"k","2","alice").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
    }

    @Test void createsPendingAccountWithoutPasswordAndCannotLogin()throws Exception {
        var admin=adminSession();
        var resp=createAccount(admin.token(),admin.csrfToken(),"k1","2","Alice.Worker");
        assertEquals(201,resp.statusCode(),resp.body());
        var data=json.readTree(resp.body()).get("data");
        assertEquals("pending_activation",data.get("state").asText());
        assertFalse(data.get("enabled").asBoolean());
        assertTrue(data.get("must_change_password").asBoolean());
        assertEquals("alice.worker",data.get("login_name").asText());
        assertFalse(resp.body().contains("password_hash")); // 不得返回密码摘要；must_change_password 是期望字段
        var row=jdbc.queryForMap("SELECT enabled,password_hash FROM account WHERE staff_id=2");
        assertFalse((Boolean)row.get("enabled"));assertNull(row.get("password_hash"));
        assertEquals(401,postJson("/auth/staff-login","{\"login_name\":\"alice.worker\",\"password\":\"Whatever-2026\"}").statusCode());
    }

    @Test void sameKeyReplayReturnsSameAccountWithoutDuplication()throws Exception {
        var admin=adminSession();
        var first=createAccount(admin.token(),admin.csrfToken(),"replay","2","alice");
        assertEquals(201,first.statusCode());
        String id=json.readTree(first.body()).get("data").get("account_id").asText();
        var second=createAccount(admin.token(),admin.csrfToken(),"replay","2","alice");
        assertEquals(200,second.statusCode(),second.body());
        assertEquals(id,json.readTree(second.body()).get("data").get("account_id").asText());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
    }

    @Test void sameKeyDifferentContentConflicts()throws Exception {
        var admin=adminSession();
        assertEquals(201,createAccount(admin.token(),admin.csrfToken(),"k","2","alice").statusCode());
        var conflict=createAccount(admin.token(),admin.csrfToken(),"k","2","bob");
        assertEquals(409,conflict.statusCode());
        assertTrue(conflict.body().contains("IDEMPOTENCY_CONFLICT"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
    }

    @Test void differentKeySameStaffOrLoginNameRejected()throws Exception {
        var admin=adminSession();
        assertEquals(201,createAccount(admin.token(),admin.csrfToken(),"k1","2","shared-login").statusCode());
        var sameStaff=createAccount(admin.token(),admin.csrfToken(),"k2","2","shared-login");
        assertEquals(409,sameStaff.statusCode());assertTrue(sameStaff.body().contains("ACCOUNT_EXISTS"));
        var sameLogin=createAccount(admin.token(),admin.csrfToken(),"k3","5","shared-login");
        assertEquals(409,sameLogin.statusCode());assertTrue(sameLogin.body().contains("ACCOUNT_EXISTS"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=5",Integer.class));
    }

    @Test void offDutyUnverifiedAndMissingStaffRejected()throws Exception {
        var admin=adminSession();
        assertEquals(422,createAccount(admin.token(),admin.csrfToken(),"k1","3","alice").statusCode());
        assertEquals(422,createAccount(admin.token(),admin.csrfToken(),"k2","4","alice").statusCode());
        assertEquals(404,createAccount(admin.token(),admin.csrfToken(),"k3","999999","alice").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id IN (3,4)",Integer.class));
    }

    @Test void failureRollsBackIdempotencyAuditAndAccountThenSameKeyRecovers()throws Exception {
        var admin=adminSession();
        jdbc.update("UPDATE staff SET employment_state='离岗' WHERE staff_id=2");
        assertEquals(422,createAccount(admin.token(),admin.csrfToken(),"recover","2","alice").statusCode());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operation_request WHERE operation='staff-account-create'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='创建员工账号'",Integer.class));
        jdbc.update("UPDATE staff SET employment_state='在岗' WHERE staff_id=2");
        assertEquals(201,createAccount(admin.token(),admin.csrfToken(),"recover","2","alice").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
    }

    @Test void concurrentDifferentKeysCreateSingleAccount()throws Exception {
        var admin=adminPrincipal();
        var index=new AtomicInteger();
        var results=ConcurrentRunner.run(2,Duration.ofSeconds(15),()->{
            int k=index.getAndIncrement();
            try{accounts.create(admin,"2","concurrent","key-"+k);return "created";}
            catch(ApiException e){return e.code().name();}
        });
        assertEquals(1,results.stream().filter("created"::equals).count());
        assertEquals(1,results.stream().filter("ACCOUNT_EXISTS"::equals).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE staff_id=2",Integer.class));
    }

    @Test void activationEnablesAndForcesPasswordChangeThenLoginWorks()throws Exception {
        long id=insertPending(2,"alice");
        long activated=activation.activate("alice","Activation-pass-2026","合成激活依据");
        assertEquals(id,activated);
        var row=jdbc.queryForMap("SELECT enabled,password_hash,must_change_password FROM account WHERE account_id=?",id);
        assertTrue((Boolean)row.get("enabled"));assertNotNull(row.get("password_hash"));assertTrue((Boolean)row.get("must_change_password"));
        var login=postJson("/auth/staff-login","{\"login_name\":\"alice\",\"password\":\"Activation-pass-2026\"}");
        assertEquals(200,login.statusCode(),login.body());
        assertTrue(json.readTree(login.body()).get("data").get("must_change_password").asBoolean());
        assertEquals(401,postJson("/auth/staff-login","{\"login_name\":\"alice\",\"password\":\"Wrong-password-2026\"}").statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='员工账号首次激活'",Integer.class));
    }

    @Test void repeatActivationAndDisabledAccountCannotBeReenabled()throws Exception {
        long id=insertPending(2,"alice");
        activation.activate("alice","Activation-pass-2026","合成激活依据");
        var repeat=assertThrows(ApiException.class,()->activation.activate("alice","Another-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.FORBIDDEN,repeat.code());
        jdbc.update("UPDATE account SET enabled=false WHERE account_id=?",id);
        var disabled=assertThrows(ApiException.class,()->activation.activate("alice","Another-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.FORBIDDEN,disabled.code());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=?",Boolean.class,id));
    }

    @Test void offDutyUnverifiedAndPatientAccountsCannotActivate()throws Exception {
        long offDuty=insertPending(3,"off-duty-login");
        long unverified=insertPending(4,"unverified-login");
        var a=assertThrows(ApiException.class,()->activation.activate("off-duty-login","Activation-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.VALIDATION_FAILED,a.code());
        var b=assertThrows(ApiException.class,()->activation.activate("unverified-login","Activation-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.VALIDATION_FAILED,b.code());
        var patient=assertThrows(ApiException.class,()->activation.activate("patient-synthetic","Activation-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.NOT_FOUND,patient.code());
        assertFalse(jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=?",Boolean.class,offDuty));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=?",Boolean.class,unverified));
    }

    @Test void concurrentActivationSucceedsOnce()throws Exception {
        insertPending(2,"alice");
        var results=ConcurrentRunner.run(2,Duration.ofSeconds(15),()->{
            try{activation.activate("alice","Activation-pass-2026","合成激活依据");return "ok";}
            catch(ApiException e){return e.code().name();}
        });
        assertEquals(1,results.stream().filter("ok"::equals).count());
        assertEquals(1,results.stream().filter("FORBIDDEN"::equals).count());
        assertTrue(jdbc.queryForObject("SELECT enabled FROM account WHERE staff_id=2",Boolean.class));
    }

    @Test void meReflectsMustChangePasswordAndTurnsFalseAfterChange()throws Exception {
        insertPending(2,"alice");
        activation.activate("alice","Activation-pass-2026","合成激活依据");
        var login=postJson("/auth/staff-login","{\"login_name\":\"alice\",\"password\":\"Activation-pass-2026\"}");
        String token=tokenOf(login),csrf=csrfOf(login);
        var me1=request("/auth/me","GET","Cookie","A_CLINIC_SESSION="+token);
        assertEquals(200,me1.statusCode());
        assertTrue(json.readTree(me1.body()).get("data").get("must_change_password").asBoolean());
        assertFalse(me1.body().contains("password_hash"));
        var changed=postJson("/auth/password-change","{\"current_password\":\"Activation-pass-2026\",\"new_password\":\"Replacement-pass-2026\"}","Cookie","A_CLINIC_SESSION="+token,"X-CSRF-Token",csrf);
        assertEquals(200,changed.statusCode(),changed.body());
        var me2=request("/auth/me","GET","Cookie","A_CLINIC_SESSION="+tokenOf(changed));
        assertEquals(200,me2.statusCode());
        assertFalse(json.readTree(me2.body()).get("data").get("must_change_password").asBoolean());
    }

    @Test void createActivateDisableThenReplayReturnsDisabledAndCannotReactivate()throws Exception {
        var admin=adminSession();
        var created=createAccount(admin.token(),admin.csrfToken(),"lifecycle","2","alice");
        assertEquals(201,created.statusCode(),created.body());
        String id=json.readTree(created.body()).get("data").get("account_id").asText();
        activation.activate("alice","Activation-pass-2026","合成激活依据");
        accounts.disable(adminPrincipal(),Long.parseLong(id),"停用原因");
        var replay=createAccount(admin.token(),admin.csrfToken(),"lifecycle","2","alice");
        assertEquals(200,replay.statusCode(),replay.body());
        var data=json.readTree(replay.body()).get("data");
        assertEquals("disabled",data.get("state").asText());
        assertFalse(data.get("enabled").asBoolean());
        assertFalse(replay.body().contains("password_hash"));
        var re=assertThrows(ApiException.class,()->activation.activate("alice","Another-pass-2026","合成激活依据"));
        assertEquals(ApiErrorCode.FORBIDDEN,re.code());
    }

    @Test void pendingAccountDisableRejectedWithoutSuccessAudit()throws Exception {
        long id=insertPending(2,"alice");
        var admin=adminSession();
        var resp=disableAccount(admin.token(),admin.csrfToken(),id,"想取消激活");
        assertEquals(422,resp.statusCode(),resp.body());
        assertTrue(resp.body().contains("NOT_ACTIVATED"));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='停用员工账号'",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=?",Boolean.class,id));
        assertNull(jdbc.queryForObject("SELECT password_hash FROM account WHERE account_id=?",String.class,id));
    }

    @Test void disabledAccountRepeatDisableIsIdempotent()throws Exception {
        long id=insertPending(2,"alice");
        activation.activate("alice","Activation-pass-2026","合成激活依据");
        accounts.disable(adminPrincipal(),id,"停用原因");
        int version=jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=?",Integer.class,id);
        accounts.disable(adminPrincipal(),id,"重复停用");
        assertEquals(version,jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE operation='停用员工账号'",Integer.class));
    }
}
