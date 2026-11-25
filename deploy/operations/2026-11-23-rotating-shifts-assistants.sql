-- One-time, fictional classroom roster adjustment. Never run on a real clinic database.
-- Keeps every schedule referenced by an appointment unchanged.
BEGIN;

DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo'
     AND current_database() !~ '^a_dental_teacher_demo_test_[a-f0-9]{8}$' THEN
    RAISE EXCEPTION 'Classroom roster requires the dedicated demo database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization WHERE organization_id=901 AND credit_code='DEMO-ORG-901')
     OR (SELECT count(*) FROM branch WHERE branch_id BETWEEN 901 AND 904 AND code='DEMO-0'||(branch_id-900)::text) <> 4
     OR (SELECT count(*) FROM staff WHERE (staff_id BETWEEN 911 AND 918 OR staff_id BETWEEN 921 AND 924) AND staff_type='牙医') <> 12
     OR NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name='assistant_shift')
     OR EXISTS (SELECT 1 FROM staff WHERE staff_id BETWEEN 951 AND 962)
     OR EXISTS (SELECT 1 FROM assistant_shift WHERE staff_id BETWEEN 951 AND 962) THEN
    RAISE EXCEPTION 'Classroom roster preconditions failed; no data changed';
  END IF;
END $$;

-- Refresh the next fourteen business dates for every branch. Existing intervals stay intact.
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.work_day,
       (d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai',
       (d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai',1
FROM branch b
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day FROM generate_series(1,14) n) d
CROSS JOIN (VALUES (TIME '09:00',TIME '12:00'),(TIME '13:00',TIME '17:00')) t(start_time,end_time)
WHERE b.branch_id BETWEEN 901 AND 904
  AND NOT EXISTS (SELECT 1 FROM branch_opening_interval existing
      WHERE existing.branch_id=b.branch_id AND existing.valid_on=d.work_day
        AND existing.starts_at=(d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai'
        AND existing.ends_at=(d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai');

-- Add three non-login assistants to each fictional branch. One is off each day.
INSERT INTO staff(staff_id,name,staff_type,job_title,employment_state)
VALUES (951,'方小满','其他人员','诊疗助手','在岗'),
       (952,'许闻溪','其他人员','诊疗助手','在岗'),
       (953,'乔安','其他人员','诊疗助手','在岗'),
       (954,'程可心','其他人员','诊疗助手','在岗'),
       (955,'沈予宁','其他人员','诊疗助手','在岗'),
       (956,'吴青禾','其他人员','诊疗助手','在岗'),
       (957,'罗星晚','其他人员','诊疗助手','在岗'),
       (958,'江南枝','其他人员','诊疗助手','在岗'),
       (959,'叶知秋','其他人员','诊疗助手','在岗'),
       (960,'苏听澜','其他人员','诊疗助手','在岗'),
       (961,'孟沐','其他人员','诊疗助手','在岗'),
       (962,'林初一','其他人员','诊疗助手','在岗');
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
SELECT staff_id,901+(staff_id-951)/3,true,now() FROM staff WHERE staff_id BETWEEN 951 AND 962;

-- Use only existing future opening intervals; no clinical or appointment records are written.
INSERT INTO assistant_shift(staff_id,branch_id,starts_at,ends_at,state)
SELECT 951+(oi.branch_id-901)*3+
         (((oi.valid_on-DATE '2026-11-23') + CASE WHEN (oi.starts_at AT TIME ZONE 'Asia/Shanghai')::time < TIME '12:00' THEN 0 ELSE 1 END) % 3),
       oi.branch_id,oi.starts_at,oi.ends_at,'已排班'
FROM branch_opening_interval oi
WHERE oi.branch_id BETWEEN 901 AND 904
  AND oi.valid_on > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND (oi.starts_at AT TIME ZONE 'Asia/Shanghai')::time IN (TIME '09:00',TIME '13:00')
  AND (oi.ends_at AT TIME ZONE 'Asia/Shanghai')::time IN (TIME '12:00',TIME '17:00');

-- The original two branches had four dentists every morning. Rotate the unbooked
-- future schedules: one off, one afternoon, two morning per branch/date.
-- Existing appointment-linked schedules remain completely unchanged.
CREATE TEMP TABLE roster_candidates ON COMMIT DROP AS
SELECT sc.schedule_id,sc.dentist_id,sc.branch_id,
       (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date AS work_day,
       row_number() OVER (PARTITION BY sc.branch_id,(sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date
                          ORDER BY md5((sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date::text||':'||sc.dentist_id::text)) AS rank_no,
       count(*) OVER (PARTITION BY sc.branch_id,(sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date) AS candidate_count
FROM schedule sc
WHERE sc.branch_id IN (901,902) AND sc.dentist_id BETWEEN 911 AND 918
  AND sc.schedule_id BETWEEN 1 AND 112 AND sc.state='已发布'
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00'
  AND (sc.ends_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '12:00'
  AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.schedule_id=sc.schedule_id)
  AND EXISTS (SELECT 1 FROM branch_opening_interval oi WHERE oi.branch_id=sc.branch_id
      AND oi.valid_on=(sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date
      AND (oi.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00'
      AND (oi.ends_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '17:00')
  AND NOT EXISTS (SELECT 1 FROM schedule other WHERE other.dentist_id=sc.dentist_id
      AND other.schedule_id<>sc.schedule_id AND other.state='已发布'
      AND tstzrange(other.starts_at,other.ends_at,'[)') &&
          tstzrange(((sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date+TIME '13:00') AT TIME ZONE 'Asia/Shanghai',
                    ((sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date+TIME '17:00') AT TIME ZONE 'Asia/Shanghai','[)'));

-- Published intervals are immutable. Pause selected unbooked morning schedules,
-- then create separate afternoon schedules with fresh IDs and service mappings.
UPDATE schedule sc SET state='暂停',version=sc.version+1
FROM roster_candidates c WHERE sc.schedule_id=c.schedule_id
  AND ((c.candidate_count>=2 AND c.rank_no IN (1,2)) OR (c.candidate_count=1 AND c.rank_no=1));
WITH moved AS (
  INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
  SELECT c.dentist_id,(c.work_day+TIME '13:00') AT TIME ZONE 'Asia/Shanghai',
         (c.work_day+TIME '17:00') AT TIME ZONE 'Asia/Shanghai',
         '两者','已发布',c.branch_id,now(),0
  FROM roster_candidates c
  WHERE (c.candidate_count>=2 AND c.rank_no=2) OR (c.candidate_count=1 AND c.rank_no=1)
  RETURNING schedule_id,dentist_id
)
INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
SELECT moved.schedule_id,ds.item_id,i.suggested_minutes
FROM moved JOIN dentist_service ds ON ds.dentist_id=moved.dentist_id AND ds.enabled=true
JOIN service_item i ON i.item_id=ds.item_id AND i.enabled=true;

-- Fill only missing future doctor dates. The original branches rotate 2 morning,
-- 1 afternoon and 1 off; each newer branch has one morning and one afternoon.
WITH doctor_order AS (
  SELECT sb.branch_id,s.staff_id,
         row_number() OVER (PARTITION BY sb.branch_id ORDER BY s.staff_id)-1 AS doctor_rank
  FROM staff s JOIN staff_branch sb ON sb.staff_id=s.staff_id AND sb.enabled=true
  WHERE (s.staff_id BETWEEN 911 AND 918 OR s.staff_id BETWEEN 921 AND 924)
    AND sb.branch_id BETWEEN 901 AND 904 AND s.staff_type='牙医' AND s.employment_state='在岗'
), desired AS (
  SELECT doctor_order.branch_id,doctor_order.staff_id,d.work_day,
         CASE WHEN doctor_order.branch_id IN (901,902)
              THEN (doctor_order.doctor_rank+(d.work_day-DATE '2026-11-23')) % 4
              WHEN doctor_order.staff_id IN (922,924) THEN 1 ELSE 2 END AS shift_kind
  FROM doctor_order
  CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day FROM generate_series(1,14) n) d
), inserted AS (
  INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
  SELECT d.staff_id,
         (d.work_day+CASE WHEN d.shift_kind=1 THEN TIME '13:00' ELSE TIME '09:00' END) AT TIME ZONE 'Asia/Shanghai',
         (d.work_day+CASE WHEN d.shift_kind=1 THEN TIME '17:00' ELSE TIME '12:00' END) AT TIME ZONE 'Asia/Shanghai',
         '两者','已发布',d.branch_id,now(),0
  FROM desired d WHERE d.shift_kind<>0
    AND NOT EXISTS (SELECT 1 FROM schedule existing WHERE existing.dentist_id=d.staff_id
      AND (existing.starts_at AT TIME ZONE 'Asia/Shanghai')::date=d.work_day)
  RETURNING schedule_id,dentist_id
)
INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
SELECT inserted.schedule_id,ds.item_id,i.suggested_minutes
FROM inserted JOIN dentist_service ds ON ds.dentist_id=inserted.dentist_id AND ds.enabled=true
JOIN service_item i ON i.item_id=ds.item_id AND i.enabled=true;

SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff),true);
SELECT setval(pg_get_serial_sequence('assistant_shift','shift_id'),(SELECT max(shift_id) FROM assistant_shift),true);

INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
VALUES ('系统','organization',901,'调整虚构排班并加入诊疗助手',NULL,
        json_build_object('assistant_ids',ARRAY[951,952,953,954,955,956,957,958,959,960,961,962],
                          'roster_candidates',(SELECT count(*) FROM roster_candidates))::text,
        '课程用虚构轮班；任何已有预约关联排班保持原样',clock_timestamp());

DO $$
BEGIN
  IF (SELECT count(*) FROM staff WHERE staff_id BETWEEN 951 AND 962 AND job_title='诊疗助手')<>12
     OR (SELECT count(*) FROM assistant_shift WHERE staff_id BETWEEN 951 AND 962)<14
     OR EXISTS (SELECT 1 FROM appointment a JOIN roster_candidates c ON c.schedule_id=a.schedule_id) THEN
    RAISE EXCEPTION 'Classroom roster verification failed; rolling back';
  END IF;
END $$;
COMMIT;
