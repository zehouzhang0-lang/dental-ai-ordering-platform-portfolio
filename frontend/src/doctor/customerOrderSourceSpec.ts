/**
 * 医生动态下单的客户原始口径。
 *
 * 来源仅限：
 * - 《动态下单表最终版.docx》
 * - 《产品内容.doc》
 * - 《家红-正畸平台-医生端操作文档.docx》（仅隐形正畸专项选项）
 *
 * 价格和标准工时未在上述文件中确认，因此不在这里补演示值。
 */

export type SourceUploadRule = {
  code: string
  label: string
  required: boolean
  accept: string
}

export const CUSTOMER_ORDER_STEPS = [
  '基础信息与产品',
  '牙位与制作要求',
  '材料与工艺',
  '资料上传',
  '试戴与过程确认',
  '报价与周期确认'
] as const

export const CATEGORY_NAMES: Record<string, string> = {
  FIXED_RESTORATION: '固定义齿',
  REMOVABLE_PROSTHETICS: '活动义齿',
  IMPLANT_RESTORATION: '种植修复',
  CONVENTIONAL_ORTHODONTICS: '正畸产品',
  CLEAR_ALIGNER: '隐形正畸',
  DESIGN_SERVICE: '设计服务'
}

export const CLEAR_ALIGNER_PRODUCT_CODE = 'CLEAR_ALIGNER_BRACELESS'

export const CLEAR_ALIGNER_ARCH_OPTIONS = [
  { value: 'FULL', label: '全颌' },
  { value: 'UPPER', label: '上颌' },
  { value: 'LOWER', label: '下颌' }
] as const

export const CLEAR_ALIGNER_TREATMENT_OPTIONS = [
  { value: 'REGULAR', label: '常规矫治' },
  { value: 'COMBINED', label: '联合矫治' }
] as const

export const ORTHODONTIC_PRODUCT_GROUPS = [
  {
    label: '颌垫类产品',
    codes: [
      'ORTHO_NIGHT_GUARD_HARD',
      'ORTHO_NIGHT_GUARD_SOFT',
      'ORTHO_NIGHT_GUARD_DUAL',
      'ORTHO_PRINTED_NIGHT_GUARD',
      'ORTHO_SPORT_MOUTH_GUARD'
    ]
  },
  {
    label: '保持器类产品',
    codes: [
      'ORTHO_NANCE_PALATAL_ARCH',
      'ORTHO_HAWLEY_RETAINER',
      'ORTHO_TRANSPALATAL_ARCH',
      'ORTHO_BEGER_RETAINER',
      'ORTHO_WELDED_HAWLEY_RETAINER',
      'ORTHO_FIXED_RETAINER',
      'ORTHO_LINGUAL_ARCH',
      'ORTHO_TONGUE_HABIT_APPLIANCE',
      'ORTHO_CLEAR_RETAINER',
      'ORTHO_SPACE_MAINTAINER',
      'ORTHO_ERUPTION_BLOCKER'
    ]
  },
  {
    label: '功能性矫正产品',
    codes: [
      'ORTHO_MANDIBULAR_INCLINED_PLANE',
      'ORTHO_CUSTOM_APPLIANCE',
      'ORTHO_SIDE_GUIDE_PLANE',
      'ORTHO_VESTIBULAR_SHIELD',
      'ORTHO_REVERSE_PULL_HEADGEAR',
      'ORTHO_ORAL_MUSCLE_ACTIVATOR',
      'ORTHO_LIP_BUMPER',
      'ORTHO_FLAT_BITE_PLANE',
      'ORTHO_SPRING_APPLIANCE',
      'ORTHO_FRANKEL_II',
      'ORTHO_FRANKEL_III',
      'ORTHO_PERIODONTAL_SPLINT',
      'ORTHO_ROBERTS_RETRACTOR'
    ]
  },
  {
    label: '扩弓类产品',
    codes: [
      'ORTHO_CROZAT_LOWER_EXPANDER',
      'ORTHO_THREE_WAY_DIAMOND_EXPANDER',
      'ORTHO_THREE_DIMENSIONAL_EXPANDER',
      'ORTHO_OMNIDIRECTIONAL_SCREW_EXPANDER',
      'ORTHO_TWIN_BLOCK',
      'ORTHO_QUAD_HELIX_WELDED',
      'ORTHO_QUAD_HELIX_LINGUAL',
      'ORTHO_RAPID_EXPANDER_LOWER',
      'ORTHO_FAN_EXPANDER_INTEGRATED',
      'ORTHO_FAN_EXPANDER_SEPARATE',
      'ORTHO_MEMORY_EXPANDER',
      'ORTHO_BIONATOR',
      'ORTHO_SCREW_EXPANDER',
      'ORTHO_UNIVERSAL_EXPANDER',
      'ORTHO_PENDULUM_APPLIANCE',
      'ORTHO_CAST_RAPID_EXPANDER'
    ]
  },
  {
    label: '阻鼾类产品',
    codes: [
      'ORTHO_SHARK_SNORE_GUARD',
      'ORTHO_PLASTIC_ROD_SNORE_APPLIANCE',
      'ORTHO_METAL_ROD_SNORE_APPLIANCE'
    ]
  }
] as const

export const PRODUCT_MATERIAL_OPTIONS: Record<string, string[]> = {
  FIXED_PRINTED_ZIRCONIA_CROWN: ['打印氧化锆冠'],
  FIXED_ALL_CERAMIC_CROWN: [
    'E-max 铸瓷',
    '3M LAVA',
    '爱尔创氧化锆',
    '爱迪特绚彩 3D 氧化锆',
    '威兰德氧化锆',
    '德国弹性瓷',
    '泽康氧化锆全瓷（二代）',
    '特美云锆',
    '特美德锆',
    '特美玉锆',
    '特美锆',
    '睿典瓷'
  ],
  FIXED_LAYERED_CERAMIC_CROWN: ['威兰德氧化锆', '德国弹性瓷'],
  FIXED_PFM_CROWN: [
    '钴铬合金烤瓷',
    '纯钛烤瓷',
    '贵金属烤瓷',
    'Bego 钴铬合金',
    '德尔合金',
    'Bego 钴铬合金聚合瓷冠',
    '纯钛聚合瓷冠',
    '贵金属聚合瓷冠'
  ],
  FIXED_INLAY: ['E-max 铸瓷', '爱尔创氧化锆', '爱迪特绚彩 3D 氧化锆', '微晶瓷', '聚合瓷', '贵金属', '钴铬合金', '纯钛', '德尔生物'],
  FIXED_VENEER: ['E-max 铸瓷', '家红水晶超薄贴面', '微晶瓷'],
  FIXED_TEMPORARY_CROWN: ['切削临时树脂冠'],
  FIXED_POST_CORE: ['E-max 铸瓷', '爱尔创氧化锆', '爱迪特氧化锆', '纯钛', '钴铬合金', 'Bego 钴铬合金'],
  FIXED_FULL_METAL_CROWN: ['贵金属冠'],
  FIXED_MARYLAND_BRIDGE: ['HPP 马里兰桥', '临时树脂马里兰桥', '钴铬金属马里兰桥', '氧化锆马里兰桥（仅限前牙）', '马里兰桥翼'],
  FIXED_DIAGNOSTIC_WAXUP: ['美学蜡型牙'],
  FIXED_TELESCOPIC_CROWN: ['Bego 钴铬合金套筒冠内冠', 'Bego 钴铬合金套筒冠外冠', '贵金属套筒冠内冠', '贵金属套筒冠外冠', '钴铬合金套筒冠内冠', '钴铬合金套筒冠外冠'],

  IMPLANT_ZIRCONIA_CROWN: ['Cercon 泽康全瓷牙', 'LAVA 氧化锆全瓷牙', '威兰德氧化锆全瓷牙', '特美云锆全瓷牙', '普通氧化锆全瓷牙', '水晶氧化锆全瓷牙', '特美德锆全瓷牙', '特美玉锆全瓷牙', '特美锆全瓷牙'],
  IMPLANT_CUSTOM_ABUTMENT: ['LAVA 氧化锆基台（含钛连接体）', '威兰德氧化锆基台（含钛连接体）', '氧化锆基台（含钛连接体）', '水晶氧化锆基台（含钛连接体）'],
  IMPLANT_MONOLITHIC_CROWN: ['纯钛聚合瓷冠', '纯钛金属冠', '钴铬合金聚合瓷冠', '钴铬金属冠', 'LAVA 氧化锆基台一体冠含上瓷', '威兰德氧化锆基台一体冠含上瓷', '氧化锆基台一体冠含上瓷', '水晶氧化锆基台一体冠含上瓷'],
  IMPLANT_SURGICAL_GUIDE: ['全口种植放射导板（胶托排牙）', '种植导板孔', '种植导板（含 CBCT 分析设计及一孔）', '种植导板（无牙颌）'],
  IMPLANT_FRAMEWORK: ['种植桥连基台切削支架（含基台）', '连基台铸造纯钛金属杆卡', '连基台铸造贵金属杆卡'],
  IMPLANT_FULL_ARCH: ['球帽式覆盖义齿', '支架上部排塑钢牙修复', '纯钛马泷桥架上部国产氧化锆', '纯钛马泷桥架上部威兰德全瓷', '纯钛马泷桥架上部泽康全瓷', '纯钛马泷桥架上部进口水晶锆'],
  IMPLANT_SUPRASTRUCTURE_DENTURE: ['标准基台', '个性化基台', '角度基台', '复合基台', '临时基台', 'Ti Base'],

  REMOVABLE_FRAMEWORK_DENTURE: ['BPD 完美支架（大）', 'BPD 完美支架（小）', 'Bego 钴铬合金钢托（大）', 'Bego 钴铬合金钢托（小）', 'Bio HPP 支架（大）', '德尔打印纯钛支架（大）', '德尔打印纯钛支架（小）', '德尔钴铬合金支架（大）', '德尔钴铬合金支架（小）', '维他灵钢托（大）', '维他灵钢托（小）', '精密附件支架'],
  REMOVABLE_ACRYLIC_DENTURE: ['PPDS', '代充胶', '充不碎胶', '隐形义齿胶（含一颗牙）', 'Lucitone 199 丙烯酸树脂'],
  REMOVABLE_FLEXIBLE_DENTURE: ['Valplast 弹性材料', 'TCS 弹性材料'],
  REMOVABLE_SNAP_ON_SMILE: ['微笑牙套'],
  REMOVABLE_TEMPORARY_DENTURE: ['临时全口活动义齿'],
  REMOVABLE_REPAIR: ['活动托垫底', '活动托修补', '清洁', '加软衬', '加铸造支托/卡环', '加白胶钩/透明卡环/隐形卡环', '加成品钢网', '加成品舌杆', '加成品吸盘'],
  REMOVABLE_CUSTOM_TRAY_WAX_RIM: ['个别托盘', '蜡堤'],
  REMOVABLE_ACRYLIC_COMPLETION: ['充胶完成'],
  REMOVABLE_COMPLETE_DENTURE: ['全口胶托（塑钢牙）', '全口胶托（山八树脂牙）', '全口 Vita 排牙'],
  REMOVABLE_BLEACHING_TRAY: ['漂白牙套']
}

export const ORTHODONTIC_ACCESSORIES = [
  '加公仔图/色块',
  '加卡环',
  '加唇弓',
  '加唇弓胶',
  '加唇挡',
  '加带环',
  '加平导/斜导',
  '加弹簧',
  '加彩色粉胶',
  '加成品邻间钩',
  '加扩弓螺丝',
  '加牵引钩',
  '加胶',
  '加腭弓',
  '加舌刺（舌栅）',
  '加颊面管',
  '加颌垫',
  '焊接点',
  '铸造带环'
]

export const FIXED_PRECISION_ATTACHMENTS = [
  'Bredent 球状精密附着体',
  'Bredent 锁式精密附着体',
  'Bredent 键槽精密附着体',
  'Key-key way 栓道桥扣位附着体',
  'MK1 锁式精密附着体',
  '太极扣精密附着体',
  '按扣式精密附着体',
  '杆卡式精密附着体',
  '根面快套精密附着体',
  '磁性式精密附着体',
  '附着体换固位胶圈（活动托部分）'
]

export const VITA_16_SHADES = ['A1', 'A2', 'A3', 'A3.5', 'A4', 'B1', 'B2', 'B3', 'B4', 'C1', 'C2', 'C3', 'C4', 'D2', 'D3', 'D4']
export const VITA_3D_SHADES = ['1M1', '1M2', '2L1.5', '2L2.5', '2M1', '2M2', '2M3', '2R1.5', '2R2.5', '3L1.5', '3L2.5', '3M1', '3M2', '3M3', '3R1.5', '3R2.5', '4L1.5', '4L2.5', '4M1', '4M2', '4M3', '4R1.5', '4R2.5', '5M1', '5M2', '5M3']

export const DENTURE_BASE_SHADES = ['Meharry', 'Light Meharry', '标准粉', '深粉', '浅粉', '透明']

export const UPLOAD_RULES: Record<string, SourceUploadRule[]> = {
  FIXED_RESTORATION: [
    { code: 'upper_arch', label: '上颌扫描', required: true, accept: '.stl,.ply,.obj' },
    { code: 'lower_arch', label: '下颌扫描', required: true, accept: '.stl,.ply,.obj' },
    { code: 'bite_scan', label: '咬合扫描', required: true, accept: '.stl,.ply,.obj' },
    { code: 'shade_photo', label: '比色照片', required: true, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'intraoral_photo', label: '口内咬合照', required: false, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'old_restoration', label: '旧义齿参考', required: false, accept: '.jpg,.jpeg,.png,.pdf' }
  ],
  IMPLANT_RESTORATION: [
    { code: 'full_arch_scan', label: '上下颌口扫', required: true, accept: '.stl,.ply,.obj' },
    { code: 'scan_body', label: '种植体扫描杆数据', required: true, accept: '.stl,.ply,.obj' },
    { code: 'bite_scan', label: '咬合记录', required: true, accept: '.stl,.ply,.obj' },
    { code: 'healing_photo', label: '术前/术后照片', required: true, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'cbct', label: 'CBCT 影像', required: false, accept: '.dcm,.dicom,.zip,.pdf' }
  ],
  REMOVABLE_PROSTHETICS: [
    { code: 'full_arch_scan', label: '上下颌口扫', required: true, accept: '.stl,.ply,.obj' },
    { code: 'jaw_record', label: '颌位记录', required: true, accept: '.stl,.ply,.obj,.pdf' },
    { code: 'old_denture', label: '旧义齿全貌照', required: true, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'profile_photo', label: '面部侧面照', required: false, accept: '.jpg,.jpeg,.png' }
  ],
  CONVENTIONAL_ORTHODONTICS: [
    { code: 'full_arch_scan', label: '全口扫描', required: true, accept: '.stl,.ply,.obj' },
    { code: 'five_photos', label: '标准五张口内照', required: true, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'panoramic', label: '全景片', required: true, accept: '.jpg,.jpeg,.png,.pdf,.dcm,.dicom' },
    { code: 'cephalometric', label: '头颅侧位片', required: true, accept: '.jpg,.jpeg,.png,.pdf,.dcm,.dicom' },
    { code: 'treatment_plan', label: '医生矫治方案', required: false, accept: '.pdf,.doc,.docx,.jpg,.jpeg,.png' }
  ],
  CLEAR_ALIGNER: [
    { code: 'upper_model', label: '上颌数字模型', required: false, accept: '.stl,.ply,.obj' },
    { code: 'lower_model', label: '下颌数字模型', required: false, accept: '.stl,.ply,.obj' },
    { code: 'bite_model', label: '咬合模型', required: false, accept: '.stl,.ply,.obj' },
    { code: 'facial_photos', label: '面像照片', required: false, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'intraoral_photos', label: '口内照片', required: false, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'panoramic', label: '全景片', required: false, accept: '.jpg,.jpeg,.png,.pdf,.dcm,.dicom' },
    { code: 'cephalometric', label: '头颅侧位片', required: false, accept: '.jpg,.jpeg,.png,.pdf,.dcm,.dicom' }
  ],
  DESIGN_SERVICE: [
    { code: 'arch_scan', label: '口扫数据', required: true, accept: '.stl,.ply,.obj' },
    { code: 'shade_reference', label: '比色图', required: true, accept: '.jpg,.jpeg,.png,.pdf' },
    { code: 'cbct', label: 'CBCT（种植导板设计）', required: false, accept: '.dcm,.dicom,.zip,.pdf' }
  ]
}
