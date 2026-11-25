-- Append-only classroom cases for the existing dedicated demo database.
-- All people, clinical notes and money below are fictional; no actual payment occurred.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo' THEN
    RAISE EXCEPTION 'Refusing to modify a non-teaching database';
  END IF;
  IF EXISTS (SELECT 1 FROM patient WHERE patient_id IN (949,950))
     OR EXISTS (SELECT 1 FROM appointment WHERE appointment_id IN (960,961))
     OR EXISTS (SELECT 1 FROM visit_record WHERE visit_id IN (960,961))
     OR EXISTS (SELECT 1 FROM bill WHERE bill_id IN (960,961)) THEN
    RAISE EXCEPTION 'Teaching case IDs already exist; refusing duplicate import';
  END IF;
END $$;

INSERT INTO patient(patient_id,name,birth_date,phone,allergy_status,primary_dentist_id)
VALUES (949,'宋予宁','1999-04-08','13900000949','无已知',912),
       (950,'方若汐','1987-09-21','13900000950','无已知',913);

INSERT INTO appointment(appointment_id,patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
 starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,checked_in_at,
 actual_started_at,actual_dentist_id,consent_basis,version,request_key,request_hash,requested_by,branch_id,policy_id)
VALUES (960,949,10,912,912,'2026-11-18 03:00+00','2026-11-18 03:30+00',
        '初诊','初诊检查','现场','已完成','无需协调','2026-11-17 09:00+00','2026-11-18 02:55+00',
        '2026-11-18 03:00+00',912,'教学虚构场景：现场确认',3,'aaaaaaaa-aaaa-4aaa-8aaa-000000000960',repeat('a',64),902,901,901),
       (961,950,11,913,913,'2026-11-18 03:00+00','2026-11-18 03:30+00',
        '复诊','种植术后复查','现场','已完成','无需协调','2026-11-17 09:00+00','2026-11-18 02:55+00',
        '2026-11-18 03:00+00',913,'教学虚构场景：现场确认',3,'aaaaaaaa-aaaa-4aaa-8aaa-000000000961',repeat('b',64),902,902,902);
INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity)
VALUES (960,901,1),(961,904,1);
INSERT INTO visit_record(visit_id,appointment_id,dentist_id,allergy_result,allergy_snapshot,clinical_notes,started_at,completed_at)
VALUES (960,960,912,'无已知','教学虚构：已核对自述过敏情况','教学虚构：完成初诊检查，建议按需复查。','2026-11-18 03:00+00','2026-11-18 03:35+00'),
       (961,961,913,'无已知','教学虚构：已核对自述过敏情况','教学虚构：种植术后复查，建议继续观察并按期复诊。','2026-11-18 03:00+00','2026-11-18 03:35+00');
INSERT INTO followup_plan(plan_id,source_visit_id,dentist_id,suggested_from,suggested_to,duration_minutes,purpose,state)
VALUES (961,961,913,'2026-11-25','2026-12-09',30,'种植术后再次复查','待协调');

INSERT INTO bill(bill_id,patient_id,branch_id,visit_id,appointment_id,total_cents,paid_cents,status,version,created_at)
VALUES (960,949,901,960,960,8000,0,'待收款',1,'2026-11-18 03:40+00'),
       (961,950,902,961,961,16000,16000,'已收款',2,'2026-11-18 03:40+00');
INSERT INTO bill_item(item_id,bill_id,name,quantity,unit_price_cents,amount_cents)
VALUES (960,960,'初诊检查',1,8000,8000),(961,961,'种植术后复查',1,16000,16000);
INSERT INTO payment(payment_id,bill_id,amount_cents,method,state,received_by,received_at,note)
VALUES (961,961,16000,'其他线下','已登记',902,'2026-11-18 03:50+00','教学虚构收款案例；未发生真实资金收取');
INSERT INTO audit_log(actor_type,target_type,target_id,operation,reason,occurred_at)
SELECT '系统',kind,id,'导入教学虚构案例','仅用于课程展示；未发生真实诊疗或资金收取',clock_timestamp()
FROM (VALUES ('appointment',960),('appointment',961),('visit_record',960),('visit_record',961),
             ('followup_plan',961),('bill',960),('bill',961),('payment',961)) AS sample(kind,id);
-- Explicit fixture IDs must not collide with later normal UI inserts.
SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient),true);
SELECT setval(pg_get_serial_sequence('appointment','appointment_id'),(SELECT max(appointment_id) FROM appointment),true);
SELECT setval(pg_get_serial_sequence('visit_record','visit_id'),(SELECT max(visit_id) FROM visit_record),true);
SELECT setval(pg_get_serial_sequence('followup_plan','plan_id'),(SELECT max(plan_id) FROM followup_plan),true);
SELECT setval(pg_get_serial_sequence('bill','bill_id'),(SELECT max(bill_id) FROM bill),true);
SELECT setval(pg_get_serial_sequence('bill_item','item_id'),(SELECT max(item_id) FROM bill_item),true);
SELECT setval(pg_get_serial_sequence('payment','payment_id'),(SELECT max(payment_id) FROM payment),true);
COMMIT;
