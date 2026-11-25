ALTER TABLE appointment ADD COLUMN actual_started_at timestamptz;
ALTER TABLE appointment ADD COLUMN actual_dentist_id bigint REFERENCES staff(staff_id);
ALTER TABLE appointment ADD CONSTRAINT ck_actual_encounter_pair CHECK ((actual_started_at IS NULL) = (actual_dentist_id IS NULL));
COMMENT ON COLUMN appointment.actual_started_at IS '服务端记录的实际开始接诊时间；历史未知不回填';
