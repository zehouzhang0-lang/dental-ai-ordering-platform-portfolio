<script setup lang="ts">
import { computed, inject, nextTick, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { code: number; msg: string; data: T }
type Paged<T> = { items: ClinicSummary[]; total: number; page: number; size: number }

type ClinicSummary = {
  clinic_id: number
  clinic_code: string | null
  clinic_name: string
  contact_name: string | null
  contact_phone: string | null
  contact_email: string | null
  business_region: string | null
  salesperson: string | null
  customer_type: string | null
  settlement_type: string | null
  organization_nature: string | null
  business_level: string | null
  default_shipping_method: string | null
  status: string
  blacklisted: boolean
  preference_count: number
  created_at: string
  updated_at: string
}

type InvoiceProfile = {
  invoice_title: string | null
  tax_number: string | null
  bank_name: string | null
  bank_account: string | null
  registered_address: string | null
  registered_phone: string | null
}

type ShippingAddress = {
  address_id?: number | null
  address_label: string | null
  recipient_name: string
  recipient_phone: string
  province: string | null
  city: string | null
  district: string | null
  detail_address: string
  shipping_method: string | null
  default_flag: boolean
  status: string
}

type DoctorContact = {
  doctor_contact_id?: number | null
  doctor_name: string
  phone: string | null
  email: string | null
  position_title: string | null
  primary_flag: boolean
  notes: string | null
  status: string
}

type BusinessDocument = {
  document_id?: number | null
  document_category: string
  document_name: string
  document_no: string | null
  valid_from: string | null
  valid_until: string | null
  file_id: number | null
  status: string
  notes: string | null
}

type ProductPrice = {
  product_id: number
  product_type: string
  product_name: string
  material_spec: string | null
  base_price_cents: number
  custom_price_cents: number | null
  currency: string
  effective_from: string | null
  effective_until: string | null
  status: string
  price_note: string | null
}

type PrintTemplate = {
  template_id: number
  template_code: string
  template_name: string
  document_type: string
  layout_style: string
  description: string | null
  version: number
}

type TemplateBinding = {
  document_type: string
  template_id: number
  template_name: string
  layout_style: string
  version: number
}

type BlacklistStatus = {
  active: boolean
  reason: string | null
  overdue_amount_cents: number
  effective_at: string | null
  created_by_user_id: number | null
  released_at: string | null
  release_reason: string | null
}

type ChangeLog = {
  change_log_id: number
  change_type: string
  change_summary: string
  operator_user_id: number | null
  created_at: string
}

type Management = {
  clinic: ClinicSummary
  invoice_profile: InvoiceProfile
  addresses: ShippingAddress[]
  doctors: DoctorContact[]
  documents: BusinessDocument[]
  prices: ProductPrice[]
  preferences: Record<string, string>
  available_templates: PrintTemplate[]
  template_bindings: TemplateBinding[]
  blacklist: BlacklistStatus
  change_logs: ChangeLog[]
}

const props = defineProps<{ token: string; permissions: string[]; focusClinicId: number | null }>()
const emit = defineEmits<{ focusConsumed: [] }>()

const clinics = ref<ClinicSummary[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const keyword = ref('')
const statusFilter = ref<'ALL' | 'ACTIVE' | 'INACTIVE' | 'BLACKLISTED' | 'INCOMPLETE'>('ALL')
const detailVisible = ref(false)
const selectedId = ref<number | null>(null)
const detail = ref<Management | null>(null)
const bindingDraft = ref<Record<string, number | null>>({})
const createVisible = ref(false)
const newClinic = ref({
  clinic_code: '', clinic_name: '', contact_name: '', contact_phone: '', contact_email: '',
  business_region: '', salesperson: '', customer_type: 'CLINIC', settlement_type: 'PER_ORDER',
  organization_nature: 'PRIVATE', business_level: 'B', default_shipping_method: 'EXPRESS'
})
const blacklistReason = ref('')
const blacklistAmountYuan = ref<number | null>(null)
const releaseReason = ref('')
const printType = ref('ORDER_SHEET')

const documentTypes = [
  { key: 'ORDER_SHEET', label: '下单单据', icon: '📝' },
  { key: 'PRODUCTION_WORK_ORDER', label: '生产工单', icon: '⚙️' },
  { key: 'DELIVERY_NOTE', label: '送货单', icon: '📦' },
  { key: 'STATEMENT', label: '对账单', icon: '🧾' }
]

const preferenceFields = [
  { key: 'color', label: '颜色偏好' }, { key: 'contact', label: '邻接偏好' },
  { key: 'occlusion', label: '咬合偏好' },
  { key: 'margin', label: '边缘偏好' }, { key: 'shape', label: '形态偏好' },
  { key: 'material', label: '材料偏好' }, { key: 'note', label: '其他要求' }
]

const visibleClinics = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return clinics.value.filter((clinic) => {
    if (statusFilter.value === 'ACTIVE' && (clinic.status !== 'ACTIVE' || clinic.blacklisted)) return false
    if (statusFilter.value === 'INACTIVE' && clinic.status !== 'INACTIVE') return false
    if (statusFilter.value === 'BLACKLISTED' && !clinic.blacklisted) return false
    if (statusFilter.value === 'INCOMPLETE' && isComplete(clinic)) return false
    if (!search) return true
    return [clinic.clinic_code, clinic.clinic_name, clinic.contact_name, clinic.contact_phone, clinic.salesperson]
      .some((value) => String(value || '').toLowerCase().includes(search))
  })
})

const stats = computed(() => ({
  all: clinics.value.length,
  active: clinics.value.filter((item) => item.status === 'ACTIVE' && !item.blacklisted).length,
  blacklist: clinics.value.filter((item) => item.blacklisted).length,
  incomplete: clinics.value.filter((item) => !isComplete(item)).length
}))

const selectedTemplate = computed(() => detail.value?.available_templates.find((item) =>
  item.document_type === printType.value && item.template_id === bindingDraft.value[printType.value]) || null)
const canCreateCustomer = computed(() => props.permissions.includes('clinic:create'))

function isComplete(clinic: ClinicSummary) {
  return Boolean(clinic.clinic_code && clinic.contact_name && clinic.contact_phone && clinic.business_region
    && clinic.salesperson && clinic.customer_type && clinic.settlement_type && clinic.business_level)
}

async function request<T>(path: string, options: RequestInit = {}) {
  const response = await authenticatedFetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${props.token}`, ...(options.headers ?? {}) }
  })
  if (!response.ok) {
    let message = ''
    try {
      const payload = await response.json() as { message?: string; msg?: string }
      message = payload.message || payload.msg || ''
    } catch {
      // 保留 HTTP 状态码作为兜底。
    }
    throw new Error(message || `请求失败（${response.status}）`)
  }
  return (await response.json() as ApiResponse<T>).data
}

async function loadClinics() {
  loading.value = true
  error.value = ''
  try {
    const payload = await request<Paged<ClinicSummary>>('/clinics?page=1&size=100')
    clinics.value = payload.items
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '客户列表加载失败'
  } finally {
    loading.value = false
  }
}

async function openCustomer(clinicId: number) {
  selectedId.value = clinicId
  detailVisible.value = true
  detail.value = null
  error.value = ''
  try {
    const payload = await request<Management>(`/clinics/${clinicId}/management`)
    detail.value = payload
    bindingDraft.value = Object.fromEntries(documentTypes.map((type) => [type.key,
      payload.template_bindings.find((item) => item.document_type === type.key)?.template_id ??
      payload.available_templates.find((item) => item.document_type === type.key)?.template_id ?? null]))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '客户详情加载失败'
  }
}

function managementPayload() {
  if (!detail.value) return null
  const value = detail.value
  return {
    clinic_code: value.clinic.clinic_code,
    clinic_name: value.clinic.clinic_name,
    contact_name: value.clinic.contact_name,
    contact_phone: value.clinic.contact_phone,
    contact_email: value.clinic.contact_email,
    business_region: value.clinic.business_region,
    salesperson: value.clinic.salesperson,
    customer_type: value.clinic.customer_type,
    settlement_type: value.clinic.settlement_type,
    organization_nature: value.clinic.organization_nature,
    business_level: value.clinic.business_level,
    default_shipping_method: value.clinic.default_shipping_method,
    status: value.clinic.status,
    invoice_profile: value.invoice_profile,
    addresses: value.addresses.map(({ address_id: _id, ...item }) => item),
    doctors: value.doctors.map(({ doctor_contact_id: _id, ...item }) => item),
    documents: value.documents.map(({ document_id: _id, ...item }) => item),
    prices: value.prices.map((item) => ({
      product_id: item.product_id,
      custom_price_cents: item.custom_price_cents,
      currency: item.currency,
      effective_from: item.effective_from || null,
      effective_until: item.effective_until || null,
      status: item.custom_price_cents == null ? 'ACTIVE' : (item.status === 'INHERITED' ? 'ACTIVE' : item.status),
      price_note: item.price_note
    })),
    preferences: value.preferences,
    template_bindings: documentTypes.flatMap((type) => bindingDraft.value[type.key]
      ? [{ document_type: type.key, template_id: bindingDraft.value[type.key] }]
      : [])
  }
}

async function saveManagement() {
  if (!detail.value || !selectedId.value) return
  if (!detail.value.clinic.clinic_code?.trim() || !detail.value.clinic.clinic_name.trim()) {
    ElMessage.warning('客户编码和客户名称必填')
    return
  }
  saving.value = true
  try {
    detail.value = await request<Management>(`/clinics/${selectedId.value}/management`, {
      method: 'PUT', body: JSON.stringify(managementPayload())
    })
    await loadClinics()
    ElMessage.success('客户档案与关联设置已保存')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function createCustomer() {
  if (!newClinic.value.clinic_name.trim()) {
    ElMessage.warning('请填写客户名称')
    return
  }
  saving.value = true
  try {
    const created = await request<ClinicSummary>('/clinics', { method: 'POST', body: JSON.stringify(newClinic.value) })
    createVisible.value = false
    await loadClinics()
    await openCustomer(created.clinic_id)
    ElMessage.success('客户已建档，请继续完善详细资料')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '创建失败')
  } finally {
    saving.value = false
  }
}

function addAddress() {
  detail.value?.addresses.push({ address_label: '新地址', recipient_name: '', recipient_phone: '', province: '', city: '', district: '', detail_address: '', shipping_method: detail.value.clinic.default_shipping_method || 'EXPRESS', default_flag: detail.value.addresses.length === 0, status: 'ACTIVE' })
}

function addDoctor() {
  detail.value?.doctors.push({ doctor_name: '', phone: '', email: '', position_title: '医生', primary_flag: detail.value.doctors.length === 0, notes: '', status: 'ACTIVE' })
}

function addDocument(category: string) {
  detail.value?.documents.push({ document_category: category, document_name: category === 'CONTRACT' ? '合作合同' : '资质证件', document_no: '', valid_from: '', valid_until: '', file_id: null, status: 'ACTIVE', notes: '' })
}

function setDefaultAddress(index: number) {
  detail.value?.addresses.forEach((item, itemIndex) => { item.default_flag = itemIndex === index })
}

function setPrimaryDoctor(index: number) {
  detail.value?.doctors.forEach((item, itemIndex) => { item.primary_flag = itemIndex === index })
}

function setPriceYuan(price: ProductPrice, event: Event) {
  const value = Number((event.target as HTMLInputElement).value)
  price.custom_price_cents = Number.isFinite(value) && value > 0 ? Math.round(value * 100) : null
}

async function addToBlacklist() {
  if (!detail.value || !selectedId.value || !blacklistReason.value.trim()) {
    ElMessage.warning('请填写黑名单原因')
    return
  }
  saving.value = true
  try {
    detail.value = await request<Management>(`/clinics/${selectedId.value}/blacklist`, {
      method: 'POST', body: JSON.stringify({ reason: blacklistReason.value, overdue_amount_cents: Math.round((blacklistAmountYuan.value || 0) * 100) })
    })
    blacklistReason.value = ''
    blacklistAmountYuan.value = null
    await loadClinics()
    ElMessage.success('已加入黑名单，服务端下单门禁已生效')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '黑名单操作失败')
  } finally {
    saving.value = false
  }
}

async function releaseBlacklist() {
  if (!detail.value || !selectedId.value || !releaseReason.value.trim()) {
    ElMessage.warning('请填写解除原因')
    return
  }
  saving.value = true
  try {
    detail.value = await request<Management>(`/clinics/${selectedId.value}/blacklist/release`, {
      method: 'POST', body: JSON.stringify({ release_reason: releaseReason.value })
    })
    releaseReason.value = ''
    await loadClinics()
    ElMessage.success('黑名单已解除，客户可恢复下单')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '解除失败')
  } finally {
    saving.value = false
  }
}

function money(cents?: number | null) {
  if (cents == null) return '—'
  return `¥${(cents / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function compactDate(value?: string | null) {
  if (!value) return '未记录'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value))
}

function statusLabel(value?: string | null) {
  return ({
    ACTIVE: '启用', INACTIVE: '停用', INHERITED: '全局基础价', EXPIRED: '已过期',
    CLINIC: '诊所', HOSPITAL: '医院', DISTRIBUTOR: '经销商',
    PRIVATE: '民营', PUBLIC: '公立', CHAIN: '连锁', INDIVIDUAL: '个体',
    S: 'S 战略', A: 'A 核心', B: 'B 成长', C: 'C 普通',
    PER_ORDER: '按单结算', MONTHLY: '月结', PREPAID: '预付',
    EXPRESS: '快递', DEDICATED: '专车', SELF_PICKUP: '自提', AIR: '空运',
    QUALIFICATION: '资质证件', CONTRACT: '合作合同'
  } as Record<string, string>)[value || ''] || value || '未设置'
}

async function printDocument(type: string) {
  printType.value = type
  await nextTick()
  window.print()
}

let lastFocusedClinicId: number | null = null
onMounted(() => { if (props.token) void loadClinics() })
watch(() => props.focusClinicId, async (clinicId) => {
  if (clinicId === null) {
    lastFocusedClinicId = null
    return
  }
  if (lastFocusedClinicId === clinicId) return
  lastFocusedClinicId = clinicId
  await openCustomer(clinicId)
  emit('focusConsumed')
}, { immediate: true })
</script>

<template>
  <section class="cmp-page" data-testid="customer-management-v2">
    <header class="cmp-heading">
      <div><span class="cmp-eyebrow">CUSTOMER OPERATIONS</span><h1>客户管理</h1><p>统一管理客户档案、商务资料、专属价格、打印模板和下单风险。</p></div>
      <button v-if="canCreateCustomer" class="cmp-primary" type="button" data-testid="customer-create-button" @click="createVisible=true"><span>＋</span>新增客户</button>
    </header>

    <div v-if="error" class="cmp-alert is-danger"><span>!</span><div><strong>客户资料暂时不可用</strong><p>{{ error }}</p></div><button type="button" @click="loadClinics">重试</button></div>

    <section class="cmp-kpi-grid">
      <button type="button" :class="{active:statusFilter==='ALL'}" @click="statusFilter='ALL'"><i>🏥</i><div><span>全部客户</span><strong>{{ stats.all }}</strong><small>已建立客户档案</small></div></button>
      <button type="button" :class="{active:statusFilter==='ACTIVE'}" @click="statusFilter='ACTIVE'"><i>✓</i><div><span>正常合作</span><strong>{{ stats.active }}</strong><small>可正常创建与提交订单</small></div></button>
      <button type="button" class="is-risk" :class="{active:statusFilter==='BLACKLISTED'}" @click="statusFilter='BLACKLISTED'"><i>⛔</i><div><span>黑名单</span><strong>{{ stats.blacklist }}</strong><small>已启用服务端下单门禁</small></div></button>
      <button type="button" class="is-warning" :class="{active:statusFilter==='INCOMPLETE'}" @click="statusFilter='INCOMPLETE'"><i>◌</i><div><span>资料待完善</span><strong>{{ stats.incomplete }}</strong><small>核心商务字段不完整</small></div></button>
    </section>

    <section class="cmp-filter-bar">
      <label class="cmp-search"><span>⌕</span><input v-model="keyword" type="search" placeholder="搜索客户编码、客户名称、联系人、电话或业务员" data-testid="customer-code-search"></label>
      <div class="cmp-filter-chips"><button v-for="item in [{key:'ALL',label:'全部'},{key:'ACTIVE',label:'正常'},{key:'INACTIVE',label:'停用'},{key:'BLACKLISTED',label:'黑名单'},{key:'INCOMPLETE',label:'待完善'}]" :key="item.key" type="button" :class="{active:statusFilter===item.key}" @click="statusFilter=item.key as typeof statusFilter">{{ item.label }}</button></div>
      <span class="cmp-result-count">{{ visibleClinics.length }} 家客户</span>
    </section>

    <div v-if="loading" class="cmp-state"><span class="cmp-spinner" /><strong>正在整理客户档案…</strong></div>
    <section v-else-if="visibleClinics.length" class="cmp-customer-grid">
      <article v-for="clinic in visibleClinics" :key="clinic.clinic_id" :class="{'is-blacklisted':clinic.blacklisted}">
        <header><div class="cmp-avatar">{{ clinic.clinic_name.slice(0,1) }}</div><div><small>{{ clinic.clinic_code || `KH${String(clinic.clinic_id).padStart(6,'0')}` }}</small><h2>{{ clinic.clinic_name }}</h2><p>{{ clinic.business_region || '片区待设置' }} · {{ clinic.salesperson || '业务员待设置' }}</p></div><span class="cmp-status" :class="clinic.blacklisted?'danger':clinic.status==='ACTIVE'?'success':'muted'">{{ clinic.blacklisted?'黑名单':statusLabel(clinic.status) }}</span></header>
        <div class="cmp-policy" :class="clinic.blacklisted?'danger':clinic.settlement_type==='MONTHLY'?'info':'violet'"><span>{{ clinic.blacklisted?'⛔':clinic.settlement_type==='MONTHLY'?'📅':'🧾' }}</span><div><strong>{{ clinic.blacklisted?'已禁止新建和提交订单':clinic.settlement_type==='MONTHLY'?'月结客户':'按单结算' }}</strong><small>{{ clinic.blacklisted?'需解除黑名单后恢复下单':'价格和单据规则以客户档案为准' }}</small></div></div>
        <dl><div><dt>主联系人</dt><dd>{{ clinic.contact_name || '待完善' }}</dd></div><div><dt>联系电话</dt><dd>{{ clinic.contact_phone || '待完善' }}</dd></div><div><dt>客户类型</dt><dd>{{ statusLabel(clinic.customer_type) }}</dd></div><div><dt>业务等级</dt><dd>{{ statusLabel(clinic.business_level) }}</dd></div><div><dt>结算类型</dt><dd>{{ statusLabel(clinic.settlement_type) }}</dd></div><div><dt>最近维护</dt><dd>{{ compactDate(clinic.updated_at) }}</dd></div></dl>
        <footer><span :class="isComplete(clinic)?'complete':'incomplete'">{{ isComplete(clinic)?'● 核心资料完整':'○ 资料待完善' }}</span><button type="button" @click="openCustomer(clinic.clinic_id)">管理档案 <b>→</b></button></footer>
      </article>
    </section>
    <div v-else class="cmp-state"><span>🗂️</span><strong>没有符合当前条件的客户</strong><p>请调整客户编码、名称或状态筛选。</p></div>

    <el-dialog v-model="createVisible" width="720px" :show-close="false" align-center class="cmp-dialog" modal-class="cmp-dialog-mask">
      <div class="cmp-create-dialog"><header><div><small>NEW CUSTOMER</small><h2>建立客户档案</h2><p>先创建核心资料，然后在客户档案中完善价格、合同和模板。</p></div><button type="button" @click="createVisible=false">×</button></header><div class="cmp-form-grid"><label class="wide"><span>客户名称 *</span><input v-model="newClinic.clinic_name" placeholder="例：光明口腔诊所"></label><label><span>客户编码</span><input v-model="newClinic.clinic_code" placeholder="留空自动生成 KH000001"></label><label><span>业务片区</span><input v-model="newClinic.business_region" placeholder="例：华东一区"></label><label><span>主联系人</span><input v-model="newClinic.contact_name"></label><label><span>联系电话</span><input v-model="newClinic.contact_phone"></label><label><span>业务员</span><input v-model="newClinic.salesperson"></label><label><span>客户类型</span><select v-model="newClinic.customer_type"><option value="CLINIC">诊所</option><option value="HOSPITAL">医院</option><option value="DISTRIBUTOR">经销商</option></select></label><label><span>结算类型</span><select v-model="newClinic.settlement_type"><option value="PER_ORDER">按单结算</option><option value="MONTHLY">月结</option><option value="PREPAID">预付</option></select></label></div><footer><button type="button" @click="createVisible=false">取消</button><button class="primary" type="button" :disabled="saving" @click="createCustomer">{{ saving?'创建中…':'创建并完善档案' }}</button></footer></div>
    </el-dialog>

    <el-dialog v-model="detailVisible" width="960px" :show-close="false" align-center class="cmp-dialog cmp-detail-dialog" modal-class="cmp-dialog-mask">
      <div v-if="!detail" class="cmp-detail-loading"><span class="cmp-spinner" /><strong>正在读取客户完整档案…</strong></div>
      <div v-else class="cmp-detail">
        <header class="cmp-detail-head"><div class="cmp-detail-identity"><span>{{ detail.clinic.clinic_name.slice(0,1) }}</span><div><small>{{ detail.clinic.clinic_code }}</small><h2>{{ detail.clinic.clinic_name }}</h2><p>{{ detail.clinic.business_region || '片区待设置' }} · {{ detail.clinic.salesperson || '业务员待设置' }} · {{ statusLabel(detail.clinic.settlement_type) }}</p></div></div><div><span class="cmp-status" :class="detail.blacklist.active?'danger':detail.clinic.status==='ACTIVE'?'success':'muted'">{{ detail.blacklist.active?'黑名单':statusLabel(detail.clinic.status) }}</span><button class="cmp-close" type="button" @click="detailVisible=false">×</button></div></header>

        <main class="cmp-detail-body">
          <section v-if="detail.blacklist.active" class="cmp-blacklist-banner"><span>⛔</span><div><strong>当前客户已被限制下单</strong><p>{{ detail.blacklist.reason }} · 欠费 {{ money(detail.blacklist.overdue_amount_cents) }} · {{ compactDate(detail.blacklist.effective_at) }}</p></div><b>服务端门禁生效</b></section>

          <section class="cmp-section"><header><div><span>01</span><div><h3>客户主档</h3><p>客户编码用于业务查询，不再暴露数据库内部 ID。</p></div></div></header><div class="cmp-form-grid"><label><span>客户编码 *</span><input v-model="detail.clinic.clinic_code"></label><label class="wide"><span>客户名称 *</span><input v-model="detail.clinic.clinic_name"></label><label><span>业务片区</span><input v-model="detail.clinic.business_region"></label><label><span>业务员</span><input v-model="detail.clinic.salesperson"></label><label><span>客户类型</span><select v-model="detail.clinic.customer_type"><option value="CLINIC">诊所</option><option value="HOSPITAL">医院</option><option value="DISTRIBUTOR">经销商</option></select></label><label><span>单位性质</span><select v-model="detail.clinic.organization_nature"><option value="PRIVATE">民营</option><option value="PUBLIC">公立</option><option value="CHAIN">连锁</option><option value="INDIVIDUAL">个体</option></select></label><label><span>业务等级</span><select v-model="detail.clinic.business_level"><option value="S">S 战略</option><option value="A">A 核心</option><option value="B">B 成长</option><option value="C">C 普通</option></select></label><label><span>结算类型</span><select v-model="detail.clinic.settlement_type"><option value="PER_ORDER">按单结算</option><option value="MONTHLY">月结</option><option value="PREPAID">预付</option></select></label><label><span>默认发货方式</span><select v-model="detail.clinic.default_shipping_method"><option value="EXPRESS">快递</option><option value="DEDICATED">专车</option><option value="SELF_PICKUP">自提</option><option value="AIR">空运</option></select></label><label><span>档案状态</span><select v-model="detail.clinic.status"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label></div><div class="cmp-sub-grid"><label><span>主联系人</span><input v-model="detail.clinic.contact_name"></label><label><span>联系电话</span><input v-model="detail.clinic.contact_phone"></label><label><span>联系邮箱</span><input v-model="detail.clinic.contact_email" type="email"></label></div></section>

          <section class="cmp-section"><header><div><span>02</span><div><h3>开票信息</h3><p>用于对账单和开票资料核对，不代表已接入电子税票平台。</p></div></div><i>🧾</i></header><div class="cmp-form-grid"><label class="wide"><span>发票抬头</span><input v-model="detail.invoice_profile.invoice_title"></label><label><span>纳税人识别号</span><input v-model="detail.invoice_profile.tax_number"></label><label><span>开户银行</span><input v-model="detail.invoice_profile.bank_name"></label><label><span>银行账号</span><input v-model="detail.invoice_profile.bank_account"></label><label><span>注册电话</span><input v-model="detail.invoice_profile.registered_phone"></label><label class="wide"><span>注册地址</span><input v-model="detail.invoice_profile.registered_address"></label></div></section>

          <section class="cmp-section"><header><div><span>03</span><div><h3>收货地址与发货方式</h3><p>支持多个收货地址，下单和送货单优先使用默认地址。</p></div></div><button type="button" @click="addAddress">＋ 添加地址</button></header><div v-if="detail.addresses.length" class="cmp-repeat-list"><article v-for="(address,index) in detail.addresses" :key="index"><header><div><span>📍</span><strong>{{ address.address_label || `收货地址 ${index+1}` }}</strong><b v-if="address.default_flag">默认</b></div><button type="button" @click="detail.addresses.splice(index,1)">移除</button></header><div class="cmp-form-grid"><label><span>地址名称</span><input v-model="address.address_label"></label><label><span>收件人 *</span><input v-model="address.recipient_name"></label><label><span>收件电话 *</span><input v-model="address.recipient_phone"></label><label><span>发货方式</span><select v-model="address.shipping_method"><option value="EXPRESS">快递</option><option value="DEDICATED">专车</option><option value="SELF_PICKUP">自提</option><option value="AIR">空运</option></select></label><label><span>省 / 直辖市</span><input v-model="address.province"></label><label><span>市 / 区</span><input v-model="address.city"></label><label class="wide"><span>详细地址 *</span><input v-model="address.detail_address"></label></div><footer><button type="button" :class="{active:address.default_flag}" @click="setDefaultAddress(index)">{{ address.default_flag?'✓ 已设为默认':'设为默认地址' }}</button></footer></article></div><div v-else class="cmp-empty"><span>📍</span><strong>尚未维护收货地址</strong><p>添加后可用于送货单与配送核对。</p></div></section>

          <section class="cmp-section"><header><div><span>04</span><div><h3>主要医生及联系方式</h3><p>这里管理客户业务联系人，不直接修改医生端登录账号。</p></div></div><button type="button" @click="addDoctor">＋ 添加医生</button></header><div v-if="detail.doctors.length" class="cmp-repeat-list is-doctors"><article v-for="(doctor,index) in detail.doctors" :key="index"><header><div><span>👩🏻‍⚕️</span><strong>{{ doctor.doctor_name || `医生 ${index+1}` }}</strong><b v-if="doctor.primary_flag">主要医生</b></div><button type="button" @click="detail.doctors.splice(index,1)">移除</button></header><div class="cmp-form-grid"><label><span>医生姓名 *</span><input v-model="doctor.doctor_name"></label><label><span>职务 / 科室</span><input v-model="doctor.position_title"></label><label><span>联系电话</span><input v-model="doctor.phone"></label><label><span>电子邮箱</span><input v-model="doctor.email" type="email"></label><label class="wide"><span>备注</span><input v-model="doctor.notes"></label></div><footer><button type="button" :class="{active:doctor.primary_flag}" @click="setPrimaryDoctor(index)">{{ doctor.primary_flag?'✓ 主要医生':'设为主要医生' }}</button></footer></article></div><div v-else class="cmp-empty"><span>👩🏻‍⚕️</span><strong>尚未维护医生联系信息</strong><p>建议至少维护一位主要医生。</p></div></section>

          <section class="cmp-section"><header><div><span>05</span><div><h3>资质证件与合同管理</h3><p>记录证件、合同编号、有效期和私有文件关联。</p></div></div><div><button type="button" @click="addDocument('QUALIFICATION')">＋ 资质</button><button type="button" @click="addDocument('CONTRACT')">＋ 合同</button></div></header><div v-if="detail.documents.length" class="cmp-document-list"><article v-for="(document,index) in detail.documents" :key="index"><span :class="document.document_category==='CONTRACT'?'contract':'qualification'">{{ document.document_category==='CONTRACT'?'📋':'🪪' }}</span><div class="cmp-form-grid"><label><span>资料类型</span><select v-model="document.document_category"><option value="QUALIFICATION">资质证件</option><option value="CONTRACT">合作合同</option></select></label><label><span>资料名称 *</span><input v-model="document.document_name"></label><label><span>证件 / 合同编号</span><input v-model="document.document_no"></label><label><span>关联私有文件 ID</span><input v-model.number="document.file_id" type="number" min="1" placeholder="可选"></label><label><span>生效日期</span><input v-model="document.valid_from" type="date"></label><label><span>失效日期</span><input v-model="document.valid_until" type="date"></label><label class="wide"><span>备注</span><input v-model="document.notes"></label></div><button type="button" @click="detail.documents.splice(index,1)">移除</button></article></div><div v-else class="cmp-empty"><span>📋</span><strong>尚未建立资质和合同记录</strong><p>可先维护元数据，文件完成私有上传后再关联。</p></div></section>

          <section class="cmp-section"><header><div><span>06</span><div><h3>客户专属产品价格</h3><p>未设置客户价时继承全局基础价；下单后保存价格来源快照。</p></div></div><i>💲</i></header><div class="cmp-price-table"><div class="head"><span>产品</span><span>全局基础价</span><span>客户专属价</span><span>生效期</span><span>价格说明</span></div><article v-for="price in detail.prices" :key="price.product_id"><div><strong>{{ price.product_name }}</strong><small>{{ price.material_spec || price.product_type }}</small></div><b>{{ money(price.base_price_cents) }}</b><label><span>¥</span><input :value="price.custom_price_cents == null ? '' : price.custom_price_cents/100" type="number" min="0.01" step="0.01" placeholder="继承基础价" @input="setPriceYuan(price,$event)"></label><div class="dates"><input v-model="price.effective_from" type="date"><span>—</span><input v-model="price.effective_until" type="date"></div><input v-model="price.price_note" placeholder="价格说明"></article><div v-if="!detail.prices.length" class="cmp-empty"><strong>尚无启用产品</strong></div></div></section>

          <section class="cmp-section"><header><div><span>07</span><div><h3>客户单据与打印模板</h3><p>按单据类型绑定固定模板，打印时自动使用当前客户资料。</p></div></div><i>🖨️</i></header><div class="cmp-template-grid"><article v-for="type in documentTypes" :key="type.key"><header><span>{{ type.icon }}</span><div><strong>{{ type.label }}</strong><small>{{ detail.available_templates.filter(item=>item.document_type===type.key).length }} 个可用模板</small></div></header><select v-model="bindingDraft[type.key]"><option :value="null">使用系统默认模板</option><option v-for="template in detail.available_templates.filter(item=>item.document_type===type.key)" :key="template.template_id" :value="template.template_id">{{ template.template_name }} · V{{ template.version }}</option></select><p>{{ detail.available_templates.find(item=>item.template_id===bindingDraft[type.key])?.description || '未绑定时使用系统标准版' }}</p><button type="button" :disabled="!bindingDraft[type.key]" @click="printDocument(type.key)">🖨️ 预览并打印</button></article></div></section>

          <section class="cmp-section"><header><div><span>08</span><div><h3>特殊生产要求</h3><p>将制作偏好按邻接、咬合等大类维护；客服初审自动带入，订单确认后保存当时要求的快照。</p></div></div><i>🎨</i></header><div class="cmp-preference-grid"><label v-for="field in preferenceFields" :key="field.key" :class="{wide:field.key==='note'}"><span>{{ field.label }}</span><textarea v-model="detail.preferences[field.key]" :rows="field.key==='note'?3:2" :placeholder="`填写${field.label}，例如偏紧、偏松或具体毫米数`" /></label></div></section>

          <section class="cmp-section cmp-risk-section"><header><div><span>09</span><div><h3>黑名单与下单风险</h3><p>黑名单按客户生效，该客户下所有医生都无法新建或提交订单。</p></div></div><i>⛔</i></header><div v-if="detail.blacklist.active" class="cmp-risk-panel active"><div><span>⛔</span><div><strong>已加入黑名单</strong><p>{{ detail.blacklist.reason }}</p><small>欠费 {{ money(detail.blacklist.overdue_amount_cents) }} · 生效于 {{ compactDate(detail.blacklist.effective_at) }}</small></div></div><label><span>解除原因 *</span><input v-model="releaseReason" placeholder="例：欠款已结清，经财务确认"></label><button type="button" :disabled="saving||!releaseReason.trim()" @click="releaseBlacklist">解除黑名单</button></div><div v-else class="cmp-risk-panel"><div><span>🛡️</span><div><strong>当前客户可正常下单</strong><p>加入黑名单后，前端与服务端将同时拦截。</p></div></div><div class="cmp-sub-grid"><label class="wide"><span>黑名单原因 *</span><input v-model="blacklistReason" placeholder="请填写欠费、履约或风险事实"></label><label><span>欠费金额（元）</span><input v-model.number="blacklistAmountYuan" type="number" min="0" step="0.01"></label></div><button class="danger" type="button" :disabled="saving||!blacklistReason.trim()" @click="addToBlacklist">⛔ 加入黑名单</button></div></section>

          <section class="cmp-section"><header><div><span>10</span><div><h3>操作记录</h3><p>客户档案、黑名单及解除操作持续留痕。</p></div></div><i>🕘</i></header><div v-if="detail.change_logs.length" class="cmp-audit-list"><article v-for="log in detail.change_logs" :key="log.change_log_id"><span /><div><strong>{{ log.change_summary }}</strong><small>{{ compactDate(log.created_at) }} · 操作人 #{{ log.operator_user_id || '系统' }}</small></div><b>{{ log.change_type }}</b></article></div><div v-else class="cmp-empty"><strong>尚无操作记录</strong></div></section>
        </main>

        <footer class="cmp-detail-footer"><div><strong>保存会更新客户主档与所有当前编辑项</strong><span>黑名单操作独立留痕，不会因普通保存被覆盖。</span></div><button type="button" @click="detailVisible=false">关闭</button><button class="primary" type="button" :disabled="saving" data-testid="customer-save-button" @click="saveManagement">{{ saving?'保存中…':'保存客户档案' }}</button></footer>
      </div>
    </el-dialog>

    <section v-if="detail" class="cmp-print-sheet" :class="selectedTemplate?.layout_style==='COMPACT'?'compact':'standard'">
      <header><div><span>AI 智能下单平台</span><h1>{{ documentTypes.find(item=>item.key===printType)?.label }}</h1></div><strong>{{ selectedTemplate?.template_name || '系统默认模板' }}</strong></header><div class="identity"><div><small>客户编码</small><strong>{{ detail.clinic.clinic_code }}</strong></div><div><small>客户名称</small><strong>{{ detail.clinic.clinic_name }}</strong></div><div><small>主要医生</small><strong>{{ detail.doctors.find(item=>item.primary_flag)?.doctor_name || detail.clinic.contact_name || '—' }}</strong></div></div><section><h2>客户与商务资料</h2><dl><div><dt>业务片区</dt><dd>{{ detail.clinic.business_region || '—' }}</dd></div><div><dt>业务员</dt><dd>{{ detail.clinic.salesperson || '—' }}</dd></div><div><dt>结算类型</dt><dd>{{ statusLabel(detail.clinic.settlement_type) }}</dd></div><div><dt>业务等级</dt><dd>{{ statusLabel(detail.clinic.business_level) }}</dd></div></dl></section><section><h2>默认收货信息</h2><p v-if="detail.addresses.find(item=>item.default_flag)">{{ detail.addresses.find(item=>item.default_flag)?.recipient_name }} · {{ detail.addresses.find(item=>item.default_flag)?.recipient_phone }}</p><p>{{ detail.addresses.find(item=>item.default_flag)?.detail_address || '尚未设置默认收货地址' }}</p></section><section><h2>产品价格清单</h2><table><thead><tr><th>产品</th><th>材料规格</th><th>适用价格</th><th>价格来源</th></tr></thead><tbody><tr v-for="price in detail.prices" :key="price.product_id"><td>{{ price.product_name }}</td><td>{{ price.material_spec || '—' }}</td><td>{{ money(price.custom_price_cents ?? price.base_price_cents) }}</td><td>{{ price.custom_price_cents == null?'全局基础价':'客户专属价' }}</td></tr></tbody></table></section><footer><span>模板 {{ selectedTemplate?.template_code }} · V{{ selectedTemplate?.version || 1 }}</span><span>打印日期 {{ new Date().toLocaleDateString('zh-CN') }}</span></footer>
    </section>
  </section>
</template>

<style src="./customer-management-page.css"></style>
