-- T031: tax/invoice extensions and finance billing permission.
-- Tax amounts are explicit snapshots supplied/confirmed by finance; this migration does not guess a tax rate.
ALTER TABLE invoice
    ADD COLUMN invoice_type varchar(20) NOT NULL DEFAULT '人工收据',
    ADD COLUMN buyer_tax_no varchar(30),
    ADD COLUMN tax_rate_bps integer NOT NULL DEFAULT 0,
    ADD COLUMN net_amount_cents bigint NOT NULL DEFAULT 0,
    ADD COLUMN tax_amount_cents bigint NOT NULL DEFAULT 0,
    ADD COLUMN invoice_no varchar(40),
    ADD COLUMN red_of_id bigint,
    ADD COLUMN voided_at timestamptz,
    ADD COLUMN voided_by bigint,
    ADD COLUMN void_reason varchar(300);

UPDATE invoice SET net_amount_cents=amount_cents, tax_amount_cents=0 WHERE net_amount_cents=0 AND tax_amount_cents=0;

ALTER TABLE invoice
    ADD CONSTRAINT ck_invoice_type CHECK (invoice_type IN ('人工收据','增值税普通发票','增值税专用发票')),
    ADD CONSTRAINT ck_invoice_tax_rate CHECK (tax_rate_bps >= 0 AND tax_rate_bps <= 10000),
    ADD CONSTRAINT ck_invoice_split CHECK (net_amount_cents >= 0 AND tax_amount_cents >= 0 AND net_amount_cents + tax_amount_cents = amount_cents),
    ADD CONSTRAINT ck_invoice_formal_tax_no CHECK (invoice_type = '人工收据' OR (buyer_tax_no IS NOT NULL AND btrim(buyer_tax_no) <> '')),
    ADD CONSTRAINT ck_invoice_void CHECK ((voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL) OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND void_reason IS NOT NULL)),
    ADD CONSTRAINT fk_invoice_red FOREIGN KEY (red_of_id) REFERENCES invoice(invoice_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_invoice_voided_by FOREIGN KEY (voided_by) REFERENCES staff(staff_id) ON DELETE RESTRICT;

ALTER TABLE invoice DROP CONSTRAINT ck_invoice_status;
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_status CHECK (status IN ('待开票','已开票','已拒绝','已作废'));

INSERT INTO permission(code,name) VALUES ('billing.manage','收费与票据管理') ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='billing.manage'
WHERE r.code IN ('frontdesk','finance') ON CONFLICT DO NOTHING;
