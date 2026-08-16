-- GOAL-034 G3：AI-6 牙科 FAQ 知识库。
-- 结构由项目方建好，正式语料属客户输入（CP-013）。种子条目一律标记 SAMPLE_PENDING_CUSTOMER_CONFIRMATION，
-- 界面必须显示「示例内容，待甲方确认」，不得当作客户正式口径对外承诺。

CREATE TABLE ai_faq_entry (
    faq_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    category VARCHAR(64) NOT NULL,
    question VARCHAR(255) NOT NULL,
    answer TEXT NOT NULL,
    keywords VARCHAR(512) NULL,
    audience VARCHAR(32) NOT NULL DEFAULT 'DOCTOR',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    source_note VARCHAR(64) NOT NULL DEFAULT 'SAMPLE_PENDING_CUSTOMER_CONFIRMATION',
    created_by_user_id BIGINT NULL,
    updated_by_user_id BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_ai_faq_entry_lookup (audience, status, category, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ai_faq_entry (category, question, answer, keywords, audience, sort_order) VALUES
    ('下单流程', '下单需要提供哪些资料？',
     '常规修复类订单需要提供：患者基本信息、牙位、产品与材料选择、口扫或印模文件、比色照片。系统会在提交前自动做一次资料完整性检查，缺项会直接列出来。',
     '资料,需要什么,提交,准备', 'DOCTOR', 10),
    ('下单流程', '口扫文件支持哪些格式？',
     '支持 STL 及常见口扫导出的压缩包。单个文件上限 500MB，单个订单最多 50 个文件。上传中断后可以续传，不需要重新上传整个文件。',
     '口扫,STL,文件,格式,上传', 'DOCTOR', 20),
    ('下单流程', '下单后还能修改资料吗？',
     '订单在客服初审通过之前可以补充或修改资料。进入生产之后需要通过沟通中心联系客服处理，不能直接改单。',
     '修改,改单,补资料,撤回', 'DOCTOR', 30),
    ('产品与材料', '氧化锆和二硅酸锂怎么选？',
     '后牙、咬合力大或需要较高强度的情况通常选氧化锆；前牙对透明度和美观要求高时通常选二硅酸锂。具体选择请结合基牙颜色、预备空间和患者需求，必要时在订单备注中说明，技师会再确认。',
     '氧化锆,二硅酸锂,材料,选择,区别', 'DOCTOR', 40),
    ('产品与材料', '比色信息怎么提供最准确？',
     '建议在自然光下拍摄带比色板的照片，比色板紧贴目标牙齿且在同一焦平面上。同时在订单中填写目标色号，照片作为辅助判断。',
     '比色,颜色,色号,拍照', 'DOCTOR', 50),
    ('交期与物流', '订单大概多久能做好？',
     '交期取决于产品类型、是否需要试戴、是否有过程确认环节。系统会在下单时给出预计到货时间；每增加一项过程确认通常会延长工期。当前系统中的周期为占位默认值，正式周期以甲方确认为准。',
     '交期,多久,工期,到货,时间', 'DOCTOR', 60),
    ('交期与物流', '怎么查看订单进度和物流？',
     '在医生端「我的订单」中可以查看订单的公开状态与物流信息。也可以直接问订单助手，例如「我这单到哪一步了」。内部工序、技师和质检细节不对医生端开放。',
     '进度,物流,快递,运单,查询', 'DOCTOR', 70),
    ('返工与售后', '做出来不合适需要返工怎么办？',
     '请在沟通中心提交问题描述并附上口内照片，客服会登记外返并安排处理。需要回寄的情况请提供回寄运单号，便于工厂对应到具体订单。',
     '返工,不合适,重做,售后,退回', 'DOCTOR', 80),
    ('账单与结算', '账单在哪里查看？',
     '医生端「账单管理」可以查看每个订单的账单状态、金额和付款状态。一期付款状态由客服人工维护，如有疑问请通过沟通中心联系客服。',
     '账单,发票,付款,结算,费用', 'DOCTOR', 90),
    ('账号与权限', '诊所里其他医生能看到我的订单吗？',
     '数据可见范围按账号所属诊所和角色配置。同一诊所内的管理员账号可以看到诊所范围内的订单，普通医生账号默认只看到本人的订单。具体范围由管理端统一配置。',
     '权限,可见,其他医生,诊所,范围', 'DOCTOR', 100);
