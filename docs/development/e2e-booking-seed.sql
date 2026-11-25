-- ISOLATED DEMO/E2E FIXTURE ONLY.
-- Run only against a freshly migrated throwaway database, never against the user's working database.
-- It creates synthetic accounts, branch, opening intervals, dentist eligibility, services, schedules and a chair.
BEGIN;
INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state)
VALUES (1,'E2E 牙医','牙医','牙医','E2E-DENTIST-1','在岗'),
       (2,'E2E 前台','其他人员','前台',NULL,'在岗'),
       (3,'E2E 负责人','其他人员','负责人',NULL,'在岗');
INSERT INTO organization(organization_id,legal_name,credit_code,contact_phone,responsible_staff_id,state)
VALUES (1,'E2E 合成口腔','E2E-ORG','02000000000',3,'营业');
INSERT INTO branch(branch_id,organization_id,code,name,address,phone,license_no,opening_hours,manager_id,state,verified_by,verified_at)
VALUES (1,1,'E2E-A','E2E 云栖店','合成地址','02000000000','E2E-LICENSE','09:00-18:00',3,'营业',3,now());
INSERT INTO patient(patient_id,name,phone,allergy_status)
VALUES (1,'E2E 患者甲','13600000001','无已知'),(2,'E2E 患者乙','13600000002','无已知');
INSERT INTO account(account_id,staff_id,login_name,password_hash,enabled,must_change_password,created_at)
VALUES (1,1,'e2e_dentist','$2a$12$/rpG2f6KxyWO6jvfdjDrvOk9EYZJFapCqg2nxS10ESjaJ4V21nmO2',true,false,now()),
       (2,2,'e2e_frontdesk','$2a$12$/rpG2f6KxyWO6jvfdjDrvOk9EYZJFapCqg2nxS10ESjaJ4V21nmO2',true,false,now()),
       (3,3,'e2e_owner','$2a$12$/rpG2f6KxyWO6jvfdjDrvOk9EYZJFapCqg2nxS10ESjaJ4V21nmO2',true,false,now());
INSERT INTO account(account_id,patient_id,enabled,must_change_password,created_at)
VALUES (4,1,true,false,now());
INSERT INTO account_role(account_id,role_id,granted_at,approval_basis)
SELECT 1,role_id,now(),'E2E 合成授权' FROM role WHERE code='dentist'
UNION ALL SELECT 2,role_id,now(),'E2E 合成授权' FROM role WHERE code='frontdesk'
UNION ALL SELECT 3,role_id,now(),'E2E 合成授权' FROM role WHERE code='owner';
INSERT INTO role_branch_scope(account_id,role_id,branch_id)
SELECT ar.account_id,ar.role_id,1 FROM account_role ar WHERE ar.account_id IN (1,2,3);
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
VALUES (1,1,true,now()),(2,1,true,now()),(3,1,true,now());
INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,verified_by,verified_at,verification_state,no_expiry_confirmed,version)
VALUES (1,'dentist_practice','E2E-CRED-1','2026-01-01','2027-12-31',3,now(),'verified',false,1);
INSERT INTO wechat_identity(account_id,app_id,open_id,bound_at)
VALUES (4,'mock','mock-openid-bound-0001',now());
INSERT INTO service_category(category_id,code,name,display_order,enabled)
VALUES (1,'general','综合治疗',1,true);
INSERT INTO service_item(item_id,name,unit,current_price,enabled,appointment_enabled,suggested_minutes,category_id)
VALUES (1,'初诊检查','次',80.00,true,true,30,1),(2,'牙周维护','次',680.00,true,true,60,1);
INSERT INTO branch_service(branch_id,item_id,current_price,enabled,auto_bookable)
VALUES (1,1,80.00,true,true),(1,2,680.00,true,true);
INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at)
VALUES (1,1,true,now()),(1,2,true,now());
INSERT INTO booking_policy(policy_id,branch_id,version_no,late_hours,window_days,event_limit,restriction_days,status,effective_at,approved_by,approved_at)
VALUES (1,1,1,24,7,2,7,'published','2026-01-01T00:00:00Z',3,'2026-01-01T00:00:00Z');
INSERT INTO resource_category(category_id,category_code,name,enabled)
VALUES (1,'chair','牙椅',true);
INSERT INTO resource(resource_id,name,resource_type,category_id,operating_state,branch_id)
VALUES (1,'E2E 牙椅 1','牙椅',1,'可用',1);
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
VALUES (1,'2026-11-02','2026-11-02T01:00:00Z','2026-11-02T04:00:00Z',1),
       (1,'2026-11-02','2026-11-02T05:00:00Z','2026-11-02T10:00:00Z',1);
INSERT INTO schedule(schedule_id,dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
VALUES (1,1,'2026-11-02T01:00:00Z','2026-11-02T04:00:00Z','两者','已发布',1,now(),0),
       (2,1,'2026-11-02T06:00:00Z','2026-11-02T10:00:00Z','两者','已发布',1,now(),0);
INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
VALUES (1,1,30),(1,2,60),(2,1,30),(2,2,60);
INSERT INTO appointment(appointment_id,patient_id,schedule_id,confirmed_dentist_id,intended_dentist_id,starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,request_key,request_hash,requested_by,branch_id,policy_id,cancelled_at,cancelled_by_type,closure_reason)
VALUES (90,2,1,1,1,'2026-10-20T01:00:00Z','2026-10-20T01:30:00Z','初诊','history-link','现场','已取消','无需协调',gen_random_uuid(),repeat('c',64),2,1,1,now(),'clinic','synthetic history');
INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity) VALUES(90,1,1);COMMIT;
