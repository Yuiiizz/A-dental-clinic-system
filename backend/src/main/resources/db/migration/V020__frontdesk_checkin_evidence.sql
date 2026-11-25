-- Keep the actual in-person check and any manual time-window exception with the ticket.
ALTER TABLE queue_ticket
    ADD COLUMN verification_basis varchar(300),
    ADD COLUMN exception_reason varchar(300);

ALTER TABLE queue_ticket
    ADD CONSTRAINT ck_queue_ticket_verification_basis_nonblank
        CHECK (verification_basis IS NULL OR length(btrim(verification_basis)) > 0),
    ADD CONSTRAINT ck_queue_ticket_exception_reason_nonblank
        CHECK (exception_reason IS NULL OR length(btrim(exception_reason)) > 0);

COMMENT ON COLUMN queue_ticket.verification_basis IS '前台现场核验依据；旧记录及自助签到可空，不得记录证件号码';
COMMENT ON COLUMN queue_ticket.exception_reason IS '前台超窗或未配置窗口时的人工签到理由';
