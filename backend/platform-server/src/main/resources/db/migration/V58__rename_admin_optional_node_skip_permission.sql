UPDATE system_permission
SET permission_name = '管理员跳过可选工序',
    module_code = 'workflow',
    status = 'ACTIVE'
WHERE permission_code = 'workflow:skip-optional';
