-- Source:
--   动态下单表最终版.docx
--   产品内容.doc
--   docs/requirements/product-catalog-v2-source-baseline-20260731.json
--
-- Only confirmed product names and workflow mappings are published here.
-- Prices remain PENDING_QUOTE, materials/accessories remain separately maintainable,
-- and the unconfirmed clear-aligner entry stays inactive.

INSERT INTO catalog_config_version (
    version_no, version_name, publication_status, effective_at, published_at
)
VALUES (
    20260731, '客户产品目录首版', 'DRAFT', CURRENT_TIMESTAMP(3), NULL
)
ON DUPLICATE KEY UPDATE
    config_version_id = LAST_INSERT_ID(config_version_id),
    version_name = VALUES(version_name);

SET @catalog_version_id = LAST_INSERT_ID();

INSERT INTO catalog_category_v2 (
    config_version_id, category_code, display_name, sort_order, status
)
VALUES
    (@catalog_version_id, 'FIXED_RESTORATION', '固定义齿', 10, 'ACTIVE'),
    (@catalog_version_id, 'REMOVABLE_PROSTHETICS', '活动义齿', 20, 'ACTIVE'),
    (@catalog_version_id, 'IMPLANT_RESTORATION', '种植修复', 30, 'ACTIVE'),
    (@catalog_version_id, 'CONVENTIONAL_ORTHODONTICS', '正畸产品', 40, 'ACTIVE'),
    (@catalog_version_id, 'CLEAR_ALIGNER', '隐形正畸', 50, 'ACTIVE'),
    (@catalog_version_id, 'DESIGN_SERVICE', '设计服务', 60, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = VALUES(status);

INSERT INTO catalog_product_v2 (
    config_version_id, category_id, product_code, display_name,
    workflow_product_type, tooth_rule_code,
    pricing_status, base_price_cents, currency, sort_order, status
)
VALUES
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_PRINTED_ZIRCONIA_CROWN', '打印氧化锆冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 10, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_ALL_CERAMIC_CROWN', '全瓷冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 20, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_LAYERED_CERAMIC_CROWN', '全瓷上瓷冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 30, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_PFM_CROWN', '烤瓷冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 40, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_INLAY', '嵌体', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 50, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_VENEER', '贴面', 'VENEER_RESTORATION', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 60, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_TEMPORARY_CROWN', '临时冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 70, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_POST_CORE', '桩核', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 80, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_FULL_METAL_CROWN', '全金属冠', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 90, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_MARYLAND_BRIDGE', '马里兰桥', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 100, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_DIAGNOSTIC_WAXUP', '美学蜡型', 'REGULAR_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 110, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'FIXED_RESTORATION'), 'FIXED_TELESCOPIC_CROWN', '双重冠', 'TELESCOPIC_CROWN', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 120, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_FRAMEWORK_DENTURE', '支架义齿', 'REMOVABLE_STEEL', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 130, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_ACRYLIC_DENTURE', '树脂活动义齿', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 140, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_FLEXIBLE_DENTURE', '弹性义齿', 'REMOVABLE_INVISIBLE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 150, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_SNAP_ON_SMILE', '微笑牙套', 'REMOVABLE_INVISIBLE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 160, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_TEMPORARY_DENTURE', '临时活动义齿', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 170, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_REPAIR', '活动义齿修理', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 180, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_CUSTOM_TRAY_WAX_RIM', '个性化托盘/蜡堤', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 190, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_ACRYLIC_COMPLETION', '充胶完成', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 200, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_COMPLETE_DENTURE', '全口义齿', 'REMOVABLE_ACRYLIC', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 210, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'REMOVABLE_PROSTHETICS'), 'REMOVABLE_BLEACHING_TRAY', '漂白牙套', 'REMOVABLE_INVISIBLE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 220, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_ZIRCONIA_CROWN', '种植氧化锆冠', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 230, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_CUSTOM_ABUTMENT', '种植个性化基台', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 240, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_MONOLITHIC_CROWN', '种植一体冠', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 250, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_SURGICAL_GUIDE', '种植导板', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 260, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_FRAMEWORK', '种植桥架', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 270, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_FULL_ARCH', '全口种植产品', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 280, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'IMPLANT_RESTORATION'), 'IMPLANT_SUPRASTRUCTURE_DENTURE', '种植上部义齿', 'IMPLANT_RESTORATION', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 290, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_NIGHT_GUARD_HARD', '夜磨牙颌垫（硬）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 300, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_NIGHT_GUARD_SOFT', '夜磨牙颌垫（软）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 310, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_NIGHT_GUARD_DUAL', '夜磨牙颌垫（内软外硬）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 320, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_PRINTED_NIGHT_GUARD', '打印夜磨牙颌垫', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 330, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SPORT_MOUTH_GUARD', '运动牙套', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 340, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_NANCE_PALATAL_ARCH', '兰丝腭弓+腭杆', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 350, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_HAWLEY_RETAINER', '哈利式保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 360, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_TRANSPALATAL_ARCH', '横腭杆 TPA', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 370, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_BEGER_RETAINER', '比格保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 380, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_WELDED_HAWLEY_RETAINER', '焊接哈利式保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 390, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FIXED_RETAINER', '舌侧固定保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 400, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_LINGUAL_ARCH', '舌弓', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 410, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_TONGUE_HABIT_APPLIANCE', '舌棚/舌刺/舌栅', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 420, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_CLEAR_RETAINER', '透明保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 430, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SPACE_MAINTAINER', '间隙保持器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 440, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_ERUPTION_BLOCKER', '阻萌器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 450, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_MANDIBULAR_INCLINED_PLANE', '下颌斜面导板', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 460, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_CUSTOM_APPLIANCE', '个性化矫正器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 470, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SIDE_GUIDE_PLANE', '侧面导板', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 480, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_VESTIBULAR_SHIELD', '前庭盾', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 490, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_REVERSE_PULL_HEADGEAR', '前方牵引器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 500, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_ORAL_MUSCLE_ACTIVATOR', '口外肌激动器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 510, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_LIP_BUMPER', '唇档', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 520, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FLAT_BITE_PLANE', '平面导板', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 530, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SPRING_APPLIANCE', '弹簧矫正器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 540, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FRANKEL_II', '法兰克尔 II', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 550, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FRANKEL_III', '法兰克尔 III', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 560, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_PERIODONTAL_SPLINT', '牙周夹板', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 570, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_ROBERTS_RETRACTOR', '罗拔式牵引器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 580, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_CROZAT_LOWER_EXPANDER', 'Crozat 扩弓器（下颌）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 590, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_THREE_WAY_DIAMOND_EXPANDER', '三方向菱形扩弓器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 600, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_THREE_DIMENSIONAL_EXPANDER', '三维扩弓器（螺旋式）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 610, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_OMNIDIRECTIONAL_SCREW_EXPANDER', '全方位螺旋调节器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 620, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_TWIN_BLOCK', '双导面功能矫治器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 630, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_QUAD_HELIX_WELDED', '四眼圈簧扩弓器（焊接式）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 640, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_QUAD_HELIX_LINGUAL', '四眼簧扩弓器（舌侧焊接）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 650, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_RAPID_EXPANDER_LOWER', '快速扩弓器（下颌）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 660, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FAN_EXPANDER_INTEGRATED', '扇形扩弓器（一体式）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 670, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_FAN_EXPANDER_SEPARATE', '扇形扩弓器（分离式）', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 680, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_MEMORY_EXPANDER', '横向记忆扩弓器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 690, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_BIONATOR', '比纳特', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 700, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SCREW_EXPANDER', '螺旋扩弓器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 710, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_UNIVERSAL_EXPANDER', '通用型扩弓器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 720, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_PENDULUM_APPLIANCE', '钟摆矫治器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 730, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_CAST_RAPID_EXPANDER', '铸造快速扩弓器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 740, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_SHARK_SNORE_GUARD', '鲨鱼阻鼾器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 750, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_PLASTIC_ROD_SNORE_APPLIANCE', '塑料拉杆式阻鼾器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 760, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CONVENTIONAL_ORTHODONTICS'), 'ORTHO_METAL_ROD_SNORE_APPLIANCE', '金属拉杆式阻鼾器', 'ORTHODONTICS', 'TOOTH_ORTHO', 'PENDING_QUOTE', NULL, 'CNY', 770, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_FULL_CROWN', '全冠设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 780, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_VENEER', '贴面设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 790, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_INLAY', '嵌体设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 800, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_MARYLAND_BRIDGE', '马里兰桥设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 810, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_CUSTOM_ABUTMENT', '个性化基台设计', 'DESIGN_SERVICE', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 820, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_INTEGRATED_CROWN', '一体冠设计', 'DESIGN_SERVICE', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 830, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_FULL_ARCH_IMPLANT_FRAMEWORK', '全口种植桥架设计', 'DESIGN_SERVICE', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 840, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_IMPLANT_GUIDE', '种植导板设计', 'DESIGN_SERVICE', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 850, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_IMPLANT_SUPRASTRUCTURE', '种植上部义齿设计', 'DESIGN_SERVICE', 'TOOTH_IMPLANT', 'PENDING_QUOTE', NULL, 'CNY', 860, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_COMPLETE_DENTURE', '全口义齿设计', 'DESIGN_SERVICE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 870, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_FRAMEWORK', '支架设计', 'DESIGN_SERVICE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 880, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_ACRYLIC_DENTURE', '树脂义齿设计', 'DESIGN_SERVICE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 890, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_FLEXIBLE_DENTURE', '弹性义齿设计', 'DESIGN_SERVICE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 900, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_DIAGNOSTIC_WAXUP', '美学蜡型设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 910, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_INNER_CROWN', '内冠设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 920, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_TELESCOPIC_CROWN', '双重冠设计', 'DESIGN_SERVICE', 'TOOTH_FIXED', 'PENDING_QUOTE', NULL, 'CNY', 930, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'DESIGN_SERVICE'), 'DESIGN_CUSTOM_TRAY', '个性化托盘设计', 'DESIGN_SERVICE', 'TOOTH_REMOVABLE', 'PENDING_QUOTE', NULL, 'CNY', 940, 'ACTIVE'),
    (@catalog_version_id, (SELECT category_id FROM catalog_category_v2 WHERE config_version_id = @catalog_version_id AND category_code = 'CLEAR_ALIGNER'), 'CLEAR_ALIGNER_TYPE_A', '隐形正畸 A 型', 'ORTHODONTICS', 'TOOTH_CLEAR_ALIGNER', 'PENDING_QUOTE', NULL, 'CNY', 950, 'INACTIVE')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    workflow_product_type = VALUES(workflow_product_type),
    tooth_rule_code = VALUES(tooth_rule_code),
    pricing_status = 'PENDING_QUOTE',
    base_price_cents = NULL,
    currency = 'CNY',
    sort_order = VALUES(sort_order),
    status = VALUES(status);

UPDATE catalog_config_version
SET publication_status = 'INACTIVE'
WHERE publication_status = 'ACTIVE'
  AND config_version_id <> @catalog_version_id;

UPDATE catalog_config_version
SET publication_status = 'ACTIVE',
    effective_at = CURRENT_TIMESTAMP(3),
    published_at = CURRENT_TIMESTAMP(3),
    lock_version = lock_version + 1
WHERE config_version_id = @catalog_version_id;
