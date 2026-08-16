<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { productionProgressNodes, productionProgressSummary } from '../utils/productionProgress'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { code: number; msg: string; data: T }
type Row = Record<string, any>
type DeliveryRegionFilter = 'DOMESTIC' | 'INTERNATIONAL' | 'UNCLASSIFIED'
type Notice = {
  notification_id: number
  event: string
  order_id: number | null
  order_no: string | null
  message: string | null
  read_at: string | null
  delivered_at: string | null
  created_at: string
}

const props = defineProps<{
  activeRoute: string
  token: string
  notifications: Notice[]
  notificationsLoading: boolean
  notificationError: string
  unreadCount: number
}>()

const emit = defineEmits<{
  refreshNotifications: []
  markNotificationRead: [id: number]
  markAllNotificationsRead: []
  openOrder: [orderId: number]
}>()

const loading = ref(false)
const failed = ref(false)
const failureKind = ref<'request' | 'permission'>('request')
const keyword = ref('')
const statusFilter = ref('ALL')
const page = ref(1)
const pageSize = 10
const drawerVisible = ref(false)
const drawerTitle = ref('')
const drawerKind = ref('')
const drawerData = ref<Row>({})
const drawerLoading = ref(false)

const clientTab = ref<'directory' | 'contribution'>('directory')
const deliveryRegion = ref<DeliveryRegionFilter>('DOMESTIC')
const deliveryTab = ref<'billing' | 'tracking'>('billing')
const equipmentTab = ref<'list' | 'approval'>('list')
const safetyTab = ref<'supervision' | 'rules'>('supervision')
const notificationTab = ref<'all' | 'unread'>('all')

const clients = ref<Row[]>([])
const clientSummary = ref<Row | null>(null)
const salesSummary = ref<Row | null>(null)
const deliveryOrders = ref<Row[]>([])
const processRows = ref<Row[]>([])
const staffRows = ref<Row[]>([])
const performanceRows = ref<Row[]>([])
const qualitySummary = ref<Row | null>(null)
const qualityRows = ref<Row[]>([])
const supportSummary = ref<Row | null>(null)
const supportRows = ref<Row[]>([])
const equipmentApprovals = ref<Row[]>([])
const safetyRules = ref<Row[]>([])
const outsourcingRows = ref<Row[]>([])
const actionBusy = ref<string | number | null>(null)
const actionMessage = ref('')
const products = ref<Row[]>([])
const aiSummary = ref<Row | null>(null)
const aiTrend = ref<Row | null>(null)
const dictionaryType = ref<'REASON_CATEGORY' | 'RESPONSIBILITY_TYPE'>('REASON_CATEGORY')
const dictionaryItems = ref<Row[]>([])
const dictionaryLoading = ref(false)
const dictionaryError = ref(false)
const selectedDictionaryId = ref<number | null>(null)
const dictionaryLabel = ref('')
const dictionarySort = ref(50)
const dictionarySaving = ref(false)
const dictionaryMessage = ref('')

const routeSet = new Set([
  '/admin/clinics', '/delivery', '/admin/outsourcing', '/workflow/process-instance',
  '/production/quality', '/performance', '/production/devices',
  '/production/material-exceptions', '/production/safety-environment',
  '/production/cost-management', '/system/form-configs', '/notifications',
  '/admin/ai-governance'
])

const businessFailure = '数据暂时无法加载，请稍后重试'
const failureMessage = computed(() => failureKind.value === 'permission' ? '当前账号无权查看此业务内容' : businessFailure)

async function request<T>(path: string, options: RequestInit = {}) {
  const response = await authenticatedFetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${props.token}`,
      ...(options.headers ?? {})
    }
  })
  if (!response.ok) throw new Error(String(response.status))
  const payload = await response.json() as ApiResponse<T>
  return payload.data
}

async function poolMap<T, R>(items: T[], limit: number, task: (item: T) => Promise<R>) {
  const results = new Array<R>(items.length)
  let cursor = 0
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor++
      results[index] = await task(items[index])
    }
  })
  await Promise.all(workers)
  return results
}

function resetViewState() {
  keyword.value = ''
  statusFilter.value = props.activeRoute === '/workflow/process-instance' ? 'HAS_PROCESS' : 'ALL'
  if (props.activeRoute === '/delivery') deliveryRegion.value = 'DOMESTIC'
  page.value = 1
  drawerVisible.value = false
  actionBusy.value = null
  actionMessage.value = ''
  failed.value = false
  failureKind.value = 'request'
}

async function loadClients() {
  const [list, summary, sales] = await Promise.all([
    request<Row>('/clinics?page=1&size=100'),
    request<Row>('/dashboards/phase-one-ab'),
    request<Row>('/dashboards/sales')
  ])
  clients.value = list.items ?? []
  clientSummary.value = summary
  salesSummary.value = sales
}

async function loadDelivery() {
  deliveryOrders.value = await request<Row[]>('/logistics/orders?limit=50')
}

async function loadProcesses() {
  const orderList = await request<Row>('/orders?page=1&size=100')
  const orders = orderList.items ?? []
  processRows.value = await poolMap<Row, Row>(orders, 3, async (order) => {
    try {
      const instance = await request<Row>(`/orders/${order.order_id}/process-instance`)
      return { order, instance, failed: false }
    } catch (error) {
      const missing = error instanceof Error && error.message === '404'
      return { order, instance: null, failed: !missing, missing }
    }
  })
}

async function loadQuality() {
  const [summary, reworks, external] = await Promise.all([
    request<Row>('/production/quality/summary'),
    request<Row[]>('/reworks'),
    request<Row>('/quality-records?record_type=EXTERNAL_RETURN&page=1&size=50')
  ])
  qualitySummary.value = summary
  qualityRows.value = [
    ...(reworks ?? []).map((item) => ({ ...item, issue_type: '内返', source: item.from_process_name || '生产检查' })),
    ...((external.items ?? []) as Row[]).map((item) => ({ ...item, issue_type: '外返', source: '客户反馈' }))
  ]
}

async function loadPerformance() {
  const workload = await request<Row>('/staff/workload?page=1&size=30')
  staffRows.value = workload.items ?? []
  performanceRows.value = await poolMap<Row, Row>(staffRows.value.slice(0, 12), 3, async (staff) => {
    try {
      const stats = await request<Row>(`/performance?user_id=${staff.user_id}`)
      return {
        staff,
        stats: stats.performance_formula_version === 'STANDARD_TIME_PENDING'
          ? {
              ...stats,
              standard_time_pending: true,
              standard_duration: null,
              standard_coverage_rate: null,
              on_time_rate: null,
              duration_efficiency: null,
              performance_score: null,
              performance_formula_version: '待客户提供正式标准工时'
            }
          : { ...stats, standard_time_pending: false },
        failed: false
      }
    } catch {
      return { staff, stats: null, failed: true }
    }
  })
}

async function loadSupportSummary() {
  const pathByRoute: Record<string, string> = {
    '/production/devices': '/production/equipment/summary',
    '/production/material-exceptions': '/production/material-exceptions/summary',
    '/production/safety-environment': '/production/safety-environment/summary',
    '/production/cost-management': '/production/cost-management/summary'
  }
  if (props.activeRoute === '/production/devices') {
    const [summary, rows, approvals] = await Promise.all([
      request<Row>(pathByRoute[props.activeRoute]),
      request<Row[]>('/production/equipment'),
      request<Row[]>('/production/equipment/approvals')
    ])
    supportSummary.value = summary
    supportRows.value = rows
    equipmentApprovals.value = approvals
    return
  }
  if (props.activeRoute === '/production/safety-environment') {
    const [summary, rows, rules] = await Promise.all([
      request<Row>(pathByRoute[props.activeRoute]),
      request<Row[]>('/production/safety-environment/events'),
      request<Row[]>('/production/safety-environment/rules')
    ])
    supportSummary.value = summary
    supportRows.value = rows
    safetyRules.value = rules
    return
  }
  const listPath = props.activeRoute === '/production/material-exceptions'
    ? '/production/material-exceptions'
    : '/production/cost-management/records'
  const [summary, rows] = await Promise.all([
    request<Row>(pathByRoute[props.activeRoute]),
    request<Row[]>(listPath)
  ])
  supportSummary.value = summary
  supportRows.value = rows
}

async function loadOutsourcing() {
  outsourcingRows.value = await request<Row[]>('/production/outsourcing')
}

async function loadProducts() {
  const list = await request<Row>('/products?page=1&size=100')
  products.value = (list.items ?? []).map((item: Row) => ({
    ...item,
    material_spec: businessProductMaterial(item.material_spec),
    price_note: businessProductPriceNote(item.price_note)
  }))
}

async function loadAi() {
  const [summary, trend] = await Promise.all([
    request<Row>('/ai/governance/summary'),
    request<Row>('/ai/governance/cost-trend?days=7')
  ])
  aiSummary.value = summary
  aiTrend.value = trend
}

async function refresh() {
  if (!props.token || !routeSet.has(props.activeRoute)) return
  if (props.activeRoute === '/notifications') {
    emit('refreshNotifications')
    return
  }
  loading.value = true
  failed.value = false
  failureKind.value = 'request'
  try {
    if (props.activeRoute === '/admin/clinics') await loadClients()
    else if (props.activeRoute === '/delivery') await loadDelivery()
    else if (props.activeRoute === '/admin/outsourcing') await loadOutsourcing()
    else if (props.activeRoute === '/workflow/process-instance') await loadProcesses()
    else if (props.activeRoute === '/production/quality') await loadQuality()
    else if (props.activeRoute === '/performance') await loadPerformance()
    else if (['/production/devices', '/production/material-exceptions', '/production/safety-environment', '/production/cost-management'].includes(props.activeRoute)) await loadSupportSummary()
    else if (props.activeRoute === '/system/form-configs') await loadProducts()
    else if (props.activeRoute === '/admin/ai-governance') await loadAi()
  } catch (error) {
    failureKind.value = error instanceof Error && error.message === '403' ? 'permission' : 'request'
    failed.value = true
  } finally {
    loading.value = false
  }
}

const clientRankingMap = computed<Map<number, Row>>(() => new Map<number, Row>((clientSummary.value?.top_customers ?? []).map((item: Row) => [Number(item.clinic_id), item])))
const drawerDetails = computed<Row[]>(() => drawerData.value?.details ?? [])

function clientRank(clinicId: number) {
  const index = (clientSummary.value?.top_customers ?? []).findIndex((item: Row) => item.clinic_id === clinicId)
  return index >= 0 ? `第 ${index + 1} 名` : '暂未进入排名'
}
const filteredClients = computed(() => clients.value.filter((item) => {
  const text = `${item.clinic_name} ${item.contact_name ?? ''} ${item.contact_phone ?? ''}`.toLowerCase()
  const keywordMatch = !keyword.value || text.includes(keyword.value.toLowerCase())
  const statusMatch = statusFilter.value === 'ALL' || item.status === statusFilter.value
  return keywordMatch && statusMatch
}))

const filteredProducts = computed(() => products.value.filter((item) => {
  const text = `${item.product_name} ${item.product_type} ${item.material_spec ?? ''}`.toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (statusFilter.value === 'ALL' || item.status === statusFilter.value)
}))

type ProcessDisplayStatus = 'UNASSIGNED' | 'PRODUCING' | 'COMPLETED' | 'NO_PROCESS'

function processNodes(row: Row): Row[] {
  return productionProgressNodes(row.instance?.nodes ?? []) as Row[]
}

function processDisplayStatus(row: Row): ProcessDisplayStatus {
  const nodes = processNodes(row)
  if (!nodes.length) return 'NO_PROCESS'
  if (nodes.every((node) => ['COMPLETED', 'SKIPPED'].includes(node.node_status))) return 'COMPLETED'
  const unfinishedNodes = nodes.filter((node) => !['COMPLETED', 'SKIPPED'].includes(node.node_status))
  return unfinishedNodes.some((node) => !node.assigned_user_id) ? 'UNASSIGNED' : 'PRODUCING'
}

const filteredProcesses = computed(() => processRows.value.filter((row) => {
  const order = row.order ?? {}
  const text = `${order.order_no} ${order.clinic_name ?? ''} ${order.product_type ?? ''}`.toLowerCase()
  const status = processDisplayStatus(row)
  const statusMatch = statusFilter.value === 'ALL'
    || (statusFilter.value === 'HAS_PROCESS' && status !== 'NO_PROCESS')
    || statusFilter.value === status
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && statusMatch
}).sort((left, right) => {
  const priority: Record<ProcessDisplayStatus, number> = {
    UNASSIGNED: 0,
    PRODUCING: 1,
    COMPLETED: 2,
    NO_PROCESS: 3
  }
  return priority[processDisplayStatus(left)] - priority[processDisplayStatus(right)]
    || Number(right.order?.order_id ?? 0) - Number(left.order?.order_id ?? 0)
}))

const filteredQuality = computed(() => qualityRows.value.filter((item) => {
  const text = `${item.order_no ?? item.order_id ?? ''} ${item.reason_detail ?? ''} ${item.issue_type}`.toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (statusFilter.value === 'ALL' || item.status === statusFilter.value || item.issue_type === statusFilter.value)
}))

const filteredPerformance = computed(() => performanceRows.value.filter((row) => {
  const staff = row.staff ?? {}
  return !keyword.value || `${staff.display_name ?? ''} ${staff.username ?? ''} ${staff.department_name ?? ''}`.toLowerCase().includes(keyword.value.toLowerCase())
}))

type FilterOption = { value: string; label: string }

const deliveryStatusOptions = computed<FilterOption[]>(() => deliveryTab.value === 'billing'
  ? [
      { value: 'ALL', label: '全部状态' },
      { value: 'PENDING', label: '账单待上传' },
      { value: 'UPLOADED', label: '账单已上传' },
      { value: 'PENDING_PAYMENT', label: '待付款' },
      { value: 'PARTIALLY_PAID', label: '部分付款' },
      { value: 'PAID', label: '已付款' },
      { value: 'NOT_REQUIRED', label: '无需付款' }
    ]
  : [
      { value: 'ALL', label: '全部状态' },
      { value: 'PENDING', label: '待发货' },
      { value: 'SHIPPED', label: '已发货' },
      { value: 'EXCEPTION', label: '配送异常' },
      { value: 'FOLLOWING', label: '跟进中' },
      { value: 'RESOLVED', label: '已解决' }
    ])

function deliveryRegionOf(item: Row): DeliveryRegionFilter {
  const value = String(item.delivery_region ?? '').toUpperCase()
  if (value === 'DOMESTIC' || value === 'INTERNATIONAL') return value
  return 'UNCLASSIFIED'
}

function deliveryRegionLabel(item: Row) {
  const region = deliveryRegionOf(item)
  if (region === 'DOMESTIC') return '国内'
  if (region === 'INTERNATIONAL') return '国外'
  return '待归类'
}

const deliveryRegionCounts = computed<Record<DeliveryRegionFilter, number>>(() => {
  const counts: Record<DeliveryRegionFilter, number> = { DOMESTIC: 0, INTERNATIONAL: 0, UNCLASSIFIED: 0 }
  for (const item of deliveryOrders.value) counts[deliveryRegionOf(item)] += 1
  return counts
})

const deliveryRegionNotice = computed(() => {
  const count = deliveryRegionCounts.value.UNCLASSIFIED
  if (deliveryRegion.value === 'UNCLASSIFIED') {
    return count > 0
      ? `当前有 ${count} 单尚未维护最终配送地区，未归入国内或国外业务`
      : '当前没有待归类订单'
  }
  return count > 0 ? `另有 ${count} 单尚未维护最终配送地区，请在业务数据补齐后归类` : ''
})

const deliveryEmptyText = computed(() => {
  if (deliveryRegion.value === 'DOMESTIC') return '当前筛选下没有国内业务订单'
  if (deliveryRegion.value === 'INTERNATIONAL') return '当前筛选下没有国外业务订单'
  return '当前筛选下没有待归类订单'
})

const filteredDeliveryOrders = computed(() => deliveryOrders.value.filter((item) => {
  if (deliveryRegionOf(item) !== deliveryRegion.value) return false
  const text = `${item.order_no} ${item.tracking_no ?? ''} ${item.destination_country ?? ''}`.toLowerCase()
  if (keyword.value && !text.includes(keyword.value.toLowerCase())) return false
  if (statusFilter.value === 'ALL') return true
  return deliveryTab.value === 'billing'
    ? item.bill_status === statusFilter.value || item.payment_status === statusFilter.value
    : item.logistics_status === statusFilter.value || item.external_status === statusFilter.value
}))

const visibleSupportRows = computed<Row[]>(() => {
  if (props.activeRoute === '/production/devices') {
    return equipmentTab.value === 'approval' ? equipmentApprovals.value : supportRows.value
  }
  if (props.activeRoute === '/production/safety-environment' && safetyTab.value === 'rules') {
    return safetyRules.value
  }
  return supportRows.value
})

const supportStatusOptions = computed<FilterOption[]>(() => {
  const all = { value: 'ALL', label: '全部状态' }
  if (props.activeRoute === '/production/devices') {
    return equipmentTab.value === 'approval'
      ? [all, { value: 'PENDING', label: '待处理' }, { value: 'IN_PROGRESS', label: '处理中' }, { value: 'DONE', label: '已完成' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已驳回' }]
      : [all, { value: 'RUNNING', label: '运行' }, { value: 'IDLE', label: '待机' }, { value: 'MAINTENANCE', label: '维护' }, { value: 'FAULT', label: '故障' }, { value: 'SCRAPPED', label: '已报废' }]
  }
  if (props.activeRoute === '/production/material-exceptions') {
    return [all, { value: 'PENDING', label: '待处理' }, { value: 'IN_PROGRESS', label: '处理中' }, { value: 'CLOSED', label: '已关闭' }]
  }
  if (props.activeRoute === '/production/safety-environment') {
    return safetyTab.value === 'rules'
      ? [all, { value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }]
      : [all, { value: 'PENDING', label: '待处理' }, { value: 'IN_PROGRESS', label: '整改中' }, { value: 'CLOSED', label: '已关闭' }]
  }
  return [all, { value: 'NORMAL', label: '正常' }, { value: 'WARNING', label: '异常提醒' }, { value: 'CONFIRMED', label: '已确认' }]
})

const filteredSupport = computed(() => visibleSupportRows.value.filter((item) => {
  const text = Object.values(item).filter((value) => typeof value === 'string' || typeof value === 'number').join(' ').toLowerCase()
  const keywordMatch = !keyword.value || text.includes(keyword.value.toLowerCase())
  const statusMatch = statusFilter.value === 'ALL' || item.status === statusFilter.value
  return keywordMatch && statusMatch
}))

const filteredOutsourcing = computed(() => outsourcingRows.value.filter((item) => {
  const text = `${item.batch_no} ${item.order_no} ${item.item_name} ${item.supplier_name}`.toLowerCase()
  return (!keyword.value || text.includes(keyword.value.toLowerCase()))
    && (statusFilter.value === 'ALL' || item.status === statusFilter.value || (statusFilter.value === 'OVERDUE' && item.is_overdue))
}))

const filteredNotices = computed(() => props.notifications.filter((item) => notificationTab.value === 'all' || !item.read_at))
const currentRows = computed<Row[]>(() => {
  if (props.activeRoute === '/admin/clinics') return filteredClients.value
  if (props.activeRoute === '/workflow/process-instance') return filteredProcesses.value
  if (props.activeRoute === '/production/quality') return filteredQuality.value
  if (props.activeRoute === '/performance') return filteredPerformance.value
  if (props.activeRoute === '/admin/outsourcing') return filteredOutsourcing.value
  if (['/production/devices', '/production/material-exceptions', '/production/safety-environment', '/production/cost-management'].includes(props.activeRoute)) return filteredSupport.value
  if (props.activeRoute === '/system/form-configs') return filteredProducts.value
  if (props.activeRoute === '/notifications') return filteredNotices.value
  return []
})
const pageCount = computed(() => Math.max(1, Math.ceil(currentRows.value.length / pageSize)))
const pagedRows = computed(() => currentRows.value.slice((page.value - 1) * pageSize, page.value * pageSize))

function clearFilters() {
  keyword.value = ''
  statusFilter.value = props.activeRoute === '/workflow/process-instance' ? 'HAS_PROCESS' : 'ALL'
  page.value = 1
}

function compactDate(value: string | null | undefined) {
  if (!value) return '暂未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}

function statusLabel(value: string | null | undefined) {
  const labels: Record<string, string> = {
    ACTIVE: '启用', INACTIVE: '停用', RUNNING: '运行', IDLE: '待机', MAINTENANCE: '维护', FAULT: '故障', SCRAPPED: '已报废',
    PENDING: '待处理', IN_PROGRESS: '处理中', CLOSED: '已关闭', COMPLETED: '已完成', READY: '待开始',
    SHIPPED: '已发货', DELIVERED: '已送达', EXCEPTION: '异常', FOLLOWING: '跟进中', RESOLVED: '已解决',
    NORMAL: '正常', WARNING: '异常提醒', CONFIRMED: '已确认', PAID: '已付款', PENDING_PAYMENT: '待付款',
    UPLOADED: '已上传', PARTIALLY_PAID: '部分付款', NOT_REQUIRED: '无需付款',
    APPROVED: '已通过', REJECTED: '已驳回', DONE: '已完成', SENT: '已发出', DELAYED: '已延迟', RETURNED: '已返回'
  }
  return labels[value ?? ''] ?? value ?? '暂未记录'
}

function supportTypeLabel(value: string | null | undefined) {
  const labels: Record<string, string> = {
    MAINTENANCE_PLAN: '维护登记', CALIBRATION: '校准登记', FAULT_REPAIR: '维修登记', DOWNTIME: '停机登记',
    REPAIR_REQUEST: '维修审批', SCRAP_REQUEST: '报废审批', SHORTAGE: '缺料', WRONG_MATERIAL: '错料',
    BATCH_ABNORMAL: '批次异常', MATERIAL_LOSS: '材料损耗', SAFETY_INSPECTION: '安全检查',
    HAZARD_RECTIFICATION: '隐患整改', ENVIRONMENT_RECORD: '环境检查', PPE_DEVICE_REMINDER: '防护提醒',
    PROCESS: '工序成本', MATERIAL: '材料成本', LABOR: '人员成本', REWORK: '返工成本', OUTSOURCING: '外协成本',
    SAFETY: '安全检查', ENVIRONMENT: '环境检查', DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月'
  }
  return labels[value ?? ''] ?? value ?? '暂未记录'
}

function productLabel(value: string | null | undefined) {
  const labels: Record<string, string> = {
    REGULAR_CROWN: '常规冠修复', FIXED_CROWN: '常规冠修复', PFM_BRIDGE: '烤瓷桥',
    BRIDGE: '桥体', FIXED_BRIDGE: '固定桥', VENEER: '贴面', VENEER_SET: '贴面套装',
    IMPLANT: '种植修复', IMPLANT_CROWN: '种植牙冠', IMPLANT_RESTORATION: '种植修复',
    DENTURE: '活动义齿', REMOVABLE_DENTURE: '活动修复', REMOVABLE: '活动修复',
    ORTHODONTIC: '正畸产品', ORTHODONTICS: '正畸产品', CLEAR_ALIGNER: '隐形矫治',
    NIGHT_GUARD: '夜磨牙垫'
  }
  return labels[value ?? ''] ?? value ?? '产品未标注'
}

function businessProductMaterial(value: string | null | undefined) {
  const normalized = String(value ?? '').replace(/一期默认产品[；;、]?/g, '').trim()
  return normalized || '材料规格待客服维护'
}

function businessProductPriceNote(value: string | null | undefined) {
  const normalized = String(value ?? '').trim()
  if (!normalized) return '暂无备注'
  if (/(种子|占位|演示|测试)/.test(normalized)) return '价格信息待客服维护，不作为正式报价'
  return normalized
}

function amount(value: number | null | undefined, currency = 'CNY') {
  if (value === null || value === undefined) return '暂未统计'
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency }).format(value / 100)
}

// 后端用 null 表示该口径尚未启用；不能退化成 0%，否则管理端会展示假指标。
function optionalRate(value: number | null | undefined) {
  if (value === null || value === undefined) return '口径未启用'
  return `${Number(value).toFixed(1)}%`
}

function costAmount(value: number | null | undefined) {
  if (value === null || value === undefined) return '暂未统计'
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value)
}

function processCurrent(row: Row): Row {
  const nodes = processNodes(row)
  return nodes.find((node: Row) => node.node_status === 'IN_PROGRESS')
    ?? nodes.find((node: Row) => node.node_status === 'READY')
    ?? nodes.find((node: Row) => node.node_status === 'PENDING')
    ?? nodes.at(-1)
    ?? {}
}

function processProgress(row: Row) {
  return productionProgressSummary(row.instance?.nodes ?? []).percent
}

function processProgressText(row: Row) {
  const summary = productionProgressSummary(row.instance?.nodes ?? [])
  return summary.total ? `${summary.completed}/${summary.total} · ${summary.percent}%` : '未开始'
}

function processExecutorText(row: Row) {
  const status = processDisplayStatus(row)
  if (status === 'NO_PROCESS') return '未开始'
  if (status === 'COMPLETED') return '已完成'
  const current = processCurrent(row)
  return current.assigned_user_id ? `员工 ${current.assigned_user_id}` : '待派工'
}

function processAnomalyText(row: Row) {
  const nodes = processNodes(row)
  const completedLate = nodes.some((node) => node.node_status === 'COMPLETED'
    && node.deadline_at
    && node.completed_at
    && new Date(node.completed_at) > new Date(node.deadline_at))
  if (completedLate) return '逾期完成'
  if (processDisplayStatus(row) === 'COMPLETED') return '无'
  const current = processCurrent(row)
  return current.deadline_at && new Date(current.deadline_at) < new Date() ? '已超时' : '无'
}

async function openClient(row: Row) {
  openDrawer('客户详情', 'client', row)
  drawerLoading.value = true
  try {
    const [detail, preference] = await Promise.all([
      request<Row>(`/clinics/${row.clinic_id}`),
      request<Row>(`/clinics/${row.clinic_id}/preference`)
    ])
    drawerData.value = { ...detail, preference, ranking: clientRankingMap.value.get(row.clinic_id) }
  } catch {
    drawerData.value = { ...row, detailFailed: true }
  } finally {
    drawerLoading.value = false
  }
}

async function openDelivery(row: Row) {
  openDrawer('账单与配送详情', 'delivery', row)
  drawerLoading.value = true
  try {
    const [bill, payments, logistics] = await Promise.allSettled([
      request<Row>(`/orders/${row.order_id}/bill`),
      request<Row[]>(`/orders/${row.order_id}/payments`),
      request<Row>(`/orders/${row.order_id}/logistics`)
    ])
    drawerData.value = {
      ...row,
      bill: bill.status === 'fulfilled' ? bill.value : null,
      payments: payments.status === 'fulfilled' ? payments.value : [],
      logistics: logistics.status === 'fulfilled' ? logistics.value : null
    }
  } finally {
    drawerLoading.value = false
  }
}

async function openProcess(row: Row) {
  const instance = row.instance
    ? { ...row.instance, nodes: productionProgressNodes(row.instance.nodes ?? []) }
    : null
  openDrawer('订单工序详情', 'process', { ...row, instance })
}

async function openPerformance(row: Row) {
  openDrawer('个人绩效详情', 'performance', row)
  drawerLoading.value = true
  try {
    const details = await request<Row[]>(`/performance/details?user_id=${row.staff.user_id}`)
    drawerData.value = { ...row, details }
  } catch {
    drawerData.value = { ...row, details: [], detailFailed: true }
  } finally {
    drawerLoading.value = false
  }
}

async function openSupport(row: Row) {
  const route = props.activeRoute
  const config = route === '/production/devices'
    ? { title: '设备详情', kind: 'equipment', path: `/production/equipment/${encodeURIComponent(row.equipment_code)}` }
    : route === '/production/material-exceptions'
      ? { title: '物料异常详情', kind: 'material', path: `/production/material-exceptions/${encodeURIComponent(row.exception_no)}` }
      : route === '/production/safety-environment'
        ? { title: safetyTab.value === 'rules' ? '检查规则详情' : '安环事项详情', kind: safetyTab.value === 'rules' ? 'safety-rule' : 'safety', path: safetyTab.value === 'rules' ? '' : `/production/safety-environment/events/${encodeURIComponent(row.event_no)}` }
        : { title: '订单成本详情', kind: 'cost', path: `/production/cost-management/records/${encodeURIComponent(row.cost_no)}` }
  openDrawer(config.title, config.kind, row)
  if (!config.path) return
  drawerLoading.value = true
  try {
    drawerData.value = await request<Row>(config.path)
  } catch {
    drawerData.value = { ...row, detailFailed: true }
  } finally {
    drawerLoading.value = false
  }
}

async function openOutsourcing(row: Row) {
  openDrawer('外协批次详情', 'outsourcing', row)
  drawerLoading.value = true
  try {
    drawerData.value = await request<Row>(`/production/outsourcing/${encodeURIComponent(row.batch_no)}`)
  } catch {
    drawerData.value = { ...row, detailFailed: true }
  } finally {
    drawerLoading.value = false
  }
}

async function decideEquipmentApproval(row: Row, decision: 'APPROVED' | 'REJECTED') {
  actionBusy.value = row.event_id
  actionMessage.value = ''
  try {
    await request<Row>(`/production/equipment/approvals/${row.event_id}`, {
      method: 'PUT',
      body: JSON.stringify({ decision, decision_note: decision === 'APPROVED' ? '管理端已复核并同意' : '管理端复核后暂不通过' })
    })
    await loadSupportSummary()
    actionMessage.value = decision === 'APPROVED' ? '审批已通过' : '审批已驳回'
  } catch {
    actionMessage.value = '当前审批暂时无法完成，请稍后重试'
  } finally {
    actionBusy.value = null
  }
}

async function advanceSupportStatus(row: Row) {
  actionBusy.value = row.exception_no ?? row.event_no ?? row.cost_no
  actionMessage.value = ''
  try {
    if (props.activeRoute === '/production/material-exceptions') {
      const status = row.status === 'PENDING' ? 'IN_PROGRESS' : 'CLOSED'
      await request<Row>(`/production/material-exceptions/${encodeURIComponent(row.exception_no)}/status`, {
        method: 'PUT', body: JSON.stringify({ status, responsibility_owner: row.responsibility_owner, description: row.description })
      })
    } else if (props.activeRoute === '/production/safety-environment') {
      const status = row.status === 'PENDING' ? 'IN_PROGRESS' : 'CLOSED'
      await request<Row>(`/production/safety-environment/events/${encodeURIComponent(row.event_no)}/status`, {
        method: 'PUT', body: JSON.stringify({ status, responsible_owner: row.responsible_owner, description: row.description })
      })
    } else if (props.activeRoute === '/production/cost-management') {
      await request<Row>(`/production/cost-management/records/${encodeURIComponent(row.cost_no)}/status`, {
        method: 'PUT', body: JSON.stringify({ status: 'CONFIRMED' })
      })
    }
    await loadSupportSummary()
    actionMessage.value = '处理结果已保存'
  } catch {
    actionMessage.value = '当前操作暂时无法完成，请稍后重试'
  } finally {
    actionBusy.value = null
  }
}

function openDrawer(title: string, kind: string, data: Row) {
  drawerTitle.value = title
  drawerKind.value = kind
  drawerData.value = data
  drawerVisible.value = true
}

function closeDrawer() {
  drawerVisible.value = false
  drawerData.value = {}
  drawerKind.value = ''
}

async function openQualitySettings() {
  openDrawer('返工原因与责任', 'dictionary', {})
  await loadDictionaryItems()
}

async function loadDictionaryItems() {
  dictionaryLoading.value = true
  dictionaryError.value = false
  dictionaryMessage.value = ''
  try {
    dictionaryItems.value = await request<Row[]>(`/reworks/dictionaries/items?dictionary_type=${dictionaryType.value}`)
    const selected = dictionaryItems.value.find((item) => item.item_id === selectedDictionaryId.value) ?? dictionaryItems.value[0]
    if (selected) selectDictionary(selected)
  } catch {
    dictionaryItems.value = []
    dictionaryError.value = true
  } finally {
    dictionaryLoading.value = false
  }
}

function selectDictionary(item: Row) {
  selectedDictionaryId.value = item.item_id
  dictionaryLabel.value = item.label
  dictionarySort.value = item.sort_order
}

async function saveDictionary() {
  if (!selectedDictionaryId.value || !dictionaryLabel.value.trim()) return
  dictionarySaving.value = true
  dictionaryMessage.value = ''
  try {
    await request<Row>(`/reworks/dictionaries/items/${selectedDictionaryId.value}`, {
      method: 'PUT',
      body: JSON.stringify({ label: dictionaryLabel.value.trim(), sort_order: dictionarySort.value })
    })
    dictionaryMessage.value = '设置已保存'
    await loadDictionaryItems()
  } catch {
    dictionaryMessage.value = '设置暂时无法保存，请稍后重试'
  } finally {
    dictionarySaving.value = false
  }
}

const supportCards = computed(() => {
  const s = supportSummary.value ?? {}
  if (props.activeRoute === '/production/devices') return [
    ['设备总数', s.total_equipment_count], ['运行', s.running_count], ['待机', s.idle_count], ['维护', s.maintenance_count], ['故障', s.fault_count], ['平均稼动率', `${s.average_utilization_rate ?? 0}%`]
  ]
  if (props.activeRoute === '/production/material-exceptions') return [
    ['异常总数', s.total_exception_count], ['缺料', s.shortage_count], ['错料', s.wrong_material_count], ['批次异常', s.batch_abnormal_count], ['材料损耗', s.material_loss_count], ['待处理', s.pending_count]
  ]
  if (props.activeRoute === '/production/safety-environment') return [
    ['应检查', s.safety_inspection_count], ['未检查', s.pending_count], ['检查异常', s.high_risk_count], ['整改中', s.in_progress_count], ['逾期未检', s.overdue_count], ['已关闭', s.closed_count]
  ]
  return [
    ['成本记录', s.record_count], ['人员成本', costAmount(s.labor_cost_amount)], ['材料成本', costAmount(s.material_cost_amount)], ['工序成本', costAmount(s.process_cost_amount)], ['返工成本', costAmount(s.rework_cost_amount)], ['外协成本', costAmount(s.outsourcing_cost_amount)], ['异常提醒', s.abnormal_warning_count]
  ]
})

const supportEmptyText = computed(() => {
  if (props.activeRoute === '/production/devices') return '当前没有可查看的设备或审批记录'
  if (props.activeRoute === '/production/material-exceptions') return '当前没有可查看的物料异常记录'
  if (props.activeRoute === '/production/safety-environment') return '当前没有可查看的检查、整改或规则记录'
  return '当前没有可查看的订单成本记录'
})

const aiHoverIndex = ref<number | null>(null)
const aiRawPoints = computed(() => (aiTrend.value?.points ?? []) as Row[])
const aiPoints = computed(() => {
  const source = new Map(aiRawPoints.value.map((item) => [String(item.date), item]))
  const end = new Date()
  end.setHours(12, 0, 0, 0)
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(end)
    day.setDate(end.getDate() - (6 - index))
    const date = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
    const point = source.get(date)
    return {
      date,
      success_count: Number(point?.success_count ?? 0),
      estimated_cost_microusd: Number(point?.estimated_cost_microusd ?? 0)
    }
  })
})
const aiMax = computed(() => Math.max(1, ...aiPoints.value.map((item) => item.success_count)))
const aiCostMax = computed(() => Math.max(1, ...aiPoints.value.map((item) => item.estimated_cost_microusd)))
const aiChartPoints = computed(() => aiPoints.value.map((item, index) => ({
  ...item,
  x: 44 + index * (512 / 6),
  y: 156 - item.success_count / aiMax.value * 112,
  costY: 156 - item.estimated_cost_microusd / aiCostMax.value * 112
})))
const aiPolyline = computed(() => aiChartPoints.value.map((item) => `${item.x},${item.y}`).join(' '))
const aiAreaPath = computed(() => `M 44 156 L ${aiPolyline.value.replaceAll(' ', ' L ')} L 556 156 Z`)
const aiCostPolyline = computed(() => aiChartPoints.value.map((item) => `${item.x},${item.costY}`).join(' '))
const aiHasActivity = computed(() => aiPoints.value.some((item) => item.success_count > 0 || item.estimated_cost_microusd > 0))
const aiHasCost = computed(() => aiPoints.value.some((item) => item.estimated_cost_microusd > 0))
const aiActiveChartPoint = computed(() => aiChartPoints.value[aiHoverIndex.value ?? 6])
const aiDateRange = computed(() => `${aiPoints.value[0]?.date.slice(5).replace('-', '/')}—${aiPoints.value[6]?.date.slice(5).replace('-', '/')}`)
const aiBudgetPercent = computed(() => {
  const budget = Number(aiSummary.value?.daily_budget_microusd ?? 0)
  if (budget <= 0) return 0
  return Math.min(100, Number(aiSummary.value?.estimated_cost_microusd ?? 0) / budget * 100)
})

function microUsd(value: number | null | undefined) {
  return `$${(Number(value ?? 0) / 1_000_000).toFixed(4)}`
}

function noticeLabel(event: string) {
  const labels: Record<string, string> = {
    ORDER_STATUS_CHANGED: '订单状态', MESSAGE_CREATED: '沟通消息', DESIGN_DRAFT_CREATED: '设计稿',
    BILL_CREATED: '账单提醒', LOGISTICS_UPDATED: '配送提醒', AI_BUDGET_EXCEEDED: '预算提醒'
  }
  return labels[event] ?? '业务通知'
}

function escapeClose(event: KeyboardEvent) {
  if (event.key === 'Escape' && drawerVisible.value) closeDrawer()
}

watch(() => props.activeRoute, () => {
  if (!routeSet.has(props.activeRoute)) return
  resetViewState()
  void refresh()
}, { immediate: true })

watch([keyword, statusFilter], () => { page.value = 1 })
watch([deliveryRegion, deliveryTab], () => { page.value = 1; keyword.value = ''; statusFilter.value = 'ALL' })
watch([equipmentTab, safetyTab], () => { page.value = 1; keyword.value = ''; statusFilter.value = 'ALL' })
watch(dictionaryType, () => { if (drawerKind.value === 'dictionary') void loadDictionaryItems() })

onMounted(() => window.addEventListener('keydown', escapeClose))
onBeforeUnmount(() => window.removeEventListener('keydown', escapeClose))

defineExpose({ refresh, openQualitySettings })
</script>

<template>
  <section class="arp-page" :data-route="activeRoute">
    <div v-if="failed" class="arp-state arp-state-error" role="alert"><strong>{{ failureMessage }}</strong><button type="button" @click="refresh">重新加载</button></div>
    <div v-else-if="loading" class="arp-state"><span class="arp-spinner" />正在加载业务数据…</div>

    <template v-else-if="activeRoute === '/admin/clinics'">
      <nav class="arp-primary-tabs"><button :class="{ active: clientTab === 'directory' }" @click="clientTab = 'directory'">客户目录</button><button :class="{ active: clientTab === 'contribution' }" @click="clientTab = 'contribution'">客户贡献</button></nav>
      <div v-if="clientTab === 'directory'" class="arp-table-card arp-fill-card">
        <div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索客户、联系人或电话"></label><select v-model="statusFilter"><option value="ALL">全部状态</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select><button @click="clearFilters">清空</button><em>共 {{ filteredClients.length }} 家客户</em></div>
        <div class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>客户</th><th>联系人</th><th>客户状态</th><th>制作偏好</th><th>订单与件数</th><th>接单金额</th><th>最近维护</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.clinic_id" @click="openClient(row)"><td><span class="arp-avatar">🏥</span><strong>{{ row.clinic_name }}</strong><small>客户编号 {{ row.clinic_id }}</small></td><td><strong>{{ row.contact_name || '暂未维护' }}</strong><small>{{ row.contact_phone || '电话暂未维护' }}</small></td><td><i class="arp-badge" :class="row.status === 'ACTIVE' ? 'ok' : 'muted'">{{ statusLabel(row.status) }}</i></td><td>{{ row.preference_count }} 项</td><td><strong>{{ clientRankingMap.get(row.clinic_id)?.order_count ?? '暂未统计' }} 单</strong><small>{{ clientRankingMap.get(row.clinic_id)?.item_count ?? '暂未统计' }} 件</small></td><td>暂未统计</td><td>{{ compactDate(row.updated_at) }}</td><td><button @click.stop="openClient(row)">查看</button></td></tr></tbody></table></div>
        <footer class="arp-pagination"><span>显示 {{ (page - 1) * pageSize + (currentRows.length ? 1 : 0) }}–{{ Math.min(page * pageSize, currentRows.length) }}，共 {{ currentRows.length }} 条</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer>
      </div>
      <div v-else class="arp-contribution">
        <div class="arp-sales-band"><div><span>本年接单金额</span><strong>{{ amount(salesSummary?.inbound?.current_amount_cents, salesSummary?.currency || 'CNY') }}</strong><small>截至 {{ salesSummary?.through_date || '当前周期' }}</small></div><div><span>本年出货金额</span><strong>{{ amount(salesSummary?.outbound?.current_amount_cents, salesSummary?.currency || 'CNY') }}</strong><small>金额覆盖 {{ salesSummary?.outbound?.current_amount_order_count ?? 0 }} 单</small></div><div><span>本月订单</span><strong>{{ clientSummary?.current_month?.order_count ?? 0 }}</strong><small>较上月 {{ clientSummary?.monthly_order_delta ?? 0 }}</small></div><div><span>本月件数</span><strong>{{ clientSummary?.current_month?.item_count ?? 0 }}</strong><small>较上月 {{ clientSummary?.monthly_item_delta ?? 0 }}</small></div></div>
        <div class="arp-ranking-card">
          <header><div><span>🏆</span><strong>本月客户贡献</strong></div><small>按真实件数排序 · 客户销售额暂未统计</small></header>
          <div class="arp-ranking-head"><span>排名与客户</span><span>件数贡献</span><span>订单</span><span>件数</span><span>接单金额</span></div>
          <article v-for="(row, index) in (clientSummary?.top_customers ?? []) as Row[]" :key="row.clinic_id">
            <div><i>{{ Number(index) + 1 }}</i><strong>{{ row.clinic_name }}</strong></div>
            <span><i :style="{ width: `${Math.max(8, Number(row.item_count) / Math.max(1, Number(clientSummary?.top_customers?.[0]?.item_count ?? 1)) * 100)}%` }" /></span>
            <b>{{ row.order_count }}</b><b>{{ row.item_count }}</b><em>暂未统计</em>
          </article>
          <div v-if="!(clientSummary?.top_customers?.length)" class="arp-empty">当前还没有可查看的客户贡献记录</div>
        </div>
      </div>
    </template>

    <template v-else-if="activeRoute === '/delivery'">
      <nav class="arp-primary-tabs arp-delivery-region-tabs" aria-label="配送地区">
        <button data-testid="delivery-region-domestic" :class="{ active: deliveryRegion === 'DOMESTIC' }" @click="deliveryRegion = 'DOMESTIC'">国内业务 <span>{{ deliveryRegionCounts.DOMESTIC }}</span></button>
        <button data-testid="delivery-region-international" :class="{ active: deliveryRegion === 'INTERNATIONAL' }" @click="deliveryRegion = 'INTERNATIONAL'">国外业务 <span>{{ deliveryRegionCounts.INTERNATIONAL }}</span></button>
        <button data-testid="delivery-region-unclassified" :class="{ active: deliveryRegion === 'UNCLASSIFIED' }" @click="deliveryRegion = 'UNCLASSIFIED'">待归类 <span>{{ deliveryRegionCounts.UNCLASSIFIED }}</span></button>
      </nav>
      <nav class="arp-secondary-tabs"><button :class="{ active: deliveryTab === 'billing' }" @click="deliveryTab = 'billing'">账单与收款</button><button :class="{ active: deliveryTab === 'tracking' }" @click="deliveryTab = 'tracking'">配送跟踪</button></nav>
      <div v-if="deliveryRegionNotice" class="arp-business-note">⚠️ {{ deliveryRegionNotice }}</div>
      <div class="arp-table-card arp-delivery-card">
        <div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索订单号、运单号或目的国家"></label><select v-model="statusFilter"><option v-for="option in deliveryStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><button @click="refresh">刷新</button><em>当前结果 {{ filteredDeliveryOrders.length }} 条</em></div>
        <div class="arp-table-scroll"><table class="arp-wide"><thead><tr v-if="deliveryTab === 'billing'"><th>订单</th><th>产品</th><th>账单状态</th><th>付款状态</th><th>账单金额</th><th>配送地区</th><th>操作</th></tr><tr v-else><th>订单</th><th>产品</th><th>配送地区</th><th>承运商</th><th>运单号</th><th>发货状态</th><th>配送状态</th><th>最近跟进</th><th>操作</th></tr></thead><tbody><tr v-for="row in filteredDeliveryOrders" :key="row.order_id" @click="openDelivery(row)"><template v-if="deliveryTab === 'billing'"><td><strong>{{ row.order_no }}</strong><small>订单编号 {{ row.order_id }}</small></td><td>{{ productLabel(row.product_type) }}</td><td><i class="arp-badge">{{ statusLabel(row.bill_status) }}</i></td><td><i class="arp-badge">{{ statusLabel(row.payment_status) }}</i></td><td>查看详情</td><td><i class="arp-badge" :class="deliveryRegionOf(row) === 'UNCLASSIFIED' ? 'muted' : 'info'">{{ deliveryRegionLabel(row) }}</i></td><td><button @click.stop="openDelivery(row)">查看</button></td></template><template v-else><td><strong>{{ row.order_no }}</strong></td><td>{{ productLabel(row.product_type) }}</td><td><i class="arp-badge" :class="deliveryRegionOf(row) === 'UNCLASSIFIED' ? 'muted' : 'info'">{{ deliveryRegionLabel(row) }}</i></td><td>{{ row.carrier || '暂未记录' }}</td><td>{{ row.tracking_no || '暂未记录' }}</td><td>{{ statusLabel(row.external_status) }}</td><td><i class="arp-badge" :class="row.logistics_status === 'EXCEPTION' ? 'danger' : 'ok'">{{ statusLabel(row.logistics_status) }}</i></td><td>{{ row.last_follow_up_note || '暂无跟进记录' }}</td><td><button @click.stop="openDelivery(row)">查看</button></td></template></tr></tbody></table></div>
        <div v-if="filteredDeliveryOrders.length === 0" class="arp-empty">{{ deliveryEmptyText }}</div>
      </div>
    </template>

    <template v-else-if="activeRoute === '/admin/outsourcing'">
      <div class="arp-table-card arp-fill-card"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索外协件、订单或供应商"></label><select v-model="statusFilter"><option value="ALL">全部状态</option><option value="SENT">已发出</option><option value="OVERDUE">已超时</option><option value="DELAYED">已延迟</option><option value="RETURNED">已返回</option></select><button @click="clearFilters">清空</button><button @click="refresh">刷新</button><em>真实记录 {{ filteredOutsourcing.length }} 条</em></div><div class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>外协件 / 订单</th><th>供应商</th><th>数量</th><th>发出时间</th><th>预计返回</th><th>实际返回</th><th>流转状态</th><th>异常状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.outsourcing_id" @click="openOutsourcing(row)"><td><strong>{{ row.item_name }}</strong><small>{{ row.batch_no }} · {{ row.order_no }}</small></td><td>{{ row.supplier_name }}</td><td>{{ row.quantity }} 件</td><td>{{ compactDate(row.sent_at) }}</td><td>{{ compactDate(row.expected_return_at) }}</td><td>{{ compactDate(row.actual_return_at) }}</td><td><i class="arp-badge" :class="row.status === 'RETURNED' ? 'ok' : row.status === 'DELAYED' ? 'danger' : 'info'">{{ statusLabel(row.status) }}</i></td><td><i v-if="row.is_overdue" class="arp-badge danger">已超时</i><span v-else>{{ row.abnormal_note || '无异常' }}</span></td><td><button @click.stop="openOutsourcing(row)">查看</button></td></tr></tbody></table><div v-if="filteredOutsourcing.length === 0" class="arp-empty arp-empty-large"><span>☁️</span><strong>当前还没有可查看的外协进度记录</strong><p>发生外协后，将按外协件或批次展示发出、预计返回、实际返回、超时和异常。</p></div></div><footer class="arp-pagination"><span>按外协件或批次追踪，不按整单合并</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="activeRoute === '/workflow/process-instance'">
      <div class="arp-table-card arp-fill-card"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索订单、客户或产品"></label><select v-model="statusFilter"><option value="HAS_PROCESS">已生成工序</option><option value="UNASSIGNED">待派工</option><option value="PRODUCING">生产中</option><option value="COMPLETED">已完成</option><option value="NO_PROCESS">尚未生成工序</option><option value="ALL">全部订单</option></select><button @click="clearFilters">清空</button><em>共 {{ filteredProcesses.length }} 单</em></div><div class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>订单</th><th>客户 / 产品</th><th>当前生产工序</th><th>生产进度</th><th>当前执行人</th><th>时限</th><th>异常</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.order.order_id" @click="openProcess(row)"><td><strong>{{ row.order.order_no }}</strong><small>编号 {{ row.order.order_id }}</small></td><td><strong>{{ row.order.clinic_name || '客户暂未记录' }}</strong><small>{{ productLabel(row.order.product_type) }}</small></td><td>{{ processCurrent(row).process_name || (row.failed ? '暂时无法查看' : '未开始') }}</td><td><div class="arp-progress"><i :style="{ width: `${processProgress(row)}%` }" /></div><small>{{ processProgressText(row) }}</small></td><td>{{ processExecutorText(row) }}</td><td>{{ compactDate(processCurrent(row).deadline_at) }}</td><td><i v-if="processAnomalyText(row) !== '无'" class="arp-badge danger">{{ processAnomalyText(row) }}</i><span v-else>无</span></td><td><button @click.stop="openProcess(row)">查看</button></td></tr></tbody></table><div v-if="filteredProcesses.length === 0" class="arp-empty">当前筛选下没有工序订单</div></div><footer class="arp-pagination"><span>共 {{ currentRows.length }} 单</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="activeRoute === '/production/quality'">
      <div class="arp-metric-band"><article><span>出检订单</span><strong>{{ qualitySummary?.inspected_order_count ?? 0 }}</strong></article><article><span>一次通过率</span><strong>{{ qualitySummary?.first_pass_rate ?? 0 }}%</strong></article><article><span>终检通过率</span><strong>{{ qualitySummary?.final_pass_rate ?? 0 }}%</strong></article><article><span>总返工率</span><strong>{{ qualitySummary?.total_rework_rate ?? 0 }}%</strong></article><article><span>内返 / 外返</span><strong>{{ qualitySummary?.internal_rework_count ?? 0 }} / {{ qualitySummary?.external_rework_count ?? 0 }}</strong></article><article><span>投诉 / 退货</span><strong>{{ optionalRate(qualitySummary?.complaint_rate) }} / {{ optionalRate(qualitySummary?.return_rate) }}</strong></article></div>
      <div class="arp-table-card arp-metric-table"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索订单或问题原因"></label><select v-model="statusFilter"><option value="ALL">全部问题</option><option value="内返">内返</option><option value="外返">外返</option><option value="PENDING">待处理</option><option value="CLOSED">已关闭</option></select><button @click="clearFilters">清空</button><em>问题记录 {{ filteredQuality.length }} 条</em></div><div class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>问题类型</th><th>订单</th><th>客户 / 产品</th><th>来源</th><th>原因</th><th>责任依据</th><th>处理状态</th><th>发生 / 更新</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.rework_id ?? row.quality_record_id" @click="openDrawer('质量问题详情', 'quality', row)"><td><i class="arp-badge" :class="row.issue_type === '外返' ? 'danger' : 'warning'">{{ row.issue_type }}</i></td><td><strong>{{ row.order_no || `订单 ${row.order_id}` }}</strong></td><td><strong>{{ row.clinic_name || '客户暂未记录' }}</strong><small>{{ productLabel(row.product_type) }}</small></td><td>{{ row.source }}</td><td>{{ row.reason_detail || row.reason_category || '暂未记录' }}</td><td>{{ row.issue_type === '外返' ? '当前责任信息以客服登记结果为准' : statusLabel(row.responsibility_type) }}</td><td>{{ statusLabel(row.status) }}</td><td>{{ compactDate(row.updated_at || row.status_updated_at || row.created_at) }}</td><td><button @click.stop="openDrawer('质量问题详情', 'quality', row)">查看</button></td></tr></tbody></table></div><div v-if="filteredQuality.length === 0" class="arp-empty">当前还没有可查看的质量问题记录</div></div>
    </template>

    <template v-else-if="activeRoute === '/performance'">
      <div class="arp-table-card arp-fill-card"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索员工、账号或部门"></label><button @click="refresh">刷新</button><button @click="clearFilters">清空</button><em>当前比较 {{ filteredPerformance.length }} 人</em></div><div class="arp-table-scroll"><table class="arp-wide arp-performance-table"><thead><tr><th>员工</th><th>完成工序</th><th>有效 / 标准工时</th><th>标准覆盖率</th><th>准时率</th><th>通过率</th><th>工时效率</th><th>生产责任返工</th><th>未归因返工</th><th>绩效参考分</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.staff.user_id" @click="openPerformance(row)"><td><span class="arp-person-avatar">{{ (row.staff.display_name || row.staff.username || '员').slice(0, 1) }}</span><strong>{{ row.staff.display_name || row.staff.username }}</strong><small>{{ row.staff.department_name || '部门暂未记录' }} · {{ row.staff.post_names?.join(' / ') || '岗位暂未记录' }}</small></td><template v-if="!row.failed"><td>{{ row.stats.completed_count }}</td><td>{{ row.stats.effective_duration }} / {{ row.stats.standard_time_pending ? '—' : row.stats.standard_duration }} 分钟</td><td>{{ row.stats.standard_time_pending ? '待数据' : `${row.stats.standard_coverage_rate}%` }}</td><td>{{ row.stats.standard_time_pending ? '待数据' : `${row.stats.on_time_rate}%` }}</td><td>{{ row.stats.pass_rate }}%</td><td>{{ row.stats.standard_time_pending ? '待数据' : `${row.stats.duration_efficiency}%` }}</td><td>{{ row.stats.responsible_rework_count }}</td><td>{{ row.stats.unclassified_rework_count }}</td><td><strong class="arp-score">{{ row.stats.standard_time_pending ? '—' : row.stats.performance_score }}</strong><small>{{ row.stats.standard_time_pending ? '正式口径未启用' : '仅供绩效分析' }}</small></td></template><template v-else><td colspan="9"><span class="arp-row-failed">该员工数据暂时无法加载</span></td></template><td><button @click.stop="openPerformance(row)">查看</button></td></tr></tbody></table></div><footer><span>客户正式标准工时未启用时，标准工时、准时率、效率和绩效分均不参与计算。</span></footer><footer class="arp-pagination"><span>只使用当前可见员工与真实绩效结果</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="['/production/devices', '/production/material-exceptions', '/production/safety-environment', '/production/cost-management'].includes(activeRoute)">
      <nav v-if="activeRoute === '/production/devices'" class="arp-primary-tabs"><button :class="{ active: equipmentTab === 'list' }" @click="equipmentTab = 'list'">设备清单</button><button :class="{ active: equipmentTab === 'approval' }" @click="equipmentTab = 'approval'">审批事项</button></nav>
      <nav v-if="activeRoute === '/production/safety-environment'" class="arp-primary-tabs"><button :class="{ active: safetyTab === 'supervision' }" @click="safetyTab = 'supervision'">检查监督</button><button :class="{ active: safetyTab === 'rules' }" @click="safetyTab = 'rules'">检查规则</button></nav>
      <div class="arp-metric-band"><article v-for="card in supportCards" :key="card[0]"><span>{{ card[0] }}</span><strong>{{ card[1] ?? 0 }}</strong></article></div>
      <div class="arp-table-card arp-metric-table"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" :placeholder="activeRoute === '/production/devices' ? '搜索设备编号或名称' : activeRoute === '/production/material-exceptions' ? '搜索异常编号或物料' : activeRoute === '/production/safety-environment' ? '搜索部门、事项或检查规则' : '搜索成本或订单编号'"></label><select v-model="statusFilter"><option v-for="option in supportStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select><button @click="clearFilters">清空</button><button @click="refresh">刷新</button><em>{{ actionMessage || `共 ${filteredSupport.length} 条 · 更新于 ${compactDate(supportSummary?.generated_at)}` }}</em></div>
        <div class="arp-table-scroll">
          <table v-if="activeRoute === '/production/devices' && equipmentTab === 'list'" class="arp-wide"><thead><tr><th>设备</th><th>类型 / 部门</th><th>状态</th><th>负责人</th><th>稼动率</th><th>上次维护</th><th>下次维护</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.equipment_id" @click="openSupport(row)"><td><strong>{{ row.equipment_name }}</strong><small>{{ row.equipment_code }}</small></td><td><strong>{{ row.equipment_type }}</strong><small>{{ row.department_name || '部门暂未记录' }}</small></td><td><i class="arp-badge" :class="row.status === 'FAULT' ? 'danger' : row.status === 'RUNNING' ? 'ok' : 'warning'">{{ statusLabel(row.status) }}</i></td><td>{{ row.owner_user_id ? `员工 ${row.owner_user_id}` : '暂未指定' }}</td><td><div class="arp-progress"><i :style="{ width: `${row.utilization_rate}%` }" /></div><small>{{ row.utilization_rate }}%</small></td><td>{{ compactDate(row.last_maintenance_at) }}</td><td>{{ compactDate(row.next_maintenance_at) }}</td><td>{{ compactDate(row.updated_at) }}</td><td><button @click.stop="openSupport(row)">查看</button></td></tr></tbody></table>
          <table v-else-if="activeRoute === '/production/devices'" class="arp-wide"><thead><tr><th>审批事项</th><th>设备编号</th><th>申请人</th><th>停机影响</th><th>申请说明</th><th>审批状态</th><th>申请时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.event_id"><td><strong>{{ supportTypeLabel(row.event_type) }}</strong></td><td>{{ row.equipment_code }}</td><td>{{ row.requested_by_user_id ? `员工 ${row.requested_by_user_id}` : '暂未记录' }}</td><td>{{ row.downtime_minutes }} 分钟</td><td>{{ row.description || '暂无说明' }}</td><td><i class="arp-badge" :class="row.status === 'PENDING' ? 'warning' : row.status === 'APPROVED' ? 'ok' : 'danger'">{{ statusLabel(row.status) }}</i></td><td>{{ compactDate(row.created_at) }}</td><td><div v-if="row.status === 'PENDING'" class="arp-row-actions"><button :disabled="actionBusy === row.event_id" @click="decideEquipmentApproval(row, 'APPROVED')">通过</button><button class="danger" :disabled="actionBusy === row.event_id" @click="decideEquipmentApproval(row, 'REJECTED')">驳回</button></div><span v-else>{{ row.decision_note || '已完成审批' }}</span></td></tr></tbody></table>
          <table v-else-if="activeRoute === '/production/material-exceptions'" class="arp-wide"><thead><tr><th>异常 / 物料</th><th>关联订单</th><th>异常类型</th><th>损耗数量</th><th>责任人</th><th>处理状态</th><th>说明</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.exception_id" @click="openSupport(row)"><td><strong>{{ row.material_name }}</strong><small>{{ row.exception_no }} · {{ row.material_code }}</small></td><td>{{ row.order_id ? `订单 ${row.order_id}` : '暂未关联' }}</td><td>{{ supportTypeLabel(row.exception_type) }}</td><td>{{ row.loss_quantity }}</td><td>{{ row.responsibility_owner || '待确认' }}</td><td><i class="arp-badge" :class="row.status === 'CLOSED' ? 'ok' : row.status === 'PENDING' ? 'warning' : 'info'">{{ statusLabel(row.status) }}</i></td><td>{{ row.description || '暂无说明' }}</td><td>{{ compactDate(row.updated_at) }}</td><td><div class="arp-row-actions"><button @click.stop="openSupport(row)">查看</button><button v-if="row.status !== 'CLOSED'" :disabled="actionBusy === row.exception_no" @click.stop="advanceSupportStatus(row)">{{ row.status === 'PENDING' ? '开始处理' : '关闭' }}</button></div></td></tr></tbody></table>
          <table v-else-if="activeRoute === '/production/safety-environment' && safetyTab === 'rules'" class="arp-wide"><thead><tr><th>检查规则</th><th>检查类型</th><th>适用部门</th><th>固定周期</th><th>负责人</th><th>下次应检</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.rule_id" @click="openSupport(row)"><td><strong>{{ row.rule_name }}</strong><small>{{ row.rule_code }}</small></td><td>{{ supportTypeLabel(row.check_type) }}</td><td>{{ row.department_name }}</td><td>{{ supportTypeLabel(row.cycle_type) }} · 每 {{ row.cycle_interval }} 个周期</td><td>{{ row.responsible_owner || '暂未指定' }}</td><td>{{ compactDate(row.next_due_at) }}</td><td><i class="arp-badge" :class="row.status === 'ACTIVE' ? 'ok' : 'muted'">{{ statusLabel(row.status) }}</i></td><td>{{ compactDate(row.updated_at) }}</td><td><button @click.stop="openSupport(row)">查看</button></td></tr></tbody></table>
          <table v-else-if="activeRoute === '/production/safety-environment'" class="arp-wide"><thead><tr><th>检查 / 整改事项</th><th>部门 / 设备</th><th>风险等级</th><th>负责人</th><th>应完成时间</th><th>整改状态</th><th>说明</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.event_id" @click="openSupport(row)"><td><strong>{{ supportTypeLabel(row.event_type) }}</strong><small>{{ row.event_no }}</small></td><td><strong>{{ row.department_name || '部门暂未记录' }}</strong><small>{{ row.equipment_code || '无关联设备' }}</small></td><td><i class="arp-badge" :class="row.risk_level === 'CRITICAL' ? 'danger' : row.risk_level === 'HIGH' ? 'warning' : 'ok'">{{ row.risk_level === 'CRITICAL' ? '重大' : row.risk_level === 'HIGH' ? '较高' : '一般' }}</i></td><td>{{ row.responsible_owner || '待确认' }}</td><td>{{ compactDate(row.due_at) }}<small v-if="row.status !== 'CLOSED' && row.due_at && new Date(row.due_at) < new Date()">已逾期</small></td><td>{{ statusLabel(row.status) }}</td><td>{{ row.description || '暂无说明' }}</td><td><div class="arp-row-actions"><button @click.stop="openSupport(row)">查看</button><button v-if="row.status !== 'CLOSED'" :disabled="actionBusy === row.event_no" @click.stop="advanceSupportStatus(row)">{{ row.status === 'PENDING' ? '开始整改' : '关闭' }}</button></div></td></tr></tbody></table>
          <table v-else class="arp-wide"><thead><tr><th>成本记录</th><th>关联订单 / 工序</th><th>成本类型</th><th>金额</th><th>部门 / 供应方</th><th>异常状态</th><th>追溯说明</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.cost_id" @click="openSupport(row)"><td><strong>{{ row.cost_no }}</strong></td><td><strong>{{ row.order_id ? `订单 ${row.order_id}` : '暂未关联订单' }}</strong><small>{{ row.node_instance_id ? `工序 ${row.node_instance_id}` : '订单级成本' }}</small></td><td>{{ supportTypeLabel(row.cost_type) }}</td><td><strong>¥{{ Number(row.amount).toFixed(2) }}</strong></td><td><strong>{{ row.department_name || '部门暂未记录' }}</strong><small>{{ row.supplier_name || '内部成本' }}</small></td><td><i class="arp-badge" :class="row.status === 'WARNING' ? 'danger' : row.status === 'CONFIRMED' ? 'ok' : 'info'">{{ statusLabel(row.status) }}</i></td><td>{{ row.description || '暂无说明' }}</td><td>{{ compactDate(row.updated_at) }}</td><td><div class="arp-row-actions"><button @click.stop="openSupport(row)">查看</button><button v-if="row.status !== 'CONFIRMED'" :disabled="actionBusy === row.cost_no" @click.stop="advanceSupportStatus(row)">确认记录</button></div></td></tr></tbody></table>
          <div v-if="filteredSupport.length === 0" class="arp-empty arp-empty-large"><span>{{ activeRoute === '/production/devices' ? '⚙️' : activeRoute === '/production/material-exceptions' ? '⚠️' : activeRoute === '/production/safety-environment' ? '🛡️' : '📊' }}</span><strong>{{ supportEmptyText }}</strong><p>当前筛选条件下没有可查看的业务记录。</p></div>
        </div><footer class="arp-pagination"><span>显示 {{ (page - 1) * pageSize + (currentRows.length ? 1 : 0) }}–{{ Math.min(page * pageSize, currentRows.length) }}，共 {{ currentRows.length }} 条</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="activeRoute === '/system/form-configs'">
      <div class="arp-table-card arp-fill-card"><div class="arp-toolbar"><label class="arp-search"><span>⌕</span><input v-model="keyword" placeholder="搜索产品名称、类型或材料"></label><select v-model="statusFilter"><option value="ALL">全部状态</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select><button @click="clearFilters">清空</button><em>共 {{ filteredProducts.length }} 个产品</em></div><div class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>产品名称</th><th>产品类型</th><th>材料规格</th><th>基础价格</th><th>币种</th><th>启用状态</th><th>价格备注</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.product_id" @click="openDrawer('产品详情', 'product', row)"><td><span class="arp-product-icon">🦷</span><strong>{{ row.product_name }}</strong><small>产品编号 {{ row.product_id }}</small></td><td>{{ productLabel(row.product_type) }}</td><td>{{ row.material_spec || '暂未维护' }}</td><td>{{ row.base_price_cents <= 1 ? '价格待确认' : amount(row.base_price_cents, row.currency) }}</td><td>{{ row.currency }}</td><td><i class="arp-badge" :class="row.status === 'ACTIVE' ? 'ok' : 'muted'">{{ statusLabel(row.status) }}</i></td><td>{{ row.price_note || '暂无备注' }}</td><td>{{ compactDate(row.updated_at) }}</td><td><button @click.stop="openDrawer('产品详情', 'product', row)">查看</button></td></tr></tbody></table></div><footer class="arp-pagination"><span>管理端只读查看客服端维护结果</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="activeRoute === '/notifications'">
      <div class="arp-table-card arp-fill-card"><div class="arp-toolbar"><div class="arp-segment"><button :class="{ active: notificationTab === 'all' }" @click="notificationTab = 'all'">全部</button><button :class="{ active: notificationTab === 'unread' }" @click="notificationTab = 'unread'">未读 <b>{{ unreadCount }}</b></button></div><button :disabled="notificationsLoading" @click="emit('refreshNotifications')">刷新</button><button :disabled="unreadCount === 0" @click="emit('markAllNotificationsRead')">全部已读</button><em>当前账号 {{ filteredNotices.length }} 条通知</em></div><div v-if="notificationError" class="arp-state arp-state-error">{{ businessFailure }}</div><div v-else class="arp-table-scroll"><table class="arp-wide"><thead><tr><th>类型</th><th>通知内容</th><th>关联订单</th><th>已读状态</th><th>送达 / 创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in pagedRows" :key="row.notification_id" :class="{ 'is-unread': !row.read_at }"><td><span class="arp-notice-dot" :class="{ active: !row.read_at }" />{{ noticeLabel(row.event) }}</td><td><strong>{{ row.message || '业务状态已更新' }}</strong></td><td><button v-if="row.order_id" class="arp-order-link" @click="emit('openOrder', row.order_id)">{{ row.order_no || `订单 ${row.order_id}` }}</button><span v-else>无关联订单</span></td><td><i class="arp-badge" :class="row.read_at ? 'muted' : 'info'">{{ row.read_at ? '已读' : '未读' }}</i></td><td>{{ compactDate(row.delivered_at || row.created_at) }}</td><td><button v-if="!row.read_at" @click="emit('markNotificationRead', row.notification_id)">标记已读</button><span v-else>已完成</span></td></tr></tbody></table></div><div v-if="filteredNotices.length === 0 && !notificationsLoading" class="arp-empty">当前没有{{ notificationTab === 'unread' ? '未读' : '' }}业务通知</div><footer class="arp-pagination"><span>实时更新不会改变当前滚动位置</span><div><button :disabled="page <= 1" @click="page--">上一页</button><b>{{ page }}</b><button :disabled="page >= pageCount" @click="page++">下一页</button></div></footer></div>
    </template>

    <template v-else-if="activeRoute === '/admin/ai-governance'">
      <div class="arp-ai-card">
        <div class="arp-ai-metrics">
          <article><span>24 小时成功调用</span><strong>{{ aiSummary?.success_count ?? 0 }}</strong><small>全平台总量</small></article>
          <article><span>安全拒答</span><strong>{{ aiSummary?.safe_refusal_count ?? 0 }}</strong><small>风险请求已拦截</small></article>
          <article><span>服务异常</span><strong>{{ aiSummary?.model_failed_count ?? 0 }}</strong><small>模型失败</small></article>
          <article><span>预计费用 / 每日预算</span><strong>{{ microUsd(aiSummary?.estimated_cost_microusd) }}</strong><small>{{ Number(aiSummary?.daily_budget_microusd ?? 0) > 0 ? microUsd(aiSummary?.daily_budget_microusd) : '暂未设置每日预算' }}</small></article>
        </div>

        <div class="arp-ai-grid">
          <section class="arp-trend-card">
            <header class="arp-trend-header">
              <div class="arp-trend-title">
                <span class="arp-icon-chip">
                  <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 17l5-5 4 3 7-8"/><path d="M15 7h5v5"/></svg>
                </span>
                <div><strong>近 7 日用量和成本趋势</strong><small>全平台成功调用与预计费用</small></div>
              </div>
              <div class="arp-trend-meta">
                <span>{{ aiDateRange }}</span>
                <i class="arp-legend-call">调用量</i>
                <i class="arp-legend-cost">预计成本</i>
              </div>
            </header>

            <div class="arp-trend-plot">
              <svg viewBox="0 0 600 200" preserveAspectRatio="xMidYMid meet" role="img" aria-label="近七日调用量和预计成本趋势图">
                <defs>
                  <linearGradient id="arp-ai-area" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0" stop-color="#3b82f6" stop-opacity=".22"/>
                    <stop offset="1" stop-color="#3b82f6" stop-opacity="0"/>
                  </linearGradient>
                </defs>
                <line v-for="y in [44, 100, 156]" :key="y" x1="44" :y1="y" x2="556" :y2="y" class="arp-grid-line"/>
                <path v-if="aiHasActivity" :d="aiAreaPath" class="arp-call-area"/>
                <polyline v-if="aiHasActivity" :points="aiPolyline" class="arp-call-line"/>
                <polyline v-if="aiHasCost" :points="aiCostPolyline" class="arp-cost-line"/>
                <line v-if="aiHasActivity && aiHoverIndex !== null" :x1="aiActiveChartPoint.x" y1="36" :x2="aiActiveChartPoint.x" y2="156" class="arp-hover-line"/>
                <circle v-if="aiHasActivity" :cx="aiActiveChartPoint.x" :cy="aiActiveChartPoint.y" r="5" class="arp-active-dot"/>
                <circle v-if="aiHasCost" :cx="aiActiveChartPoint.x" :cy="aiActiveChartPoint.costY" r="4" class="arp-active-cost-dot"/>
                <g v-if="aiHasActivity && aiHoverIndex !== null" :transform="`translate(${Math.min(428, Math.max(26, aiActiveChartPoint.x - 70))}, 6)`" class="arp-chart-tooltip">
                  <rect width="144" height="36" rx="8"/>
                  <text x="10" y="15">{{ aiActiveChartPoint.date.slice(5).replace('-', '/') }} · 调用 {{ aiActiveChartPoint.success_count }} 次</text>
                  <text x="10" y="29">预计费用 {{ microUsd(aiActiveChartPoint.estimated_cost_microusd) }}</text>
                </g>
                <g v-for="(point, index) in aiChartPoints" :key="point.date">
                  <rect :x="point.x - 42" y="38" width="84" height="126" class="arp-hover-zone" tabindex="0" @mouseenter="aiHoverIndex = index" @mouseleave="aiHoverIndex = null" @focus="aiHoverIndex = index" @blur="aiHoverIndex = null"/>
                  <text :x="point.x" y="187" text-anchor="middle" class="arp-axis-label">{{ point.date.slice(5).replace('-', '/') }}</text>
                </g>
              </svg>
              <div v-if="!aiHasActivity" class="arp-chart-empty"><span>近 7 日暂无成功调用</span><small>发生真实调用后将在这里形成趋势</small></div>
            </div>

            <footer class="arp-trend-summary">
              <article><span>7 日成功</span><strong>{{ aiTrend?.total_success_count ?? 0 }} 次</strong></article>
              <article><span>活跃天数</span><strong>{{ aiPoints.filter((item) => item.success_count > 0).length }} 天</strong></article>
              <article><span>预计费用</span><strong>{{ microUsd(aiTrend?.total_estimated_cost_microusd) }}</strong></article>
            </footer>
          </section>

          <div class="arp-ai-side">
            <section class="arp-risk-card">
              <header><strong>风险状态</strong><span>近 24 小时</span></header>
              <div class="arp-risk-grid">
                <article><i class="blue"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l7 3v5c0 4.6-2.9 7.8-7 10-4.1-2.2-7-5.4-7-10V6l7-3z"/><path d="M9 12l2 2 4-5"/></svg></i><span>安全拒答</span><b>{{ aiSummary?.safe_refusal_count ?? 0 }}</b></article>
                <article><i class="cyan"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2"/></svg></i><span>访问限流</span><b>{{ aiSummary?.rate_limited_count ?? 0 }}</b></article>
                <article><i class="orange"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4l9 16H3L12 4z"/><path d="M12 9v5m0 3h.01"/></svg></i><span>模型失败</span><b>{{ aiSummary?.model_failed_count ?? 0 }}</b></article>
                <article><i class="violet"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 16h12l-2-3V9a4 4 0 00-8 0v4l-2 3z"/><path d="M10 19h4"/></svg></i><span>预算提醒</span><b>{{ aiSummary?.budget_alert_count ?? 0 }}</b></article>
              </div>
            </section>

            <section class="arp-budget-card">
              <header><strong>预算状态</strong><span :class="{ danger: aiSummary?.budget_exceeded }">{{ aiSummary?.budget_exceeded ? '已触发保护' : '运行正常' }}</span></header>
              <div class="arp-budget">
                <span>当前预计费用</span>
                <strong>{{ microUsd(aiSummary?.estimated_cost_microusd) }}</strong>
                <div class="arp-budget-meta"><span>今日使用比例</span><b>{{ Number(aiSummary?.daily_budget_microusd ?? 0) > 0 ? `${aiBudgetPercent.toFixed(1)}%` : '—' }}</b></div>
                <div class="arp-progress"><i :style="{ width: `${aiBudgetPercent}%` }"/></div>
                <small>{{ Number(aiSummary?.daily_budget_microusd ?? 0) > 0 ? `每日预算 ${microUsd(aiSummary?.daily_budget_microusd)}` : '暂未设置每日预算' }}</small>
              </div>
            </section>
          </div>
        </div>
      </div>
    </template>

    <template v-if="!['equipment', 'material', 'safety', 'safety-rule', 'cost', 'outsourcing'].includes(drawerKind)">

    <div v-if="drawerVisible" class="arp-drawer-layer" @click.self="closeDrawer"><aside class="arp-drawer" role="dialog" aria-modal="true" :aria-label="drawerTitle"><header><div><span>只读业务详情</span><h2>{{ drawerTitle }}</h2></div><button aria-label="关闭" @click="closeDrawer">×</button></header><div class="arp-drawer-body"><div v-if="drawerLoading" class="arp-state"><span class="arp-spinner" />正在加载详情…</div><template v-else-if="drawerKind === 'client' && drawerData"><section class="arp-profile-banner"><i>🏥</i><div><strong>{{ drawerData.clinic_name }}</strong><span>客户编号 {{ drawerData.clinic_id }} · {{ statusLabel(drawerData.status) }}</span></div></section><section class="arp-detail-section"><header><span>01</span><div><h3>客户档案</h3><small>由客服端维护</small></div></header><dl><div><dt>联系人</dt><dd>{{ drawerData.contact_name || '暂未维护' }}</dd></div><div><dt>联系电话</dt><dd>{{ drawerData.contact_phone || '暂未维护' }}</dd></div><div><dt>建档时间</dt><dd>{{ compactDate(drawerData.created_at) }}</dd></div><div><dt>最近维护</dt><dd>{{ compactDate(drawerData.updated_at) }}</dd></div></dl></section><section class="arp-detail-section"><header><span>02</span><div><h3>客户贡献</h3><small>本月真实统计</small></div></header><dl><div><dt>排名</dt><dd>{{ drawerData.ranking ? `第 ${clientSummary?.top_customers?.findIndex((item: Row) => item.clinic_id === drawerData.clinic_id) + 1} 名` : '暂未进入排名' }}</dd></div><div><dt>订单 / 件数</dt><dd>{{ drawerData.ranking?.order_count ?? '暂未统计' }} / {{ drawerData.ranking?.item_count ?? '暂未统计' }}</dd></div><div><dt>接单金额</dt><dd>暂未统计</dd></div><div><dt>出货金额</dt><dd>暂未统计</dd></div></dl></section><section class="arp-detail-section"><header><span>03</span><div><h3>制作偏好</h3><small>全部只读</small></div></header><dl><div v-for="(value, key) in drawerData.preference?.preferences ?? {}" :key="key"><dt>{{ key }}</dt><dd>{{ value || '暂未维护' }}</dd></div></dl><p v-if="!Object.keys(drawerData.preference?.preferences ?? []).length" class="arp-detail-note">当前客户暂未维护制作偏好</p></section><p class="arp-readonly-note">客户资料由客服端维护，管理端仅用于经营与服务关系分析。</p></template><template v-else-if="drawerKind === 'delivery' && drawerData"><section class="arp-profile-banner"><i>📦</i><div><strong>{{ drawerData.order_no }}</strong><span>{{ productLabel(drawerData.product_type) }} · {{ statusLabel(drawerData.external_status) }}</span></div></section><section class="arp-detail-section"><header><span>01</span><div><h3>账单信息</h3><small>真实账单状态</small></div></header><dl><div><dt>账单状态</dt><dd>{{ statusLabel(drawerData.bill?.bill_status || drawerData.bill_status) }}</dd></div><div><dt>付款状态</dt><dd>{{ statusLabel(drawerData.bill?.payment_status || drawerData.payment_status) }}</dd></div><div><dt>账单金额</dt><dd>{{ amount(drawerData.bill?.amount_cents, drawerData.bill?.currency || 'CNY') }}</dd></div><div><dt>收款记录</dt><dd>{{ drawerData.payments?.length ?? 0 }} 条</dd></div></dl></section><section class="arp-detail-section"><header><span>02</span><div><h3>配送信息</h3><small>系统人工维护状态</small></div></header><dl><div><dt>承运商</dt><dd>{{ drawerData.logistics?.carrier || drawerData.carrier || '暂未记录' }}</dd></div><div><dt>运单号</dt><dd>{{ drawerData.logistics?.tracking_no || drawerData.tracking_no || '暂未记录' }}</dd></div><div><dt>配送状态</dt><dd>{{ statusLabel(drawerData.logistics?.logistics_status || drawerData.logistics_status) }}</dd></div><div><dt>配送地区</dt><dd>尚未维护</dd></div></dl></section></template><template v-else-if="drawerKind === 'process' && drawerData"><section class="arp-profile-banner"><i>⚙️</i><div><strong>{{ drawerData.order.order_no }}</strong><span>{{ productLabel(drawerData.order.product_type) }} · 进度 {{ processProgress(drawerData) }}%</span></div></section><p v-if="!drawerData.instance" class="arp-detail-note">当前订单尚未生成可查看的工序记录</p><section v-else class="arp-timeline"><article v-for="(node, index) in drawerData.instance.nodes" :key="node.node_instance_id"><i :class="node.node_status.toLowerCase()">{{ node.node_status === 'COMPLETED' ? '✓' : Number(index) + 1 }}</i><div><header><strong>{{ node.process_name }}</strong><span>{{ statusLabel(node.node_status) }}</span></header><p>执行人：{{ node.assigned_user_id ? `员工 ${node.assigned_user_id}` : '待派工' }}</p><small>开始 {{ compactDate(node.started_at) }} · 完成 {{ compactDate(node.completed_at) }} · 时限 {{ compactDate(node.deadline_at) }}</small></div></article></section><p class="arp-readonly-note">工序和派工信息由生产端维护，管理端仅查看生产进度并监督异常。</p></template><template v-else-if="drawerKind === 'quality' && drawerData"><section class="arp-profile-banner"><i>🔍</i><div><strong>{{ drawerData.order_no || `订单 ${drawerData.order_id}` }}</strong><span>{{ drawerData.issue_type }} · {{ statusLabel(drawerData.status) }}</span></div></section><section class="arp-detail-section"><header><span>01</span><div><h3>问题事实</h3><small>{{ drawerData.source }}</small></div></header><dl><div><dt>问题原因</dt><dd>{{ drawerData.reason_detail || drawerData.reason_category || '暂未记录' }}</dd></div><div><dt>发生时间</dt><dd>{{ compactDate(drawerData.created_at) }}</dd></div><div><dt>目标工序</dt><dd>{{ drawerData.target_process_name || '暂未记录' }}</dd></div><div><dt>影响节点</dt><dd>{{ drawerData.impacted_node_count ?? '暂未统计' }}</dd></div></dl></section><section class="arp-detail-section"><header><span>02</span><div><h3>责任与处理</h3><small>管理监督依据</small></div></header><dl><div><dt>责任依据</dt><dd>{{ drawerData.issue_type === '外返' ? '当前责任信息以客服登记结果为准' : statusLabel(drawerData.responsibility_type) }}</dd></div><div><dt>处理状态</dt><dd>{{ statusLabel(drawerData.status) }}</dd></div><div><dt>关闭说明</dt><dd>{{ drawerData.close_note || drawerData.status_note || '暂未记录' }}</dd></div><div><dt>最近更新</dt><dd>{{ compactDate(drawerData.updated_at || drawerData.status_updated_at) }}</dd></div></dl></section></template><template v-else-if="drawerKind === 'performance' && drawerData"><section class="arp-profile-banner"><i>{{ (drawerData.staff.display_name || drawerData.staff.username || '员').slice(0, 1) }}</i><div><strong>{{ drawerData.staff.display_name || drawerData.staff.username }}</strong><span>{{ drawerData.staff.department_name || '部门暂未记录' }} · 绩效参考分 {{ drawerData.stats?.performance_score ?? '暂未统计' }}</span></div></section><section class="arp-detail-section"><header><span>01</span><div><h3>绩效依据</h3><small>仅供绩效分析，不作为工资结算结果</small></div></header><dl><div><dt>完成工序</dt><dd>{{ drawerData.stats?.completed_count ?? '暂未统计' }}</dd></div><div><dt>有效 / 标准工时</dt><dd>{{ drawerData.stats?.effective_duration ?? '—' }} / {{ drawerData.stats?.standard_duration ?? '—' }} 分钟</dd></div><div><dt>准时 / 通过率</dt><dd>{{ drawerData.stats?.on_time_rate ?? '—' }}% / {{ drawerData.stats?.pass_rate ?? '—' }}%</dd></div><div><dt>公式版本</dt><dd>{{ drawerData.stats?.performance_formula_version || '暂未记录' }}</dd></div></dl></section><section class="arp-detail-section"><header><span>02</span><div><h3>工时明细</h3><small>{{ drawerData.details?.length ?? 0 }} 条</small></div></header><div class="arp-detail-list"><article v-for="item in drawerData.details ?? []" :key="item.work_log_id"><strong>{{ item.order_no }} · {{ item.node_name }}</strong><span>有效 {{ item.effective_duration ?? '—' }} / 标准 {{ item.standard_duration ?? '—' }} 分钟</span><small>{{ compactDate(item.started_at) }} – {{ compactDate(item.completed_at) }}</small></article><p v-if="!(drawerData.details?.length)" class="arp-detail-note">当前没有可查看的工时明细</p></div></section></template><template v-else-if="drawerKind === 'product' && drawerData"><section class="arp-profile-banner"><i>🦷</i><div><strong>{{ drawerData.product_name }}</strong><span>{{ productLabel(drawerData.product_type) }} · {{ statusLabel(drawerData.status) }}</span></div></section><section class="arp-detail-section"><header><span>01</span><div><h3>产品资料</h3><small>客服端维护结果</small></div></header><dl><div><dt>材料规格</dt><dd>{{ drawerData.material_spec || '暂未维护' }}</dd></div><div><dt>基础价格</dt><dd>{{ drawerData.base_price_cents <= 1 ? '价格待确认' : amount(drawerData.base_price_cents, drawerData.currency) }}</dd></div><div><dt>币种</dt><dd>{{ drawerData.currency }}</dd></div><div><dt>价格备注</dt><dd>{{ drawerData.price_note || '暂无备注' }}</dd></div><div><dt>创建时间</dt><dd>{{ compactDate(drawerData.created_at) }}</dd></div><div><dt>最近更新</dt><dd>{{ compactDate(drawerData.updated_at) }}</dd></div></dl></section><p class="arp-readonly-note">管理端只读查看产品资料，不提供产品配置或下单模板设置。</p></template><template v-else-if="drawerKind === 'dictionary'"><div class="arp-dictionary-tabs"><button :class="{ active: dictionaryType === 'REASON_CATEGORY' }" @click="dictionaryType = 'REASON_CATEGORY'">返工原因</button><button :class="{ active: dictionaryType === 'RESPONSIBILITY_TYPE' }" @click="dictionaryType = 'RESPONSIBILITY_TYPE'">责任类型</button></div><div v-if="dictionaryLoading" class="arp-state">正在加载设置…</div><div v-else-if="dictionaryError" class="arp-state arp-state-error">{{ businessFailure }}</div><div v-else class="arp-dictionary-layout"><div class="arp-dictionary-list"><button v-for="item in dictionaryItems" :key="item.item_id" :class="{ active: selectedDictionaryId === item.item_id }" @click="selectDictionary(item)"><span>{{ item.label }}</span><i class="arp-badge" :class="item.status === 'ACTIVE' ? 'ok' : 'muted'">{{ statusLabel(item.status) }}</i></button></div><div class="arp-dictionary-form"><label><span>业务名称</span><input v-model="dictionaryLabel"></label><label><span>显示顺序</span><input v-model.number="dictionarySort" type="number"></label><p>生产端和客服端只读取启用设置，管理端修改会保留真实业务记录。</p><button :disabled="dictionarySaving || !selectedDictionaryId" @click="saveDictionary">{{ dictionarySaving ? '保存中…' : '保存设置' }}</button><small v-if="dictionaryMessage">{{ dictionaryMessage }}</small></div></div></template></div><footer><span>按 Esc、点击遮罩或关闭按钮均可退出</span><button @click="closeDrawer">关闭</button></footer></aside></div>
    </template>
    <div v-if="drawerVisible && ['equipment', 'material', 'safety', 'safety-rule', 'cost', 'outsourcing'].includes(drawerKind)" class="arp-drawer-layer arp-support-drawer-layer" @click.self="closeDrawer">
      <aside class="arp-drawer" role="dialog" aria-modal="true" :aria-label="drawerTitle">
        <header><div><span>真实业务详情</span><h2>{{ drawerTitle }}</h2></div><button aria-label="关闭" @click="closeDrawer">×</button></header>
        <div class="arp-drawer-body">
          <div v-if="drawerLoading" class="arp-state"><span class="arp-spinner" />正在加载详情…</div>
          <template v-else-if="drawerKind === 'equipment'">
            <section class="arp-profile-banner"><i>⚙️</i><div><strong>{{ drawerData.equipment?.equipment_name || drawerData.equipment_name }}</strong><span>{{ drawerData.equipment?.equipment_code || drawerData.equipment_code }} · {{ statusLabel(drawerData.equipment?.status || drawerData.status) }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>设备总体情况</h3><small>真实设备登记</small></div></header><dl><div><dt>设备类型</dt><dd>{{ drawerData.equipment?.equipment_type || drawerData.equipment_type }}</dd></div><div><dt>所属部门</dt><dd>{{ drawerData.equipment?.department_name || drawerData.department_name || '暂未记录' }}</dd></div><div><dt>稼动率</dt><dd>{{ drawerData.equipment?.utilization_rate ?? drawerData.utilization_rate }}%</dd></div><div><dt>负责人</dt><dd>{{ drawerData.equipment?.owner_user_id ? `员工 ${drawerData.equipment.owner_user_id}` : '暂未指定' }}</dd></div><div><dt>上次维护</dt><dd>{{ compactDate(drawerData.equipment?.last_maintenance_at) }}</dd></div><div><dt>下次维护</dt><dd>{{ compactDate(drawerData.equipment?.next_maintenance_at) }}</dd></div></dl></section>
            <section class="arp-detail-section"><header><span>02</span><div><h3>维护与审批记录</h3><small>{{ drawerData.events?.length ?? 0 }} 条</small></div></header><div class="arp-detail-list"><article v-for="item in drawerData.events ?? []" :key="item.event_id"><strong>{{ supportTypeLabel(item.event_type) }} · {{ statusLabel(item.status) }}</strong><span>{{ item.description || '暂无说明' }}</span><small>{{ compactDate(item.created_at) }} · 停机影响 {{ item.downtime_minutes }} 分钟</small></article><p v-if="!(drawerData.events?.length)" class="arp-detail-note">当前没有维护、校准、维修或审批记录</p></div></section>
          </template>
          <template v-else-if="drawerKind === 'material'">
            <section class="arp-profile-banner"><i>⚠️</i><div><strong>{{ drawerData.material_name }}</strong><span>{{ drawerData.exception_no }} · {{ supportTypeLabel(drawerData.exception_type) }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>异常事实</h3><small>严格限定为物料异常</small></div></header><dl><div><dt>物料编码</dt><dd>{{ drawerData.material_code }}</dd></div><div><dt>关联订单</dt><dd>{{ drawerData.order_id ? `订单 ${drawerData.order_id}` : '暂未关联' }}</dd></div><div><dt>损耗数量</dt><dd>{{ drawerData.loss_quantity }}</dd></div><div><dt>处理状态</dt><dd>{{ statusLabel(drawerData.status) }}</dd></div><div><dt>责任人</dt><dd>{{ drawerData.responsibility_owner || '待确认' }}</dd></div><div><dt>异常说明</dt><dd>{{ drawerData.description || '暂无说明' }}</dd></div></dl></section><p class="arp-readonly-note">此处只追踪异常事实与处理结果，不作为库存数量或采购结论。</p>
          </template>
          <template v-else-if="drawerKind === 'safety'">
            <section class="arp-profile-banner"><i>🛡️</i><div><strong>{{ supportTypeLabel(drawerData.event_type) }}</strong><span>{{ drawerData.event_no }} · {{ statusLabel(drawerData.status) }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>检查与整改依据</h3><small>按固定周期监督</small></div></header><dl><div><dt>责任部门</dt><dd>{{ drawerData.department_name || '暂未记录' }}</dd></div><div><dt>责任人</dt><dd>{{ drawerData.responsible_owner || '待确认' }}</dd></div><div><dt>风险等级</dt><dd>{{ drawerData.risk_level }}</dd></div><div><dt>应完成时间</dt><dd>{{ compactDate(drawerData.due_at) }}</dd></div><div><dt>关联设备</dt><dd>{{ drawerData.equipment_code || '无关联设备' }}</dd></div><div><dt>检查说明</dt><dd>{{ drawerData.description || '暂无说明' }}</dd></div></dl></section>
          </template>
          <template v-else-if="drawerKind === 'safety-rule'">
            <section class="arp-profile-banner"><i>🗓️</i><div><strong>{{ drawerData.rule_name }}</strong><span>{{ drawerData.rule_code }} · {{ statusLabel(drawerData.status) }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>固定检查周期</h3><small>部门检查规则</small></div></header><dl><div><dt>检查类型</dt><dd>{{ supportTypeLabel(drawerData.check_type) }}</dd></div><div><dt>适用部门</dt><dd>{{ drawerData.department_name }}</dd></div><div><dt>执行周期</dt><dd>{{ supportTypeLabel(drawerData.cycle_type) }} · 每 {{ drawerData.cycle_interval }} 个周期</dd></div><div><dt>责任人</dt><dd>{{ drawerData.responsible_owner || '暂未指定' }}</dd></div><div><dt>下次应检</dt><dd>{{ compactDate(drawerData.next_due_at) }}</dd></div><div><dt>最近更新</dt><dd>{{ compactDate(drawerData.updated_at) }}</dd></div></dl></section>
          </template>
          <template v-else-if="drawerKind === 'cost'">
            <section class="arp-profile-banner"><i>📊</i><div><strong>{{ drawerData.cost_no }}</strong><span>{{ supportTypeLabel(drawerData.cost_type) }} · {{ statusLabel(drawerData.status) }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>订单成本追溯</h3><small>不含收入、利润和薪酬结算</small></div></header><dl><div><dt>关联订单</dt><dd>{{ drawerData.order_id ? `订单 ${drawerData.order_id}` : '暂未关联' }}</dd></div><div><dt>关联工序</dt><dd>{{ drawerData.node_instance_id ? `工序 ${drawerData.node_instance_id}` : '订单级成本' }}</dd></div><div><dt>成本金额</dt><dd>¥{{ Number(drawerData.amount ?? 0).toFixed(2) }}</dd></div><div><dt>异常状态</dt><dd>{{ statusLabel(drawerData.status) }}</dd></div><div><dt>部门 / 供应方</dt><dd>{{ drawerData.department_name || '暂未记录' }} / {{ drawerData.supplier_name || '内部成本' }}</dd></div><div><dt>追溯说明</dt><dd>{{ drawerData.description || '暂无说明' }}</dd></div></dl></section><p class="arp-readonly-note">异常提醒来自现有业务记录，页面没有自行设定成本阈值。</p>
          </template>
          <template v-else>
            <section class="arp-profile-banner"><i>☁️</i><div><strong>{{ drawerData.item_name }}</strong><span>{{ drawerData.batch_no }} · {{ drawerData.order_no }}</span></div></section>
            <section class="arp-detail-section"><header><span>01</span><div><h3>外协履约进度</h3><small>按外协件或批次追踪</small></div></header><dl><div><dt>供应商</dt><dd>{{ drawerData.supplier_name }}</dd></div><div><dt>数量</dt><dd>{{ drawerData.quantity }} 件</dd></div><div><dt>发出时间</dt><dd>{{ compactDate(drawerData.sent_at) }}</dd></div><div><dt>预计返回</dt><dd>{{ compactDate(drawerData.expected_return_at) }}</dd></div><div><dt>实际返回</dt><dd>{{ compactDate(drawerData.actual_return_at) }}</dd></div><div><dt>履约状态</dt><dd>{{ statusLabel(drawerData.status) }}{{ drawerData.is_overdue ? ' · 已超时' : '' }}</dd></div></dl></section><p v-if="drawerData.abnormal_note" class="arp-detail-note">异常说明：{{ drawerData.abnormal_note }}</p>
          </template>
        </div>
        <footer><span>详情内容可独立滚动</span><button @click="closeDrawer">关闭</button></footer>
      </aside>
    </div>
  </section>
</template>

<style scoped src="../admin-remaining-pages.css"></style>
<style scoped src="../admin-ai-polish.css"></style>
<style scoped src="../admin-support-pages.css"></style>
<style scoped>
.arp-delivery-region-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.arp-delivery-region-tabs button span {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  font-size: 10px;
}

.arp-delivery-region-tabs button.active span {
  background: #dbeafe;
  color: #1d4ed8;
}
</style>
