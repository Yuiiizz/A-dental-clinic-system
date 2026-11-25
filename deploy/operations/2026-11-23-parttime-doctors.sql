-- One-time fictional expansion: six dentists per branch, rotating over the next 14 Shanghai dates.
-- Run only on the dedicated course database with psql -v ON_ERROR_STOP=1 after a full backup.
-- All people and credentials below are fictional. Never run on a real clinic database.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo'
     AND current_database() !~ '^a_dental_teacher_demo_test_[a-f0-9]{8}$' THEN
    RAISE EXCEPTION 'Part-time roster requires the dedicated demo database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization WHERE organization_id=901 AND credit_code='DEMO-ORG-901')
     OR (SELECT count(*) FROM branch WHERE branch_id BETWEEN 901 AND 904
         AND code='DEMO-0'||(branch_id-900)::text) <> 4
     OR (SELECT count(*) FROM staff WHERE (staff_id BETWEEN 911 AND 918 OR staff_id BETWEEN 921 AND 924)
         AND staff_type='牙医' AND practice_no='DEMO-PRACTICE-'||staff_id) <> 12
     OR (SELECT count(*) FROM staff WHERE staff_id BETWEEN 951 AND 962
         AND staff_type='其他人员') <> 12
     OR EXISTS (SELECT 1 FROM staff WHERE staff_id BETWEEN 925 AND 936)
     OR EXISTS (SELECT 1 FROM account WHERE staff_id BETWEEN 925 AND 936
         OR login_name IN (SELECT 'dentist_'||lpad(n::text,3,'0') FROM generate_series(13,24) n))
     OR NOT EXISTS (SELECT 1 FROM role WHERE code='dentist') THEN
    RAISE EXCEPTION 'Part-time roster preconditions failed; no data changed';
  END IF;
END $$;

-- Wait for any in-flight booking, then prevent a new appointment from linking to
-- a schedule while its unbooked status is checked and the roster is adjusted.
LOCK TABLE schedule,appointment IN SHARE ROW EXCLUSIVE MODE;

-- Keep every appointment-linked schedule, including cancelled appointments, byte-for-byte intact.
CREATE TEMP TABLE booked_schedule_before ON COMMIT DROP AS
SELECT DISTINCT sc.schedule_id,sc.dentist_id,sc.branch_id,sc.starts_at,sc.ends_at,
       sc.service_type,sc.state,sc.published_at,sc.version
FROM schedule sc JOIN appointment a ON a.schedule_id=sc.schedule_id;

INSERT INTO staff(staff_id,name,staff_type,job_title,practice_no,employment_state,public_intro)
VALUES
 (925,'梁知微','牙医','主治医师','DEMO-PRACTICE-925','在岗','提供初诊检查、洁牙和牙周维护等预约项目；出诊日期请查看排班。'),
 (926,'陆星禾','牙医','医师','DEMO-PRACTICE-926','在岗','提供初诊检查、儿童防龋和树脂补牙等预约项目；出诊日期请查看排班。'),
 (927,'贺清越','牙医','主治医师','DEMO-PRACTICE-927','在岗','提供初诊检查、洁牙和牙周复查等预约项目；出诊日期请查看排班。'),
 (928,'苏予宁','牙医','医师','DEMO-PRACTICE-928','在岗','提供初诊检查、正畸复诊和全瓷冠复诊等预约项目；出诊日期请查看排班。'),
 (929,'林栖月','牙医','医师','DEMO-PRACTICE-929','在岗','提供初诊检查、洁牙和树脂补牙等预约项目；出诊日期请查看排班。'),
 (930,'陈景初','牙医','主治医师','DEMO-PRACTICE-930','在岗','提供初诊检查、牙周维护和复查等预约项目；出诊日期请查看排班。'),
 (931,'许知禾','牙医','医师','DEMO-PRACTICE-931','在岗','提供初诊检查、儿童窝沟封闭和涂氟等预约项目；出诊日期请查看排班。'),
 (932,'沈望舒','牙医','主治医师','DEMO-PRACTICE-932','在岗','提供初诊检查、种植术后复查和拔牙评估等预约项目；出诊日期请查看排班。'),
 (933,'秦可安','牙医','医师','DEMO-PRACTICE-933','在岗','提供初诊检查、儿童窝沟封闭和涂氟等预约项目；出诊日期请查看排班。'),
 (934,'周以澄','牙医','主治医师','DEMO-PRACTICE-934','在岗','提供初诊检查和正畸复诊等预约项目；出诊日期请查看排班。'),
 (935,'方予白','牙医','医师','DEMO-PRACTICE-935','在岗','提供初诊检查、洁牙和树脂补牙等预约项目；出诊日期请查看排班。'),
 (936,'叶清和','牙医','主治医师','DEMO-PRACTICE-936','在岗','提供初诊检查、种植术后复查和拔牙评估等预约项目；出诊日期请查看排班。');
INSERT INTO credential(staff_id,credential_type,certificate_no,valid_from,valid_to,
                       verified_by,verified_at,verification_state,no_expiry_confirmed,version)
SELECT staff_id,'dentist_practice','DEMO-CREDENTIAL-'||staff_id,
       CURRENT_DATE-INTERVAL '1 year',CURRENT_DATE+INTERVAL '3 years',901,now(),'verified',false,1
FROM staff WHERE staff_id BETWEEN 925 AND 936;
INSERT INTO account(staff_id,login_name,password_hash,enabled,must_change_password,created_at)
SELECT staff_id,'dentist_'||lpad((staff_id-912)::text,3,'0'),NULL,false,true,now()
FROM staff WHERE staff_id BETWEEN 925 AND 936;
INSERT INTO account_role(account_id,role_id,granted_at,approval_basis)
SELECT a.account_id,r.role_id,now(),'虚构医生账号预置，待人工激活'
FROM account a CROSS JOIN role r WHERE a.staff_id BETWEEN 925 AND 936 AND r.code='dentist';
INSERT INTO staff_branch(staff_id,branch_id,enabled,verified_at)
SELECT staff_id,CASE WHEN staff_id<=926 THEN 901 WHEN staff_id<=928 THEN 902
                     WHEN staff_id<=932 THEN 903 ELSE 904 END,true,now()
FROM staff WHERE staff_id BETWEEN 925 AND 936;
INSERT INTO role_branch_scope(account_id,role_id,branch_id)
SELECT ar.account_id,ar.role_id,sb.branch_id FROM account_role ar
JOIN account a ON a.account_id=ar.account_id
JOIN staff_branch sb ON sb.staff_id=a.staff_id
WHERE a.staff_id BETWEEN 925 AND 936;
INSERT INTO dentist_service(dentist_id,item_id,enabled,verified_at)
SELECT dentist_id,item_id,true,now() FROM (VALUES
 (925,901),(925,902),(925,905),(925,910),
 (926,901),(926,906),(926,908),(926,911),
 (927,901),(927,902),(927,905),(927,910),
 (928,901),(928,903),(928,907),
 (929,901),(929,905),(929,908),
 (930,901),(930,902),(930,910),
 (931,901),(931,906),(931,911),
 (932,901),(932,904),(932,909),
 (933,901),(933,906),(933,911),
 (934,901),(934,903),
 (935,901),(935,905),(935,908),
 (936,901),(936,904),(936,909)) x(dentist_id,item_id);

-- Existing future slots in this fictional dataset are replaced only when never
-- referenced by any appointment. A booked doctor remains on their original shift.
UPDATE schedule sc SET state='暂停',version=sc.version+1
WHERE sc.branch_id BETWEEN 901 AND 904
  AND (sc.dentist_id BETWEEN 911 AND 918 OR sc.dentist_id BETWEEN 921 AND 924)
  AND sc.state='已发布'
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date <= (now() AT TIME ZONE 'Asia/Shanghai')::date+14
  AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.schedule_id=sc.schedule_id);

-- Ensure both business intervals exist on every date before publishing slots.
INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.work_day,
       (d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai',
       (d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai',1
FROM branch b
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day
                    FROM generate_series(1,14) n) d
CROSS JOIN (VALUES (TIME '09:00',TIME '12:00'),(TIME '13:00',TIME '17:00')) t(start_time,end_time)
WHERE b.branch_id BETWEEN 901 AND 904
  AND NOT EXISTS (SELECT 1 FROM branch_opening_interval oi
    WHERE oi.branch_id=b.branch_id AND oi.valid_on=d.work_day
      AND oi.starts_at=(d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai'
      AND oi.ends_at=(d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai');

-- Mon/Wed/Fri: two morning + one afternoon. Other days: one morning + one afternoon.
-- Each branch has six doctors; choose the least-used doctor in that Shanghai week.
DO $$
DECLARE b record; d record; half_day integer; desired integer; actual integer;
        chosen bigint; new_schedule bigint; shift_start time; shift_end time;
BEGIN
  FOR b IN SELECT branch_id FROM branch WHERE branch_id BETWEEN 901 AND 904 ORDER BY branch_id LOOP
    FOR d IN SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day
             FROM generate_series(1,14) n ORDER BY 1 LOOP
      FOR half_day IN 1..2 LOOP
        shift_start:=CASE WHEN half_day=1 THEN TIME '09:00' ELSE TIME '13:00' END;
        shift_end:=CASE WHEN half_day=1 THEN TIME '12:00' ELSE TIME '17:00' END;
        desired:=CASE WHEN half_day=1 AND extract(isodow FROM d.work_day) IN (1,3,5) THEN 2 ELSE 1 END;
        SELECT count(*) INTO actual FROM schedule sc
          WHERE sc.branch_id=b.branch_id AND sc.state='已发布'
            AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date=d.work_day
            AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=shift_start;
        WHILE actual<desired LOOP
          SELECT s.staff_id INTO chosen FROM staff s
          JOIN staff_branch sb ON sb.staff_id=s.staff_id AND sb.branch_id=b.branch_id
             AND sb.enabled=true AND sb.verified_at IS NOT NULL
          WHERE s.staff_type='牙医' AND s.employment_state='在岗'
            AND (s.staff_id BETWEEN 911 AND 918 OR s.staff_id BETWEEN 921 AND 936)
            AND EXISTS (SELECT 1 FROM credential c WHERE c.staff_id=s.staff_id
              AND c.credential_type='dentist_practice' AND c.verification_state='verified'
              AND c.valid_from<=d.work_day AND c.valid_to>=d.work_day)
            AND EXISTS (SELECT 1 FROM dentist_service ds WHERE ds.dentist_id=s.staff_id
              AND ds.item_id=901 AND ds.enabled=true)
            AND NOT EXISTS (SELECT 1 FROM schedule sc WHERE sc.dentist_id=s.staff_id
              AND sc.state='已发布'
              AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date=d.work_day)
            AND NOT EXISTS (SELECT 1 FROM dentist_unavailable du WHERE du.dentist_id=s.staff_id
              AND du.state='有效' AND tstzrange(du.starts_at,du.ends_at,'[)') &&
                tstzrange((d.work_day+shift_start) AT TIME ZONE 'Asia/Shanghai',
                          (d.work_day+shift_end) AT TIME ZONE 'Asia/Shanghai','[)'))
          ORDER BY (SELECT count(*) FROM schedule weekly
                    WHERE weekly.dentist_id=s.staff_id AND weekly.state='已发布'
                      AND (weekly.starts_at AT TIME ZONE 'Asia/Shanghai')::date
                        BETWEEN date_trunc('week',d.work_day::timestamp)::date
                            AND date_trunc('week',d.work_day::timestamp)::date+6),
                   md5(d.work_day::text||':'||b.branch_id::text||':'||half_day::text||':'||s.staff_id::text)
          LIMIT 1;
          IF chosen IS NULL THEN RAISE EXCEPTION 'No qualified doctor for branch %, date %, half-day %',b.branch_id,d.work_day,half_day; END IF;
          INSERT INTO schedule(dentist_id,starts_at,ends_at,service_type,state,branch_id,published_at,travel_buffer_minutes)
          VALUES (chosen,(d.work_day+shift_start) AT TIME ZONE 'Asia/Shanghai',
                  (d.work_day+shift_end) AT TIME ZONE 'Asia/Shanghai','两者','已发布',b.branch_id,now(),0)
          RETURNING schedule_id INTO new_schedule;
          INSERT INTO schedule_service(schedule_id,item_id,duration_minutes)
          SELECT new_schedule,ds.item_id,si.suggested_minutes FROM dentist_service ds
          JOIN service_item si ON si.item_id=ds.item_id AND si.enabled=true AND si.appointment_enabled=true
          JOIN branch_service bs ON bs.item_id=si.item_id AND bs.branch_id=b.branch_id AND bs.enabled=true
          WHERE ds.dentist_id=chosen AND ds.enabled=true;
          actual:=actual+1;
        END LOOP;
      END LOOP;
    END LOOP;
  END LOOP;
END $$;

SELECT setval(pg_get_serial_sequence('staff','staff_id'),(SELECT max(staff_id) FROM staff),true);
SELECT setval(pg_get_serial_sequence('account','account_id'),(SELECT max(account_id) FROM account),true);
INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
VALUES ('系统','organization',901,'扩充虚构医生轮班',NULL,
        json_build_object('doctor_ids',ARRAY[925,926,927,928,929,930,931,932,933,934,935,936],
                          'horizon_days',14,'appointment_linked_schedules','unchanged')::text,
        '课程用虚构轮班；不代表真实医生资质或出诊承诺',clock_timestamp());

DO $$
BEGIN
  IF (SELECT count(*) FROM staff WHERE staff_id BETWEEN 925 AND 936 AND staff_type='牙医')<>12
     OR EXISTS (SELECT 1 FROM booked_schedule_before old
       JOIN schedule sc ON sc.schedule_id=old.schedule_id
       WHERE (sc.dentist_id,sc.branch_id,sc.starts_at,sc.ends_at,sc.service_type,
              sc.state,sc.published_at,sc.version) IS DISTINCT FROM
             (old.dentist_id,old.branch_id,old.starts_at,old.ends_at,old.service_type,
              old.state,old.published_at,old.version))
     OR EXISTS (SELECT 1 FROM (VALUES (901),(902),(903),(904)) b(branch_id)
       WHERE (SELECT count(*) FROM staff_branch sb JOIN staff s ON s.staff_id=sb.staff_id
                WHERE sb.branch_id=b.branch_id AND sb.enabled=true AND s.staff_type='牙医')<>6) THEN
    RAISE EXCEPTION 'Part-time roster verification failed; rolling back';
  END IF;
END $$;
COMMIT;

SELECT b.name AS branch_name,count(DISTINCT sb.staff_id) FILTER (WHERE s.staff_type='牙医') AS doctors,
       count(*) FILTER (WHERE sc.state='已发布' AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00') AS morning_shifts,
       count(*) FILTER (WHERE sc.state='已发布' AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00') AS afternoon_shifts
FROM branch b JOIN staff_branch sb ON sb.branch_id=b.branch_id AND sb.enabled=true
JOIN staff s ON s.staff_id=sb.staff_id
LEFT JOIN schedule sc ON sc.dentist_id=s.staff_id AND sc.branch_id=b.branch_id
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date <= (now() AT TIME ZONE 'Asia/Shanghai')::date+14
WHERE b.branch_id BETWEEN 901 AND 904 AND s.staff_type='牙医'
GROUP BY b.branch_id,b.name ORDER BY b.branch_id;
