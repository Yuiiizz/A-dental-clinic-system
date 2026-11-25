-- Finance records an independently verified offline refund; no payment-channel transfer is initiated here.
ALTER TABLE refund ADD COLUMN processing_basis varchar(300);
ALTER TABLE refund ADD CONSTRAINT ck_refund_processing_complete CHECK (
    (status = '待处理' AND processed_at IS NULL AND processed_by IS NULL AND processing_basis IS NULL)
    OR (status IN ('已退款','已拒绝') AND processed_at IS NOT NULL AND processed_by IS NOT NULL
        AND processing_basis IS NOT NULL AND btrim(processing_basis) <> '')
);
ALTER TABLE refund ADD CONSTRAINT fk_refund_processed_by FOREIGN KEY (processed_by) REFERENCES staff(staff_id) ON DELETE RESTRICT;
INSERT INTO permission(code,name) VALUES ('billing.refund_process','登记线下退款处理结果') ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='billing.refund_process'
WHERE r.code='finance' ON CONFLICT DO NOTHING;
