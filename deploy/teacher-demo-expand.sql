-- One-time expansion for an existing teaching-only dataset after V024.
-- All people, credentials, addresses and phone numbers are fictional. Never run on a real clinic database.
-- Run with psql -v ON_ERROR_STOP=1. A failed guard rolls back every change.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo'
     AND current_database() !~ '^a_dental_teacher_demo_test_[a-f0-9]{8}$' THEN
    RAISE EXCEPTION 'Teaching expansion requires the dedicated demo database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization WHERE organization_id=901 AND credit_code='DEMO-ORG-901')
     OR NOT EXISTS (SELECT 1 FROM branch WHERE branch_id=901 AND code='DEMO-01')
     OR NOT EXISTS (SELECT 1 FROM branch WHERE branch_id=902 AND code='DEMO-02')
     OR EXISTS (SELECT 1 FROM branch WHERE branch_id=901 AND address NOT IN ('教学虚拟地址一','示例地址一'))
     OR EXISTS (SELECT 1 FROM branch WHERE branch_id=902 AND address NOT IN ('教学虚拟地址二','示例地址二'))
     OR (SELECT count(*) FROM staff WHERE staff_id BETWEEN 911 AND 918
           AND practice_no='DEMO-PRACTICE-'||staff_id) <> 8
     OR NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='branch' AND column_name='public_intro')
     OR EXISTS (SELECT 1 FROM branch WHERE branch_id IN (903,904) OR code IN ('DEMO-03','DEMO-04'))
     OR EXISTS (SELECT 1 FROM staff WHERE staff_id BETWEEN 921 AND 924)
     OR EXISTS (SELECT 1 FROM resource WHERE resource_id BETWEEN 909 AND 912)
     OR EXISTS (SELECT 1 FROM account WHERE account_id BETWEEN 921 AND 924
           OR login_name IN ('dentist_009','dentist_010','dentist_011','dentist_012'))
  THEN RAISE EXCEPTION 'Teaching expansion preconditions failed; no data changed'; END IF;
END $$;

UPDATE branch SET address='示例地址一',public_intro='提供初诊检查、牙体修复和儿童防龋等预约服务。可先查看医生排班，再选择适合的日期与时段；具体可约情况以预约页面为准。'
WHERE branch_id=901 AND address IN ('教学虚拟地址一','示例地址一');
UPDATE branch SET address='示例地址二',public_intro='提供洁牙、牙周维护和术后复查等预约服务。门店出诊医生与可约时段会随已发布排班更新，请以预约页面为准。'
WHERE branch_id=902 AND address IN ('教学虚拟地址二','示例地址二');
UPDATE staff SET public_intro=CASE staff_id
  WHEN 911 THEN '提供初诊检查、牙周维护和树脂补牙等预约项目。具体出诊日期请查看排班。'
  WHEN 912 THEN '提供正畸复诊与儿童口腔相关预约项目。具体出诊日期请查看排班。'
  WHEN 913 THEN '提供种植术后复查与拔牙评估等预约项目。具体出诊日期请查看排班。'
  WHEN 914 THEN '提供牙周维护与洁牙等预约项目。具体出诊日期请查看排班。'
  WHEN 915 THEN '提供儿童防龋与窝沟封闭等预约项目。具体出诊日期请查看排班。'
  WHEN 916 THEN '提供全瓷冠复诊与初诊检查等预约项目。具体出诊日期请查看排班。'
  WHEN 917 THEN '提供拔牙评估与种植术后复查等预约项目。具体出诊日期请查看排班。'
  WHEN 918 THEN '提供树脂补牙与初诊检查等预约项目。具体出诊日期请查看排班。'
END WHERE staff_id BETWEEN 911 AND 918;

INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at,license_no_expiry_confirmed,public_intro)
VALUES (903,901,'DEMO-03','城东门店','示例地址三','00000000003','DEMO-LICENSE-03','每日 09:00–17:00',901,'营业',901,now(),true,
        '提供初诊检查、洁牙和牙体修复等预约服务。查看当天出诊医生后，可选择适合的时段。'),
       (904,901,'DEMO-04','城北门店','示例地址四','00000000004','DEMO-LICENSE-04','每日 09:00–17:00',901,'营业',901,now(),true,
        '提供儿童口腔检查、防龋和正畸复诊等预约服务。上午、下午的出诊安排请查看医生排班。');

INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state,public_intro)
VALUES (921,'顾予川','牙医','医师','DEMO-PRACTICE-921','在岗','提供初诊检查与树脂补牙等预约项目，上午出诊日期请查看排班。'),
       (922,'宋初晴','牙医','主治医师','DEMO-PRACTICE-922','在岗','提供初诊检查、洁牙与树脂补牙等预约项目，下午出诊日期请查看排班。'),
       (923,'姜予舟','牙医','医师','DEMO-PRACTICE-923','在岗','提供儿童口腔检查与防龋等预约项目，上午出诊日期请查看排班。'),
       (924,'温澄','牙医','主治医师','DEMO-PRACTICE-924','在岗','提供初诊检查与正畸复诊等预约项目，下午出诊日期请查看排班。');
INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed,version)
SELECT staff_id,'dentist_practice','DEMO-CREDENTIAL-'||staff_id,CURRENT_DATE-INTERVAL '1 year',CURRENT_DATE+INTERVAL '3 years',901,now(),'verified',false,1
FROM staff WHERE staff_id BETWEEN 921 AND 924;

INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at)
SELECT staff_id,staff_id,'dentist_'||lpad((staff_id-912)::text,3,'0'),NULL,false,true,now()
FROM staff WHERE staff_id BETWEEN 921 AND 924;
INSERT INTO account_role(account_id,role_id,granted_at,approval_basis)
SELECT a.account_id,r.role_id,now(),'示例医生账号预置，待人工激活'
FROM account a CROSS JOIN role r WHERE a.account_id BETWEEN 921 AND 924 AND r.code='dentist';
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
SELECT staff_id,CASE WHEN staff_id IN (921,922) THEN 903 ELSE 904 END,true,now()
FROM staff WHERE staff_id BETWEEN 921 AND 924;
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
SELECT staff_id,branch_id,true,now() FROM (VALUES (901,903),(901,904),(902,903),(902,904),(903,903),(903,904)) x(staff_id,branch_id);
INSERT INTO role_branch_scope(account_id,role_id,branch_id)
SELECT ar.account_id,ar.role_id,sb.branch_id FROM account_role ar
JOIN staff_branch sb ON sb.staff_id=ar.account_id
WHERE ar.account_id BETWEEN 921 AND 924 OR (ar.account_id IN (901,902,903) AND sb.branch_id IN (903,904));

INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable)
SELECT b.branch_id,i.item_id,i.current_price,true,true FROM branch b CROSS JOIN service_item i
WHERE b.branch_id IN (903,904) AND i.item_id BETWEEN 901 AND 911;
INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at)
SELECT dentist_id,item_id,true,now() FROM (VALUES
  (921,901),(921,908),(922,901),(922,905),(922,908),
  (923,901),(923,906),(923,911),(924,901),(924,903)) x(dentist_id,item_id);
INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id)
SELECT resource_id,'【示例】牙椅 '||resource_id,'牙椅',901,'可用',
  CASE WHEN resource_id IN (909,910) THEN 903 ELSE 904 END
FROM generate_series(909,912) resource_id;
INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,restriction_enabled,effective_at,approved_by,approved_at)
SELECT branch_id,branch_id,1,24,7,2,7,'published',false,now()-INTERVAL '1 day',901,now()-INTERVAL '1 day'
FROM branch WHERE branch_id IN (903,904);
INSERT INTO branch_booking_config(branch_id,self_checkin_enabled,early_minutes,late_minutes,slot_step_minutes,updated_by,updated_at)
VALUES (903,false,30,30,30,901,now()),(904,false,30,30,30,901,now());
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.day,(d.day+t.start_time) AT TIME ZONE 'Asia/Shanghai',
  (d.day+t.end_time) AT TIME ZONE 'Asia/Shanghai',1
FROM (VALUES (903),(904)) b(branch_id)
CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+n AS day FROM generate_series(1,14) n) d
CROSS JOIN (VALUES (TIME '09:00',TIME '12:00'),(TIME '13:00',TIME '17:00')) t(start_time,end_time);
INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
SELECT s.staff_id,(d.day+CASE WHEN s.staff_id IN (921,923) THEN TIME '09:00' ELSE TIME '13:00' END) AT TIME ZONE 'Asia/Shanghai',
  (d.day+CASE WHEN s.staff_id IN (921,923) THEN TIME '12:00' ELSE TIME '17:00' END) AT TIME ZONE 'Asia/Shanghai',
  '两者','已发布',sb.branch_id,now(),0
FROM staff s JOIN staff_branch sb ON sb.staff_id=s.staff_id
CROSS JOIN LATERAL (SELECT (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')::date+n AS day FROM generate_series(1,14) n) d
WHERE s.staff_id BETWEEN 921 AND 924;
INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
SELECT sc.schedule_id,ds.item_id,si.suggested_minutes FROM schedule sc
JOIN dentist_service ds ON ds.dentist_id=sc.dentist_id JOIN service_item si ON si.item_id=ds.item_id
WHERE sc.dentist_id BETWEEN 921 AND 924;

-- Explicit fixture IDs must not collide with later records created through the UI.
SELECT setval(pg_get_serial_sequence('branch','branch_id'),(SELECT max(branch_id) FROM branch),true);
SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff),true);
SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account),true);
SELECT setval(pg_get_serial_sequence('resource','resource_id'),(SELECT max(resource_id) FROM resource),true);
SELECT setval(pg_get_serial_sequence('booking_policy','policy_id'),(SELECT max(policy_id) FROM booking_policy),true);

INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
VALUES ('系统','organization',901,'扩充虚构门店医生目录',NULL,
        json_build_object('branch_ids',ARRAY[903,904],'doctor_ids',ARRAY[921,922,923,924],
                          'intro_fields','branch.public_intro,staff.public_intro')::text,
        '课程用虚构目录增量；不代表真实经营资质或临床出诊承诺',clock_timestamp());

DO $$
BEGIN
  IF (SELECT count(*) FROM branch WHERE branch_id IN (903,904) AND public_intro IS NOT NULL)<>2
     OR (SELECT count(*) FROM staff WHERE staff_id BETWEEN 921 AND 924 AND public_intro IS NOT NULL)<>4
     OR (SELECT count(*) FROM schedule WHERE dentist_id BETWEEN 921 AND 924 AND state='已发布')<>56
     OR (SELECT count(*) FROM branch_opening_interval WHERE branch_id IN (903,904))<>56 THEN
    RAISE EXCEPTION 'Directory expansion counts do not match; rolling back';
  END IF;
END $$;
COMMIT;
