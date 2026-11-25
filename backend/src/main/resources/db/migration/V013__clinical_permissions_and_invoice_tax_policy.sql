-- T033: split clinical permissions and make invoice tax handling buyer/treatment aware.
INSERT INTO permission(code,name) VALUES
 ('clinical.record','完成就诊与临床记录'),
 ('clinical.plan','治疗计划创建与执行')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code IN ('clinical.record','clinical.plan')
WHERE r.code='dentist' ON CONFLICT DO NOTHING;

ALTER TABLE invoice ADD COLUMN buyer_type varchar(10) NOT NULL DEFAULT '个人';
ALTER TABLE invoice ADD COLUMN tax_treatment varchar(10) NOT NULL DEFAULT '不征税';
ALTER TABLE invoice ADD COLUMN red_basis varchar(300);
ALTER TABLE invoice DROP CONSTRAINT ck_invoice_formal_tax_no;
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_buyer_tax CHECK (
    invoice_type = '人工收据'
    OR (buyer_type = '个人')
    OR (buyer_type = '单位' AND buyer_tax_no IS NOT NULL AND btrim(buyer_tax_no) <> '')
);
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_buyer_type CHECK (buyer_type IN ('个人','单位'));
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_tax_treatment CHECK (tax_treatment IN ('应税','免税','零税率','不征税'));

CREATE UNIQUE INDEX uq_invoice_red_active ON invoice(red_of_id)
WHERE red_of_id IS NOT NULL AND status IN ('待开票','已开票');
