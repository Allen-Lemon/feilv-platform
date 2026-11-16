-- 退役三个占位菜单，不删除历史角色关联或任何业务数据；重复执行保持禁用。
UPDATE t_admin_menu SET enabled = 0
WHERE code IN ('marketing','content','risk') OR path IN ('/marketing','/content','/risk');
