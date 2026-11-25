ALTER TABLE branch ADD COLUMN public_intro varchar(500);
ALTER TABLE staff ADD COLUMN public_intro varchar(500);

COMMENT ON COLUMN branch.public_intro IS '经经营主体确认可公开的门店介绍；为空时不展示';
COMMENT ON COLUMN staff.public_intro IS '经核对可公开的医生介绍；为空时不展示';
