import type {
  BillRecord,
  ClinicRole,
  DoctorAccount,
  DoctorFile,
  DoctorFaqAnswer,
  DoctorGateway,
  DoctorProductRecommendation,
  DoctorNotification,
  DoctorPortalDataset,
  LogisticsRecord,
  Message,
  MessageThread,
  OrderDetail,
  OrderDraftInput,
  OrderReview,
  OrderSummary,
  PatientDetail,
  PatientCreateInput,
  PatientUpdateInput,
  PatientSummary,
  ProductOption,
  PublicProgressItem,
  ReviewDecisionInput,
  ReviewType
} from '../types/contracts'

const now = '2026-07-18 15:20'

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function file(fileId: string, name: string, kind: DoctorFile['kind'], uploadedAt: string): DoctorFile {
  return {
    file_id: fileId,
    name,
    kind,
    size_label: kind === 'STL' ? '18.4 MB' : kind === 'IMAGE' ? '2.1 MB' : '680 KB',
    status: 'READY',
    uploaded_at: uploadedAt
  }
}

function review(
  id: string,
  type: ReviewType,
  status: OrderReview['status'],
  versions: OrderReview['versions']
): OrderReview {
  const actionable = status === 'PENDING_REVIEW'
  return {
    review_id: id,
    review_type: type,
    status,
    current_version: versions.at(-1)?.version ?? 0,
    versions,
    allowed_actions: actionable ? ['APPROVE_REVIEW', 'REJECT_REVIEW'] : [],
    state_version: versions.length + 3
  }
}

const products: ProductOption[] = [
  {
    product_id: 'PRD-CROWN',
    product_type: 'FIXED_CROWN',
    product_name: '常规牙冠',
    material: '氧化锆',
    quote: null,
    review_capabilities: ['CAD_DESIGN', 'POST_MILLING_PHOTOS', 'POST_GLAZING_PHOTOS'],
    form_fields: [
      { key: 'shade', label: '色号', type: 'SELECT', required: true, options: ['A1', 'A2', 'A3', 'B1'] },
      { key: 'margin', label: '边缘设计', type: 'SELECT', required: true, options: ['浅凹肩', '深凹肩', '刃状'] },
      { key: 'contact', label: '邻接要求', type: 'TEXT', required: false }
    ]
  },
  {
    product_id: 'PRD-IMPLANT',
    product_type: 'IMPLANT_RESTORATION',
    product_name: '种植冠',
    material: '氧化锆·钛基台',
    quote: { amount_minor: 168000, currency: 'CNY' },
    review_capabilities: ['CAD_DESIGN', 'POST_MILLING_PHOTOS', 'POST_GLAZING_PHOTOS'],
    form_fields: [
      { key: 'implant_system', label: '种植系统', type: 'SELECT', required: true, options: ['Straumann', 'Nobel', 'Osstem'] },
      { key: 'platform', label: '平台规格', type: 'TEXT', required: true },
      { key: 'emergence', label: '穿龈轮廓要求', type: 'TEXTAREA', required: false }
    ]
  },
  {
    product_id: 'PRD-BRIDGE',
    product_type: 'FIXED_BRIDGE',
    product_name: '固定桥',
    material: '全瓷',
    quote: null,
    review_capabilities: ['CAD_DESIGN', 'POST_GLAZING_PHOTOS'],
    form_fields: [
      { key: 'units', label: '单位数', type: 'NUMBER', required: true },
      { key: 'pontic', label: '桥体设计', type: 'SELECT', required: true, options: ['改良盖嵴式', '卫生型', '骑跨式'] }
    ]
  },
  {
    product_id: 'PRD-REMOVABLE',
    product_type: 'REMOVABLE_DENTURE',
    product_name: '局部活动义齿',
    material: '钴铬支架·树脂牙',
    quote: null,
    review_capabilities: ['CAD_DESIGN', 'POST_GLAZING_PHOTOS'],
    form_fields: [
      { key: 'denture_scope', label: '修复范围', type: 'SELECT', required: true, options: ['上颌', '下颌', '上下颌'] },
      { key: 'framework', label: '支架材料', type: 'SELECT', required: true, options: ['钴铬合金', '纯钛', '树脂基托'] },
      { key: 'clasp_note', label: '卡环与基托要求', type: 'TEXTAREA', required: false }
    ]
  },
  {
    product_id: 'PRD-ORTHO',
    product_type: 'ORTHODONTIC',
    product_name: '正畸保持器',
    material: '透明热压膜片',
    quote: null,
    review_capabilities: ['CAD_DESIGN'],
    form_fields: [
      { key: 'arch', label: '牙弓', type: 'SELECT', required: true, options: ['上颌', '下颌', '上下颌'] },
      { key: 'thickness', label: '膜片厚度', type: 'SELECT', required: true, options: ['0.75mm', '1.0mm', '1.5mm'] },
      { key: 'ortho_note', label: '正畸要求', type: 'TEXTAREA', required: false }
    ]
  },
  {
    product_id: 'PRD-ALIGNER',
    product_type: 'CLEAR_ALIGNER',
    product_name: '隐形矫治方案',
    material: '多层复合膜片',
    quote: null,
    review_capabilities: ['CAD_DESIGN'],
    form_fields: [
      { key: 'treatment_goal', label: '矫治目标', type: 'TEXTAREA', required: true },
      { key: 'stage_count', label: '预期阶段数', type: 'NUMBER', required: false },
      { key: 'attachment', label: '附件偏好', type: 'SELECT', required: false, options: ['由设计师建议', '尽量减少', '医生指定'] }
    ]
  },
  {
    product_id: 'PRD-DESIGN',
    product_type: 'DIGITAL_DESIGN',
    product_name: '数字化修复设计',
    material: '数字文件交付',
    quote: null,
    review_capabilities: ['CAD_DESIGN'],
    form_fields: [
      { key: 'design_output', label: '设计输出', type: 'SELECT', required: true, options: ['冠桥设计', '美学蜡型', '种植导板', '数字排牙'] },
      { key: 'software_format', label: '文件格式', type: 'SELECT', required: true, options: ['STL', 'PLY', 'OBJ'] },
      { key: 'design_note', label: '设计说明', type: 'TEXTAREA', required: false }
    ]
  }
]

const orders: OrderSummary[] = [
  {
    order_id: '1001', order_no: 'ORD20260718-1001', doctor_name: '陈医生', patient_id: 'P-1001', patient_code: 'A026', patient_name: '张*', clinic_name: '明悦口腔诊所', product_type: 'IMPLANT_RESTORATION', product_name: '种植冠', tags: ['VIP', '种植'], external_status: 'IN_PRODUCTION', current_action: 'DESIGN_REVIEW_REQUIRED', created_at: '2026-07-15 09:20', due_at: '2026-07-24', quote: { amount_minor: 168000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE', 'APPROVE_REVIEW', 'REJECT_REVIEW'], state_version: 12
  },
  {
    order_id: '1002', order_no: 'ORD20260718-1002', doctor_name: '陈医生', patient_id: 'P-1002', patient_code: 'A031', patient_name: '李*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_CROWN', product_name: '常规牙冠', tags: ['加急'], external_status: 'NEEDS_INFO', current_action: 'SUPPLEMENT_REQUIRED', created_at: '2026-07-16 14:10', due_at: '2026-07-23', quote: null, allowed_actions: ['VIEW_ORDER', 'SUPPLEMENT_ORDER', 'SEND_MESSAGE'], state_version: 5
  },
  {
    order_id: '1003', order_no: 'ORD20260717-1003', doctor_name: '陈医生', patient_id: 'P-1003', patient_code: 'B008', patient_name: '王*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_CROWN', product_name: '常规牙冠', tags: ['新患者'], external_status: 'IN_PRODUCTION', current_action: 'POST_MILLING_REVIEW_REQUIRED', created_at: '2026-07-13 11:35', due_at: '2026-07-22', quote: { amount_minor: 98000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE', 'APPROVE_REVIEW', 'REJECT_REVIEW'], state_version: 17
  },
  {
    order_id: '1004', order_no: 'ORD20260712-1004', doctor_name: '陈医生', patient_id: 'P-1004', patient_code: 'B012', patient_name: '赵*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_BRIDGE', product_name: '固定桥', tags: [], external_status: 'AWAITING_PAYMENT', current_action: 'PAYMENT_REQUIRED', created_at: '2026-07-12 10:05', due_at: '2026-07-20', quote: { amount_minor: 286000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE', 'PAY_BILL'], state_version: 22
  },
  {
    order_id: '1005', order_no: 'ORD20260710-1005', doctor_name: '陈医生', patient_id: 'P-1001', patient_code: 'A026', patient_name: '张*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_CROWN', product_name: '常规牙冠', tags: ['复诊'], external_status: 'SHIPPED', current_action: 'NONE', created_at: '2026-07-10 08:40', due_at: '2026-07-19', quote: { amount_minor: 108000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE'], state_version: 26
  },
  {
    order_id: '1006', order_no: 'ORD20260708-1006', doctor_name: '陈医生', patient_id: 'P-1005', patient_code: 'C003', patient_name: '周*', clinic_name: '明悦口腔诊所', product_type: 'IMPLANT_RESTORATION', product_name: '种植冠', tags: ['VIP'], external_status: 'DELIVERED_PENDING_CONFIRMATION', current_action: 'RECEIPT_CONFIRMATION_REQUIRED', created_at: '2026-07-08 16:20', due_at: '2026-07-18', quote: { amount_minor: 178000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE', 'CONFIRM_RECEIPT'], state_version: 31
  },
  {
    order_id: '1007', order_no: 'ORD20260702-1007', doctor_name: '陈医生', patient_id: 'P-1002', patient_code: 'A031', patient_name: '李*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_CROWN', product_name: '常规牙冠', tags: [], external_status: 'COMPLETED', current_action: 'NONE', created_at: '2026-07-02 09:15', due_at: '2026-07-12', quote: { amount_minor: 98000, currency: 'CNY' }, allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE'], state_version: 36
  },
  {
    order_id: '1008', order_no: 'DRAFT-20260718-08', doctor_name: '陈医生', patient_id: 'P-1003', patient_code: 'B008', patient_name: '王*', clinic_name: '明悦口腔诊所', product_type: 'FIXED_BRIDGE', product_name: '固定桥', tags: ['草稿'], external_status: 'DRAFT', current_action: 'NONE', created_at: '2026-07-18 13:10', due_at: '-', quote: null, allowed_actions: ['VIEW_ORDER', 'SUBMIT_ORDER'], state_version: 2
  }
]

const publicProgressMilestones = [
  { key: 'review', label: '资料审核', rank: 0, note: '订单资料正在审核' },
  { key: 'design', label: '方案设计', rank: 1, note: '订单已通过审核，正在进行方案设计' },
  { key: 'production', label: '制作处理中', rank: 2, note: '方案已确认，正在制作' },
  { key: 'final-review', label: '成品复核', rank: 3, note: '成品正在复核' },
  { key: 'ready-to-ship', label: '待发货', rank: 4, note: '成品已完成，等待发货' },
  { key: 'shipped', label: '配送中', rank: 5, note: '订单已发货，请在物流页面查看配送信息' },
  { key: 'completed', label: '已完成', rank: 6, note: '订单已完成' }
] as const

function publicProgressRank(order: OrderSummary): number {
  if (order.external_status === 'DRAFT') return -1
  if (['SUBMITTED', 'UNDER_REVIEW', 'NEEDS_INFO'].includes(order.external_status)) return 0
  if (order.external_status === 'IN_PRODUCTION') {
    if (order.current_action === 'DESIGN_REVIEW_REQUIRED') return 1
    if (order.current_action === 'POST_MILLING_REVIEW_REQUIRED') return 3
    return 2
  }
  if (order.external_status === 'PRODUCTION_COMPLETED') return 3
  if (['READY_TO_DISPATCH', 'AWAITING_PAYMENT'].includes(order.external_status)) return 4
  if (['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(order.external_status)) return 5
  if (order.external_status === 'COMPLETED') return 6
  return 0
}

function publicProgressFor(order: OrderSummary): PublicProgressItem[] {
  const currentRank = publicProgressRank(order)
  return [
    {
      key: 'submitted',
      label: currentRank < 0 ? '订单待提交' : '订单已提交',
      status: currentRank < 0 ? 'ACTIVE' : 'DONE',
      occurred_at: order.created_at,
      note: currentRank < 0 ? '订单仍为草稿，提交后进入资料审核' : '订单已进入公开处理流程'
    },
    ...publicProgressMilestones.map((milestone): PublicProgressItem => {
      const status = currentRank > milestone.rank
        ? 'DONE'
        : currentRank === milestone.rank
          ? milestone.rank === 6 ? 'DONE' : 'ACTIVE'
          : 'PENDING'
      const deliveredNote = order.external_status === 'DELIVERED_PENDING_CONFIRMATION' && milestone.rank === 5
        ? '订单已送达，等待确认收货'
        : milestone.note
      return {
        key: milestone.key,
        label: milestone.label,
        status,
        note: status === 'ACTIVE' ? deliveredNote : undefined
      }
    })
  ]
}

const patients: PatientSummary[] = [
  { patient_id: 'P-1001', patient_code: 'A026', patient_name: '张先生', patient_age: 42, patient_gender: '男', date_of_birth: '1984-03-18', phone: '138****2026', email: 'zhang@example.com', medical_notes: '青霉素过敏；请避免相关用药。', treatment_status: 'IN_TREATMENT', treatment_started_at: '2026-04-01', treatment_ended_at: null, clinic_name: '明悦口腔诊所', doctor_name: '陈医生', tags: ['VIP', '种植'], oral_description: '右上后牙缺失，已完成种植体植入。', latest_order_no: 'ORD20260718-1001', latest_product_name: '种植冠', latest_order_at: '2026-07-15', created_at: '2026-04-01', updated_at: '2026-07-18', order_count: 4 },
  { patient_id: 'P-1002', patient_code: 'A031', patient_name: '李女士', patient_age: 35, patient_gender: '女', date_of_birth: '1991-06-12', phone: '139****8812', email: '', medical_notes: '', treatment_status: 'IN_TREATMENT', treatment_started_at: '2026-05-10', treatment_ended_at: null, clinic_name: '明悦口腔诊所', doctor_name: '陈医生', tags: ['复诊'], oral_description: '左下后牙牙体缺损。', latest_order_no: 'ORD20260718-1002', latest_product_name: '常规牙冠', latest_order_at: '2026-07-16', created_at: '2026-05-10', updated_at: '2026-07-17', order_count: 3 },
  { patient_id: 'P-1003', patient_code: 'B008', patient_name: '王先生', patient_age: 51, patient_gender: '男', date_of_birth: '1975-09-03', phone: '', email: '', medical_notes: '高血压病史，术前复核血压。', treatment_status: 'FOLLOW_UP', treatment_started_at: '2026-03-15', treatment_ended_at: null, clinic_name: '明悦口腔诊所', doctor_name: '陈医生', tags: ['新患者'], oral_description: '前牙美学修复咨询。', latest_order_no: 'ORD20260717-1003', latest_product_name: '常规牙冠', latest_order_at: '2026-07-13', created_at: '2026-03-15', updated_at: '2026-07-18', order_count: 2 },
  { patient_id: 'P-1004', patient_code: 'B012', patient_name: '赵女士', patient_age: 47, patient_gender: '女', date_of_birth: '1979-12-23', phone: '', email: '', medical_notes: '', treatment_status: 'TREATMENT_ENDED', treatment_started_at: '2026-01-10', treatment_ended_at: '2026-05-05', clinic_name: '明悦口腔诊所', doctor_name: '陈医生', tags: [], oral_description: '下颌后牙连续缺失。', latest_order_no: 'ORD20260712-1004', latest_product_name: '固定桥', latest_order_at: '2026-07-12', created_at: '2026-01-10', updated_at: '2026-07-12', order_count: 1 },
  { patient_id: 'P-1005', patient_code: 'C003', patient_name: '周先生', patient_age: 39, patient_gender: '男', date_of_birth: '1987-02-06', phone: '', email: '', medical_notes: '', treatment_status: 'ARCHIVED', treatment_started_at: '2025-10-02', treatment_ended_at: '2026-02-11', clinic_name: '明悦口腔诊所', doctor_name: '陈医生', tags: ['VIP'], oral_description: '左上第一磨牙种植修复。', latest_order_no: 'ORD20260708-1006', latest_product_name: '种植冠', latest_order_at: '2026-07-08', created_at: '2025-10-02', updated_at: '2026-07-08', order_count: 5 }
]

const bills: BillRecord[] = orders.filter((order) => order.quote).map((order, index) => {
  const paid = order.order_id === '1004' ? 0 : order.quote!.amount_minor
  return {
    bill_id: `BILL-${order.order_id}`,
    order_id: order.order_id,
    order_no: order.order_no,
    clinic_name: order.clinic_name,
    doctor_name: order.doctor_name,
    product_name: order.product_name,
    settlement_type: index === 2 ? 'MONTHLY' : 'PER_ORDER',
    amount: clone(order.quote!),
    paid: { amount_minor: paid, currency: order.quote!.currency },
    outstanding: { amount_minor: order.quote!.amount_minor - paid, currency: order.quote!.currency },
    payment_status: paid ? 'PAID' : 'UNPAID',
    bill_status: 'ISSUED',
    issued_at: order.created_at,
    due_at: order.due_at,
    allowed_actions: paid ? ['REQUEST_INVOICE'] : ['PAY_BILL', 'REQUEST_INVOICE']
  }
})

const logistics: LogisticsRecord[] = [
  { logistics_id: 'LG-1005', order_id: '1005', order_no: 'ORD20260710-1005', product_name: '常规牙冠', carrier: '顺丰速运', tracking_no: 'SF1234567890', status: 'IN_TRANSIT', updated_at: '2026-07-18 14:30', can_confirm_receipt: false, events: [{ label: '已发出', time: '2026-07-17 10:20', location: '上海' }, { label: '运输中', time: '2026-07-18 14:30', location: '苏州' }] },
  { logistics_id: 'LG-1006', order_id: '1006', order_no: 'ORD20260708-1006', product_name: '种植冠', carrier: '顺丰速运', tracking_no: 'SF9876543210', status: 'DELIVERED_PENDING_CONFIRMATION', updated_at: '2026-07-18 11:05', can_confirm_receipt: true, events: [{ label: '已发出', time: '2026-07-16 09:10', location: '上海' }, { label: '已送达', time: '2026-07-18 11:05', location: '诊所前台' }] }
]

const cadReview = review('RV-1001-CAD', 'CAD_DESIGN', 'PENDING_REVIEW', [
  { version: 1, status: 'REJECTED', submitted_at: '2026-07-16 10:20', doctor_comment: '邻接面请再调整', files: [file('F-CAD-1', 'design-v1.stl', 'STL', '2026-07-16 10:20')] },
  { version: 2, status: 'SUPERSEDED', submitted_at: '2026-07-17 11:00', files: [file('F-CAD-2', 'design-v2.stl', 'STL', '2026-07-17 11:00')] },
  { version: 3, status: 'PENDING', submitted_at: '2026-07-18 09:10', files: [file('F-CAD-3', 'design-v3.stl', 'STL', '2026-07-18 09:10')] }
])

const millingReview = review('RV-1003-MILL', 'POST_MILLING_PHOTOS', 'PENDING_REVIEW', [
  { version: 1, status: 'PENDING', submitted_at: '2026-07-18 10:35', files: [file('F-MILL-1', 'milling-front.jpg', 'IMAGE', '2026-07-18 10:35'), file('F-MILL-2', 'milling-margin.jpg', 'IMAGE', '2026-07-18 10:35')] }
])

const details = new Map<string, OrderDetail>()
for (const order of orders) {
  const orderReviews = order.order_id === '1001' ? [cadReview] : order.order_id === '1003' ? [millingReview] : []
  details.set(order.order_id, {
    ...clone(order),
    public_message: order.current_action === 'SUPPLEMENT_REQUIRED' ? '请补充比色照片，补齐后将继续审核。' : '订单正按已确认的公开进度处理。',
    form_snapshot: {
      '牙位': order.product_type === 'FIXED_BRIDGE' ? '35–37' : '14',
      '材料': order.product_type === 'IMPLANT_RESTORATION' ? '氧化锆·钛基台' : '氧化锆',
      '色号': 'A2',
      '制作要求': '依订单确认资料制作'
    },
    progress: publicProgressFor(order),
    review_options: orderReviews.map((item) => item.review_type),
    reviews: clone(orderReviews),
    files: [file(`F-ORDER-${order.order_id}`, `${order.patient_code}-scan.stl`, 'STL', order.created_at), file(`F-PHOTO-${order.order_id}`, `${order.patient_code}-shade.jpg`, 'IMAGE', order.created_at)],
    messages: [],
    bill_summary: {
      bill_status: order.quote ? 'ISSUED' : 'PENDING_QUOTE',
      payment_status: order.current_action === 'PAYMENT_REQUIRED' ? 'UNPAID' : order.quote ? 'PAID' : 'UNPAID',
      outstanding: order.current_action === 'PAYMENT_REQUIRED' ? clone(order.quote) : order.quote ? { amount_minor: 0, currency: order.quote.currency } : null
    }
  })
}

const threads: MessageThread[] = [
  {
    thread_id: 'TH-1001', order_id: '1001', order_no: 'ORD20260718-1001', patient_name: '张*', product_name: '种植冠', unread: true, latest_message: '新版设计稿已提交，请确认。', latest_at: '10分钟前',
    messages: [
      { message_id: 'M-1', sender: 'ORDER_SERVICE', content: '订单资料已确认，已进入制作阶段。', sent_at: '2026-07-16 09:30', status: 'SENT', attachments: [] },
      { message_id: 'M-2', sender: 'SELF', content: '好的，请在设计稿完成后通知我。', sent_at: '2026-07-16 09:42', status: 'SENT', attachments: [] },
      { message_id: 'M-3', sender: 'ORDER_SERVICE', content: '新版设计稿已提交，请确认。', sent_at: '2026-07-18 09:12', status: 'SENT', attachments: [], review: clone(cadReview) }
    ]
  },
  {
    thread_id: 'TH-1003', order_id: '1003', order_no: 'ORD20260717-1003', patient_name: '王*', product_name: '常规牙冠', unread: true, latest_message: '切削后照片已上传，请确认。', latest_at: '45分钟前', messages: [{ message_id: 'M-4', sender: 'ORDER_SERVICE', content: '切削后照片已上传，请确认是否继续。', sent_at: '2026-07-18 10:36', status: 'SENT', attachments: [], review: clone(millingReview) }]
  },
  {
    thread_id: 'TH-1002', order_id: '1002', order_no: 'ORD20260718-1002', patient_name: '李*', product_name: '常规牙冠', unread: false, latest_message: '请补充比色照片。', latest_at: '昨天', messages: [{ message_id: 'M-5', sender: 'ORDER_SERVICE', content: '当前资料缺少比色照片，请在订单中补充。', sent_at: '2026-07-17 14:20', status: 'SENT', attachments: [] }]
  }
]

const notifications: DoctorNotification[] = [
  { notification_id: 'N-1', category: 'REVIEW', title: '设计稿待确认', summary: 'ORD20260718-1001 新版设计稿已上传。', read: false, created_at: '10分钟前', target_type: 'ORDER', target_id: '1001' },
  { notification_id: 'N-2', category: 'MESSAGE', title: '新消息', summary: 'ORD20260717-1003 有新的照片确认消息。', read: false, created_at: '45分钟前', target_type: 'MESSAGE', target_id: 'TH-1003' },
  { notification_id: 'N-3', category: 'BILLING', title: '账单待付款', summary: 'ORD20260712-1004 待付金额 ¥2,860.00。', read: false, created_at: '2小时前', target_type: 'BILLING', target_id: 'BILL-1004' },
  { notification_id: 'N-4', category: 'LOGISTICS', title: '已送达，待确认收货', summary: 'ORD20260708-1006 已送达诊所。', read: true, created_at: '4小时前', target_type: 'ORDER', target_id: '1006' }
]

const account: DoctorAccount = {
  display_name: '陈医生',
  email: 'portfolio-user01@example.test',
  clinic_name: '明悦口腔诊所',
  clinic_address: '上海市徐汇区漕溪北路 88 号',
  clinic_contact: '021-5555-8899',
  notification_preferences: {
    ORDER_STATUS: { in_app: true, email: true },
    REVIEW_REQUEST: { in_app: true, email: true },
    MESSAGE: { in_app: true, email: false },
    BILLING: { in_app: true, email: true },
    LOGISTICS: { in_app: true, email: false }
  },
  members: [
    { member_id: 'MB-1', display_name: '陈医生', email: 'portfolio-user01@example.test', roles: ['CLINIC_ADMIN', 'DOCTOR'], status: 'ACTIVE', billing_permission: 'FINANCIAL_ACTION', logistics_permission: 'RECEIPT' },
    { member_id: 'MB-2', display_name: '林医生', email: 'portfolio-user02@example.test', roles: ['DOCTOR'], status: 'ACTIVE', billing_permission: 'VIEW', logistics_permission: 'RECEIPT' },
    { member_id: 'MB-3', display_name: '张前台', email: 'portfolio-user03@example.test', roles: ['RECEPTION'], status: 'ACTIVE', billing_permission: 'VIEW', logistics_permission: 'VIEW' },
    { member_id: 'MB-4', display_name: '周护士', email: 'portfolio-user04@example.test', roles: ['NURSE'], status: 'PENDING_ACTIVATION', billing_permission: 'NONE', logistics_permission: 'VIEW' }
  ]
}

const dataset: DoctorPortalDataset = {
  orders,
  patients,
  bills,
  statements: [
    { statement_id: 'MS-2026-06', period: '2026-06', clinic_name: account.clinic_name, order_count: 18, total: { amount_minor: 2386000, currency: 'CNY' }, paid: { amount_minor: 1800000, currency: 'CNY' }, balance: { amount_minor: 586000, currency: 'CNY' }, status: 'OPEN', due_at: '2026-07-31' },
    { statement_id: 'MS-2026-05', period: '2026-05', clinic_name: account.clinic_name, order_count: 21, total: { amount_minor: 2680000, currency: 'CNY' }, paid: { amount_minor: 2680000, currency: 'CNY' }, balance: { amount_minor: 0, currency: 'CNY' }, status: 'SETTLED', due_at: '2026-06-30' }
  ],
  invoiceRefunds: [
    { record_id: 'INV-202607-03', kind: 'INVOICE', related_no: 'BILL-1001', title: '明悦口腔诊所', amount: { amount_minor: 168000, currency: 'CNY' }, status: 'ISSUED', created_at: '2026-07-17' },
    { record_id: 'RF-202607-01', kind: 'REFUND', related_no: 'ORD20260702-1007', title: '部分退款申请', amount: { amount_minor: 20000, currency: 'CNY' }, status: 'PENDING_REVIEW', created_at: '2026-07-18' }
  ],
  logistics,
  threads,
  notifications,
  account,
  products
}

function patientDetail(patient: PatientSummary): PatientDetail {
  return {
    ...clone(patient),
    notes: patient.medical_notes,
    orders: orders.filter((order) => order.patient_id === patient.patient_id).map((order) => ({ order_id: order.order_id, order_no: order.order_no, product_name: order.product_name, external_status: order.external_status, created_at: order.created_at })),
    history_references: [
      { order_no: 'ORD20260312-0812', product_name: patient.latest_product_name ?? '常规牙冠', matched_fields: ['产品类型', '材料', '牙位区域'], summary: '同诊所权限范围内的相似历史订单，仅供资料参考。' }
    ]
  }
}

export class MockDoctorGateway implements DoctorGateway {
  private activeRole: ClinicRole = 'DOCTOR'

  updateToken(_token: string): void {
    // Mock mode has no remote session.
  }

  async loadDataset(): Promise<DoctorPortalDataset> {
    const snapshot = clone(dataset)
    if (this.activeRole !== 'DOCTOR') {
      const doctorOnlyActions = new Set(['CREATE_ORDER', 'SUBMIT_ORDER', 'APPROVE_REVIEW', 'REJECT_REVIEW'])
      snapshot.orders.forEach((order) => { order.allowed_actions = order.allowed_actions.filter((action) => !doctorOnlyActions.has(action)) })
      snapshot.threads.forEach((thread) => thread.messages.forEach((message) => {
        if (message.review) message.review.allowed_actions = []
      }))
    }
    return snapshot
  }

  async switchRole(role: ClinicRole): Promise<DoctorPortalDataset> {
    this.activeRole = role
    return await this.loadDataset()
  }

  async loadOrderDetail(orderId: string): Promise<OrderDetail> {
    const detail = details.get(orderId)
    if (!detail) throw new Error('订单不存在')
    const result = clone(detail)
    result.messages = clone(threads.find((thread) => thread.order_id === orderId)?.messages ?? [])
    if (this.activeRole !== 'DOCTOR') {
      result.allowed_actions = result.allowed_actions.filter((action) => !['CREATE_ORDER', 'SUBMIT_ORDER', 'APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))
      result.reviews.forEach((item) => { item.allowed_actions = [] })
    }
    return result
  }

  async getFilePreviewUrl(fileId: string): Promise<string> {
    const files = [...details.values()].flatMap((detail) => [
      ...detail.files,
      ...detail.reviews.flatMap((item) => item.versions.flatMap((version) => version.files))
    ])
    const file = files.find((item) => item.file_id === fileId)
    if (!file) throw new Error('文件不存在或当前账号无权预览')
    if (file.preview_url) return file.preview_url
    if (file.kind === 'IMAGE') {
      const label = encodeURIComponent(file.name)
      return `data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' width='960' height='600'%3E%3Crect width='100%25' height='100%25' fill='%23eef4ff'/%3E%3Ctext x='50%25' y='50%25' text-anchor='middle' fill='%231d4ed8' font-size='28'%3E${label}%3C/text%3E%3C/svg%3E`
    }
    return `data:text/plain;charset=utf-8,${encodeURIComponent(`模拟预览：${file.name}`)}`
  }

  async loadPatientDetail(patientId: string): Promise<PatientDetail> {
    const patient = patients.find((item) => item.patient_id === patientId)
    if (!patient) throw new Error('患者不存在')
    return patientDetail(patient)
  }

  async createPatient(input: PatientCreateInput): Promise<PatientSummary> {
    const item: PatientSummary = {
      patient_id: `P-${Date.now()}`,
      patient_code: `P${String(patients.length + 1).padStart(3, '0')}`,
      patient_name: input.patientName,
      patient_age: input.patientAge,
      patient_gender: input.patientGender,
      date_of_birth: input.dateOfBirth,
      phone: input.phone,
      email: input.email,
      medical_notes: input.medicalNotes,
      treatment_status: input.treatmentStatus,
      treatment_started_at: input.treatmentStartedAt,
      treatment_ended_at: input.treatmentEndedAt,
      clinic_name: account.clinic_name,
      doctor_name: account.display_name,
      tags: [...input.tags],
      oral_description: input.oralDescription,
      latest_order_no: null,
      latest_product_name: null,
      latest_order_at: null,
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
      order_count: 0
    }
    patients.unshift(item)
    return clone(item)
  }

  async updatePatient(input: PatientUpdateInput): Promise<PatientSummary> {
    const patient = patients.find((item) => item.patient_id === input.patientId)
    if (!patient) throw new Error('患者不存在')
    Object.assign(patient, {
      patient_name: input.patientName,
      patient_age: input.patientAge,
      patient_gender: input.patientGender,
      date_of_birth: input.dateOfBirth,
      phone: input.phone,
      email: input.email,
      medical_notes: input.medicalNotes,
      treatment_status: input.treatmentStatus,
      treatment_started_at: input.treatmentStartedAt,
      treatment_ended_at: input.treatmentEndedAt,
      oral_description: input.oralDescription,
      tags: [...input.tags],
      updated_at: new Date().toISOString()
    })
    return clone(patient)
  }

  async saveDraft(input: OrderDraftInput): Promise<OrderSummary> {
    const patient = patients.find((item) => item.patient_id === input.patientId) ?? patients[0]
    const product = products.find((item) => item.product_id === input.productId) ?? products[0]
    const existing = input.draftOrderId
      ? orders.find((item) => item.order_id === input.draftOrderId)
      : undefined
    const saved: OrderSummary = {
      order_id: existing?.order_id ?? `DRAFT-${Date.now()}`,
      order_no: existing?.order_no ?? `DRAFT-${now.slice(0, 10).replaceAll('-', '')}-${String(Date.now()).slice(-4)}`,
      doctor_name: account.display_name,
      patient_id: patient.patient_id,
      patient_code: patient.patient_code,
      patient_name: patient.patient_name,
      clinic_name: account.clinic_name,
      product_type: product.product_type,
      product_name: product.product_name,
      tags: ['草稿'],
      external_status: 'DRAFT',
      current_action: 'NONE',
      created_at: existing?.created_at ?? now,
      due_at: '-',
      quote: null,
      allowed_actions: ['VIEW_ORDER', 'SUBMIT_ORDER'],
      state_version: (existing?.state_version ?? 0) + 1
    }
    if (existing) Object.assign(existing, saved)
    else orders.unshift(saved)
    details.set(saved.order_id, {
      ...clone(saved),
      public_message: '订单仍为草稿，提交后进入资料审核。',
      form_snapshot: { ...clone(input.caseFields), ...clone(input.dynamicFields) },
      progress: publicProgressFor(saved),
      review_options: [...input.reviewOptions],
      reviews: [],
      files: clone(input.files),
      messages: [],
      bill_summary: { bill_status: 'PENDING_QUOTE', payment_status: 'UNPAID', outstanding: null }
    })
    return clone(saved)
  }

  async uploadOrderFiles(_orderId: string, files: File[]): Promise<DoctorFile[]> {
    return files.map((item, index) => {
      const extension = item.name.split('.').pop()?.toLowerCase()
      return {
        file_id: `MOCK-F-${Date.now()}-${index}`,
        name: item.name,
        kind: extension === 'stl' ? 'STL' : extension === 'pdf' ? 'PDF' : 'IMAGE',
        size_label: `${Math.max(0.1, item.size / 1024 / 1024).toFixed(1)} MB`,
        status: 'READY',
        uploaded_at: now
      }
    })
  }

  async submitOrder(input: OrderDraftInput): Promise<OrderSummary> {
    const patient = patients.find((item) => item.patient_id === input.patientId) ?? patients[0]
    const product = products.find((item) => item.product_id === input.productId) ?? products[0]
    const existingDraft = input.draftOrderId
      ? orders.find((item) => item.order_id === input.draftOrderId)
      : undefined
    const created: OrderSummary = {
      order_id: existingDraft?.order_id ?? `MOCK-${Date.now()}`,
      order_no: existingDraft?.order_no.replace(/^DRAFT-/, 'ORD') ?? `ORD${now.slice(0, 10).replaceAll('-', '')}-${String(Date.now()).slice(-4)}`,
      doctor_name: account.display_name,
      patient_id: patient.patient_id,
      patient_code: patient.patient_code,
      patient_name: patient.patient_name,
      clinic_name: account.clinic_name,
      product_type: product.product_type,
      product_name: product.product_name,
      tags: [],
      external_status: 'SUBMITTED',
      current_action: 'NONE',
      created_at: existingDraft?.created_at ?? now,
      due_at: '后端计算中',
      quote: product.quote,
      allowed_actions: ['VIEW_ORDER', 'SEND_MESSAGE'],
      state_version: (existingDraft?.state_version ?? 0) + 1
    }
    const createdReviews = input.reviewOptions.map((type, index) => review(`RV-${created.order_id}-${index + 1}`, type, 'WAITING', []))
    const detail: OrderDetail = {
      ...clone(created),
      public_message: '订单已提交，等待订单服务确认资料。',
      form_snapshot: { ...clone(input.caseFields), ...clone(input.dynamicFields) },
      progress: publicProgressFor(created),
      review_options: [...input.reviewOptions],
      reviews: createdReviews,
      files: clone(input.files),
      messages: [],
      bill_summary: { bill_status: 'PENDING_QUOTE', payment_status: 'UNPAID', outstanding: null }
    }
    if (existingDraft) Object.assign(existingDraft, created)
    else orders.unshift(created)
    details.set(created.order_id, detail)
    const patientRecord = patients.find((item) => item.patient_id === created.patient_id)
    if (patientRecord) {
      patientRecord.order_count += 1
      patientRecord.latest_order_no = created.order_no
      patientRecord.latest_product_name = created.product_name
      patientRecord.latest_order_at = created.created_at
    }
    threads.unshift({
      thread_id: `TH-${created.order_id}`,
      order_id: created.order_id,
      order_no: created.order_no,
      patient_name: created.patient_name,
      product_name: created.product_name,
      unread: false,
      latest_message: '订单已提交，等待资料确认。',
      latest_at: '刚刚',
      messages: [{ message_id: `M-${created.order_id}`, sender: 'ORDER_SERVICE', content: '订单已提交，等待资料确认。', sent_at: now, status: 'SENT', attachments: [] }]
    })
    return clone(created)
  }

  async submitReview(input: ReviewDecisionInput): Promise<OrderReview> {
    const detail = details.get(input.orderId)
    const item = detail?.reviews.find((candidate) => candidate.review_id === input.reviewId)
    if (!item) throw new Error('审核项不存在')
    if (item.status !== 'PENDING_REVIEW' || !item.allowed_actions.includes(input.decision === 'APPROVE' ? 'APPROVE_REVIEW' : 'REJECT_REVIEW')) {
      throw new Error('当前版本已处理，请刷新后查看最新状态')
    }
    if (item.state_version !== input.stateVersion) throw new Error('确认状态已更新，请刷新后重试')
    if (!input.idempotencyKey.trim()) throw new Error('缺少幂等操作标识')
    if (input.decision === 'REJECT' && !input.comment?.trim()) throw new Error('驳回时必须填写修改意见')
    item.status = input.decision === 'APPROVE' ? 'APPROVED' : 'REVISION_REQUESTED'
    item.allowed_actions = []
    item.state_version += 1
    const latest = item.versions.at(-1)
    if (latest) {
      latest.status = input.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'
      if (input.comment) latest.doctor_comment = input.comment
    }
    threads.forEach((thread) => thread.messages.forEach((message) => {
      if (message.review?.review_id === item.review_id) message.review = clone(item)
    }))
    const summary = orders.find((order) => order.order_id === input.orderId)
    const hasPendingReview = detail?.reviews.some((candidate) => candidate.status === 'PENDING_REVIEW') ?? false
    if (summary) {
      if (!hasPendingReview) {
        summary.current_action = 'NONE'
        summary.allowed_actions = summary.allowed_actions.filter((action) => !['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))
      }
      summary.state_version += 1
    }
    if (detail) {
      if (!hasPendingReview) {
        detail.current_action = 'NONE'
        detail.allowed_actions = detail.allowed_actions.filter((action) => !['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))
      }
      detail.state_version = summary?.state_version ?? detail.state_version + 1
      detail.public_message = input.decision === 'APPROVE'
        ? '当前版本已同意，订单将继续后续制作。'
        : '修改意见已发送，等待订单服务提交新版本。'
    }
    return clone(item)
  }

  async sendMessage(threadId: string, content: string): Promise<Message> {
    const message: Message = { message_id: `M-${Date.now()}`, sender: 'SELF', content, sent_at: now, status: 'SENT', attachments: [] }
    const thread = threads.find((item) => item.thread_id === threadId)
    if (thread) {
      thread.messages.push(message)
      thread.latest_message = content
      thread.latest_at = '刚刚'
    }
    return clone(message)
  }

  async markThreadRead(threadId: string): Promise<void> {
    const thread = threads.find((item) => item.thread_id === threadId)
    if (thread) thread.unread = false
  }

  async markNotificationRead(notificationId: string): Promise<void> {
    const item = notifications.find((notification) => notification.notification_id === notificationId)
    if (item) item.read = true
  }

  async markAllNotificationsRead(): Promise<void> {
    notifications.forEach((notification) => { notification.read = true })
  }

  async confirmReceipt(orderId: string, _stateVersion: number): Promise<void> {
    const item = orders.find((order) => order.order_id === orderId)
    if (!item?.allowed_actions.includes('CONFIRM_RECEIPT')) throw new Error('当前订单不可确认收货')
    item.external_status = 'COMPLETED'
    item.current_action = 'NONE'
    item.allowed_actions = ['VIEW_ORDER', 'SEND_MESSAGE']
    const logisticsItem = logistics.find((candidate) => candidate.order_id === orderId)
    if (logisticsItem) {
      logisticsItem.status = 'COMPLETED'
      logisticsItem.can_confirm_receipt = false
      logisticsItem.updated_at = now
      logisticsItem.events.push({ label: '医生已确认收货', time: now, location: '诊所' })
    }
    const detail = details.get(orderId)
    if (detail) {
      detail.external_status = 'COMPLETED'
      detail.current_action = 'NONE'
      detail.allowed_actions = ['VIEW_ORDER', 'SEND_MESSAGE']
      detail.progress = detail.progress.map((progress) => ({ ...progress, status: 'DONE' }))
    }
  }

  async askAssistant(question: string, _orderId?: string): Promise<{ answer: string; orderIds: string[] }> {
    const unsafe = /(内部工序|技师|员工|质检|返工|工时|绩效|责任)/.test(question)
    if (unsafe) {
      return { answer: '抱歉，我只能提供您权限范围内的订单公开进度、账单、物流和医生可见消息。', orderIds: [] }
    }
    const matched = orders.filter((order) => question.includes(order.order_no) || question.includes(order.patient_code)).slice(0, 3)
    const targets = matched.length ? matched : orders.filter((order) => order.current_action !== 'NONE').slice(0, 3)
    return {
      answer: targets.length
        ? `找到 ${targets.length} 条相关订单。以下内容仅包含当前角色可见的公开状态。`
        : '未找到当前权限范围内的相关订单。',
      orderIds: targets.map((order) => order.order_id)
    }
  }

  async askFaq(question: string, _category?: string): Promise<DoctorFaqAnswer> {
    const unsafe = /(内部工序|技师|员工|质检|返工|工时|绩效|责任)/.test(question)
    if (unsafe) {
      return {
        answer: '我只能回答下单流程、产品材料、交期物流、返工售后和账单方面的常见问题。',
        resultStatus: 'SAFE_REFUSAL',
        matchedQuestions: [],
        requiresCustomerConfirmation: false
      }
    }
    const known = ['下单需要提供哪些资料？', '口扫文件支持哪些格式？', '订单大概多久能做好？']
    const matched = known.filter((item) => question.split('').some((char) => item.includes(char)))
    if (!matched.length) {
      return {
        answer: '这个问题暂时不在常见问题库里，请通过沟通中心联系客服。',
        resultStatus: 'NO_MATCH',
        matchedQuestions: [],
        requiresCustomerConfirmation: false
      }
    }
    return {
      answer: '（演示数据）常见问题示例回答，正式语料待甲方确认。',
      resultStatus: 'SUCCESS',
      matchedQuestions: matched.slice(0, 3),
      requiresCustomerConfirmation: true
    }
  }

  async recommendProducts(_caseNote?: string): Promise<DoctorProductRecommendation[]> {
    return [
      {
        productId: 'mock-1',
        displayName: '（演示数据）常规牙冠',
        categoryName: '固定修复',
        reason: '演示数据，不代表真实推荐结果。'
      }
    ]
  }
}
