package cn.dental.migration;

import cn.dental.support.PostgresTestSupport;
import cn.dental.support.ConcurrentRunner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DENTAL_TEST_DATABASE", matches="dental_test_[a-f0-9]{32}")
class SchemaMigrationTest {
    private String schema;
    @BeforeEach void setup() throws Exception {
        try (var c=PostgresTestSupport.connect(); var s=c.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public");
            schema="migration_"+UUID.randomUUID().toString().replace("-", "");
            s.execute("CREATE SCHEMA "+schema);
        }
    }
    private Flyway flyway(String target) {
        var env=System.getenv(); PostgresTestSupport.validateTarget(env);
        var config=Flyway.configure().dataSource(env.get("TEST_DB_URL"),env.get("TEST_DB_USERNAME"),env.get("TEST_DB_PASSWORD"))
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true);
        if (target!=null) config.target(target);
        return config.load();
    }
    private Connection connection() throws Exception {
        var c=PostgresTestSupport.connect();
        try (var s=c.createStatement()) {s.execute("SET search_path TO "+schema+",public");}
        return c;
    }
    private void execute(String sql) throws Exception {try(var c=connection();var s=c.createStatement()){s.execute(sql);}}
    private long number(String sql) throws Exception {try(var c=connection();var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private void rejects(String state,String sql) {var e=assertThrows(SQLException.class,()->execute(sql));assertEquals(state,e.getSQLState());}
    private void fixture() throws Exception {
        execute("""
            INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state) VALUES
            (1,'合成牙医甲','牙医','牙医','SYNTHETIC-1','在岗'),(2,'合成牙医乙','牙医','牙医','SYNTHETIC-2','在岗');
            INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state)
            VALUES(1,'合成测试主体','SYNTHETIC-ORG','00000000000',1,'营业');
            INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at)
            VALUES(1,1,'SYNTHETIC-A','合成门店','合成地址','00000000000','SYNTHETIC-LICENSE','09:00-18:00',1,'营业',1,now());
            INSERT INTO patient(patient_id,name,phone,allergy_status) VALUES(1,'合成患者甲','00000000000','未申报'),(2,'合成患者乙','00000000000','未申报');
            INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at) VALUES(1,1,true,false,now()),(2,2,true,false,now());
            INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,effective_at,approved_by,approved_at)
            VALUES(1,1,1,24,7,2,7,'published','2030-01-01T00:00:00Z',1,'2029-12-01T00:00:00Z');
            INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
            VALUES(1,1,'2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','两者','已发布',1,now(),0),
                  (2,2,'2030-01-02T01:00:00Z','2030-01-02T10:00:00Z','两者','已发布',1,now(),0);
            INSERT INTO resource_category(category_id,category_code,name,enabled) VALUES(1,'CHAIR','合成牙椅类',true);
            INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id) VALUES(1,'合成牙椅','牙椅',1,'可用',1);
            """);
    }
    private String appointment(long id,long patient,long dentist,String start,String end, String state, String extraColumns,String extraValues) {
        return "INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,request_key,request_hash,requested_by,branch_id,policy_id"+extraColumns+") VALUES ("+
            id+","+patient+","+dentist+","+dentist+",'2030-01-02T"+start+":00Z','2030-01-02T"+end+":00Z','初诊','合成测试','患者端','"+state+"','无需协调',now(),'"+UUID.randomUUID()+"','"+"a".repeat(64)+"',"+patient+",1,1"+extraValues+")";
    }
    @Test void procurementReceiptPostingIsAtomicAndLedgerFactsAreImmutable() throws Exception {
        flyway(null).migrate(); fixture();
        execute("INSERT INTO supplier(supplier_id,name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled) "
            + "VALUES(1,'合成供应商','SYNTH-SUP','合成凭证','2031-12-31',1,now(),true)");
        execute("INSERT INTO stock_item(stock_item_id,code,name,kind,specification,base_unit,storage_condition) "
            + "VALUES(1,'SYNTH-GLOVE','合成手套','耗材','M','只','常温')");
        execute("INSERT INTO stock_batch(batch_id,stock_item_id,manufacturer,batch_no,expires_on,state) "
            + "VALUES(1,1,'合成厂家','LOT-1','2031-01-01','合格')");
        execute("INSERT INTO warehouse(warehouse_id,branch_id,name) VALUES(1,1,'合成库位')");
        execute("INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at) "
            + "VALUES(2,1,'SYNTH-B','合成二店','合成地址','00000000000','SYNTH-L2','09:00-18:00',1,'营业',1,now())");
        execute("INSERT INTO warehouse(warehouse_id,branch_id,name) VALUES(2,2,'跨店库位')");
        rejects("23514", "INSERT INTO purchase_receipt(branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state,request_key) "
            + "VALUES(1,1,'BAD','SUP-1',now(),1,'已过账','internal-bad')");
        execute("INSERT INTO purchase_receipt(receipt_id,branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state,request_key) "
            + "VALUES(1,1,1,'SYNTH-R1','SUP-1',now(),1,'草稿','internal-r1')");
        rejects("23514", "UPDATE purchase_receipt SET state='已过账' WHERE receipt_id=1");
        rejects("23514", "INSERT INTO purchase_receipt_line(receipt_id,line_no,batch_id,warehouse_id,quantity,unit_cost) "
            + "VALUES(1,2,1,2,2.000,3.2500)");
        execute("INSERT INTO purchase_receipt_line(receipt_line_id,receipt_id,line_no,batch_id,warehouse_id,quantity,unit_cost) "
            + "VALUES(1,1,1,1,1,2.000,3.2500)");
        rejects("23514", "INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key) "
            + "VALUES(1,1,'采购入库',1.000,now(),now(),1,'错误数量','internal-m-bad')");
        rejects("23514", "INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key) "
            + "VALUES(1,1,'盘盈',2.000,now(),now(),1,'未开放类型','internal-m-other')");
        execute("INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key) "
            + "VALUES(1,1,'采购入库',2.000,now(),now(),1,'供货凭证','internal-m1')");
        rejects("23505", "INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key) "
            + "VALUES(1,1,'采购入库',2.000,now(),now(),1,'重复入库','internal-m2')");
        rejects("23514", "UPDATE purchase_receipt_line SET quantity=3 WHERE receipt_line_id=1");
        execute("UPDATE purchase_receipt SET branch_id=2 WHERE receipt_id=1");
        rejects("23514", "UPDATE purchase_receipt SET state='已过账' WHERE receipt_id=1");
        execute("UPDATE purchase_receipt SET branch_id=1 WHERE receipt_id=1");
        execute("UPDATE purchase_receipt SET state='已过账' WHERE receipt_id=1");
        assertEquals(2,number("SELECT sum(quantity_delta) FROM stock_movement WHERE warehouse_id=1"));
        rejects("23514", "UPDATE purchase_receipt SET supplier_document='改写' WHERE receipt_id=1");
        rejects("23514", "UPDATE purchase_receipt_line SET quantity=3 WHERE receipt_line_id=1");
        rejects("23514", "DELETE FROM stock_movement WHERE receipt_line_id=1");
        rejects("23514", "UPDATE stock_batch SET expires_on='2032-01-01' WHERE batch_id=1");
        rejects("23514", "UPDATE stock_item SET base_unit='箱' WHERE stock_item_id=1");
    }
    @Test void freshMigrationMatchesEveryDictionaryColumn() throws Exception {
        flyway(null).migrate();flyway(null).validate();assertEquals(0,flyway(null).migrate().migrationsExecuted);
        assertEquals(65,number("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name<>'flyway_schema_history'"));
        String text=Files.readString(Path.of("../specs/001-scheduling-booking/data-model.md"));
        String table=null;int checked=0;
        try(var c=connection()) {
            for(String line:text.substring(text.indexOf("## 最终字段清单")).split("\\R")) {
                if(line.startsWith("### ")) table=line.substring(4).split(" ")[0];
                if(!line.matches("\\| [a-z_]+ \\|.*"))continue;
                String[] cells=line.split("\\|",-1);String column=cells[1].trim(),type=cells[2].trim(),flags=cells[3].trim();
                try(var ps=c.prepareStatement("SELECT is_nullable, udt_name,character_maximum_length,numeric_precision,numeric_scale FROM information_schema.columns WHERE table_schema=? AND table_name=? AND column_name=?")) {
                    ps.setString(1,schema);ps.setString(2,table);ps.setString(3,column);
                    try(var r=ps.executeQuery()) {assertTrue(r.next(),table+"."+column);
                        assertEquals(flags.contains("PK")||Arrays.asList(flags.split(";")).contains("R")?"NO":"YES",r.getString(1),table+"."+column);
                        String expected=type.startsWith("varchar")?"varchar":type.startsWith("char(")?"bpchar":type.startsWith("decimal")||type.startsWith("numeric")?"numeric":switch(type){case "bigint"->"int8";case "integer"->"int4";case "boolean"->"bool";default->type;};
                        assertEquals(expected,r.getString(2),table+"."+column);
                        if(type.startsWith("varchar")||type.startsWith("char(")) assertEquals(Integer.parseInt(type.replaceAll("[^0-9]","")),r.getInt(3));
                        if(expected.equals("numeric")) {String[] ns=type.substring(type.indexOf('(')+1,type.indexOf(')')).split(",");assertEquals(Integer.parseInt(ns[0]),r.getInt(4));assertEquals(Integer.parseInt(ns[1]),r.getInt(5));}
                    }
                }
                for (String flag:flags.split(";")) {
                    if(flag.startsWith("FK:")) {
                        String[] target=flag.substring(3).split("[.]");boolean found=false;
                        try(var keys=c.getMetaData().getImportedKeys(null,schema,table)) {
                            while(keys.next()) if(column.equals(keys.getString("FKCOLUMN_NAME")) && target[0].equals(keys.getString("PKTABLE_NAME")) && target[1].equals(keys.getString("PKCOLUMN_NAME"))) found=true;
                        }
                        assertTrue(found,"Missing FK "+table+"."+column);
                    }
                    if(flag.equals("UQ")) {
                        try(var ps=c.prepareStatement("SELECT count(*) FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=i.indkey[0] WHERE i.indrelid=?::regclass AND i.indisunique AND i.indnkeyatts=1 AND a.attname=?")) {
                            ps.setString(1,schema+"."+table);ps.setString(2,column);
                            try(var r=ps.executeQuery()){r.next();assertTrue(r.getInt(1)>0,"Missing UQ "+table+"."+column);}
                        }
                    }
                }
                checked++;
            }
        }
        assertEquals(573,checked);assertEquals(7,number("SELECT count(*) FROM role"));
        assertEquals(65,number("SELECT count(*) FROM pg_constraint WHERE connamespace='"+schema+"'::regnamespace AND contype='p' AND conrelid <> '"+schema+".flyway_schema_history'::regclass"));
    }
    @Test void v007DoesNotBackfillHistoryForExistingCredentials() throws Exception {
        flyway("1").migrate();
        execute("INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state) VALUES(1,'历史员工','其他人员','员工','在岗')");
        execute("INSERT INTO credential(credential_id,staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed) VALUES(1,1,'other','LEGACY-CERT','2020-01-01','2030-01-01',1,now(),'pending',false)");
        flyway(null).migrate();
        assertEquals(1,number("SELECT version FROM credential WHERE credential_id=1"));
        assertEquals(0,number("SELECT count(*) FROM credential_review WHERE credential_id=1"));
    }

    @Test void upgradesExistingConfirmedAppointmentWithoutGuessingIntendedDoctor() throws Exception {
        flyway("1").migrate();fixture();
        execute(appointment(1,1,1,"01:00","01:30","已确认","", ""));
        execute("UPDATE appointment SET confirmed_dentist_id=NULL,intended_dentist_id=2 WHERE appointment_id=1");
        flyway(null).migrate();assertEquals(1,number("SELECT confirmed_dentist_id FROM appointment WHERE appointment_id=1"));
        assertEquals(2,number("SELECT intended_dentist_id FROM appointment WHERE appointment_id=1"));
        assertEquals(1,number("SELECT count(*) FROM appointment"));
    }
    @Test void ambiguousUpgradeRollsBack() throws Exception {
        flyway("1").migrate();fixture();execute(appointment(1,1,1,"01:00","01:30","已确认","", ""));
        execute("UPDATE appointment SET schedule_id=NULL,confirmed_dentist_id=NULL WHERE appointment_id=1");
        assertThrows(Exception.class,()->flyway(null).migrate());
        assertEquals(1,number("SELECT count(*) FROM flyway_schema_history WHERE success"));
        assertEquals(1,number("SELECT count(*) FROM appointment WHERE confirmed_dentist_id IS NULL"));
        assertEquals(0,number("SELECT count(*) FROM information_schema.tables WHERE table_schema='"+schema+"' AND table_name='operation_request'"));
    }
    @Test void enforcesIdentityPolicyAndRoleScope() throws Exception {
        flyway(null).migrate();fixture();
        rejects("23514","INSERT INTO account(enabled,must_change_password,created_at) VALUES(false,false,now())");
        rejects("23514","UPDATE account SET staff_id=1 WHERE account_id=1");
        execute("INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status) VALUES(2,1,2,24,7,2,7,'draft')");
        rejects("23514","UPDATE booking_policy SET status='published' WHERE version_no=2");
        rejects("23503","INSERT INTO role_branch_scope VALUES(1,(SELECT role_id FROM role WHERE code='frontdesk'),1)");
        rejects("23514","UPDATE patient SET allergy_status='有' WHERE patient_id=1");
    }
    @Test void blocksPatientDoctorAndResourceOverlapButAllowsAdjacentSlots() throws Exception {
        flyway(null).migrate();fixture();execute(appointment(1,1,1,"01:00","01:30","已确认","", ""));
        rejects("23P01",appointment(2,1,2,"01:10","01:40","已确认","", ""));
        rejects("23P01",appointment(3,2,1,"01:10","01:40","已确认","", ""));
        execute(appointment(4,1,1,"01:30","02:00","已确认","", ""));
        execute("INSERT INTO resource_booking(appointment_id,resource_id,starts_at,ends_at,state) VALUES(1,1,'2030-01-02T01:00Z','2030-01-02T01:40Z','有效')");
        rejects("23P01","INSERT INTO resource_booking(appointment_id,resource_id,starts_at,ends_at,state) VALUES(4,1,'2030-01-02T01:30Z','2030-01-02T02:00Z','有效')");
        execute("UPDATE appointment SET coordination_state='待协调' WHERE appointment_id=1");
        rejects("23P01",appointment(5,2,1,"01:10","01:20","已确认","", ""));
        rejects("23503","UPDATE appointment SET confirmed_dentist_id=2 WHERE appointment_id=1");
    }
    @Test void realConcurrentDoctorReservationHasOneWinner() throws Exception {
        flyway(null).migrate();fixture();
        var counter=new java.util.concurrent.atomic.AtomicLong(10);
        var results=ConcurrentRunner.run(2,Duration.ofSeconds(15),()->{
            long id=counter.incrementAndGet();
            try {execute(appointment(id,id==11?1:2,1,"02:00","02:30","已确认","", ""));return "ok";}
            catch(SQLException e){return e.getSQLState();}
        });
        assertEquals(1,results.stream().filter("ok"::equals).count(),results.toString());assertEquals(1,results.stream().filter(x -> x.equals("23P01") || x.equals("40P01")).count(),results.toString());
    }
    @Test void preservesConfirmedSuccessorAndIdempotencyScopes() throws Exception {
        flyway(null).migrate();fixture();execute(appointment(1,1,1,"01:00","01:30","已确认","", ""));
        execute(appointment(2,1,1,"03:00","03:30","已确认",",previous_id",",1"));
        execute("UPDATE appointment SET state='已取消',cancelled_at=now(),cancelled_by_type='patient',closure_reason='合成取消' WHERE appointment_id=2");
        rejects("23505",appointment(3,1,1,"04:00","04:30","已确认",",previous_id",",1"));
        String insert="INSERT INTO operation_request(operation_id,actor_account_id,operation,request_key,request_hash,created_at) VALUES(gen_random_uuid(),%d,'book','same-client-key','"+"b".repeat(64)+"',now())";
        execute(insert.formatted(1));execute(insert.formatted(2));rejects("23505",insert.formatted(1));
    }

    @Test void applicationStartupActuallyRunsFlyway() throws Exception {
        // Production deployment uses this Boot integration, not a manually invoked test-only migrator.
        var env=System.getenv();PostgresTestSupport.validateTarget(env);
        var app=new org.springframework.boot.SpringApplication(cn.dental.AdenDentalApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        try(var context=app.run("--spring.profiles.active=local", "--spring.main.banner-mode=off",
                "--app.mock.wechat-enabled=false", "--app.seed.enabled=false",
                "--spring.datasource.url="+env.get("TEST_DB_URL"),
                "--spring.datasource.username="+env.get("TEST_DB_USERNAME"),
                "--spring.flyway.default-schema="+schema,"--spring.flyway.schemas="+schema)) {
            // Password is supplied via the process environment by the harness, never command arguments.
            assertEquals(25,number("SELECT count(*) FROM flyway_schema_history WHERE success"));
            assertEquals(1,number("SELECT count(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='assistant_shift'"));
            assertEquals(2,number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND column_name='public_intro' AND table_name IN ('branch','staff')"));
            assertEquals(1,number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='patient' AND column_name='source_channel'"));
            assertEquals(2,number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='queue_ticket' AND column_name IN ('verification_basis','exception_reason')"));
            assertEquals(3,number("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='dentist_unavailable' AND column_name IN ('resolved_at','resolved_by','resolve_basis')"));
            assertEquals(2,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code IN ('inventory','owner') AND p.code='suppliers.manage'"));
            assertEquals(0,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code IN ('frontdesk','dentist','finance','sysadmin') AND p.code='suppliers.manage'"));
            assertEquals(1,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='sysadmin' AND p.code='accounts.manage'"));
            assertEquals(1,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='owner' AND p.code='branches.create'"));
            assertEquals(1,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='owner' AND p.code='staff.manage_global'"));
            assertEquals(0,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='sysadmin' AND p.code='staff.manage_global'"));
            assertEquals(0,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='sysadmin' AND p.code<>'accounts.manage'"));
            // 新权限只默认授予 frontdesk；owner/sysadmin 不隐式获得。
            assertEquals(1,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='frontdesk' AND p.code='patients.bind_issue'"));
            assertEquals(0,number("SELECT count(*) FROM role_permission rp JOIN role r USING(role_id) JOIN permission p USING(permission_id) WHERE r.code='owner' AND p.code='patients.bind_issue'"));
            assertNotNull(context.getBean(Flyway.class));
        }
    }
    @Test void v004RevokesLegacyBindingAndEnforcesActiveUniqueness() throws Exception {
        flyway("3").migrate();fixture();
        String legacyHash="a".repeat(64);
        execute("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+legacyHash+"',1,1,now()+interval '10 minutes',0,now())");
        flyway(null).migrate();
        // 历史绑定行保留且被撤销，缺 branch_id 不任填。
        assertEquals(1,number("SELECT count(*) FROM identity_challenge WHERE purpose='patient_binding'"));
        assertEquals(0,number("SELECT count(*) FROM identity_challenge WHERE purpose='patient_binding' AND revoked_at IS NULL"));
        // 过渡约束：缺 branch_id 且未撤销的新绑定行被拒。
        rejects("23514","INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+"b".repeat(64)+"',2,1,now()+interval '10 minutes',0,now())");
        // 部分唯一索引：同患者两条未消费且未撤销的绑定行被拒；过期但未消费未撤销的待用记录也占位。
        execute("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+"c".repeat(64)+"',2,1,1,now()+interval '10 minutes',0,now())");
        rejects("23505","INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+"d".repeat(64)+"',2,1,1,now()+interval '10 minutes',0,now())");
        rejects("23505","INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+"e".repeat(64)+"',2,1,1,now()-interval '1 minute',0,now()-interval '5 minutes')");
        // 显式撤销后允许再次签发同患者。
        String activeHash="c".repeat(64);
        execute("UPDATE identity_challenge SET revoked_at=now() WHERE token_hash='"+activeHash+"'");
        execute("INSERT INTO identity_challenge(challenge_id,purpose,token_hash,patient_id,issued_by,branch_id,expires_at,failed_attempts,created_at) VALUES(gen_random_uuid(),'patient_binding','"+"f".repeat(64)+"',2,1,1,now()+interval '10 minutes',0,now())");
        assertEquals(1,number("SELECT count(*) FROM identity_challenge WHERE patient_id=2 AND purpose='patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL"));
    }
    @Test void followupUniqueOnlyAppliesToActiveAppointments() throws Exception {
        flyway(null).migrate();fixture();execute(appointment(1,1,1,"01:00","01:30","已完成","", ""));
        execute("INSERT INTO visit_record(visit_id,appointment_id,dentist_id,allergy_result,started_at,completed_at) VALUES(1,1,1,'无已知','2030-01-02T01:00Z','2030-01-02T01:30Z')");
        execute("INSERT INTO followup_plan(plan_id,source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,state) VALUES(1,1,1,'2030-01-02','2030-01-03',30,'合成复诊','待协调')");
        execute(appointment(2,1,1,"03:00","03:30","已确认",",followup_plan_id",",1"));
        rejects("23505",appointment(3,1,1,"04:00","04:30","已确认",",followup_plan_id",",1"));
        execute("UPDATE appointment SET state='已取消',cancelled_at=now(),cancelled_by_type='patient',closure_reason='合成取消' WHERE appointment_id=2");
        execute(appointment(4,1,1,"04:00","04:30","已确认",",followup_plan_id",",1"));
    }
    @Test void followupServiceItemMigrationPreservesLegacyPlansAndRestrictsMissingItems() throws Exception {
        flyway(null).migrate(); fixture(); execute(appointment(1,1,1,"01:00","01:30","已完成","", ""));
        execute("INSERT INTO visit_record(visit_id,appointment_id,dentist_id,allergy_result,started_at,completed_at) VALUES(1,1,1,'无已知','2030-01-02T01:00Z','2030-01-02T01:30Z')");
        execute("INSERT INTO followup_plan(plan_id,source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,state) VALUES(1,1,1,'2030-01-02','2030-01-03',30,'原有复诊','待协调')");
        assertEquals(1, number("SELECT count(*) FROM followup_plan WHERE plan_id=1 AND service_item_id IS NULL"));
        rejects("23503", "UPDATE followup_plan SET service_item_id=999 WHERE plan_id=1");
        execute("INSERT INTO service_category(category_id,code,name,display_order,enabled) VALUES(1,'TEST','合成类别',1,true)");
        execute("INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id) VALUES(1,'合成复诊项目','次',100,true,true,30,1)");
        execute("UPDATE followup_plan SET service_item_id=1 WHERE plan_id=1");
        assertEquals(1, number("SELECT service_item_id FROM followup_plan WHERE plan_id=1"));
        rejects("23001", "DELETE FROM service_item WHERE item_id=1");
    }
    @Test void resourceRecoveryRequiresCompleteEvidence() throws Exception {
        flyway(null).migrate();fixture();
        execute("INSERT INTO resource_unavailable(unavailable_id,resource_id,kind,starts_at,ends_at,reason,state,recorded_by,recorded_at) VALUES(1,1,'故障','2020-01-01T00:00Z','2020-01-02T00:00Z','合成故障','有效',1,'2020-01-01T00:00Z')");
        assertEquals(1,number("SELECT count(*) FROM resource_unavailable WHERE state='有效'"));
        rejects("23514","UPDATE resource_unavailable SET state='已撤销' WHERE unavailable_id=1");
        execute("UPDATE resource_unavailable SET state='已撤销',resolved_at=now(),resolved_by=2,resolve_type='已修复',resolve_basis='合成核实' WHERE unavailable_id=1");
        assertEquals(1,number("SELECT recorded_by FROM resource_unavailable WHERE unavailable_id=1"));
        assertEquals(2,number("SELECT resolved_by FROM resource_unavailable WHERE unavailable_id=1"));
    }
}
