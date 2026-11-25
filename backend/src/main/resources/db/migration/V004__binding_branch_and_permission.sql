-- 旧档绑定与前台签发/重签：为一次性身份凭证补充门店与撤销时间，加每患者至多一条活跃绑定凭证，
-- 并把「签发患者绑定凭证」权限只默认授予 frontdesk。仅新增，不改 V001-V003。

ALTER TABLE identity_challenge ADD COLUMN branch_id bigint;
COMMENT ON COLUMN identity_challenge.branch_id IS '门店，绑定用途';
ALTER TABLE identity_challenge ADD COLUMN revoked_at timestamptz;
COMMENT ON COLUMN identity_challenge.revoked_at IS '撤销时间，重签或历史行迁移时置位';

-- 历史绑定行缺 branch_id：不任填门店。保留记录但统一撤销（不可再消费），再用新约束过渡。
UPDATE identity_challenge SET revoked_at = now()
 WHERE purpose = 'patient_binding' AND branch_id IS NULL AND revoked_at IS NULL;

ALTER TABLE identity_challenge ADD CONSTRAINT fk_identity_challenge_branch
  FOREIGN KEY (branch_id) REFERENCES branch (branch_id) ON DELETE RESTRICT;

-- 过渡后的字段约束：注册上下文无门店；绑定上下文须有门店，除非已被撤销（历史行）。
ALTER TABLE identity_challenge DROP CONSTRAINT ck_identity_challenge_purpose_fields;
ALTER TABLE identity_challenge ADD CONSTRAINT ck_identity_challenge_purpose_fields CHECK (
  (purpose = 'wechat_registration' AND app_id IS NOT NULL AND open_id IS NOT NULL AND patient_id IS NULL AND branch_id IS NULL) OR
  (purpose = 'patient_binding' AND patient_id IS NOT NULL AND issued_by IS NOT NULL AND (branch_id IS NOT NULL OR revoked_at IS NOT NULL))
);

-- 每患者至多一条未消费且未撤销的绑定凭证。该部分唯一索引也包含已过期记录（不能在索引里写 now()）；
-- 签发代码须在患者锁下先撤销已过期/次数耗尽的待用记录再插入，活跃记录只允许显式重签。
CREATE UNIQUE INDEX uq_identity_challenge_active_binding
  ON identity_challenge (patient_id)
  WHERE purpose = 'patient_binding' AND consumed_at IS NULL AND revoked_at IS NULL;

INSERT INTO permission (code, name) VALUES ('patients.bind_issue', '签发患者绑定凭证') ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission (role_id, permission_id)
  SELECT r.role_id, p.permission_id FROM role r JOIN permission p ON
  (r.code = 'frontdesk' AND p.code = 'patients.bind_issue')
  ON CONFLICT DO NOTHING;
