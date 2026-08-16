-- D-178: expose the existing versioned catalog maintenance capability as an
-- administrator-facing ordering-content setting. This only updates menu
-- metadata; catalog versions, published snapshots and historical orders remain
-- untouched.

UPDATE system_menu
SET menu_name = '下单内容设置',
    route_path = '/admin/catalog',
    component_path = 'CatalogConfigurationCenterView',
    permission_code = 'catalog:manage',
    icon = 'product',
    status = 'ACTIVE'
WHERE menu_code = 'catalog-configuration-center';
