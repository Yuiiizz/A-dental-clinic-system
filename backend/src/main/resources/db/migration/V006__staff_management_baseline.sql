-- T025-A: staff global management baseline.
-- Add optimistic version for global staff edits; credential verifier may be empty for a real pending review.
ALTER TABLE staff ADD COLUMN version integer NOT NULL DEFAULT 1;
ALTER TABLE staff ADD CONSTRAINT ck_staff_version CHECK (version > 0);
COMMENT ON COLUMN staff.version IS '员工主档并发版本';

ALTER TABLE credential ALTER COLUMN verified_by DROP NOT NULL;
ALTER TABLE credential ALTER COLUMN verified_at DROP NOT NULL;
ALTER TABLE credential ADD CONSTRAINT ck_credential_verifier_pair CHECK (
    (verified_by IS NULL AND verified_at IS NULL)
    OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)
);
ALTER TABLE credential ADD CONSTRAINT ck_credential_verified_requires_verifier CHECK (
    verification_state <> 'verified'
    OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)
);

INSERT INTO permission(code,name) VALUES
 ('staff.manage_global','维护全组织员工主档')
ON CONFLICT(code) DO NOTHING;

INSERT INTO role_permission(role_id,permission_id)
 SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='staff.manage_global'
 WHERE r.code='owner'
ON CONFLICT DO NOTHING;
