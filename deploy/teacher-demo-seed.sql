-- Classroom fixture, not a Flyway migration. Run once with psql -v ON_ERROR_STOP=1
-- against a fresh, dedicated database whose name contains teacher_demo.
-- All people, credentials, clinics and prices below are fictional.
BEGIN;
DO $$
BEGIN
  IF position('teacher_demo' in current_database()) = 0 THEN
    RAISE EXCEPTION 'Teacher demo seed requires a dedicated teacher_demo database';
  END IF;
  IF EXISTS (SELECT 1 FROM organization) OR EXISTS (SELECT 1 FROM branch)
     OR EXISTS (SELECT 1 FROM patient)
     OR EXISTS (SELECT 1 FROM staff s WHERE NOT EXISTS
       (SELECT 1 FROM account a JOIN account_role ar ON ar.account_id=a.account_id
        JOIN role r ON r.role_id=ar.role_id WHERE a.staff_id=s.staff_id AND r.code='sysadmin')) THEN
    RAISE EXCEPTION 'Teacher demo seed requires an empty business database';
  END IF;
END $$;

INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state)
VALUES (901,'程明远','其他人员','负责人',NULL,'在岗'),
       (902,'苏晴','其他人员','前台',NULL,'在岗'),
       (903,'陆知远','其他人员','库管',NULL,'在岗'),
       (911,'林知夏','牙医','主治医师','DEMO-PRACTICE-911','在岗'),
       (912,'周屿','牙医','副主任医师','DEMO-PRACTICE-912','在岗'),
       (913,'陈望','牙医','主治医师','DEMO-PRACTICE-913','在岗'),
       (914,'沈禾','牙医','医师','DEMO-PRACTICE-914','在岗'),
       (915,'许清禾','牙医','主治医师','DEMO-PRACTICE-915','在岗'),
       (916,'唐予安','牙医','主治医师','DEMO-PRACTICE-916','在岗'),
       (917,'贺景行','牙医','副主任医师','DEMO-PRACTICE-917','在岗'),
       (918,'叶澄','牙医','医师','DEMO-PRACTICE-918','在岗');
INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state)
VALUES (901,'A口腔诊所','DEMO-ORG-901','00000000000',901,'营业');
INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at,license_no_expiry_confirmed)
VALUES (901,901,'DEMO-01','云栖门店','示例地址一','00000000001','DEMO-LICENSE-01','每日 09:00–17:00',901,'营业',901,now(),true),
       (902,901,'DEMO-02','滨江门店','示例地址二','00000000002','DEMO-LICENSE-02','每日 09:00–17:00',901,'营业',901,now(),true);

-- Accounts are pending activation. No default password or mock WeChat identity is inserted.
INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at)
SELECT s.staff_id,s.staff_id,
       CASE s.staff_id WHEN 901 THEN 'owner' WHEN 902 THEN 'frontdesk'
            WHEN 903 THEN 'inventory' WHEN 911 THEN 'dentist_001'
            ELSE 'dentist_' || s.staff_id::text END,
       NULL,false,true,now()
FROM staff s WHERE s.staff_id IN (901,902,903,911,912,913,914,915,916,917,918);
INSERT INTO account_role(account_id,role_id,granted_at,approval_basis)
SELECT a.account_id,r.role_id,now(),'示例数据预置'
FROM account a JOIN role r ON r.code=CASE WHEN a.staff_id=901 THEN 'owner'
  WHEN a.staff_id=902 THEN 'frontdesk' WHEN a.staff_id=903 THEN 'inventory' ELSE 'dentist' END
WHERE a.account_id IN (901,902,903,911,912,913,914,915,916,917,918);
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
SELECT s.staff_id, CASE WHEN s.staff_id IN (913,914,917,918) THEN 902 ELSE 901 END,true,now()
FROM staff s WHERE s.staff_id IN (902,911,912,913,914,915,916,917,918);
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES (901,901,true,now()),(901,902,true,now());
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES (903,901,true,now()),(903,902,true,now());
INSERT INTO role_branch_scope(account_id,role_id,branch_id)
SELECT ar.account_id,ar.role_id,sb.branch_id FROM account_role ar
JOIN staff_branch sb ON sb.staff_id=ar.account_id WHERE ar.account_id IN (901,902,903,911,912,913,914,915,916,917,918);
INSERT INTO role_branch_scope(account_id,role_id,branch_id)
SELECT ar.account_id,ar.role_id,902 FROM account_role ar WHERE ar.account_id=902;
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at) VALUES (902,902,true,now());
INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed,version)
SELECT staff_id,'dentist_practice','DEMO-CREDENTIAL-' || staff_id,
  CURRENT_DATE - INTERVAL '1 year',CURRENT_DATE + INTERVAL '3 years',901,now(),'verified',false,1
FROM staff WHERE staff_id BETWEEN 911 AND 918;

-- Synthetic suppliers are manually verified teaching records; sample receipts below are not payable or paid transactions.
INSERT INTO supplier(supplier_id,name,credit_code,qualification_ref,valid_to,verified_by,verified_at,enabled)
VALUES (901,'口腔器材供应商甲','DEMO-SUP-901','示例资质核验依据',CURRENT_DATE + INTERVAL '3 years',901,now(),true),
       (902,'牙科材料供应商乙','DEMO-SUP-902','示例资质核验依据',CURRENT_DATE + INTERVAL '3 years',901,now(),true),
       (903,'消毒用品供应商丙','DEMO-SUP-903','示例资质核验依据',CURRENT_DATE + INTERVAL '3 years',901,now(),true);

-- V019 ordinary consumable examples. Each document is a fictional, posted receipt with a matching inbound movement.
INSERT INTO stock_item(stock_item_id,code,name,kind,specification,base_unit,storage_condition)
VALUES (901,'DEMO-CONS-01','一次性口镜','耗材','独立包装','件','常温干燥'),
       (902,'DEMO-CONS-02','医用防护面罩','耗材','标准规格','个','常温干燥'),
       (903,'DEMO-CONS-03','牙科棉卷','耗材','100 支/袋','袋','避光干燥'),
       (904,'DEMO-CONS-04','消毒指示卡','耗材','50 张/盒','盒','常温干燥');
INSERT INTO warehouse(warehouse_id,branch_id,name)
VALUES (901,901,'云栖主库'),(902,901,'云栖诊区库'),
       (903,902,'滨江主库');
INSERT INTO stock_batch(batch_id,stock_item_id,manufacturer,batch_no,expires_on,state,trace_reference)
VALUES (901,901,'【示例】器材生产企业甲','DEMO-BATCH-01',CURRENT_DATE + INTERVAL '2 years','合格','示例追溯号 01'),
       (902,902,'【示例】防护用品企业乙','DEMO-BATCH-02',CURRENT_DATE + INTERVAL '2 years','合格','示例追溯号 02'),
       (903,903,'【示例】棉品企业丙','DEMO-BATCH-03',CURRENT_DATE + INTERVAL '2 years','合格','示例追溯号 03'),
       (904,904,'【示例】消毒用品企业丁','DEMO-BATCH-04',CURRENT_DATE + INTERVAL '2 years','合格','示例追溯号 04');
INSERT INTO purchase_receipt(receipt_id,branch_id,supplier_id,document_no,supplier_document,received_at,accepted_by,state,request_key)
VALUES (901,901,901,'DEMO-RC-901','示例送货单 A',now(),903,'草稿',gen_random_uuid()::text),
       (902,901,902,'DEMO-RC-902','示例送货单 B',now(),903,'草稿',gen_random_uuid()::text),
       (903,902,903,'DEMO-RC-903','示例送货单 C',now(),903,'草稿',gen_random_uuid()::text);
INSERT INTO purchase_receipt_line(receipt_line_id,receipt_id,line_no,batch_id,warehouse_id,quantity,unit_cost)
VALUES (901,901,1,901,901,20.000,12.5000),
       (902,901,2,902,902,10.000,35.0000),
       (903,902,1,901,902,6.000,12.5000),
       (904,902,2,903,901,30.000,8.5000),
       (905,903,1,904,903,12.000,22.0000);
INSERT INTO stock_movement(receipt_line_id,warehouse_id,kind,quantity_delta,occurred_at,posted_at,operator_id,evidence,request_key)
SELECT l.receipt_line_id,l.warehouse_id,'采购入库',l.quantity,now(),now(),903,
  '【示例】采购验收 ' || r.document_no,gen_random_uuid()::text
FROM purchase_receipt_line l JOIN purchase_receipt r ON r.receipt_id=l.receipt_id
WHERE r.receipt_id BETWEEN 901 AND 903;
UPDATE purchase_receipt SET state='已过账' WHERE receipt_id BETWEEN 901 AND 903;

INSERT INTO service_category(category_id,code,name,display_order,enabled)
VALUES (901,'demo_general','综合',1,true),(902,'demo_child','儿童',2,true),
       (903,'demo_restore','修复',3,true),(904,'demo_surgery','外科',4,true);
INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id)
VALUES (901,'初诊检查','次',80,true,true,30,901),(902,'牙周维护','次',680,true,true,60,901),
       (903,'正畸复诊','次',280,true,true,30,901),(904,'种植术后复查','次',160,true,true,30,904),
       (905,'洁牙','次',180,true,true,45,901),(906,'儿童窝沟封闭','颗',120,true,true,30,902),
       (907,'全瓷冠复诊','次',160,true,true,30,903),(908,'树脂补牙','颗',360,true,true,45,901),
       (909,'拔牙评估','次',80,true,true,30,904),(910,'牙周复查','次',120,true,true,30,901),
       (911,'儿牙涂氟','次',80,true,true,30,902);
INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable)
SELECT b.branch_id,i.item_id,i.current_price,true,true FROM branch b CROSS JOIN service_item i
WHERE b.branch_id IN (901,902) AND i.item_id BETWEEN 901 AND 911;
INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at)
SELECT mapping.dentist_id,mapping.item_id,true,now() FROM (VALUES
 (911,901),(911,902),(911,908),(911,910),(912,901),(912,903),(912,911),
 (913,901),(913,904),(913,909),(914,901),(914,902),(914,905),(914,910),
 (915,901),(915,906),(915,911),(916,901),(916,907),(917,901),(917,904),(917,909),
 (918,901),(918,908)) AS mapping(dentist_id,item_id);
INSERT INTO resource_category(category_id,category_code,name,enabled)
VALUES (901,'demo_chair','牙椅',true);
INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id)
SELECT 900+n,'牙椅 ' || n,'牙椅',901,'可用',CASE WHEN n<=4 THEN 901 ELSE 902 END
FROM generate_series(1,8) n;
INSERT INTO service_resource_requirement(item_id,category_id,quantity,turnover_minutes)
SELECT item_id,901,1,0 FROM service_item WHERE item_id BETWEEN 901 AND 911;
INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,restriction_enabled,effective_at,approved_by,approved_at)
SELECT branch_id,branch_id,1,24,7,2,7,'published',false,now()-INTERVAL '1 day',901,now()-INTERVAL '1 day'
FROM branch WHERE branch_id IN (901,902);
INSERT INTO branch_booking_config(branch_id,self_checkin_enabled,early_minutes,late_minutes,slot_step_minutes,updated_by,updated_at)
VALUES (901,false,30,30,30,901,now()),(902,false,30,30,30,901,now());

-- Rolling 14-day horizon; business dates are computed in Asia/Shanghai.
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.day,(d.day+TIME '09:00') AT TIME ZONE 'Asia/Shanghai',
  (d.day+TIME '12:00') AT TIME ZONE 'Asia/Shanghai',1
FROM (VALUES(901),(902)) b(branch_id)
CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+n AS day
  FROM generate_series(1,14) n) d;
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.day,(d.day+TIME '13:00') AT TIME ZONE 'Asia/Shanghai',
  (d.day+TIME '17:00') AT TIME ZONE 'Asia/Shanghai',1
FROM (VALUES(901),(902)) b(branch_id)
CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+n AS day
  FROM generate_series(1,14) n) d;
INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
SELECT s.staff_id,(d.day+TIME '09:00') AT TIME ZONE 'Asia/Shanghai',
 (d.day+TIME '12:00') AT TIME ZONE 'Asia/Shanghai','两者','已发布',sb.branch_id,now(),0
FROM staff s JOIN staff_branch sb ON sb.staff_id=s.staff_id
CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+n AS day
  FROM generate_series(1,14) n) d
WHERE s.staff_id BETWEEN 911 AND 918;
INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
SELECT s.schedule_id,ds.item_id,i.suggested_minutes FROM schedule s
JOIN dentist_service ds ON ds.dentist_id=s.dentist_id JOIN service_item i ON i.item_id=ds.item_id
WHERE s.dentist_id BETWEEN 911 AND 918;

INSERT INTO patient(patient_id,name,birth_date,phone,allergy_status,primary_dentist_id)
SELECT 930+n,(ARRAY['顾安宁','林语桐','陈知远','许可欣','周予宁','唐一诺','沈嘉禾','叶书言'])[n],DATE '1990-01-01' + n * 100,
  '13000000' || lpad(n::text,3,'0'),'无已知',910+n
FROM generate_series(1,8) n;

-- One upcoming confirmed appointment per synthetic user. Each doctor has a separate chair.
INSERT INTO appointment(appointment_id,patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
 starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,
 request_key,request_hash,requested_by,branch_id,policy_id)
SELECT 940+m.n,930+m.n,s.schedule_id,m.dentist_id,m.dentist_id,
 (d.day+TIME '10:00') AT TIME ZONE 'Asia/Shanghai',
 (d.day+TIME '10:00') AT TIME ZONE 'Asia/Shanghai' + make_interval(mins => i.suggested_minutes),
 '初诊','示例预约','现场','已确认','无需协调',now(),
 gen_random_uuid()::text,repeat('d',64),902,s.branch_id,s.branch_id
FROM (VALUES (1,911,901),(2,912,903),(3,913,904),(4,914,905),
             (5,915,906),(6,916,907),(7,917,909),(8,918,908)) m(n,dentist_id,item_id)
CROSS JOIN (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+2 AS day) d
JOIN schedule s ON s.dentist_id=m.dentist_id
 AND s.starts_at=(d.day+TIME '09:00') AT TIME ZONE 'Asia/Shanghai'
JOIN service_item i ON i.item_id=m.item_id;
INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity)
SELECT 940+m.n,m.item_id,1 FROM (VALUES (1,901),(2,903),(3,904),(4,905),
 (5,906),(6,907),(7,909),(8,908)) m(n,item_id);
INSERT INTO resource_booking(appointment_id,resource_id,starts_at,ends_at,state)
SELECT a.appointment_id,900+m.chair,a.starts_at,a.ends_at,'有效'
FROM (VALUES (1,1),(2,2),(3,5),(4,6),(5,3),(6,4),(7,7),(8,8)) m(n,chair)
JOIN appointment a ON a.appointment_id=940+m.n;

-- Clearly synthetic internal conversation examples; no patient identity or clinical details.
INSERT INTO internal_staff_message(branch_id,frontdesk_staff_id,dentist_staff_id,sender_account_id,body,sent_at)
VALUES (901,902,911,902,'【示例】请核对明日上午排班；如需调整，请先按排班流程处理。',now()-INTERVAL '22 minutes'),
       (901,902,911,911,'【示例】排班已核对。若涉及预约改期，请先取得用户确认。',now()-INTERVAL '18 minutes'),
       (901,902,912,902,'【示例】本周复诊时段有咨询，请查看已发布排班。',now()-INTERVAL '12 minutes'),
       (901,902,912,912,'【示例】可先按当前号源查询，具体改约仍走正式流程。',now()-INTERVAL '8 minutes');

-- Keep identity sequences ahead of explicit fixture IDs so normal UI creation remains safe.
SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff),true);
SELECT setval(pg_get_serial_sequence('organization','organization_id'),(SELECT max(organization_id) FROM organization),true);
SELECT setval(pg_get_serial_sequence('branch','branch_id'),(SELECT max(branch_id) FROM branch),true);
SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account),true);
SELECT setval(pg_get_serial_sequence('credential','credential_id'),(SELECT max(credential_id) FROM credential),true);
SELECT setval(pg_get_serial_sequence('supplier','supplier_id'),(SELECT max(supplier_id) FROM supplier),true);
SELECT setval(pg_get_serial_sequence('stock_item','stock_item_id'),(SELECT max(stock_item_id) FROM stock_item),true);
SELECT setval(pg_get_serial_sequence('stock_batch','batch_id'),(SELECT max(batch_id) FROM stock_batch),true);
SELECT setval(pg_get_serial_sequence('warehouse','warehouse_id'),(SELECT max(warehouse_id) FROM warehouse),true);
SELECT setval(pg_get_serial_sequence('purchase_receipt','receipt_id'),(SELECT max(receipt_id) FROM purchase_receipt),true);
SELECT setval(pg_get_serial_sequence('purchase_receipt_line','receipt_line_id'),(SELECT max(receipt_line_id) FROM purchase_receipt_line),true);
SELECT setval(pg_get_serial_sequence('stock_movement','movement_id'),(SELECT max(movement_id) FROM stock_movement),true);
SELECT setval(pg_get_serial_sequence('service_category','category_id'),(SELECT max(category_id) FROM service_category),true);
SELECT setval(pg_get_serial_sequence('service_item','item_id'),(SELECT max(item_id) FROM service_item),true);
SELECT setval(pg_get_serial_sequence('resource_category','category_id'),(SELECT max(category_id) FROM resource_category),true);
SELECT setval(pg_get_serial_sequence('resource','resource_id'),(SELECT max(resource_id) FROM resource),true);
SELECT setval(pg_get_serial_sequence('booking_policy','policy_id'),(SELECT max(policy_id) FROM booking_policy),true);
SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient),true);
SELECT setval(pg_get_serial_sequence('appointment','appointment_id'),(SELECT max(appointment_id) FROM appointment),true);
COMMIT;
