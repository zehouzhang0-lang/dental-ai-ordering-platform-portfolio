<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import CustomerManagementPage from './CustomerManagementPage.vue'
import { productionProgressNodes } from '../utils/productionProgress'
import { staffOrderIdentity } from '../utils/orderIdentity'

const StlViewerDialog = defineAsyncComponent(() => import('./StlViewerDialog.vue'))

type AuthMenu = {
  menuCode: string
  menuName: string
  routePath: string | null
  permissionCode: string | null
}

type LoginUser = {
  username: string
  userId: string | number | null
  clinicId: number | null
  roles: string[]
  permissions: string[]
  menus: AuthMenu[]
  dataScope: string | null
}

type ApiResponse<T> = { code: number; msg: string; data: T }
type Paged<T> = { items: T[]; total: number; page: number; size: number }

type OrderItem = {
  order_id: number
  order_no: string
  clinic_id: number
  clinic_name: string
  doctor_user_id: number | null
  patient_id?: number | null
  patient_name?: string | null
  cs_user_id: number | null
  product_type: string
  internal_status: string
  external_status: string
  production_note: string | null
  reject_reason: string | null
  form_schema_snapshot?: unknown
  form_data: Record<string, unknown>
  // TASK-034 F 批次：交期引擎的输出。delivery_alert 非空即客户要求的「时间异常提示」。
  promised_delivery_date?: string | null
  doctor_requested_delivery_date?: string | null
  delivery_variance_days?: number | null
  delivery_alert?: string | null
  delivery_alert_message?: string | null
  delivery_estimate_status?: string | null
  created_at?: string
  updated_at?: string
}

type MessageItem = {
  msg_id: number
  order_id: number
  order_no: string
  product_type: string
  external_status: string
  sender_user_id: number | null
  sender_role: string
  content: string
  visible_to: string
  review_status: string
  mention_user_ids?: number[]
  created_at?: string
}

type AttentionItem = {
  message_id: number
  order_id: number
  order_no: string
  sender_role: string
  content: string
  created_at: string
}

type DesignDraft = {
  draft_id: number
  order_id: number
  version: number
  uploader_user_id: string | number | null
  file_id: number | null
  file_ids: number[]
  file_count: number
  status: string
  cs_reject_reason: string | null
  internal_reject_reason?: string | null
  doctor_reject_reason: string | null
}

type OrderFile = {
  file_id: number
  source_type: string
  visibility: string
  original_filename: string
  content_type: string | null
  file_size: number | null
  upload_status: string
  created_at: string
}

type BillInfo = {
  bill_id: number | null
  order_id: number
  bill_status: string
  payment_status: string
  amount_cents: number | null
  currency: string
  file_id: number | null
}

type UploadTokenResponse = {
  file_id: number
  upload_url: string
  expires_in_seconds: number
}

type FileCompleteResponse = {
  file_id: number
  upload_status: string
  file_size: number
  content_type: string | null
  checksum: string | null
}

type PaymentItem = {
  payment_id: number
  order_id: number
  amount_cents: number
  currency: string
  payment_method: string
  received_at: string
  payment_note: string | null
  created_at: string
}

type LogisticsInfo = {
  logistics_id: number | null
  order_id: number
  carrier: string | null
  tracking_no: string | null
  logistics_status: string
}

type ProcessNodeItem = {
  node_instance_id: number
  node_code: string
  process_name: string
  stage_name: string
  node_category: string | null
  step_order: number
  is_optional: number
  assigned_user_id: number | null
  node_status: string
  standard_duration: number | null
  started_at: string | null
  deadline_at: string | null
  completed_at: string | null
}

type ProcessInstanceInfo = {
  instance_id: number
  order_id: number
  instance_status: string
  intake_branch_used: string | null
  created_at: string
  updated_at: string
  nodes: ProcessNodeItem[]
}

type DeliveryItem = {
  order_id: number
  order_no: string
  product_type: string
  external_status: string
  bill_status: string
  payment_status: string
  carrier: string | null
  tracking_no: string | null
  logistics_status: string
  last_follow_up_note: string | null
}

type QualityRecordItem = {
  quality_record_id: number
  quality_record_type: string
  order_id: number
  order_no: string
  product_type: string
  clinic_name: string
  check_id: number
  check_result: string
  rework_id: number | null
  reason_category: string
  reason_detail: string
  responsibility_type: string
  status: string
  status_note: string
  created_at: string
  status_updated_at: string
  updated_at: string
}

type CsPortalFocusTask =
  | 'ALL_ORDERS'
  | 'ORDER_REVIEW'
  | 'MESSAGE_REVIEW'
  | 'WAITING_REPLY'
  | 'DESIGN_UPDATE'
  | 'DELIVERY_FOLLOW_UP'
  | 'SHIPPING_PENDING'
  | 'BILLING_PENDING'
  | 'QUALITY_FOLLOW_UP'
  | 'SEARCH_CUSTOMER'
  | 'SEARCH_PRODUCT'
  | 'SEARCH_OUTSOURCING'

type ClinicItem = {
  clinic_id: number
  clinic_name: string
  contact_name: string | null
  contact_phone: string | null
  status: string
  preference_count: number
  created_at: string
  updated_at: string
}

type ClinicPreference = {
  clinic_id: number
  clinic_name: string
  preferences: Record<string, unknown>
  updated_at: string
}

type DetailLoadState = { loading: boolean; error: string }
type TimelineStep = { key: string; icon: string; label: string; detail: string; state: 'done' | 'current' | 'pending' }

type ProductItem = {
  product_id: number
  product_type: string
  product_name: string
  material_spec: string | null
  base_price_cents: number
  currency: string
  status: string
  price_note: string | null
  created_at: string
  updated_at: string
}

type FormRequirement = {
  field_id: number
  product_type: string
  field_key: string
  field_label: string
  field_type: string
  is_required: boolean
  options: string[]
  sort_order: number
  status: string
}

type ReviewDisplayField = {
  key: string
  label: string
  value: string
  required: boolean
  missing: boolean
  long: boolean
}

type NotificationItem = {
  notification_id: number
  event_id: number
  event: string
  order_id: number | null
  order_no: string | null
  message: string | null
  read_at: string | null
  delivered_at: string | null
  created_at: string
}

type OutsourcingItem = {
  outsourcing_id: number
  batch_no: string
  order_id: number
  order_no: string
  product_type: string
  item_name: string
  supplier_name: string
  quantity: number
  status: string
  sent_at: string
  expected_return_at: string | null
  actual_return_at: string | null
  abnormal_note: string | null
  created_at: string
  updated_at: string
  overdue: boolean
  is_overdue?: boolean
}

type MissingInfoItem = { field_key: string; field_label: string; tip: string }
type MissingInfoResponse = { is_complete: boolean; missing_items: MissingInfoItem[] }
type TranslateResponse = { translated_text: string }
type ProductionNoteResponse = {
  draft_note: string
  template_version: string
  knowledge_context_notes: string[]
  requires_customer_template_confirmation: boolean
}
type PreviewResponse = { preview_url: string }
type HelpTopic = {
  key: 'START' | 'ORDER' | 'TRANSLATION' | 'INQUIRY' | 'BILLING' | 'PERMISSION'
  label: string
  title: string
  intro: string
  articles: Array<{ title: string; body: string }>
}

const props = defineProps<{
  activeRoute: string
  token: string
  user: LoginUser | null
  authenticatedFetch: typeof fetch
  searchKeyword: string
  focusOrderId: number | null
  focusTask: CsPortalFocusTask | null
}>()

const emit = defineEmits<{
  navigate: [routePath: string, focusOrderId?: number, focusTask?: CsPortalFocusTask]
  focusConsumed: []
  refreshNotifications: []
}>()

const pageLoading = ref(false)
const pageError = ref('')
const pageResult = ref('')
const orders = ref<OrderItem[]>([])
const orderTotal = ref(0)
const notifications = ref<NotificationItem[]>([])
const unreadCount = ref(0)
const clinics = ref<ClinicItem[]>([])
const products = ref<ProductItem[]>([])
const deliveryItems = ref<DeliveryItem[]>([])
const qualityRecords = ref<QualityRecordItem[]>([])
const outsourcingItems = ref<OutsourcingItem[]>([])

const orderKeyword = ref('')
const orderFilter = ref<'ALL' | 'NEW' | 'REGISTERED' | 'QUESTION' | 'EXCEPTION'>('ALL')
const selectedOrder = ref<OrderItem | null>(null)
const orderDrawerVisible = ref(false)
const orderMessages = ref<MessageItem[]>([])
const orderDrafts = ref<DesignDraft[]>([])
const orderFiles = ref<OrderFile[]>([])
const orderBill = ref<BillInfo | null>(null)
const orderLogistics = ref<LogisticsInfo | null>(null)
const orderProcess = ref<ProcessInstanceInfo | null>(null)
const orderDrawerMessageDraft = ref('')
const orderDrawerMessageSending = ref(false)
const orderDrawerMessageError = ref('')
const orderDrawerShowAllMessages = ref(false)
const orderDrawerShowAllFiles = ref(false)
const orderDrawerShowAllDetails = ref(false)
const orderDrawerShowAllHistory = ref(false)
const expandedProductionStageKeys = ref<string[]>([])
const businessGateNote = ref('')
const businessGateLoading = ref(false)
const businessGateError = ref('')
const orderFilePreviewVisible = ref(false)
const orderFilePreviewLoading = ref(false)
const orderFilePreviewUrl = ref('')
const orderFilePreviewName = ref('')
const orderFilePreviewKind = ref<'IMAGE' | 'DOCUMENT'>('DOCUMENT')
const orderFilePreviewError = ref('')
const orderStlViewerVisible = ref(false)

const inquiryOrderId = ref<number | null>(null)
const inquiryMessages = ref<MessageItem[]>([])
const attentionItems = ref<AttentionItem[]>([])
const pendingMessages = ref<MessageItem[]>([])
const inquiryKeyword = ref('')
const inquiryTab = ref<'ALL' | 'WAITING' | 'REVIEW'>('ALL')
const inquiryDraft = ref('')
const inquirySending = ref(false)
const inquiryReviewLoadingId = ref<number | null>(null)
const inquiryReviewNotes = ref<Record<number, string>>({})

const translationOrderId = ref<number | null>(null)
const translationKeyword = ref('')
const translationFilter = ref<'ALL' | 'NOT_STARTED' | 'PENDING' | 'CONFIRMED' | 'REJECTED'>('ALL')
const translationTab = ref<'INFO' | 'TRANSLATION' | 'FILES' | 'HISTORY'>('INFO')
const translationSource = ref('')
const translationDraft = ref('')
const translationFiles = ref<OrderFile[]>([])
const translationRequirements = ref<FormRequirement[]>([])
const translationClinicPreference = ref<ClinicPreference | null>(null)
const productionNoteDraft = ref('')
const missingInfoItems = ref<MissingInfoItem[]>([])
const missingInfoChecked = ref(false)
const aiLoading = ref(false)

const designOrderId = ref<number | null>(null)
const designKeyword = ref('')
const designFilter = ref<'ALL' | 'UPDATED'>('ALL')
const designDrafts = ref<DesignDraft[]>([])
const designPreviewUrls = ref<Record<number, string>>({})
const designDrawerVisible = ref(false)
const designDetailState = ref<DetailLoadState>({ loading: false, error: '' })

const customerKeyword = ref('')
const customerFilter = ref<'ALL' | 'INCOMPLETE' | 'INACTIVE'>('ALL')
const selectedClinicId = ref<number | null>(null)
const selectedClinic = ref<ClinicItem | null>(null)
const clinicPreference = ref<ClinicPreference | null>(null)
const clinicPreferenceDraft = ref<Record<string, unknown>>({})
const clinicPreferenceTextDraft = ref<Record<string, string>>({})
const customerDrawerVisible = ref(false)
const customerDetailState = ref<DetailLoadState>({ loading: false, error: '' })

const productKeyword = ref('')
const selectedProductId = ref<number | null>(null)
const productRequirements = ref<FormRequirement[]>([])
const productEditName = ref('')
const productEditMaterial = ref('')
const productEditPrice = ref(0)
const productEditStatus = ref('ACTIVE')
const productEditNote = ref('')
const productDrawerVisible = ref(false)
const productDetailState = ref<DetailLoadState>({ loading: false, error: '' })

const billingTab = ref<'ORDER' | 'MONTHLY'>('ORDER')
const billingFilter = ref<'ALL' | 'PENDING'>('ALL')
const selectedBillingOrderId = ref<number | null>(null)
const selectedBill = ref<BillInfo | null>(null)
const selectedPayments = ref<PaymentItem[]>([])
const billDocument = ref<File | null>(null)
const billAmountYuan = ref<number | null>(null)
const billCreateLoading = ref(false)
const billCreateError = ref('')
const paymentAmountYuan = ref<number | null>(null)
const paymentMethod = ref('BANK_TRANSFER')
const paymentNote = ref('')
const billingDrawerVisible = ref(false)
const billingDetailState = ref<DetailLoadState>({ loading: false, error: '' })
const billingDetailErrors = ref<string[]>([])

const deliveryStatus = ref('ALL')
const selectedDeliveryOrderId = ref<number | null>(null)
const carrierDraft = ref('')
const trackingDraft = ref('')
const logisticsStatusDraft = ref('EXCEPTION')
const logisticsFollowUpDraft = ref('')
const deliveryDrawerVisible = ref(false)
const shippingDialogVisible = ref(false)

const qualityStatus = ref<'ACTIVE' | 'ALL' | 'PENDING' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'>('ACTIVE')
const selectedQualityRecordId = ref<number | null>(null)
const qualityDrawerVisible = ref(false)
const activeFocusKey = ref('')

const outsourcingStatus = ref('ALL')
const selectedOutsourcingId = ref<number | null>(null)
const outsourcingDrawerVisible = ref(false)
const outsourcingDetailState = ref<DetailLoadState>({ loading: false, error: '' })

const settingsTab = ref<'TEAM' | 'ASSIGNMENT' | 'REPLIES' | 'PREFERENCES'>('TEAM')
const notificationFilter = ref<'ALL' | 'UNREAD' | 'ORDER' | 'MESSAGE' | 'DESIGN' | 'BILLING'>('ALL')
const searchInput = ref(props.searchKeyword)
const helpKeyword = ref('')
const helpTopic = ref<HelpTopic['key']>('START')

const helpTopics: HelpTopic[] = [
  {
    key: 'START', label: '开始使用', title: '客服端统一业务口径',
    intro: '客服登记新订单，但不替代翻译人员的信息审核，也不替代生产端的专业审核。',
    articles: [
      { title: '新订单如何处理？', body: '客户提交后，订单首先显示为“新订单”。客服完成登记后进入信息审核/翻译流程；登记不是审单，也不会修改客户原始下单内容。' },
      { title: '在哪里和客户沟通？', body: '所有订单事项都在“问单沟通”中自由交流。快捷回复只会填入输入框，必须由客服人工确认发送。' }
    ]
  },
  {
    key: 'ORDER', label: '订单与登记', title: '新订单登记与后续跟踪',
    intro: '“新订单”表示客户刚提交且尚未完成客服登记；“已登记”表示订单已进入后续处理链路。',
    articles: [
      { title: '登记会改变客户资料吗？', body: '不会。客服在客户原始资料上补充和核对，原始提交内容必须保留。' },
      { title: '登记后去哪里处理？', body: '需要文字核对的订单进入信息审核/翻译；存在疑点时进入问单沟通；设计文件由设计稿管理继续处理。' }
    ]
  },
  {
    key: 'TRANSLATION', label: '信息审核/翻译', title: '翻译岗位的处理边界',
    intro: '翻译人员核对客户文字、整理翻译稿并人工确认生产信息，普通客服不替代翻译岗位确认。',
    articles: [
      { title: 'AI 草稿可以直接发送吗？', body: '不可以。AI 内容只能作为草稿，必须由翻译人员逐项核对后人工确认。' },
      { title: '发现资料缺失怎么办？', body: '不要自行猜测，使用“发现疑点，创建问单”进入订单会话向客户确认。' }
    ]
  },
  {
    key: 'INQUIRY', label: '问单与设计确认', title: '订单会话与设计确认',
    intro: '问单沟通是自由会话区域，设计确认也是问单的一部分，不再建立独立的设计确认页面。',
    articles: [
      { title: '快捷回复会自动发送吗？', body: '不会。点击快捷回复只会填入输入框，客服检查后还要再次点击“发送消息”。' },
      { title: '设计确认如何完成？', body: '设计稿内部审核通过后，在对应订单会话中发起确认；客户确认或要求修改的结果再回写设计版本。' }
    ]
  },
  {
    key: 'BILLING', label: '账单与配送', title: '账单、收款与配送',
    intro: '按单账单、人工收款和配送记录分别保存；月结自动归集需要真实结算规则和后端能力。',
    articles: [
      { title: '登记收款等于在线支付吗？', body: '不等于。当前只记录真实发生的人工收款事实，不代表支付网关、退款、对账或电子发票已接入。' },
      { title: '什么时候可以发货？', body: '发货提交时由后端核验终检门禁；客服还需填写真实承运商与运单号，不能用演示轨迹代替。' }
    ]
  },
  {
    key: 'PERMISSION', label: '权限与数据范围', title: '账号权限与数据范围',
    intro: '页面只展示当前登录账号有权访问的业务数据；菜单可见不等于后端权限可以省略。',
    articles: [
      { title: '遇到权限不足怎么办？', body: '先确认客户或订单是否分配给本人，再通过组织既有内部渠道联系系统管理员。' },
      { title: '在哪里修改登录安全？', body: '登录账号、密码、启停和解锁由系统管理员在管理端维护，客服端不提供账号切换或安全设置。' }
    ]
  }
]

async function apiFetch<T>(path: string, options: RequestInit = {}) {
  const response = await props.authenticatedFetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {})
    }
  })
  if (!response.ok) {
    let detail = ''
    try {
      const payload = await response.json() as { message?: string; msg?: string }
      detail = payload.message || payload.msg || ''
    } catch {
      // 保留状态码作为可理解的兜底错误。
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  return await response.json() as ApiResponse<T>
}

// 订单详情的可选附属资料允许部分降级；待办/待审消息不得走此入口。
async function safeData<T>(path: string, fallback: T): Promise<T> {
  try {
    return (await apiFetch<T>(path)).data
  } catch {
    return fallback
  }
}

function compactDateTime(value?: string | null) {
  if (!value) return '时间未记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date)
}

function money(amount: number | null | undefined, currency = 'CNY') {
  if (amount == null) return '金额待录入'
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(amount / 100)
}

function statusLabel(status?: string | null) {
  const labels: Record<string, string> = {
    DRAFT: '草稿', SUBMITTED: '已提交', PENDING_CS_REVIEW: '新订单', PENDING_PRODUCTION_REVIEW: '待生产审核',
    PENDING_DOCTOR_CONFIRM: '待客户确认', PROCESS_INSTANCE_CREATED: '已进入生产', PRODUCING: '生产中',
    IN_PRODUCTION: '生产中',
    SHIPPED: '已发货', COMPLETED: '已完成', REJECTED: '已退回', APPROVED: '已通过',
    PENDING: '待处理', PENDING_PAYMENT: '待收款', PARTIALLY_PAID: '部分收款', PAID: '已收款',
    UNPAID: '待收款', UPLOADED: '已上传', SENT: '已发送', DELIVERED: '已签收',
    EXCEPTION: '配送异常', FOLLOWING: '跟进中', FOLLOWING_UP: '跟进中', RESOLVED: '已解决', CLOSED: '已关闭', DELAYED: '已延迟',
    RETURNED: '已返回', CANCELLED: '已取消', ACTIVE: '启用', INACTIVE: '停用', PENDING_CS_REVIEW_DESIGN: '待内部审核',
    PENDING_DOCTOR_REVIEW: '待客户确认', PENDING_DOCTOR: '待客户确认',
    INTERNAL_REJECTED: '内审已退回', DOCTOR_CONFIRMED: '客户已确认', DOCTOR_REJECTED: '客户要求修改',
    READY: '待开工', IN_PROGRESS: '进行中', SKIPPED: '已跳过', DIRECT: '已发送',
    ISSUED: '已出账', PENDING_QUOTE: '待报价', UNKNOWN: '暂未记录', UNDER_REVIEW: '审核中',
    PENDING_REVIEW: '待审核', CS_REJECTED: '客服已退回', PRODUCTION_REJECTED: '生产已退回',
    DESIGN_UPLOADED: '设计稿已上传', QC_PASSED: '质检通过', PENDING_SHIP: '待发货',
    READY_TO_SHIP: '待发货', RECEIVED: '已收货', NO_PAYMENT_REQUIRED: '无需收款',
    NOT_ISSUED: '未出账', PROCESSING: '处理中', FAILED: '处理失败', DISABLED: '已停用',
    PENDING_CONFIRM: '待确认', REVISION_REQUESTED: '要求修改', REVISING: '修改中',
    NOT_REQUIRED: '无需处理', UPLOADING: '上传中', VALID: '有效', INVALID: '无效',
    ARCHIVED: '已归档', OUTSOURCED: '外协中', IN_TRANSIT: '运输中', OVERDUE: '已超期',
    ASSIGNED: '已分配生产', IN_DESIGN: '设计处理中', IN_QC: '质检中',
    DESIGNING: '设计中', QC: '质检中', FAIL: '不通过', PASS: '通过',
    FIT_ISSUE: '适配问题', MATERIAL_ISSUE: '材料问题', DESIGN_ISSUE: '设计问题', OTHER: '其他',
    WORKER: '生产', DOCTOR: '医生', CS: '客服', SYSTEM: '系统'
  }
  if (!status) return '状态未记录'
  return labels[status] || (/[一-鿿]/.test(status) ? status : '状态待确认')
}

function productLabel(type?: string | null) {
  const labels: Record<string, string> = {
    REGULAR_CROWN: '常规牙冠',
    FIXED_CROWN: '常规牙冠',
    IMPLANT: '种植修复',
    IMPLANT_CROWN: '种植牙冠',
    IMPLANT_RESTORATION: '种植修复',
    VENEER: '贴面',
    VENEER_SET: '贴面套装',
    BRIDGE: '桥体',
    FIXED_BRIDGE: '固定桥',
    PFM_BRIDGE: '烤瓷桥',
    DENTURE: '活动义齿',
    REMOVABLE_DENTURE: '活动修复',
    REMOVABLE: '活动修复',
    ORTHODONTIC: '正畸产品',
    ORTHODONTICS: '正畸产品',
    CLEAR_ALIGNER: '隐形矫治',
    NIGHT_GUARD: '夜磨牙垫'
  }
  if (!type) return '产品未记录'
  return labels[type] || (/[一-鿿]/.test(type) ? type : '其他定制产品')
}

function fileSizeLabel(value?: number | null) {
  if (value == null) return '大小未记录'
  if (value < 1024) return `${value} 字节`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} 千字节`
  return `${(value / 1024 / 1024).toFixed(1)} 兆字节`
}

function registrationStatus(order: OrderItem) {
  return order.internal_status === 'PENDING_CS_REVIEW' ? 'NEW' : 'REGISTERED'
}

function informationStatus(order: OrderItem) {
  if (order.internal_status === 'PENDING_CS_REVIEW') return '待客服初审'
  if (order.internal_status === 'CS_REJECTED') return '客服初审已退回'
  if (hasPassedCsReview(order)) return '客服初审已通过'
  return '暂未进入客服初审'
}

function hasPassedCsReview(order: OrderItem) {
  return [
    'PENDING_PRODUCTION_REVIEW', 'PRODUCTION_REJECTED', 'PROCESS_INSTANCE_CREATED',
    'ASSIGNED', 'IN_DESIGN', 'IN_PRODUCTION', 'IN_QC', 'QC_PASSED', 'SHIPPED', 'COMPLETED'
  ].includes(order.internal_status)
}

function translationReviewBucket(order: OrderItem): 'NOT_STARTED' | 'PENDING' | 'CONFIRMED' | 'REJECTED' {
  if (order.internal_status === 'PENDING_CS_REVIEW') return 'PENDING'
  if (order.internal_status === 'CS_REJECTED') return 'REJECTED'
  if (hasPassedCsReview(order)) return 'CONFIRMED'
  return 'NOT_STARTED'
}

function requiresTranslationReview(source: string) {
  const text = source.trim()
  if (!text) return false
  return /[A-Za-z]{2,}|[\u3040-\u30ff\uac00-\ud7af]/u.test(text)
}

function orderMayHaveProcess(order: OrderItem) {
  return [
    'PROCESS_INSTANCE_CREATED', 'PRODUCING', 'IN_PRODUCTION', 'PENDING_SHIP',
    'READY_TO_SHIP', 'SHIPPED', 'COMPLETED'
  ].includes(order.internal_status)
}

function orderFormValue(order: OrderItem | null, keys: string[]) {
  if (!order) return ''
  for (const key of keys) {
    const value = order.form_data?.[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
    if (typeof value === 'number') return String(value)
    if (Array.isArray(value) && value.length) return value.join('、')
  }
  return ''
}

function csOrderIdentity(order: OrderItem) {
  return staffOrderIdentity(order, productLabel(order.product_type), { maskPatient: false })
}

function csOrderMatchesKeyword(order: OrderItem, keyword: string) {
  return csOrderIdentity(order).searchValues.some((value) => value.toLowerCase().includes(keyword))
}

const reviewFieldLabels: Record<string, string> = {
  patient_name: '患者姓名',
  tooth_position: '牙位',
  tooth: '牙位',
  teeth: '牙位',
  material: '材料',
  shade: '色号',
  color: '颜色',
  doctor_note: '医生备注',
  instruction: '客户指示',
  customer_instruction: '客户指示',
  description: '制作说明',
  notes: '补充说明',
  special_requirements: '特殊要求',
  retention_type: '固位方式',
  retention: '固位方式',
  contact: '邻接要求',
  contact_type: '邻接要求',
  occlusion: '咬合要求',
  margin: '边缘要求',
  quantity: '数量',
  item_count: '件数',
  qty: '数量',
  case_count: '病例数量',
  due_date: '期望交期',
  delivery_date: '期望交期'
}

function isInternalReviewField(key: string) {
  return /^(?:_|demo_|acceptance_|test_|debug_|internal_)/i.test(key)
}

function reviewFieldValue(value: unknown): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number') return String(value)
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) {
    return value.map((item) => reviewFieldValue(item)).filter(Boolean).join('、')
  }
  return '已提交结构化资料'
}

function senderLabel(role: string) {
  const labels: Record<string, string> = {
    DOCTOR: '医生/客户', CUSTOMER: '医生/客户', CS: '客服', WORKER: '生产人员',
    FACTORY: '生产人员', ADMIN: '系统管理员', SYSTEM: '系统'
  }
  return labels[role] || '其他协作人员'
}

function fileEmoji(file: Pick<OrderFile, 'content_type' | 'original_filename'>) {
  const hint = `${file.content_type || ''} ${file.original_filename}`.toLowerCase()
  if (/image|png|jpe?g|webp/.test(hint)) return '🖼️'
  if (/pdf/.test(hint)) return '📕'
  if (/zip|rar|7z/.test(hint)) return '🗜️'
  if (/stl|obj|ply|cad/.test(hint)) return '🦷'
  return '📎'
}

function formatFileSize(size?: number | null) {
  if (size == null) return '大小未记录'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function paymentMethodLabel(method?: string | null) {
  const labels: Record<string, string> = { BANK_TRANSFER: '银行转账', CASH: '现金', OTHER: '其他方式', WECHAT: '微信转账', ALIPAY: '支付宝转账' }
  return method ? labels[method] || '其他方式' : '方式未记录'
}

function fieldTypeLabel(type?: string | null) {
  const labels: Record<string, string> = { text: '单行文本', textarea: '多行文本', select: '单选', radio: '单选', checkbox: '多选', number: '数字', date: '日期', file: '文件' }
  return type ? labels[type.toLowerCase()] || '业务字段' : '业务字段'
}

function preferenceLabel(key: string) {
  const labels: Record<string, string> = { color: '颜色偏好', contact: '邻接偏好', occlusion: '咬合偏好', margin: '边缘偏好', shape: '形态偏好', material: '材料偏好', note: '其他制作说明' }
  return labels[key] || '其他偏好'
}

const productionRequirementFields = [
  { key: 'contact', label: '邻接' },
  { key: 'occlusion', label: '咬合' },
  { key: 'color', label: '颜色' },
  { key: 'material', label: '材料' },
  { key: 'margin', label: '边缘' },
  { key: 'shape', label: '形态' },
  { key: 'note', label: '其他要求' }
]

function productionPreferenceText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(productionPreferenceText).filter(Boolean).join('、')
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => {
        const text = productionPreferenceText(item)
        return text ? `${key}：${text}` : ''
      })
      .filter(Boolean)
      .join('；')
  }
  return ''
}

function customerRequirementItems(preferences?: Record<string, unknown>) {
  return productionRequirementFields.flatMap((field) => {
    const value = productionPreferenceText(preferences?.[field.key])
    return value ? [{ ...field, value }] : []
  })
}

function buildAutomaticProductionNote(order: OrderItem, preference: ClinicPreference) {
  const orderLines = [
    { label: '产品', value: productLabel(order.product_type) },
    { label: '牙位', value: orderFormValue(order, ['tooth_position', 'tooth', 'teeth']) },
    { label: '颜色', value: orderFormValue(order, ['shade', 'color']) },
    { label: '材料', value: orderFormValue(order, ['material']) }
  ].filter((item) => item.value)
  const requirements = customerRequirementItems(preference.preferences)
  const instruction = orderFormValue(order, ['instruction', 'customer_instruction', 'description', 'special_requirements', 'notes', 'doctor_note'])
  const sections = [
    ['订单制作信息', ...orderLines.map((item) => `- ${item.label}：${item.value}`)].join('\n'),
    requirements.length
      ? ['客户档案特殊要求（初审时自动带入）', ...requirements.map((item) => `- ${item.label}：${item.value}`)].join('\n')
      : '客户档案特殊要求（初审时自动带入）\n- 当前客户档案未维护特殊要求'
  ]
  if (instruction) sections.push(`本单客户指示\n${instruction}`)
  return sections.join('\n\n')
}

function preferenceText(value: unknown) {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value, null, 2)
}

function parsePreferenceText(value: string): unknown {
  const text = value.trim()
  if (!text) return null
  if (text.startsWith('{') || text.startsWith('[')) return JSON.parse(text)
  return text
}

function detailError(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function fileIds(draft: DesignDraft) {
  return draft.file_ids?.length ? draft.file_ids : draft.file_id ? [draft.file_id] : []
}

function doctorVisibleDrafts(drafts: DesignDraft[]) {
  const visibleStatuses = new Set([
    'PENDING_DOCTOR',
    'PENDING_DOCTOR_CONFIRM',
    'PENDING_DOCTOR_REVIEW',
    'DOCTOR_CONFIRMED',
    'DOCTOR_REJECTED'
  ])
  return drafts.filter((draft) => visibleStatuses.has(draft.status))
}

function fileTypeLabel(file: OrderFile) {
  const extension = file.original_filename.split('.').pop()?.toLowerCase() || ''
  if (extension === 'stl' || extension === 'obj' || extension === 'ply') return '三维模型'
  if (file.content_type?.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(extension)) return '图片'
  if (file.content_type === 'application/pdf' || extension === 'pdf') return '文档'
  if (file.content_type?.startsWith('text/') || ['txt', 'csv'].includes(extension)) return '文本'
  return '订单文件'
}

function fileSourceLabel(source?: string | null) {
  const labels: Record<string, string> = {
    DOCTOR_UPLOAD: '医生上传', CS_UPLOAD: '客服上传', PRODUCTION_UPLOAD: '生产上传',
    WORKER_UPLOAD: '生产上传', SYSTEM_GENERATED: '系统生成', DESIGN_DRAFT: '设计稿',
    ORDER: '订单资料', ORDER_ATTACHMENT: '医生订单附件', BILL: '账单文件'
  }
  return source ? labels[source] || '业务附件' : '来源未记录'
}

function fileVisibilityLabel(visibility?: string | null) {
  const labels: Record<string, string> = {
    DOCTOR: '医生可见', DOCTOR_CS: '医生与客服可见', INTERNAL: '仅内部可见',
    CS: '客服可见', CS_ONLY: '仅客服可见', CS_WORKER: '客服与生产可见',
    PRODUCTION: '生产可见', ALL: '相关人员可见'
  }
  return visibility ? labels[visibility] || '按权限可见' : '可见范围未记录'
}

function messageVisibilityLabel(visibility?: string | null) {
  const labels: Record<string, string> = {
    DOCTOR: '医生可见', DOCTOR_CS: '医生与客服可见', INTERNAL: '仅内部可见',
    CS: '客服可见', CS_ONLY: '仅客服可见', CS_WORKER: '客服与生产可见',
    PRODUCTION: '生产可见', ALL: '相关人员可见'
  }
  return visibility ? labels[visibility] || '按权限可见' : '可见范围未记录'
}

function formValueLabel(value: unknown): string {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'number') return String(value)
  if (Array.isArray(value)) return value.map(formValueLabel).filter(Boolean).join('、')
  if (value == null || typeof value === 'object') return ''
  const text = String(value).trim()
  const labels: Record<string, string> = {
    YES: '是', NO: '否', TRUE: '是', FALSE: '否', HIGH: '高', MEDIUM: '中', LOW: '低',
    URGENT: '加急', NORMAL: '普通', ZIRCONIA: '氧化锆', METAL: '金属', RESIN: '树脂',
    TITANIUM: '钛', UPPER: '上颌', LOWER: '下颌', LEFT: '左侧', RIGHT: '右侧'
  }
  const mapped = labels[text.toUpperCase()]
  if (mapped) return mapped
  return /^[A-Z][A-Z0-9_]*$/.test(text) && text.includes('_') ? '其他' : text
}

const formFieldLabels: Record<string, string> = {
  shade: '颜色', color: '颜色', material: '材料', restoration_type: '修复类型',
  abutment_type: '基台类型', implant_brand: '种植系统', implant_system: '种植系统',
  emergence_profile: '穿龈轮廓', retention_type: '固位方式', occlusion: '咬合要求',
  contact: '邻接要求', margin: '边缘要求', pontic_type: '桥体形式', surface: '表面处理',
  translucency: '透光度', gingiva_color: '牙龈颜色', appliance_type: '矫治器类型',
  arch: '牙弓', stages: '阶段数', due_date: '期望完成时间', priority: '优先级',
  scan_type: '扫描类型', impression_type: '印模类型', cement_gap: '粘接间隙',
  connector_size: '连接体尺寸', framework_material: '支架材料', clasp_type: '卡环类型'
}

const ignoredFormFieldPattern = /(demo|mock|fixture|test|acceptance|marker|debug|internal|scenario|token|secret|password|测试|验收标记|内部调试)/i
const duplicateFormFields = new Set([
  'patient_name', 'patient_id', 'tooth_position', 'tooth', 'teeth', 'instruction',
  'customer_instruction', 'description', 'notes', 'special_requirements', 'doctor_note',
  'shade', 'color'
])

const selectedOrderSpecEntries = computed(() => {
  const order = selectedOrder.value
  if (!order) return []
  return Object.entries(order.form_data || {}).flatMap(([key, rawValue]) => {
    if (duplicateFormFields.has(key) || ignoredFormFieldPattern.test(key)) return []
    const label = formFieldLabels[key] || (/[一-鿿]/.test(key) ? key : '')
    const value = formValueLabel(rawValue)
    return label && value ? [{ key, label, value }] : []
  })
})

const selectedOrderClinicalNotes = computed(() => {
  const order = selectedOrder.value
  if (!order) return []
  const entries = [
    { label: '医生指示', value: orderFormValue(order, ['instruction', 'customer_instruction', 'description']) },
    { label: '特殊要求', value: orderFormValue(order, ['special_requirements', 'notes', 'doctor_note']) },
    { label: '已确认制作要求', value: businessProductionNote(order.production_note) },
    { label: '退回原因', value: order.reject_reason?.trim() || '' }
  ]
  const seen = new Set<string>()
  return entries.filter((item) => {
    if (!item.value || seen.has(item.value)) return false
    seen.add(item.value)
    return true
  })
})

function businessProductionNote(value: string | null | undefined): string {
  const note = value?.trim() || ''
  if (!note) return ''
  if (isLegacyTechnicalProductionNote(note)) return ''
  if (/(?:^|\s)(?:task\s*)?9D\.\d+(?:\.\d+)?|\u56fa\u5b9a\u6f14\u793a\u6570\u636e|\u9a8c\u6536(?:\u6807\u8bb0|\u6570\u636e)|acceptance|fixture|mock/i.test(note)) return ''
  if (/^\u5ba2\u670d\u521d\u5ba1\u901a\u8fc7[\uff0c,]?\s*\u8fdb\u5165\u751f\u4ea7\u5ba1\u6838[\u3002.]?$/.test(note)) return ''
  return note
}

function isLegacyTechnicalProductionNote(note: string): boolean {
  return /AI-5\s*\u751f\u4ea7\u5907\u6ce8(?:\u8349\u7a3f|\uff08\u4eba\u5de5\u786e\u8ba4\uff09)|PHASE_ONE_DEFAULT_V1|\u77e5\u8bc6\u4e0a\u4e0b\u6587\s*[\uff1a:]|\u5ba2\u6237\u6a21\u677f\u672a\u786e\u8ba4|\borders\.(?:order_no|product_type|form_data|production_note|internal_status|external_status)\b/i.test(note)
}

const roleNames: Record<string, string> = {
  CS: '客服',
  ADMIN: '管理员',
  WORKER: '生产人员',
  DOCTOR: '医生'
}

const dataScopeNames: Record<string, string> = {
  ALL: '全部客户范围',
  DEPT: '本部门范围',
  SELF: '仅本人负责范围',
  CLINIC: '本诊所范围'
}

const accountRoleSummary = computed(() => props.user?.roles.map((role) => roleNames[role] || '业务账号').join(' / ') || '角色未返回')
const accountDataScope = computed(() => dataScopeNames[props.user?.dataScope || ''] || '数据范围未返回')
const businessCapabilities = computed(() => {
  const permissions = props.user?.permissions || []
  const groups = [
    { label: '订单与流程协同', match: /^(order|workflow|check):/ },
    { label: '客户资料管理', match: /^clinic:/ },
    { label: '文件与设计协同', match: /^file:/ },
    { label: '沟通与通知', match: /^(message|notification):/ },
    { label: '产品资料管理', match: /^product:/ },
    { label: 'AI 辅助处理', match: /^ai:/ }
  ]
  return groups.filter((group) => permissions.some((permission) => group.match.test(permission))).map((group) => group.label)
})

const toothRows = [
  { key: 'upper', label: '上颌', teeth: [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28] },
  { key: 'lower', label: '下颌', teeth: [48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38] }
]

const selectedToothNumbers = computed(() => {
  const raw = orderFormValue(selectedOrder.value, ['tooth_position', 'tooth', 'teeth']).replace(/[–—~至]/g, '-')
  const selected = new Set((raw.match(/\b(?:1[1-8]|2[1-8]|3[1-8]|4[1-8])\b/g) || []).map(Number))
  for (const match of raw.matchAll(/\b([1-4][1-8])\s*-\s*([1-4][1-8])\b/g)) {
    const start = Number(match[1])
    const end = Number(match[2])
    if (Math.floor(start / 10) !== Math.floor(end / 10)) continue
    for (let tooth = Math.min(start, end); tooth <= Math.max(start, end); tooth += 1) selected.add(tooth)
  }
  return [...selected]
})

const orderDrawerAlert = computed(() => {
  const order = selectedOrder.value
  if (!order) return null
  if (order.reject_reason?.trim()) return { tone: 'danger', title: '订单存在退回事项', text: order.reject_reason.trim() }
  if (order.internal_status === 'PENDING_CS_REVIEW') return { tone: 'warning', title: '新订单待客服登记', text: '请核对客户资料后完成登记，再进入信息审核与生产流程。' }
  const rejectedStatuses = ['REJECTED', 'RETURNED', 'REVISION_REQUESTED', 'CS_REJECTED', 'PRODUCTION_REJECTED']
  if (rejectedStatuses.includes(order.internal_status)) return { tone: 'danger', title: '订单需要重新处理', text: `当前订单阶段：${statusLabel(order.internal_status)}。` }
  const pendingReviewStatuses = [
    'PENDING_PRODUCTION_REVIEW', 'PENDING_DOCTOR_CONFIRM', 'PENDING_REVIEW',
    'PENDING_CONFIRM', 'PENDING_CS_REVIEW_DESIGN'
  ]
  if (pendingReviewStatuses.includes(order.internal_status)) return { tone: 'warning', title: '订单存在待审核事项', text: `当前订单阶段：${statusLabel(order.internal_status)}，请完成对应审核或确认。` }
  if (orderMessages.value.some((message) => ['PENDING', 'PENDING_REVIEW', 'UNDER_REVIEW'].includes(message.review_status))) {
    return { tone: 'warning', title: '订单沟通待处理', text: '当前订单存在待审核或待回复的真实沟通消息。' }
  }
  if (orderDrafts.value.some((draft) => ['PENDING_CS_REVIEW_DESIGN', 'PENDING_DOCTOR_REVIEW', 'PENDING_DOCTOR_CONFIRM'].includes(draft.status))) {
    return { tone: 'warning', title: '设计稿待审核或确认', text: '请在文件与设计区域核对最新设计稿状态。' }
  }
  const supplementStatuses = ['PENDING_INFORMATION', 'INFORMATION_REQUIRED', 'SUPPLEMENT_REQUIRED', 'PENDING_SUPPLEMENT', 'MISSING_INFORMATION', 'NEEDS_INFO']
  if (supplementStatuses.includes(order.internal_status)) return { tone: 'warning', title: '订单资料待补充', text: '请根据订单记录与沟通信息补齐真实资料后继续处理。' }
  return null
})

const orderAuditTimeline = computed(() => {
  const order = selectedOrder.value
  if (!order) return []
  const entries: Array<{ key: string; label: string; detail: string; time: string }> = []
  const push = (key: string, label: string, detail: string, time?: string | null) => {
    if (time) entries.push({ key, label, detail, time })
  }
  push('order-created', '订单创建', `${order.clinic_name}提交${productLabel(order.product_type)}订单`, order.created_at)
  if (order.updated_at && order.updated_at !== order.created_at) push('order-updated', '订单资料更新', statusLabel(order.internal_status), order.updated_at)
  if (orderProcess.value) {
    push('process-created', '生产流程创建', statusLabel(orderProcess.value.instance_status), orderProcess.value.created_at)
    if (orderProcess.value.updated_at !== orderProcess.value.created_at) push('process-updated', '生产流程更新', statusLabel(orderProcess.value.instance_status), orderProcess.value.updated_at)
    orderProcess.value.nodes.forEach((node) => {
      push(`node-${node.node_instance_id}-start`, `${node.process_name}开始`, node.stage_name, node.started_at)
      push(`node-${node.node_instance_id}-complete`, `${node.process_name}完成`, node.stage_name, node.completed_at)
    })
  }
  orderMessages.value.forEach((message) => push(`message-${message.msg_id}`, '沟通消息', `${senderLabel(message.sender_role)}发送消息`, message.created_at))
  return entries.sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime())
})

const orderedDrawerMessages = computed(() => [...orderMessages.value].sort((a, b) => {
  const left = a.created_at ? new Date(a.created_at).getTime() : 0
  const right = b.created_at ? new Date(b.created_at).getTime() : 0
  return left - right
}))

const sortedOrderProcessNodes = computed(() => [...(orderProcess.value?.nodes || [])].sort((a, b) => a.step_order - b.step_order))

const CS_BUSINESS_GATE_LABELS = new Map([
  ['客服定基台', '确认基台信息已核对，可进入后续种植制作'],
  ['客服核对订单信息及账单', '确认订单资料与账单已核对，可进入最终发货']
])

const actionableBusinessGate = computed(() => sortedOrderProcessNodes.value.find((node) =>
  node.node_status === 'READY'
  && CS_BUSINESS_GATE_LABELS.has(node.process_name)) || null)
const billingBusinessGateBlocked = computed(() =>
  actionableBusinessGate.value?.process_name === '客服核对订单信息及账单'
  && !orderBill.value?.bill_id)

type ProcessNodeVisual = {
  icon: string
  label: string
}

const PROCESS_NODE_VISUAL_GROUPS: ReadonlyArray<ProcessNodeVisual & { processNames: readonly string[] }> = [
  {
    icon: '📥',
    label: '接收节点',
    processNames: ['客户、客服、销售下单', '收发入货', '印模', '模型接收']
  },
  {
    icon: '🔎',
    label: '信息与入货审核',
    processNames: [
      '国外件信息检验、翻译，国内件信息检验', '入厂检验', 'CAD入货检', '种植入货检',
      '模型入货检验', '胶托入货检验', '上瓷入货检验', '车瓷入货检验', '车金入货检验',
      '钢托入货检验'
    ]
  },
  {
    icon: '💾',
    label: '数据审核',
    processNames: ['入厂检验、数据技术检验', '数据审核', '口扫', 'CAD扫描', '扫描']
  },
  {
    icon: '✏️',
    label: '设计节点',
    processNames: [
      'CAD设计', 'CAD内冠设计', 'CAD设计外冠', '种植上部冠设计', '钢托画线设计',
      '活动钢托设计', '设计钢托', '钢托设计'
    ]
  },
  {
    icon: '🔍',
    label: '设计与形态确认',
    processNames: ['CAD确认设计', '车瓷形态确认', '客服定基台']
  },
  {
    icon: '⚙️',
    label: '制作加工',
    processNames: [
      'CAD传统切蜡', 'CAD切削', 'CAD切削基台', 'CAD包埋', 'CAD打印内冠', 'CAD打印外冠',
      'CAD打印金属', 'CAD烧结', 'CAD铸造', '个性化基台', '成品基台', '充胶', '刻蜡', '复模',
      '打印模型', '打印钢托', '打磨', '打磨就位', '就位', '排牙', '烧结', '研磨（订配件）',
      '种植研磨基台', '种植配基台', '落盒充胶', '车瓷', '车金固+活焊接', '车金就位',
      '车金焊接/安装配件', '车金研磨/就位冠', '选牙排牙', '钢托打印', '钢托打磨/就位',
      '钢托打磨就位'
    ]
  },
  {
    icon: '🎨',
    label: '上色与表面处理',
    processNames: [
      'CAD排版/切削/染色', '上光固化', '上瓷', '上瓷烧结', '上瓷（上op）', '上釉', '抛光'
    ]
  },
  {
    icon: '✅',
    label: '质量检查',
    processNames: [
      'CAD出货检', 'CAD检验出货', '上瓷出货检验', '收发出货检验', '模型检验出货',
      '胶托打磨出货检验', '胶托打磨出货检验质检出货', '质检出货', '车瓷出货检验',
      '车金出货检', '车金出货检验', '钢托出货检验'
    ]
  },
  {
    icon: '💳',
    label: '账单核对',
    processNames: ['客服核对订单信息及账单']
  },
  {
    icon: '🚀',
    label: '出货节点',
    processNames: ['收发出货', '等待出货', '发货']
  }
]

const PROCESS_NODE_VISUALS = new Map<string, ProcessNodeVisual>(
  PROCESS_NODE_VISUAL_GROUPS.flatMap(({ icon, label, processNames }) =>
    processNames.map((processName) => [processName, { icon, label }]))
)

const DEFAULT_PROCESS_NODE_VISUAL: ProcessNodeVisual = { icon: '⚙️', label: '制作工序' }

function processNodeVisual(node: ProcessNodeItem) {
  return PROCESS_NODE_VISUALS.get(node.process_name.trim()) || DEFAULT_PROCESS_NODE_VISUAL
}

type MainProductionStageKey =
  | 'ORDER_RECEIVED'
  | 'INFORMATION_REVIEW'
  | 'FACTORY_INTAKE'
  | 'IMPLANT_PRODUCTION'
  | 'CAD_PRODUCTION'
  | 'MATERIAL_PRODUCTION'
  | 'FINISH_AND_QC'
  | 'BILLING_AND_SHIPPING'

type MainProductionStageDefinition = {
  key: MainProductionStageKey
  label: string
  icon: string
  description: string
}

type MainProductionStage = MainProductionStageDefinition & {
  nodes: ProcessNodeItem[]
  status: string
  resolvedCount: number
  currentNode: ProcessNodeItem | null
}

const MAIN_PRODUCTION_STAGES: readonly MainProductionStageDefinition[] = [
  { key: 'ORDER_RECEIVED', label: '订单接收', icon: '📥', description: '订单已提交并进入工厂业务流程' },
  { key: 'INFORMATION_REVIEW', label: '信息与数据审核', icon: '💾', description: '核对订单资料、印模、口扫和生产数据' },
  { key: 'FACTORY_INTAKE', label: '入厂收货', icon: '📥', description: '收发人员完成实物接收与内部流转' },
  { key: 'IMPLANT_PRODUCTION', label: '种植部件制作', icon: '⚙️', description: '完成种植基台及相关部件制作' },
  { key: 'CAD_PRODUCTION', label: 'CAD设计与切削', icon: '✏️', description: '完成数字设计、切削、打印与设计检验' },
  { key: 'MATERIAL_PRODUCTION', label: '金属与瓷层加工', icon: '⚙️', description: '完成车金、上瓷、车瓷及材料成型加工' },
  { key: 'FINISH_AND_QC', label: '上釉、抛光与质检', icon: '✅', description: '完成表面处理和最终质量检查' },
  { key: 'BILLING_AND_SHIPPING', label: '账单核对与发货', icon: '🚀', description: '完成账单核对、待发货与最终发出' }
]

const INFORMATION_STAGE_NAMES = new Set(['下单入厂', '取模分支', '模型'])
const IMPLANT_STAGE_NAMES = new Set(['种植', '基台分支'])
const CAD_STAGE_NAMES = new Set(['CAD', '正畸'])
const CAD_ROUTE_STAGE_NAMES = new Set(['内冠', '外冠', '贴面路线'])
const BILLING_AND_SHIPPING_PROCESS_NAMES = new Set(['等待出货', '客服核对订单信息及账单', '发货'])
const RESOLVED_PROCESS_NODE_STATUSES = new Set(['COMPLETED', 'SKIPPED'])
const ACTIVE_PROCESS_NODE_STATUSES = new Set(['IN_PROGRESS', 'PROCESSING', 'RUNNING'])

function mainProductionStageKey(node: ProcessNodeItem): MainProductionStageKey {
  if (node.process_name === '客户、客服、销售下单') return 'ORDER_RECEIVED'
  if (INFORMATION_STAGE_NAMES.has(node.stage_name)) return 'INFORMATION_REVIEW'
  if (node.stage_name === '收发') return 'FACTORY_INTAKE'
  if (IMPLANT_STAGE_NAMES.has(node.stage_name)) return 'IMPLANT_PRODUCTION'
  if (CAD_STAGE_NAMES.has(node.stage_name)) return 'CAD_PRODUCTION'
  if (CAD_ROUTE_STAGE_NAMES.has(node.stage_name) && node.process_name.startsWith('CAD')) return 'CAD_PRODUCTION'
  if (node.stage_name === '收尾' && BILLING_AND_SHIPPING_PROCESS_NAMES.has(node.process_name)) return 'BILLING_AND_SHIPPING'
  if (node.stage_name === '收尾') return 'FINISH_AND_QC'
  return 'MATERIAL_PRODUCTION'
}

function mainProductionStageStatus(nodes: ProcessNodeItem[]) {
  if (nodes.every((node) => node.node_status === 'SKIPPED')) return 'SKIPPED'
  if (nodes.every((node) => RESOLVED_PROCESS_NODE_STATUSES.has(node.node_status))) return 'COMPLETED'
  if (nodes.some((node) => ACTIVE_PROCESS_NODE_STATUSES.has(node.node_status) || node.node_status === 'COMPLETED')) return 'IN_PROGRESS'
  if (nodes.some((node) => node.node_status === 'READY')) return 'READY'
  return 'PENDING'
}

const mainProductionStages = computed<MainProductionStage[]>(() => MAIN_PRODUCTION_STAGES.flatMap((definition) => {
  const nodes = productionProgressNodes(sortedOrderProcessNodes.value).filter((node) => mainProductionStageKey(node) === definition.key)
  if (!nodes.length) return []
  const status = mainProductionStageStatus(nodes)
  const currentNode = nodes.find((node) => ACTIVE_PROCESS_NODE_STATUSES.has(node.node_status))
    || nodes.find((node) => node.node_status === 'READY')
    || nodes.find((node) => !RESOLVED_PROCESS_NODE_STATUSES.has(node.node_status))
    || nodes.at(-1)
    || null
  return [{
    ...definition,
    nodes,
    status,
    resolvedCount: nodes.filter((node) => RESOLVED_PROCESS_NODE_STATUSES.has(node.node_status)).length,
    currentNode
  }]
}))

const mainProductionCompletedCount = computed(() => mainProductionStages.value.filter((stage) => stage.status === 'COMPLETED').length)

function productionStageExpanded(stageKey: MainProductionStageKey) {
  return expandedProductionStageKeys.value.includes(stageKey)
}

function toggleProductionStage(stageKey: MainProductionStageKey) {
  expandedProductionStageKeys.value = productionStageExpanded(stageKey)
    ? expandedProductionStageKeys.value.filter((key) => key !== stageKey)
    : [...expandedProductionStageKeys.value, stageKey]
}

const displayedOrderMessages = computed(() => orderDrawerShowAllMessages.value
  ? orderedDrawerMessages.value
  : orderedDrawerMessages.value.slice(-5))

const sortedOrderFiles = computed(() => [...orderFiles.value].sort((a, b) =>
  new Date(b.created_at).getTime() - new Date(a.created_at).getTime()))

const displayedOrderFiles = computed(() => orderDrawerShowAllFiles.value
  ? sortedOrderFiles.value
  : sortedOrderFiles.value.slice(0, 3))

const sortedOrderDrafts = computed(() => [...orderDrafts.value].sort((a, b) => b.version - a.version))
const displayedOrderDrafts = computed(() => sortedOrderDrafts.value.slice(0, 2))

const displayedOrderSpecEntries = computed(() => orderDrawerShowAllDetails.value
  ? selectedOrderSpecEntries.value
  : selectedOrderSpecEntries.value.slice(0, 6))

const displayedOrderAuditTimeline = computed(() => orderDrawerShowAllHistory.value
  ? orderAuditTimeline.value
  : orderAuditTimeline.value.slice(0, 5))

function clearLoadedOrderState() {
  orders.value = []
  orderTotal.value = 0
  selectedOrder.value = null
  orderDrawerVisible.value = false
  orderMessages.value = []
  orderDrafts.value = []
  orderFiles.value = []
  orderBill.value = null
  orderLogistics.value = null
  orderProcess.value = null
  inquiryOrderId.value = null
  inquiryMessages.value = []
  translationOrderId.value = null
  translationSource.value = ''
  translationDraft.value = ''
  translationFiles.value = []
  translationRequirements.value = []
  translationClinicPreference.value = null
  designOrderId.value = null
  designDrafts.value = []
  designPreviewUrls.value = {}
  designDrawerVisible.value = false
  selectedBillingOrderId.value = null
  selectedBill.value = null
  selectedPayments.value = []
  billingDrawerVisible.value = false
}

async function loadOrders() {
  clearLoadedOrderState()
  const pageSize = 100
  const first = await apiFetch<Paged<OrderItem>>(`/orders?page=1&size=${pageSize}`)
  const pageCount = Math.ceil(first.data.total / pageSize)
  const items = [...first.data.items]
  for (let page = 2; page <= pageCount; page += 1) {
    const payload = await apiFetch<Paged<OrderItem>>(`/orders?page=${page}&size=${pageSize}`)
    items.push(...payload.data.items)
  }
  orders.value = items
  orderTotal.value = first.data.total
}

async function loadNotifications() {
  const [list, count] = await Promise.all([
    apiFetch<NotificationItem[]>('/notifications?limit=100'),
    apiFetch<{ unread_count: number }>('/notifications/unread-count')
  ])
  notifications.value = list.data
  unreadCount.value = count.data.unread_count
  emit('refreshNotifications')
}

async function loadClinics() {
  const payload = await apiFetch<Paged<ClinicItem>>('/clinics?page=1&size=100')
  clinics.value = payload.data.items
}

async function loadProducts() {
  const payload = await apiFetch<Paged<ProductItem>>('/products?page=1&size=100')
  products.value = payload.data.items
}

async function loadDelivery() {
  deliveryItems.value = (await apiFetch<DeliveryItem[]>('/logistics/orders?limit=100')).data
}

async function loadQualityRecords() {
  const payload = await apiFetch<Paged<QualityRecordItem>>('/quality-records?record_type=EXTERNAL_RETURN&page=1&size=100')
  qualityRecords.value = payload.data.items
}

async function loadOutsourcing() {
  outsourcingItems.value = (await apiFetch<OutsourcingItem[]>('/production/outsourcing')).data
}

async function loadInquiryBase() {
  attentionItems.value = []
  pendingMessages.value = []
  await loadOrders()
  const [attention, pending] = await Promise.all([
    apiFetch<AttentionItem[]>('/messages/attention-items'),
    apiFetch<MessageItem[]>('/messages/pending-review')
  ])
  attentionItems.value = attention.data
  pendingMessages.value = pending.data
  if (!inquiryOrderId.value && orders.value.length) inquiryOrderId.value = orders.value[0].order_id
  if (inquiryOrderId.value) await loadInquiryMessages(inquiryOrderId.value)
}

async function loadOrderAttention() {
  attentionItems.value = []
  attentionItems.value = (await apiFetch<AttentionItem[]>('/messages/attention-items')).data
}

async function loadInquiryMessages(orderId: number) {
  inquiryOrderId.value = orderId
  inquiryMessages.value = (await apiFetch<MessageItem[]>(`/orders/${orderId}/messages`)).data
}

function resetOrderDrawerLayout() {
  orderDrawerShowAllMessages.value = false
  orderDrawerShowAllFiles.value = false
  orderDrawerShowAllDetails.value = false
  orderDrawerShowAllHistory.value = false
  expandedProductionStageKeys.value = []
}

async function sendInquiryMessage() {
  if (!inquiryOrderId.value || !inquiryDraft.value.trim()) return
  inquirySending.value = true
  pageError.value = ''
  try {
    await apiFetch<MessageItem>(`/orders/${inquiryOrderId.value}/messages`, {
      method: 'POST', body: JSON.stringify({ content: inquiryDraft.value.trim(), mention_user_ids: [] })
    })
    inquiryDraft.value = ''
    await loadInquiryMessages(inquiryOrderId.value)
    pageResult.value = '消息已发送并保存到订单会话。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '消息发送失败'
  } finally {
    inquirySending.value = false
  }
}

async function reviewInquiryMessage(message: MessageItem, action: 'APPROVE' | 'REJECT') {
  const reviewNote = (inquiryReviewNotes.value[message.msg_id] || '').trim()
  if (action === 'REJECT' && !reviewNote) {
    pageError.value = '退回修改时请填写需要调整的内容。'
    return
  }
  inquiryReviewLoadingId.value = message.msg_id
  pageError.value = ''
  pageResult.value = ''
  try {
    await apiFetch<MessageItem>(`/messages/${message.msg_id}/review`, {
      method: 'POST',
      body: JSON.stringify({ action, review_note: reviewNote || null })
    })
    delete inquiryReviewNotes.value[message.msg_id]
    pendingMessages.value = (await apiFetch<MessageItem[]>('/messages/pending-review')).data
    const selectedOrderId = inquiryOrderId.value
    const selectedOrderStillVisible = selectedOrderId !== null
      && conversationOrders.value.some((order) => order.order_id === selectedOrderId)
    if (selectedOrderId !== null && selectedOrderStillVisible) {
      await loadInquiryMessages(selectedOrderId)
    } else if (inquiryTab.value === 'REVIEW') {
      inquiryOrderId.value = null
      inquiryMessages.value = []
    }
    pageResult.value = action === 'APPROVE' ? '消息已审核通过并按可见范围发送。' : '消息已退回生产人员修改。'
    emit('refreshNotifications')
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '消息审核失败'
  } finally {
    inquiryReviewLoadingId.value = null
  }
}

async function openOrder(order: OrderItem) {
  resetOrderDrawerLayout()
  if (selectedOrder.value?.order_id !== order.order_id) {
    orderDrawerMessageDraft.value = ''
    orderDrawerMessageError.value = ''
    businessGateNote.value = ''
    businessGateError.value = ''
  }
  resetOrderPreview()
  selectedOrder.value = order
  orderDrawerVisible.value = true
  orderMessages.value = []
  orderDrafts.value = []
  orderFiles.value = []
  orderBill.value = null
  orderLogistics.value = null
  orderProcess.value = null
  const [messageResult, drafts, files, bill, logistics, process] = await Promise.all([
    apiFetch<MessageItem[]>(`/orders/${order.order_id}/messages`)
      .then((payload) => ({ messages: payload.data, error: '' }))
      .catch((error) => ({ messages: [] as MessageItem[], error: detailError(error, '订单消息加载失败') })),
    safeData<DesignDraft[]>(`/orders/${order.order_id}/design-drafts`, []),
    safeData<OrderFile[]>(`/orders/${order.order_id}/files`, []),
    safeData<BillInfo | null>(`/orders/${order.order_id}/bill`, null),
    safeData<LogisticsInfo | null>(`/orders/${order.order_id}/logistics`, null),
    orderMayHaveProcess(order)
      ? safeData<ProcessInstanceInfo | null>(`/orders/${order.order_id}/process-instance`, null)
      : Promise.resolve(null)
  ])
  orderMessages.value = messageResult.messages
  orderDrawerMessageError.value = messageResult.error
  orderDrafts.value = doctorVisibleDrafts(drafts)
  orderFiles.value = files
  orderBill.value = bill
  orderLogistics.value = logistics
  orderProcess.value = process
}

async function completeBusinessGate() {
  const order = selectedOrder.value
  const gate = actionableBusinessGate.value
  const note = businessGateNote.value.trim()
  if (!order || !gate || businessGateLoading.value) return
  if (billingBusinessGateBlocked.value) {
    businessGateError.value = '请先在账单管理上传 PDF 并建立该订单的真实账单。'
    return
  }
  if (!note) {
    businessGateError.value = '请填写本次核对结论，便于后续审计追溯。'
    return
  }
  businessGateLoading.value = true
  businessGateError.value = ''
  try {
    await apiFetch(`/orders/${order.order_id}/process-instance/nodes/${gate.node_instance_id}/complete-business-gate`, {
      method: 'POST',
      body: JSON.stringify({ note })
    })
    orderProcess.value = (await apiFetch<ProcessInstanceInfo>(
      `/orders/${order.order_id}/process-instance`
    )).data
    businessGateNote.value = ''
    pageResult.value = `${gate.process_name}已完成，后续节点已按流程门禁重新计算。`
  } catch (error) {
    businessGateError.value = error instanceof Error ? error.message : '客服业务门禁处理失败，请刷新后重试。'
  } finally {
    businessGateLoading.value = false
  }
}

async function sendOrderDrawerMessage() {
  const order = selectedOrder.value
  const content = orderDrawerMessageDraft.value.trim()
  if (!order || !content || orderDrawerMessageSending.value) return
  orderDrawerMessageSending.value = true
  orderDrawerMessageError.value = ''
  try {
    await apiFetch<MessageItem>(`/orders/${order.order_id}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content, visible_to: 'DOCTOR_CS', mention_user_ids: [] })
    })
    orderMessages.value = (await apiFetch<MessageItem[]>(`/orders/${order.order_id}/messages`)).data
    if (inquiryOrderId.value === order.order_id) inquiryMessages.value = [...orderMessages.value]
    orderDrawerMessageDraft.value = ''
    pageResult.value = '消息已发送并保存到订单沟通记录。'
  } catch (error) {
    orderDrawerMessageError.value = error instanceof Error ? error.message : '消息发送失败，请稍后重试。'
  } finally {
    orderDrawerMessageSending.value = false
  }
}

function resetOrderPreview() {
  orderFilePreviewVisible.value = false
  orderFilePreviewLoading.value = false
  orderFilePreviewUrl.value = ''
  orderFilePreviewName.value = ''
  orderFilePreviewKind.value = 'DOCUMENT'
  orderFilePreviewError.value = ''
  orderStlViewerVisible.value = false
}

async function previewOrderFile(file: OrderFile) {
  orderFilePreviewLoading.value = true
  orderFilePreviewError.value = ''
  try {
    const payload = await apiFetch<PreviewResponse>(`/files/${file.file_id}/preview-url`)
    orderFilePreviewUrl.value = payload.data.preview_url
    orderFilePreviewName.value = file.original_filename
    const extension = file.original_filename.split('.').pop()?.toLowerCase() || ''
    if (extension === 'stl') {
      orderStlViewerVisible.value = true
      return
    }
    orderFilePreviewKind.value = file.content_type?.startsWith('image/') || ['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(extension)
      ? 'IMAGE'
      : 'DOCUMENT'
    orderFilePreviewVisible.value = true
  } catch (error) {
    orderFilePreviewError.value = error instanceof Error ? error.message : '文件预览失败，请稍后重试。'
  } finally {
    orderFilePreviewLoading.value = false
  }
}

async function navigateFromOrderDrawer(route: '/cs/inquiries' | '/cs/information-translation' | '/cs/designs' | '/cs/billing' | '/cs/delivery') {
  const order = selectedOrder.value
  if (!order) return
  orderDrawerVisible.value = false
  if (route === '/cs/inquiries') {
    inquiryOrderId.value = order.order_id
    inquiryMessages.value = [...orderMessages.value]
  } else if (route === '/cs/information-translation') {
    await selectTranslationOrder(order)
  } else if (route === '/cs/designs') {
    designOrderId.value = order.order_id
  } else if (route === '/cs/billing') {
    selectedBillingOrderId.value = order.order_id
  } else if (route === '/cs/delivery') {
    selectedDeliveryOrderId.value = order.order_id
  }
  emit('navigate', route, order.order_id)
}

function openInquiryForOrder(orderId: number) {
  inquiryOrderId.value = orderId
  emit('navigate', '/cs/inquiries', orderId)
}

async function selectTranslationOrder(order: OrderItem) {
  translationOrderId.value = order.order_id
  translationTab.value = 'INFO'
  const customerText = orderFormValue(order, ['instruction', 'customer_instruction', 'description', 'notes', 'special_requirements', 'doctor_note'])
  translationSource.value = customerText
  productionNoteDraft.value = businessProductionNote(order.production_note)
  translationDraft.value = ''
  translationFiles.value = []
  translationRequirements.value = []
  translationClinicPreference.value = null
  const frozenRequirements = frozenFormRequirements(order)
  const [files, requirements, preference] = await Promise.all([
    safeData<OrderFile[]>(`/orders/${order.order_id}/files`, []),
    frozenRequirements == null
      ? safeData<FormRequirement[]>(`/form-configs?product_type=${encodeURIComponent(order.product_type)}`, [])
      : Promise.resolve(frozenRequirements),
    safeData<ClinicPreference | null>(`/clinics/${order.clinic_id}/preference`, null)
  ])
  if (translationOrderId.value !== order.order_id) return
  translationFiles.value = files
  translationRequirements.value = requirements.filter((item) => item.status === 'ACTIVE')
  translationClinicPreference.value = preference
  if (!productionNoteDraft.value && preference && order.internal_status === 'PENDING_CS_REVIEW') {
    productionNoteDraft.value = buildAutomaticProductionNote(order, preference)
  }
  missingInfoItems.value = []
  missingInfoChecked.value = false
}

function frozenFormRequirements(order: OrderItem): FormRequirement[] | null {
  if (!Array.isArray(order.form_schema_snapshot)) return null
  let fieldId = -1
  return order.form_schema_snapshot.flatMap((rule) => {
    if (!rule || typeof rule !== 'object') return []
    const entry = rule as Record<string, unknown>
    if (entry.rule_type !== 'FORM_SCHEMA') return []
    const schema = entry.schema
    if (!schema || typeof schema !== 'object') return []
    const fields = (schema as Record<string, unknown>).fields
    if (!Array.isArray(fields)) return []
    return fields.flatMap((field) => {
      if (!field || typeof field !== 'object') return []
      const definition = field as Record<string, unknown>
      const key = String(definition.key ?? '').trim()
      if (!key) return []
      return [{
        field_id: fieldId--,
        product_type: order.product_type,
        field_key: key,
        field_label: String(definition.label ?? key),
        field_type: String(definition.type ?? 'text'),
        is_required: Boolean(definition.required),
        options: Array.isArray(definition.options)
          ? definition.options.map((option) => typeof option === 'object' && option
            ? String((option as Record<string, unknown>).value ?? '')
            : String(option)).filter(Boolean)
          : [],
        sort_order: Number(definition.sort_order ?? Math.abs(fieldId)),
        status: 'ACTIVE'
      }]
    })
  })
}

async function checkMissingInfo() {
  if (!translationOrderId.value) return false
  aiLoading.value = true
  pageError.value = ''
  try {
    const payload = await apiFetch<MissingInfoResponse>('/ai/check-missing', {
      method: 'POST', body: JSON.stringify({ order_id: translationOrderId.value })
    })
    missingInfoItems.value = payload.data.missing_items
    missingInfoChecked.value = true
    return payload.data.is_complete
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '资料检查失败'
    return false
  } finally {
    aiLoading.value = false
  }
}

async function generateTranslation() {
  if (!translationOrderId.value || !translationSource.value.trim()) return
  aiLoading.value = true
  pageError.value = ''
  try {
    const payload = await apiFetch<TranslateResponse>('/ai/translate', {
      method: 'POST', body: JSON.stringify({ order_id: translationOrderId.value, source_text: translationSource.value.trim() })
    })
    translationDraft.value = payload.data.translated_text
    pageResult.value = 'AI 翻译草稿已生成，请由翻译人员人工核对。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '翻译草稿生成失败'
  } finally {
    aiLoading.value = false
  }
}

async function generateProductionNote() {
  if (!translationOrderId.value) return
  aiLoading.value = true
  pageError.value = ''
  try {
    const payload = await apiFetch<ProductionNoteResponse>('/ai/production-note', {
      method: 'POST', body: JSON.stringify({ order_id: translationOrderId.value })
    })
    productionNoteDraft.value = payload.data.draft_note
    pageResult.value = '生产信息草稿已生成，保存前需要人工确认。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '生产信息草稿生成失败'
  } finally {
    aiLoading.value = false
  }
}

async function confirmProductionNote() {
  const orderId = translationOrderId.value
  const order = selectedTranslationOrder.value
  if (!orderId || !order || !productionNoteDraft.value.trim()) return
  if (order.internal_status !== 'PENDING_CS_REVIEW') {
    pageError.value = '该订单已不在待客服初审状态，请刷新队列后重试。'
    return
  }

  const isComplete = await checkMissingInfo()
  if (!isComplete) {
    if (!pageError.value) {
      pageError.value = missingInfoItems.value.length
        ? '订单资料仍有缺失，不能通过客服初审；请先通过问单沟通补齐资料。'
        : '资料完整性检查未通过，请稍后重试。'
    }
    translationTab.value = 'INFO'
    return
  }

  const existingConfirmedNote = businessProductionNote(order.production_note)
  const translationAlreadyConfirmed = /客服确认译文：/.test(existingConfirmedNote)
  if (requiresTranslationReview(translationSource.value) && !translationDraft.value.trim() && !translationAlreadyConfirmed) {
    pageError.value = '检测到外文客户指示，请先生成或填写翻译稿并人工核对。'
    return
  }

  aiLoading.value = true
  pageError.value = ''
  try {
    const translatedText = translationDraft.value.trim()
    const translatedBlock = translatedText ? `客服确认译文：${translatedText}` : ''
    const reviewedDraft = requiresTranslationReview(translationSource.value) && translatedBlock
      && !productionNoteDraft.value.includes(translatedBlock)
      ? `${productionNoteDraft.value.trim()}\n\n客服确认译文：${translatedText}`
      : productionNoteDraft.value.trim()
    const confirmedProductionNote = reviewedDraft

    const reviewedOrder = await apiFetch<OrderItem>(`/orders/${orderId}/review`, {
      method: 'POST',
      body: JSON.stringify({
        action: 'APPROVE',
        production_note: confirmedProductionNote,
        reject_reason: null
      })
    })
    orders.value = orders.value.map((item) => item.order_id === orderId ? reviewedOrder.data : item)
    productionNoteDraft.value = businessProductionNote(reviewedOrder.data.production_note)
    translationTab.value = 'HISTORY'
    pageResult.value = '客服初审已通过，订单已进入生产审核。'
    emit('refreshNotifications')
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '客服初审处理失败'
  } finally {
    aiLoading.value = false
  }
}

async function selectDesignOrder(orderId: number) {
  designOrderId.value = orderId
  designDrawerVisible.value = true
  designPreviewUrls.value = {}
  designDetailState.value = { loading: true, error: '' }
  try {
    designDrafts.value = doctorVisibleDrafts((await apiFetch<DesignDraft[]>(`/orders/${orderId}/design-drafts`)).data)
  } catch (error) {
    designDrafts.value = []
    designDetailState.value.error = detailError(error, '设计稿版本加载失败')
  } finally {
    designDetailState.value.loading = false
  }
}

async function previewDesignDraft(draft: DesignDraft) {
  const id = fileIds(draft)[0]
  if (!id) return
  pageError.value = ''
  try {
    const payload = await apiFetch<PreviewResponse>(`/files/${id}/preview-url`)
    designPreviewUrls.value[draft.draft_id] = payload.data.preview_url
    window.open(payload.data.preview_url, '_blank', 'noopener,noreferrer')
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '设计稿预览失败'
  }
}

async function selectClinic(clinicId: number) {
  selectedClinicId.value = clinicId
  customerDrawerVisible.value = true
  customerDetailState.value = { loading: true, error: '' }
  try {
    const [clinic, preference] = await Promise.all([
      apiFetch<ClinicItem>(`/clinics/${clinicId}`),
      apiFetch<ClinicPreference>(`/clinics/${clinicId}/preference`)
    ])
    selectedClinic.value = clinic.data
    clinicPreference.value = preference.data
    clinicPreferenceDraft.value = { ...preference.data.preferences }
    clinicPreferenceTextDraft.value = Object.fromEntries(Object.entries(preference.data.preferences).map(([key, value]) => [key, preferenceText(value)]))
  } catch (error) {
    selectedClinic.value = clinics.value.find((item) => item.clinic_id === clinicId) || null
    clinicPreference.value = null
    clinicPreferenceDraft.value = {}
    clinicPreferenceTextDraft.value = {}
    customerDetailState.value.error = detailError(error, '客户详情加载失败')
  } finally {
    customerDetailState.value.loading = false
  }
}

async function saveClinicPreference() {
  if (!selectedClinicId.value) return
  pageLoading.value = true
  pageError.value = ''
  try {
    clinicPreferenceDraft.value = Object.fromEntries(
      clinicPreferenceKeys.value.map((key) => [key, parsePreferenceText(clinicPreferenceTextDraft.value[key] || '')])
    )
    const payload = await apiFetch<ClinicPreference>(`/clinics/${selectedClinicId.value}/preference`, {
      method: 'PUT', body: JSON.stringify(clinicPreferenceDraft.value)
    })
    clinicPreference.value = payload.data
    clinicPreferenceDraft.value = { ...payload.data.preferences }
    clinicPreferenceTextDraft.value = Object.fromEntries(Object.entries(payload.data.preferences).map(([key, value]) => [key, preferenceText(value)]))
    pageResult.value = '客户制作偏好已保存，不会反向修改历史订单。'
  } catch (error) {
    pageError.value = error instanceof SyntaxError ? '偏好中的结构化内容不是有效 JSON，请检查括号和引号。' : detailError(error, '客户偏好保存失败')
  } finally {
    pageLoading.value = false
  }
}

async function selectProduct(productId: number) {
  selectedProductId.value = productId
  productDrawerVisible.value = true
  productDetailState.value = { loading: true, error: '' }
  const product = products.value.find((item) => item.product_id === productId)
  if (product) {
    productEditName.value = product.product_name
    productEditMaterial.value = product.material_spec || ''
    productEditPrice.value = product.base_price_cents / 100
    productEditStatus.value = product.status
    productEditNote.value = product.price_note || ''
    try {
      productRequirements.value = (await apiFetch<FormRequirement[]>(`/form-configs?product_type=${encodeURIComponent(product.product_type)}`)).data
    } catch (error) {
      productRequirements.value = []
      productDetailState.value.error = detailError(error, '医生下单要求加载失败')
    } finally {
      productDetailState.value.loading = false
    }
  } else {
    productDetailState.value = { loading: false, error: '未找到该产品资料' }
  }
}

async function saveProduct() {
  const product = products.value.find((item) => item.product_id === selectedProductId.value)
  if (!product) return
  pageLoading.value = true
  pageError.value = ''
  try {
    const payload = await apiFetch<ProductItem>(`/products/${product.product_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        product_name: productEditName.value.trim(),
        material_spec: productEditMaterial.value.trim() || null,
        base_price_cents: Math.round(Number(productEditPrice.value || 0) * 100),
        currency: product.currency,
        status: productEditStatus.value,
        price_note: productEditNote.value.trim() || null
      })
    })
    products.value = products.value.map((item) => item.product_id === payload.data.product_id ? payload.data : item)
    pageResult.value = '已有产品资料已保存。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '产品资料保存失败'
  } finally {
    pageLoading.value = false
  }
}

async function selectBillingOrder(orderId: number) {
  selectedBillingOrderId.value = orderId
  billingDrawerVisible.value = true
  billingDetailState.value = { loading: true, error: '' }
  billingDetailErrors.value = []
  selectedBill.value = null
  selectedPayments.value = []
  billDocument.value = null
  billAmountYuan.value = null
  billCreateError.value = ''
  const [bill, payments] = await Promise.allSettled([
    apiFetch<BillInfo>(`/orders/${orderId}/bill`),
    apiFetch<PaymentItem[]>(`/orders/${orderId}/payments`)
  ])
  if (selectedBillingOrderId.value !== orderId) return
  if (bill.status === 'fulfilled') selectedBill.value = bill.value.data
  else billingDetailErrors.value.push('账单资料')
  if (payments.status === 'fulfilled') selectedPayments.value = payments.value.data
  else billingDetailErrors.value.push('收款记录')
  billingDetailState.value = { loading: false, error: billingDetailErrors.value.length === 2 ? '账单与收款资料暂时无法加载' : '' }
}

function selectBillDocument(event: Event) {
  billDocument.value = (event.target as HTMLInputElement).files?.[0] || null
  billCreateError.value = ''
}

async function createBillForSelectedOrder() {
  const orderId = selectedBillingOrderId.value
  const file = billDocument.value
  const amountYuan = billAmountYuan.value
  if (!orderId || !file || amountYuan == null || amountYuan <= 0 || billCreateLoading.value) return
  if (!file.name.toLowerCase().endsWith('.pdf') && file.type !== 'application/pdf') {
    billCreateError.value = '账单文件只接受 PDF。'
    return
  }
  if (file.size <= 0 || file.size > 500 * 1024 * 1024) {
    billCreateError.value = '账单 PDF 必须大于 0 且不超过 500MB。'
    return
  }
  billCreateLoading.value = true
  billCreateError.value = ''
  try {
    const uploadToken = await apiFetch<UploadTokenResponse>('/files/upload-token', {
      method: 'POST',
      body: JSON.stringify({
        order_id: orderId,
        source_type: 'BILL',
        visibility: 'DOCTOR_CS',
        original_filename: file.name,
        content_type: 'application/pdf',
        file_size: file.size
      })
    })
    const uploadResponse = await fetch(uploadToken.data.upload_url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/pdf' },
      body: file
    })
    if (!uploadResponse.ok) {
      throw new Error(`账单文件上传失败：${uploadResponse.status}`)
    }
    await apiFetch<FileCompleteResponse>(`/files/${uploadToken.data.file_id}/complete`, { method: 'POST' })
    await apiFetch<BillInfo>(`/orders/${orderId}/bill`, {
      method: 'POST',
      body: JSON.stringify({
        file_id: uploadToken.data.file_id,
        amount_cents: Math.round(amountYuan * 100),
        currency: 'CNY'
      })
    })
    await loadDelivery()
    await selectBillingOrder(orderId)
    pageResult.value = '账单 PDF 已上传并与订单关联。'
  } catch (error) {
    billCreateError.value = error instanceof Error ? error.message : '账单建立失败'
  } finally {
    billCreateLoading.value = false
  }
}

function selectDeliveryOrder(item: DeliveryItem) {
  selectedDeliveryOrderId.value = item.order_id
  carrierDraft.value = item.carrier || ''
  trackingDraft.value = item.tracking_no || ''
  logisticsFollowUpDraft.value = item.last_follow_up_note || ''
  shippingDialogVisible.value = false
  deliveryDrawerVisible.value = true
}

async function selectOutsourcing(item: OutsourcingItem) {
  selectedOutsourcingId.value = item.outsourcing_id
  outsourcingDrawerVisible.value = true
  outsourcingDetailState.value = { loading: true, error: '' }
  try {
    const detail = (await apiFetch<OutsourcingItem>(`/production/outsourcing/${encodeURIComponent(item.batch_no)}`)).data
    if (selectedOutsourcingId.value === item.outsourcing_id) {
      outsourcingItems.value = outsourcingItems.value.map((existing) => existing.outsourcing_id === detail.outsourcing_id ? detail : existing)
    }
  } catch (error) {
    outsourcingDetailState.value.error = detailError(error, '外协详情加载失败')
  } finally {
    outsourcingDetailState.value.loading = false
  }
}

async function createPaymentRecord() {
  if (!selectedBillingOrderId.value || !paymentAmountYuan.value || paymentAmountYuan.value <= 0) return
  pageLoading.value = true
  pageError.value = ''
  try {
    await apiFetch<PaymentItem>(`/orders/${selectedBillingOrderId.value}/payments`, {
      method: 'POST', body: JSON.stringify({
        amount_cents: Math.round(paymentAmountYuan.value * 100), currency: 'CNY',
        payment_method: paymentMethod.value, payment_note: paymentNote.value.trim() || null
      })
    })
    paymentAmountYuan.value = null
    paymentNote.value = ''
    await selectBillingOrder(selectedBillingOrderId.value)
    pageResult.value = '收款记录已保存，历史记录保持可追溯。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '收款记录保存失败'
  } finally {
    pageLoading.value = false
  }
}

async function shipSelectedOrder() {
  if (!selectedDeliveryOrderId.value || !carrierDraft.value.trim() || !trackingDraft.value.trim()) return
  pageLoading.value = true
  pageError.value = ''
  try {
    await apiFetch<LogisticsInfo>(`/orders/${selectedDeliveryOrderId.value}/logistics`, {
      method: 'POST', body: JSON.stringify({ carrier: carrierDraft.value.trim(), tracking_no: trackingDraft.value.trim() })
    })
    await loadDelivery()
    const refreshed = deliveryItems.value.find((item) => item.order_id === selectedDeliveryOrderId.value)
    if (refreshed) selectDeliveryOrder(refreshed)
    shippingDialogVisible.value = false
    pageResult.value = '发货信息已登记，医生端将看到脱敏物流信息。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '发货登记失败'
  } finally {
    pageLoading.value = false
  }
}

async function saveLogisticsFollowUp() {
  if (!selectedDeliveryOrderId.value || !logisticsFollowUpDraft.value.trim()) return
  pageLoading.value = true
  pageError.value = ''
  try {
    await apiFetch<DeliveryItem>(`/orders/${selectedDeliveryOrderId.value}/logistics/exception`, {
      method: 'POST', body: JSON.stringify({ logistics_status: logisticsStatusDraft.value, follow_up_note: logisticsFollowUpDraft.value.trim() })
    })
    await loadDelivery()
    const refreshed = deliveryItems.value.find((item) => item.order_id === selectedDeliveryOrderId.value)
    if (refreshed) {
      selectedDeliveryOrderId.value = refreshed.order_id
      logisticsFollowUpDraft.value = refreshed.last_follow_up_note || ''
    }
    pageResult.value = '配送异常跟进已保存为内部记录。'
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '配送跟进保存失败'
  } finally {
    pageLoading.value = false
  }
}

async function markNotification(notification: NotificationItem) {
  if (!notification.read_at) {
    await apiFetch<NotificationItem>(`/notifications/${notification.notification_id}/read`, { method: 'POST' })
    await loadNotifications()
  }
  if (notification.order_id) {
    const order = orders.value.find((item) => item.order_id === notification.order_id)
    if (order) await openOrder(order)
    emit('navigate', '/cs/orders')
  }
}

async function markAllNotifications() {
  await apiFetch<{ updated_count: number }>('/notifications/read-all', { method: 'POST' })
  await loadNotifications()
  pageResult.value = '全部通知已标记为已读；业务状态没有被修改。'
}

async function loadRoute(route: string) {
  if (!props.token) return
  const focusOrderId = props.focusOrderId
  const focusTask = props.focusTask
  const focusKey = focusTask || focusOrderId !== null ? `${route}:${focusTask ?? ''}:${focusOrderId ?? ''}` : ''
  if (focusKey && activeFocusKey.value === focusKey) return
  if (focusKey) activeFocusKey.value = focusKey
  pageLoading.value = true
  pageError.value = ''
  pageResult.value = ''
  try {
    if (route === '/cs/orders') {
      if (focusTask === 'ALL_ORDERS') orderFilter.value = 'ALL'
      await Promise.all([loadOrders(), loadOrderAttention()])
      const focusOrder = orders.value.find((item) => item.order_id === focusOrderId)
      if (focusOrder) await openOrder(focusOrder)
    }
    if (route === '/cs/information-translation') {
      if (focusTask === 'ORDER_REVIEW') translationFilter.value = 'PENDING'
      await loadOrders()
      const first = orders.value.find((item) => item.order_id === focusOrderId)
        || orders.value.find((item) => item.order_id === translationOrderId.value)
        || filteredTranslationOrders.value[0]
        || orders.value[0]
      if (first) await selectTranslationOrder(first)
    }
    if (route === '/cs/designs') {
      if (focusTask === 'DESIGN_UPDATE') designFilter.value = 'UPDATED'
      await loadOrders()
      designDrawerVisible.value = false
      if (focusOrderId !== null && orders.value.some((item) => item.order_id === focusOrderId)) await selectDesignOrder(focusOrderId)
    }
    if (route === '/cs/inquiries') {
      if (focusTask === 'WAITING_REPLY') inquiryTab.value = 'WAITING'
      if (focusTask === 'MESSAGE_REVIEW') inquiryTab.value = 'REVIEW'
      await loadInquiryBase()
      const focusOrder = orders.value.find((item) => item.order_id === focusOrderId)
      if (focusOrder) await loadInquiryMessages(focusOrder.order_id)
    }
    if (route === '/cs/customers') {
      await Promise.all([loadClinics(), loadOrders()])
      customerDrawerVisible.value = false
    }
    if (route === '/cs/products') {
      await loadProducts()
      productDrawerVisible.value = false
      if (focusTask === 'SEARCH_PRODUCT' && focusOrderId !== null
        && products.value.some((item) => item.product_id === focusOrderId)) {
        await selectProduct(focusOrderId)
      }
    }
    if (route === '/cs/billing') {
      if (focusTask === 'BILLING_PENDING') {
        billingTab.value = 'ORDER'
        billingFilter.value = 'PENDING'
      }
      await Promise.all([loadOrders(), loadDelivery()])
      billingDrawerVisible.value = false
      if (focusOrderId !== null && deliveryItems.value.some((item) => item.order_id === focusOrderId)) await selectBillingOrder(focusOrderId)
    }
    if (route === '/cs/delivery') {
      if (focusTask === 'DELIVERY_FOLLOW_UP') deliveryStatus.value = 'FOLLOW_UP'
      if (focusTask === 'SHIPPING_PENDING') deliveryStatus.value = 'PENDING'
      await loadDelivery()
      deliveryDrawerVisible.value = false
      const focusDelivery = deliveryItems.value.find((item) => item.order_id === focusOrderId)
      if (focusDelivery) selectDeliveryOrder(focusDelivery)
    }
    if (route === '/cs/quality') {
      if (focusTask === 'QUALITY_FOLLOW_UP') qualityStatus.value = 'ACTIVE'
      await loadQualityRecords()
      qualityDrawerVisible.value = false
      const focusQuality = qualityRecords.value.find((item) => item.order_id === focusOrderId)
      if (focusQuality) selectQualityRecord(focusQuality)
    }
    if (route === '/cs/outsourcing') {
      await loadOutsourcing()
      outsourcingDrawerVisible.value = false
      const focusOutsourcing = focusTask === 'SEARCH_OUTSOURCING'
        ? outsourcingItems.value.find((item) => item.outsourcing_id === focusOrderId)
        : null
      if (focusOutsourcing) await selectOutsourcing(focusOutsourcing)
    }
    if (route === '/cs/notifications') await Promise.all([loadOrders(), loadNotifications()])
    if (route === '/cs/search') await Promise.all([loadOrders(), loadClinics(), loadProducts(), loadDelivery(), loadOutsourcing()])
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '页面数据加载失败'
  } finally {
    pageLoading.value = false
    if (focusKey && !(route === '/cs/customers' && focusTask === 'SEARCH_CUSTOMER')) emit('focusConsumed')
  }
}

const filteredOrders = computed(() => {
  const keyword = orderKeyword.value.trim().toLowerCase()
  return orders.value.filter((order) => {
    const matchesKeyword = !keyword || csOrderMatchesKeyword(order, keyword)
    if (!matchesKeyword) return false
    if (orderFilter.value === 'NEW') return registrationStatus(order) === 'NEW'
    if (orderFilter.value === 'REGISTERED') return registrationStatus(order) === 'REGISTERED'
    if (orderFilter.value === 'QUESTION') return attentionItems.value.some((item) => item.order_id === order.order_id)
    if (orderFilter.value === 'EXCEPTION') return Boolean(order.reject_reason)
    return true
  }).sort((a, b) => Number(registrationStatus(b) === 'NEW') - Number(registrationStatus(a) === 'NEW'))
})

const orderFilterCounts = computed(() => ({
  ALL: orders.value.length,
  NEW: orders.value.filter((item) => registrationStatus(item) === 'NEW').length,
  REGISTERED: orders.value.filter((item) => registrationStatus(item) === 'REGISTERED').length,
  QUESTION: attentionItems.value.length,
  EXCEPTION: orders.value.filter((item) => Boolean(item.reject_reason)).length
}))

const orderFilterOptions: Array<{ key: 'ALL' | 'NEW' | 'REGISTERED' | 'QUESTION' | 'EXCEPTION'; label: string }> = [
  { key: 'ALL', label: '全部订单' },
  { key: 'NEW', label: '新订单' },
  { key: 'REGISTERED', label: '已登记' },
  { key: 'QUESTION', label: '有问单' },
  { key: 'EXCEPTION', label: '异常' }
]

function orderFilterCount(key: 'ALL' | 'NEW' | 'REGISTERED' | 'QUESTION' | 'EXCEPTION') {
  return orderFilterCounts.value[key]
}

const notificationFilterOptions: Array<{ key: 'ALL' | 'UNREAD' | 'ORDER' | 'MESSAGE' | 'DESIGN' | 'BILLING'; label: string }> = [
  { key: 'ALL', label: '全部' },
  { key: 'UNREAD', label: '未读' },
  { key: 'ORDER', label: '订单' },
  { key: 'MESSAGE', label: '问单' },
  { key: 'DESIGN', label: '设计稿' },
  { key: 'BILLING', label: '账单' }
]

const selectedTranslationOrder = computed(() => orders.value.find((item) => item.order_id === translationOrderId.value) || null)
const selectedDesignOrder = computed(() => orders.value.find((item) => item.order_id === designOrderId.value) || null)
const selectedProduct = computed(() => products.value.find((item) => item.product_id === selectedProductId.value) || null)
const selectedDelivery = computed(() => deliveryItems.value.find((item) => item.order_id === selectedDeliveryOrderId.value) || null)
const selectedOutsourcing = computed(() => outsourcingItems.value.find((item) => item.outsourcing_id === selectedOutsourcingId.value) || null)
const selectedBillingOrder = computed(() => orders.value.find((item) => item.order_id === selectedBillingOrderId.value) || null)
const selectedClinicOrders = computed(() => orders.value.filter((item) => item.clinic_id === selectedClinicId.value))

const orderDetailFields = computed<ReviewDisplayField[]>(() => {
  if (!selectedOrder.value) return []
  let extraIndex = 0
  return Object.entries(selectedOrder.value.form_data || {})
    .filter(([key, value]) => !isInternalReviewField(key) && Boolean(reviewFieldValue(value)))
    .map(([key, value]) => {
      const text = reviewFieldValue(value)
      if (!reviewFieldLabels[key]) extraIndex += 1
      return {
        key,
        label: reviewFieldLabels[key] || `补充资料 ${extraIndex}`,
        value: text,
        required: false,
        missing: false,
        long: text.length > 34 || /instruction|note|description|requirement/.test(key)
      }
    })
})

const orderTimeline = computed<TimelineStep[]>(() => {
  const order = selectedOrder.value
  if (!order) return []
  const steps = [
    { key: 'submitted', icon: '✓', label: '客户提交订单', detail: compactDateTime(order.created_at) },
    { key: 'review', icon: '✏️', label: '客服登记与资料审核', detail: registrationStatus(order) === 'NEW' ? '当前等待客服处理' : '已进入后续业务处理' },
    { key: 'production-review', icon: '🔍', label: '生产审核', detail: '依据真实订单状态推进' },
    { key: 'production', icon: '⚙️', label: '生产制作', detail: '生产节点由工厂端记录' },
    { key: 'design', icon: '🎨', label: '设计确认', detail: orderDrafts.value.length ? `${orderDrafts.value.length} 个真实版本` : '尚无设计版本' },
    { key: 'quality', icon: '✅', label: '终检放行', detail: '由出检记录决定' },
    { key: 'billing', icon: '💳', label: '账单与收款', detail: statusLabel(orderBill.value?.payment_status) },
    { key: 'shipping', icon: '🚀', label: '配送交付', detail: statusLabel(orderLogistics.value?.logistics_status) }
  ]
  const statusIndex: Record<string, number> = {
    PENDING_CS_REVIEW: 1,
    PENDING_PRODUCTION_REVIEW: 2,
    PENDING_DOCTOR_CONFIRM: 4,
    PROCESS_INSTANCE_CREATED: 3,
    PRODUCING: 3,
    SHIPPED: 7,
    COMPLETED: 7
  }
  const activeIndex = statusIndex[order.internal_status] ?? 1
  return steps.map((step, index) => ({ ...step, state: index < activeIndex ? 'done' : index === activeIndex ? 'current' : 'pending' })) as TimelineStep[]
})

const clinicPreferenceKeys = computed(() => ['color', 'contact', 'occlusion', 'margin', 'shape', 'material', 'note'])
const translationCustomerRequirementItems = computed(() => customerRequirementItems(translationClinicPreference.value?.preferences))
const clinicUnknownPreferences = computed(() => {
  const known = new Set(clinicPreferenceKeys.value)
  return Object.entries(clinicPreference?.value?.preferences || {}).filter(([key, value]) => !known.has(key) && value != null)
})

const receivedAmountCents = computed(() => selectedPayments.value.reduce((sum, item) => sum + item.amount_cents, 0))
const outstandingAmountCents = computed(() => Math.max(0, (selectedBill.value?.amount_cents || 0) - receivedAmountCents.value))
const billingContradiction = computed(() => {
  if (!selectedBill.value) return ''
  const billAmount = selectedBill.value.amount_cents
  if (billAmount != null && receivedAmountCents.value > billAmount) return '累计收款已经超过账单金额，请先核对收款记录。'
  if (selectedBill.value.payment_status === 'PAID' && billAmount != null && receivedAmountCents.value < billAmount) return '账单标记为已收款，但收款记录合计不足。'
  if (selectedBill.value.payment_status !== 'PAID' && billAmount != null && receivedAmountCents.value >= billAmount) return '收款记录已经覆盖账单金额，但账单状态尚未同步。'
  return ''
})
const canRecordPayment = computed(() => Boolean(
  selectedBill.value?.bill_id
  && !billingContradiction.value
  && selectedBill.value.payment_status !== 'PAID'
  && outstandingAmountCents.value > 0
))

const carrierOptions = [
  { name: '顺丰速运', mark: 'SF', icon: '📦' },
  { name: '京东物流', mark: 'JD', icon: '🚚' },
  { name: 'EMS', mark: 'EMS', icon: '✉️' },
  { name: '其他承运商', mark: '＋', icon: '🛣️' }
]
const deliveryPaymentReady = computed(() => ['PAID', 'NOT_REQUIRED'].includes(selectedDelivery.value?.payment_status || ''))
const deliveryCanRegister = computed(() => Boolean(
  selectedDelivery.value
  && selectedDelivery.value.logistics_status === 'PENDING'
  && deliveryPaymentReady.value
))
const deliveryAlreadyShipped = computed(() => Boolean(selectedDelivery.value && selectedDelivery.value.logistics_status !== 'PENDING'))

function productEmoji(type?: string | null) {
  if (/IMPLANT/.test(type || '')) return '🦷'
  if (/BRIDGE/.test(type || '')) return '🌉'
  if (/DENTURE|REMOVABLE/.test(type || '')) return '😁'
  if (/ALIGNER|ORTHODONT/.test(type || '')) return '✨'
  return '👑'
}

const conversationOrders = computed(() => {
  const keyword = inquiryKeyword.value.trim().toLowerCase()
  const waitingOrderIds = new Set(attentionItems.value.map((item) => item.order_id))
  const reviewOrderIds = new Set(pendingMessages.value.map((item) => item.order_id))
  return orders.value.filter((order) => {
    if (inquiryTab.value === 'WAITING' && !waitingOrderIds.has(order.order_id)) return false
    if (inquiryTab.value === 'REVIEW' && !reviewOrderIds.has(order.order_id)) return false
    return !keyword || csOrderMatchesKeyword(order, keyword)
  })
})

const filteredTranslationOrders = computed(() => {
  const keyword = translationKeyword.value.trim().toLowerCase()
  return orders.value.filter((order) => {
    if (translationFilter.value !== 'ALL' && translationReviewBucket(order) !== translationFilter.value) return false
    return !keyword || csOrderMatchesKeyword(order, keyword)
  }).sort((left, right) => {
    const confirmationOrder = Number(hasPassedCsReview(left)) - Number(hasPassedCsReview(right))
    if (confirmationOrder !== 0) return confirmationOrder
    return new Date(left.created_at || 0).getTime() - new Date(right.created_at || 0).getTime()
  })
})

const translationReviewFields = computed<ReviewDisplayField[]>(() => {
  const order = selectedTranslationOrder.value
  if (!order) return []

  const result: ReviewDisplayField[] = []
  const usedKeys = new Set<string>()
  const activeRequirements = [...translationRequirements.value].sort((left, right) => left.sort_order - right.sort_order)
  const frozenValues = order.form_data?.form_values && typeof order.form_data.form_values === 'object'
    ? order.form_data.form_values as Record<string, unknown>
    : {}

  for (const requirement of activeRequirements) {
    if (isInternalReviewField(requirement.field_key)) continue
    const value = reviewFieldValue(order.form_data?.[requirement.field_key] ?? frozenValues[requirement.field_key])
    result.push({
      key: requirement.field_key,
      label: requirement.field_label || reviewFieldLabels[requirement.field_key] || '订单信息',
      value: value || '未填写',
      required: requirement.is_required,
      missing: requirement.is_required && !value,
      long: requirement.field_type === 'textarea' || value.length > 34
    })
    usedKeys.add(requirement.field_key)
  }

  let extraIndex = 0
  for (const [key, rawValue] of Object.entries(order.form_data || {})) {
    if (usedKeys.has(key) || isInternalReviewField(key)) continue
    const value = reviewFieldValue(rawValue)
    if (!value) continue
    extraIndex += 1
    result.push({
      key,
      label: reviewFieldLabels[key] || `补充信息 ${extraIndex}`,
      value,
      required: false,
      missing: false,
      long: ['instruction', 'customer_instruction', 'description', 'notes', 'special_requirements', 'doctor_note'].includes(key) || value.length > 34
    })
  }

  return result
})

const translationRequiredMissingCount = computed(() => translationReviewFields.value.filter((item) => item.missing).length)

const translationReviewChecklist = computed(() => {
  const order = selectedTranslationOrder.value
  if (!order) return []
  const hasFrozenConfiguration = Array.isArray(order.form_schema_snapshot)
  const hasManufacturingParameters = hasFrozenConfiguration
    ? translationRequiredMissingCount.value === 0
    : Boolean(
      orderFormValue(order, ['tooth_position', 'tooth', 'teeth'])
      && (orderFormValue(order, ['material']) || orderFormValue(order, ['shade', 'color']))
    )
  return [
    {
      label: '必填资料',
      value: translationRequiredMissingCount.value ? `${translationRequiredMissingCount.value} 项待补充` : '已填写完整',
      ok: translationRequiredMissingCount.value === 0
    },
    {
      label: '制作参数',
      value: hasFrozenConfiguration
        ? (hasManufacturingParameters ? '已按提交快照核对' : '提交快照仍有必填项缺失')
        : (hasManufacturingParameters ? '已填写关键参数' : '关键参数待核对'),
      ok: hasManufacturingParameters
    },
    { label: '客户文字', value: translationSource.value.trim() ? '有内容需要翻译' : '未单独填写外文指示', ok: true },
    { label: '关联附件', value: translationFiles.value.length ? `${translationFiles.value.length} 个附件` : '未上传附件', ok: translationFiles.value.length > 0 }
  ]
})

const translationFilterCounts = computed(() => ({
  ALL: orders.value.length,
  NOT_STARTED: orders.value.filter((order) => translationReviewBucket(order) === 'NOT_STARTED').length,
  PENDING: orders.value.filter((order) => translationReviewBucket(order) === 'PENDING').length,
  CONFIRMED: orders.value.filter((order) => translationReviewBucket(order) === 'CONFIRMED').length,
  REJECTED: orders.value.filter((order) => translationReviewBucket(order) === 'REJECTED').length
}))

const filteredClinics = computed(() => {
  const keyword = customerKeyword.value.trim().toLowerCase()
  return clinics.value.filter((clinic) => {
    const isIncomplete = !clinic.contact_name?.trim() || !clinic.contact_phone?.trim() || clinic.preference_count === 0
    const isInactive = clinic.status === 'INACTIVE'
    if (customerFilter.value === 'INCOMPLETE' && !isIncomplete) return false
    if (customerFilter.value === 'INACTIVE' && !isInactive) return false
    return !keyword || [clinic.clinic_name, clinic.contact_name, clinic.contact_phone]
      .some((value) => String(value || '').toLowerCase().includes(keyword))
  })
})

const incompleteClinics = computed(() => clinics.value.filter((clinic) =>
  !clinic.contact_name?.trim() || !clinic.contact_phone?.trim() || clinic.preference_count === 0))
const inactiveClinics = computed(() => clinics.value.filter((clinic) => clinic.status === 'INACTIVE'))

const filteredProducts = computed(() => {
  const keyword = productKeyword.value.trim().toLowerCase()
  return products.value.filter((product) => !keyword || [product.product_name, product.product_type, product.material_spec]
    .some((value) => String(value || '').toLowerCase().includes(keyword)))
})

const filteredDesignOrders = computed(() => {
  const keyword = designKeyword.value.trim().toLowerCase()
  return orders.value.filter((order) => {
    if (designFilter.value === 'UPDATED' && !['DESIGN_UPLOADED', 'PENDING_DOCTOR_CONFIRM'].includes(order.internal_status)) return false
    return !keyword || csOrderMatchesKeyword(order, keyword)
  })
})

const filteredBillingItems = computed(() => deliveryItems.value.filter((item) =>
  billingFilter.value === 'ALL' || !['PAID', 'NOT_REQUIRED', 'SETTLED', 'NO_PAYMENT_REQUIRED'].includes(item.payment_status)))
const filteredDelivery = computed(() => deliveryItems.value.filter((item) => {
  if (deliveryStatus.value === 'FOLLOW_UP') return ['EXCEPTION', 'FOLLOWING', 'FOLLOWING_UP'].includes(item.logistics_status)
  return deliveryStatus.value === 'ALL' || item.logistics_status === deliveryStatus.value
}))
const filteredQualityRecords = computed(() => qualityRecords.value.filter((item) => {
  if (qualityStatus.value === 'ACTIVE') return ['PENDING', 'IN_PROGRESS'].includes(item.status)
  return qualityStatus.value === 'ALL' || item.status === qualityStatus.value
}))
const selectedQualityRecord = computed(() => qualityRecords.value.find((item) => item.quality_record_id === selectedQualityRecordId.value) || null)
const filteredOutsourcing = computed(() => outsourcingItems.value.filter((item) => outsourcingStatus.value === 'ALL' || item.status === outsourcingStatus.value))

function selectQualityRecord(item: QualityRecordItem) {
  selectedQualityRecordId.value = item.quality_record_id
  qualityDrawerVisible.value = true
}

function setQualityStatus(status: string) {
  if (['ACTIVE', 'ALL', 'PENDING', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].includes(status)) {
    qualityStatus.value = status as typeof qualityStatus.value
  }
}

function setDeliveryStatus(status: string) {
  deliveryStatus.value = status
  if (selectedDeliveryOrderId.value && !filteredDelivery.value.some((item) => item.order_id === selectedDeliveryOrderId.value)) {
    selectedDeliveryOrderId.value = null
    deliveryDrawerVisible.value = false
  }
}

function setOutsourcingStatus(status: string) {
  outsourcingStatus.value = status
  if (selectedOutsourcingId.value && !filteredOutsourcing.value.some((item) => item.outsourcing_id === selectedOutsourcingId.value)) {
    selectedOutsourcingId.value = null
    outsourcingDrawerVisible.value = false
  }
}

const filteredNotifications = computed(() => notifications.value.filter((item) => {
  if (notificationFilter.value === 'UNREAD') return !item.read_at
  const event = item.event.toUpperCase()
  if (notificationFilter.value === 'ORDER') return event.includes('ORDER')
  if (notificationFilter.value === 'MESSAGE') return event.includes('MESSAGE')
  if (notificationFilter.value === 'DESIGN') return event.includes('DESIGN')
  if (notificationFilter.value === 'BILLING') return event.includes('BILL') || event.includes('PAYMENT')
  return true
}))

const filteredHelpTopics = computed(() => {
  const keyword = helpKeyword.value.trim().toLowerCase()
  if (!keyword) return helpTopics
  return helpTopics.filter((topic) => [topic.label, topic.title, topic.intro, ...topic.articles.flatMap((item) => [item.title, item.body])]
    .some((value) => value.toLowerCase().includes(keyword)))
})

const selectedHelpTopic = computed(() => helpTopics.find((item) => item.key === helpTopic.value) || helpTopics[0])

const searchResults = computed(() => {
  const keyword = searchInput.value.trim().toLowerCase()
  if (keyword.length < 2) return []
  const results: Array<{ type: string; title: string; detail: string; route: string; id: number }> = []
  orders.value.forEach((order) => {
    if ([order.order_no, order.clinic_name, productLabel(order.product_type), orderFormValue(order, ['patient_name'])]
      .some((value) => String(value || '').toLowerCase().includes(keyword))) {
      results.push({ type: '订单', title: order.order_no, detail: `${order.clinic_name} · ${productLabel(order.product_type)}`, route: '/cs/orders', id: order.order_id })
    }
  })
  clinics.value.forEach((clinic) => {
    if ([clinic.clinic_name, clinic.contact_name, clinic.contact_phone].some((value) => String(value || '').toLowerCase().includes(keyword))) {
      results.push({ type: '客户', title: clinic.clinic_name, detail: clinic.contact_name || '联系人未设置', route: '/cs/customers', id: clinic.clinic_id })
    }
  })
  products.value.forEach((product) => {
    if ([product.product_name, product.product_type, product.material_spec].some((value) => String(value || '').toLowerCase().includes(keyword))) {
      results.push({ type: '产品', title: product.product_name, detail: product.material_spec || '材料规格待完善', route: '/cs/products', id: product.product_id })
    }
  })
  outsourcingItems.value.forEach((item) => {
    if ([item.batch_no, item.order_no, item.supplier_name, item.item_name].some((value) => String(value || '').toLowerCase().includes(keyword))) {
      results.push({ type: '外协', title: item.batch_no, detail: `${item.order_no} · ${item.supplier_name}`, route: '/cs/outsourcing', id: item.outsourcing_id })
    }
  })
  return results.slice(0, 80)
})

async function openSearchResult(result: { type: string; route: string; id: number }) {
  if (result.type === '客户') {
    emit('navigate', result.route, result.id, 'SEARCH_CUSTOMER')
    return
  }
  if (result.type === '产品') {
    emit('navigate', result.route, result.id, 'SEARCH_PRODUCT')
    return
  }
  if (result.type === '外协') {
    emit('navigate', result.route, result.id, 'SEARCH_OUTSOURCING')
    return
  }
  emit('navigate', result.route, result.id)
}

watch(() => props.searchKeyword, (value) => { searchInput.value = value })
watch(() => props.activeRoute, (route) => { void loadRoute(route) }, { immediate: true })
watch([() => props.focusOrderId, () => props.focusTask], () => {
  if (props.focusOrderId === null && props.focusTask === null) {
    activeFocusKey.value = ''
    return
  }
  if (props.activeRoute.startsWith('/cs/')) void loadRoute(props.activeRoute)
})
watch([orderFilter, orderKeyword], () => {
  if (selectedOrder.value && !filteredOrders.value.some((item) => item.order_id === selectedOrder.value?.order_id)) {
    orderDrawerVisible.value = false
  }
})
watch(productKeyword, () => {
  if (selectedProductId.value && !filteredProducts.value.some((item) => item.product_id === selectedProductId.value)) {
    productDrawerVisible.value = false
  }
})
watch([designKeyword, designFilter], () => {
  if (designOrderId.value && !filteredDesignOrders.value.some((item) => item.order_id === designOrderId.value)) {
    designDrawerVisible.value = false
  }
})
watch([translationKeyword, translationFilter], () => {
  if (translationOrderId.value && !filteredTranslationOrders.value.some((item) => item.order_id === translationOrderId.value)) {
    translationOrderId.value = null
  }
})
watch([inquiryTab, pendingMessages], () => {
  const currentVisible = conversationOrders.value.some((item) => item.order_id === inquiryOrderId.value)
  if (currentVisible) return
  const first = conversationOrders.value[0]
  if (first) {
    void loadInquiryMessages(first.order_id)
  } else if (inquiryTab.value === 'REVIEW') {
    inquiryOrderId.value = null
    inquiryMessages.value = []
  }
})
watch([customerKeyword, customerFilter], () => {
  if (selectedClinicId.value && !filteredClinics.value.some((item) => item.clinic_id === selectedClinicId.value)) {
    customerDrawerVisible.value = false
  }
})
watch(billingTab, (tab) => {
  if (tab === 'MONTHLY') billingDrawerVisible.value = false
})
</script>

<template>
  <div class="cs-r-page" :data-route="activeRoute">
    <div v-if="pageError" class="cs-r-feedback is-error" role="alert"><strong>页面暂时无法完成操作</strong><span>{{ pageError }}</span><button type="button" @click="loadRoute(activeRoute)">重新加载</button></div>
    <div v-if="pageResult" class="cs-r-feedback is-success" role="status"><span>{{ pageResult }}</span><button type="button" aria-label="关闭提示" @click="pageResult = ''">×</button></div>

    <template v-if="activeRoute === '/cs/orders'">
      <header class="cs-r-heading"><div><h1>订单管理</h1><p>查看客户提交的新订单、登记状态和后续业务进度。</p></div><span class="cs-r-count">{{ orderTotal }} 单</span></header>
      <section class="cs-r-filter-card">
        <div class="cs-r-segmented" aria-label="订单快捷筛选">
          <button v-for="item in orderFilterOptions" :key="item.key" type="button" :class="{ active: orderFilter === item.key }" @click="orderFilter = item.key">{{ item.label }} <b>{{ orderFilterCount(item.key) }}</b></button>
        </div>
      </section>
      <section class="cs-r-table-card">
        <header class="cs-r-table-toolbar"><div><h3>订单列表</h3><span>{{ filteredOrders.length }} / {{ orderTotal }} 单</span></div><label class="cs-r-search"><span>⌕</span><input v-model="orderKeyword" type="search" placeholder="搜索客户、患者、病例号、牙位、材料、颜色或系统单号" aria-label="搜索订单"></label></header>
        <div v-if="pageLoading" class="cs-r-state">正在加载真实订单…</div>
        <div v-else-if="filteredOrders.length === 0" class="cs-r-state"><strong>没有符合条件的订单</strong><span>调整筛选条件后重试。</span></div>
        <table v-else data-testid="cs-orders-table">
          <colgroup><col style="width:17%"><col style="width:15%"><col style="width:15%"><col style="width:11%"><col style="width:17%"><col style="width:13%"><col style="width:12%"></colgroup>
          <thead><tr><th>订单识别</th><th>产品信息</th><th>客户单号 / 系统号</th><th>登记状态</th><th>信息状态</th><th>订单阶段</th><th>操作</th></tr></thead>
          <tbody><tr v-for="order in filteredOrders" :key="order.order_id" :class="{ 'is-new': registrationStatus(order) === 'NEW' }" @click="openOrder(order)">
            <td><strong>{{ csOrderIdentity(order).primary }}</strong><small>{{ csOrderIdentity(order).secondary }}</small></td>
            <td><strong>{{ productLabel(order.product_type) }}</strong><small>{{ orderFormValue(order, ['material','material_name','material_spec']) || '材料待确认' }} · {{ orderFormValue(order, ['shade','color','shade_code']) || '色号待确认' }}</small></td>
            <td><strong>{{ csOrderIdentity(order).reference }}</strong><small>{{ csOrderIdentity(order).systemOrderNo }}</small></td>
            <td><span class="cs-r-badge" :class="registrationStatus(order) === 'NEW' ? 'is-amber' : 'is-green'">{{ registrationStatus(order) === 'NEW' ? '新订单' : '已登记' }}</span></td>
            <td>{{ informationStatus(order) }}</td><td><span class="cs-r-badge is-violet">{{ statusLabel(order.internal_status) }}</span><span v-if="order.delivery_alert" class="cs-r-badge is-red" data-testid="cs-delivery-alert-badge" :title="order.delivery_alert_message ?? ''">⏱ 时间异常</span></td>
            <td><button class="cs-r-link" type="button" @click.stop="openOrder(order)">查看</button></td>
          </tr></tbody>
        </table>
      </section>
      <el-drawer v-model="orderDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer cs-r-order-drawer" modal-class="cs-r-drawer-overlay" @closed="resetOrderPreview">
        <div v-if="selectedOrder" class="cs-r-drawer-shell cs-r-order-drawer-shell">
          <header class="cs-r-order-drawer-head">
            <div><small>订单详情</small><h2>{{ csOrderIdentity(selectedOrder).primary }}</h2><p>{{ csOrderIdentity(selectedOrder).secondary }} · {{ selectedOrder.order_no }}</p></div>
            <div class="cs-r-order-head-actions"><button type="button" @click="navigateFromOrderDrawer('/cs/information-translation')">信息审核</button><button type="button" aria-label="关闭订单详情" @click="orderDrawerVisible = false">×</button></div>
          </header>

          <div class="cs-r-order-drawer-body">
            <section v-if="selectedOrder.delivery_alert" class="cs-r-order-delivery-alert" data-testid="cs-delivery-alert">
              <strong>⏱ 交期时间异常</strong>
              <p>{{ selectedOrder.delivery_alert_message }}</p>
            </section>
            <section class="cs-r-order-summary">
              <div class="cs-r-summary-grid">
                <div><span>客户</span><strong>{{ selectedOrder.clinic_name }}</strong></div>
                <div><span>患者</span><strong>{{ orderFormValue(selectedOrder,['patient_name']) || (selectedOrder.patient_id ? `档案 #${selectedOrder.patient_id}` : '按权限显示') }}</strong></div>
                <div><span>产品</span><strong>{{ productLabel(selectedOrder.product_type) }}</strong></div>
                <div><span>订单阶段</span><strong>{{ statusLabel(selectedOrder.internal_status) }}</strong></div>
                <div><span>牙位</span><strong>{{ orderFormValue(selectedOrder,['tooth_position','tooth','teeth']) || '待确认' }}</strong></div>
                <div><span>色号</span><strong>{{ orderFormValue(selectedOrder,['shade','color']) || '待确认' }}</strong></div>
                <div><span>负责医生</span><strong>{{ selectedOrder.doctor_user_id ? `人员 #${selectedOrder.doctor_user_id}` : '未分配' }}</strong></div>
                <div><span>客服负责人</span><strong>{{ selectedOrder.cs_user_id ? `人员 #${selectedOrder.cs_user_id}` : '未分配' }}</strong></div>
                <div><span>应收金额</span><strong>{{ orderBill ? money(orderBill.amount_cents, orderBill.currency || 'CNY') : '金额待录入' }}</strong></div>
                <div class="is-state"><span>登记状态</span><strong>{{ registrationStatus(selectedOrder) === 'NEW' ? '新订单' : '已登记' }}</strong></div>
                <div><span>系统可行交期</span><strong data-testid="cs-promised-delivery-date">{{ selectedOrder.promised_delivery_date ? (selectedOrder.delivery_estimate_status === 'PLACEHOLDER' ? `${selectedOrder.promised_delivery_date}（待确认）` : selectedOrder.promised_delivery_date) : '尚未生成交期计划' }}</strong></div>
                <div><span>医生要求到货</span><strong>{{ selectedOrder.doctor_requested_delivery_date || '未指定' }}</strong></div>
                <div><span>订单创建时间</span><strong>{{ compactDateTime(selectedOrder.created_at) }}</strong></div>
                <div><span>最近更新</span><strong>{{ compactDateTime(selectedOrder.updated_at) }}</strong></div>
                <div><span>生产创建时间</span><strong>{{ orderProcess ? compactDateTime(orderProcess.created_at) : '尚未创建生产流程' }}</strong></div>
              </div>
            </section>

            <section v-if="orderDrawerAlert" class="cs-r-order-alert" :class="`is-${orderDrawerAlert.tone}`">
              <span aria-hidden="true">{{ orderDrawerAlert.tone === 'success' ? '✓' : orderDrawerAlert.tone === 'danger' ? '!' : 'i' }}</span>
              <div><strong>{{ orderDrawerAlert.title }}</strong><p>{{ orderDrawerAlert.text }}</p></div>
            </section>

            <section
              v-if="actionableBusinessGate"
              class="cs-r-order-section cs-r-business-gate"
              data-testid="cs-business-gate-card"
            >
              <div class="cs-r-order-section-title">
                <div><span>客服业务门禁</span><h3>{{ actionableBusinessGate.process_name }}</h3></div>
                <b>{{ statusLabel(actionableBusinessGate.node_status) }}</b>
              </div>
              <p>{{ CS_BUSINESS_GATE_LABELS.get(actionableBusinessGate.process_name) }}</p>
              <button
                v-if="actionableBusinessGate.process_name === '客服核对订单信息及账单'"
                class="cs-r-order-panel-route"
                type="button"
                @click="navigateFromOrderDrawer('/cs/billing')"
              >
                先前往账单管理核对
              </button>
              <p v-if="billingBusinessGateBlocked" class="cs-r-order-inline-error">
                当前订单尚未建立 PDF 账单，服务端会阻止该门禁放行。
              </p>
              <label>
                <span>核对结论</span>
                <textarea
                  v-model="businessGateNote"
                  rows="3"
                  maxlength="500"
                  placeholder="填写已核对的业务事实，不使用演示值代替正式资料"
                ></textarea>
              </label>
              <p v-if="businessGateError" class="cs-r-order-inline-error">{{ businessGateError }}</p>
              <button
                class="cs-r-primary"
                type="button"
                :disabled="businessGateLoading || billingBusinessGateBlocked || !businessGateNote.trim()"
                @click="completeBusinessGate"
              >
                {{ businessGateLoading ? '提交中…' : `确认完成${actionableBusinessGate.process_name}` }}
              </button>
            </section>

            <section class="cs-r-order-section cs-r-order-production-timeline" data-testid="cs-order-production-timeline">
              <div class="cs-r-order-section-title"><div><span>生产进度</span><h3>制作时间线</h3></div><b v-if="orderProcess">已完成 {{ mainProductionCompletedCount }} / {{ mainProductionStages.length }} 个主流程</b></div>
              <div v-if="mainProductionStages.length" class="cs-r-order-production-list">
                <article v-for="stage in mainProductionStages" :key="stage.key" class="cs-r-order-production-step" :class="`is-${stage.status.toLowerCase().replaceAll('_','-')}`">
                  <span class="cs-r-order-production-marker" :aria-label="stage.status === 'COMPLETED' ? `${stage.label}已完成` : `${stage.label}，${statusLabel(stage.status)}`">
                    <span v-if="stage.status === 'COMPLETED'" aria-hidden="true">✓</span>
                    <span v-else class="cs-r-order-production-icon" aria-hidden="true">{{ stage.icon }}</span>
                  </span>
                  <div class="cs-r-order-production-content">
                    <header><strong>{{ stage.label }}</strong><b>{{ statusLabel(stage.status) }}</b></header>
                    <p>{{ stage.description }}</p>
                    <time>已处理 {{ stage.resolvedCount }} / {{ stage.nodes.length }} 道内部工序</time>
                    <p v-if="stage.status === 'IN_PROGRESS' && stage.currentNode" class="cs-r-order-production-current-note">当前内部工序：{{ stage.currentNode.process_name }}<template v-if="stage.currentNode.assigned_user_id != null">，负责人 #{{ stage.currentNode.assigned_user_id }}</template></p>
                    <button class="cs-r-order-production-toggle" type="button" :aria-expanded="productionStageExpanded(stage.key)" @click="toggleProductionStage(stage.key)">
                      {{ productionStageExpanded(stage.key) ? '收起内部工序' : `查看${stage.nodes.length}道内部工序` }}
                      <span aria-hidden="true">{{ productionStageExpanded(stage.key) ? '⌃' : '⌄' }}</span>
                    </button>
                    <div v-if="productionStageExpanded(stage.key)" class="cs-r-order-production-sublist">
                      <div v-for="node in stage.nodes" :key="node.node_instance_id" class="cs-r-order-production-substep" :class="`is-${node.node_status.toLowerCase().replaceAll('_','-')}`">
                        <span class="cs-r-order-production-submarker" :aria-label="node.node_status === 'COMPLETED' ? `${node.process_name}已完成` : `${node.process_name}，${processNodeVisual(node).label}`">
                          <span v-if="node.node_status === 'COMPLETED'" aria-hidden="true">✓</span>
                          <span v-else class="cs-r-order-production-icon" aria-hidden="true">{{ processNodeVisual(node).icon }}</span>
                        </span>
                        <div><strong>{{ node.process_name }}</strong><small>{{ node.stage_name }}<template v-if="node.assigned_user_id != null"> · 负责人 #{{ node.assigned_user_id }}</template></small><time v-if="node.completed_at">完成 {{ compactDateTime(node.completed_at) }}</time><time v-else-if="node.started_at">开始 {{ compactDateTime(node.started_at) }}</time><time v-else-if="node.deadline_at">截止 {{ compactDateTime(node.deadline_at) }}</time><time v-else>时间尚未安排</time></div>
                        <b>{{ statusLabel(node.node_status) }}</b>
                      </div>
                    </div>
                  </div>
                </article>
              </div>
              <div v-else class="cs-r-state">当前订单尚未创建生产流程</div>
            </section>

            <section id="cs-order-section-details" class="cs-r-order-section cs-r-order-flow-section cs-r-order-panel-details" data-testid="cs-order-section-details">
              <div class="cs-r-order-section-title"><div><span>临床信息</span><h3>订单资料</h3></div></div>
              <div class="cs-r-order-panel-core"><div><span>产品</span><strong>{{ productLabel(selectedOrder.product_type) }}</strong></div><div><span>牙位</span><strong>{{ orderFormValue(selectedOrder,['tooth_position','tooth','teeth']) || '待确认' }}</strong></div><div><span>色号</span><strong>{{ orderFormValue(selectedOrder,['shade','color']) || '待确认' }}</strong></div></div>
              <div v-if="displayedOrderSpecEntries.length" class="cs-r-spec-grid"><div v-for="item in displayedOrderSpecEntries" :key="item.key"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
              <div v-else class="cs-r-state">当前订单暂无额外临床或制作参数</div>
              <div v-if="selectedOrderClinicalNotes.length" class="cs-r-order-notes"><article v-for="item in selectedOrderClinicalNotes" :key="item.label"><strong>{{ item.label }}</strong><p>{{ item.value }}</p></article></div>
              <div v-else class="cs-r-state">当前订单暂无临床备注</div>
              <div v-if="orderDrawerShowAllDetails" class="cs-r-order-panel-tooth-chart">
                <header><span>完整牙位图</span><strong>{{ orderFormValue(selectedOrder,['tooth_position','tooth','teeth']) || '待确认' }}</strong></header>
                <div class="cs-r-tooth-chart"><div v-for="row in toothRows" :key="row.key"><strong>{{ row.label }}</strong><div><span v-for="tooth in row.teeth" :key="tooth" :class="{ 'is-selected': selectedToothNumbers.includes(tooth) }">{{ tooth }}</span></div></div></div>
              </div>
              <button class="cs-r-order-panel-toggle" type="button" @click="orderDrawerShowAllDetails = !orderDrawerShowAllDetails">{{ orderDrawerShowAllDetails ? '收起完整资料' : '展开完整资料' }}</button>
            </section>

            <section id="cs-order-section-files" class="cs-r-order-section cs-r-order-flow-section cs-r-order-panel-files" data-testid="cs-order-section-files">
              <div class="cs-r-order-section-title"><div><span>资料附件</span><h3>文件与设计稿</h3></div><b>{{ orderFiles.length }} 个文件</b></div>
              <p v-if="orderFilePreviewError" class="cs-r-order-inline-error">{{ orderFilePreviewError }}</p>
              <div v-if="displayedOrderFiles.length" class="cs-r-order-files">
                <button v-for="file in displayedOrderFiles" :key="file.file_id" type="button" :disabled="orderFilePreviewLoading" @click="previewOrderFile(file)">
                  <i>{{ fileTypeLabel(file).slice(0, 1) }}</i>
                  <span><strong>{{ file.original_filename }}</strong><small>{{ fileTypeLabel(file) }} · {{ fileSizeLabel(file.file_size) }} · {{ compactDateTime(file.created_at) }}</small><em>{{ fileSourceLabel(file.source_type) }} · {{ fileVisibilityLabel(file.visibility) }}</em></span>
                  <b>{{ orderFilePreviewLoading ? '读取中' : '预览' }}</b>
                </button>
              </div>
              <div v-else class="cs-r-state">当前订单暂无可查看文件</div>
              <button v-if="sortedOrderFiles.length > 3" class="cs-r-order-panel-toggle" type="button" @click="orderDrawerShowAllFiles = !orderDrawerShowAllFiles">{{ orderDrawerShowAllFiles ? '收起文件' : '查看全部文件' }}</button>
              <div class="cs-r-order-panel-designs">
                <header><div><span>设计资料</span><h3>最近设计稿</h3></div><b>{{ orderDrafts.length }} 个版本</b></header>
                <div v-if="displayedOrderDrafts.length" class="cs-r-order-drafts"><article v-for="draft in displayedOrderDrafts" :key="draft.draft_id"><header><strong>设计稿第 {{ draft.version }} 版</strong><span>{{ statusLabel(draft.status) }}</span></header><p>{{ draft.file_count || fileIds(draft).length }} 个医生可见文件</p><small v-if="draft.doctor_reject_reason">客户反馈：{{ draft.doctor_reject_reason }}</small><button v-if="fileIds(draft).length" type="button" @click="previewDesignDraft(draft)">预览当前版本</button></article></div>
                <div v-else class="cs-r-state">当前订单暂无设计稿版本</div>
                <button class="cs-r-order-panel-route" type="button" @click="navigateFromOrderDrawer('/cs/designs')">进入设计稿管理</button>
              </div>
            </section>

            <section id="cs-order-section-history" class="cs-r-order-section cs-r-order-flow-section cs-r-order-panel-history" data-testid="cs-order-section-history">
              <div class="cs-r-order-section-title"><div><span>处理记录</span><h3>订单时间线</h3></div><b>{{ orderAuditTimeline.length }} 条</b></div>
              <div v-if="displayedOrderAuditTimeline.length" class="cs-r-audit-timeline"><article v-for="entry in displayedOrderAuditTimeline" :key="entry.key"><time>{{ compactDateTime(entry.time) }}</time><span></span><div><strong>{{ entry.label }}</strong><p>{{ entry.detail }}</p></div></article></div>
              <div v-else class="cs-r-state">当前订单暂无可用的真实时间记录</div>
              <button v-if="orderAuditTimeline.length > 5" class="cs-r-order-panel-toggle" type="button" @click="orderDrawerShowAllHistory = !orderDrawerShowAllHistory">{{ orderDrawerShowAllHistory ? '收起记录' : '查看全部记录' }}</button>
            </section>

            <section id="cs-order-section-messages" class="cs-r-order-section cs-r-order-flow-section cs-r-order-panel-messages" data-testid="cs-order-section-messages">
              <div class="cs-r-order-section-title"><div><span>订单会话</span><h3>沟通信息</h3></div><b>{{ orderMessages.length }} 条</b></div>
              <div v-if="displayedOrderMessages.length" class="cs-r-order-messages"><article v-for="message in displayedOrderMessages" :key="message.msg_id" :class="{ 'is-self': message.sender_role === 'CS' }"><span>{{ senderLabel(message.sender_role).slice(0,1) }}</span><div><header><strong>{{ senderLabel(message.sender_role) }}</strong><small>{{ compactDateTime(message.created_at) }}</small></header><p>{{ message.content }}</p><footer><em>{{ messageVisibilityLabel(message.visible_to) }}</em><b>{{ statusLabel(message.review_status) }}</b></footer></div></article></div>
              <div v-else class="cs-r-state">当前订单暂无沟通记录，可直接发送第一条消息</div>
              <button v-if="orderedDrawerMessages.length > 5" class="cs-r-order-panel-toggle" type="button" @click="orderDrawerShowAllMessages = !orderDrawerShowAllMessages">{{ orderDrawerShowAllMessages ? '收起消息' : '查看全部消息' }}</button>
              <p v-if="orderDrawerMessageError" class="cs-r-order-inline-error">{{ orderDrawerMessageError }}</p>
              <div class="cs-r-order-composer"><textarea v-model="orderDrawerMessageDraft" rows="3" placeholder="给医生或客户发送消息……" aria-label="订单沟通消息" @keydown.ctrl.enter.exact.prevent="sendOrderDrawerMessage" @keydown.meta.enter.exact.prevent="sendOrderDrawerMessage"></textarea><div><span>按住控制键或命令键并回车发送</span><button class="is-primary" type="button" :disabled="orderDrawerMessageSending || !orderDrawerMessageDraft.trim()" @click="sendOrderDrawerMessage">{{ orderDrawerMessageSending ? '发送中…' : '发送' }}</button></div></div>
            </section>
          </div>
        </div>
      </el-drawer>

      <el-dialog v-model="orderFilePreviewVisible" width="min(920px, 92vw)" append-to-body destroy-on-close class="cs-r-order-file-dialog">
        <template #header><div><strong>订单文件预览</strong><span>{{ orderFilePreviewName }}</span></div></template>
        <img v-if="orderFilePreviewKind === 'IMAGE'" :src="orderFilePreviewUrl" :alt="orderFilePreviewName">
        <iframe v-else :src="orderFilePreviewUrl" :title="`${orderFilePreviewName}预览`" />
      </el-dialog>
      <StlViewerDialog v-model:visible="orderStlViewerVisible" :source-url="orderFilePreviewUrl" :filename="orderFilePreviewName" />
    </template>

    <template v-else-if="activeRoute === '/cs/information-translation'">
      <header class="cs-r-heading"><div><h1>信息审核/翻译</h1><p>在现有页面完成资料核对、翻译确认和客服初审；通过后进入生产审核。</p></div><span class="cs-r-count">{{ orders.length }} 项任务</span></header>
      <div class="cs-r-workspace is-translation">
        <aside class="cs-r-side-list"><header><strong>处理队列</strong><span>{{ filteredTranslationOrders.length }}</span></header><label class="cs-r-search"><span>⌕</span><input v-model="translationKeyword" type="search" placeholder="搜索客户、患者、病例号、牙位、材料、颜色或系统单号" aria-label="搜索信息审核任务"></label><div class="cs-r-conversation-tabs"><button type="button" :class="{active:translationFilter==='ALL'}" @click="translationFilter='ALL'">全部 {{ translationFilterCounts.ALL }}</button><button type="button" :class="{active:translationFilter==='NOT_STARTED'}" @click="translationFilter='NOT_STARTED'">未进入 {{ translationFilterCounts.NOT_STARTED }}</button><button type="button" :class="{active:translationFilter==='PENDING'}" @click="translationFilter='PENDING'">待初审 {{ translationFilterCounts.PENDING }}</button><button type="button" :class="{active:translationFilter==='CONFIRMED'}" @click="translationFilter='CONFIRMED'">已初审 {{ translationFilterCounts.CONFIRMED }}</button><button type="button" :class="{active:translationFilter==='REJECTED'}" @click="translationFilter='REJECTED'">已退回 {{ translationFilterCounts.REJECTED }}</button></div><button v-for="order in filteredTranslationOrders" :key="order.order_id" type="button" :class="{ active: translationOrderId === order.order_id }" @click="selectTranslationOrder(order)"><strong>{{ csOrderIdentity(order).primary }}</strong><span>{{ csOrderIdentity(order).secondary }}</span><small>{{ informationStatus(order) }} · {{ csOrderIdentity(order).reference }}</small></button><div v-if="filteredTranslationOrders.length === 0" class="cs-r-state">当前筛选下暂无任务</div></aside>
        <section v-if="selectedTranslationOrder" class="cs-r-work-content">
          <header class="cs-r-work-head"><div><h2>{{ csOrderIdentity(selectedTranslationOrder).primary }}</h2><p>{{ csOrderIdentity(selectedTranslationOrder).secondary }} · {{ selectedTranslationOrder.order_no }}</p></div><span class="cs-r-badge is-amber">{{ informationStatus(selectedTranslationOrder) }}</span></header>
          <div class="cs-r-tab-strip"><button type="button" :class="{active:translationTab==='INFO'}" @click="translationTab='INFO'">信息审核</button><button type="button" :class="{active:translationTab==='TRANSLATION'}" @click="translationTab='TRANSLATION'">翻译整理</button><button type="button" :class="{active:translationTab==='FILES'}" @click="translationTab='FILES'">附件 {{ translationFiles.length }}</button><button type="button" :class="{active:translationTab==='HISTORY'}" @click="translationTab='HISTORY'">处理记录</button></div>
          <section class="cs-r-info-band"><div><span>颜色</span><strong>{{ orderFormValue(selectedTranslationOrder,['shade','color']) || '待确认' }}</strong></div><div><span>牙位</span><strong>{{ orderFormValue(selectedTranslationOrder,['tooth_position','tooth','teeth']) || '待确认' }}</strong></div><div><span>材料</span><strong>{{ orderFormValue(selectedTranslationOrder,['material']) || '待确认' }}</strong></div><div><span>产品</span><strong>{{ productLabel(selectedTranslationOrder.product_type) }}</strong></div></section>
          <template v-if="translationTab==='INFO'">
            <section class="cs-r-review-card" data-testid="cs-information-review-card">
              <header>
                <div><span class="cs-r-step-mark">01</span><div><h3>客户提交资料</h3><p>已转换为中文业务字段；客户原始数据保持只读。</p></div></div>
                <span class="cs-r-review-count">{{ translationReviewFields.length }} 项资料</span>
              </header>
              <div v-if="translationReviewFields.length" class="cs-r-review-field-grid">
                <article v-for="field in translationReviewFields" :key="field.key" :data-field-key="field.key" :class="{ 'is-wide': field.long, 'is-missing': field.missing }">
                  <header><span>{{ field.label }}</span><em v-if="field.required">必填</em></header>
                  <p>{{ field.value }}</p>
                </article>
              </div>
              <div v-else class="cs-r-state">该订单尚未提交可审核的业务资料</div>
              <p class="cs-r-review-footnote">这里只展示业务审核所需字段，客户原始提交记录保持不变。</p>
            </section>
            <section class="cs-r-review-card" data-testid="cs-information-review-actions">
              <header>
                <div><span class="cs-r-step-mark">02</span><div><h3>审核与下一步</h3><p>先核对必填资料和制作参数，再决定进入翻译或发起问单。</p></div></div>
                <button type="button" :disabled="aiLoading" @click="checkMissingInfo">{{ aiLoading ? '检查中…' : '智能检查完整性' }}</button>
              </header>
              <div class="cs-r-review-checklist">
                <article v-for="item in translationReviewChecklist" :key="item.label" :class="item.ok ? 'is-ok' : 'is-warning'">
                  <span>{{ item.ok ? '✓' : '!' }}</span><div><strong>{{ item.label }}</strong><small>{{ item.value }}</small></div>
                </article>
              </div>
              <div v-if="missingInfoChecked" class="cs-r-inline-state" :class="missingInfoItems.length ? 'is-warning' : 'is-ok'">
                <strong>{{ missingInfoItems.length ? `发现 ${missingInfoItems.length} 项需要确认` : '智能检查未发现必填资料缺失' }}</strong>
                <span v-for="item in missingInfoItems" :key="item.field_key">{{ item.field_label }}：{{ item.tip }}</span>
              </div>
              <footer>
                <button type="button" @click="openInquiryForOrder(selectedTranslationOrder.order_id)">有疑点，转问单沟通</button>
                <button class="is-primary" type="button" @click="translationTab='TRANSLATION'">下一步：翻译整理</button>
              </footer>
            </section>
          </template>
          <template v-else-if="translationTab==='TRANSLATION'"><section class="cs-r-readonly-note"><strong>需要翻译的客户文字</strong><p class="cs-r-preserve-text">{{ translationSource || '该订单未单独填写外文指示，可跳过翻译并直接确认生产信息。' }}</p></section><section class="cs-r-editor-card"><header><div><h3>翻译确认稿</h3><p>检测到外文时必须生成或填写并人工核对；中文订单可跳过。</p></div><button type="button" :disabled="aiLoading || !translationSource.trim()" @click="generateTranslation">生成翻译草稿</button></header><textarea v-model="translationDraft" rows="5" placeholder="生成后由翻译人员逐项校对" aria-label="翻译草稿"></textarea></section><section class="cs-r-readonly-note" data-testid="cs-customer-requirement-reminder"><strong>客户档案特殊要求（已自动带入确认稿）</strong><div v-if="translationCustomerRequirementItems.length" class="cs-r-requirement-list"><p v-for="item in translationCustomerRequirementItems" :key="item.key"><b>{{ item.label }}</b><span>{{ item.value }}</span></p></div><p v-else>{{ translationClinicPreference ? '当前客户档案未维护特殊要求。' : '客户档案特殊要求暂未读取，请到客户管理核对。' }}</p></section><section class="cs-r-editor-card"><header><div><h3>客服初审生产信息</h3><p>客户档案要求会自动带入；通过初审后保存为订单快照，档案后续修改不会改变本单。</p></div><button type="button" :disabled="aiLoading" @click="generateProductionNote">根据档案重新整理</button></header><textarea v-model="productionNoteDraft" rows="9" placeholder="客户档案要求会自动带入，也可在确认前补充或修正" aria-label="生产信息确认稿"></textarea><footer><button type="button" @click="openInquiryForOrder(selectedTranslationOrder.order_id)">发现疑点，创建问单</button><button class="is-primary" type="button" :disabled="aiLoading || !productionNoteDraft.trim() || selectedTranslationOrder.internal_status !== 'PENDING_CS_REVIEW'" @click="confirmProductionNote">{{ selectedTranslationOrder.internal_status === 'PENDING_CS_REVIEW' ? '确认并通过客服初审' : '客服初审已完成' }}</button></footer></section></template>
          <section v-else-if="translationTab==='FILES'" class="cs-r-editor-card"><header><div><h3>订单附件</h3><p>只显示当前账号可访问的真实文件记录。</p></div><span>{{ translationFiles.length }} 个</span></header><div v-if="translationFiles.length" class="cs-r-record-list"><article v-for="file in translationFiles" :key="file.file_id"><div><strong>{{ file.original_filename }}</strong><span>{{ file.content_type || '类型未记录' }} · {{ file.file_size == null ? '大小未记录' : `${file.file_size} B` }}</span></div><span class="cs-r-badge">{{ statusLabel(file.upload_status) }}</span></article></div><div v-else class="cs-r-state">当前订单没有可查看附件</div></section>
          <section v-else class="cs-r-editor-card"><header><div><h3>处理记录</h3><p>显示当前订单已有的真实时间和客服初审结果。</p></div></header><div class="cs-r-record-list"><article><div><strong>订单建立</strong><span>{{ compactDateTime(selectedTranslationOrder.created_at) }}</span></div><span class="cs-r-badge">{{ statusLabel(selectedTranslationOrder.internal_status) }}</span></article><article><div><strong>最近更新</strong><span>{{ compactDateTime(selectedTranslationOrder.updated_at) }}</span></div><span class="cs-r-badge" :class="hasPassedCsReview(selectedTranslationOrder) ? 'is-green':'is-amber'">{{ informationStatus(selectedTranslationOrder) }}</span></article></div><section class="cs-r-readonly-note"><strong>客服初审确认的制作要求</strong><p>{{ businessProductionNote(selectedTranslationOrder.production_note) || '尚未形成客服初审确认的制作要求。' }}</p></section></section>
        </section>
        <div v-else class="cs-r-state">请选择左侧任务</div>
      </div>
    </template>

    <template v-else-if="activeRoute === '/cs/designs'">
      <header class="cs-r-heading"><div><h1>设计稿进度</h1><p>只读查看已提交医生的设计版本和客户确认结果；技术设计内审由生产负责人处理。</p></div><span class="cs-r-count">{{ designDrafts.length }} 个医生可见版本</span></header>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button type="button" :class="{active:designFilter==='ALL'}" @click="designFilter='ALL'">全部订单</button><button type="button" :class="{active:designFilter==='UPDATED'}" @click="designFilter='UPDATED'">有设计更新</button></div></section>
      <section class="cs-r-table-card">
        <header class="cs-r-table-toolbar"><div><h3>设计订单</h3><span>{{ filteredDesignOrders.length }} / {{ orders.length }} 个订单</span></div><label class="cs-r-search"><span>⌕</span><input v-model="designKeyword" type="search" placeholder="搜索客户、患者、病例号、牙位、材料、颜色或系统单号" aria-label="搜索设计订单"></label></header>
        <table v-if="filteredDesignOrders.length">
          <thead><tr><th>订单识别</th><th>客户单号 / 系统号</th><th>产品</th><th>颜色 / 牙位</th><th>订单阶段</th><th>操作</th></tr></thead>
          <tbody><tr v-for="order in filteredDesignOrders" :key="order.order_id" @click="selectDesignOrder(order.order_id)"><td><strong>{{ csOrderIdentity(order).primary }}</strong><small>{{ csOrderIdentity(order).secondary }}</small></td><td><strong>{{ csOrderIdentity(order).reference }}</strong><small>{{ order.order_no }}</small></td><td>{{ productLabel(order.product_type) }}</td><td><strong>{{ orderFormValue(order,['shade','color']) || '待确认' }}</strong><small>{{ orderFormValue(order,['tooth_position','tooth','teeth']) || '牙位待确认' }}</small></td><td><span class="cs-r-badge is-violet">{{ statusLabel(order.internal_status) }}</span></td><td><button class="cs-r-link" type="button" @click.stop="selectDesignOrder(order.order_id)">查看版本</button></td></tr></tbody>
        </table>
        <div v-else class="cs-r-state"><strong>没有符合条件的设计订单</strong><span>调整搜索条件，或等待生产端上传设计文件。</span></div>
      </section>
      <el-drawer v-model="designDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay">
        <div v-if="selectedDesignOrder" class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>DESIGN REVIEW</small><h2>{{ csOrderIdentity(selectedDesignOrder).primary }}</h2><p>{{ csOrderIdentity(selectedDesignOrder).secondary }} · {{ selectedDesignOrder.order_no }}</p></div><div><span class="cs-r-badge is-violet">{{ designDrafts.length }} 个版本</span><button type="button" aria-label="关闭设计稿详情" @click="designDrawerVisible=false">×</button></div></header>
          <div v-if="designDetailState.loading" class="cs-r-state cs-r-detail-loading"><span class="cs-r-loading-orbit">🎨</span><strong>正在读取设计版本</strong></div>
          <template v-else>
            <section v-if="designDetailState.error" class="cs-r-detail-alert is-danger"><span>!</span><div><strong>设计版本加载失败</strong><p>{{ designDetailState.error }}</p></div><button type="button" @click="selectDesignOrder(selectedDesignOrder.order_id)">重试</button></section>
            <section class="cs-r-info-band"><div><span>产品</span><strong>{{ productLabel(selectedDesignOrder.product_type) }}</strong></div><div><span>颜色</span><strong>{{ orderFormValue(selectedDesignOrder,['shade','color']) || '待确认' }}</strong></div><div><span>牙位</span><strong>{{ orderFormValue(selectedDesignOrder,['tooth_position','tooth','teeth']) || '待确认' }}</strong></div><div><span>诊所</span><strong>{{ selectedDesignOrder.clinic_name }}</strong></div></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🗂️</span><div><h3>医生可见版本与确认记录</h3><p>客服仅跟进进度和沟通，不执行技术设计审核</p></div></div><span>{{ designDrafts.length }} 个</span></div>
              <div v-if="designDrafts.length" class="cs-r-version-list"><article v-for="draft in designDrafts" :key="draft.draft_id"><header><div class="cs-r-version-mark"><span>📐</span><div><strong>设计稿 V{{ draft.version }}</strong><small>{{ draft.file_count || fileIds(draft).length }} 个医生可见文件</small></div></div><span class="cs-r-badge" :class="draft.status.includes('REJECT') ? 'is-red' : draft.status.includes('CONFIRM') ? 'is-green' : 'is-amber'">{{ statusLabel(draft.status) }}</span></header><div v-if="draft.doctor_reject_reason" class="cs-r-version-reason"><strong>客户修改意见</strong><p>{{ draft.doctor_reject_reason }}</p></div><div v-else class="cs-r-version-body"><div><span>文件状态</span><p>{{ fileIds(draft).length ? '已对医生开放' : '版本尚未关联文件' }}</p></div><div><span>客户确认</span><p>{{ draft.status.includes('CONFIRM') ? '客户已明确确认' : '等待客户确认' }}</p></div></div><footer><button type="button" :disabled="fileIds(draft).length === 0" @click="previewDesignDraft(draft)">👁 预览文件</button><button v-if="['PENDING_DOCTOR','PENDING_DOCTOR_REVIEW','PENDING_DOCTOR_CONFIRM'].includes(draft.status)" class="is-primary" type="button" @click="openInquiryForOrder(selectedDesignOrder.order_id)">进入客户确认问单</button><span v-else class="cs-r-action-state">只读进度</span></footer></article></div>
              <div v-else-if="!designDetailState.error" class="cs-r-state"><strong>当前订单还没有设计稿版本</strong><span>设计文件由生产端上传后在这里出现。</span></div>
            </section>
          </template>
        </div>
      </el-drawer>
    </template>

    <template v-else-if="activeRoute === '/cs/inquiries'">
      <header class="cs-r-heading"><div><h1>问单沟通</h1><p>围绕订单事项与客户自由沟通；设计确认和翻译疑点都在这里形成完整记录。</p></div><span class="cs-r-count">{{ attentionItems.length }} 项待关注</span></header>
      <div class="cs-r-chat-layout">
        <aside class="cs-r-conversations"><label class="cs-r-search"><span>⌕</span><input v-model="inquiryKeyword" type="search" placeholder="搜索订单或客户" aria-label="搜索会话"></label><div class="cs-r-conversation-tabs"><button type="button" :class="{active:inquiryTab==='ALL'}" @click="inquiryTab='ALL'">全部会话</button><button type="button" :class="{active:inquiryTab==='WAITING'}" @click="inquiryTab='WAITING'">待回复 {{ attentionItems.length }}</button><button type="button" :class="{active:inquiryTab==='REVIEW'}" @click="inquiryTab='REVIEW'">待审核 {{ pendingMessages.length }}</button></div><button v-for="order in conversationOrders" :key="order.order_id" type="button" :class="{ active: inquiryOrderId === order.order_id }" @click="loadInquiryMessages(order.order_id)"><span class="cs-r-avatar">{{ order.clinic_name.slice(0,1) }}</span><div><strong>{{ order.clinic_name }}</strong><span>{{ order.order_no }} · {{ productLabel(order.product_type) }}</span><small>{{ attentionItems.some(item => item.order_id === order.order_id) ? '有待处理问单事项' : '查看完整会话' }}</small></div><i v-if="attentionItems.some(item => item.order_id === order.order_id)" /></button><div v-if="conversationOrders.length===0" class="cs-r-state">当前口径下没有会话</div></aside>
        <section class="cs-r-chat-panel">
          <header><div><h2>{{ orders.find(item => item.order_id === inquiryOrderId)?.clinic_name || '请选择会话' }}</h2><p>{{ orders.find(item => item.order_id === inquiryOrderId)?.order_no || '从左侧选择订单' }}</p></div><span class="cs-r-badge is-green">平台内沟通</span></header>
          <div class="cs-r-message-timeline">
            <div v-if="inquiryMessages.length === 0" class="cs-r-state">当前订单暂无沟通记录</div>
            <article v-for="message in inquiryMessages" :key="message.msg_id" :class="{ 'is-self': message.sender_role === 'CS', 'is-reviewable': message.review_status === 'PENDING_REVIEW' }">
              <span class="cs-r-avatar">{{ senderLabel(message.sender_role).slice(0,1) }}</span>
              <div>
                <header><strong>{{ senderLabel(message.sender_role) }}</strong><small>{{ compactDateTime(message.created_at) }}</small></header>
                <p>{{ message.content }}</p>
                <small v-if="message.review_status !== 'APPROVED'">{{ statusLabel(message.review_status) }}</small>
                <section v-if="message.review_status === 'PENDING_REVIEW'" class="cs-r-message-review">
                  <label :for="`message-review-note-${message.msg_id}`">审核意见</label>
                  <textarea :id="`message-review-note-${message.msg_id}`" v-model="inquiryReviewNotes[message.msg_id]" rows="2" :aria-label="`消息 ${message.msg_id} 审核意见`" placeholder="通过可选填；退回修改时必填"></textarea>
                  <div>
                    <button class="is-approve" type="button" :disabled="inquiryReviewLoadingId === message.msg_id" @click="reviewInquiryMessage(message, 'APPROVE')">审核通过</button>
                    <button class="is-reject" type="button" :disabled="inquiryReviewLoadingId === message.msg_id || !inquiryReviewNotes[message.msg_id]?.trim()" @click="reviewInquiryMessage(message, 'REJECT')">退回修改</button>
                  </div>
                </section>
              </div>
            </article>
          </div>
          <div class="cs-r-quick-replies"><button type="button" @click="inquiryDraft = '您好，我们正在核对您提交的资料，请稍候。'">资料核对中</button><button type="button" @click="inquiryDraft = '请确认当前设计版本是否可以进入后续制作。'">设计确认</button><button type="button" @click="inquiryDraft = '请补充缺少的信息，我们收到后会继续处理。'">补充资料</button></div>
          <footer class="cs-r-composer"><textarea v-model="inquiryDraft" rows="3" placeholder="输入要发送给客户的内容；快捷回复只会填入，不会自动发送" aria-label="问单消息"></textarea><div><span>仅对客消息会显示给医生/客户</span><button class="is-primary" type="button" :disabled="inquirySending || !inquiryDraft.trim() || !inquiryOrderId" @click="sendInquiryMessage">{{ inquirySending ? '发送中…' : '发送消息' }}</button></div></footer>
        </section>
      </div>
    </template>

    <template v-else-if="activeRoute === '/cs/customers'">
      <CustomerManagementPage
        :token="token"
        :permissions="user?.permissions ?? []"
        :focus-clinic-id="focusTask === 'SEARCH_CUSTOMER' ? focusOrderId : null"
        @focus-consumed="emit('focusConsumed')"
      />
    </template>

    <template v-else-if="activeRoute === '/__legacy-customers' && selectedClinic">
      <header class="cs-r-heading"><div><h1>客户管理</h1><p>查看诊所基础档案、联系人和制作偏好；仅展示接口返回的真实资料状态。</p></div><button class="cs-r-primary" type="button" disabled title="当前后端仅允许管理端创建诊所业务档案">＋ 新增客户</button></header>
      <div v-if="incompleteClinics.length || inactiveClinics.length" class="cs-r-client-alerts">
        <div v-if="incompleteClinics.length" class="cs-r-client-alert is-amber"><span>⚠</span><div><strong>{{ incompleteClinics.length }} 个客户资料待完善</strong><p>仅按真实联系人、联系电话和制作偏好字段判断：{{ incompleteClinics.map(item => item.clinic_name).join('、') }}</p></div></div>
        <div v-if="inactiveClinics.length" class="cs-r-client-alert is-muted"><span>○</span><div><strong>{{ inactiveClinics.length }} 个客户已停用</strong><p>{{ inactiveClinics.map(item => item.clinic_name).join('、') }}</p></div></div>
      </div>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button type="button" :class="{active:customerFilter==='ALL'}" @click="customerFilter='ALL'">全部客户 {{ clinics.length }}</button><button type="button" :class="{active:customerFilter==='INCOMPLETE'}" @click="customerFilter='INCOMPLETE'">资料待完善 {{ incompleteClinics.length }}</button><button type="button" :class="{active:customerFilter==='INACTIVE'}" @click="customerFilter='INACTIVE'">已停用 {{ inactiveClinics.length }}</button></div><label class="cs-r-search"><span>⌕</span><input v-model="customerKeyword" type="search" placeholder="搜索诊所、联系人或电话" aria-label="搜索客户"></label></section>
      <section class="cs-r-customer-grid"><button v-for="clinic in filteredClinics" :key="clinic.clinic_id" type="button" :class="{ active: selectedClinicId === clinic.clinic_id }" @click="selectClinic(clinic.clinic_id)"><header><span class="cs-r-avatar">{{ clinic.clinic_name.slice(0,1) }}</span><div><strong>{{ clinic.clinic_name }}</strong><small>客户档案 #{{ clinic.clinic_id }}</small></div><span class="cs-r-badge" :class="clinic.status === 'INACTIVE' ? 'is-red' : 'is-green'">{{ statusLabel(clinic.status) }}</span></header><div class="cs-r-summary-grid"><div><span>联系人</span><strong>{{ clinic.contact_name || '未设置' }}</strong></div><div><span>联系电话</span><strong>{{ clinic.contact_phone || '未设置' }}</strong></div><div><span>制作偏好</span><strong>{{ clinic.preference_count ? `${clinic.preference_count} 项` : '待完善' }}</strong></div><div><span>建立时间</span><strong>{{ compactDateTime(clinic.created_at) }}</strong></div><div><span>最近更新</span><strong>{{ compactDateTime(clinic.updated_at) }}</strong></div><div><span>档案状态</span><strong>{{ statusLabel(clinic.status) }}</strong></div></div><footer><span>{{ !clinic.contact_name || !clinic.contact_phone || clinic.preference_count === 0 ? '资料待完善' : '基础资料完整' }}</span><b>查看详情 →</b></footer></button><div v-if="filteredClinics.length === 0" class="cs-r-state">没有符合条件的客户</div></section>
      <el-dialog v-model="customerDrawerVisible" width="860px" :show-close="false" align-center class="cs-r-customer-dialog" modal-class="cs-r-drawer-overlay">
        <div v-if="selectedClinic" class="cs-r-customer-detail">
          <header><div class="cs-r-customer-identity"><span class="cs-r-customer-avatar">🏥</span><div><small>CUSTOMER PROFILE · #{{ selectedClinic.clinic_id }}</small><h2>{{ selectedClinic.clinic_name }}</h2><p>{{ selectedClinic.contact_name || '联系人未设置' }} · {{ selectedClinic.contact_phone || '电话未设置' }}</p></div></div><div><span class="cs-r-badge" :class="selectedClinic.status==='ACTIVE'?'is-green':'is-red'">{{ statusLabel(selectedClinic.status) }}</span><button type="button" aria-label="关闭客户详情" @click="customerDrawerVisible=false">×</button></div></header>
          <div v-if="customerDetailState.loading" class="cs-r-state cs-r-detail-loading"><span class="cs-r-loading-orbit">🏥</span><strong>正在读取客户档案</strong></div>
          <div v-else class="cs-r-customer-tab-body cs-r-continuous-detail">
            <section v-if="customerDetailState.error" class="cs-r-detail-alert is-warning"><span>⚠️</span><div><strong>部分客户资料暂未加载</strong><p>{{ customerDetailState.error }}</p></div><button type="button" @click="selectClinic(selectedClinic.clinic_id)">重试</button></section>
            <section class="cs-r-customer-hero"><div><span>🏥</span><div><small>诊所客户</small><strong>{{ selectedClinic.clinic_name }}</strong><p>平台客户档案 #{{ selectedClinic.clinic_id }}</p></div></div><span class="cs-r-badge" :class="selectedClinic.preference_count?'is-green':'is-amber'">{{ selectedClinic.preference_count ? `${selectedClinic.preference_count} 项制作偏好` : '制作偏好待完善' }}</span></section><section class="cs-r-summary-grid cs-r-customer-info-grid"><div><span>主要联系人</span><strong>{{ selectedClinic.contact_name || '尚未设置' }}</strong></div><div><span>联系电话</span><strong>{{ selectedClinic.contact_phone || '尚未设置' }}</strong></div><div><span>电子邮箱</span><strong>当前档案未建模</strong></div><div><span>收货地址</span><strong>当前档案未建模</strong></div><div><span>建立时间</span><strong>{{ compactDateTime(selectedClinic.created_at) }}</strong></div><div><span>最近更新</span><strong>{{ compactDateTime(selectedClinic.updated_at) }}</strong></div></section><section class="cs-r-detail-alert is-info"><span>💡</span><div><strong>资料完整性提示</strong><p>联系人、电话和制作偏好来自真实接口；邮箱与收货地址尚未纳入客服客户档案。</p></div></section>
            <section class="cs-r-capability-empty"><span>👩🏻‍⚕️</span><strong>医生成员接口尚未接入客服端</strong><p>当前不会根据订单中的医生编号虚构姓名、职位或联系方式。</p></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🎨</span><div><h3>制作偏好</h3><p>保存后只影响后续业务，不反向修改历史订单</p></div></div><span>{{ selectedClinic.preference_count }} 项已维护</span></div><div class="cs-r-preference-list"><label v-for="key in clinicPreferenceKeys" :key="key"><span>{{ preferenceLabel(key) }}</span><textarea v-model="clinicPreferenceTextDraft[key]" :rows="key==='note' ? 3 : 2" :placeholder="`填写${preferenceLabel(key)}；结构化内容可使用 JSON`"></textarea></label></div><div v-if="clinicUnknownPreferences.length" class="cs-r-structured-preferences"><header><strong>历史附加偏好</strong><span>只读保留，不参与本次保存</span></header><article v-for="([key,value],index) in clinicUnknownPreferences" :key="key"><span>附加偏好 {{ index + 1 }}</span><pre>{{ preferenceText(value) }}</pre></article></div><footer class="cs-r-modal-actions"><span>最近更新：{{ compactDateTime(clinicPreference?.updated_at) }}</span><button class="cs-r-primary" type="button" :disabled="pageLoading || !clinicPreference" @click="saveClinicPreference">{{ pageLoading ? '保存中…' : '保存制作偏好' }}</button></footer></section>
            <section class="cs-r-capability-empty"><span>📑</span><strong>商务条款尚未建立独立数据模型</strong><p>合同、客户价表、账期、欠款与负责人没有真实接口时不生成演示数据。</p></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">📦</span><div><h3>真实订单记录</h3><p>仅列出当前账号可访问且诊所编号匹配的订单</p></div></div><span>{{ selectedClinicOrders.length }} 单</span></div><div v-if="selectedClinicOrders.length" class="cs-r-record-list"><article v-for="order in selectedClinicOrders" :key="order.order_id"><div><strong>{{ order.order_no }}</strong><span>{{ productLabel(order.product_type) }} · {{ compactDateTime(order.created_at) }}</span></div><span class="cs-r-badge is-violet">{{ statusLabel(order.internal_status) }}</span></article></div><div v-else class="cs-r-state"><strong>当前范围内没有订单记录</strong><span>这不是演示空数据，而是当前账号真实结果。</span></div></section>
          </div>
        </div>
      </el-dialog>
    </template>

    <template v-else-if="activeRoute === '/cs/products'">
      <header class="cs-r-heading"><div><h1>产品管理</h1><p>维护已有产品资料、基础价格和医生下单要求；本期不新增产品。</p></div><span class="cs-r-count">{{ products.length }} 个已有产品</span></header>
      <section class="cs-r-table-card"><header class="cs-r-table-toolbar"><div><h3>已有产品</h3><span>{{ filteredProducts.length }} / {{ products.length }} 个产品</span></div><label class="cs-r-search"><span>⌕</span><input v-model="productKeyword" type="search" placeholder="搜索已有产品" aria-label="搜索产品"></label></header><table v-if="filteredProducts.length"><thead><tr><th>产品名称</th><th>产品类型</th><th>材料规格</th><th>基础价格</th><th>启用状态</th><th>操作</th></tr></thead><tbody><tr v-for="product in filteredProducts" :key="product.product_id" @click="selectProduct(product.product_id)"><td><strong>{{ product.product_name }}</strong><small>#{{ product.product_id }}</small></td><td>{{ productLabel(product.product_type) }}</td><td>{{ product.material_spec || '材料规格待完善' }}</td><td>{{ money(product.base_price_cents,product.currency) }}</td><td><span class="cs-r-badge" :class="product.status === 'ACTIVE' ? 'is-green' : 'is-red'">{{ statusLabel(product.status) }}</span></td><td><button class="cs-r-link" type="button" @click.stop="selectProduct(product.product_id)">查看资料</button></td></tr></tbody></table><div v-else class="cs-r-state">没有符合条件的已有产品</div></section>
      <el-drawer v-model="productDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay">
        <div v-if="selectedProduct" class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>PRODUCT PROFILE</small><h2>{{ selectedProduct.product_name }}</h2></div><div><span class="cs-r-badge" :class="selectedProduct.status==='ACTIVE'?'is-green':'is-red'">{{ statusLabel(selectedProduct.status) }}</span><button type="button" aria-label="关闭产品详情" @click="productDrawerVisible=false">×</button></div></header>
          <div class="cs-r-product-banner"><span>{{ productEmoji(selectedProduct.product_type) }}</span><div><small>{{ productLabel(selectedProduct.product_type) }}</small><strong>{{ selectedProduct.product_name }}</strong><p>产品编号 #{{ selectedProduct.product_id }} · {{ selectedProduct.material_spec || '材料规格待完善' }}</p></div></div>
          <div v-if="productDetailState.loading" class="cs-r-state cs-r-detail-loading"><span class="cs-r-loading-orbit">{{ productEmoji(selectedProduct.product_type) }}</span><strong>正在读取产品配置</strong></div>
          <template v-else>
            <section v-if="productDetailState.error" class="cs-r-detail-alert is-warning"><span>⚠️</span><div><strong>部分产品资料未加载</strong><p>{{ productDetailState.error }}</p></div><button type="button" @click="selectProduct(selectedProduct.product_id)">重试</button></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🧾</span><div><h3>产品资料</h3><p>维护真实产品名称、材料、价格和启用状态</p></div></div></div><div class="cs-r-form-grid cs-r-section-form"><label><span>产品名称</span><input v-model="productEditName"></label><label><span>材料规格</span><input v-model="productEditMaterial"></label><label><span>基础价格（元）</span><input v-model.number="productEditPrice" type="number" min="0" step="0.01"></label><label><span>启用状态</span><select v-model="productEditStatus"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label><label class="is-wide"><span>价格说明</span><textarea v-model="productEditNote" rows="3" placeholder="填写真实价格口径或适用说明"></textarea></label></div><footer class="cs-r-inline-actions"><span>产品类型在本期保持只读，避免破坏已有订单。</span><button class="cs-r-primary" type="button" :disabled="pageLoading || !productEditName.trim()" @click="saveProduct">{{ pageLoading ? '保存中…' : '保存已有产品' }}</button></footer></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">📋</span><div><h3>医生下单要求</h3><p>真实表单配置按显示顺序排列</p></div></div><span>{{ productRequirements.length }} 项</span></div><div v-if="productRequirements.length" class="cs-r-requirement-list"><article v-for="requirement in [...productRequirements].sort((a,b)=>a.sort_order-b.sort_order)" :key="requirement.field_id"><span class="cs-r-requirement-order">{{ String(requirement.sort_order).padStart(2,'0') }}</span><div><strong>{{ requirement.field_label }} <em v-if="requirement.is_required">必填</em></strong><span>{{ fieldTypeLabel(requirement.field_type) }} · {{ requirement.options?.length ? `${requirement.options.length} 个选项` : '无预设选项' }}</span></div><span class="cs-r-badge" :class="requirement.status === 'ACTIVE' ? 'is-green' : 'is-red'">{{ statusLabel(requirement.status) }}</span></article></div><div v-else-if="!productDetailState.error" class="cs-r-state"><strong>尚未配置医生下单要求</strong><span>这里不会填充演示字段。</span></div></section>
            <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🕘</span><div><h3>变更记录</h3><p>当前仅展示接口提供的真实时间</p></div></div></div><div class="cs-r-record-list"><article><div><strong>产品档案建立</strong><span>{{ compactDateTime(selectedProduct.created_at) }}</span></div><span class="cs-r-badge is-violet">真实时间</span></article><article><div><strong>最近一次更新</strong><span>{{ compactDateTime(selectedProduct.updated_at) }}</span></div><span class="cs-r-badge" :class="selectedProduct.status==='ACTIVE'?'is-green':'is-red'">{{ statusLabel(selectedProduct.status) }}</span></article></div><div class="cs-r-capability-empty is-compact"><span>🕘</span><strong>逐字段审计记录尚未接入</strong><p>当前仅展示产品真实建立和更新时间，不编造操作人或变更内容。</p></div></section>
          </template>
        </div>
      </el-drawer>
    </template>

    <template v-else-if="activeRoute === '/cs/billing'">
      <header class="cs-r-heading"><div><h1>账单管理</h1><p>管理真实按单账单和收款事实；月结自动归集仅在后端规则接入后启用。</p></div><span class="cs-r-count">{{ deliveryItems.length }} 条账单关联记录</span></header>
      <div class="cs-r-tab-strip is-large"><button type="button" :class="{active:billingTab==='ORDER'}" @click="billingTab='ORDER'">按单账单</button><button type="button" :class="{active:billingTab==='MONTHLY'}" @click="billingTab='MONTHLY'">月结账单</button></div>
      <section v-if="billingTab === 'ORDER'" class="cs-r-filter-card"><div class="cs-r-segmented"><button type="button" :class="{active:billingFilter==='ALL'}" @click="billingFilter='ALL'">全部账单</button><button type="button" :class="{active:billingFilter==='PENDING'}" @click="billingFilter='PENDING'">待处理</button></div></section>
      <section v-if="billingTab === 'ORDER'" class="cs-r-table-card"><header class="cs-r-table-toolbar"><div><h3>按单账单</h3><span>{{ filteredBillingItems.length }} / {{ deliveryItems.length }} 条记录</span></div></header><table v-if="filteredBillingItems.length"><thead><tr><th>订单</th><th>产品</th><th>账单状态</th><th>收款状态</th><th>配送状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredBillingItems" :key="item.order_id" @click="selectBillingOrder(item.order_id)"><td><strong>{{ item.order_no }}</strong><small>#{{ item.order_id }}</small></td><td>{{ productLabel(item.product_type) }}</td><td><span class="cs-r-badge is-violet">{{ statusLabel(item.bill_status) }}</span></td><td><span class="cs-r-badge" :class="item.payment_status === 'PAID' ? 'is-green' : 'is-amber'">{{ statusLabel(item.payment_status) }}</span></td><td>{{ statusLabel(item.logistics_status) }}</td><td><button class="cs-r-link" type="button" @click.stop="selectBillingOrder(item.order_id)">查看账单</button></td></tr></tbody></table><div v-else class="cs-r-state">当前筛选下没有账单记录</div></section>
      <el-drawer v-model="billingDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay"><div class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>BILLING DETAILS</small><h2>{{ selectedBillingOrder?.order_no || '账单记录' }}</h2></div><div><span class="cs-r-badge" :class="selectedBill?.payment_status==='PAID'?'is-green':'is-amber'">{{ statusLabel(selectedBill?.payment_status) }}</span><button type="button" aria-label="关闭账单详情" @click="billingDrawerVisible=false">×</button></div></header>
        <div v-if="billingDetailState.loading" class="cs-r-state cs-r-detail-loading"><span class="cs-r-loading-orbit">💳</span><strong>正在核对账单与收款记录</strong></div>
        <template v-else>
          <section v-if="billingDetailState.error" class="cs-r-detail-alert is-danger"><span>!</span><div><strong>账单详情加载失败</strong><p>{{ billingDetailState.error }}</p></div><button v-if="selectedBillingOrderId" type="button" @click="selectBillingOrder(selectedBillingOrderId)">重试</button></section>
          <section v-else-if="billingDetailErrors.length" class="cs-r-detail-alert is-warning"><span>⚠️</span><div><strong>部分账务资料未加载</strong><p>{{ billingDetailErrors.join('、') }}暂时不可用。</p></div><button v-if="selectedBillingOrderId" type="button" @click="selectBillingOrder(selectedBillingOrderId)">重试</button></section>
          <section v-if="billingContradiction" class="cs-r-detail-alert is-danger"><span>!</span><div><strong>账务状态存在矛盾</strong><p>{{ billingContradiction }}</p></div></section>
          <section class="cs-r-money-hero" :class="selectedBill?.payment_status==='PAID'?'is-paid':'is-pending'"><div><span>💰</span><div><small>账单金额</small><strong>{{ money(selectedBill?.amount_cents,selectedBill?.currency) }}</strong><p>{{ selectedBill?.bill_id ? `账单 #${selectedBill.bill_id}` : '该订单尚未建立账单' }}</p></div></div><span class="cs-r-badge" :class="selectedBill?.payment_status==='PAID'?'is-green':'is-amber'">{{ statusLabel(selectedBill?.payment_status) }}</span></section><section class="cs-r-summary-grid"><div><span>账单状态</span><strong>{{ statusLabel(selectedBill?.bill_status) }}</strong></div><div><span>账单文件</span><strong>{{ selectedBill?.file_id ? `文件 #${selectedBill.file_id}` : '未上传' }}</strong></div><div><span>累计收款</span><strong>{{ money(receivedAmountCents,selectedBill?.currency) }}</strong></div><div><span>剩余应收</span><strong>{{ money(outstandingAmountCents,selectedBill?.currency) }}</strong></div></section><section v-if="selectedBill?.payment_status==='PAID' && !billingContradiction" class="cs-r-detail-alert is-success"><span>✓</span><div><strong>当前账单已完成收款</strong><p>真实收款记录仍可在下方继续追溯。</p></div></section><section v-else-if="!selectedBill" class="cs-r-capability-empty is-compact"><span>🧾</span><strong>该订单尚未建立真实账单</strong><p>未建立账单前不能登记收款。</p></section>
          <section v-if="!selectedBill?.bill_id" class="cs-r-payment-form cs-r-bill-create-form">
            <header><span>＋</span><div><strong>上传并建立订单账单</strong><small>仅接受本订单 PDF；单文件不超过 500MB</small></div></header>
            <div class="cs-r-form-grid">
              <label class="is-wide"><span>账单 PDF</span><input type="file" accept="application/pdf,.pdf" @change="selectBillDocument"></label>
              <label><span>应收金额（元）</span><input v-model.number="billAmountYuan" type="number" min="0.01" step="0.01" placeholder="0.00"></label>
              <label><span>币种</span><input value="人民币（CNY）" disabled></label>
            </div>
            <p v-if="billDocument" class="cs-r-file-selection">已选择：{{ billDocument.name }}</p>
            <p v-if="billCreateError" class="cs-r-order-inline-error">{{ billCreateError }}</p>
            <button class="cs-r-primary" type="button" :disabled="billCreateLoading || !billDocument || !billAmountYuan || billAmountYuan<=0" @click="createBillForSelectedOrder">{{ billCreateLoading ? '上传并建立中…' : '上传并建立账单' }}</button>
          </section>
          <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🦷</span><div><h3>关联订单</h3><p>金额明细接口未拆分时只显示真实订单资料</p></div></div></div><div v-if="selectedBillingOrder" class="cs-r-detail-field-grid"><article><span>订单编号</span><strong>{{ selectedBillingOrder.order_no }}</strong></article><article><span>诊所</span><strong>{{ selectedBillingOrder.clinic_name }}</strong></article><article><span>产品</span><strong>{{ productLabel(selectedBillingOrder.product_type) }}</strong></article><article><span>订单阶段</span><strong>{{ statusLabel(selectedBillingOrder.internal_status) }}</strong></article><article><span>颜色</span><strong>{{ orderFormValue(selectedBillingOrder,['shade','color']) || '未记录' }}</strong></article><article><span>牙位</span><strong>{{ orderFormValue(selectedBillingOrder,['tooth_position','tooth','teeth']) || '未记录' }}</strong></article></div></section>
          <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">💵</span><div><h3>人工收款记录</h3><p>每笔记录独立保存并保持可追溯</p></div></div><span>{{ selectedPayments.length }} 笔</span></div><div v-if="selectedPayments.length" class="cs-r-payment-list"><article v-for="payment in selectedPayments" :key="payment.payment_id"><span>¥</span><div><strong>{{ money(payment.amount_cents,payment.currency) }}</strong><small>{{ paymentMethodLabel(payment.payment_method) }} · {{ compactDateTime(payment.received_at) }}</small><p>{{ payment.payment_note || '本笔收款无备注' }}</p></div><b>#{{ payment.payment_id }}</b></article></div><div v-else class="cs-r-state"><strong>暂无收款记录</strong><span>这表示当前账本中没有真实人工收款。</span></div><div v-if="canRecordPayment" class="cs-r-payment-form"><header><span>＋</span><div><strong>登记一笔真实收款</strong><small>剩余应收 {{ money(outstandingAmountCents,selectedBill?.currency) }}</small></div></header><div class="cs-r-form-grid"><label><span>收款金额（元）</span><input v-model.number="paymentAmountYuan" type="number" min="0.01" :max="outstandingAmountCents/100" step="0.01" placeholder="0.00"></label><label><span>收款方式</span><select v-model="paymentMethod"><option value="BANK_TRANSFER">银行转账</option><option value="CASH">现金</option><option value="OTHER">其他方式</option></select></label><label class="is-wide"><span>收款备注</span><input v-model="paymentNote" placeholder="填写凭据编号或业务说明"></label></div><button class="cs-r-primary" type="button" :disabled="pageLoading || !paymentAmountYuan || paymentAmountYuan<=0 || paymentAmountYuan*100>outstandingAmountCents" @click="createPaymentRecord">{{ pageLoading ? '保存中…' : '确认登记收款' }}</button></div><div v-else-if="!selectedBill?.bill_id" class="cs-r-capability-empty is-compact"><span>🧾</span><strong>尚未建立真实账单</strong><p>请先由有权限的岗位建立账单，再登记人工收款。</p></div><div v-else class="cs-r-detail-alert" :class="billingContradiction?'is-danger':'is-success'"><span>{{ billingContradiction ? '!' : '✓' }}</span><div><strong>{{ billingContradiction ? '暂不能登记新收款' : '当前没有待登记金额' }}</strong><p>{{ billingContradiction || '账单已收清或剩余应收为零。' }}</p></div></div></section>
          <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🧾</span><div><h3>操作记录</h3><p>仅列出当前接口返回的真实事实</p></div></div></div><div class="cs-r-record-list"><article><div><strong>订单建立</strong><span>{{ compactDateTime(selectedBillingOrder?.created_at) }}</span></div><span class="cs-r-badge is-violet">订单记录</span></article><article v-for="payment in selectedPayments" :key="payment.payment_id"><div><strong>登记收款 {{ money(payment.amount_cents,payment.currency) }}</strong><span>{{ compactDateTime(payment.created_at) }} · {{ paymentMethodLabel(payment.payment_method) }}</span></div><span class="cs-r-badge is-green">已保存</span></article></div><div class="cs-r-capability-empty is-compact"><span>🧾</span><strong>账单状态审计明细尚未接入</strong><p>这里仅列出当前接口返回的真实订单时间与收款记录。</p></div></section>
        </template>
      </div></el-drawer>
      <section v-if="billingTab === 'MONTHLY'" class="cs-r-monthly"><div class="cs-r-monthly-banner"><div><span>自动归集能力</span><h2>月结规则与生成接口尚未接入</h2><p>当前页面不会虚构月结客户、月结金额或生成结果；后端能力完成后再展示真实草稿。</p></div><span class="cs-r-badge is-amber">暂不可用</span></div><section class="cs-r-table-card"><table><thead><tr><th>月结单编号</th><th>结算周期</th><th>客户</th><th>订单数</th><th>应收合计</th><th>已收 / 未收</th><th>账单状态</th><th>生成时间</th><th>操作</th></tr></thead></table><div class="cs-r-state"><strong>暂无真实月结账单</strong><span>计入节点、调整、多币种、跨期及生成接口接入后，系统才会显示自动归集结果。</span></div></section></section>
    </template>

    <template v-else-if="activeRoute === '/cs/delivery'">
      <header class="cs-r-heading"><div><h1>配送管理</h1><p>核对发货门禁、登记承运商与运单，并跟进物流异常。</p></div><span class="cs-r-count">{{ deliveryItems.length }} 个配送订单</span></header>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button v-for="item in [{key:'ALL',label:'全部'},{key:'PENDING',label:'待发货'},{key:'SHIPPED',label:'已发货'},{key:'FOLLOW_UP',label:'异常 / 跟进中'}]" :key="item.key" type="button" :class="{active:deliveryStatus===item.key}" @click="setDeliveryStatus(item.key)">{{ item.label }}</button></div></section>
      <section class="cs-r-table-card"><header class="cs-r-table-toolbar"><div><h3>配送订单</h3><span>{{ filteredDelivery.length }} / {{ deliveryItems.length }} 个订单</span></div></header><table v-if="filteredDelivery.length"><thead><tr><th>订单</th><th>产品</th><th>收款 / 结算</th><th>承运商</th><th>运单号</th><th>配送状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredDelivery" :key="item.order_id" @click="selectDeliveryOrder(item)"><td><strong>{{ item.order_no }}</strong><small>#{{ item.order_id }}</small></td><td>{{ productLabel(item.product_type) }}</td><td><span class="cs-r-badge" :class="item.payment_status==='PAID'||item.payment_status==='NO_PAYMENT_REQUIRED'?'is-green':'is-amber'">{{ statusLabel(item.payment_status) }}</span></td><td>{{ item.carrier || '待登记' }}</td><td>{{ item.tracking_no || '待登记' }}</td><td><span class="cs-r-badge" :class="item.logistics_status==='EXCEPTION'?'is-red':item.logistics_status==='SHIPPED'?'is-green':'is-amber'">{{ statusLabel(item.logistics_status) }}</span></td><td><button class="cs-r-link" type="button" @click.stop="selectDeliveryOrder(item)">查看配送</button></td></tr></tbody></table><div v-else class="cs-r-state">当前筛选下没有配送订单</div></section>
      <el-drawer v-model="deliveryDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay"><div v-if="selectedDelivery" class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>DELIVERY DETAILS</small><h2>{{ selectedDelivery.order_no }}</h2></div><div><span class="cs-r-badge" :class="selectedDelivery.logistics_status==='EXCEPTION'?'is-red':deliveryAlreadyShipped?'is-green':'is-amber'">{{ statusLabel(selectedDelivery.logistics_status) }}</span><button type="button" aria-label="关闭配送详情" @click="deliveryDrawerVisible=false">×</button></div></header>
        <section class="cs-r-delivery-hero"><span>{{ deliveryAlreadyShipped ? '🚚' : '📦' }}</span><div><small>{{ productLabel(selectedDelivery.product_type) }}</small><strong>{{ statusLabel(selectedDelivery.logistics_status) }}</strong><p>{{ selectedDelivery.carrier ? `${selectedDelivery.carrier} · ${selectedDelivery.tracking_no}` : '尚未登记承运商和运单号' }}</p></div></section><section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🚦</span><div><h3>发货门禁</h3><p>真实门禁由提交时后端再次校验</p></div></div></div><div class="cs-r-gate-grid"><article><span>✅</span><div><strong>最终出检</strong><small>提交时由后端检查真实出检记录</small></div><b>服务端校验</b></article><article :class="deliveryPaymentReady?'is-ready':'is-blocked'"><span>{{ deliveryPaymentReady ? '💳' : '⚠️' }}</span><div><strong>收款 / 结算</strong><small>{{ statusLabel(selectedDelivery.payment_status) }}</small></div><b>{{ deliveryPaymentReady ? '已满足' : '待处理' }}</b></article><article><span>📍</span><div><strong>收货地址</strong><small>当前配送接口未返回地址字段</small></div><b>人工核对</b></article></div></section><section class="cs-r-summary-grid"><div><span>账单状态</span><strong>{{ statusLabel(selectedDelivery.bill_status) }}</strong></div><div><span>收款状态</span><strong>{{ statusLabel(selectedDelivery.payment_status) }}</strong></div><div><span>承运商</span><strong>{{ selectedDelivery.carrier || '待登记' }}</strong></div><div><span>运单号</span><strong>{{ selectedDelivery.tracking_no || '待登记' }}</strong></div></section><section v-if="deliveryCanRegister" class="cs-r-drawer-actions"><span>发货后不可在此重复登记。</span><button class="is-primary" type="button" @click="shippingDialogVisible=true; carrierDraft=''; trackingDraft=''">🚀 登记发货</button></section><section v-else-if="selectedDelivery.logistics_status==='PENDING'" class="cs-r-detail-alert is-warning"><span>⚠️</span><div><strong>暂不能登记发货</strong><p>请先完成收款或确认无需收款，再由服务端校验最终出检。</p></div></section><section v-else class="cs-r-detail-alert is-success"><span>✓</span><div><strong>该订单已经登记物流</strong><p>为避免重复覆盖，不再显示发货登记表单。</p></div></section>
        <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🗺️</span><div><h3>物流时间线</h3><p>不伪造承运商实时轨迹</p></div></div></div><div class="cs-r-detail-timeline"><article class="is-done"><span class="cs-r-timeline-node">✓</span><div><strong>订单进入配送列表</strong><small>由订单与物流真实状态生成</small></div></article><article :class="deliveryAlreadyShipped?'is-done':'is-current'"><span class="cs-r-timeline-node">🚚</span><div><strong>登记发货</strong><small>{{ deliveryAlreadyShipped ? `${selectedDelivery.carrier || '承运商未记录'} · ${selectedDelivery.tracking_no || '运单未记录'}` : '等待满足发货门禁' }}</small></div></article><article :class="selectedDelivery.logistics_status==='EXCEPTION'?'is-current':'is-pending'"><span class="cs-r-timeline-node">⚠️</span><div><strong>异常跟进</strong><small>{{ selectedDelivery.last_follow_up_note || '当前没有异常跟进记录' }}</small></div></article><article :class="selectedDelivery.logistics_status==='DELIVERED'?'is-done':'is-pending'"><span class="cs-r-timeline-node">🏁</span><div><strong>客户签收</strong><small>{{ selectedDelivery.logistics_status==='DELIVERED' ? '已签收' : '尚无真实签收记录' }}</small></div></article></div><div class="cs-r-capability-empty is-compact"><span>📡</span><strong>未接入承运商实时轨迹</strong><p>页面只展示平台登记数据和人工跟进，避免制造虚假运输节点。</p></div></section>
        <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🛠️</span><div><h3>配送异常跟进</h3><p>跟进内容仅作为内部记录保存</p></div></div></div><div v-if="deliveryAlreadyShipped" class="cs-r-payment-form"><div class="cs-r-form-grid"><label><span>跟进状态</span><select v-model="logisticsStatusDraft"><option value="EXCEPTION">配送异常</option><option value="FOLLOWING_UP">跟进中</option><option value="RESOLVED">已解决</option></select></label><label class="is-wide"><span>内部跟进说明</span><textarea v-model="logisticsFollowUpDraft" rows="4" placeholder="填写已核实的异常和处理动作"></textarea></label></div><button class="cs-r-primary" type="button" :disabled="pageLoading || !logisticsFollowUpDraft.trim()" @click="saveLogisticsFollowUp">{{ pageLoading ? '保存中…' : '保存异常跟进' }}</button></div><div v-else class="cs-r-capability-empty is-compact"><span>📦</span><strong>订单尚未发货</strong><p>发货前没有配送异常跟进对象。</p></div></section>
        <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🕘</span><div><h3>操作记录</h3><p>仅展示当前配送接口返回的真实记录</p></div></div></div><div class="cs-r-record-list"><article><div><strong>当前配送状态</strong><span>{{ statusLabel(selectedDelivery.logistics_status) }}</span></div><span class="cs-r-badge is-violet">实时读取</span></article><article v-if="selectedDelivery.last_follow_up_note"><div><strong>最近一次内部跟进</strong><span>{{ selectedDelivery.last_follow_up_note }}</span></div><span class="cs-r-badge is-amber">已保存</span></article></div><div class="cs-r-capability-empty is-compact"><span>🕘</span><strong>完整物流审计接口尚未接入</strong><p>操作人和精确事件时间不会用静态内容代替。</p></div></section>
      </div></el-drawer>
      <el-dialog v-model="shippingDialogVisible" width="760px" :show-close="false" :close-on-click-modal="false" align-center class="cs-r-shipping-dialog" modal-class="cs-r-drawer-overlay"><div v-if="selectedDelivery" class="cs-r-shipping-panel"><header><div><small>REGISTER SHIPMENT</small><h2>登记发货 · {{ selectedDelivery.order_no }}</h2><p>选择承运商并填写真实运单号，提交时服务端再次检查最终出检。</p></div><button type="button" aria-label="关闭发货登记" @click="shippingDialogVisible=false">×</button></header><section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🚚</span><div><h3>选择承运商</h3><p>可参考常用承运商视觉卡片，也可手工填写</p></div></div></div><div class="cs-r-carrier-grid"><button v-for="option in carrierOptions" :key="option.name" type="button" :class="{active:carrierDraft===option.name || (option.name==='其他承运商' && carrierDraft && !carrierOptions.slice(0,3).some(item=>item.name===carrierDraft))}" @click="carrierDraft=option.name==='其他承运商'?'':option.name"><span>{{ option.icon }}</span><strong>{{ option.mark }}</strong><small>{{ option.name }}</small></button></div></section><section class="cs-r-form-grid"><label><span>承运商名称</span><input v-model="carrierDraft" placeholder="选择上方承运商或手工填写"></label><label><span>真实运单号</span><input v-model="trackingDraft" placeholder="请核对后填写"></label></section><section class="cs-r-gate-grid is-compact"><article><span>✅</span><div><strong>最终出检</strong><small>服务端强制校验</small></div></article><article class="is-ready"><span>💳</span><div><strong>收款 / 结算</strong><small>{{ statusLabel(selectedDelivery.payment_status) }}</small></div></article><article><span>📍</span><div><strong>收货地址</strong><small>请按线下确认地址发件</small></div></article></section><footer><button type="button" @click="shippingDialogVisible=false">取消</button><button class="cs-r-primary" type="button" :disabled="pageLoading || !carrierDraft.trim() || !trackingDraft.trim()" @click="shipSelectedOrder">{{ pageLoading ? '提交中…' : '确认登记发货' }}</button></footer></div></el-dialog>
    </template>

    <template v-else-if="activeRoute === '/cs/quality'">
      <header class="cs-r-heading"><div><h1>投诉 / 返工跟进</h1><p>查看客户外返、投诉原因、责任归类和处理状态；当前页面只读，不会修改质量记录。</p></div><span class="cs-r-count">{{ filteredQualityRecords.length }} / {{ qualityRecords.length }} 条记录</span></header>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button v-for="item in [{key:'ACTIVE',label:'待跟进'},{key:'ALL',label:'全部'},{key:'PENDING',label:'待处理'},{key:'IN_PROGRESS',label:'处理中'},{key:'RESOLVED',label:'已解决'},{key:'CLOSED',label:'已关闭'}]" :key="item.key" type="button" :class="{active:qualityStatus===item.key}" @click="setQualityStatus(item.key)">{{ item.label }}</button></div></section>
      <section class="cs-r-table-card"><header class="cs-r-table-toolbar"><div><h3>客户外返与投诉记录</h3><span>按最新记录优先显示</span></div></header><table v-if="filteredQualityRecords.length"><thead><tr><th>客户 / 订单</th><th>产品</th><th>问题分类</th><th>责任归类</th><th>处理状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredQualityRecords" :key="item.quality_record_id" @click="selectQualityRecord(item)"><td><strong>{{ item.clinic_name }}</strong><small>{{ item.order_no }}</small></td><td>{{ productLabel(item.product_type) }}</td><td><strong>{{ statusLabel(item.reason_category) }}</strong><small>{{ item.reason_detail || '未补充具体说明' }}</small></td><td>{{ statusLabel(item.responsibility_type) }}</td><td><span class="cs-r-badge" :class="item.status==='CLOSED'||item.status==='RESOLVED'?'is-green':item.status==='IN_PROGRESS'?'is-violet':'is-amber'">{{ statusLabel(item.status) }}</span></td><td>{{ compactDateTime(item.status_updated_at || item.updated_at) }}</td><td><button class="cs-r-link" type="button" @click.stop="selectQualityRecord(item)">查看详情</button></td></tr></tbody></table><div v-else class="cs-r-state"><strong>当前筛选下没有投诉或返工记录</strong><span>可切换“全部”查看已解决和已关闭记录。</span></div></section>
      <el-drawer v-model="qualityDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay"><div v-if="selectedQualityRecord" class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>QUALITY FOLLOW-UP</small><h2>{{ selectedQualityRecord.clinic_name }}</h2><p>{{ selectedQualityRecord.order_no }} · {{ productLabel(selectedQualityRecord.product_type) }}</p></div><div><span class="cs-r-badge" :class="selectedQualityRecord.status==='CLOSED'||selectedQualityRecord.status==='RESOLVED'?'is-green':selectedQualityRecord.status==='IN_PROGRESS'?'is-violet':'is-amber'">{{ statusLabel(selectedQualityRecord.status) }}</span><button type="button" aria-label="关闭投诉返工详情" @click="qualityDrawerVisible=false">×</button></div></header><section class="cs-r-summary-grid"><div><span>记录编号</span><strong>#{{ selectedQualityRecord.quality_record_id }}</strong></div><div><span>关联订单</span><strong>{{ selectedQualityRecord.order_no }}</strong></div><div><span>问题分类</span><strong>{{ statusLabel(selectedQualityRecord.reason_category) }}</strong></div><div><span>责任归类</span><strong>{{ statusLabel(selectedQualityRecord.responsibility_type) }}</strong></div><div><span>检查结论</span><strong>{{ statusLabel(selectedQualityRecord.check_result) }}</strong></div><div><span>返工记录</span><strong>{{ selectedQualityRecord.rework_id ? `#${selectedQualityRecord.rework_id}` : '未关联' }}</strong></div></section><section class="cs-r-readonly-note"><strong>问题说明</strong><p>{{ selectedQualityRecord.reason_detail || '当前记录未补充问题说明。' }}</p></section><section class="cs-r-readonly-note"><strong>跟进备注</strong><p>{{ selectedQualityRecord.status_note || '当前记录未补充跟进备注。' }}</p></section><section class="cs-r-record-list"><article><div><strong>记录建立</strong><span>{{ compactDateTime(selectedQualityRecord.created_at) }}</span></div><span class="cs-r-badge is-violet">真实记录</span></article><article><div><strong>状态更新</strong><span>{{ compactDateTime(selectedQualityRecord.status_updated_at || selectedQualityRecord.updated_at) }}</span></div><span class="cs-r-badge" :class="selectedQualityRecord.status==='CLOSED'||selectedQualityRecord.status==='RESOLVED'?'is-green':'is-amber'">{{ statusLabel(selectedQualityRecord.status) }}</span></article></section><section class="cs-r-drawer-actions"><span>如需联系客户，可进入该订单的问单会话。</span><button class="is-primary" type="button" @click="emit('navigate','/cs/inquiries',selectedQualityRecord.order_id,'WAITING_REPLY')">进入问单沟通</button></section></div></el-drawer>
    </template>

    <template v-else-if="activeRoute === '/cs/outsourcing'">
      <header class="cs-r-heading"><div><h1>外协管理</h1><p>按外发地点与合作方查看已批准外协事项的履约和异常。</p></div><button class="cs-r-primary" type="button" disabled title="当前后端仅提供外协列表和详情">＋ 登记外协</button></header>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button v-for="item in [{key:'ALL',label:'全部外发'},{key:'SENT',label:'已发出'},{key:'DELAYED',label:'已延迟'},{key:'RETURNED',label:'已返回'}]" :key="item.key" type="button" :class="{active:outsourcingStatus===item.key}" @click="setOutsourcingStatus(item.key)">{{ item.label }}</button></div><span>{{ filteredOutsourcing.length }} 个批次</span></section>
      <section class="cs-r-table-card"><header class="cs-r-table-toolbar"><div><h3>外协批次</h3><span>{{ filteredOutsourcing.length }} / {{ outsourcingItems.length }} 个批次</span></div></header><table v-if="filteredOutsourcing.length"><thead><tr><th>外协批次</th><th>关联订单</th><th>外协内容</th><th>合作方 / 外发地点</th><th>预计返回</th><th>履约状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredOutsourcing" :key="item.outsourcing_id" :class="{ 'is-overdue': item.overdue || item.is_overdue }" @click="selectOutsourcing(item)"><td><strong>{{ item.batch_no }}</strong><small>#{{ item.outsourcing_id }}</small></td><td><strong>{{ item.order_no }}</strong><small>{{ productLabel(item.product_type) }}</small></td><td>{{ item.item_name }} × {{ item.quantity }}</td><td><strong>{{ item.supplier_name }}</strong><small>地点档案待关联</small></td><td>{{ compactDateTime(item.expected_return_at) }}</td><td><span class="cs-r-badge" :class="item.overdue||item.is_overdue?'is-red':item.status==='RETURNED'?'is-green':'is-amber'">{{ item.overdue||item.is_overdue?'已超期':statusLabel(item.status) }}</span></td><td><button class="cs-r-link" type="button" @click.stop="selectOutsourcing(item)">查看详情</button></td></tr></tbody></table><div v-else class="cs-r-state">当前没有外协批次</div></section>
      <el-drawer v-model="outsourcingDrawerVisible" size="540px" :with-header="false" class="cs-r-drawer" modal-class="cs-r-drawer-overlay"><div v-if="selectedOutsourcing" class="cs-r-drawer-shell"><header class="cs-r-detail-head"><div><small>OUTSOURCING DETAILS</small><h2>{{ selectedOutsourcing.batch_no }}</h2></div><div><span class="cs-r-badge" :class="selectedOutsourcing.overdue||selectedOutsourcing.is_overdue?'is-red':selectedOutsourcing.status==='RETURNED'?'is-green':'is-amber'">{{ selectedOutsourcing.overdue||selectedOutsourcing.is_overdue?'已超期':statusLabel(selectedOutsourcing.status) }}</span><button type="button" aria-label="关闭外协详情" @click="outsourcingDrawerVisible=false">×</button></div></header>
        <div v-if="outsourcingDetailState.loading" class="cs-r-state cs-r-detail-loading"><span class="cs-r-loading-orbit">🏭</span><strong>正在读取外协批次</strong></div>
        <template v-else>
          <section v-if="outsourcingDetailState.error" class="cs-r-detail-alert is-warning"><span>⚠️</span><div><strong>外协详情未完整加载</strong><p>{{ outsourcingDetailState.error }}</p></div><button type="button" @click="selectOutsourcing(selectedOutsourcing)">重试</button></section>
          <section v-if="selectedOutsourcing.overdue||selectedOutsourcing.is_overdue" class="cs-r-detail-alert is-danger"><span>⏰</span><div><strong>该外协批次已经超过预计返回时间</strong><p>{{ selectedOutsourcing.abnormal_note || '当前没有补充异常说明，需要通过既有线下流程跟进。' }}</p></div></section>
          <section class="cs-r-outsourcing-hero"><span>🏭</span><div><small>{{ selectedOutsourcing.supplier_name || '合作方未记录' }}</small><strong>{{ selectedOutsourcing.item_name }}</strong><p>{{ selectedOutsourcing.order_no }} · {{ productLabel(selectedOutsourcing.product_type) }}</p></div><b>× {{ selectedOutsourcing.quantity }}</b></section><section class="cs-r-summary-grid"><div><span>外协批次</span><strong>{{ selectedOutsourcing.batch_no }}</strong></div><div><span>关联订单</span><strong>{{ selectedOutsourcing.order_no }}</strong></div><div><span>合作方</span><strong>{{ selectedOutsourcing.supplier_name || '未记录' }}</strong></div><div><span>外发地点</span><strong>地点档案尚未关联</strong></div><div><span>发出时间</span><strong>{{ compactDateTime(selectedOutsourcing.sent_at) }}</strong></div><div><span>预计返回</span><strong>{{ compactDateTime(selectedOutsourcing.expected_return_at) }}</strong></div><div><span>实际返回</span><strong>{{ compactDateTime(selectedOutsourcing.actual_return_at) }}</strong></div><div><span>履约状态</span><strong>{{ statusLabel(selectedOutsourcing.status) }}</strong></div></section><section class="cs-r-readonly-note"><strong>异常说明</strong><p>{{ selectedOutsourcing.abnormal_note || '当前真实记录中没有异常说明。' }}</p></section>
          <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🧭</span><div><h3>外协履约进度</h3><p>仅由真实状态和时间字段生成</p></div></div></div><div class="cs-r-detail-timeline"><article class="is-done"><span class="cs-r-timeline-node">📦</span><div><strong>批次建立</strong><small>{{ compactDateTime(selectedOutsourcing.created_at) }}</small></div></article><article :class="selectedOutsourcing.sent_at?'is-done':'is-current'"><span class="cs-r-timeline-node">🚚</span><div><strong>外协发出</strong><small>{{ compactDateTime(selectedOutsourcing.sent_at) }}</small></div></article><article :class="selectedOutsourcing.overdue||selectedOutsourcing.is_overdue?'is-current':'is-pending'"><span class="cs-r-timeline-node">⏳</span><div><strong>预计返回</strong><small>{{ compactDateTime(selectedOutsourcing.expected_return_at) }}</small></div></article><article :class="selectedOutsourcing.actual_return_at?'is-done':'is-pending'"><span class="cs-r-timeline-node">✅</span><div><strong>实际返回</strong><small>{{ compactDateTime(selectedOutsourcing.actual_return_at) }}</small></div></article></div></section>
          <section class="cs-r-capability-empty is-compact"><span>🛠️</span><strong>异常写入能力尚未接入</strong><p>当前接口只能读取异常说明；页面不会提供看似可用但无法保存的按钮。</p></section>
          <section><div class="cs-r-section-title"><div><span class="cs-r-section-emoji">🧾</span><div><h3>操作记录</h3><p>仅展示当前接口提供的真实时间和状态</p></div></div></div><div class="cs-r-record-list"><article><div><strong>外协批次建立</strong><span>{{ compactDateTime(selectedOutsourcing.created_at) }}</span></div><span class="cs-r-badge is-violet">真实时间</span></article><article><div><strong>最近更新</strong><span>{{ compactDateTime(selectedOutsourcing.updated_at) }}</span></div><span class="cs-r-badge" :class="selectedOutsourcing.status==='RETURNED'?'is-green':'is-amber'">{{ statusLabel(selectedOutsourcing.status) }}</span></article></div><div class="cs-r-capability-empty is-compact"><span>🧾</span><strong>完整操作审计尚未接入</strong><p>操作人、批准来源和逐节点变更没有接口时不编造。</p></div></section>
        </template>
      </div></el-drawer>
    </template>

    <template v-else-if="activeRoute === '/cs/settings'">
      <header class="cs-r-heading"><div><h1>设置与账号</h1><p>查看当前登录账号、业务能力和数据范围；账号权限由管理员统一维护。</p></div><span class="cs-r-count">{{ props.user?.username }}</span></header>
      <div class="cs-r-tab-strip is-large"><button type="button" :class="{active:settingsTab==='TEAM'}" @click="settingsTab='TEAM'">当前账号</button><button type="button" :class="{active:settingsTab==='ASSIGNMENT'}" @click="settingsTab='ASSIGNMENT'">客户分配</button><button type="button" :class="{active:settingsTab==='REPLIES'}" @click="settingsTab='REPLIES'">常用回复</button><button type="button" :class="{active:settingsTab==='PREFERENCES'}" @click="settingsTab='PREFERENCES'">通知与偏好</button></div>
      <section v-if="settingsTab==='TEAM'" class="cs-r-settings-card"><header><span class="cs-r-avatar">{{ props.user?.username.slice(0,1).toUpperCase() }}</span><div><h2>{{ props.user?.username }}</h2><p>{{ accountRoleSummary }} · {{ accountDataScope }}</p></div><span class="cs-r-badge is-green">当前账号</span></header><div class="cs-r-summary-grid"><div><span>用户编号</span><strong>{{ props.user?.userId ?? '未返回' }}</strong></div><div><span>登录账号</span><strong>{{ props.user?.username }}</strong></div><div><span>岗位角色</span><strong>{{ accountRoleSummary }}</strong></div><div><span>数据范围</span><strong>{{ accountDataScope }}</strong></div></div><div v-if="businessCapabilities.length" class="cs-r-permission-chips"><span v-for="capability in businessCapabilities" :key="capability">{{ capability }}</span></div><div v-else class="cs-r-state">当前账号尚未配置业务能力</div><div class="cs-r-readonly-note"><strong>权限维护</strong><p>页面仅展示可理解的业务能力；具体权限由管理员在管理端统一配置。</p></div></section>
      <section v-else-if="settingsTab==='ASSIGNMENT'" class="cs-r-settings-card"><header><div><h2>客户分配</h2><p>客服经理可查看与调整客户负责人；普通岗位只读取自身服务范围。</p></div></header><div class="cs-r-state"><strong>客户级分配接口尚未提供</strong><span>当前只有订单负责人字段，页面不会使用模拟客户分配结果。</span></div></section>
      <section v-else-if="settingsTab==='REPLIES'" class="cs-r-settings-card"><header><div><h2>常用回复</h2><p>快捷回复只填入问单输入框，不自动向客户发送。</p></div><button type="button" disabled>＋ 新增回复</button></header><div class="cs-r-state"><strong>尚未建立真实常用回复</strong><span>团队/个人范围、版本与停用接口接入后在此维护。</span></div></section>
      <section v-else class="cs-r-settings-card"><header><div><h2>通知与显示偏好</h2><p>偏好读取和保存接口尚未提供。</p></div></header><div class="cs-r-state"><strong>暂无真实偏好数据</strong><span>接口接入前不显示默认开启、默认关闭或保存成功等模拟状态。</span></div></section>
    </template>

    <template v-else-if="activeRoute === '/cs/notifications'">
      <header class="cs-r-heading"><div><h1>通知中心</h1><p>查看本人业务通知；已读不表示对应业务已经处理。</p></div><button class="cs-r-primary" type="button" :disabled="unreadCount===0" @click="markAllNotifications">全部标为已读</button></header>
      <section class="cs-r-filter-card"><div class="cs-r-segmented"><button v-for="item in notificationFilterOptions" :key="item.key" type="button" :class="{active:notificationFilter===item.key}" @click="notificationFilter=item.key">{{ item.key === 'UNREAD' ? `${item.label} ${unreadCount}` : item.label }}</button></div></section>
      <section class="cs-r-notification-list"><button v-for="notification in filteredNotifications" :key="notification.notification_id" type="button" :class="{unread:!notification.read_at}" @click="markNotification(notification)"><span class="cs-r-notification-dot" /><div><header><strong>{{ notification.message || '业务通知' }}</strong><small>{{ compactDateTime(notification.created_at) }}</small></header><p>{{ notification.order_no ? `关联订单 ${notification.order_no}` : '系统与业务通知' }}</p></div><b>{{ notification.read_at ? '已读' : '查看' }}</b></button><div v-if="filteredNotifications.length===0" class="cs-r-state">当前筛选下没有通知</div></section>
    </template>

    <template v-else-if="activeRoute === '/cs/help'">
      <header class="cs-r-heading"><div><h1>帮助中心</h1><p>按照当前客服端页面提供可直接执行的操作说明。</p></div><span class="cs-r-count">仅顶栏入口</span></header>
      <div class="cs-r-help-layout"><aside><label class="cs-r-search"><span>⌕</span><input v-model="helpKeyword" type="search" placeholder="搜索操作说明" aria-label="搜索帮助"></label><button v-for="topic in filteredHelpTopics" :key="topic.key" type="button" :class="{active:helpTopic===topic.key}" @click="helpTopic=topic.key">{{ topic.label }}</button><div v-if="filteredHelpTopics.length===0" class="cs-r-state">没有匹配的帮助主题</div></aside><section><div class="cs-r-help-hero"><span>当前页面帮助</span><h2>{{ selectedHelpTopic.title }}</h2><p>{{ selectedHelpTopic.intro }}</p></div><article v-for="article in selectedHelpTopic.articles" :key="article.title"><h3>{{ article.title }}</h3><p>{{ article.body }}</p></article><div class="cs-r-readonly-note"><strong>问题反馈</strong><p>当前没有配置真实管理员联系方式，因此不显示虚构电话或邮箱。请通过组织既有内部渠道联系系统管理员。</p></div></section></div>
    </template>

    <template v-else-if="activeRoute === '/cs/search'">
      <header class="cs-r-heading"><div><h1>全局搜索</h1><p>在当前账号业务范围内查找订单、客户、产品和外协记录。</p></div><span class="cs-r-count">{{ searchResults.length }} 条结果</span></header>
      <section class="cs-r-global-search"><label><span>⌕</span><input v-model="searchInput" type="search" placeholder="输入至少两个字符" aria-label="全局搜索关键词" autofocus></label><p>搜索结果不会包含无权访问记录，也不会展示密码、令牌或对象存储路径。</p></section>
      <section v-if="searchInput.trim().length<2" class="cs-r-state"><strong>请输入至少两个字符</strong><span>可以搜索订单号、客户、产品或外协批次号。</span></section>
      <section v-else-if="searchResults.length===0" class="cs-r-state"><strong>没有找到“{{ searchInput }}”的相关结果</strong><span>缩短关键词，或确认当前账号是否负责该客户。</span></section>
      <section v-else class="cs-r-search-results"><button v-for="result in searchResults" :key="`${result.type}-${result.id}`" type="button" @click="openSearchResult(result)"><span class="cs-r-search-type">{{ result.type }}</span><div><strong>{{ result.title }}</strong><p>{{ result.detail }}</p></div><b>打开记录 →</b></button></section>
    </template>
  </div>
</template>
