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
class AuthorizationIntegrationTest {
    private ConfigurableApplicationContext app;
    private SessionService sessions;private JdbcTemplate jdbc;private String base;
    private cn.dental.identity.application.AccountActivationService activation;
    private final HttpClient client=HttpClient.newHttpClient();
    @BeforeAll void start()throws Exception {
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        String schema="authorization_"+UUID.randomUUID().toString().replace("-","");
        try(var c=PostgresTestSupport.connect();var s=c.createStatement()){s.execute("CREATE SCHEMA "+schema);}
        app=new SpringApplication(AdenDentalApplication.class).run(
            "--spring.profiles.active=local","--spring.main.banner-mode=off","--server.address=127.0.0.1","--server.port=0",
            "--spring.datasource.url="+env.get("TEST_DB_URL")+"?currentSchema="+schema+",public",
            "--spring.datasource.username="+env.get("TEST_DB_USERNAME"),
            "--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema,
            "--app.mock.wechat-enabled=false","--app.seed.enabled=false");
        sessions=app.getBean(SessionService.class);jdbc=app.getBean(JdbcTemplate.class);
        activation=app.getBean(cn.dental.identity.application.AccountActivationService.class);
        base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'合成前台','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者','00000000000','未申报')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(1,1,'synthetic','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(2,1,true,false,now())");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(3,'合成员工乙','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(3,3,'synthetic-b','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(4,'合成目标员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at) VALUES(4,4,'synthetic-target','NOT-A-LOGIN-HASH',true,false,now())");
        jdbc.update("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(5,'合成改密员工','其他人员','前台','在岗')");
        jdbc.update("INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state) VALUES(1,'合成主体','TEST-ORG','00000000000',1,'营业')");
        for(long branch=1;branch<=2;branch++){
            jdbc.update("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) VALUES(?,1,?,'合成门店','合成地址','00000000000',?,'09:00-18:00',1,'营业',1,now())",branch,"TEST-B"+branch,"TEST-L"+branch);
            jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(3,?,true,now())",branch);
            jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(4,?,true,now())",branch);
            jdbc.update("INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES(5,?,true,now())",branch);
        }

    }
    @AfterAll void stop(){if(app!=null)app.close();}
    @BeforeEach void reset(){
        jdbc.update("DELETE FROM audit_log");jdbc.update("DELETE FROM role_branch_scope");jdbc.update("DELETE FROM account_role");jdbc.update("DELETE FROM auth_session");
        jdbc.update("DELETE FROM account WHERE staff_id=5");
        jdbc.update("UPDATE account SET enabled=true,auth_version=1,must_change_password=false");jdbc.update("UPDATE staff_branch SET enabled=true");
        role(1,"sysadmin");
    }
    private void role(long account,String code){jdbc.update("INSERT INTO account_role(account_id,role_id,granted_at,approval_basis) SELECT ?,role_id,now(),'合成批准依据' FROM role WHERE code=?",account,code);}
    private void scope(long account,String role,long branch){jdbc.update("INSERT INTO role_branch_scope(account_id,role_id,branch_id) SELECT ?,role_id,? FROM role WHERE code=?",account,branch,role);}
    private int version(long account){return jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=?",Integer.class,account);}
    private long insertPending(long staffId,String login){
        long id=jdbc.queryForObject("SELECT coalesce(max(account_id),0)+1 FROM account",Long.class);
        jdbc.update("INSERT INTO account(account_id,login_name,staff_id,enabled,password_hash,must_change_password,created_at) VALUES(?,?,?,false,NULL,true,now())",id,login,staffId);
        return id;
    }
    private SessionService.Principal principal(long account){var s=sessions.issue(account,SessionService.STAFF);return sessions.authenticate(s.token(),SessionService.STAFF);}

    private HttpResponse<String> request(String path,String method,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10)).method(method,HttpRequest.BodyPublishers.noBody());
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> putJson(String path,String body,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postJson(String path,String body,String... headers)throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(java.time.Duration.ofSeconds(10))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if(headers.length>0)builder.headers(headers);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String tokenOf(HttpResponse<String> r){String c=r.headers().firstValue("Set-Cookie").orElseThrow();return c.substring(c.indexOf('=')+1,c.indexOf(';'));}
    private String csrfOf(HttpResponse<String> r)throws Exception{return app.getBean(tools.jackson.databind.json.JsonMapper.class).readTree(r.body()).get("data").get("csrf_token").asText();}

    @Test void roleAndBranchMustBelongToSameGrant(){
        role(3,"frontdesk");role(3,"owner");scope(3,"frontdesk",1);scope(3,"owner",2);
        var p=principal(3);var auth=app.getBean(cn.dental.identity.application.AuthorizationService.class);
        assertDoesNotThrow(()->auth.requireBranch(p,"appointments.assist",1));
        assertDoesNotThrow(()->auth.requireBranch(p,"branch.configure",2));
        assertThrows(ApiException.class,()->auth.requireBranch(p,"appointments.assist",2));
        assertThrows(ApiException.class,()->auth.requireBranch(p,"branch.configure",1));
        assertThrows(ApiException.class,()->auth.requireBranch(principal(1),"appointments.assist",1));
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=3 AND branch_id=1");
        assertThrows(ApiException.class,()->auth.requireBranch(p,"appointments.assist",1));
    }
    @Test void selfGrantUnverifiedAssignmentAndPatientRolesAreRejected(){
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);var actor=principal(1);
        var own=java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("owner",java.util.List.of("1"),"合成批准"));
        assertThrows(ApiException.class,()->service.setRoles(actor,1,version(1),own));
        var patient=java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("patient",java.util.List.of(),"合成批准"));
        assertThrows(ApiException.class,()->service.setRoles(actor,3,version(3),patient));
        jdbc.update("UPDATE staff_branch SET enabled=false WHERE staff_id=3 AND branch_id=1");
        assertThrows(ApiException.class,()->service.setRoles(actor,3,version(3),own));
        var dentist=java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("dentist",java.util.List.of("2"),"合成批准"));
        assertThrows(ApiException.class,()->service.setRoles(actor,3,version(3),dentist));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=3",Integer.class));
    }
    @Test void replacementRevokesSessionsAndIdenticalRetryHasNoSideEffects(){
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);var actor=principal(1);var old=principal(3);
        var grants=java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("frontdesk",java.util.List.of("1"),"合成批准"));
        service.setRoles(actor,3,version(3),grants);
        assertThrows(ApiException.class,()->app.getBean(cn.dental.identity.application.AuthorizationService.class).requireCurrent(old));
        assertEquals(2,jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=3",Integer.class));
        var stale=assertThrows(ApiException.class,()->service.setRoles(actor,3,1,grants));
        assertEquals(cn.dental.shared.web.ApiErrorCode.VERSION_CONFLICT,stale.code());
        assertEquals(2,jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=3",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=3",Integer.class));
        service.setRoles(actor,3,version(3),grants);
        assertEquals(2,jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=3",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=3",Integer.class));
        assertTrue(jdbc.queryForObject("SELECT after_data FROM audit_log WHERE target_id=3",String.class).contains("合成批准"));
    }
    @Test void lastAdministratorCannotBeDisabled(){
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);var actor=principal(1);
        assertThrows(ApiException.class,()->service.disable(actor,1,"合成停用原因"));
        assertTrue(jdbc.queryForObject("SELECT enabled FROM account WHERE account_id=1",Boolean.class));
        role(3,"sysadmin");
        service.disable(actor,3,"合成停用原因");service.disable(actor,3,"重复停用");
        assertEquals(2,jdbc.queryForObject("SELECT auth_version FROM account WHERE account_id=3",Integer.class));
    }
    @Test void concurrentAdministratorsCannotDisableEachOtherLeavingNoAdmin()throws Exception {
        role(3,"sysadmin");var a=principal(1);var b=principal(3);var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var result=cn.dental.support.ConcurrentRunner.run(2,java.time.Duration.ofSeconds(15),()->{
            boolean first=index.getAndIncrement()==0;
            try {service.disable(first?a:b,first?3:1,"并发合成停用");return true;}catch(ApiException expected){return false;}
        });
        assertEquals(1,result.stream().filter(Boolean::booleanValue).count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account WHERE account_id IN (1,3) AND enabled",Integer.class));
    }
    @Test void meAndAccountListExposeOnlySafeFields()throws Exception {
        var s=sessions.issue(1,SessionService.STAFF);String cookie="A_CLINIC_SESSION="+s.token();
        var me=request("/auth/me","GET","Cookie",cookie);assertEquals(200,me.statusCode());assertTrue(me.body().contains("sysadmin"));
        assertFalse(me.body().contains("password_hash"));assertFalse(me.body().contains(s.token()));
        var list=request("/staff/accounts?limit=1","GET","Cookie",cookie);assertEquals(200,list.statusCode());assertTrue(list.body().contains("next_cursor"));assertFalse(list.body().contains("password_hash"));
        var other=sessions.issue(3,SessionService.STAFF);assertEquals(403,request("/staff/accounts","GET","Cookie","A_CLINIC_SESSION="+other.token()).statusCode());
    }

    @Test void roleMutationRequiresCsrfAndRevokesTargetSessionOverHttp()throws Exception {
        var admin=sessions.issue(1,SessionService.STAFF);var target=sessions.issue(3,SessionService.STAFF);
        var uri=URI.create(base+"/staff/accounts/3/role-scopes");
        var missingVersion=putJson("/staff/accounts/3/role-scopes","{\"roles\":[{\"role_code\":\"frontdesk\",\"branch_ids\":[\"1\"],\"approval_basis\":\"合成批准\"}]}","Cookie","A_CLINIC_SESSION="+admin.token(),"X-CSRF-Token",admin.csrfToken());
        assertEquals(422,missingVersion.statusCode(),missingVersion.body());
        String body="{\"expected_version\":1,\"roles\":[{\"role_code\":\"frontdesk\",\"branch_ids\":[\"1\"],\"approval_basis\":\"合成批准\"}]}";
        var missing=HttpRequest.newBuilder(uri).header("Content-Type","application/json").header("Cookie","A_CLINIC_SESSION="+admin.token()).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403,client.send(missing,HttpResponse.BodyHandlers.ofString()).statusCode());
        var valid=HttpRequest.newBuilder(uri).header("Content-Type","application/json").header("Cookie","A_CLINIC_SESSION="+admin.token()).header("X-CSRF-Token",admin.csrfToken()).PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(200,client.send(valid,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,request("/auth/me","GET","Cookie","A_CLINIC_SESSION="+target.token()).statusCode());
    }

    @Test void roleScopeReadIsAdminOnlyAndRejectsPatientTargets()throws Exception {
        role(3,"frontdesk");scope(3,"frontdesk",1);
        var admin=sessions.issue(1,SessionService.STAFF);String cookie="A_CLINIC_SESSION="+admin.token();
        assertEquals(200,request("/staff/accounts/3/role-scopes","GET","Cookie",cookie).statusCode());
        assertEquals(401,request("/staff/accounts/3/role-scopes","GET").statusCode());
        var plain=sessions.issue(3,SessionService.STAFF);
        assertEquals(403,request("/staff/accounts/3/role-scopes","GET","Cookie","A_CLINIC_SESSION="+plain.token()).statusCode());
        var patient=sessions.issue(2,SessionService.PATIENT);
        int patientStatus=request("/staff/accounts/3/role-scopes","GET","Cookie","A_CLINIC_SESSION="+patient.token()).statusCode();
        assertTrue(Set.of(401,403).contains(patientStatus),"患者会话不能读管理端授权，实际="+patientStatus);
        assertEquals(404,request("/staff/accounts/2/role-scopes","GET","Cookie",cookie).statusCode());
    }

    @Test void roleScopeDetailGroupsByRoleAndExcludesSensitiveFields()throws Exception {
        role(3,"frontdesk");scope(3,"frontdesk",1);
        role(3,"owner");scope(3,"owner",2);
        jdbc.update("UPDATE account_role SET approval_basis='owner-basis' WHERE account_id=3 AND role_id=(SELECT role_id FROM role WHERE code='owner')");
        var admin=sessions.issue(1,SessionService.STAFF);
        var response=request("/staff/accounts/3/role-scopes","GET","Cookie","A_CLINIC_SESSION="+admin.token());
        assertEquals(200,response.statusCode(),response.body());
        var json=app.getBean(tools.jackson.databind.json.JsonMapper.class);
        var data=json.readTree(response.body()).get("data");
        assertEquals("3",data.get("account_id").asText());
        assertEquals("active",data.get("state").asText());
        assertEquals(1,data.get("version").asInt());
        var roles=data.get("roles");
        assertEquals(2,roles.size());
        assertEquals("frontdesk",roles.get(0).get("role_code").asText());
        assertEquals("1",roles.get(0).get("branch_ids").get(0).asText());
        assertEquals("owner",roles.get(1).get("role_code").asText());
        assertEquals("2",roles.get(1).get("branch_ids").get(0).asText());
        assertEquals("owner-basis",roles.get(1).get("approval_basis").asText());
        assertFalse(response.body().contains("password_hash"));
        assertFalse(response.body().contains("patient"));
        assertFalse(response.body().contains("session"));
        assertFalse(response.body().contains("phone"));
    }

    @Test void staleExpectedVersionConflictsWithoutSideEffects()throws Exception {
        role(3,"frontdesk");scope(3,"frontdesk",1);
        var targetSession=sessions.issue(3,SessionService.STAFF);
        var targetPrincipal=sessions.authenticate(targetSession.token(),SessionService.STAFF);
        var admin=sessions.issue(1,SessionService.STAFF);
        var body="{\"expected_version\":99,\"roles\":[{\"role_code\":\"owner\",\"branch_ids\":[\"1\"],\"approval_basis\":\"合成批准\"}]}";
        var response=putJson("/staff/accounts/3/role-scopes",body,"Cookie","A_CLINIC_SESSION="+admin.token(),"X-CSRF-Token",admin.csrfToken());
        assertEquals(409,response.statusCode(),response.body());
        assertTrue(response.body().contains("VERSION_CONFLICT"));
        assertEquals(1,version(3));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=3 AND role_id=(SELECT role_id FROM role WHERE code='frontdesk')",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=3",Integer.class));
        assertDoesNotThrow(()->app.getBean(cn.dental.identity.application.AuthorizationService.class).requireCurrent(targetPrincipal));
    }

    @Test void twoAdministratorsWithSameBaselineOnlyOneUpdates()throws Exception {
        role(3,"sysadmin");var a=principal(1);var b=principal(3);
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);
        var grants=java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("owner",java.util.List.of("1"),"合成批准"));
        var index=new java.util.concurrent.atomic.AtomicInteger();
        var results=cn.dental.support.ConcurrentRunner.run(2,java.time.Duration.ofSeconds(15),()->{
            boolean first=index.getAndIncrement()==0;
            try{service.setRoles(first?a:b,4,1,grants);return "ok";}
            catch(ApiException e){return e.code().name();}
        });
        assertEquals(1,results.stream().filter("ok"::equals).count());
        assertEquals(1,results.stream().filter("VERSION_CONFLICT"::equals).count());
        assertEquals(2,version(4));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE target_id=4",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=4 AND role_id=(SELECT role_id FROM role WHERE code='owner')",Integer.class));
    }

    @Test void passwordChangeInvalidatesPreviouslyReadVersion()throws Exception {
        long id=insertPending(5,"change-pass");
        activation.activate("change-pass","Activation-pass-2026","合成激活依据");
        var login=postJson("/auth/staff-login","{\"login_name\":\"change-pass\",\"password\":\"Activation-pass-2026\"}");
        assertEquals(200,login.statusCode(),login.body());
        String token=tokenOf(login),csrf=csrfOf(login);
        int before=version(id);
        var changed=postJson("/auth/password-change","{\"current_password\":\"Activation-pass-2026\",\"new_password\":\"Replacement-pass-2026\"}","Cookie","A_CLINIC_SESSION="+token,"X-CSRF-Token",csrf);
        assertEquals(200,changed.statusCode(),changed.body());
        assertTrue(version(id)>before);
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);
        var stale=assertThrows(ApiException.class,()->service.setRoles(principal(1),id,before,
            java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("frontdesk",java.util.List.of("1"),"合成批准"))));
        assertEquals(cn.dental.shared.web.ApiErrorCode.VERSION_CONFLICT,stale.code());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=?",Integer.class,id));
    }

    @Test void disableInvalidatesPreviouslyReadRoleVersion()throws Exception {
        role(3,"frontdesk");scope(3,"frontdesk",1);int before=version(3);
        var service=app.getBean(cn.dental.identity.application.AccountAdminService.class);
        service.disable(principal(1),3,"合成停用原因");
        assertEquals(before+1,version(3));
        var stale=assertThrows(ApiException.class,()->service.setRoles(principal(1),3,before,
            java.util.List.of(new cn.dental.identity.application.AccountAdminService.Grant("owner",java.util.List.of("1"),"合成批准"))));
        assertEquals(cn.dental.shared.web.ApiErrorCode.VERSION_CONFLICT,stale.code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM account_role WHERE account_id=3 AND role_id=(SELECT role_id FROM role WHERE code='frontdesk')",Integer.class));
    }

    @Test void roleScopeReadReflectsTheVersionAndRolesFromTheSameUpdate()throws Exception {
        role(3,"frontdesk");scope(3,"frontdesk",1);
        var admin=sessions.issue(1,SessionService.STAFF);
        var json=app.getBean(tools.jackson.databind.json.JsonMapper.class);
        var before=json.readTree(request("/staff/accounts/3/role-scopes","GET","Cookie","A_CLINIC_SESSION="+admin.token()).body()).get("data");
        assertEquals(1,before.get("version").asInt());
        assertEquals("frontdesk",before.get("roles").get(0).get("role_code").asText());
        var body="{\"expected_version\":1,\"roles\":[{\"role_code\":\"owner\",\"branch_ids\":[\"1\"],\"approval_basis\":\"合成批准\"}]}";
        assertEquals(200,putJson("/staff/accounts/3/role-scopes",body,"Cookie","A_CLINIC_SESSION="+admin.token(),"X-CSRF-Token",admin.csrfToken()).statusCode());
        var after=json.readTree(request("/staff/accounts/3/role-scopes","GET","Cookie","A_CLINIC_SESSION="+admin.token()).body()).get("data");
        assertEquals(2,after.get("version").asInt());
        assertEquals(1,after.get("roles").size());
        assertEquals("owner",after.get("roles").get(0).get("role_code").asText());
    }
}
