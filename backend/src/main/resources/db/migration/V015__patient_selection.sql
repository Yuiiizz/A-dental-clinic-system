-- 前台新患者选择凭证；只新增本功能必要结构。
CREATE TABLE patient_selection (
    selection_id uuid NOT NULL,
    token_hash char(64) NOT NULL,
    account_id bigint NOT NULL,
    staff_id bigint NOT NULL,
    branch_id bigint NOT NULL,
    patient_id bigint NOT NULL,
    source varchar(16) NOT NULL,
    verification_method varchar(30) NOT NULL,
    verification_result varchar(20) NOT NULL,
    resolution varchar(30) NOT NULL,
    in_person boolean NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    appointment_id bigint,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_patient_selection PRIMARY KEY (selection_id),
    CONSTRAINT uq_patient_selection_token UNIQUE (token_hash),
    CONSTRAINT fk_patient_selection_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT fk_patient_selection_staff FOREIGN KEY (staff_id) REFERENCES staff(staff_id),
    CONSTRAINT fk_patient_selection_branch FOREIGN KEY (branch_id) REFERENCES branch(branch_id),
    CONSTRAINT fk_patient_selection_patient FOREIGN KEY (patient_id) REFERENCES patient(patient_id),
    CONSTRAINT fk_patient_selection_appointment FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id),
    CONSTRAINT ck_patient_selection_source CHECK (source IN ('lookup','create')),
    CONSTRAINT ck_patient_selection_method CHECK (verification_method IN ('visit_evidence','id_document')),
    CONSTRAINT ck_patient_selection_result CHECK (verification_result = 'confirmed'),
    CONSTRAINT ck_patient_selection_resolution CHECK (resolution IN ('new_patient','single_exact','manual_candidate','shared_phone','same_name')),
    CONSTRAINT ck_patient_selection_in_person CHECK (in_person),
    CONSTRAINT ck_patient_selection_consumption CHECK ((consumed_at IS NULL) = (appointment_id IS NULL))
);
CREATE INDEX ix_patient_selection_actor_expiry ON patient_selection(account_id, expires_at);
CREATE INDEX ix_patient_selection_patient ON patient_selection(patient_id, created_at);
COMMENT ON TABLE patient_selection IS '前台患者选择短时凭证；只存摘要，不存姓名电话证件号';
COMMENT ON COLUMN patient_selection.verification_result IS '仅 confirmed 可签发';

INSERT INTO permission (code, name) VALUES
 ('patients.lookup', '本店患者精确检索与选择'),
 ('patients.create', '本店患者档案建立')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM role r JOIN permission p ON r.code='frontdesk' AND p.code IN ('patients.lookup','patients.create')
ON CONFLICT DO NOTHING;
