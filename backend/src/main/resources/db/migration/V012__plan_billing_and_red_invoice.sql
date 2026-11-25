-- T032: link treatment plans to bills and support red-reversal invoices.
ALTER TABLE bill ADD COLUMN plan_id bigint;
ALTER TABLE bill ADD CONSTRAINT fk_bill_plan FOREIGN KEY (plan_id) REFERENCES treatment_plan(plan_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX uq_bill_plan_active ON bill(plan_id) WHERE plan_id IS NOT NULL;

ALTER TABLE invoice DROP CONSTRAINT ck_invoice_amount;
ALTER TABLE invoice DROP CONSTRAINT ck_invoice_split;
ALTER TABLE invoice DROP CONSTRAINT ck_invoice_status;

ALTER TABLE invoice ADD CONSTRAINT ck_invoice_amount CHECK (
    (red_of_id IS NULL AND amount_cents > 0)
    OR (red_of_id IS NOT NULL AND amount_cents < 0)
);
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_split CHECK (
    (net_amount_cents + tax_amount_cents = amount_cents)
    AND ((red_of_id IS NULL AND net_amount_cents >= 0 AND tax_amount_cents >= 0)
      OR (red_of_id IS NOT NULL AND net_amount_cents <= 0 AND tax_amount_cents <= 0))
);
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_status CHECK (
    status IN ('待开票','已开票','已拒绝','已作废','已红冲')
);

ALTER TABLE invoice ADD CONSTRAINT ck_invoice_red_type CHECK (
    red_of_id IS NULL OR invoice_type IN ('增值税普通发票','增值税专用发票')
);
