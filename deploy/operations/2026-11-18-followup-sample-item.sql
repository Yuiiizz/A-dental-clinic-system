-- One-time classroom-only completion of the fictional follow-up example.
-- Requires V021 and an independently verified backup. Never rerun the full seed.
BEGIN;
DO $$
DECLARE sample_plan followup_plan%ROWTYPE;
BEGIN
  IF current_database() <> 'a_dental_teacher_demo' THEN
    RAISE EXCEPTION 'Refusing to modify a non-teaching database';
  END IF;
  SELECT * INTO STRICT sample_plan FROM followup_plan WHERE plan_id=961 FOR UPDATE;
  IF sample_plan.service_item_id IS NOT NULL OR sample_plan.source_visit_id<>961
     OR sample_plan.dentist_id<>913 OR sample_plan.state<>'待协调'
     OR NOT EXISTS (SELECT 1 FROM appointment_item ai
                    JOIN visit_record v ON v.appointment_id=ai.appointment_id
                    WHERE v.visit_id=961 AND ai.item_id=904)
     OR NOT EXISTS (SELECT 1 FROM dentist_service ds JOIN service_item i ON i.item_id=ds.item_id
                    WHERE ds.dentist_id=913 AND ds.item_id=904 AND ds.enabled
                      AND i.enabled AND i.appointment_enabled) THEN
    RAISE EXCEPTION 'Fictional follow-up facts changed; refusing update';
  END IF;
  UPDATE followup_plan SET service_item_id=904 WHERE plan_id=961;
  INSERT INTO audit_log(actor_type,target_type,target_id,operation,before_data,after_data,reason,occurred_at)
  VALUES ('系统','followup_plan',961,'补全教学虚构复诊项目',
          json_build_object('service_item_id',sample_plan.service_item_id)::text,
          json_build_object('service_item_id',904)::text,
          '仅补全课程虚构案例的种植术后复查项目，不代表真实临床建议',clock_timestamp());
END $$;
COMMIT;
SELECT plan_id,source_visit_id,dentist_id,service_item_id,state FROM followup_plan WHERE plan_id=961;
