-- T024-A: branch creation permission + license no-expiry confirmation.
-- Existing tables are unchanged; no new table.
ALTER TABLE branch
    ADD COLUMN license_no_expiry_confirmed boolean NOT NULL DEFAULT false;

INSERT INTO permission(code,name) VALUES
 ('branches.create','新建门店')
ON CONFLICT(code) DO NOTHING;

-- Only the owner role gets creation authority. Existing branch.configure remains for existing stores.
INSERT INTO role_permission(role_id,permission_id)
 SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON p.code='branches.create'
 WHERE r.code='owner'
ON CONFLICT DO NOTHING;
