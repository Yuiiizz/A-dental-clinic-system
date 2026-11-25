ALTER TABLE patient ADD COLUMN source_channel varchar(24);
ALTER TABLE patient ADD CONSTRAINT ck_patient_source_channel
  CHECK (source_channel IN ('现场', '电话', '转介绍', '微信小程序', '其他'));
COMMENT ON COLUMN patient.source_channel IS '建档时明确登记的获知渠道；历史未采集为 NULL，不作推断';
