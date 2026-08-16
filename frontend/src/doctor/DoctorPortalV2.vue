<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createDoctorGateway,
  isDoctorReviewSubmittedRefreshError,
  resolveDoctorGatewayMode
} from './services/doctorGateway'
import DoctorCaseGroupWizard from './DoctorCaseGroupWizard.vue'
import DoctorDynamicFields from './DoctorDynamicFields.vue'
import type {
  ClinicRole,
  DoctorFile,
  DoctorPage,
  DoctorPortalDataset,
  LogisticsRecord,
  MessageThread,
  Money,
  OrderDetail,
  OrderDraftInput,
  OrderReview,
  OrderSummary,
  PatientDetail,
  PatientSummary,
  ProductOption,
  ReviewType
} from './types/contracts'

const StlViewerDialog = defineAsyncComponent(() => import('../components/StlViewerDialog.vue'))

type CurrentUser = {
  username?: string
  userId?: string | number | null
  clinicId?: number | null
  roles?: string[]
  permissions?: string[]
  dataScope?: string | null
}

type DoctorTimelineEntry = {
  key: string
  title: string
  actor: string
  occurredAt: string
  tone: 'order' | 'message' | 'review'
}

type DoctorOrderSpecEntry = {
  key: string
  label: string
  value: string
}

type ProcessConfirmation = {
  confirmation_code: string
  confirmation_name: string
  confirmation_status: 'PLANNED' | 'AWAITING_DOCTOR' | 'CONFIRMED' | 'REJECTED'
  requested_at: string | null
  responded_at: string | null
  doctor_comment: string | null
  waiting_days: number
  overdue: boolean
}

type DeliveryPlanBillItem = {
  item_code: string
  item_name: string
  pricing_status: string
  amount_cents: number | null
  currency: string
  remark: string | null
}

type DeliveryPlan = {
  order_id: number
  order_type: string
  priority_code: string
  shipping_method: string
  base_cycle_days: number
  process_confirmation_count: number
  process_confirmation_days: number
  waiting_days: number
  production_days: number
  transit_days: number
  computed_delivery_date: string
  doctor_requested_delivery_date: string | null
  variance_days: number | null
  variance_flag: string
  delivery_alert: string | null
  delivery_alert_message: string | null
  estimate_status: 'PLACEHOLDER' | 'CONFIRMED'
  placeholder_rules: string[]
  process_confirmations: ProcessConfirmation[]
  try_in: {
    try_in_required: boolean
    try_in_status: string | null
    can_select_final_product: boolean
  }
  bill_items: DeliveryPlanBillItem[]
}

const upperTeeth = ['18', '17', '16', '15', '14', '13', '12', '11', '21', '22', '23', '24', '25', '26', '27', '28']
const lowerTeeth = ['48', '47', '46', '45', '44', '43', '42', '41', '31', '32', '33', '34', '35', '36', '37', '38']

const props = defineProps<{
  token: string
  currentUser: CurrentUser | null
  authenticatedFetch: typeof fetch
}>()

const emit = defineEmits<{
  logout: []
}>()

const pageMetaZh: Record<DoctorPage, { title: string; description: string }> = {
  dashboard: { title: '工作台', description: '查看待处理订单、公开进度与近期业务概览' },
  orders: { title: '订单管理', description: '管理订单资料、外部状态与当前待办' },
  assistant: { title: '订单助手', description: '查询本诊所可查看的订单信息' },
  patients: { title: '患者管理', description: '维护患者档案并关联历史订单' },
  billing: { title: '账单与物流', description: '查看结算、发票退款与物流收货信息' },
  account: { title: '账户设置', description: '管理账户、诊所成员、通知偏好与安全设置' },
  messages: { title: '消息中心', description: '按订单集中处理沟通与确认事项' }
}

const pageMetaEn: Record<DoctorPage, { title: string; description: string }> = {
  dashboard: { title: 'Dashboard', description: 'Review actions, public progress and recent activity' },
  orders: { title: 'Orders', description: 'Manage case files, public status and required actions' },
  assistant: { title: 'Order Assistant', description: 'Query public order information within your access scope' },
  patients: { title: 'Patients', description: 'Maintain patient profiles and linked order history' },
  billing: { title: 'Billing & Delivery', description: 'Review settlements, invoices, refunds and deliveries' },
  account: { title: 'Clinic Settings', description: 'Manage clinic profile, members, notifications and security' },
  messages: { title: 'Messages', description: 'Handle order conversations and review requests' }
}

const roleLabels: Record<ClinicRole, string> = {
  CLINIC_ADMIN: '诊所管理员',
  DOCTOR: '医生',
  RECEPTION: '前台',
  NURSE: '护士'
}

const reviewLabels: Record<ReviewType, string> = {
  CAD_DESIGN: '设计稿确认',
  POST_MILLING_PHOTOS: '切削后照片确认',
  POST_GLAZING_PHOTOS: '上釉后照片确认'
}

const productTypeLabels: Record<string, string> = {
  FIXED_CROWN: '固定修复',
  REGULAR_CROWN: '固定修复',
  FIXED_BRIDGE: '固定桥修复',
  IMPLANT_RESTORATION: '种植修复',
  IMPLANT: '种植修复',
  REMOVABLE_DENTURE: '活动修复',
  REMOVABLE: '活动修复',
  ORTHODONTIC: '正畸产品',
  ORTHODONTICS: '正畸产品',
  CLEAR_ALIGNER: '隐形矫治',
  DIGITAL_DESIGN: '数字化设计'
}

const productNameLabels: Record<string, string> = {
  FIXED_CROWN: '固定牙冠',
  REGULAR_CROWN: '常规牙冠',
  FIXED_BRIDGE: '固定桥',
  IMPLANT_RESTORATION: '种植修复',
  IMPLANT: '种植修复',
  REMOVABLE_DENTURE: '活动义齿',
  REMOVABLE: '活动义齿',
  REMOVABLE_STEEL: '金属支架活动义齿',
  REMOVABLE_INVISIBLE: '隐形活动义齿',
  ORTHODONTIC: '正畸产品',
  ORTHODONTICS: '正畸产品',
  CLEAR_ALIGNER: '隐形矫治',
  DIGITAL_DESIGN: '数字化设计',
  PRECISION_ATTACHMENT: '精密附件',
  TELESCOPIC_CROWN: '套筒冠',
  VENEER_RESTORATION: '贴面修复'
}

type WizardCategoryId = 'fixed' | 'implant' | 'removable' | 'ortho' | 'aligner' | 'design'

const wizardCategories: Array<{ id: WizardCategoryId; icon: string; name: string; note: string; types: string[] }> = [
  { id: 'fixed', icon: '👑', name: '固定修复', note: '牙冠、贴面、嵌体与固定桥', types: ['FIXED_CROWN', 'REGULAR_CROWN', 'FIXED_BRIDGE'] },
  { id: 'implant', icon: '🔩', name: '种植修复', note: '种植冠、桥与个性化基台', types: ['IMPLANT_RESTORATION', 'IMPLANT'] },
  { id: 'removable', icon: '🦷', name: '活动修复', note: '全口义齿与局部义齿', types: ['REMOVABLE_DENTURE', 'REMOVABLE'] },
  { id: 'ortho', icon: '📐', name: '正畸产品', note: '保持器、扩弓器与功能矫治器', types: ['ORTHODONTIC', 'ORTHODONTICS'] },
  { id: 'aligner', icon: '✨', name: '隐形矫治', note: '数字化隐形矫治方案', types: ['CLEAR_ALIGNER'] },
  { id: 'design', icon: '🎨', name: '数字化设计', note: '仅设计、排牙与导板服务', types: ['DIGITAL_DESIGN'] }
]

const wizardToothNumbers = [18, 17, 16, 15, 14, 13, 12, 11, 21, 22, 23, 24, 25, 26, 27, 28, 48, 47, 46, 45, 44, 43, 42, 41, 31, 32, 33, 34, 35, 36, 37, 38]

const doctorOrderFallbackLabels: Record<string, string> = {
  material: '材料',
  shade: '色号',
  shade_system: '比色系统',
  margin: '边缘设计',
  margin_type: '边缘类型',
  contact: '邻接要求',
  contact_requirement: '邻接要求',
  occlusal: '咬合要求',
  occlusion: '咬合要求',
  manufacturing: '制作方式',
  method: '制作方式',
  polish: '抛光要求',
  implant_system: '种植系统',
  implant_brand: '种植体品牌',
  implant_dimension: '种植体规格',
  implant_spec: '种植体规格',
  platform: '平台规格',
  emergence: '穿龈轮廓要求',
  retention: '固位方式',
  units: '单位数',
  pontic: '桥体设计',
  arch: '牙弓范围',
  case_type: '病例类型'
}

const doctorOrderDuplicateFieldKeys = new Set([
  'patient_name', 'patientname', 'patient', '患者姓名',
  'tooth_position', 'toothposition', 'tooth', 'teeth', 'tooth_no', 'tooth_number', '牙位',
  'product', 'product_type', 'product_name', '产品', '产品类型', '产品名称',
  'clinic', 'clinic_name', 'doctor', 'doctor_name', '诊所', '医生'
])

const doctorOrderPatientFieldKeys = new Set([
  'patient_name', 'patientname', 'patient', '患者姓名'
])

const doctorOrderToothFieldKeys = new Set([
  'tooth_position', 'toothposition', 'tooth', 'teeth', 'tooth_no', 'tooth_number', '牙位'
])

const doctorOrderTechnicalFieldPattern = /(^|_)(demo|acceptance|marker|scenario|test|internal|debug|mock|fixture)(_|$)/i

const statusLabels: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  UNDER_REVIEW: '资料审核中',
  NEEDS_INFO: '待补充资料',
  IN_PRODUCTION: '制作中',
  PRODUCTION_COMPLETED: '制作完成',
  READY_TO_DISPATCH: '待发货',
  SHIPPED: '已发货',
  DELIVERED_PENDING_CONFIRMATION: '已送达待确认',
  COMPLETED: '已完成',
  AWAITING_PAYMENT: '待付款',
  DESIGN_REVIEW_REQUIRED: '设计稿待确认',
  POST_MILLING_REVIEW_REQUIRED: '照片待确认',
  SUPPLEMENT_REQUIRED: '补充资料',
  PAYMENT_REQUIRED: '完成付款',
  RECEIPT_CONFIRMATION_REQUIRED: '确认收货',
  NONE: '无需操作',
  PAID: '已支付',
  UNPAID: '未支付',
  ISSUED: '已出账',
  UPLOADED: '已上传',
  PENDING_PAYMENT: '待支付',
  PARTIALLY_PAID: '部分支付',
  OPEN: '待结清',
  SETTLED: '已结清',
  PENDING_REVIEW: '待确认',
  PENDING: '待确认',
  IN_TRANSIT: '运输中',
  ACTIVE: '正常',
  PENDING_ACTIVATION: '待激活',
  DISABLED: '已停用',
  APPROVED: '已同意',
  REJECTED: '已驳回',
  UNKNOWN: '暂未记录',
  PENDING_QUOTE: '待报价',
  DIRECT: '已发送',
  SUPERSEDED: '已被新版本替代',
  REVISION_REQUESTED: '待修改',
  REVISING: '修改中',
  WAITING: '等待提交',
  NOT_REQUESTED: '未启用'
}

const categoryLabels: Record<string, string> = {
  ORDER: '订单', REVIEW: '确认', MESSAGE: '消息', BILLING: '账单', LOGISTICS: '物流', SYSTEM: '系统'
}

const activePage = ref<DoctorPage>('dashboard')
const loading = ref(true)
const loadError = ref('')
const dataset = ref<DoctorPortalDataset | null>(null)
const gateway = createDoctorGateway({
  token: props.token,
  displayName: props.currentUser?.username || '医生',
  clinicName: '当前诊所',
  authenticatedFetch: props.authenticatedFetch
})
const dataMode = resolveDoctorGatewayMode()

watch(() => props.token, (nextToken) => {
  gateway.updateToken(nextToken)
})

const activeRole = ref<ClinicRole>('DOCTOR')
const roleMenuOpen = ref(false)
const availableRoles = ref<ClinicRole[]>(['DOCTOR'])
const portalLanguage = ref<'ZH' | 'EN'>('ZH')
const globalKeyword = ref('')
const globalSearchOpen = ref(false)
const notificationOpen = ref(false)
const notificationKeyword = ref('')
const notificationFilter = ref<'ALL' | 'UNREAD' | 'READ'>('ALL')

const orderKeyword = ref('')
const orderStatus = ref('ALL')
const orderProduct = ref('ALL')
const orderQuick = ref('ALL')
const orderDoctor = ref('ALL')
const orderTag = ref('ALL')
const orderDateFrom = ref('')
const orderDateTo = ref('')
const orderFiltersExpanded = ref(false)
const orderPage = ref(1)
const selectedOrderIds = ref<string[]>([])
const orderDrawerOpen = ref(false)
const selectedOrder = ref<OrderDetail | null>(null)
const orderDetailLoading = ref(false)
const orderDrawerMessageDraft = ref('')
const orderDrawerMessageSending = ref(false)

// TASK-034 F 批次：交期计划、过程确认与试戴。
// estimate_status = PLACEHOLDER 表示交期用了客户尚未确认的标准周期，界面必须标「待确认」——
// 这条不是装饰，占位值表现成正式承诺交期就是对客户的误导。
const deliveryPlan = ref<DeliveryPlan | null>(null)
const deliveryPlanLoading = ref(false)
const deliveryPlanBusy = ref(false)
const requestedDeliveryDateDraft = ref('')

const patientKeyword = ref('')
const patientStatus = ref<'ALL' | PatientSummary['treatment_status']>('ALL')
const wizardPatientKeyword = ref('')
const patientDrawerOpen = ref(false)
const patientDrawerTab = ref<'basic' | 'orders' | 'history'>('basic')
const selectedPatient = ref<PatientDetail | null>(null)
const patientLoading = ref(false)
const patientDialogOpen = ref(false)
const patientEditMode = ref(false)
const patientSaving = ref(false)
const newPatient = reactive({
  name: '', age: '', gender: '', dateOfBirth: '', phone: '', email: '', medicalNotes: '',
  treatmentStatus: 'IN_TREATMENT' as PatientSummary['treatment_status'], treatmentStartedAt: '',
  treatmentEndedAt: '', oralDescription: '', tags: ''
})

const billingTab = ref<'perOrder' | 'monthly' | 'invoiceRefund' | 'logistics'>('perOrder')
const billingStatus = ref<'ALL' | 'UNPAID' | 'PAID' | 'OVERDUE'>('ALL')
const bulkInvoiceDownloading = ref(false)
const downloadableInvoiceRefunds = computed(() => dataset.value?.invoiceRefunds
  .filter((record) => Boolean(record.record_id)) ?? [])
const logisticsDrawerOpen = ref(false)
const selectedLogistics = ref<LogisticsRecord | null>(null)

const messageKeyword = ref('')
const messageFilter = ref<'ALL' | 'UNREAD' | 'READ'>('ALL')
const activeThreadId = ref('')
const messageDraft = ref('')
const sendingMessage = ref(false)
const rejectDialogOpen = ref(false)
const rejectReason = ref('')
const reviewTarget = ref<{ orderId: string; review: OrderReview } | null>(null)

const assistantQuestion = ref('')
const assistantLoading = ref(false)
const assistantMessages = ref<Array<{ role: 'SELF' | 'ASSISTANT'; content: string; orderIds?: string[] }>>([
  { role: 'ASSISTANT', content: '您好，我可以帮您查询订单公开进度、待办、账单与物流信息。' }
])

const accountTab = ref<'profile' | 'members' | 'notifications' | 'security'>('profile')
const memberDialogOpen = ref(false)
const newMember = reactive({ displayName: '', email: '', role: 'DOCTOR' as ClinicRole, billing: 'VIEW', logistics: 'VIEW' })
const passwordForm = reactive({ current: '', next: '', confirm: '' })

const wizardOpen = ref(false)
const wizardInitialPatientId = ref('')
const wizardInitialGroupId = ref<number | null>(null)
const wizardStep = ref(1)
const wizardSaving = ref(false)
const wizardSubmitting = ref(false)
const wizardUploading = ref(false)
const wizardNotice = ref('')
const wizardUploadedFileSignatures = ref<Record<string, string>>({})
const wizardCategory = ref<WizardCategoryId>('fixed')
const wizardSelectedTeeth = ref<number[]>([])
const wizardToothMode = ref<'RESTORE' | 'MISSING'>('RESTORE')
const wizardDragActive = ref(false)
const wizard = reactive<OrderDraftInput>({
  draftOrderId: undefined,
  patientId: '',
  productId: '',
  productType: '',
  caseFields: { tooth: '', case_note: '' },
  dynamicFields: {},
  reviewOptions: [],
  files: []
})

const viewerOpen = ref(false)
const viewerFile = ref<DoctorFile | null>(null)
const filePreviewOpen = ref(false)
const filePreview = ref<DoctorFile | null>(null)
const filePreviewLoading = ref(false)
const reviewSubmitting = ref(false)

const navGroups = computed(() => [
  {
    label: uiText('工作台', 'Workspace'),
    items: [
      { page: 'dashboard' as DoctorPage, label: uiText('首页概览', 'Dashboard'), icon: '⌂' },
      { page: 'orders' as DoctorPage, label: uiText('我的订单', 'My Orders'), icon: '▤' },
      ...(activeRole.value === 'DOCTOR' ? [{ page: 'assistant' as DoctorPage, label: uiText('订单助手', 'Order Assistant'), icon: '✦' }] : []),
      { page: 'patients' as DoctorPage, label: uiText('患者档案', 'Patients'), icon: '♙' },
      { page: 'billing' as DoctorPage, label: uiText('账单中心', 'Billing'), icon: '▧' }
    ]
  },
  {
    label: uiText('诊所与账户', 'Clinic & Account'),
    items: [
      { page: 'messages' as DoctorPage, label: uiText('消息中心', 'Messages'), icon: '✉' },
      { page: 'account' as DoctorPage, label: uiText('诊所设置', 'Clinic Settings'), icon: '⚙' }
    ]
  }
])

const currentMeta = computed(() => (portalLanguage.value === 'EN' ? pageMetaEn : pageMetaZh)[activePage.value])
const account = computed(() => dataset.value?.account)
const unreadCount = computed(() => dataset.value?.notifications.filter((item) => !item.read).length ?? 0)
const canCreateOrder = computed(() => activeRole.value === 'DOCTOR')
const canManageMembers = computed(() => activeRole.value === 'CLINIC_ADMIN')
const canReview = computed(() => activeRole.value === 'DOCTOR')

const orderRows = computed(() => {
  const keyword = orderKeyword.value.trim().toLowerCase()
  return (dataset.value?.orders ?? []).filter((order) => {
    const matchesKeyword = !keyword || [order.order_no, order.doctor_name, order.patient_name, order.patient_code, order.clinic_name, order.product_name, ...order.tags].join(' ').toLowerCase().includes(keyword)
    const matchesStatus = orderStatus.value === 'ALL' || order.external_status === orderStatus.value
    const matchesProduct = orderProduct.value === 'ALL' || order.product_type === orderProduct.value
    const matchesDoctor = orderDoctor.value === 'ALL' || order.doctor_name === orderDoctor.value
    const matchesTag = orderTag.value === 'ALL' || order.tags.includes(orderTag.value)
    const createdDate = doctorLocalDateKey(order.created_at)
    const matchesDate = (!orderDateFrom.value || createdDate >= orderDateFrom.value) && (!orderDateTo.value || createdDate <= orderDateTo.value)
    const matchesQuick = orderQuick.value === 'ALL'
      || (orderQuick.value === 'TODO' && order.current_action !== 'NONE')
      || (orderQuick.value === 'DUE' && isDueSoon(order))
      || (orderQuick.value === 'DRAFT' && order.external_status === 'DRAFT')
      || (orderQuick.value === 'DELIVERY' && ['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(order.external_status))
      || (orderQuick.value === 'PAYMENT' && order.current_action === 'PAYMENT_REQUIRED')
    return matchesKeyword && matchesStatus && matchesProduct && matchesDoctor && matchesTag && matchesDate && matchesQuick
  })
})

const orderDoctors = computed(() => Array.from(new Set((dataset.value?.orders ?? []).map((item) => item.doctor_name))))
const orderTags = computed(() => Array.from(new Set((dataset.value?.orders ?? []).flatMap((item) => item.tags))))
const orderProductTypes = computed(() => Array.from(new Set((dataset.value?.products ?? []).map((item) => item.product_type))))

const orderPageSize = 6
const pagedOrders = computed(() => orderRows.value.slice((orderPage.value - 1) * orderPageSize, orderPage.value * orderPageSize))

const patientRows = computed(() => {
  const keyword = patientKeyword.value.trim().toLowerCase()
  return (dataset.value?.patients ?? []).filter((patient) => {
    const matchesKeyword = !keyword || [patient.patient_name, patient.patient_code, patient.doctor_name, patient.clinic_name, patient.phone, patient.email, patient.oral_description, ...patient.tags].join(' ').toLowerCase().includes(keyword)
    return matchesKeyword && (patientStatus.value === 'ALL' || patientTreatmentState(patient) === patientStatus.value)
  })
})

const billingRows = computed(() => (dataset.value?.bills ?? []).filter((item) => {
  if (billingStatus.value === 'ALL') return true
  if (billingStatus.value === 'OVERDUE') return item.outstanding.amount_minor > 0 && item.due_at < dashboardToday.value
  if (billingStatus.value === 'UNPAID') return item.outstanding.amount_minor > 0
  return item.payment_status === billingStatus.value
}))

const billingStats = computed(() => {
  const bills = dataset.value?.bills ?? []
  const sum = (selector: (item: DoctorPortalDataset['bills'][number]) => number) => bills.reduce((total, item) => total + selector(item), 0)
  const currency = bills[0]?.amount.currency ?? 'CNY'
  const moneyOf = (amount_minor: number): Money => ({ amount_minor, currency })
  return [
    { label: '本期账单', value: money(moneyOf(sum((item) => item.amount.amount_minor))), note: `${bills.length} 笔订单`, tone: 'blue' },
    { label: '待支付', value: money(moneyOf(sum((item) => item.outstanding.amount_minor))), note: '请在到期日前完成', tone: 'amber' },
    { label: '已逾期', value: money(moneyOf(sum((item) => item.due_at < dashboardToday.value ? item.outstanding.amount_minor : 0))), note: '逾期账单需优先处理', tone: 'rose' },
    { label: '年度已支付', value: money(moneyOf(sum((item) => item.paid.amount_minor))), note: '本年度累计', tone: 'green' },
    { label: '账户余额', value: money(moneyOf(0)), note: '暂无可用抵扣余额', tone: 'violet' }
  ]
})

const filteredThreads = computed(() => {
  const keyword = messageKeyword.value.trim().toLowerCase()
  return (dataset.value?.threads ?? []).filter((thread) => {
    const matchesRead = messageFilter.value === 'ALL' || (messageFilter.value === 'UNREAD' ? thread.unread : !thread.unread)
    const matchesKeyword = !keyword || [thread.order_no, thread.patient_name, thread.product_name, thread.latest_message, ...thread.messages.map((message) => message.content)].join(' ').toLowerCase().includes(keyword)
    return matchesRead && matchesKeyword
  })
})

const activeThread = computed<MessageThread | null>(() => {
  return filteredThreads.value.find((thread) => thread.thread_id === activeThreadId.value) ?? filteredThreads.value[0] ?? null
})

const filteredNotifications = computed(() => {
  const keyword = notificationKeyword.value.trim().toLowerCase()
  return (dataset.value?.notifications ?? []).filter((item) => {
    const matchesRead = notificationFilter.value === 'ALL' || (notificationFilter.value === 'UNREAD' ? !item.read : item.read)
    return matchesRead && (!keyword || `${item.title} ${item.summary}`.toLowerCase().includes(keyword))
  })
})

const globalResults = computed(() => {
  const keyword = globalKeyword.value.trim().toLowerCase()
  if (!keyword) return { orders: [] as OrderSummary[], patients: [] as PatientSummary[] }
  return {
    orders: (dataset.value?.orders ?? []).filter((item) => [item.order_no, item.patient_name, item.patient_code, item.product_name].join(' ').toLowerCase().includes(keyword)).slice(0, 6),
    patients: (dataset.value?.patients ?? []).filter((item) => [item.patient_name, item.patient_code, item.doctor_name].join(' ').toLowerCase().includes(keyword)).slice(0, 6)
  }
})

const selectedProduct = computed<ProductOption | null>(() => dataset.value?.products.find((item) => item.product_id === wizard.productId) ?? null)
const selectedWizardCategory = computed(() => wizardCategories.find((item) => item.id === wizardCategory.value) ?? wizardCategories[0])
const wizardCategoryProducts = computed(() => (dataset.value?.products ?? []).filter((item) => selectedWizardCategory.value.types.includes(item.product_type)))
const wizardCategoryAvailable = computed(() => wizardCategoryProducts.value.length > 0)
const selectedOrderProduct = computed<ProductOption | null>(() => dataset.value?.products.find((item) => item.product_type === selectedOrder.value?.product_type) ?? null)
const selectedOrderFieldLabels = computed(() => new Map(
  (selectedOrderProduct.value?.form_fields ?? []).map((field) => [normalizeDoctorOrderFieldKey(field.key), field.label.trim()])
))
const selectedWizardPatient = computed(() => dataset.value?.patients.find((item) => item.patient_id === wizard.patientId) ?? null)
const wizardPatientRows = computed(() => {
  const keyword = wizardPatientKeyword.value.trim().toLowerCase()
  return (dataset.value?.patients ?? []).filter((patient) => !keyword || [patient.patient_name, patient.patient_code, patient.doctor_name, ...patient.tags].join(' ').toLowerCase().includes(keyword))
})
const selectedProductFields = computed(() => (selectedProduct.value?.form_fields ?? []).filter((field) => {
  const normalizedKey = normalizeDoctorOrderFieldKey(field.key)
  return !doctorOrderPatientFieldKeys.has(normalizedKey) && !doctorOrderToothFieldKeys.has(normalizedKey)
}))
const wizardStlCount = computed(() => wizard.files.filter((candidate) => candidate.kind === 'STL').length)
const wizardSubmitDisabled = computed(() =>
  wizardSubmitting.value
  || wizardSaving.value
  || wizardUploading.value
  || wizardMissingForStep(4).length > 0
)
const clinicRoleOptions = computed(() => (Object.entries(roleLabels) as Array<[ClinicRole, string]>).map(([value, name]) => ({ value, name })))
const filePreviewName = computed(() => filePreview.value?.name ?? '')
const selectedOrderToothText = computed(() => orderToothText(selectedOrder.value))
const selectedOrderTeeth = computed(() => new Set(parseDoctorTeeth(selectedOrderToothText.value)))
const selectedOrderClinicalNotes = computed(() => {
  const entries = Object.entries(selectedOrder.value?.form_snapshot ?? {})
    .filter(([key, value]) => isClinicalNoteKey(key) && !isTechnicalDoctorOrderField(key) && value.trim())
    .map(([, value]) => value.trim())
  return [...new Set(entries)].join('\n')
})
const selectedOrderSpecEntries = computed<DoctorOrderSpecEntry[]>(() => Object.entries(selectedOrder.value?.form_snapshot ?? {})
  .flatMap(([key, value]) => {
    const label = doctorOrderFieldLabel(key)
    const displayValue = value.trim()
    return label && displayValue && !isClinicalNoteKey(key) ? [{ key, label, value: displayValue }] : []
  }))
const canSendOrderDrawerMessage = computed(() => selectedOrder.value?.allowed_actions.includes('SEND_MESSAGE') ?? false)
const orderTimelineItems = computed<DoctorTimelineEntry[]>(() => {
  const order = selectedOrder.value
  if (!order) return []
  const items: DoctorTimelineEntry[] = [{
    key: `created-${order.order_id}`,
    title: '订单已创建',
    actor: order.doctor_name,
    occurredAt: order.created_at,
    tone: 'order'
  }]

  order.progress.forEach((progress) => {
    if (!progress.occurred_at) return
    items.push({
      key: `progress-${progress.key}-${progress.occurred_at}`,
      title: progress.label,
      actor: progress.note || '订单服务',
      occurredAt: progress.occurred_at,
      tone: 'order'
    })
  })

  order.messages.forEach((message) => {
    items.push({
      key: `message-${message.message_id}`,
      title: '消息已发送',
      actor: message.sender === 'SELF' ? order.doctor_name : '订单服务',
      occurredAt: message.sent_at,
      tone: 'message'
    })
  })

  order.reviews.forEach((review) => review.versions.forEach((version) => {
    items.push({
      key: `review-${review.review_id}-${version.version}`,
      title: `${reviewLabel(review.review_type)} V${version.version}`,
      actor: `订单服务 · ${label(version.status)}`,
      occurredAt: version.submitted_at,
      tone: 'review'
    })
  }))

  return items
    .filter((item) => item.occurredAt && item.occurredAt !== '-')
    .sort((left, right) => doctorDateValue(right.occurredAt) - doctorDateValue(left.occurredAt))
    .slice(0, 16)
})
const dashboardAttentionCount = computed(() => (dataset.value?.orders ?? []).filter((item) => item.current_action !== 'NONE').length)
const pendingTaskOrders = computed(() => (dataset.value?.orders ?? []).filter((item) => item.current_action !== 'NONE').slice(0, 5))
const dashboardToday = computed(() => new Date().toLocaleDateString('sv-SE'))
const dashboardGreeting = computed(() => {
  const hour = new Date().getHours()
  const greeting = hour < 12 ? '早上好' : hour < 18 ? '下午好' : '晚上好'
  return `${greeting}，${account.value?.display_name || props.currentUser?.username || '医生'} 👋`
})
const dashboardContext = computed(() => {
  const now = new Date()
  const date = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  }).format(now)
  const weekday = new Intl.DateTimeFormat('zh-CN', { weekday: 'long' }).format(now)
  return `${date} ${weekday} · ${account.value?.clinic_name || '当前诊所'} · ${dashboardAttentionCount.value} 项需要处理`
})
const dashboardStats = computed(() => {
  const items = dataset.value?.orders ?? []
  return [
    { key: 'today', label: '今日订单', value: items.filter((item) => doctorLocalDateKey(item.created_at) === dashboardToday.value).length, note: '今日提交与草稿', tone: 'blue', icon: '📦' },
    { key: 'production', label: '制作中', value: items.filter((item) => item.external_status === 'IN_PRODUCTION').length, note: '公开进度更新', tone: 'indigo', icon: '🔬' },
    { key: 'delivery', label: '即将送达', value: items.filter((item) => ['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(item.external_status)).length, note: '配送与收货', tone: 'amber', icon: '🚀' },
    { key: 'reply', label: '待回复', value: dataset.value?.threads.filter((item) => item.unread).length ?? 0, note: '消息与沟通', tone: 'rose', icon: '⚠️' },
    { key: 'review', label: '设计待确认', value: items.filter((item) => item.current_action.includes('REVIEW')).length, note: '确认后继续制作', tone: 'violet', icon: '✏️' },
    { key: 'due', label: '到期提醒', value: items.filter((item) => isDueSoon(item)).length, note: '预计日期临近', tone: 'orange', icon: '🕐' }
  ]
})
const dashboardUpcomingOrders = computed(() => (dataset.value?.orders ?? [])
  .filter((item) => item.due_at !== '-' && item.external_status !== 'COMPLETED')
  .sort((left, right) => left.due_at.localeCompare(right.due_at))
  .slice(0, 3))
const dashboardDeliveryOrders = computed(() => dashboardUpcomingOrders.value.filter((item) =>
  ['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(item.external_status)
))
const dashboardDueOrders = computed(() => dashboardUpcomingOrders.value.filter((item) =>
  !['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(item.external_status)
))
const dashboardWeeklyCounts = computed(() => {
  const counts = [0, 0, 0, 0, 0, 0]
  const today = new Date(`${dashboardToday.value}T12:00:00`)
  for (const order of dataset.value?.orders ?? []) {
    const created = parseDoctorDateTime(order.created_at)
    if (Number.isNaN(created.getTime())) continue
    const weeksAgo = Math.floor((today.getTime() - created.getTime()) / 604800000)
    const bucket = 5 - weeksAgo
    if (bucket >= 0 && bucket < counts.length) counts[bucket] += 1
  }
  return counts
})
const dashboardTrendPoints = computed(() => {
  return dashboardWeeklyCounts.value.map((value, index) => `${24 + index * 103},${104 - Math.round(value / dashboardTrendMax.value * 72)}`).join(' ')
})
const dashboardTrendMax = computed(() => Math.max(1, ...dashboardWeeklyCounts.value))

function money(value: Money | null | undefined): string {
  if (!value) return '价格待确认'
  const symbol = value.currency === 'CNY' ? '¥' : `${value.currency} `
  return `${symbol}${(value.amount_minor / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
}

function uiText(zh: string, en: string): string {
  return portalLanguage.value === 'EN' ? en : zh
}

function setPortalLanguage(language: 'ZH' | 'EN') {
  portalLanguage.value = language
  ElMessage.success(language === 'EN' ? 'English navigation enabled' : '已切换为中文界面')
}

function compactDoctorDateTime(value?: string | null): string {
  if (!value || value === '-') return '时间未记录'
  const date = parseDoctorDateTime(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
  }).format(date)
}

function preciseDoctorDateTime(value?: string | null): string {
  if (!value || value === '-') return '时间未记录'
  const date = parseDoctorDateTime(value)
  if (Number.isNaN(date.getTime())) return value
  const twoDigits = (candidate: number) => String(candidate).padStart(2, '0')
  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()} ${twoDigits(date.getHours())}:${twoDigits(date.getMinutes())}:${twoDigits(date.getSeconds())}`
}

function doctorTimelineDateTime(value?: string | null): string {
  if (!value || value === '-') return '时间未记录'
  const date = parseDoctorDateTime(value)
  if (Number.isNaN(date.getTime())) return value
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const period = date.getHours() < 12 ? '上午' : '下午'
  const hour = date.getHours() % 12 || 12
  return `${date.getMonth() + 1}月${date.getDate()}日，${period}${hour}:${minutes}`
}

function parseDoctorDateTime(value: string): Date {
  const normalized = value.trim().replace(' ', 'T')
  const hasExplicitTimezone = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized)
  return new Date(hasExplicitTimezone ? normalized : `${normalized}Z`)
}

function doctorLocalDateKey(value?: string | null): string {
  if (!value || value === '-') return ''
  const date = parseDoctorDateTime(value)
  if (Number.isNaN(date.getTime())) return value.slice(0, 10)
  const twoDigits = (candidate: number) => String(candidate).padStart(2, '0')
  return `${date.getFullYear()}-${twoDigits(date.getMonth() + 1)}-${twoDigits(date.getDate())}`
}

function doctorDateValue(value: string): number {
  const parsed = parseDoctorDateTime(value).getTime()
  return Number.isNaN(parsed) ? 0 : parsed
}

function isClinicalNoteKey(key: string): boolean {
  return /(note|instruction|remark|说明|备注|要求|医嘱)/i.test(key)
}

function normalizeDoctorOrderFieldKey(key: string): string {
  return key.trim().replace(/[\s-]+/g, '_').toLowerCase()
}

function isTechnicalDoctorOrderField(key: string): boolean {
  return doctorOrderTechnicalFieldPattern.test(normalizeDoctorOrderFieldKey(key))
}

function doctorOrderFieldLabel(key: string): string | null {
  const normalizedKey = normalizeDoctorOrderFieldKey(key)
  if (doctorOrderDuplicateFieldKeys.has(normalizedKey) || isTechnicalDoctorOrderField(normalizedKey)) return null
  const configuredLabel = selectedOrderFieldLabels.value.get(normalizedKey)
  if (configuredLabel && /[\u3400-\u9fff]/.test(configuredLabel)) return configuredLabel
  if (doctorOrderFallbackLabels[normalizedKey]) return doctorOrderFallbackLabels[normalizedKey]
  return /[\u3400-\u9fff]/.test(key) ? key.trim() : null
}

function orderToothText(order: OrderDetail | null): string {
  if (!order) return '暂未记录'
  return order.form_snapshot['牙位']
    || order.form_snapshot.tooth_position
    || order.form_snapshot.tooth
    || order.form_snapshot.teeth
    || '暂未记录'
}

function parseDoctorTeeth(value: string): string[] {
  const selected = new Set<string>()
  const normalized = value.replace(/[—–~至]/g, '-')
  normalized.replace(/([1-4][1-8])\s*-\s*([1-4][1-8])/g, (_match, start: string, end: string) => {
    const startQuadrant = Number(start[0])
    const endQuadrant = Number(end[0])
    const startTooth = Number(start[1])
    const endTooth = Number(end[1])
    if (startQuadrant === endQuadrant) {
      const direction = startTooth <= endTooth ? 1 : -1
      for (let tooth = startTooth; tooth !== endTooth + direction; tooth += direction) selected.add(`${startQuadrant}${tooth}`)
    } else {
      selected.add(start)
      selected.add(end)
    }
    return _match
  })
  normalized.match(/[1-4][1-8]/g)?.forEach((tooth) => selected.add(tooth))
  return [...selected]
}

function fileGlyph(item: DoctorFile): string {
  if (item.kind === 'IMAGE') return '🖼️'
  if (item.kind === 'STL') return '🦷'
  if (item.kind === 'PDF') return '📄'
  return '📎'
}

function currentReviewFiles(review: OrderReview): DoctorFile[] {
  return review.versions.find((version) => version.version === review.current_version)?.files ?? []
}

function label(value: string | null | undefined): string {
  if (!value) return '-'
  return statusLabels[value] ?? (/^[A-Z][A-Z0-9_]+$/.test(value) ? '处理中' : value)
}

function reviewLabel(value: ReviewType): string {
  return reviewLabels[value]
}

function productTypeLabel(value: string): string {
  return productTypeLabels[value] ?? '定制修复'
}

function productNameLabel(value: string | null | undefined, productType = ''): string {
  if (!value?.trim()) return '定制修复'
  const normalized = value.trim()
  if (productNameLabels[normalized]) return productNameLabels[normalized]
  if (/^[A-Z][A-Z0-9_]+$/.test(normalized)) return productTypeLabel(productType || normalized)
  return normalized
}

function patientTreatmentState(patient: PatientSummary): PatientSummary['treatment_status'] {
  return patient.treatment_status
}

function patientTreatmentLabel(patient: PatientSummary): string {
  return ({ IN_TREATMENT: '治疗中', FOLLOW_UP: '待复诊', TREATMENT_ENDED: '治疗结束', ARCHIVED: '已归档' } as const)[patientTreatmentState(patient)]
}

function patientTreatmentTone(patient: PatientSummary): string {
  return ({ IN_TREATMENT: 'success', FOLLOW_UP: 'warning', TREATMENT_ENDED: 'neutral', ARCHIVED: 'neutral' } as const)[patientTreatmentState(patient)]
}

function patientDurationLabel(patient: PatientSummary): string {
  if (!patient.treatment_started_at) return '尚未记录'
  const start = new Date(`${patient.treatment_started_at.slice(0, 10)}T12:00:00`)
  const endValue = patient.treatment_ended_at || new Date().toISOString().slice(0, 10)
  const end = new Date(`${endValue.slice(0, 10)}T12:00:00`)
  const days = Math.max(0, Math.floor((end.getTime() - start.getTime()) / 86400000))
  if (days < 31) return `${days || 1} 天`
  const months = Math.floor(days / 30)
  const rest = days % 30
  return rest ? `${months}个月${rest}天` : `${months}个月`
}

function patientDate(value: string | null | undefined): string {
  return value ? value.slice(0, 10) : '-'
}

function patientAgeValue(): number | null {
  if (newPatient.age) return Number(newPatient.age)
  if (!newPatient.dateOfBirth) return null
  const birth = new Date(`${newPatient.dateOfBirth}T12:00:00`)
  const today = new Date()
  let age = today.getFullYear() - birth.getFullYear()
  const beforeBirthday = today.getMonth() < birth.getMonth() || (today.getMonth() === birth.getMonth() && today.getDate() < birth.getDate())
  if (beforeBirthday) age--
  return Math.max(0, age)
}

function resetPatientForm() {
  Object.assign(newPatient, {
    name: '', age: '', gender: '', dateOfBirth: '', phone: '', email: '', medicalNotes: '',
    treatmentStatus: 'IN_TREATMENT', treatmentStartedAt: new Date().toISOString().slice(0, 10),
    treatmentEndedAt: '', oralDescription: '', tags: ''
  })
}

function openPatientCreate() {
  resetPatientForm()
  patientDialogOpen.value = true
}

function beginPatientEdit() {
  if (!selectedPatient.value) return
  const patient = selectedPatient.value
  Object.assign(newPatient, {
    name: patient.patient_name,
    age: patient.patient_age == null ? '' : String(patient.patient_age),
    gender: patient.patient_gender || '',
    dateOfBirth: patient.date_of_birth || '',
    phone: patient.phone,
    email: patient.email,
    medicalNotes: patient.medical_notes,
    treatmentStatus: patient.treatment_status,
    treatmentStartedAt: patient.treatment_started_at || '',
    treatmentEndedAt: patient.treatment_ended_at || '',
    oralDescription: patient.oral_description,
    tags: patient.tags.join('，')
  })
  patientEditMode.value = true
  patientDrawerTab.value = 'basic'
}

function deliveryProgress(order: OrderSummary): number {
  if (order.external_status === 'DELIVERED_PENDING_CONFIRMATION') return 4
  if (order.external_status === 'SHIPPED') return 3
  if (order.external_status === 'READY_TO_DISPATCH') return 2
  return 1
}

function resetOrderFilters() {
  orderKeyword.value = ''
  orderStatus.value = 'ALL'
  orderProduct.value = 'ALL'
  orderDoctor.value = 'ALL'
  orderTag.value = 'ALL'
  orderDateFrom.value = ''
  orderDateTo.value = ''
  orderQuick.value = 'ALL'
  orderPage.value = 1
}

function withWizardOrderContext(order: OrderSummary): OrderSummary {
  const patient = selectedWizardPatient.value
  const product = selectedProduct.value
  return {
    ...order,
    patient_id: patient?.patient_id ?? order.patient_id,
    patient_code: patient?.patient_code ?? order.patient_code,
    patient_name: patient?.patient_name ?? order.patient_name,
    product_type: product?.product_type ?? order.product_type,
    product_name: product?.product_name ?? order.product_name
  }
}

function upsertOrderSummary(order: OrderSummary) {
  if (!dataset.value) return
  const index = dataset.value.orders.findIndex((item) => item.order_id === order.order_id)
  if (index >= 0) dataset.value.orders.splice(index, 1, order)
  else dataset.value.orders.unshift(order)
}

function applyRefreshedDataset(refreshed: DoctorPortalDataset, guaranteedOrder: OrderSummary) {
  const index = refreshed.orders.findIndex((item) => item.order_id === guaranteedOrder.order_id)
  if (index >= 0) refreshed.orders.splice(index, 1, { ...refreshed.orders[index], ...guaranteedOrder })
  else refreshed.orders.unshift(guaranteedOrder)
  dataset.value = refreshed
  activeThreadId.value = refreshed.threads[0]?.thread_id ?? activeThreadId.value
}

function showHelp() {
  ElMessage.info('帮助中心：订单资料、设计确认与账单问题可从右侧消息中心联系订单服务。')
}

function showSupport() {
  switchPage('messages')
  ElMessage.info('已打开消息中心，请选择订单会话联系支持。')
}

function isDueSoon(order: OrderSummary): boolean {
  if (!order.due_at || order.due_at === '-' || order.external_status === 'COMPLETED') return false
  const due = new Date(`${order.due_at.slice(0, 10)}T23:59:59`)
  const today = new Date(`${new Date().toLocaleDateString('sv-SE')}T00:00:00`)
  const difference = due.getTime() - today.getTime()
  return difference >= 0 && difference <= 3 * 86400000
}

function statusTone(value: string): string {
  if (['COMPLETED', 'PAID', 'SETTLED', 'APPROVED', 'ACTIVE'].includes(value)) return 'success'
  if (['NEEDS_INFO', 'AWAITING_PAYMENT', 'DELIVERED_PENDING_CONFIRMATION', 'PENDING_REVIEW', 'REVISION_REQUESTED', 'UNPAID', 'PENDING_PAYMENT', 'PARTIALLY_PAID'].includes(value)) return 'warning'
  if (['IN_PRODUCTION', 'SHIPPED', 'IN_TRANSIT', 'SUBMITTED', 'UNDER_REVIEW'].includes(value)) return 'primary'
  return 'neutral'
}

function switchPage(page: DoctorPage) {
  activePage.value = page
  globalSearchOpen.value = false
  roleMenuOpen.value = false
  if (page === 'messages' && activeThread.value) activeThreadId.value = activeThread.value.thread_id
}

async function loadPortal() {
  loading.value = true
  loadError.value = ''
  try {
    dataset.value = await gateway.loadDataset()
    activeThreadId.value = dataset.value.threads[0]?.thread_id ?? ''
    const ownMember = dataset.value.account.members.find((member) => member.email === dataset.value?.account.email)
      ?? dataset.value.account.members[0]
    if (ownMember?.roles.length) {
      availableRoles.value = ownMember.roles
      if (!ownMember.roles.includes(activeRole.value)) activeRole.value = ownMember.roles[0]
    } else {
      const backendRoles = (props.currentUser?.roles ?? []).filter((role): role is ClinicRole => ['CLINIC_ADMIN', 'DOCTOR', 'RECEPTION', 'NURSE'].includes(role))
      availableRoles.value = backendRoles.length ? backendRoles : ['DOCTOR']
      activeRole.value = availableRoles.value[0]
    }
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : '医生端数据加载失败'
  } finally {
    loading.value = false
  }
}

async function chooseRole(role: ClinicRole) {
  if (role === activeRole.value) {
    roleMenuOpen.value = false
    return
  }
  const previousRole = activeRole.value
  activeRole.value = role
  roleMenuOpen.value = false
  if (role !== 'DOCTOR' && activePage.value === 'assistant') activePage.value = 'dashboard'
  loading.value = true
  try {
    dataset.value = await gateway.switchRole(role)
    activeThreadId.value = dataset.value.threads[0]?.thread_id ?? ''
    selectedOrderIds.value = []
    ElMessage.success(`已切换为${roleLabels[role]}身份`)
  } catch (cause) {
    activeRole.value = previousRole
    ElMessage.error(cause instanceof Error ? cause.message : '身份切换失败')
  } finally {
    loading.value = false
  }
}

async function deliveryApi<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await props.authenticatedFetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${props.token}`,
      ...(options.headers ?? {})
    }
  })
  if (!response.ok) {
    let detail = ''
    try {
      const body = await response.json() as { message?: string; msg?: string; error?: string }
      detail = body.message || body.msg || body.error || ''
    } catch {
      detail = ''
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  const payload = await response.json() as { data: T }
  return payload.data
}

async function loadDeliveryPlan(orderId: string) {
  deliveryPlan.value = null
  requestedDeliveryDateDraft.value = ''
  // 交期计划在提交时才建立；草稿订单没有计划，静默跳过而不是弹错。
  if (resolveDoctorGatewayMode() !== 'api') return
  deliveryPlanLoading.value = true
  try {
    const plan = await deliveryApi<DeliveryPlan>(`/orders/${orderId}/delivery-plan`)
    deliveryPlan.value = plan
    requestedDeliveryDateDraft.value = plan.doctor_requested_delivery_date ?? plan.computed_delivery_date
  } catch {
    deliveryPlan.value = null
  } finally {
    deliveryPlanLoading.value = false
  }
}

async function saveRequestedDeliveryDate() {
  const orderId = selectedOrder.value?.order_id
  if (!orderId || !requestedDeliveryDateDraft.value || deliveryPlanBusy.value) return
  deliveryPlanBusy.value = true
  try {
    deliveryPlan.value = await deliveryApi<DeliveryPlan>(
      `/orders/${orderId}/delivery-plan/requested-date`,
      {
        method: 'PUT',
        body: JSON.stringify({ requested_delivery_date: requestedDeliveryDateDraft.value })
      }
    )
    ElMessage.success(deliveryPlan.value.variance_flag === 'EARLIER_THAN_FEASIBLE'
      ? '已提交；该时间早于系统可行交期，订单服务会与您确认'
      : '要求到货时间已更新')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '更新到货时间失败')
  } finally {
    deliveryPlanBusy.value = false
  }
}

async function respondProcessConfirmation(confirmation: ProcessConfirmation, accepted: boolean) {
  const orderId = selectedOrder.value?.order_id
  if (!orderId || deliveryPlanBusy.value) return
  deliveryPlanBusy.value = true
  try {
    await deliveryApi(
      `/orders/${orderId}/process-confirmations/${confirmation.confirmation_code}/respond`,
      { method: 'POST', body: JSON.stringify({ accepted }) }
    )
    await loadDeliveryPlan(orderId)
    ElMessage.success(accepted ? '已确认' : '已提交修改要求')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '提交确认结果失败')
  } finally {
    deliveryPlanBusy.value = false
  }
}

function deliveryDateLabel(plan: DeliveryPlan) {
  return plan.estimate_status === 'PLACEHOLDER'
    ? `${plan.computed_delivery_date}（待确认）`
    : plan.computed_delivery_date
}

async function openOrder(orderId: string) {
  orderDrawerOpen.value = true
  orderDetailLoading.value = true
  selectedOrder.value = null
  orderDrawerMessageDraft.value = ''
  void loadDeliveryPlan(orderId)
  try {
    const detail = await gateway.loadOrderDetail(orderId)
    selectedOrder.value = detail
    if (dataset.value) {
      const summary = dataset.value.orders.find((item) => item.order_id === orderId)
      if (summary) {
        Object.assign(summary, {
          external_status: detail.external_status,
          current_action: detail.current_action,
          allowed_actions: detail.allowed_actions,
          state_version: detail.state_version,
          due_at: detail.due_at,
          quote: detail.quote
        })
      }
      const threadIndex = dataset.value.threads.findIndex((thread) => thread.order_id === orderId)
      const latestMessage = detail.messages.at(-1)
      const thread: MessageThread = {
        thread_id: `TH-${orderId}`,
        order_id: orderId,
        order_no: detail.order_no,
        patient_name: detail.patient_name,
        product_name: detail.product_name,
        unread: false,
        latest_message: latestMessage?.content || '暂无沟通记录',
        latest_at: latestMessage?.sent_at || detail.created_at,
        messages: detail.messages
      }
      if (threadIndex >= 0) dataset.value.threads.splice(threadIndex, 1, thread)
      else dataset.value.threads.push(thread)
    }
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '订单详情加载失败')
  } finally {
    orderDetailLoading.value = false
  }
}

async function openPatient(patientId: string) {
  patientDrawerOpen.value = true
  patientDrawerTab.value = 'basic'
  patientLoading.value = true
  selectedPatient.value = null
  patientEditMode.value = false
  try {
    selectedPatient.value = await gateway.loadPatientDetail(patientId)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '患者档案加载失败')
  } finally {
    patientLoading.value = false
  }
}

function openNotification(itemId: string) {
  const item = dataset.value?.notifications.find((notification) => notification.notification_id === itemId)
  if (!item) return
  if (!item.read) {
    item.read = true
    void gateway.markNotificationRead(item.notification_id).catch(() => { item.read = false })
  }
  notificationOpen.value = false
  if (item.target_type === 'ORDER' && item.target_id) {
    switchPage('orders')
    void openOrder(item.target_id)
  } else if (item.target_type === 'MESSAGE') {
    activeThreadId.value = item.target_id ?? ''
    switchPage('messages')
  } else if (item.target_type === 'BILLING') {
    switchPage('billing')
  }
}

async function markAllNotifications() {
  if (!dataset.value) return
  const before = dataset.value.notifications.map((item) => item.read)
  dataset.value.notifications.forEach((item) => { item.read = true })
  try {
    await gateway.markAllNotificationsRead()
    ElMessage.success('全部通知已标记为已读')
  } catch (cause) {
    dataset.value.notifications.forEach((item, index) => { item.read = before[index] })
    ElMessage.error(cause instanceof Error ? cause.message : '操作失败')
  }
}

function toggleOrderSelection(orderId: string, checked: boolean) {
  selectedOrderIds.value = checked
    ? Array.from(new Set([...selectedOrderIds.value, orderId]))
    : selectedOrderIds.value.filter((id) => id !== orderId)
}

function togglePageSelection(checked: boolean) {
  const pageIds = pagedOrders.value.map((item) => item.order_id)
  selectedOrderIds.value = checked
    ? Array.from(new Set([...selectedOrderIds.value, ...pageIds]))
    : selectedOrderIds.value.filter((id) => !pageIds.includes(id))
}

function openWizard(initialPatientId = '', initialGroupId: number | null = null) {
  if (!canCreateOrder.value) return
  wizardInitialPatientId.value = initialPatientId
  wizardInitialGroupId.value = initialGroupId
  Object.assign(wizard, {
    draftOrderId: undefined,
    patientId: initialPatientId,
    productId: '',
    productType: '',
    caseFields: { tooth: '', case_note: '' },
    dynamicFields: {},
    reviewOptions: [],
    files: []
  })
  wizardStep.value = 1
  wizardCategory.value = 'fixed'
  wizardSelectedTeeth.value = []
  wizardToothMode.value = 'RESTORE'
  wizardDragActive.value = false
  wizardPatientKeyword.value = ''
  wizardNotice.value = ''
  wizardUploadedFileSignatures.value = {}
  wizardOpen.value = true
  chooseWizardCategory('fixed')
}

function resumeSelectedCaseGroup() {
  const order = selectedOrder.value
  if (!order?.group_id || order.external_status !== 'DRAFT') return
  orderDrawerOpen.value = false
  openWizard(order.patient_id, order.group_id)
}

async function handleCaseGroupSubmitted() {
  wizardOpen.value = false
  wizardInitialPatientId.value = ''
  wizardInitialGroupId.value = null
  resetOrderFilters()
  switchPage('orders')
  try {
    dataset.value = await gateway.loadDataset()
  } catch (cause) {
    ElMessage.warning(cause instanceof Error ? cause.message : '订单已提交，列表刷新失败，请稍后手动刷新')
  }
}

function chooseWizardCategory(categoryId: WizardCategoryId) {
  wizardCategory.value = categoryId
  const category = wizardCategories.find((item) => item.id === categoryId)
  const product = (dataset.value?.products ?? []).find((item) => category?.types.includes(item.product_type))
  wizard.productId = product?.product_id ?? ''
  wizard.productType = product?.product_type ?? ''
  wizard.dynamicFields = {}
  wizard.reviewOptions = []
  wizardToothMode.value = categoryId === 'removable' ? 'MISSING' : 'RESTORE'
}

function chooseWizardProduct(product: ProductOption) {
  wizard.productId = product.product_id
  wizard.productType = product.product_type
  wizard.dynamicFields = {}
  wizard.reviewOptions = []
}

function toggleWizardTooth(tooth: number) {
  wizardSelectedTeeth.value = wizardSelectedTeeth.value.includes(tooth)
    ? wizardSelectedTeeth.value.filter((item) => item !== tooth)
    : [...wizardSelectedTeeth.value, tooth].sort((left, right) => left - right)
  wizard.caseFields.tooth = wizardSelectedTeeth.value.join('、')
  wizard.caseFields.tooth_mode = wizardToothMode.value
}

function wizardMissingForStep(step: number): string[] {
  const missing: string[] = []
  if (step >= 1) {
    if (!wizard.patientId) missing.push('患者')
    if (!wizard.productId) missing.push('产品')
  }
  if (step >= 2 && !wizard.caseFields.tooth?.trim()) missing.push('牙位')
  if (step >= 3) {
    selectedProductFields.value.filter((field) => field.required).forEach((field) => {
      if (!wizard.dynamicFields[field.key]?.trim()) missing.push(field.label)
    })
  }
  if (step >= 4 && !wizard.files.some((item) => item.kind === 'STL' && item.status === 'READY')) missing.push('STL 扫描文件')
  return missing
}

function wizardSubmissionDynamicFields(): Record<string, string> {
  const patientName = selectedWizardPatient.value?.patient_name.trim() ?? ''
  const toothPosition = wizard.caseFields.tooth.trim()
  const fields: Record<string, string> = {
    ...wizard.dynamicFields,
    patient_name: patientName,
    tooth_position: toothPosition
  }
  selectedProduct.value?.form_fields.forEach((field) => {
    const normalizedKey = normalizeDoctorOrderFieldKey(field.key)
    if (doctorOrderPatientFieldKeys.has(normalizedKey)) fields[field.key] = patientName
    if (doctorOrderToothFieldKeys.has(normalizedKey)) fields[field.key] = toothPosition
  })
  return fields
}

async function saveWizardDraft(silent = false) {
  if (wizardSaving.value || wizardSubmitting.value || wizardUploading.value) return false
  if (!wizard.patientId || !wizard.productId) {
    if (!silent) ElMessage.warning('选择患者和产品后才能保存草稿')
    return false
  }
  wizardSaving.value = true
  try {
    const wasNewDraft = !wizard.draftOrderId
    const saved = withWizardOrderContext(await gateway.saveDraft({
      ...wizard,
      draftOrderId: wizard.draftOrderId,
      dynamicFields: wizardSubmissionDynamicFields(),
      files: [...wizard.files],
      reviewOptions: [...wizard.reviewOptions]
    }))
    wizard.draftOrderId = saved.order_id
    upsertOrderSummary(saved)
    if (wasNewDraft) resetOrderFilters()
    wizardNotice.value = `草稿已保存 · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
    if (!silent) ElMessage.success('草稿已保存')
    return true
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '草稿保存失败')
    return false
  } finally {
    wizardSaving.value = false
  }
}

async function nextWizardStep() {
  if (wizardSaving.value || wizardSubmitting.value || wizardUploading.value) return
  const missing = wizardMissingForStep(wizardStep.value)
  if (missing.length) {
    ElMessage.warning(`请先补充：${missing.join('、')}`)
    return
  }
  const saved = await saveWizardDraft(true)
  if (!saved) return
  wizardStep.value = Math.min(5, wizardStep.value + 1)
}

async function addWizardFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const list = Array.from(input.files ?? [])
  input.value = ''
  await uploadWizardFiles(list)
}

async function handleWizardDrop(event: DragEvent) {
  wizardDragActive.value = false
  await uploadWizardFiles(Array.from(event.dataTransfer?.files ?? []))
}

function wizardFileSignature(file: File) {
  return `${file.name}:${file.size}:${file.lastModified}`
}

async function removeWizardFile(file: DoctorFile) {
  const previousFiles = [...wizard.files]
  const previousSignatures = { ...wizardUploadedFileSignatures.value }
  wizard.files = wizard.files.filter((candidate) => candidate.file_id !== file.file_id)
  wizardUploadedFileSignatures.value = Object.fromEntries(
    Object.entries(wizardUploadedFileSignatures.value)
      .filter(([, fileId]) => fileId !== file.file_id)
  )
  if (wizard.draftOrderId && !(await saveWizardDraft(true))) {
    wizard.files = previousFiles
    wizardUploadedFileSignatures.value = previousSignatures
    return
  }
  ElMessage.success(`已从订单中移除 ${file.name}`)
}

async function uploadWizardFiles(list: File[]) {
  const accepted = list.filter((item) => /\.(stl|jpg|jpeg|png|pdf)$/i.test(item.name))
  const rejected = list.length - accepted.length
  if (rejected) ElMessage.warning(`${rejected} 个文件格式不支持`)
  const knownSignatures = new Set(Object.keys(wizardUploadedFileSignatures.value))
  const pending = accepted.filter((file) => {
    const signature = wizardFileSignature(file)
    if (knownSignatures.has(signature)) return false
    knownSignatures.add(signature)
    return true
  })
  const alreadyUploaded = accepted.length - pending.length
  if (alreadyUploaded) ElMessage.info(`${alreadyUploaded} 个已完成文件已跳过，未重复上传`)
  if (!pending.length) return
  if (!wizard.draftOrderId) {
    const saved = await saveWizardDraft(true)
    if (!saved || !wizard.draftOrderId) return
  }
  wizardUploading.value = true
  let completedCount = 0
  try {
    for (const file of pending) {
      const uploaded = await gateway.uploadOrderFiles(wizard.draftOrderId, [file])
      const completed = uploaded[0]
      if (!completed) throw new Error(`文件 ${file.name} 上传完成后未返回文件记录`)
      wizard.files.push(completed)
      wizardUploadedFileSignatures.value = {
        ...wizardUploadedFileSignatures.value,
        [wizardFileSignature(file)]: completed.file_id
      }
      completedCount += 1
    }
    ElMessage.success(`${completedCount} 个文件已就绪`)
  } catch (cause) {
    const prefix = completedCount ? `已有 ${completedCount} 个文件完成并已保留；` : ''
    ElMessage.error(`${prefix}${cause instanceof Error ? cause.message : '文件上传失败'}`)
  } finally {
    wizardUploading.value = false
  }
}

function markThreadUnread(threadId: string) {
  const thread = dataset.value?.threads.find((item) => item.thread_id === threadId)
  if (!thread) return
  thread.unread = true
  ElMessage.success('已在当前页面标记为未读')
}

function downloadInvoice(recordId: string, notify = true) {
  const record = dataset.value?.invoiceRefunds.find((item) => item.record_id === recordId)
  if (!record) return false
  const pdfLines = [
    record.kind === 'INVOICE' ? 'INVOICE RECORD' : 'REFUND RECORD',
    `Record: ${record.record_id}`,
    `Related: ${record.related_no}`,
    `Amount: ${record.amount.currency} ${(record.amount.amount_minor / 100).toFixed(2)}`,
    `Status: ${record.status}`,
    `Date: ${record.created_at}`
  ]
  const escapePdf = (value: string) => value.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)')
  const stream = `BT /F1 18 Tf 54 760 Td (${escapePdf(pdfLines[0])}) Tj /F1 11 Tf${pdfLines.slice(1).map((line) => ` 0 -28 Td (${escapePdf(line)}) Tj`).join('')} ET`
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`
  ]
  let pdf = '%PDF-1.4\n'
  const offsets = [0]
  objects.forEach((object, index) => {
    offsets.push(pdf.length)
    pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
  })
  const xref = pdf.length
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.slice(1).map((offset) => `${String(offset).padStart(10, '0')} 00000 n `).join('\n')}\ntrailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`
  const url = URL.createObjectURL(new Blob([pdf], { type: 'application/pdf' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `${record.record_id}.pdf`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
  if (notify) ElMessage.success('PDF 记录已下载')
  return true
}

async function downloadAllInvoices() {
  if (bulkInvoiceDownloading.value) return
  const records = [...downloadableInvoiceRefunds.value]
  if (records.length === 0) {
    ElMessage.info('当前没有可下载的发票或退款记录')
    return
  }
  bulkInvoiceDownloading.value = true
  let completed = 0
  try {
    for (const record of records) {
      if (downloadInvoice(record.record_id, false)) completed += 1
      await new Promise<void>((resolve) => window.setTimeout(resolve, 80))
    }
    ElMessage.success(`已下载 ${completed} 份发票或退款记录`)
  } finally {
    bulkInvoiceDownloading.value = false
  }
}

async function submitWizard() {
  const missing = wizardMissingForStep(4)
  if (missing.length) {
    wizardNotice.value = `提交前还需补充：${missing.join('、')}`
    ElMessage.warning(wizardNotice.value)
    return
  }
  wizardSubmitting.value = true
  try {
    const created = withWizardOrderContext(await gateway.submitOrder({
      ...wizard,
      draftOrderId: wizard.draftOrderId,
      dynamicFields: wizardSubmissionDynamicFields(),
      files: [...wizard.files],
      reviewOptions: [...wizard.reviewOptions]
    }))
    upsertOrderSummary(created)
    resetOrderFilters()
    wizardOpen.value = false
    switchPage('orders')
    try {
      applyRefreshedDataset(await gateway.loadDataset(), created)
    } catch {
      upsertOrderSummary(created)
    }
    ElMessage.success(`订单 ${created.order_no} 已提交`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '订单提交失败')
  } finally {
    wizardSubmitting.value = false
  }
}

function chooseThread(threadId: string) {
  activeThreadId.value = threadId
  const thread = dataset.value?.threads.find((item) => item.thread_id === threadId)
  if (thread?.unread) {
    thread.unread = false
    void gateway.markThreadRead(threadId).catch(() => undefined)
  }
}

async function sendMessage() {
  const content = messageDraft.value.trim()
  const thread = activeThread.value
  if (!content || !thread) return
  sendingMessage.value = true
  try {
    const item = await gateway.sendMessage(thread.thread_id, content)
    if (!thread.messages.some((message) => message.message_id === item.message_id)) thread.messages.push(item)
    thread.latest_message = content
    thread.latest_at = '刚刚'
    messageDraft.value = ''
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '消息发送失败')
  } finally {
    sendingMessage.value = false
  }
}

async function sendOrderDrawerMessage() {
  const order = selectedOrder.value
  const content = orderDrawerMessageDraft.value.trim()
  if (!order || !content) return
  if (!order.allowed_actions.includes('SEND_MESSAGE')) {
    ElMessage.warning('当前订单暂不支持发送消息')
    return
  }

  orderDrawerMessageSending.value = true
  try {
    const threadId = dataset.value?.threads.find((thread) => thread.order_id === order.order_id)?.thread_id ?? `TH-${order.order_id}`
    const item = await gateway.sendMessage(threadId, content)
    if (!order.messages.some((message) => message.message_id === item.message_id)) order.messages.push(item)

    if (dataset.value) {
      let thread = dataset.value.threads.find((candidate) => candidate.order_id === order.order_id)
      if (!thread) {
        thread = {
          thread_id: threadId,
          order_id: order.order_id,
          order_no: order.order_no,
          patient_name: order.patient_name,
          product_name: order.product_name,
          unread: false,
          latest_message: content,
          latest_at: item.sent_at,
          messages: order.messages
        }
        dataset.value.threads.push(thread)
      } else {
        if (!thread.messages.some((message) => message.message_id === item.message_id)) thread.messages.push(item)
        thread.latest_message = content
        thread.latest_at = item.sent_at
      }
    }

    orderDrawerMessageDraft.value = ''
    ElMessage.success('消息已发送给订单服务')
    window.setTimeout(() => {
      const stream = document.querySelector<HTMLElement>('[data-testid="doctor-order-dialogue"]')
      stream?.scrollTo({ top: stream.scrollHeight, behavior: 'smooth' })
    })
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '消息发送失败')
  } finally {
    orderDrawerMessageSending.value = false
  }
}

function startReviewDecision(orderId: string, review: OrderReview, decision: 'APPROVE' | 'REJECT') {
  if (reviewSubmitting.value) return
  const action = decision === 'APPROVE' ? 'APPROVE_REVIEW' : 'REJECT_REVIEW'
  if (!canReview.value || review.status !== 'PENDING_REVIEW' || !review.allowed_actions.includes(action)) return
  reviewTarget.value = { orderId, review }
  if (decision === 'REJECT') {
    rejectReason.value = ''
    rejectDialogOpen.value = true
    return
  }
  void ElMessageBox.confirm('同意后，对方将按当前版本继续后续制作。请确认已完成检查。', '确认同意当前版本', {
    confirmButtonText: '确认同意', cancelButtonText: '再检查一下', type: 'warning'
  }).then(() => submitReviewDecision('APPROVE')).catch(() => undefined)
}

function applyRefreshedReviewOrder(orderId: string, refreshed: OrderDetail) {
  if (selectedOrder.value?.order_id === orderId) selectedOrder.value = refreshed
  const summary = dataset.value?.orders.find((order) => order.order_id === orderId)
  if (summary) {
    Object.assign(summary, {
      external_status: refreshed.external_status,
      current_action: refreshed.current_action,
      allowed_actions: refreshed.allowed_actions,
      state_version: refreshed.state_version,
      due_at: refreshed.due_at,
      quote: refreshed.quote
    })
  }
  dataset.value?.threads.forEach((thread) => thread.messages.forEach((message) => {
    const refreshedReview = refreshed.reviews.find((item) => item.review_id === message.review?.review_id)
    if (message.review && refreshedReview) Object.assign(message.review, refreshedReview)
  }))
}

async function submitReviewDecision(decision: 'APPROVE' | 'REJECT') {
  const target = reviewTarget.value
  if (!target || reviewSubmitting.value) return
  if (decision === 'REJECT' && !rejectReason.value.trim()) {
    ElMessage.warning('驳回时必须填写修改意见')
    return
  }
  reviewSubmitting.value = true
  try {
    let usedSubmittedFallback = false
    let updated: OrderReview
    try {
      updated = await gateway.submitReview({
        orderId: target.orderId,
        reviewId: target.review.review_id,
        decision,
        comment: decision === 'REJECT' ? rejectReason.value.trim() : undefined,
        stateVersion: target.review.state_version,
        idempotencyKey: crypto.randomUUID()
      })
    } catch (cause) {
      if (!isDoctorReviewSubmittedRefreshError(cause)) throw cause
      usedSubmittedFallback = true
      updated = cause.submittedReview
    }
    const mergeReview = (review: OrderReview) => {
      if (!usedSubmittedFallback) {
        Object.assign(review, updated)
        return
      }
      review.status = updated.status
      review.current_version = updated.current_version
      review.allowed_actions = updated.allowed_actions
      review.state_version = updated.state_version
      const submittedVersion = updated.versions.find((item) => item.version === updated.current_version)
      const existingVersion = review.versions.find((item) => item.version === updated.current_version)
      if (submittedVersion && existingVersion) {
        existingVersion.status = submittedVersion.status
        existingVersion.doctor_comment = submittedVersion.doctor_comment
      } else if (submittedVersion) {
        review.versions.push(submittedVersion)
      }
    }
    mergeReview(target.review)
    dataset.value?.threads.forEach((thread) => thread.messages.forEach((message) => {
      if (message.review?.review_id === updated.review_id) mergeReview(message.review)
    }))
    if (selectedOrder.value?.order_id === target.orderId) {
      const orderReview = selectedOrder.value.reviews.find((item) => item.review_id === updated.review_id)
      if (orderReview) mergeReview(orderReview)
      if (!selectedOrder.value.reviews.some((item) => item.status === 'PENDING_REVIEW') && selectedOrder.value.current_action.includes('REVIEW')) {
        selectedOrder.value.current_action = 'NONE'
        selectedOrder.value.allowed_actions = selectedOrder.value.allowed_actions
          .filter((action) => !['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))
      }
    }
    const summary = dataset.value?.orders.find((order) => order.order_id === target.orderId)
    if (summary?.current_action.includes('REVIEW') && target.review.status !== 'PENDING_REVIEW') {
      summary.current_action = 'NONE'
      summary.allowed_actions = summary.allowed_actions
        .filter((action) => !['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))
    }
    rejectDialogOpen.value = false
    try {
      const refreshed = await gateway.loadOrderDetail(target.orderId)
      applyRefreshedReviewOrder(target.orderId, refreshed)
    } catch {
      ElMessage.warning(usedSubmittedFallback
        ? '确认已提交，但最新公开状态仍无法读取；页面已保留提交结果，请稍后刷新核对'
        : '确认已提交，但订单最新公开状态读取失败，请稍后刷新')
    }
    ElMessage.success(decision === 'APPROVE' ? '已同意当前版本，对方可以继续制作' : '已驳回并发送修改意见')
  } catch (cause) {
    try {
      const reconciled = await gateway.loadOrderDetail(target.orderId)
      const reconciledReview = reconciled.reviews.find((review) => review.review_id === target.review.review_id)
      const expectedStatus = decision === 'APPROVE' ? 'APPROVED' : 'REVISION_REQUESTED'
      if (reconciledReview?.status === expectedStatus) {
        applyRefreshedReviewOrder(target.orderId, reconciled)
        rejectDialogOpen.value = false
        ElMessage.success(decision === 'APPROVE'
          ? '服务器已完成版本确认，页面状态已重新同步'
          : '服务器已收到驳回意见，页面状态已重新同步')
        return
      }
    } catch {
      // 提交请求结果不明确且暂时无法回读时，保留原始错误供用户重试。
    }
    ElMessage.error(cause instanceof Error ? cause.message : '确认操作失败')
  } finally {
    reviewSubmitting.value = false
  }
}

async function previewFile(item: DoctorFile) {
  if (filePreviewLoading.value) return
  filePreviewLoading.value = true
  try {
    const previewUrl = await gateway.getFilePreviewUrl(item.file_id)
    const freshFile = { ...item, preview_url: previewUrl }
    if (item.kind === 'STL') {
      viewerFile.value = freshFile
      viewerOpen.value = true
    } else {
      filePreview.value = freshFile
      filePreviewOpen.value = true
    }
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '文件预览失败')
  } finally {
    filePreviewLoading.value = false
  }
}

async function askAssistant() {
  const question = assistantQuestion.value.trim()
  if (!question) return
  assistantMessages.value.push({ role: 'SELF', content: question })
  assistantQuestion.value = ''
  assistantLoading.value = true
  try {
    const contextOrder = (dataset.value?.orders ?? []).find((order) => question.includes(order.order_no) || question.includes(order.patient_code))
      ?? (dataset.value?.orders ?? []).find((order) => order.external_status !== 'DRAFT')
    if (!contextOrder) {
      // 问题定位不到订单时兜底转 AI-6 常见问题，而不是直接报「请选择订单」。
      await answerWithFaq(question)
      return
    }
    const response = await gateway.askAssistant(question, contextOrder.order_id)
    assistantMessages.value.push({ role: 'ASSISTANT', content: response.answer, orderIds: response.orderIds })
  } catch (cause) {
    assistantMessages.value.push({ role: 'ASSISTANT', content: cause instanceof Error ? cause.message : '查询暂时不可用' })
  } finally {
    assistantLoading.value = false
  }
}

async function askFaq(question: string) {
  const trimmed = question.trim()
  if (!trimmed) return
  assistantMessages.value.push({ role: 'SELF', content: trimmed })
  assistantQuestion.value = ''
  assistantLoading.value = true
  try {
    await answerWithFaq(trimmed)
  } finally {
    assistantLoading.value = false
  }
}

async function answerWithFaq(question: string) {
  try {
    const faq = await gateway.askFaq(question)
    const suffix = faq.requiresCustomerConfirmation
      ? '\n\n（以上内容引自常见问题库的示例语料，待甲方确认）'
      : ''
    assistantMessages.value.push({ role: 'ASSISTANT', content: faq.answer + suffix, orderIds: [] })
  } catch (cause) {
    assistantMessages.value.push({
      role: 'ASSISTANT',
      content: cause instanceof Error ? cause.message : '常见问题查询暂时不可用',
      orderIds: []
    })
  }
}

async function createPatient() {
  if (!dataset.value || !newPatient.name.trim()) {
    ElMessage.warning('请填写患者姓名')
    return
  }
  patientSaving.value = true
  try {
    const item = await gateway.createPatient({
      patientName: newPatient.name.trim(),
      patientAge: patientAgeValue(),
      patientGender: newPatient.gender || null,
      dateOfBirth: newPatient.dateOfBirth || null,
      phone: newPatient.phone.trim(),
      email: newPatient.email.trim(),
      medicalNotes: newPatient.medicalNotes.trim(),
      treatmentStatus: newPatient.treatmentStatus,
      treatmentStartedAt: newPatient.treatmentStartedAt || null,
      treatmentEndedAt: newPatient.treatmentEndedAt || null,
      oralDescription: newPatient.oralDescription.trim(),
      tags: newPatient.tags.split(/[,，]/).map((candidate) => candidate.trim()).filter(Boolean)
    })
    dataset.value.patients.unshift(item)
    resetPatientForm()
    patientDialogOpen.value = false
    ElMessage.success('患者已保存')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '患者保存失败')
  } finally {
    patientSaving.value = false
  }
}

async function savePatientChanges() {
  if (!dataset.value || !selectedPatient.value || !newPatient.name.trim()) {
    ElMessage.warning('请填写患者姓名')
    return
  }
  patientSaving.value = true
  try {
    const updated = await gateway.updatePatient({
      patientId: selectedPatient.value.patient_id,
      patientName: newPatient.name.trim(),
      patientAge: patientAgeValue(),
      patientGender: newPatient.gender || null,
      dateOfBirth: newPatient.dateOfBirth || null,
      phone: newPatient.phone.trim(),
      email: newPatient.email.trim(),
      medicalNotes: newPatient.medicalNotes.trim(),
      treatmentStatus: newPatient.treatmentStatus,
      treatmentStartedAt: newPatient.treatmentStartedAt || null,
      treatmentEndedAt: newPatient.treatmentEndedAt || null,
      oralDescription: newPatient.oralDescription.trim(),
      tags: newPatient.tags.split(/[,，]/).map((candidate) => candidate.trim()).filter(Boolean)
    })
    const index = dataset.value.patients.findIndex((item) => item.patient_id === updated.patient_id)
    if (index >= 0) dataset.value.patients.splice(index, 1, updated)
    selectedPatient.value = { ...selectedPatient.value, ...updated, notes: updated.medical_notes }
    patientEditMode.value = false
    ElMessage.success('患者档案已更新')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '患者档案更新失败')
  } finally {
    patientSaving.value = false
  }
}

function addMember() {
  if (!dataset.value || !newMember.displayName.trim() || !newMember.email.trim()) {
    ElMessage.warning('请填写成员姓名和邮箱')
    return
  }
  dataset.value.account.members.push({
    member_id: `LOCAL-M-${Date.now()}`,
    display_name: newMember.displayName.trim(),
    email: newMember.email.trim(),
    roles: [newMember.role],
    status: 'PENDING_ACTIVATION',
    billing_permission: newMember.billing as 'NONE' | 'VIEW' | 'FINANCIAL_ACTION',
    logistics_permission: newMember.logistics as 'NONE' | 'VIEW' | 'RECEIPT'
  })
  memberDialogOpen.value = false
  Object.assign(newMember, { displayName: '', email: '', role: 'DOCTOR', billing: 'VIEW', logistics: 'VIEW' })
  ElMessage.info('成员邀请功能暂未开放')
}

function saveProfile() {
  ElMessage.success(dataMode === 'mock' ? '设置已保存' : '资料保存功能暂未开放')
}

function updatePassword() {
  if (!passwordForm.current || passwordForm.next.length < 8 || passwordForm.next !== passwordForm.confirm) {
    ElMessage.warning('请检查当前密码、新密码长度和两次输入是否一致')
    return
  }
  Object.assign(passwordForm, { current: '', next: '', confirm: '' })
  ElMessage.info('安全设置功能暂未开放')
}

function openLogistics(item: LogisticsRecord) {
  selectedLogistics.value = item
  logisticsDrawerOpen.value = true
}

async function confirmReceipt(item: LogisticsRecord) {
  try {
    await ElMessageBox.confirm('请确认产品已由诊所实际收取。确认后订单将完成。', '确认收货', {
      confirmButtonText: '确认已收货', cancelButtonText: '取消', type: 'warning'
    })
    const order = dataset.value?.orders.find((candidate) => candidate.order_id === item.order_id)
    await gateway.confirmReceipt(item.order_id, order?.state_version ?? 0)
    item.can_confirm_receipt = false
    item.status = 'COMPLETED'
    if (order) {
      order.external_status = 'COMPLETED'
      order.current_action = 'NONE'
    }
    ElMessage.success('已确认收货，订单已完成')
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(cause instanceof Error ? cause.message : '确认收货失败')
  }
}

function openGlobalOrder(orderId: string) {
  globalSearchOpen.value = false
  switchPage('orders')
  void openOrder(orderId)
}

function openGlobalPatient(patientId: string) {
  globalSearchOpen.value = false
  switchPage('patients')
  void openPatient(patientId)
}

function openSelectedOrderConversation() {
  const orderId = selectedOrder.value?.order_id
  if (!orderId || !dataset.value) return
  activeThreadId.value = dataset.value.threads.find((thread) => thread.order_id === orderId)?.thread_id ?? ''
  orderDrawerOpen.value = false
  switchPage('messages')
}

function selectAllNotificationFilter(filter: 'ALL' | 'UNREAD' | 'READ') {
  notificationFilter.value = filter
}

function handleGlobalShortcut(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    globalSearchOpen.value = true
    window.setTimeout(() => document.querySelector<HTMLInputElement>('[data-testid="doctor-global-search"]')?.focus())
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalShortcut)
  void loadPortal()
})
onBeforeUnmount(() => window.removeEventListener('keydown', handleGlobalShortcut))
</script>

<template>
  <div class="dv2-shell" data-testid="doctor-v2-portal">
    <aside class="dv2-sidebar">
      <div class="dv2-brand">
        <span class="dv2-brand-mark">P</span>
        <div><strong>PrecisionDental</strong><small>LAB · 医生工作台</small></div>
      </div>

      <div class="dv2-clinic-card">
        <span class="dv2-avatar">{{ (account?.display_name || currentUser?.username || '医').slice(0, 1) }}</span>
        <button type="button" data-testid="doctor-role-switcher" @click="roleMenuOpen = !roleMenuOpen">
          <strong>{{ account?.display_name || currentUser?.username || '医生' }}</strong>
          <small>{{ account?.clinic_name || '当前诊所' }} · {{ roleLabels[activeRole] }}</small>
        </button>
        <i>⌄</i>
        <div v-if="roleMenuOpen" class="dv2-floating-menu is-sidebar">
          <small>切换诊所身份</small>
          <button v-for="role in availableRoles" :key="role" type="button" :class="{ active: activeRole === role }" @click="chooseRole(role)">
            <span>{{ roleLabels[role] }}</span><i>{{ activeRole === role ? '✓' : '' }}</i>
          </button>
          <p>切换后只使用所选身份的权限。</p>
        </div>
      </div>

      <nav class="dv2-nav" aria-label="医生端菜单">
        <section v-for="group in navGroups" :key="group.label">
          <small class="dv2-nav-label">{{ group.label }}</small>
          <button
            v-for="item in group.items"
            :key="item.page"
            type="button"
            class="dv2-nav-item"
            :class="{ active: activePage === item.page }"
            :data-testid="`doctor-nav-${item.page}`"
            @click="switchPage(item.page)"
          >
            <span class="dv2-nav-icon" aria-hidden="true">{{ item.icon }}</span>
            <span>{{ item.label }}</span>
            <i v-if="item.page === 'messages' && (dataset?.threads.filter((thread) => thread.unread).length ?? 0)" class="dv2-nav-count">
              {{ dataset?.threads.filter((thread) => thread.unread).length }}
            </i>
          </button>
        </section>
      </nav>

      <button type="button" class="dv2-sidebar-support" @click="showSupport"><span>?</span><div><strong>{{ uiText('需要帮助？', 'Need help?') }}</strong><small>{{ uiText('联系订单支持', 'Contact support') }}</small></div><i>›</i></button>
      <div class="dv2-sidebar-user">
        <span>安全登录中</span><button type="button" title="退出登录" aria-label="退出登录" @click="emit('logout')">退出 ↗</button>
      </div>
    </aside>

    <section class="dv2-main">
      <header class="dv2-topbar">
        <div class="dv2-topbar-context"><strong>{{ currentMeta.title }}</strong><small>{{ account?.clinic_name || '当前诊所' }}</small></div>
        <div class="dv2-topbar-actions">
          <div class="dv2-global-search" :class="{ focused: globalSearchOpen }">
            <span aria-hidden="true">⌕</span>
            <input
              v-model="globalKeyword"
              type="search"
              placeholder="搜索订单或患者"
              aria-label="全局搜索"
              data-testid="doctor-global-search"
              @focus="globalSearchOpen = true"
              @keyup.esc="globalSearchOpen = false"
            >
            <kbd>⌘ K</kbd>
          </div>

          <div class="dv2-language-switch" aria-label="界面语言"><button type="button" :class="{ active: portalLanguage === 'ZH' }" @click="setPortalLanguage('ZH')">中文</button><button type="button" :class="{ active: portalLanguage === 'EN' }" @click="setPortalLanguage('EN')">EN</button></div>
          <button class="dv2-icon-button" type="button" aria-label="打开通知中心" data-testid="doctor-notification-button" @click="notificationOpen = true">
            🔔<i v-if="unreadCount">{{ unreadCount > 9 ? '9+' : unreadCount }}</i>
          </button>
          <button class="dv2-icon-button" type="button" aria-label="打开帮助" title="帮助中心" @click="showHelp">?</button>
          <button v-if="canCreateOrder" class="dv2-primary-button" type="button" data-testid="doctor-new-order" @click="openWizard()">＋ {{ uiText('新建订单', 'New Order') }}</button>
        </div>
      </header>

      <div v-if="globalSearchOpen" class="dv2-search-backdrop" @mousedown.self="globalSearchOpen = false">
        <section class="dv2-search-popover">
          <header><strong>全局搜索</strong><span>订单与患者</span></header>
          <template v-if="globalKeyword.trim()">
            <div class="dv2-search-group">
              <small>订单</small>
              <button v-for="order in globalResults.orders" :key="order.order_id" type="button" @click="openGlobalOrder(order.order_id)">
                <span><strong>{{ order.order_no }}</strong><small>{{ order.patient_name }} · {{ productNameLabel(order.product_name, order.product_type) }}</small></span><em>{{ label(order.external_status) }}</em>
              </button>
              <p v-if="!globalResults.orders.length">没有匹配订单</p>
            </div>
            <div class="dv2-search-group">
              <small>患者</small>
              <button v-for="patient in globalResults.patients" :key="patient.patient_id" type="button" @click="openGlobalPatient(patient.patient_id)">
                <span><strong>{{ patient.patient_name }}</strong><small>{{ patient.patient_code }} · {{ patient.order_count }} 个订单</small></span><em>查看档案</em>
              </button>
              <p v-if="!globalResults.patients.length">没有匹配患者</p>
            </div>
          </template>
          <div v-else class="dv2-search-empty">输入订单号、患者姓名或患者编号开始搜索</div>
        </section>
      </div>

      <main class="dv2-content">
        <div v-if="activePage !== 'dashboard'" class="dv2-page-heading">
          <div><h1>{{ currentMeta.title }}</h1><p>{{ currentMeta.description }}</p></div>
          <button v-if="activePage === 'patients' && activeRole === 'DOCTOR'" type="button" class="dv2-primary-button dv2-patient-add" @click="openPatientCreate">＋ 新建患者</button>
        </div>

        <div v-if="loading" class="dv2-loading-card"><span class="dv2-spinner" />正在加载医生端数据…</div>
        <div v-else-if="loadError" class="dv2-error-card"><strong>页面数据暂时不可用</strong><p>{{ loadError }}</p><button type="button" @click="loadPortal">重新加载</button></div>

        <template v-else-if="dataset">
          <section v-if="activePage === 'dashboard'" class="dv2-dashboard dv2-reference-dashboard" data-testid="doctor-page-dashboard">
            <header class="dv2-dashboard-reference-heading">
              <div><h2>{{ dashboardGreeting }}</h2><p>{{ dashboardContext }}</p></div>
              <button type="button" class="dv2-primary-button" @click="switchPage('orders')">进入订单管理</button>
            </header>

            <div class="dv2-metric-grid is-six">
              <article v-for="item in dashboardStats" :key="item.key" :class="`is-${item.tone}`">
                <span class="dv2-metric-icon">{{ item.icon }}</span><div><small>{{ item.label }}</small><strong>{{ item.value }}</strong><p>{{ item.note }}</p></div>
              </article>
            </div>

            <div class="dv2-dashboard-reference-columns">
              <div class="dv2-dashboard-reference-section">
                <div class="dv2-dashboard-section-label"><span>🔴</span>需要处理</div>
                <section class="dv2-card dv2-task-card dv2-reference-action-list">
                  <header><div><h2>需要处理</h2><p>优先处理会阻塞订单继续推进的事项</p></div><button type="button" @click="switchPage('orders')">{{ pendingTaskOrders.length }} 项 · 查看全部 →</button></header>
                  <button v-for="order in pendingTaskOrders.slice(0, 4)" :key="order.order_id" type="button" class="dv2-task-row" @click="openOrder(order.order_id)">
                    <span :class="`dv2-dot is-${statusTone(order.external_status)}`" />
                    <div><strong>{{ label(order.current_action) }}</strong><small>{{ order.order_no }} · {{ order.patient_name }} · {{ productNameLabel(order.product_name, order.product_type) }}</small></div>
                    <time>{{ order.due_at }}</time><i>›</i>
                  </button>
                  <div v-if="!pendingTaskOrders.length" class="dv2-empty">暂无待处理事项</div>
                </section>
              </div>

              <div class="dv2-dashboard-reference-stack">
                <div class="dv2-dashboard-reference-section">
                  <div class="dv2-dashboard-section-label"><span>🚚</span>即将送达</div>
                  <section class="dv2-card dv2-task-card dv2-reference-compact-list">
                    <header><div><h2>配送与收货</h2><p>医生可见的在途订单</p></div><span>{{ dashboardDeliveryOrders.length }} 单</span></header>
                    <button v-for="order in dashboardDeliveryOrders" :key="order.order_id" type="button" class="dv2-task-row" @click="openOrder(order.order_id)">
                      <span :class="`dv2-dot is-${statusTone(order.external_status)}`" />
                      <div><strong>{{ order.patient_name }} · {{ productNameLabel(order.product_name, order.product_type) }}</strong><small>{{ order.order_no }} · {{ label(order.external_status) }}</small><div class="dv2-delivery-steps" aria-label="配送进度"><i v-for="step in 4" :key="step" :class="{ done: deliveryProgress(order) >= step }" /><span>出库</span><span>运输</span><span>派送</span><span>签收</span></div></div>
                      <time>预计 {{ order.due_at }}</time><i>›</i>
                    </button>
                    <div v-if="!dashboardDeliveryOrders.length" class="dv2-empty">暂无在途订单</div>
                  </section>
                </div>
                <div class="dv2-dashboard-reference-section">
                  <div class="dv2-dashboard-section-label"><span>🕐</span>到期提醒</div>
                  <section class="dv2-card dv2-task-card dv2-reference-compact-list">
                    <header><div><h2>临近交付订单</h2><p>根据预计日期排序</p></div><span>{{ dashboardDueOrders.length }} 单</span></header>
                    <button v-for="order in dashboardDueOrders" :key="order.order_id" type="button" class="dv2-task-row dv2-due-row" @click="openOrder(order.order_id)">
                      <span class="dv2-dot is-warning" />
                      <div><strong>{{ order.patient_name }} · {{ productNameLabel(order.product_name, order.product_type) }}</strong><small>{{ order.order_no }} · {{ label(order.external_status) }}</small></div>
                      <time>预计 {{ order.due_at }}</time><i>›</i>
                    </button>
                    <div v-if="!dashboardDueOrders.length" class="dv2-empty">暂无临近交付订单</div>
                  </section>
                </div>
              </div>
            </div>

            <section class="dv2-card dv2-dashboard-trend dv2-reference-performance">
              <header><div><h2>医生工作台趋势图</h2><p>近 6 周医生可见订单创建趋势</p></div><span>近 6 周</span></header>
              <div class="dv2-trend-summary dv2-reference-trend-summary">
                <article class="is-blue"><small>本月订单</small><strong>{{ dataset.orders.filter((item) => doctorLocalDateKey(item.created_at).startsWith(dashboardToday.slice(0, 7))).length }}</strong><i /></article>
                <article class="is-violet"><small>待确认</small><strong>{{ dataset.orders.filter((item) => item.current_action.includes('REVIEW')).length }}</strong><i /></article>
                <article class="is-amber"><small>待付款</small><strong>{{ dataset.orders.filter((item) => item.current_action === 'PAYMENT_REQUIRED').length }}</strong><i /></article>
                <article class="is-green"><small>已完成</small><strong>{{ dataset.orders.filter((item) => item.external_status === 'COMPLETED').length }}</strong><i /></article>
              </div>
              <div class="dv2-dashboard-trend-body dv2-reference-trend-body">
                <div class="dv2-trend-chart">
                  <svg viewBox="0 0 560 132" role="img" aria-label="近六周订单趋势">
                    <line v-for="y in [32, 68, 104]" :key="y" x1="20" :y1="y" x2="548" :y2="y" />
                    <polyline :points="dashboardTrendPoints" />
                    <circle v-for="(value, index) in dashboardWeeklyCounts" :key="index" :cx="24 + index * 103" :cy="104 - Math.round(value / dashboardTrendMax * 72)" r="4" />
                  </svg>
                  <div><span v-for="index in 6" :key="index">第{{ index }}周</span></div>
                </div>
              </div>
            </section>
          </section>

          <section v-else-if="activePage === 'orders'" class="dv2-orders" data-testid="doctor-page-orders">
            <div class="dv2-card dv2-list-card">
              <div class="dv2-list-toolbar">
                <label class="dv2-field-search"><span>⌕</span><input v-model="orderKeyword" type="search" placeholder="搜索订单、患者、诊所、产品或标签" @input="orderPage = 1"></label>
                <select v-model="orderStatus" @change="orderPage = 1"><option value="ALL">全部状态</option><option value="DRAFT">草稿</option><option value="NEEDS_INFO">待补资料</option><option value="IN_PRODUCTION">制作中</option><option value="AWAITING_PAYMENT">待付款</option><option value="SHIPPED">已发货</option><option value="COMPLETED">已完成</option></select>
                <select v-model="orderProduct" @change="orderPage = 1"><option value="ALL">全部产品</option><option v-for="type in orderProductTypes" :key="type" :value="type">{{ productTypeLabel(type) }}</option></select>
                <button type="button" class="dv2-filter-toggle" :class="{ active: orderFiltersExpanded }" @click="orderFiltersExpanded = !orderFiltersExpanded">⚙ 高级筛选 <i>{{ orderFiltersExpanded ? '⌃' : '⌄' }}</i></button>

              </div>
              <div v-if="orderFiltersExpanded" class="dv2-advanced-filters">
                <label><span>负责医生</span><select v-model="orderDoctor" @change="orderPage = 1"><option value="ALL">全部医生</option><option v-for="doctor in orderDoctors" :key="doctor" :value="doctor">{{ doctor }}</option></select></label>
                <label><span>订单标签</span><select v-model="orderTag" @change="orderPage = 1"><option value="ALL">全部标签</option><option v-for="tag in orderTags" :key="tag" :value="tag">{{ tag }}</option></select></label>
                <label><span>创建日期从</span><input v-model="orderDateFrom" type="date" @change="orderPage = 1"></label>
                <label><span>到</span><input v-model="orderDateTo" type="date" @change="orderPage = 1"></label>
                <button type="button" @click="resetOrderFilters">重置筛选</button>
              </div>
              <div class="dv2-quick-filters">
                <button v-for="item in [{ key: 'ALL', label: '全部订单' }, { key: 'TODO', label: '待我处理' }, { key: 'DUE', label: '临近到期' }, { key: 'DELIVERY', label: '配送中' }, { key: 'PAYMENT', label: '待付款' }, { key: 'DRAFT', label: '草稿箱' }]" :key="item.key" type="button" :class="{ active: orderQuick === item.key }" @click="orderQuick = item.key; orderPage = 1">{{ item.label }}</button>
                <span v-if="selectedOrderIds.length">已选 {{ selectedOrderIds.length }} 项</span>
              </div>
              <div class="dv2-table-wrap">
                <table class="dv2-table dv2-order-table">
                  <thead><tr><th class="is-check"><input type="checkbox" :checked="pagedOrders.length > 0 && pagedOrders.every((item) => selectedOrderIds.includes(item.order_id))" aria-label="选择当前页" @change="togglePageSelection(($event.target as HTMLInputElement).checked)"></th><th>订单</th><th>医生 / 患者</th><th>诊所</th><th>产品</th><th>标签</th><th>公开状态</th><th>当前操作</th><th>创建 / 到期</th><th>金额</th><th /></tr></thead>
                  <tbody>
                    <tr v-for="order in pagedOrders" :key="order.order_id" data-testid="doctor-order-row" tabindex="0" :aria-label="`查看订单 ${order.order_no}`" @click="openOrder(order.order_id)" @keydown.enter.prevent="openOrder(order.order_id)" @keydown.space.prevent="openOrder(order.order_id)">
                      <td class="is-check"><input type="checkbox" :checked="selectedOrderIds.includes(order.order_id)" :aria-label="`选择 ${order.order_no}`" @click.stop @change="toggleOrderSelection(order.order_id, ($event.target as HTMLInputElement).checked)"></td>
                      <td><strong class="dv2-link-strong">{{ order.order_no }}</strong><small>#{{ order.order_id }}</small></td>
                      <td><strong>{{ order.doctor_name }}</strong><small>{{ order.patient_name }} · {{ order.patient_code }}</small></td>
                      <td>{{ order.clinic_name }}</td><td><strong>{{ productNameLabel(order.product_name, order.product_type) }}</strong><small>{{ productTypeLabel(order.product_type) }}</small></td>
                      <td><span v-for="tag in order.tags" :key="tag" class="dv2-tag">{{ tag }}</span><span v-if="!order.tags.length">-</span></td>
                      <td><span :class="`dv2-status is-${statusTone(order.external_status)}`">{{ label(order.external_status) }}</span></td>
                      <td><span :class="{ 'dv2-action-text': order.current_action !== 'NONE' }">{{ label(order.current_action) }}</span></td>
                      <td><span>{{ compactDoctorDateTime(order.created_at) }}</span><small>到期 {{ order.due_at }}</small></td><td>{{ money(order.quote) }}</td>
                      <td><span class="dv2-row-chevron" aria-hidden="true">›</span></td>
                    </tr>
                  </tbody>
                </table>
                <div v-if="!pagedOrders.length" class="dv2-empty">没有符合当前条件的订单</div>
              </div>
              <footer class="dv2-pagination"><span>共 {{ orderRows.length }} 项</span><el-pagination v-model:current-page="orderPage" size="small" background layout="prev, pager, next" :page-size="orderPageSize" :total="orderRows.length" /></footer>
            </div>
          </section>

          <section v-else-if="activePage === 'assistant'" class="dv2-assistant" data-testid="doctor-page-assistant">
            <div class="dv2-card dv2-assistant-card">
              <header><span class="dv2-assistant-mark">✦</span><div><h2>订单助手</h2><p>可查询您当前身份有权查看的订单、账单、物流与消息信息，也可以直接问下单流程、材料、交期等常见问题</p></div></header>
              <div class="dv2-assistant-suggestions">
                <button v-for="question in ['哪些订单需要我处理？', '查看本周预计到期的订单', '有哪些账单待付款？']" :key="question" type="button" @click="assistantQuestion = question; askAssistant()">{{ question }}</button>
              </div>
              <div class="dv2-assistant-suggestions is-faq">
                <span class="dv2-assistant-faq-label">常见问题</span>
                <button v-for="question in ['下单需要提供哪些资料？', '口扫文件支持哪些格式？', '订单大概多久能做好？', '做出来不合适需要返工怎么办？']" :key="question" type="button" @click="askFaq(question)">{{ question }}</button>
              </div>
              <div class="dv2-chat-stream">
                <article v-for="(message, index) in assistantMessages" :key="index" :class="{ self: message.role === 'SELF' }">
                  <span>{{ message.role === 'SELF' ? (account?.display_name || '我').slice(0, 1) : '✦' }}</span>
                  <div><p>{{ message.content }}</p><button v-for="orderId in message.orderIds" :key="orderId" type="button" @click="openGlobalOrder(orderId)">查看 {{ dataset.orders.find((item) => item.order_id === orderId)?.order_no }} →</button></div>
                </article>
                <article v-if="assistantLoading"><span>✦</span><div><p>正在查询…</p></div></article>
              </div>
              <form class="dv2-chat-composer" @submit.prevent="askAssistant"><textarea v-model="assistantQuestion" rows="2" placeholder="输入订单号、患者编号或您想查询的问题…" /><button type="submit" :disabled="assistantLoading || !assistantQuestion.trim()">发送</button></form>
              <small class="dv2-scope-note">助手仅返回当前身份可见的公开业务信息，结果以订单页面为准。</small>
            </div>
          </section>

          <section v-else-if="activePage === 'patients'" class="dv2-patients" data-testid="doctor-page-patients">
            <div class="dv2-card dv2-list-card">
              <div class="dv2-patient-list-tools"><div class="dv2-patient-filters"><button v-for="item in [{ key: 'ALL', label: '全部' }, { key: 'IN_TREATMENT', label: '治疗中' }, { key: 'FOLLOW_UP', label: '待复诊' }, { key: 'TREATMENT_ENDED', label: '治疗结束' }, { key: 'ARCHIVED', label: '已归档' }]" :key="item.key" type="button" :class="{ active: patientStatus === item.key }" @click="patientStatus = item.key as typeof patientStatus">{{ item.label }}</button></div><label class="dv2-field-search"><span>⌕</span><input v-model="patientKeyword" type="search" placeholder="搜索患者姓名、编号、电话或标签"></label></div>
              <div class="dv2-table-wrap">
                <table class="dv2-table dv2-patient-table">
                  <thead><tr><th>患者姓名</th><th>诊所</th><th>负责医生</th><th>最近产品</th><th>建档日期</th><th>订单</th><th>治疗状态</th><th>疗程</th><th /></tr></thead>
                  <tbody><tr v-for="patient in patientRows" :key="patient.patient_id" @dblclick="openPatient(patient.patient_id)"><td><button type="button" class="dv2-link-strong" @click="openPatient(patient.patient_id)">{{ patient.patient_name }}</button><small>{{ patient.patient_code }}<span v-if="patient.tags.length"> · {{ patient.tags.join(' / ') }}</span></small></td><td>{{ patient.clinic_name }}</td><td>{{ patient.doctor_name }}</td><td><span>{{ patient.latest_product_name ? productNameLabel(patient.latest_product_name) : '暂无订单' }}</span><small>{{ patient.latest_order_no || '—' }}</small></td><td>{{ patientDate(patient.created_at) }}</td><td class="is-count">{{ patient.order_count }}</td><td><span :class="`dv2-status is-${patientTreatmentTone(patient)}`">● {{ patientTreatmentLabel(patient) }}</span></td><td><span class="dv2-duration-chip">{{ patientDurationLabel(patient) }}</span></td><td><button type="button" class="dv2-row-action is-boxed" @click="openPatient(patient.patient_id)">编辑</button></td></tr></tbody>
                </table>
                <div v-if="!patientRows.length" class="dv2-empty">没有符合当前条件的患者</div>
              </div>
              <footer class="dv2-pagination"><span>共 {{ patientRows.length }} 位患者 · 点击姓名查看完整档案与历史订单</span></footer>
            </div>
          </section>

          <section v-else-if="activePage === 'billing'" class="dv2-billing" data-testid="doctor-page-billing">
            <div class="dv2-billing-stats"><article v-for="item in billingStats" :key="item.label" :class="`is-${item.tone}`"><small>{{ item.label }}</small><strong>{{ item.value }}</strong><span>{{ item.note }}</span></article></div>
            <div class="dv2-billing-alert"><span>!</span><p><strong>账期提示</strong> 按单结算订单需在到期日前完成付款；逾期账单可能影响后续发货安排。</p></div>
            <div class="dv2-tabbar dv2-billing-tabs"><button v-for="item in [{ key: 'perOrder', label: '按单结算' }, { key: 'monthly', label: '月结账单' }, { key: 'invoiceRefund', label: '发票与退款' }, { key: 'logistics', label: '物流追踪' }]" :key="item.key" type="button" :class="{ active: billingTab === item.key }" @click="billingTab = item.key as typeof billingTab">{{ item.label }}</button></div>
            <div class="dv2-card dv2-list-card">
              <template v-if="billingTab === 'perOrder'">
                <div class="dv2-list-toolbar"><div><strong>按单结算</strong><small>按单付款的订单需结清后发货</small></div><div class="dv2-patient-filters"><button v-for="item in [{ key: 'ALL', label: '全部' }, { key: 'UNPAID', label: '待支付' }, { key: 'OVERDUE', label: '已逾期' }, { key: 'PAID', label: '已支付' }]" :key="item.key" type="button" :class="{ active: billingStatus === item.key }" @click="billingStatus = item.key as typeof billingStatus">{{ item.label }}</button></div></div>
                <div class="dv2-table-wrap"><table class="dv2-table"><thead><tr><th>账单 / 订单</th><th>诊所 / 医生</th><th>产品</th><th>账单金额</th><th>已付</th><th>待付</th><th>状态</th><th>到期</th><th /></tr></thead><tbody><tr v-for="bill in billingRows.filter((item) => item.settlement_type === 'PER_ORDER')" :key="bill.bill_id"><td><strong>{{ bill.bill_id }}</strong><small>{{ bill.order_no }}</small></td><td>{{ bill.clinic_name }}<small>{{ bill.doctor_name }}</small></td><td>{{ productNameLabel(bill.product_name) }}</td><td>{{ money(bill.amount) }}</td><td>{{ money(bill.paid) }}</td><td>{{ money(bill.outstanding) }}</td><td><span :class="`dv2-status is-${statusTone(bill.payment_status)}`">{{ bill.outstanding.amount_minor > 0 && bill.due_at < dashboardToday ? '已逾期' : label(bill.payment_status) }}</span></td><td>{{ bill.due_at }}</td><td><button v-if="bill.allowed_actions.includes('PAY_BILL')" type="button" class="dv2-row-action is-primary" @click="ElMessage.info('在线付款暂未开放，请联系订单支持')">去付款</button><button v-else type="button" class="dv2-row-action" @click="openOrder(bill.order_id)">查看订单</button></td></tr></tbody></table><div v-if="!billingRows.some((item) => item.settlement_type === 'PER_ORDER')" class="dv2-empty">暂无符合筛选条件的账单</div></div>
              </template>
              <template v-else-if="billingTab === 'monthly'">
                <div class="dv2-list-toolbar"><div><strong>月结账单</strong><small>月结订单可先发货，在账期内统一结算</small></div></div>
                <div class="dv2-table-wrap"><table class="dv2-table"><thead><tr><th>账期</th><th>诊所</th><th>订单数</th><th>账单总额</th><th>已付</th><th>待付</th><th>状态</th><th>到期日</th><th /></tr></thead><tbody><tr v-for="statement in dataset.statements" :key="statement.statement_id"><td><strong>{{ statement.period }}</strong><small>{{ statement.statement_id }}</small></td><td>{{ statement.clinic_name }}</td><td>{{ statement.order_count }}</td><td>{{ money(statement.total) }}</td><td>{{ money(statement.paid) }}</td><td>{{ money(statement.balance) }}</td><td><span :class="`dv2-status is-${statusTone(statement.status)}`">{{ label(statement.status) }}</span></td><td>{{ statement.due_at }}</td><td><button type="button" class="dv2-row-action">查看明细 →</button></td></tr></tbody></table><div v-if="!dataset.statements.length" class="dv2-empty">暂无月结账单</div></div>
              </template>
              <template v-else-if="billingTab === 'invoiceRefund'">
                <div class="dv2-list-toolbar"><div><strong>发票与退款</strong><small>集中查看发票开具和退款申请进度</small></div><button type="button" class="dv2-secondary-button" :disabled="bulkInvoiceDownloading || downloadableInvoiceRefunds.length === 0" @click="downloadAllInvoices">{{ bulkInvoiceDownloading ? '下载中…' : `下载全部 (${downloadableInvoiceRefunds.length})` }}</button><button type="button" class="dv2-secondary-button" @click="ElMessage.info('在线申请暂未开放，请联系订单支持')">＋ 发起申请</button></div>
                <div class="dv2-table-wrap"><table class="dv2-table"><thead><tr><th>记录号</th><th>类型</th><th>关联编号</th><th>抬头 / 说明</th><th>金额</th><th>状态</th><th>申请时间</th><th /></tr></thead><tbody><tr v-for="record in dataset.invoiceRefunds" :key="record.record_id"><td><strong>{{ record.record_id }}</strong></td><td>{{ record.kind === 'INVOICE' ? '发票' : '退款' }}</td><td>{{ record.related_no }}</td><td>{{ record.title }}</td><td>{{ money(record.amount) }}</td><td><span :class="`dv2-status is-${statusTone(record.status)}`">{{ label(record.status) }}</span></td><td>{{ record.created_at }}</td><td><button type="button" class="dv2-row-action" @click="downloadInvoice(record.record_id)">{{ record.kind === 'INVOICE' ? '下载 PDF' : '下载记录' }} →</button></td></tr></tbody></table><div v-if="!dataset.invoiceRefunds.length" class="dv2-empty">暂无发票或退款记录</div></div>
              </template>
              <template v-else>
                <div class="dv2-list-toolbar"><div><strong>物流</strong><small>物流信息仅在此处集中展示；已送达后需医生确认收货</small></div><label class="dv2-field-search is-small"><span>⌕</span><input type="search" placeholder="搜索订单或运单号"></label></div>
                <div class="dv2-table-wrap"><table class="dv2-table"><thead><tr><th>订单</th><th>产品</th><th>物流公司</th><th>运单号</th><th>物流状态</th><th>更新时间</th><th /></tr></thead><tbody><tr v-for="item in dataset.logistics" :key="item.logistics_id"><td><strong>{{ item.order_no }}</strong></td><td>{{ productNameLabel(item.product_name) }}</td><td>{{ item.carrier }}</td><td class="dv2-mono">{{ item.tracking_no }}</td><td><span :class="`dv2-status is-${statusTone(item.status)}`">{{ label(item.status) }}</span></td><td>{{ item.updated_at }}</td><td><button v-if="item.can_confirm_receipt" type="button" class="dv2-row-action is-primary" @click="confirmReceipt(item)">确认收货</button><button v-else type="button" class="dv2-row-action" @click="openLogistics(item)">物流详情 →</button></td></tr></tbody></table><div v-if="!dataset.logistics.length" class="dv2-empty">暂无物流记录</div></div>
              </template>
            </div>
          </section>

          <section v-else-if="activePage === 'messages'" class="dv2-messages" data-testid="doctor-page-messages">
            <div class="dv2-message-layout">
              <aside class="dv2-thread-panel">
                <div class="dv2-thread-search"><label><span>⌕</span><input v-model="messageKeyword" type="search" placeholder="搜索订单、患者或消息"></label><div><button v-for="item in [{ key: 'ALL', label: '全部' }, { key: 'UNREAD', label: '未读' }, { key: 'READ', label: '已读' }]" :key="item.key" type="button" :class="{ active: messageFilter === item.key }" @click="messageFilter = item.key as typeof messageFilter">{{ item.label }}</button></div></div>
                <button v-for="thread in filteredThreads" :key="thread.thread_id" type="button" class="dv2-thread-row" :class="{ active: activeThread?.thread_id === thread.thread_id }" title="右键标记为未读" @click="chooseThread(thread.thread_id)" @contextmenu.prevent="markThreadUnread(thread.thread_id)"><span class="dv2-thread-avatar">{{ productNameLabel(thread.product_name).slice(0, 1) }}</span><div><strong>{{ thread.patient_name }} · {{ productNameLabel(thread.product_name) }}</strong><small>{{ thread.order_no }}</small><p>{{ thread.latest_message }}</p></div><time>{{ thread.latest_at }}</time><i v-if="thread.unread" /></button>
                <div v-if="!filteredThreads.length" class="dv2-empty">没有符合筛选条件的沟通</div>
              </aside>
              <section v-if="activeThread" class="dv2-conversation">
                <header><div><h2>{{ activeThread.patient_name }} · {{ productNameLabel(activeThread.product_name) }}</h2><p>{{ activeThread.order_no }} <span class="dv2-translation-chip">A/文 可翻译</span></p></div><button type="button" class="dv2-secondary-button" @click="openGlobalOrder(activeThread.order_id)">查看订单</button></header>
                <div class="dv2-message-stream">
                  <article v-for="message in activeThread.messages" :key="message.message_id" :class="{ self: message.sender === 'SELF' }"><span>{{ message.sender === 'SELF' ? (account?.display_name || '我').slice(0, 1) : '单' }}</span><div><small>{{ message.sender === 'SELF' ? '我' : '订单服务' }} · {{ message.sent_at }}</small><p>{{ message.content }}</p><section v-if="message.review" class="dv2-review-card"><header><div><strong>{{ reviewLabel(message.review.review_type) }}</strong><small>当前版本 V{{ message.review.current_version }}</small></div><span :class="`dv2-status is-${statusTone(message.review.status)}`">{{ label(message.review.status) }}</span></header><div class="dv2-version-list"><article v-for="version in [...message.review.versions].reverse()" :key="version.version"><div><strong>V{{ version.version }}</strong><span>{{ label(version.status) }}</span><small>{{ version.submitted_at }}</small></div><button v-for="attachment in version.files" :key="attachment.file_id" type="button" @click="previewFile(attachment)"><i>{{ attachment.kind }}</i><span>{{ attachment.name }}<small>{{ attachment.size_label }}</small></span><em>预览</em></button><p v-if="version.doctor_comment">医生意见：{{ version.doctor_comment }}</p></article></div><footer v-if="message.review.status === 'PENDING_REVIEW'"><template v-if="canReview && message.review.allowed_actions.some((action) => ['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))"><button v-if="message.review.allowed_actions.includes('REJECT_REVIEW')" type="button" class="dv2-danger-button" :disabled="reviewSubmitting" @click="startReviewDecision(activeThread.order_id, message.review, 'REJECT')">驳回并留言</button><button v-if="message.review.allowed_actions.includes('APPROVE_REVIEW')" type="button" class="dv2-primary-button" :disabled="reviewSubmitting" @click="startReviewDecision(activeThread.order_id, message.review, 'APPROVE')">同意当前版本</button></template><p v-else>当前账号不能执行此操作。</p></footer></section></div></article>
                </div>
                <div class="dv2-quick-replies"><span>快捷回复</span><button v-for="reply in ['收到，我会尽快确认。', '请补充一张更清晰的照片。', '请按当前版本继续。']" :key="reply" type="button" @click="messageDraft = reply">{{ reply }}</button></div>
                <form class="dv2-message-composer" @submit.prevent="sendMessage"><textarea v-model="messageDraft" rows="2" placeholder="输入订单沟通内容…" @keydown.ctrl.enter.prevent="sendMessage" /><footer><span>Ctrl + Enter 发送</span><button type="submit" :disabled="sendingMessage || !messageDraft.trim()">发送</button></footer></form>
              </section>
              <div v-else class="dv2-empty dv2-no-thread">请选择一条沟通</div>
            </div>
          </section>

          <section v-else-if="activePage === 'account'" class="dv2-account" data-testid="doctor-page-account">
            <div class="dv2-account-page">
              <div class="dv2-tabbar dv2-settings-tabs"><button v-for="item in [{ key: 'profile', label: '账户与诊所', note: '基本资料和诊所信息' }, { key: 'members', label: '成员与权限', note: '诊所成员和角色范围' }, { key: 'notifications', label: '通知偏好', note: '站内与邮件提醒' }, { key: 'security', label: '安全设置', note: '密码和登录安全' }]" :key="item.key" type="button" :class="{ active: accountTab === item.key }" @click="accountTab = item.key as typeof accountTab"><strong>{{ item.label }}</strong></button></div>
              <section class="dv2-card dv2-settings-content">
                <template v-if="accountTab === 'profile'"><header><div><h2>账户与诊所</h2><p>维护对外展示、账单和配送所需的基础资料</p></div><button v-if="canManageMembers" type="button" class="dv2-secondary-button" @click="ElMessage.info('新增诊所暂未开放，请联系订单支持')">＋ 添加诊所</button></header><div class="dv2-clinic-selector"><span class="dv2-avatar">{{ dataset.account.clinic_name.slice(0, 1) }}</span><div><small>当前诊所</small><strong>{{ dataset.account.clinic_name }}</strong><p>{{ dataset.account.clinic_address }}</p></div><i>✓</i></div><div class="dv2-form-grid"><label><span>医生姓名</span><input v-model="dataset.account.display_name"></label><label><span>登录邮箱</span><input v-model="dataset.account.email" type="email"></label><label><span>诊所名称</span><input v-model="dataset.account.clinic_name"></label><label><span>诊所联系电话</span><input v-model="dataset.account.clinic_contact"></label><label class="is-full"><span>诊所地址</span><textarea v-model="dataset.account.clinic_address" rows="3" /></label><label><span>账单抬头</span><input :value="dataset.account.clinic_name" @input="dataset.account.clinic_name = ($event.target as HTMLInputElement).value"></label><label><span>配送联系人</span><input :value="dataset.account.display_name" readonly></label><div class="is-full dv2-doc-upload"><span>诊所资质文件</span><button type="button" @click="ElMessage.info('资质文件上传暂未开放，请联系订单支持')">＋ 上传营业执照或医疗机构执业许可证</button></div></div><footer><button type="button" class="dv2-primary-button" @click="saveProfile">保存设置</button></footer></template>
                <template v-else-if="accountTab === 'members'"><header><div><h2>成员与权限</h2><p>分别设置诊所成员可查看的账单和物流内容</p></div><button v-if="canManageMembers" type="button" class="dv2-primary-button" @click="memberDialogOpen = true">＋ 邀请成员</button></header><div v-if="!canManageMembers" class="dv2-inline-notice">当前身份可查看成员，但只有诊所管理员可以邀请或调整诊所端角色。</div><div class="dv2-member-list"><article v-for="member in dataset.account.members" :key="member.member_id"><span class="dv2-avatar">{{ member.display_name.slice(0, 1) }}</span><div><strong>{{ member.display_name }}</strong><small>{{ member.email }}</small></div><p><span v-for="role in member.roles" :key="role" class="dv2-tag">{{ roleLabels[role] }}</span></p><p><small>账单：{{ member.billing_permission }} · 物流：{{ member.logistics_permission }}</small></p><span :class="`dv2-status is-${statusTone(member.status)}`">{{ label(member.status) }}</span><button type="button" :disabled="!canManageMembers">⋯</button></article><div v-if="!dataset.account.members.length" class="dv2-empty">暂无诊所成员</div></div></template>
                <template v-else-if="accountTab === 'notifications'"><header><h2>通知偏好</h2><p>分别设置站内通知和邮件提醒</p></header><div class="dv2-preference-table"><div class="head"><strong>通知类型</strong><span>站内</span><span>邮件</span></div><div v-for="(preference, key) in dataset.account.notification_preferences" :key="key"><strong>{{ ({ ORDER_STATUS: '订单状态', REVIEW_REQUEST: '确认事项', MESSAGE: '订单消息', BILLING: '账单提醒', LOGISTICS: '物流提醒' } as Record<string, string>)[key] || key }}</strong><el-switch v-model="preference.in_app" /><el-switch v-model="preference.email" /></div><div v-if="!Object.keys(dataset.account.notification_preferences).length" class="dv2-empty">暂无可设置的通知</div></div><footer><button type="button" class="dv2-primary-button" @click="saveProfile">保存偏好</button></footer></template>
                <template v-else><header><h2>安全设置</h2><p>更新登录密码并保持账户安全</p></header><div class="dv2-form-stack"><label><span>当前密码</span><input v-model="passwordForm.current" type="password" autocomplete="current-password"></label><label><span>新密码</span><input v-model="passwordForm.next" type="password" autocomplete="new-password"><small>至少 8 位，建议包含大小写字母和数字</small></label><label><span>确认新密码</span><input v-model="passwordForm.confirm" type="password" autocomplete="new-password"></label></div><footer><button type="button" class="dv2-primary-button" @click="updatePassword">更新密码</button></footer></template>
              </section>
            </div>
          </section>
        </template>
      </main>
    </section>

    <div v-if="notificationOpen" class="dv2-drawer-mask" @mousedown.self="notificationOpen = false">
      <aside class="dv2-notification-drawer" data-testid="doctor-notification-drawer"><header><div><h2>通知中心</h2><p>{{ unreadCount }} 条未读通知</p></div><button type="button" aria-label="关闭通知中心" @click="notificationOpen = false">×</button></header><div class="dv2-notification-tools"><label><span>⌕</span><input v-model="notificationKeyword" type="search" placeholder="搜索通知"></label><button type="button" @click="markAllNotifications">全部已读</button></div><div class="dv2-chip-row"><button v-for="item in [{ key: 'ALL', label: '全部' }, { key: 'UNREAD', label: '未读' }, { key: 'READ', label: '已读' }]" :key="item.key" type="button" :class="{ active: notificationFilter === item.key }" @click="selectAllNotificationFilter(item.key as typeof notificationFilter)">{{ item.label }}</button></div><div class="dv2-notification-list"><button v-for="item in filteredNotifications" :key="item.notification_id" type="button" :class="{ unread: !item.read }" @click="openNotification(item.notification_id)"><span :class="`dv2-notification-icon is-${item.category.toLowerCase()}`">{{ categoryLabels[item.category].slice(0, 1) }}</span><div><strong>{{ item.title }}</strong><p>{{ item.summary }}</p><small>{{ item.created_at }} · {{ categoryLabels[item.category] }}</small></div><i v-if="!item.read" /></button><div v-if="!filteredNotifications.length" class="dv2-empty">没有符合筛选条件的通知</div></div></aside>
    </div>

    <div v-if="orderDrawerOpen" class="dv2-drawer-mask is-order-reference" @mousedown.self="orderDrawerOpen = false">
      <aside class="dv2-order-drawer" data-testid="doctor-order-drawer">
        <header>
          <div>
            <small>订单详情</small>
            <h2>{{ selectedOrder?.order_no || '正在加载' }}</h2>
          </div>
          <button type="button" aria-label="关闭订单详情" @click="orderDrawerOpen = false">×</button>
        </header>

        <div v-if="orderDetailLoading" class="dv2-loading-card"><span class="dv2-spinner" />正在读取订单详情…</div>
        <template v-else-if="selectedOrder">
          <div class="dv2-drawer-summary">
            <div><small>患者</small><span>{{ selectedOrder.patient_name }}</span><em v-if="selectedOrder.patient_code && selectedOrder.patient_code !== '-'">{{ selectedOrder.patient_code }}</em></div>
            <div><small>牙位</small><span>{{ selectedOrderToothText }}</span></div>
            <div><small>产品</small><span>{{ productNameLabel(selectedOrder.product_name, selectedOrder.product_type) }}</span></div>
            <div><small>诊所</small><span>{{ selectedOrder.clinic_name }}</span></div>
            <div><small>负责医生</small><span>{{ selectedOrder.doctor_name }}</span></div>
            <div class="is-amount"><small>订单金额</small><span>{{ money(selectedOrder.quote) }}</span></div>
            <div><small>订单创建时间</small><span>{{ compactDoctorDateTime(selectedOrder.created_at) }}</span></div>
            <div><small>预计到期</small><span>{{ compactDoctorDateTime(selectedOrder.due_at) }}</span></div>
            <div class="is-status"><small>公开状态</small><span :class="`dv2-status is-${statusTone(selectedOrder.external_status)}`">{{ label(selectedOrder.external_status) }}</span></div>
            <div class="is-tags"><small>订单标签</small><p><span v-for="tag in selectedOrder.tags" :key="tag" class="dv2-tag">{{ tag }}</span><em v-if="!selectedOrder.tags.length">暂无标签</em></p></div>
          </div>

          <div class="dv2-drawer-body">
            <section v-if="selectedOrder.external_status === 'DRAFT' && selectedOrder.group_id" class="dv2-detail-section dv2-action-alert">
              <div class="dv2-current-action">
                <div><strong>病例订单草稿尚未提交</strong><p>继续编辑该病例下的全部产品和资料。</p></div>
                <button type="button" class="dv2-primary-button" data-testid="doctor-resume-case-group" @click="resumeSelectedCaseGroup">继续编辑订单</button>
              </div>
            </section>
            <section v-if="selectedOrder.current_action !== 'NONE'" class="dv2-detail-section dv2-action-alert">
              <div class="dv2-current-action">
                <div><strong>{{ label(selectedOrder.current_action) }}</strong><p>完成后订单将按公开流程继续推进。</p></div>
                <button v-if="selectedOrder.current_action === 'PAYMENT_REQUIRED'" type="button" class="dv2-primary-button" @click="orderDrawerOpen = false; switchPage('billing')">去付款</button>
              </div>
            </section>

            <section v-if="deliveryPlanLoading" class="dv2-detail-section">
              <h3>交期与过程确认</h3>
              <div class="dv2-loading-card"><span class="dv2-spinner" />正在读取交期计划…</div>
            </section>
            <section v-else-if="deliveryPlan" class="dv2-detail-section dv2-delivery-plan" data-testid="doctor-delivery-plan">
              <h3>交期与过程确认</h3>

              <div v-if="deliveryPlan.estimate_status === 'PLACEHOLDER'" class="dv2-delivery-placeholder" data-testid="doctor-delivery-placeholder">
                <strong>预计到货时间待确认</strong>
                <p>以下时间按暂定标准周期估算，尚未成为正式承诺交期：{{ deliveryPlan.placeholder_rules.join('、') }}。正式交期由订单服务受理后确认。</p>
              </div>

              <dl class="dv2-delivery-grid">
                <div><dt>预计到货</dt><dd data-testid="doctor-delivery-date">{{ deliveryDateLabel(deliveryPlan) }}</dd></div>
                <div><dt>制作天数</dt><dd>{{ deliveryPlan.production_days }} 天</dd></div>
                <div><dt>在途天数</dt><dd>{{ deliveryPlan.transit_days }} 天</dd></div>
                <div><dt>过程确认</dt><dd>{{ deliveryPlan.process_confirmation_count }} 项 · +{{ deliveryPlan.process_confirmation_days }} 天</dd></div>
                <div v-if="deliveryPlan.waiting_days > 0"><dt>等待顺延</dt><dd>+{{ deliveryPlan.waiting_days }} 天</dd></div>
              </dl>

              <div v-if="deliveryPlan.delivery_alert_message" class="dv2-delivery-alert" data-testid="doctor-delivery-alert">
                {{ deliveryPlan.delivery_alert_message }}
              </div>

              <div class="dv2-delivery-adjust">
                <label>
                  <span>要求到货时间</span>
                  <input v-model="requestedDeliveryDateDraft" type="date" data-testid="doctor-requested-delivery-date">
                </label>
                <button type="button" class="dv2-secondary-button" :disabled="deliveryPlanBusy" data-testid="doctor-save-requested-delivery-date" @click="saveRequestedDeliveryDate">保存到货时间</button>
              </div>

              <div v-if="deliveryPlan.process_confirmations.length" class="dv2-delivery-confirmations">
                <article v-for="confirmation in deliveryPlan.process_confirmations" :key="confirmation.confirmation_code" :class="{ overdue: confirmation.overdue }">
                  <div>
                    <strong>{{ confirmation.confirmation_name }}</strong>
                    <small>
                      {{ ({ PLANNED: '尚未到达该环节', AWAITING_DOCTOR: '等待您确认', CONFIRMED: '已确认', REJECTED: '已要求修改' } as Record<string, string>)[confirmation.confirmation_status] }}
                      <template v-if="confirmation.overdue"> · 已超期 {{ confirmation.waiting_days }} 天，交期已顺延</template>
                    </small>
                  </div>
                  <div v-if="confirmation.confirmation_status === 'AWAITING_DOCTOR'" class="dv2-delivery-confirm-actions">
                    <button type="button" class="dv2-primary-button" :disabled="deliveryPlanBusy" @click="respondProcessConfirmation(confirmation, true)">确认</button>
                    <button type="button" class="dv2-secondary-button" :disabled="deliveryPlanBusy" @click="respondProcessConfirmation(confirmation, false)">要求修改</button>
                  </div>
                </article>
              </div>

              <div v-if="deliveryPlan.try_in.try_in_required" class="dv2-delivery-tryin" data-testid="doctor-try-in">
                <strong>试戴</strong>
                <p>
                  {{ ({ REQUESTED: '已登记试戴需求，等待工厂安排', COMPLETED: '试戴已完成，可在本订单继续选择成品与材料，无需新建订单', FINALIZED: '成品已选定' } as Record<string, string>)[deliveryPlan.try_in.try_in_status ?? 'REQUESTED'] }}
                </p>
              </div>

              <div v-if="deliveryPlan.bill_items.length" class="dv2-delivery-bill-items" data-testid="doctor-bill-items">
                <strong>计价项</strong>
                <ul>
                  <li v-for="item in deliveryPlan.bill_items" :key="item.item_code">
                    <span>{{ item.item_name }}</span>
                    <em>{{ item.pricing_status === 'PRICED' && item.amount_cents !== null ? `${(item.amount_cents / 100).toFixed(2)} ${item.currency}` : '待报价' }}</em>
                  </li>
                </ul>
              </div>
            </section>

            <section class="dv2-detail-section">
              <h3>公开进度</h3>
              <div class="dv2-progress">
                <article v-for="item in selectedOrder.progress" :key="item.key" :class="item.status.toLowerCase()">
                  <span>{{ item.status === 'DONE' ? '✓' : ({ submitted: '📥', review: '🔎', design: '✏️', production: '⚙️', 'final-review': '✅', 'ready-to-ship': '📦', shipped: '🚀', completed: '✓' } as Record<string, string>)[item.key] || '•' }}</span>
                  <div>
                    <strong>{{ item.label }}</strong>
                    <small>{{ item.status === 'DONE' ? '已完成' : item.status === 'ACTIVE' ? '⚡ 进行中' : '待开始' }}<template v-if="item.occurred_at"> · {{ compactDoctorDateTime(item.occurred_at) }}</template></small>
                    <p v-if="item.note">{{ item.note }}</p>
                  </div>
                </article>
              </div>
              <div class="dv2-public-message">{{ selectedOrder.public_message }}</div>
              <div class="dv2-reference-actions">
                <button type="button" class="dv2-secondary-button" @click="openSelectedOrderConversation">💬 进入订单沟通</button>
              </div>
              <div class="dv2-order-lock-note">{{ selectedOrder.current_action === 'NONE' ? '🔒 订单正在按公开流程处理，如需调整请直接在下方联系订单服务。' : 'ℹ️ 完成当前待办后订单将继续推进；如需协助，可直接在本抽屉发送消息。' }}</div>
            </section>

            <section class="dv2-detail-section">
              <h3>🦷 订单资料与临床要求</h3>
              <div v-if="selectedOrder.review_options.length" class="dv2-order-flags">
                <span v-for="reviewType in selectedOrder.review_options" :key="reviewType">{{ reviewType === 'CAD_DESIGN' ? '✏️' : '📸' }} {{ reviewLabel(reviewType) }}</span>
              </div>
              <div class="dv2-tooth-chart-card">
                <div class="dv2-tooth-chart-title"><strong>牙位选择</strong><span>已选：{{ selectedOrderToothText }}</span></div>
                <template v-if="selectedOrderTeeth.size">
                  <small>上颌</small>
                  <div class="dv2-tooth-row"><span v-for="tooth in upperTeeth" :key="tooth" :class="{ selected: selectedOrderTeeth.has(tooth) }">{{ tooth }}</span></div>
                  <small>下颌</small>
                  <div class="dv2-tooth-row"><span v-for="tooth in lowerTeeth" :key="tooth" :class="{ selected: selectedOrderTeeth.has(tooth) }">{{ tooth }}</span></div>
                </template>
                <p v-else>当前牙位以订单原始文本记录：{{ selectedOrderToothText }}</p>
              </div>
              <dl class="dv2-detail-grid">
                <div v-for="item in selectedOrderSpecEntries" :key="item.key"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div>
              </dl>
              <div class="dv2-clinical-note"><strong>📝 医生临床说明</strong><p>{{ selectedOrderClinicalNotes || '暂未填写额外临床说明。' }}</p></div>
            </section>

            <section class="dv2-detail-section">
              <h3>📁 订单文件与图片</h3>
              <div class="dv2-file-list">
                <button v-for="item in selectedOrder.files" :key="item.file_id" type="button" @click="previewFile(item)"><i><img v-if="item.kind === 'IMAGE' && item.preview_url" :src="item.preview_url" :alt="item.name"><template v-else>{{ fileGlyph(item) }}</template></i><div><strong>{{ item.name }}</strong><small>{{ item.kind }} · {{ item.size_label }} · {{ compactDoctorDateTime(item.uploaded_at) }}</small></div><span>预览 ↗</span></button>
                <div v-if="!selectedOrder.files.length" class="dv2-empty">暂无医生可见文件</div>
              </div>
            </section>

            <section class="dv2-detail-section">
              <h3>📊 订单时间线</h3>
              <div class="dv2-order-timeline">
                <article v-for="item in orderTimelineItems" :key="item.key" :class="`is-${item.tone}`">
                  <time>{{ doctorTimelineDateTime(item.occurredAt) }}</time>
                  <div><strong>{{ item.title }}</strong><span>{{ item.actor }}</span></div>
                </article>
                <div v-if="!orderTimelineItems.length" class="dv2-empty">此订单暂无公开时间线记录</div>
              </div>
            </section>

            <section class="dv2-detail-section">
              <h3>💬 信息与设计评测</h3>
              <p class="dv2-section-note">订单沟通、设计确认记录和医生反馈集中展示；可直接在这里回复订单服务。</p>
              <div class="dv2-order-dialogue" data-testid="doctor-order-dialogue">
                <article v-for="message in selectedOrder.messages" :key="message.message_id" class="dv2-order-bubble" :class="{ 'is-self': message.sender === 'SELF' }">
                  <strong>{{ message.sender === 'SELF' ? selectedOrder.doctor_name : '订单服务' }}</strong>
                  <p>{{ message.content }}</p>
                  <time>{{ preciseDoctorDateTime(message.sent_at) }}</time>
                </article>
              <div v-for="review in selectedOrder.reviews" :key="review.review_id" class="dv2-review-card is-drawer">
                <header><div><strong>📐 {{ reviewLabel(review.review_type) }}</strong><small>当前版本 V{{ review.current_version }}</small></div><span :class="`dv2-status is-${statusTone(review.status)}`">{{ label(review.status) }}</span></header>
                <div v-if="currentReviewFiles(review).length" class="dv2-design-preview">
                  <strong>{{ review.review_type === 'CAD_DESIGN' ? '📐 3D 设计预览' : '📸 设计评测图片' }}</strong>
                  <small>{{ productNameLabel(selectedOrder.product_name, selectedOrder.product_type) }} · 确认前请检查当前版本</small>
                  <div>
                    <button v-for="item in currentReviewFiles(review)" :key="`preview-${item.file_id}`" type="button" @click="previewFile(item)"><img v-if="item.kind === 'IMAGE' && item.preview_url" :src="item.preview_url" :alt="item.name"><i v-else>{{ fileGlyph(item) }}</i><span>{{ item.name }}</span></button>
                  </div>
                </div>
                <div class="dv2-version-list">
                  <article v-for="version in [...review.versions].reverse()" :key="version.version">
                    <div><strong>V{{ version.version }}</strong><span>{{ label(version.status) }}</span><small>{{ preciseDoctorDateTime(version.submitted_at) }}</small></div>
                    <button v-for="item in version.files" :key="item.file_id" type="button" @click="previewFile(item)"><i><img v-if="item.kind === 'IMAGE' && item.preview_url" :src="item.preview_url" :alt="item.name"><template v-else>{{ fileGlyph(item) }}</template></i><span>{{ item.name }}<small>{{ item.kind }} · {{ item.size_label }}</small></span><em>预览 ↗</em></button>
                    <p v-if="version.doctor_comment">医生意见：{{ version.doctor_comment }}</p>
                  </article>
                </div>
                <footer v-if="review.status === 'PENDING_REVIEW'">
                  <template v-if="canReview && review.allowed_actions.some((action) => ['APPROVE_REVIEW', 'REJECT_REVIEW'].includes(action))">
                    <button v-if="review.allowed_actions.includes('REJECT_REVIEW')" type="button" class="dv2-danger-button" :disabled="reviewSubmitting" @click="startReviewDecision(selectedOrder.order_id, review, 'REJECT')">驳回并留言</button>
                    <button v-if="review.allowed_actions.includes('APPROVE_REVIEW')" type="button" class="dv2-primary-button" :disabled="reviewSubmitting" @click="startReviewDecision(selectedOrder.order_id, review, 'APPROVE')">同意当前版本</button>
                  </template>
                  <p v-else>当前账号不能执行此操作。</p>
                </footer>
              </div>
                <div v-if="!selectedOrder.messages.length && !selectedOrder.reviews.length" class="dv2-empty">此订单暂无沟通信息和设计确认记录</div>
              </div>
              <form class="dv2-order-reply" @submit.prevent="sendOrderDrawerMessage">
                <input v-model="orderDrawerMessageDraft" type="text" maxlength="1000" placeholder="给实验室/客服的消息……" :disabled="!canSendOrderDrawerMessage || orderDrawerMessageSending">
                <button type="submit" :disabled="!canSendOrderDrawerMessage || orderDrawerMessageSending || !orderDrawerMessageDraft.trim()">{{ orderDrawerMessageSending ? '发送中…' : '发送' }}</button>
              </form>
              <p v-if="!canSendOrderDrawerMessage" class="dv2-order-reply-disabled">当前订单仅供查看，暂不支持发送消息。</p>
            </section>
          </div>

        </template>
      </aside>
    </div>

    <div v-if="patientDrawerOpen" class="dv2-drawer-mask" @mousedown.self="patientDrawerOpen = false">
      <aside class="dv2-patient-drawer">
        <header>
          <div><small>患者档案 · {{ selectedPatient?.patient_code || '读取中' }}</small><h2>{{ selectedPatient?.patient_name || '正在加载' }}</h2></div>
          <div class="dv2-drawer-header-actions"><button v-if="selectedPatient && !patientEditMode" type="button" @click="beginPatientEdit">编辑档案</button><button type="button" class="is-close" @click="patientDrawerOpen = false">×</button></div>
        </header>
        <div v-if="patientLoading" class="dv2-loading-card"><span class="dv2-spinner" />正在读取患者档案…</div>
        <template v-else-if="selectedPatient">
          <div class="dv2-patient-head">
            <span class="dv2-avatar is-large">{{ selectedPatient.patient_name.slice(0, 1) }}</span>
            <div><strong>{{ selectedPatient.patient_name }}</strong><small>{{ selectedPatient.clinic_name }} · {{ selectedPatient.doctor_name }}</small><p><span :class="`dv2-status is-${patientTreatmentTone(selectedPatient)}`">● {{ patientTreatmentLabel(selectedPatient) }}</span><span v-for="tag in selectedPatient.tags" :key="tag" class="dv2-tag">{{ tag }}</span></p></div>
            <dl><div><dt>订单</dt><dd>{{ selectedPatient.order_count }}</dd></div><div><dt>疗程</dt><dd>{{ patientDurationLabel(selectedPatient) }}</dd></div></dl>
          </div>
          <div v-if="!patientEditMode" class="dv2-tabbar is-drawer"><button v-for="item in [{ key: 'basic', label: '患者资料' }, { key: 'orders', label: `订单历史 ${selectedPatient.orders.length}` }, { key: 'history', label: '历史参考' }]" :key="item.key" type="button" :class="{ active: patientDrawerTab === item.key }" @click="patientDrawerTab = item.key as typeof patientDrawerTab">{{ item.label }}</button></div>
          <div class="dv2-drawer-body">
            <form v-if="patientEditMode" class="dv2-patient-edit-form" @submit.prevent="savePatientChanges">
              <header><div><h3>编辑患者档案</h3><p>诊所和负责医生由当前登录身份确定，不能跨诊所修改。</p></div></header>
              <div class="dv2-form-grid">
                <label><span>患者姓名 *</span><input v-model="newPatient.name" maxlength="128"></label>
                <label><span>患者编号</span><input :value="selectedPatient.patient_code" disabled></label>
                <label><span>出生日期</span><input v-model="newPatient.dateOfBirth" type="date"></label>
                <label><span>年龄</span><input v-model="newPatient.age" type="number" min="0" max="150"></label>
                <label><span>性别</span><select v-model="newPatient.gender"><option value="">请选择</option><option value="男">男</option><option value="女">女</option><option value="其他">其他</option></select></label>
                <label><span>治疗状态</span><select v-model="newPatient.treatmentStatus"><option value="IN_TREATMENT">治疗中</option><option value="FOLLOW_UP">待复诊</option><option value="TREATMENT_ENDED">治疗结束</option><option value="ARCHIVED">已归档</option></select></label>
                <label><span>联系电话</span><input v-model="newPatient.phone" maxlength="64" placeholder="请输入联系电话"></label>
                <label><span>电子邮箱</span><input v-model="newPatient.email" type="email" maxlength="160" placeholder="patient@example.com"></label>
                <label><span>疗程开始</span><input v-model="newPatient.treatmentStartedAt" type="date"></label>
                <label><span>疗程结束</span><input v-model="newPatient.treatmentEndedAt" type="date" :disabled="!['TREATMENT_ENDED', 'ARCHIVED'].includes(newPatient.treatmentStatus)"></label>
                <label class="is-full"><span>标签</span><input v-model="newPatient.tags" maxlength="512" placeholder="多个标签用逗号分隔"></label>
                <label class="is-full"><span>口腔情况摘要</span><textarea v-model="newPatient.oralDescription" maxlength="512" rows="3" placeholder="记录牙位、口内情况及修复关注点"></textarea></label>
                <label class="is-full"><span>病史 / 用药 / 过敏信息</span><textarea v-model="newPatient.medicalNotes" maxlength="1000" rows="4" placeholder="过敏、用药、特殊注意事项……"></textarea></label>
              </div>
            </form>
            <template v-else-if="patientDrawerTab === 'basic'">
              <section class="dv2-detail-section"><h3>联系与身份</h3><dl class="dv2-detail-grid"><div><dt>患者编号</dt><dd>{{ selectedPatient.patient_code }}</dd></div><div><dt>出生日期</dt><dd>{{ patientDate(selectedPatient.date_of_birth) }}</dd></div><div><dt>年龄 / 性别</dt><dd>{{ selectedPatient.patient_age ?? '-' }} 岁 / {{ selectedPatient.patient_gender || '-' }}</dd></div><div><dt>建档日期</dt><dd>{{ patientDate(selectedPatient.created_at) }}</dd></div><div><dt>联系电话</dt><dd>{{ selectedPatient.phone || '-' }}</dd></div><div><dt>电子邮箱</dt><dd>{{ selectedPatient.email || '-' }}</dd></div></dl></section>
              <section class="dv2-detail-section"><h3>治疗概览</h3><dl class="dv2-detail-grid"><div><dt>疗程开始</dt><dd>{{ patientDate(selectedPatient.treatment_started_at) }}</dd></div><div><dt>疗程结束</dt><dd>{{ patientDate(selectedPatient.treatment_ended_at) }}</dd></div><div class="is-full"><dt>口腔情况摘要</dt><dd>{{ selectedPatient.oral_description || '-' }}</dd></div><div class="is-full"><dt>病史 / 用药 / 过敏</dt><dd>{{ selectedPatient.medical_notes || '未记录' }}</dd></div></dl></section>
            </template>
            <template v-else-if="patientDrawerTab === 'orders'"><section class="dv2-detail-section"><h3>订单历史</h3><button v-for="order in selectedPatient.orders" :key="order.order_id" type="button" class="dv2-history-order" @click="patientDrawerOpen = false; openGlobalOrder(order.order_id)"><div><strong>{{ order.order_no }}</strong><small>{{ productNameLabel(order.product_name) }} · {{ compactDoctorDateTime(order.created_at) }}</small></div><span :class="`dv2-status is-${statusTone(order.external_status)}`">{{ label(order.external_status) }}</span></button><div v-if="!selectedPatient.orders.length" class="dv2-empty">暂无历史订单</div></section></template>
            <template v-else><section class="dv2-detail-section"><h3>历史病例参考</h3><p class="dv2-section-note">仅展示当前诊所权限范围内、可用于填写参考的历史订单。</p><article v-for="item in selectedPatient.history_references" :key="item.order_no" class="dv2-history-reference"><strong>{{ item.order_no }} · {{ productNameLabel(item.product_name) }}</strong><p>{{ item.summary }}</p><div><span v-for="field in item.matched_fields" :key="field" class="dv2-tag">{{ field }}</span></div></article><div v-if="!selectedPatient.history_references.length" class="dv2-empty">暂无可参考历史记录</div></section></template>
          </div>
          <footer class="dv2-drawer-footer">
            <template v-if="patientEditMode"><button type="button" class="dv2-secondary-button" @click="patientEditMode = false">取消</button><button type="button" class="dv2-primary-button" :disabled="patientSaving" @click="savePatientChanges">{{ patientSaving ? '保存中…' : '保存修改' }}</button></template>
            <button v-else-if="canCreateOrder" type="button" class="dv2-primary-button" @click="patientDrawerOpen = false; openWizard(selectedPatient.patient_id)">＋ 为患者新建订单</button>
          </footer>
        </template>
      </aside>
    </div>

    <div v-if="logisticsDrawerOpen" class="dv2-drawer-mask" @mousedown.self="logisticsDrawerOpen = false"><aside class="dv2-logistics-drawer"><header><div><small>物流详情</small><h2>{{ selectedLogistics?.order_no }}</h2></div><button type="button" @click="logisticsDrawerOpen = false">×</button></header><template v-if="selectedLogistics"><div class="dv2-logistics-summary"><div><small>物流公司</small><strong>{{ selectedLogistics.carrier }}</strong></div><div><small>运单号</small><strong class="dv2-mono">{{ selectedLogistics.tracking_no }}</strong></div><span :class="`dv2-status is-${statusTone(selectedLogistics.status)}`">{{ label(selectedLogistics.status) }}</span></div><div class="dv2-logistics-timeline"><article v-for="(event, index) in selectedLogistics.events" :key="`${event.time}-${event.label}`" :class="{ current: index === selectedLogistics.events.length - 1 }"><span>{{ index === selectedLogistics.events.length - 1 ? '✓' : '' }}</span><div><strong>{{ event.label }}</strong><p>{{ event.location || '' }}</p><small>{{ event.time }}</small></div></article></div></template></aside></div>

    <DoctorCaseGroupWizard
      v-if="wizardOpen && dataset"
      :token="token"
      :patients="dataset.patients"
      :gateway="gateway"
      :initial-patient-id="wizardInitialPatientId || undefined"
      :initial-group-id="wizardInitialGroupId || undefined"
      :clinic-name="account?.clinic_name"
      :doctor-name="account?.display_name"
      :clinic-contact="account?.clinic_contact"
      @close="wizardOpen = false"
      @submitted="handleCaseGroupSubmitted"
    />

    <div v-if="false && wizardOpen" class="dv2-wizard" data-testid="doctor-order-wizard">
      <header><div><span class="dv2-brand-mark">P</span><div><strong>新建订单</strong><small>{{ wizardNotice || '填写过程中可随时保存草稿' }}</small></div></div><button type="button" @click="wizardOpen = false">关闭 ×</button></header>
      <div class="dv2-wizard-steps"><button v-for="(step, index) in ['产品与患者', '牙位与病例', '产品配置', '上传资料', '复核提交']" :key="step" type="button" :class="{ active: wizardStep === index + 1, done: wizardStep > index + 1 }" :disabled="index + 1 > wizardStep" @click="wizardStep = index + 1"><span>{{ wizardStep > index + 1 ? '✓' : index + 1 }}</span><strong>{{ step }}</strong></button></div>
      <main>
        <section v-if="wizardStep === 1" class="dv2-wizard-panel dv2-wizard-catalog">
          <aside class="dv2-product-categories"><header><small>产品目录</small><strong>选择修复类别</strong></header><button v-for="category in wizardCategories" :key="category.id" type="button" :class="{ active: wizardCategory === category.id, unavailable: !dataset?.products.some((item) => category.types.includes(item.product_type)) }" @click="chooseWizardCategory(category.id)"><span>{{ category.icon }}</span><div><strong>{{ category.name }}</strong><small>{{ category.note }}</small></div><i>{{ wizardCategory === category.id ? '✓' : '›' }}</i></button></aside>
          <div class="dv2-wizard-catalog-main">
            <header><div><span>{{ selectedWizardCategory.icon }}</span><div><h1>{{ selectedWizardCategory.name }}</h1><p>{{ selectedWizardCategory.note }}</p></div></div><small>步骤 1 / 5</small></header>
            <div class="dv2-wizard-first-grid">
              <section><h3>1. 选择患者</h3><label class="dv2-field-search"><span>⌕</span><input v-model="wizardPatientKeyword" type="search" placeholder="搜索患者姓名或编号"></label><div class="dv2-choice-list"><button v-for="patient in wizardPatientRows" :key="patient.patient_id" type="button" :class="{ active: wizard.patientId === patient.patient_id }" @click="wizard.patientId = patient.patient_id"><span class="dv2-avatar">{{ patient.patient_name.slice(0, 1) }}</span><div><strong>{{ patient.patient_name }}</strong><small>{{ patient.patient_code }} · {{ patient.doctor_name }}</small></div><i>{{ wizard.patientId === patient.patient_id ? '✓' : '' }}</i></button></div></section>
              <section><h3>2. 选择具体产品</h3><div class="dv2-product-choice"><button v-for="product in wizardCategoryProducts" :key="product.product_id" type="button" :class="{ active: wizard.productId === product.product_id }" @click="chooseWizardProduct(product)"><span>{{ selectedWizardCategory.icon }}</span><div><strong>{{ productNameLabel(product.product_name, product.product_type) }}</strong><small>{{ product.material }}</small><p>待报价</p></div><i>{{ wizard.productId === product.product_id ? '✓' : '' }}</i></button><div v-if="!wizardCategoryAvailable" class="dv2-inline-notice is-warning">当前类别暂未开放在线下单，请联系订单支持。</div></div></section>
            </div>
          </div>
        </section>
        <section v-else-if="wizardStep === 2" class="dv2-wizard-panel is-narrow"><header><h1>牙位与病例</h1><p>点击牙位图选择{{ wizardToothMode === 'MISSING' ? '缺失牙位' : '需要修复的牙位' }}</p></header><div class="dv2-tooth-mode"><button type="button" :class="{ active: wizardToothMode === 'RESTORE' }" @click="wizardToothMode = 'RESTORE'; wizard.caseFields.tooth_mode = 'RESTORE'">修复牙位</button><button type="button" :class="{ active: wizardToothMode === 'MISSING' }" @click="wizardToothMode = 'MISSING'; wizard.caseFields.tooth_mode = 'MISSING'">缺失牙位</button></div><div class="dv2-tooth-chart" role="group" aria-label="FDI 牙位选择图"><div class="dv2-arch-label">上颌</div><div class="dv2-tooth-row"><button v-for="tooth in wizardToothNumbers.slice(0, 16)" :key="tooth" type="button" :class="{ active: wizardSelectedTeeth.includes(tooth), missing: wizardToothMode === 'MISSING' && wizardSelectedTeeth.includes(tooth) }" :aria-label="`牙位 ${tooth}`" @click="toggleWizardTooth(tooth)"><svg viewBox="0 0 34 44" aria-hidden="true"><path d="M8 3C3 7 3 15 7 21c2 4 2 16 6 19 2 2 3-8 5-8s3 10 5 8c4-3 4-15 6-19 4-6 4-14-1-18-4-3-7 1-10 1S12 0 8 3Z" /></svg><span>{{ tooth }}</span></button></div><div class="dv2-tooth-midline" /><div class="dv2-tooth-row is-lower"><button v-for="tooth in wizardToothNumbers.slice(16)" :key="tooth" type="button" :class="{ active: wizardSelectedTeeth.includes(tooth), missing: wizardToothMode === 'MISSING' && wizardSelectedTeeth.includes(tooth) }" :aria-label="`牙位 ${tooth}`" @click="toggleWizardTooth(tooth)"><svg viewBox="0 0 34 44" aria-hidden="true"><path d="M8 3C3 7 3 15 7 21c2 4 2 16 6 19 2 2 3-8 5-8s3 10 5 8c4-3 4-15 6-19 4-6 4-14-1-18-4-3-7 1-10 1S12 0 8 3Z" /></svg><span>{{ tooth }}</span></button></div><div class="dv2-arch-label">下颌</div></div><div class="dv2-selected-teeth"><span>已选牙位</span><strong>{{ wizard.caseFields.tooth || '尚未选择' }}</strong></div><div class="dv2-form-stack"><label><span>病例说明</span><textarea v-model="wizard.caseFields.case_note" rows="5" placeholder="填写咬合、外形、色泽或其他临床制作要求"></textarea></label></div></section>
        <section v-else-if="wizardStep === 3" class="dv2-wizard-panel is-narrow"><header><h1>产品配置</h1><p>患者和牙位已从前两步自动带入，只需填写本产品的制作要求</p></header><div class="dv2-wizard-context" aria-label="当前订单信息"><div><span>当前患者</span><strong>{{ selectedWizardPatient?.patient_name || '尚未选择' }}</strong><small>{{ selectedWizardPatient?.patient_code || '-' }}</small></div><div><span>修复牙位</span><strong>{{ wizard.caseFields.tooth || '尚未选择' }}</strong><small>{{ wizardToothMode === 'MISSING' ? '缺失牙位' : '修复牙位' }}</small></div><div><span>具体产品</span><strong>{{ productNameLabel(selectedProduct?.product_name, selectedProduct?.product_type) }}</strong><small>{{ selectedProduct?.material || '-' }}</small></div></div><div v-if="selectedProduct" class="dv2-form-stack">
	  <DoctorDynamicFields
	    :fields="selectedProductFields"
	    :model-value="wizard.dynamicFields"
	    @update:model-value="wizard.dynamicFields = $event"
	  />
</div><div v-if="selectedProduct && !selectedProductFields.length" class="dv2-inline-notice">当前产品没有需要额外填写的制作参数，可以直接进入下一步。</div></section><section v-else-if="wizardStep === 4" class="dv2-wizard-panel is-narrow"><header><h1>上传资料</h1><p>上传 STL 扫描文件及必要的照片或 PDF 资料</p></header><label class="dv2-upload-zone" :class="{ disabled: wizardUploading, dragging: wizardDragActive }" @dragenter.prevent="wizardDragActive = true" @dragover.prevent="wizardDragActive = true" @dragleave.prevent="wizardDragActive = false" @drop.prevent="handleWizardDrop"><input type="file" multiple accept=".stl,.jpg,.jpeg,.png,.pdf" :disabled="wizardUploading" @change="addWizardFiles"><span>⇧</span><strong>{{ wizardUploading ? '文件上传中…' : wizardDragActive ? '松开以上传文件' : '点击选择或拖放文件' }}</strong><small>支持 STL、JPG、PNG、PDF；至少需要一个 STL 文件</small></label><div class="dv2-upload-checklist"><span :class="{ done: wizardStlCount > 0 }">{{ wizardStlCount > 0 ? '✓' : '1' }} STL 扫描</span><span :class="{ done: wizard.files.some((item) => item.kind === 'IMAGE') }">{{ wizard.files.some((item) => item.kind === 'IMAGE') ? '✓' : '2' }} 病例照片</span><span class="optional">3 PDF 医嘱（可选）</span></div><div class="dv2-file-list">
  <article v-for="fileItem in wizard.files" :key="fileItem.file_id"><i>{{ fileItem.kind }}</i>
    <div><strong>{{ fileItem.name }}</strong><small>{{ fileItem.size_label }} · 已就绪</small></div>
    <button type="button" :disabled="wizardSaving || wizardUploading" @click="removeWizardFile(fileItem)">移除</button>
  </article>
</div></section><section v-else class="dv2-wizard-panel"><header><h1>复核并提交</h1><p>确认资料完整后提交，正式报价与预计交期将在客服受理后确认</p></header><div class="dv2-inline-notice">如制作过程中需要确认设计稿，订单服务会在订单详情中通知您。</div><div class="dv2-review-summary"><section><h3>患者与产品</h3><dl><div><dt>患者</dt><dd>{{ selectedWizardPatient?.patient_name }} · {{ selectedWizardPatient?.patient_code }}</dd></div><div><dt>产品</dt><dd>{{ productNameLabel(selectedProduct?.product_name, selectedProduct?.product_type) }} · {{ selectedProduct?.material || '-' }}</dd></div><div><dt>价格</dt><dd>由客服核价确认</dd></div></dl></section><section><h3>病例与配置</h3><dl><div><dt>牙位</dt><dd>{{ wizard.caseFields.tooth }}</dd></div><div v-for="summaryField in selectedProductFields" :key="summaryField.key">
  <dt>{{ summaryField.label }}</dt><dd>{{ wizard.dynamicFields[summaryField.key] || '-' }}</dd>
</div></dl></section><section><h3>文件与后续确认</h3><dl><div><dt>文件</dt><dd>{{ wizard.files.length }} 个（STL {{ wizardStlCount }}）</dd></div><div><dt>设计稿确认</dt><dd>收到通知后由医生确认</dd></div></dl></section></div><div v-if="wizardMissingForStep(4).length" class="dv2-inline-notice is-warning">资料检查：还需补充 {{ wizardMissingForStep(4).join('、') }}</div><div v-else class="dv2-inline-notice is-success">资料检查：必填资料已齐全，可以提交。</div></section></main><footer><button type="button" class="dv2-secondary-button" :disabled="wizardSaving || wizardSubmitting || wizardUploading" @click="saveWizardDraft(false)">{{ wizardSaving ? '保存中…' : '保存草稿' }}</button><div><button v-if="wizardStep > 1" type="button" class="dv2-secondary-button" :disabled="wizardSaving || wizardSubmitting || wizardUploading" @click="wizardStep--">上一步</button><button v-if="wizardStep < 5" type="button" class="dv2-primary-button" :disabled="wizardSaving || wizardSubmitting || wizardUploading" @click="nextWizardStep">下一步</button><button v-else type="button" class="dv2-primary-button" :disabled="wizardSubmitDisabled" @click="submitWizard">{{ wizardSubmitting ? '提交中…' : '提交订单' }}</button></div></footer></div>

    <el-dialog v-model="rejectDialogOpen" title="驳回并提交修改意见" width="520px" append-to-body><p class="dv2-dialog-note">说明需要调整的具体内容。对方提交新版本后，您可以再次确认。</p><el-input v-model="rejectReason" type="textarea" :rows="5" maxlength="500" show-word-limit placeholder="必填，请写明需要修改的位置和要求" /><template #footer><el-button :disabled="reviewSubmitting" @click="rejectDialogOpen = false">取消</el-button><el-button type="danger" :disabled="reviewSubmitting || !rejectReason.trim()" @click="submitReviewDecision('REJECT')">{{ reviewSubmitting ? '提交中…' : '确认驳回并发送' }}</el-button></template></el-dialog>

    <el-dialog v-model="patientDialogOpen" class="dv2-patient-dialog" title="新建患者" width="720px" append-to-body destroy-on-close>
      <p class="dv2-patient-dialog-intro">建立患者档案后，可直接关联订单并持续查看治疗历史。</p>
      <div class="dv2-form-grid">
        <label><span>患者姓名 *</span><input v-model="newPatient.name" maxlength="128" placeholder="请输入患者姓名"></label>
        <label><span>患者编号</span><input value="保存后自动生成" disabled></label>
        <label><span>出生日期</span><input v-model="newPatient.dateOfBirth" type="date"></label>
        <label><span>性别</span><select v-model="newPatient.gender"><option value="">请选择</option><option value="男">男</option><option value="女">女</option><option value="其他">其他</option></select></label>
        <label><span>联系电话</span><input v-model="newPatient.phone" maxlength="64" placeholder="请输入联系电话"></label>
        <label><span>电子邮箱</span><input v-model="newPatient.email" type="email" maxlength="160" placeholder="patient@example.com"></label>
        <label><span>所属诊所</span><input :value="account?.clinic_name || '当前诊所'" disabled></label>
        <label><span>负责医生</span><input :value="account?.display_name || '当前医生'" disabled></label>
        <label><span>治疗状态</span><select v-model="newPatient.treatmentStatus"><option value="IN_TREATMENT">治疗中</option><option value="FOLLOW_UP">待复诊</option><option value="TREATMENT_ENDED">治疗结束</option><option value="ARCHIVED">已归档</option></select></label>
        <label><span>疗程开始</span><input v-model="newPatient.treatmentStartedAt" type="date"></label>
        <label class="is-full"><span>标签</span><input v-model="newPatient.tags" maxlength="512" placeholder="例如：VIP、种植、复诊；多个标签用逗号分隔"></label>
        <label class="is-full"><span>口腔情况摘要</span><textarea v-model="newPatient.oralDescription" maxlength="512" rows="3" placeholder="记录牙位、口内情况及修复关注点"></textarea></label>
        <label class="is-full"><span>病史 / 用药 / 过敏信息</span><textarea v-model="newPatient.medicalNotes" maxlength="1000" rows="4" placeholder="过敏、用药、特殊注意事项……"></textarea></label>
      </div>
      <template #footer><el-button @click="patientDialogOpen = false">取消</el-button><el-button type="primary" :disabled="patientSaving || !newPatient.name.trim()" @click="createPatient">{{ patientSaving ? '保存中…' : '保存患者' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="memberDialogOpen" title="邀请诊所成员" width="600px" append-to-body><div class="dv2-form-grid"><label><span>成员姓名 *</span><input v-model="newMember.displayName"></label><label><span>邮箱 *</span><input v-model="newMember.email" type="email"></label><label><span>诊所角色</span><select v-model="newMember.role">
  <option v-for="roleOption in clinicRoleOptions" :key="roleOption.value" :value="roleOption.value">{{ roleOption.name }}</option>
</select></label><label><span>账单权限</span><select v-model="newMember.billing"><option value="NONE">无</option><option value="VIEW">查看</option><option value="FINANCIAL_ACTION">财务操作</option></select></label><label><span>物流权限</span><select v-model="newMember.logistics"><option value="NONE">无</option><option value="VIEW">查看</option><option value="RECEIPT">查看并确认收货</option></select></label></div><div class="dv2-inline-notice">诊所管理员只能分配医生端成员角色。</div><template #footer><el-button @click="memberDialogOpen = false">取消</el-button><el-button type="primary" @click="addMember">发送邀请</el-button></template></el-dialog>

    <el-dialog v-model="filePreviewOpen" title="文件预览" width="860px" append-to-body destroy-on-close><div class="dv2-preview-stage"><img v-if="filePreview?.kind === 'IMAGE' && filePreview.preview_url" class="dv2-preview-image" :src="filePreview.preview_url" :alt="filePreviewName"><iframe v-else-if="filePreview?.kind === 'PDF' && filePreview.preview_url" class="dv2-preview-frame" :src="filePreview.preview_url" :title="filePreviewName" /><div v-else-if="filePreview?.preview_url" class="dv2-preview-placeholder"><span>文件</span><strong>{{ filePreviewName }}</strong><p>该格式请在浏览器新窗口中查看。</p></div><div v-else class="dv2-preview-placeholder"><span>!</span><strong>{{ filePreviewName }}</strong><p>预览地址已失效，请关闭后重新打开。</p></div></div><template #footer><el-button v-if="filePreview?.preview_url" tag="a" :href="filePreview.preview_url" target="_blank" rel="noopener noreferrer">新窗口打开</el-button><el-button @click="filePreviewOpen = false">关闭</el-button></template></el-dialog>

    <StlViewerDialog v-if="viewerFile" v-model:visible="viewerOpen" :source-url="viewerFile.preview_url || ''" :filename="viewerFile.name" />
  </div>
</template>
