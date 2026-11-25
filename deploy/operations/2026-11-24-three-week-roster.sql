-- Repeatable classroom-only top-up. Run against a_dental_teacher_demo after backup.
-- Fills the next 21 Shanghai business dates without changing existing shifts,
-- appointments, clinical records, or manually cancelled assistant shifts.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'a_dental_teacher_demo'
     AND current_database() !~ '^a_dental_teacher_demo_test_[a-f0-9]{8}$' THEN
    RAISE EXCEPTION 'Three-week roster requires the dedicated demo database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM organization WHERE organization_id=901 AND credit_code='DEMO-ORG-901')
     OR (SELECT count(*) FROM branch WHERE branch_id BETWEEN 901 AND 904
           AND code='DEMO-0'||(branch_id-900)::text) <> 4
     OR (SELECT count(*) FROM staff WHERE (staff_id BETWEEN 911 AND 918 OR staff_id BETWEEN 921 AND 936)
           AND staff_type='牙医' AND practice_no='DEMO-PRACTICE-'||staff_id) <> 24
     OR (SELECT count(*) FROM staff WHERE staff_id BETWEEN 951 AND 962
           AND staff_type='其他人员' AND job_title='诊疗助手') <> 12 THEN
    RAISE EXCEPTION 'Three-week roster preconditions failed; no data changed';
  END IF;
END $$;

LOCK TABLE schedule, appointment, assistant_shift, branch_opening_interval IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO branch_opening_interval(branch_id,valid_on,starts_at,ends_at,version)
SELECT b.branch_id,d.work_day,
       (d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai',
       (d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai',1
FROM branch b
CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day
                    FROM generate_series(1,21) n) d
CROSS JOIN (VALUES (TIME '09:00',TIME '12:00'),(TIME '13:00',TIME '17:00')) t(start_time,end_time)
WHERE b.branch_id BETWEEN 901 AND 904
  AND NOT EXISTS (SELECT 1 FROM branch_opening_interval oi
    WHERE oi.branch_id=b.branch_id AND oi.valid_on=d.work_day
      AND oi.starts_at=(d.work_day+t.start_time) AT TIME ZONE 'Asia/Shanghai'
      AND oi.ends_at=(d.work_day+t.end_time) AT TIME ZONE 'Asia/Shanghai');

-- One assistant for each half-day; leave any existing or cancelled shift untouched.
INSERT INTO assistant_shift(staff_id,branch_id,starts_at,ends_at,state)
SELECT 951+(oi.branch_id-901)*3+
         (((oi.valid_on-DATE '2026-11-23') +
           CASE WHEN (oi.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00' THEN 0 ELSE 1 END) % 3),
       oi.branch_id,oi.starts_at,oi.ends_at,'已排班'
FROM branch_opening_interval oi
WHERE oi.branch_id BETWEEN 901 AND 904
  AND oi.valid_on > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND oi.valid_on <= (now() AT TIME ZONE 'Asia/Shanghai')::date+21
  AND ((oi.starts_at AT TIME ZONE 'Asia/Shanghai')::time,
       (oi.ends_at AT TIME ZONE 'Asia/Shanghai')::time)
      IN ((TIME '09:00',TIME '12:00'),(TIME '13:00',TIME '17:00'))
  AND NOT EXISTS (SELECT 1 FROM assistant_shift ash
    WHERE ash.branch_id=oi.branch_id AND ash.starts_at=oi.starts_at AND ash.ends_at=oi.ends_at);

-- Monday/Wednesday/Friday mornings have two dentists, other mornings one;
-- each afternoon has one. Never alter an existing published or booked shift.
DO $$
DECLARE b record; d record; half_day integer; desired integer; actual integer;
        chosen bigint; new_schedule bigint; shift_start time; shift_end time;
        added integer:=0;
BEGIN
  FOR b IN SELECT branch_id FROM branch WHERE branch_id BETWEEN 901 AND 904 ORDER BY branch_id LOOP
    FOR d IN SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day
             FROM generate_series(1,21) n ORDER BY 1 LOOP
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
          IF chosen IS NULL THEN
            RAISE EXCEPTION 'No qualified doctor for branch %, date %, half-day %',b.branch_id,d.work_day,half_day;
          END IF;
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
          added:=added+1;
        END LOOP;
      END LOOP;
    END LOOP;
  END LOOP;
  IF added>0 THEN
    INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
    VALUES ('系统','organization',901,'补齐虚构三周排班',NULL,
            json_build_object('new_doctor_shifts',added,'horizon_days',21)::text,
            '课程用虚构轮班；已有排班和预约未修改',clock_timestamp());
  END IF;
END $$;

DO $$
DECLARE b record; d record; morning_count integer; afternoon_count integer;
BEGIN
  FOR b IN SELECT branch_id FROM branch WHERE branch_id BETWEEN 901 AND 904 LOOP
    FOR d IN SELECT (now() AT TIME ZONE 'Asia/Shanghai')::date+n AS work_day
             FROM generate_series(1,21) n LOOP
      SELECT count(*) FILTER (WHERE (starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00'),
             count(*) FILTER (WHERE (starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00')
        INTO morning_count,afternoon_count FROM schedule
        WHERE branch_id=b.branch_id AND state='已发布'
          AND (starts_at AT TIME ZONE 'Asia/Shanghai')::date=d.work_day;
      IF morning_count < (CASE WHEN extract(isodow FROM d.work_day) IN (1,3,5) THEN 2 ELSE 1 END)
         OR afternoon_count<1 THEN
        RAISE EXCEPTION 'Incomplete doctor roster for branch %, date %',b.branch_id,d.work_day;
      END IF;
    END LOOP;
  END LOOP;
END $$;
COMMIT;

SELECT b.name AS branch_name,
       count(*) FILTER (WHERE (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '09:00') AS morning_shifts,
       count(*) FILTER (WHERE (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::time=TIME '13:00') AS afternoon_shifts
FROM branch b JOIN schedule sc ON sc.branch_id=b.branch_id AND sc.state='已发布'
WHERE b.branch_id BETWEEN 901 AND 904
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date > (now() AT TIME ZONE 'Asia/Shanghai')::date
  AND (sc.starts_at AT TIME ZONE 'Asia/Shanghai')::date <= (now() AT TIME ZONE 'Asia/Shanghai')::date+21
GROUP BY b.branch_id,b.name ORDER BY b.branch_id;
