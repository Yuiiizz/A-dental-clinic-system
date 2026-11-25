-- Fixed permission catalog. These entries do not enable HTTP business routes.
INSERT INTO permission(code,name) VALUES
 ('accounts.manage','安装实例员工账号维护'),
 ('branch.configure','门店基础配置'),
 ('appointments.assist','本店预约代办'),
 ('appointments.own.read','本人待接诊预约只读')
ON CONFLICT(code) DO NOTHING;
INSERT INTO role_permission(role_id,permission_id)
 SELECT r.role_id,p.permission_id FROM role r JOIN permission p ON
 (r.code='sysadmin' AND p.code='accounts.manage') OR
 (r.code='owner' AND p.code='branch.configure') OR
 (r.code='frontdesk' AND p.code='appointments.assist') OR
 (r.code='dentist' AND p.code='appointments.own.read')
ON CONFLICT DO NOTHING;
