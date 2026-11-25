-- A follow-up may suggest one appointment service. Existing plans remain unspecified.
ALTER TABLE followup_plan
    ADD COLUMN service_item_id bigint;

ALTER TABLE followup_plan
    ADD CONSTRAINT fk_followup_plan_service_item_id
    FOREIGN KEY (service_item_id) REFERENCES service_item(item_id) ON DELETE RESTRICT;

CREATE INDEX ix_followup_plan_service_item_id
    ON followup_plan(service_item_id) WHERE service_item_id IS NOT NULL;

COMMENT ON COLUMN followup_plan.service_item_id IS '可空；计划建议的可预约服务项目，预约落实时必须一致';
