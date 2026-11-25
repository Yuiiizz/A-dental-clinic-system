-- One fictional, completed historical visit for the already activated dentist_001.
-- Apply only to the dedicated teaching database after a verified backup.
-- This does not touch the in-progress appointment 941 or create a follow-up plan.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo' THEN
    RAISE EXCEPTION 'Refusing to modify a non-teaching database';
  END IF;
  IF EXISTS (SELECT 1 FROM patient WHERE patient_id=951 OR phone='13900000951')
     OR EXISTS (SELECT 1 FROM appointment WHERE appointment_id=962)
     OR EXISTS (SELECT 1 FROM visit_record WHERE visit_id=962) THEN
    RAISE EXCEPTION 'Teaching case already exists; refusing duplicate import';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM schedule
    WHERE schedule_id=1 AND dentist_id=911 AND branch_id=901 AND state='已发布'
      AND starts_at='2026-11-17 01:00+00'::timestamptz
      AND ends_at='2026-11-17 04:00+00'::timestamptz
  ) THEN
    RAISE EXCEPTION 'Expected historical schedule has changed';
  END IF;
  IF EXISTS (
    SELECT 1 FROM appointment
    WHERE confirmed_dentist_id=911 AND state IN ('已确认','候诊中','接诊中')
      AND starts_at < '2026-11-17 02:00+00'::timestamptz
      AND ends_at > '2026-11-17 01:30+00'::timestamptz
  ) THEN
    RAISE EXCEPTION 'Historical slot has an active appointment';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM account WHERE account_id=902 AND login_name='frontdesk' AND staff_id=902)
     OR NOT EXISTS (SELECT 1 FROM dentist_service WHERE dentist_id=911 AND item_id=901 AND enabled=true)
     OR NOT EXISTS (SELECT 1 FROM schedule_service WHERE schedule_id=1 AND item_id=901) THEN
    RAISE EXCEPTION 'Expected teaching account or service configuration has changed';
  END IF;
END $$;

INSERT INTO patient(patient_id,name,birth_date,phone,allergy_status,primary_dentist_id)
VALUES (951,'孟知遥','1994-06-12','13900000951','无已知',911);

INSERT INTO appointment(appointment_id,patient_id,schedule_id,intended_dentist_id,confirmed_dentist_id,
 starts_at,ends_at,visit_type,purpose,channel,state,coordination_state,confirmed_at,checked_in_at,
 actual_started_at,actual_dentist_id,consent_basis,version,request_key,request_hash,requested_by,branch_id,policy_id)
VALUES (962,951,1,911,911,'2026-11-17 01:30+00','2026-11-17 02:00+00',
        '初诊','初诊检查','现场','已完成','无需协调','2026-11-16 09:00+00','2026-11-17 01:25+00',
        '2026-11-17 01:30+00',911,'教学虚构场景：现场确认',3,
        'aaaaaaaa-aaaa-4aaa-8aaa-000000000962',repeat('c',64),902,901,901);

INSERT INTO appointment_item(appointment_id,item_id,estimated_quantity)
VALUES (962,901,1);

INSERT INTO visit_record(visit_id,appointment_id,dentist_id,allergy_result,allergy_snapshot,clinical_notes,started_at,completed_at)
VALUES (962,962,911,'无已知','教学虚构：已核对自述过敏情况',
        '教学虚构：完成初诊检查，建议按需复查；非真实医疗记录。',
        '2026-11-17 01:30+00','2026-11-17 02:05+00');

INSERT INTO audit_log(actor_type,target_type,target_id,operation,reason,occurred_at)
SELECT '系统',kind,962,'导入教学虚构案例','仅用于课程展示；未发生真实诊疗',clock_timestamp()
FROM (VALUES ('appointment'),('visit_record')) AS sample(kind);

SELECT setval(pg_get_serial_sequence('patient','patient_id'),(SELECT max(patient_id) FROM patient),true);
SELECT setval(pg_get_serial_sequence('appointment','appointment_id'),(SELECT max(appointment_id) FROM appointment),true);
SELECT setval(pg_get_serial_sequence('visit_record','visit_id'),(SELECT max(visit_id) FROM visit_record),true);
COMMIT;

SELECT a.appointment_id,a.state,a.version,v.visit_id,v.dentist_id,p.name
FROM appointment a JOIN patient p ON p.patient_id=a.patient_id
JOIN visit_record v ON v.appointment_id=a.appointment_id
WHERE a.appointment_id=962;
