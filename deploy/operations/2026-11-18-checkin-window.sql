BEGIN;
DO $$
DECLARE old_row branch_booking_config%ROWTYPE;
BEGIN
  SELECT * INTO STRICT old_row FROM branch_booking_config WHERE branch_id = 901 FOR UPDATE;
  IF old_row.early_minutes IS NOT NULL OR old_row.late_minutes IS NOT NULL
     OR old_row.self_checkin_enabled OR old_row.slot_step_minutes <> 30 THEN
    RAISE EXCEPTION 'booking config changed; refusing update';
  END IF;
  UPDATE branch_booking_config
     SET early_minutes = 30, late_minutes = 30, version = version + 1,
         updated_by = 901, updated_at = clock_timestamp()
   WHERE branch_id = 901;
  INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
  VALUES ('系统','branch_booking_config',901,'教学演示签到窗口配置',
          json_build_object('early_minutes',old_row.early_minutes,'late_minutes',old_row.late_minutes,'version',old_row.version)::text,
          json_build_object('early_minutes',30,'late_minutes',30,'version',old_row.version+1)::text,
          '2026-11-18 教学虚拟预约现场签到验收；不启用自助签到',clock_timestamp());
END $$;
COMMIT;
SELECT branch_id,self_checkin_enabled,early_minutes,late_minutes,slot_step_minutes,version
FROM branch_booking_config WHERE branch_id=901;
