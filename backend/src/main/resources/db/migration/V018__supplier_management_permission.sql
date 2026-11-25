-- Supplier records are shared by the installation's single organization.
-- This permission never grants appointment, clinical or billing access.
INSERT INTO permission(code,name) VALUES ('suppliers.manage','维护已核验供应商档案')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id)
SELECT r.role_id,p.permission_id FROM role r CROSS JOIN permission p
WHERE r.code IN ('inventory','owner') AND p.code='suppliers.manage'
ON CONFLICT DO NOTHING;
