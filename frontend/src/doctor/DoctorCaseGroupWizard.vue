<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, inject, nextTick, onMounted, reactive, ref } from 'vue'
import type { DoctorFile, DoctorGateway, DoctorProductRecommendation, PatientSummary } from './types/contracts'
import DoctorOrthodonticPrescription from './DoctorOrthodonticPrescription.vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'
import {
  CATEGORY_NAMES,
  CLEAR_ALIGNER_ARCH_OPTIONS,
  CLEAR_ALIGNER_PRODUCT_CODE,
  CLEAR_ALIGNER_TREATMENT_OPTIONS,
  CUSTOMER_ORDER_STEPS,
  DENTURE_BASE_SHADES,
  FIXED_PRECISION_ATTACHMENTS,
  ORTHODONTIC_ACCESSORIES,
  ORTHODONTIC_PRODUCT_GROUPS,
  PRODUCT_MATERIAL_OPTIONS,
  UPLOAD_RULES,
  VITA_16_SHADES,
  VITA_3D_SHADES,
  type SourceUploadRule
} from './customerOrderSourceSpec'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { data: T; message?: string; msg?: string }
type CatalogProduct = {
  product_id: number
  product_code: string
  display_name: string
  workflow_product_type: string
  tooth_rule_code: string | null
  pricing_status: 'PENDING_QUOTE' | 'PRICED'
  base_price_cents: number | null
  currency: string
  category_code: string
  category_name: string
}
type CatalogVariant = {
  variant_id: number
  product_id: number
  variant_code: string
  display_name: string
}
type CatalogCategory = {
  category_id: number
  category_code: string
  display_name: string
  sort_order: number
}
type CatalogMaterial = {
  product_id: number
  variant_id: number | null
  material_id: number
  material_code: string
  display_name: string
  brand_name: string | null
  specification: string | null
  selection_group_code: string
  required_flag: boolean | number
  selection_mode: 'SINGLE' | 'MULTIPLE'
  default_flag: boolean | number
  min_quantity: number | null
  max_quantity: number | null
  price_increment_cents: number | null
}
type CatalogAccessory = {
  product_id: number
  variant_id: number | null
  accessory_id: number
  accessory_code: string
  display_name: string
  selection_group_code: string
  required_flag: boolean | number
  default_flag: boolean | number
  min_quantity: number | null
  max_quantity: number | null
  price_increment_cents: number | null
}
type CatalogRule = {
  rule_id: number
  product_id: number | null
  variant_id: number | null
  rule_type: 'FORM_SCHEMA' | 'TOOTH' | 'UPLOAD' | 'PRICE' | 'LEAD_TIME' | 'WORKFLOW'
  rule_code: string
  rule_schema_json: unknown
}
type CatalogConfig = {
  publication_status: string
  config_version_id?: number
  categories?: CatalogCategory[]
  products: CatalogProduct[]
  variants?: CatalogVariant[]
  materials?: CatalogMaterial[]
  accessories?: CatalogAccessory[]
  rules?: CatalogRule[]
}
type QuantitySelection = { item_id: number; quantity: number }
type CaseGroupItem = {
  order_id: number
  order_no: string
  line_no: number
  relationship_type: string
  item_client_key: string
  product_id: number
  product_code: string
  product_name: string
  variant_id: number | null
  variant_code: string | null
  variant_name: string | null
  product_type: string
  pricing_status: 'PENDING_QUOTE' | 'PRICED'
  quoted_price_cents: number | null
  quoted_price_currency: string | null
  configuration_status: 'DRAFT' | 'FROZEN'
  form_values: Record<string, unknown>
  material_selections: QuantitySelection[]
  accessory_selections: QuantitySelection[]
  file_ids: number[]
  external_status: string
}
type CaseGroup = {
  group_id: number
  group_no: string
  patient_id: number
  lifecycle_status: 'DRAFT' | 'SUBMITTED'
  external_status: string
  draft_version: number
  shared_file_ids: number[]
  items: CaseGroupItem[]
}
type FormField = {
  key: string
  label: string
  type: string
  required?: boolean
  options?: Array<string | { value: string; label: string }>
  visible_when?: { field?: string; equals?: unknown }
  min?: number
  max?: number
  minimum?: number
  maximum?: number
  min_items?: number
  max_items?: number
}

const props = defineProps<{
  token: string
  patients: PatientSummary[]
  gateway: DoctorGateway
  initialPatientId?: string
  initialGroupId?: number
  clinicName?: string
  doctorName?: string
  clinicContact?: string
}>()
const emit = defineEmits<{
  close: []
  submitted: [group: CaseGroup]
}>()

const steps = CUSTOMER_ORDER_STEPS
const upperTeeth = ['18', '17', '16', '15', '14', '13', '12', '11', '21', '22', '23', '24', '25', '26', '27', '28']
const lowerTeeth = ['48', '47', '46', '45', '44', '43', '42', '41', '31', '32', '33', '34', '35', '36', '37', '38']
const toothTypes = [
  'molar3', 'molar2', 'molar1', 'premolar2', 'premolar1', 'canine', 'incisor2', 'incisor1',
  'incisor1', 'incisor2', 'canine', 'premolar1', 'premolar2', 'molar1', 'molar2', 'molar3'
] as const
const toothDimensions = {
  molar3: { crownWidth: 30, crownHeight: 20, rootWidth: 22, rootHeight: 26, roots: 3 },
  molar2: { crownWidth: 32, crownHeight: 20, rootWidth: 24, rootHeight: 26, roots: 3 },
  molar1: { crownWidth: 34, crownHeight: 21, rootWidth: 26, rootHeight: 28, roots: 3 },
  premolar2: { crownWidth: 24, crownHeight: 18, rootWidth: 13, rootHeight: 26, roots: 2 },
  premolar1: { crownWidth: 25, crownHeight: 18, rootWidth: 14, rootHeight: 26, roots: 2 },
  canine: { crownWidth: 20, crownHeight: 20, rootWidth: 9, rootHeight: 36, roots: 1 },
  incisor2: { crownWidth: 20, crownHeight: 18, rootWidth: 8, rootHeight: 26, roots: 1 },
  incisor1: { crownWidth: 24, crownHeight: 18, rootWidth: 9, rootHeight: 28, roots: 1 }
} as const
type ToothType = keyof typeof toothDimensions
type ToothSvgModel = {
  number: string
  crownPath: string
  rootPaths: string[]
  junction: { x1: number; x2: number; y: number }
  numberPosition: { x: number; y: number }
  hitArea: { x: number; y: number; width: number; height: number }
}

function toothCenters() {
  const widths = toothTypes.map((type) => toothDimensions[type].crownWidth)
  const totalWidth = widths.reduce((sum, width) => sum + width, 0) + 30
  const scale = 640 / totalWidth
  const centers: number[] = []
  let x = 30
  widths.forEach((width) => {
    const scaledWidth = width * scale
    centers.push(x + scaledWidth / 2)
    x += scaledWidth + 2 * scale
  })
  return centers
}

const dentalCenters = toothCenters()

function upperCrownPath(cx: number, top: number, width: number, height: number, type: ToothType) {
  const bottom = top + height
  const half = width / 2
  const quarter = width * .27
  if (type === 'canine') return `M${cx - half} ${bottom} L${cx - half} ${top + 6} Q${cx - half * .5} ${top} ${cx} ${top} Q${cx + half * .5} ${top} ${cx + half} ${top + 6} L${cx + half} ${bottom} Z`
  if (type.startsWith('incisor')) return `M${cx - half} ${bottom} L${cx - half} ${top + 4} Q${cx} ${top - 1} ${cx + half} ${top + 4} L${cx + half} ${bottom} Z`
  if (type.startsWith('premolar')) return `M${cx - half} ${bottom} L${cx - half} ${top + 5} Q${cx - quarter} ${top - 1} ${cx} ${top + 4} Q${cx + quarter} ${top - 1} ${cx + half} ${top + 5} L${cx + half} ${bottom} Z`
  return `M${cx - half} ${bottom} L${cx - half} ${top + 5} Q${cx - width * .37} ${top - 1} ${cx - quarter} ${top + 3} Q${cx} ${top - 2} ${cx + quarter} ${top + 3} Q${cx + width * .37} ${top - 1} ${cx + half} ${top + 5} L${cx + half} ${bottom} Z`
}

function lowerCrownPath(cx: number, top: number, width: number, height: number, type: ToothType) {
  const bottom = top + height
  const half = width / 2
  const quarter = width * .27
  if (type === 'canine') return `M${cx - half} ${top} L${cx + half} ${top} L${cx + half} ${bottom - 6} Q${cx + half * .5} ${bottom} ${cx} ${bottom} Q${cx - half * .5} ${bottom} ${cx - half} ${bottom - 6} Z`
  if (type.startsWith('incisor')) return `M${cx - half} ${top} L${cx + half} ${top} L${cx + half} ${bottom - 4} Q${cx} ${bottom + 1} ${cx - half} ${bottom - 4} Z`
  if (type.startsWith('premolar')) return `M${cx - half} ${top} L${cx + half} ${top} L${cx + half} ${bottom - 5} Q${cx + quarter} ${bottom + 1} ${cx} ${bottom - 4} Q${cx - quarter} ${bottom + 1} ${cx - half} ${bottom - 5} Z`
  return `M${cx - half} ${top} L${cx + half} ${top} L${cx + half} ${bottom - 5} Q${cx + width * .37} ${bottom + 1} ${cx + quarter} ${bottom - 3} Q${cx} ${bottom + 2} ${cx - quarter} ${bottom - 3} Q${cx - width * .37} ${bottom + 1} ${cx - half} ${bottom - 5} Z`
}

function toothRootPaths(cx: number, base: number, width: number, height: number, roots: number, upper: boolean) {
  const half = width / 2
  const tip = upper ? base - height : base + height
  if (roots === 1) {
    return [upper
      ? `M${cx - half} ${base} Q${cx - half * .65} ${tip + height * .32} ${cx} ${tip} Q${cx + half * .65} ${tip + height * .32} ${cx + half} ${base} Z`
      : `M${cx - half} ${base} Q${cx - half * .65} ${tip - height * .32} ${cx} ${tip} Q${cx + half * .65} ${tip - height * .32} ${cx + half} ${base} Z`]
  }
  if (roots === 2) {
    const rootWidth = width * .44
    const rootHalf = rootWidth / 2
    const gap = width * .12
    return [-1, 1].map((side) => {
      const rootCx = cx + side * (rootHalf + gap / 2)
      return upper
        ? `M${rootCx - rootHalf} ${base} Q${rootCx - rootHalf * .6} ${tip + height * .3} ${rootCx} ${tip} Q${rootCx + rootHalf * .6} ${tip + height * .3} ${rootCx + rootHalf} ${base} Z`
        : `M${rootCx - rootHalf} ${base} Q${rootCx - rootHalf * .6} ${tip - height * .3} ${rootCx} ${tip} Q${rootCx + rootHalf * .6} ${tip - height * .3} ${rootCx + rootHalf} ${base} Z`
    })
  }
  const rootWidth = width * .37
  const rootHalf = rootWidth / 2
  return [[0, 1], [-width * .4, .83], [width * .4, .83]].map(([offset, ratio]) => {
    const rootCx = cx + offset
    const rootTip = upper ? base - height * ratio : base + height * ratio
    return upper
      ? `M${rootCx - rootHalf} ${base} Q${rootCx - rootHalf * .6} ${rootTip + height * ratio * .32} ${rootCx} ${rootTip} Q${rootCx + rootHalf * .6} ${rootTip + height * ratio * .32} ${rootCx + rootHalf} ${base} Z`
      : `M${rootCx - rootHalf} ${base} Q${rootCx - rootHalf * .6} ${rootTip - height * ratio * .32} ${rootCx} ${rootTip} Q${rootCx + rootHalf * .6} ${rootTip - height * ratio * .32} ${rootCx + rootHalf} ${base} Z`
  })
}

function createToothSvgModel(number: string, index: number, upper: boolean): ToothSvgModel {
  const type = toothTypes[index]
  const dimensions = toothDimensions[type]
  const cx = dentalCenters[index]
  const occlusionY = upper ? 158 : 238
  const crownTop = upper ? occlusionY - dimensions.crownHeight : occlusionY
  const crownBottom = crownTop + dimensions.crownHeight
  const rootBase = upper ? crownTop : crownBottom
  return {
    number,
    crownPath: upper
      ? upperCrownPath(cx, crownTop, dimensions.crownWidth, dimensions.crownHeight, type)
      : lowerCrownPath(cx, crownTop, dimensions.crownWidth, dimensions.crownHeight, type),
    rootPaths: toothRootPaths(cx, rootBase, dimensions.rootWidth, dimensions.rootHeight, dimensions.roots, upper),
    junction: { x1: cx - dimensions.crownWidth / 2, x2: cx + dimensions.crownWidth / 2, y: rootBase },
    numberPosition: { x: cx, y: upper ? occlusionY + 13 : occlusionY - 5 },
    hitArea: {
      x: cx - dimensions.crownWidth / 2 - 2,
      y: upper ? occlusionY - dimensions.crownHeight - dimensions.rootHeight - 2 : occlusionY - 2,
      width: dimensions.crownWidth + 4,
      height: dimensions.crownHeight + dimensions.rootHeight + 4
    }
  }
}

const upperToothSvg = upperTeeth.map((number, index) => createToothSvgModel(number, index, true))
const lowerToothSvg = lowerTeeth.map((number, index) => createToothSvgModel(number, index, false))
const step = ref(1)
const loading = ref(true)
const busy = ref(false)
const catalog = ref<CatalogConfig | null>(null)
const group = ref<CaseGroup | null>(null)
const patientOptions = ref<PatientSummary[]>([...props.patients])
const patientId = ref(props.initialPatientId ?? '')
const patientKeyword = ref('')
const patientSearchFocused = ref(false)
const productKeyword = ref('')
const recommendCaseNote = ref('')
const recommendLoading = ref(false)
const recommendError = ref('')
const recommendNote = ref('')
const productRecommendations = ref<DoctorProductRecommendation[]>([])
const selectedCategoryCode = ref('')
const pendingProductIds = ref<number[]>([])
const selectedOrderId = ref<number | null>(null)
const notice = ref('')
const itemFiles = reactive<Record<number, DoctorFile[]>>({})
const orthodonticPrescriptionReady = reactive<Record<number, boolean>>({})
const objectFieldDrafts = reactive<Record<string, string>>({})
const objectFieldErrors = reactive<Record<string, string>>({})
const sharedFiles = ref<DoctorFile[]>([])
const fileUploading = ref(false)
const toothDrag = ref<{
  orderId: number
  arch: 'UPPER' | 'LOWER'
  start: string
  end: string
  moved: boolean
} | null>(null)
const suppressToothClick = ref(false)
const finalConfirmations = reactive({
  quote: false,
  requirements: false,
  cycle: false
})
const caseSettings = reactive({
  priority: 'NORMAL',
  required_delivery_date: '',
  appointment_date: '',
  shipping_method: 'COURIER',
  order_type: 'ONLINE',
  inbound_tracking_no: '',
  global_notes: ''
})
const activeProductGroup = ref('')
const newPatientOpen = ref(false)
const newPatientSaving = ref(false)
const newPatient = reactive({
  name: '',
  date_of_birth: '',
  gender: '',
  phone: '',
  email: '',
  medical_notes: ''
})

const selectedPatient = computed(() => patientOptions.value.find((item) => item.patient_id === patientId.value) ?? null)
const patientRows = computed(() => {
  const keyword = patientKeyword.value.trim().toLowerCase()
  return patientOptions.value.filter((item) => !keyword || `${item.patient_name} ${item.patient_code}`.toLowerCase().includes(keyword))
})
const catalogProducts = computed(() => {
  const keyword = productKeyword.value.trim().toLowerCase()
  return (catalog.value?.products ?? [])
    .filter((item) =>
      !keyword || `${item.product_code} ${item.display_name} ${item.category_name}`.toLowerCase().includes(keyword)
    )
})
const catalogCategories = computed(() => {
  const configured = new Map(
    (catalog.value?.categories ?? []).map((category) => [category.category_code, category.display_name])
  )
  return Object.entries(CATEGORY_NAMES).map(([code, sourceName]) => ({
    code,
    name: configured.get(code) ?? sourceName
  }))
})
const selectedCategoryProducts = computed(() =>
  catalogProducts.value.filter((product) => product.category_code === selectedCategoryCode.value)
)
const pendingProducts = computed(() => pendingProductIds.value
  .map((productId) => catalog.value?.products.find((product) => product.product_id === productId))
  .filter((product): product is CatalogProduct => Boolean(product))
)
const selectedProductCount = computed(() => (group.value?.items.length ?? 0) + pendingProducts.value.length)
const selectedProductGroups = computed(() => {
  if (selectedCategoryCode.value !== 'CONVENTIONAL_ORTHODONTICS') {
    return [{ label: '', products: selectedCategoryProducts.value }]
  }
  return ORTHODONTIC_PRODUCT_GROUPS
    .map((group) => ({
      label: group.label,
      products: selectedCategoryProducts.value.filter((product) =>
        (group.codes as readonly string[]).includes(product.product_code)
      )
    }))
    .filter((group) => group.products.length)
})
const activeItem = computed(() =>
  group.value?.items.find((item) => item.order_id === selectedOrderId.value)
  ?? group.value?.items[0]
  ?? null
)
const activeProduct = computed(() =>
  catalog.value?.products.find((product) => product.product_id === activeItem.value?.product_id)
  ?? null
)
const activeVariants = computed(() =>
  (catalog.value?.variants ?? []).filter((variant) => variant.product_id === activeItem.value?.product_id)
)
const activeVariant = computed(() =>
  activeVariants.value.find((variant) => variant.variant_id === activeItem.value?.variant_id) ?? null
)
const activeMaterials = computed(() =>
  (catalog.value?.materials ?? []).filter((binding) =>
    binding.product_id === activeItem.value?.product_id
    && (binding.variant_id == null || binding.variant_id === activeItem.value?.variant_id)
  )
)
const activeMultipleMaterials = computed(() =>
  activeMaterials.value.filter((binding) => binding.selection_mode === 'MULTIPLE')
)
const activeAccessories = computed(() =>
  (catalog.value?.accessories ?? []).filter((binding) =>
    binding.product_id === activeItem.value?.product_id
    && (binding.variant_id == null || binding.variant_id === activeItem.value?.variant_id)
  )
)
function catalogFieldsForItem(item: CaseGroupItem): FormField[] {
  return (catalog.value?.rules ?? [])
    .filter((rule) =>
      rule.rule_type === 'FORM_SCHEMA'
      && (rule.product_id == null || rule.product_id === item.product_id)
      && (rule.variant_id == null || rule.variant_id === item.variant_id)
    )
    .flatMap((rule) => {
      const schema = parseRuleSchema(rule.rule_schema_json)
      return Array.isArray(schema.fields) ? schema.fields as FormField[] : []
    })
}
const stepThreeCoreFields = new Set([
  'material_option',
  'finish_margin_type',
  'shade_system',
  'shade_value',
  'cervical_shade',
  'body_shade',
  'incisal_shade',
  'polish_grade',
  'material_shade_notes',
  'implant_system',
  'implant_diameter_length',
  'connection_type',
  'retention_type',
  'abutment_type',
  'screw_access_position',
  'clasp_design',
  'denture_teeth_brand',
  'denture_base_shade',
  'orthodontic_accessories',
  'orthodontic_accessory_notes',
  'design_delivery_format',
  'design_delivery_turnaround'
])
const activeFields = computed<FormField[]>(() =>
  activeItem.value
    ? catalogFieldsForItem(activeItem.value).filter((field) =>
        field.key !== 'tooth_positions' && !stepThreeCoreFields.has(field.key)
      )
    : []
)
const incompleteItems = computed(() => (group.value?.items ?? []).filter((item) => itemErrors(item).length > 0))
const finalConfirmationComplete = computed(() =>
  finalConfirmations.quote && finalConfirmations.requirements && finalConfirmations.cycle
)

async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await authenticatedFetch(path, {
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
      const body = await response.json() as {
        message?: string
        msg?: string
        detail?: string
        error?: string
      }
      detail = body.message || body.msg || body.detail || body.error || ''
    } catch {
      detail = await response.text().catch(() => '')
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  const payload = await response.json() as ApiResponse<T>
  return payload.data
}

function parseRuleSchema(value: unknown): Record<string, any> {
  if (value && typeof value === 'object') return value as Record<string, any>
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return {}
}

function closePatientResults() {
  window.setTimeout(() => {
    patientSearchFocused.value = false
  }, 120)
}

function caseSettingsSnapshot() {
  return {
    case_priority: caseSettings.priority,
    required_delivery_date: caseSettings.required_delivery_date,
    next_patient_appointment_date: caseSettings.appointment_date,
    shipping_method: caseSettings.shipping_method,
    order_type: caseSettings.order_type,
    inbound_tracking_no: caseSettings.inbound_tracking_no,
    global_notes: caseSettings.global_notes
  }
}

function hydrateCaseSettings(item: CaseGroupItem | undefined) {
  if (!item) return
  caseSettings.priority = String(item.form_values.case_priority ?? 'NORMAL')
  caseSettings.required_delivery_date = String(item.form_values.required_delivery_date ?? '')
  caseSettings.appointment_date = String(item.form_values.next_patient_appointment_date ?? '')
  caseSettings.shipping_method = String(item.form_values.shipping_method ?? 'COURIER')
  caseSettings.order_type = String(item.form_values.order_type ?? 'ONLINE')
  caseSettings.inbound_tracking_no = String(item.form_values.inbound_tracking_no ?? '')
  caseSettings.global_notes = String(item.form_values.global_notes ?? '')
}

function productCategory(item: CaseGroupItem) {
  return catalog.value?.products.find((product) => product.product_id === item.product_id)?.category_code ?? ''
}

function productMaterialOptions(item: CaseGroupItem) {
  return PRODUCT_MATERIAL_OPTIONS[item.product_code] ?? []
}

function materialBindingsForItem(item: CaseGroupItem) {
  return (catalog.value?.materials ?? []).filter((binding) =>
    binding.product_id === item.product_id
    && (binding.variant_id == null || binding.variant_id === item.variant_id)
  )
}

function materialBindingLabel(binding: CatalogMaterial) {
  return Array.from(new Set([
    binding.display_name,
    binding.brand_name,
    binding.specification
  ].filter(Boolean))).join(' · ')
}

function normalizedMaterialLabel(value: string) {
  return value.toLocaleLowerCase().replace(/[\s·（）()/_-]+/g, '')
}

function primaryMaterialOptions(item: CaseGroupItem) {
  const sourceOptions = productMaterialOptions(item)
  const publishedOptions = materialBindingsForItem(item)
    .filter((binding) => binding.selection_mode === 'SINGLE')
    .map(materialBindingLabel)
  const options = publishedOptions.length ? publishedOptions : sourceOptions
  const seen = new Set<string>()
  return options.filter((option) => {
    const key = normalizedMaterialLabel(option)
    if (!key || seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function primaryMaterialValue(item: CaseGroupItem) {
  const stored = String(item.form_values.material_option ?? '').trim()
  if (stored) return stored
  const selectedBinding = materialBindingsForItem(item)
    .filter((binding) => binding.selection_mode === 'SINGLE')
    .find((binding) => selected(item.material_selections, binding.material_id))
  return selectedBinding ? materialBindingLabel(selectedBinding) : ''
}

function choosePrimaryMaterial(item: CaseGroupItem, value: string) {
  item.form_values.material_option = value
  const bindings = materialBindingsForItem(item).filter((binding) => binding.selection_mode === 'SINGLE')
  const bindingIds = new Set(bindings.map((binding) => binding.material_id))
  item.material_selections = item.material_selections.filter((entry) => !bindingIds.has(entry.item_id))
  if (!value) return
  const normalized = normalizedMaterialLabel(value)
  const matched = bindings.find((binding) => {
    const candidate = normalizedMaterialLabel(materialBindingLabel(binding))
    return candidate === normalized || candidate.includes(normalized) || normalized.includes(candidate)
  })
  if (matched) {
    item.material_selections.push({
      item_id: matched.material_id,
      quantity: Math.max(1, matched.min_quantity ?? 1)
    })
  }
}

function uploadRules(item: CaseGroupItem): SourceUploadRule[] {
  return UPLOAD_RULES[productCategory(item)] ?? []
}

function clearAlignerTypes(item: CaseGroupItem) {
  return [{ code: item.product_code || CLEAR_ALIGNER_PRODUCT_CODE, name: item.product_name }]
}

function relatedOrders(item: CaseGroupItem) {
  return (group.value?.items ?? [])
    .filter((candidate) => candidate.order_id !== item.order_id)
    .map((candidate) => ({
      order_id: candidate.order_id,
      order_no: candidate.order_no,
      product_name: candidate.product_name
    }))
}

function prescriptionInitialRecords(item: CaseGroupItem) {
  return Object.fromEntries([
    'facial_photos',
    'intraoral_photos',
    'panoramic',
    'cephalometric',
    'upper_model',
    'lower_model',
    'bite_model'
  ].map((slot) => [slot, uploadedSlotIds(item, slot).join(',')]))
}

function updateClearAlignerSelection(item: CaseGroupItem, selection: { treatment_arch: string; treatment_mode: string }) {
  item.form_values.treatment_arch = selection.treatment_arch
  item.form_values.treatment_mode = selection.treatment_mode
}

function uploadedSlotIds(item: CaseGroupItem, slotCode: string) {
  const value = item.form_values.upload_slot_files
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  const ids = (value as Record<string, unknown>)[slotCode]
  return Array.isArray(ids) ? ids.map(Number).filter(Number.isFinite) : []
}

function sourceArray(item: CaseGroupItem, key: string) {
  const value = item.form_values[key]
  return Array.isArray(value) ? value.map(String) : []
}

function toggleSourceArray(item: CaseGroupItem, key: string, value: string, checked: boolean) {
  const next = new Set(sourceArray(item, key))
  if (checked) next.add(value)
  else next.delete(value)
  item.form_values[key] = Array.from(next)
}

async function createPatientFromWizard() {
  if (!newPatient.name.trim() || newPatientSaving.value) {
    ElMessage.warning('请填写患者姓名')
    return
  }
  newPatientSaving.value = true
  try {
    const created = await props.gateway.createPatient({
      patientName: newPatient.name.trim(),
      patientAge: null,
      patientGender: newPatient.gender || null,
      dateOfBirth: newPatient.date_of_birth || null,
      phone: newPatient.phone.trim(),
      email: newPatient.email.trim(),
      medicalNotes: newPatient.medical_notes.trim(),
      treatmentStatus: 'IN_TREATMENT',
      treatmentStartedAt: new Date().toISOString().slice(0, 10),
      treatmentEndedAt: null,
      oralDescription: '',
      tags: []
    })
    patientOptions.value.unshift(created)
    patientId.value = created.patient_id
    newPatientOpen.value = false
    Object.assign(newPatient, {
      name: '',
      date_of_birth: '',
      gender: '',
      phone: '',
      email: '',
      medical_notes: ''
    })
    ElMessage.success('患者已新增并选中')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '新增患者失败')
  } finally {
    newPatientSaving.value = false
  }
}

async function loadCatalog() {
  catalog.value = await api<CatalogConfig>('/catalog/configuration/active')
  if (selectedCategoryCode.value && !catalogCategories.value.some((category) => category.code === selectedCategoryCode.value)) {
    selectedCategoryCode.value = ''
  }
}

async function restoreDraft() {
  const requestedGroupId = props.initialGroupId
  if (requestedGroupId == null || !Number.isSafeInteger(requestedGroupId) || requestedGroupId <= 0) return
  try {
    const restored = await api<CaseGroup>(`/order-case-groups/${requestedGroupId}`)
    if (
      restored.lifecycle_status === 'DRAFT'
      && (!props.initialPatientId || String(restored.patient_id) === props.initialPatientId)
    ) {
      group.value = restored
      patientId.value = String(restored.patient_id)
      selectedOrderId.value = restored.items[0]?.order_id ?? null
      selectedCategoryCode.value = catalog.value?.products.find((product) => product.product_id === restored.items[0]?.product_id)?.category_code ?? ''
      hydrateCaseSettings(restored.items[0])
      step.value = 1
      notice.value = `已恢复草稿 ${restored.group_no}`
    }
  } catch {
    notice.value = '草稿恢复失败，请返回草稿列表后重试'
  }
}

async function ensureGroup() {
  if (group.value) return group.value
  if (!patientId.value) throw new Error('请先选择患者')
  const created = await api<CaseGroup>('/order-case-groups', {
    method: 'POST',
    body: JSON.stringify({
      patient_id: Number(patientId.value),
      idempotency_key: crypto.randomUUID()
    })
  })
  group.value = created
  return created
}

async function persistPendingProductsUnlocked() {
  try {
    for (const productId of [...pendingProductIds.value]) {
      const product = catalog.value?.products.find((candidate) => candidate.product_id === productId)
      if (!product) {
        removePendingProduct(productId)
        continue
      }
      const current = await ensureGroup()
      const next = await api<CaseGroup>(`/order-case-groups/${current.group_id}/items`, {
        method: 'POST',
        body: JSON.stringify({
          product_id: product.product_id,
          item_client_key: crypto.randomUUID(),
          form_values: caseSettingsSnapshot(),
          material_selections: [],
          accessory_selections: [],
          file_ids: [],
          expected_draft_version: current.draft_version
        })
      })
      group.value = next
      selectedOrderId.value = next.items.at(-1)?.order_id ?? null
      removePendingProduct(productId)
    }
    if (!group.value?.items.length) {
      ElMessage.error('所选产品已不可用，请重新选择')
      return false
    }
    return true
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '保存所选产品失败')
    return false
  }
}

async function copyItem(item: CaseGroupItem) {
  if (!group.value || busy.value) return
  busy.value = true
  try {
    group.value = await api<CaseGroup>(
      `/order-case-groups/${group.value.group_id}/items/${item.order_id}/copy`,
      {
        method: 'POST',
        body: JSON.stringify({
          item_client_key: crypto.randomUUID(),
          expected_draft_version: group.value.draft_version
        })
      }
    )
    selectedOrderId.value = group.value.items.at(-1)?.order_id ?? null
    ElMessage.success('产品已复制，原产品资料未重复添加')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '复制失败')
  } finally {
    busy.value = false
  }
}

async function removeItem(item: CaseGroupItem) {
  if (!group.value || busy.value) return
  busy.value = true
  try {
    await ElMessageBox.confirm(`移除“${item.product_name}”？专属上传会被安全停用。`, '移除子产品', {
      confirmButtonText: '确认移除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    group.value = await api<CaseGroup>(
      `/order-case-groups/${group.value.group_id}/items/${item.order_id}`,
      {
        method: 'DELETE',
        body: JSON.stringify({ expected_draft_version: group.value.draft_version })
      }
    )
    delete itemFiles[item.order_id]
    selectedOrderId.value = group.value.items[0]?.order_id ?? null
    ElMessage.success('产品已移除')
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') {
      ElMessage.error(cause instanceof Error ? cause.message : '移除失败')
    }
  } finally {
    busy.value = false
  }
}

function selected(list: QuantitySelection[], itemId: number) {
  return list.some((item) => item.item_id === itemId)
}

function selectionQuantity(list: QuantitySelection[], itemId: number) {
  return list.find((item) => item.item_id === itemId)?.quantity ?? 1
}

function toggleMaterial(binding: CatalogMaterial, checked: boolean) {
  const item = activeItem.value
  if (!item) return
  if (checked && binding.selection_mode === 'SINGLE') {
    const groupIds = new Set(activeMaterials.value
      .filter((candidate) => candidate.selection_group_code === binding.selection_group_code)
      .map((candidate) => candidate.material_id))
    item.material_selections = item.material_selections.filter((entry) => !groupIds.has(entry.item_id))
  }
  item.material_selections = checked
    ? [...item.material_selections.filter((entry) => entry.item_id !== binding.material_id), {
        item_id: binding.material_id,
        quantity: Math.max(1, binding.min_quantity ?? 1)
      }]
    : item.material_selections.filter((entry) => entry.item_id !== binding.material_id)
}

function toggleAccessory(binding: CatalogAccessory, checked: boolean) {
  const item = activeItem.value
  if (!item) return
  item.accessory_selections = checked
    ? [...item.accessory_selections.filter((entry) => entry.item_id !== binding.accessory_id), {
        item_id: binding.accessory_id,
        quantity: Math.max(1, binding.min_quantity ?? 1)
      }]
    : item.accessory_selections.filter((entry) => entry.item_id !== binding.accessory_id)
}

function setSelectionQuantity(type: 'material' | 'accessory', itemId: number, value: number) {
  const item = activeItem.value
  if (!item) return
  const list = type === 'material' ? item.material_selections : item.accessory_selections
  const entry = list.find((candidate) => candidate.item_id === itemId)
  if (entry) entry.quantity = Math.max(1, Number(value) || 1)
}

function fieldVisible(field: FormField, item: CaseGroupItem) {
  const condition = field.visible_when
  return !condition?.field || !('equals' in condition) || item.form_values[condition.field] === condition.equals
}

function optionValue(option: string | { value: string; label: string }) {
  return typeof option === 'string' ? option : option.value
}

function optionLabel(option: string | { value: string; label: string }) {
  return typeof option === 'string' ? option : option.label
}

function updateArrayField(item: CaseGroupItem, key: string, value: string) {
  item.form_values[key] = value.split(/[,，]/).map((entry) => entry.trim()).filter(Boolean)
}

function updateMultiSelectField(item: CaseGroupItem, key: string, event: Event) {
  const target = event.target as HTMLSelectElement
  item.form_values[key] = Array.from(target.selectedOptions).map((option) => option.value)
}

function updateBooleanField(item: CaseGroupItem, key: string, value: boolean) {
  item.form_values[key] = value
}

function updateTextField(item: CaseGroupItem, key: string, value: string) {
  item.form_values[key] = value
}

function fieldType(field: FormField) {
  return (field.type || 'string').toLowerCase()
}

function objectFieldKey(item: CaseGroupItem, key: string) {
  return `${item.order_id}:${key}`
}

function objectFieldText(item: CaseGroupItem, key: string) {
  const draftKey = objectFieldKey(item, key)
  if (Object.prototype.hasOwnProperty.call(objectFieldDrafts, draftKey)) {
    return objectFieldDrafts[draftKey]
  }
  const value = item.form_values[key]
  return value && typeof value === 'object' && !Array.isArray(value)
    ? JSON.stringify(value, null, 2)
    : ''
}

function updateObjectFieldDraft(item: CaseGroupItem, key: string, value: string) {
  objectFieldDrafts[objectFieldKey(item, key)] = value
}

function commitObjectField(item: CaseGroupItem, key: string) {
  const draftKey = objectFieldKey(item, key)
  if (!Object.prototype.hasOwnProperty.call(objectFieldDrafts, draftKey)) return true
  const raw = objectFieldDrafts[draftKey].trim()
  if (!raw) {
    delete item.form_values[key]
    delete objectFieldErrors[draftKey]
    return true
  }
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('补充内容格式不正确')
    }
    item.form_values[key] = parsed
    objectFieldDrafts[draftKey] = JSON.stringify(parsed, null, 2)
    delete objectFieldErrors[draftKey]
    return true
  } catch (cause) {
    objectFieldErrors[draftKey] = cause instanceof Error ? cause.message : '补充内容格式不正确'
    return false
  }
}

function commitItemObjectFields(item: CaseGroupItem) {
  return catalogFieldsForItem(item)
    .filter((field) => fieldType(field) === 'object')
    .every((field) => commitObjectField(item, field.key))
}

function caseStepOneErrors() {
  const errors: string[] = []
  if (!caseSettings.required_delivery_date) errors.push('请选择要求到货日期')
  if (['IMPRESSION', 'REWORK', 'RETURN'].includes(caseSettings.order_type)
    && !caseSettings.inbound_tracking_no.trim()) {
    errors.push('请填写寄模运单号')
  }
  return errors
}

function itemStepErrors(item: CaseGroupItem, targetStep: number) {
  const errors: string[] = []
  const product = catalog.value?.products.find((candidate) => candidate.product_id === item.product_id)
  if (!product) errors.push('所选产品暂不可用，请重新选择')
  if (targetStep === 1) {
    errors.push(...caseStepOneErrors())
  }
  if (targetStep === 2 && product?.tooth_rule_code && !selectedTeeth(item).length) {
    errors.push('请选择牙位')
  }

  const requiredSourceFields: Record<string, Array<[string, string]>> = {
    FIXED_RESTORATION: [
      ['occlusion_level', '请选择咬合'],
      ['contact_level', '请选择邻接'],
      ['stain_level', '请选择染色'],
      ['margin_type', '请选择边缘类型']
    ],
    REMOVABLE_PROSTHETICS: [
      ['occlusion_level', '请选择咬合'],
      ['stain_level', '请选择染色']
    ],
    IMPLANT_RESTORATION: [
      ['retention_type', '请选择固位方式'],
      ['implant_system', '请选择种植系统'],
      ['implant_diameter_length', '请填写种植直径与长度'],
      ['connection_type', '请选择连接方式']
    ],
    CONVENTIONAL_ORTHODONTICS: [
      ['dentition_stage', '请选择牙龄'],
      ['angle_class', '请选择错颌畸形类别'],
      ['skeletal_type', '请选择骨骼类型'],
      ['orthodontic_concern', '请选择诉求问题']
    ],
    CLEAR_ALIGNER: [
      ['treatment_arch', '请选择矫治牙颌'],
      ['treatment_mode', '请选择矫治方式']
    ],
    DESIGN_SERVICE: [
      ['delivery_format', '请选择交付数据格式'],
      ['design_standard', '请选择设计标准'],
      ['design_requirement_turnaround', '请选择设计时间']
    ]
  }
  if (targetStep === 2) {
    for (const [key, message] of requiredSourceFields[product?.category_code ?? ''] ?? []) {
      const value = item.form_values[key]
      if (value == null || value === '' || (Array.isArray(value) && !value.length)) errors.push(message)
    }
  }
  if (targetStep === 3) {
    const variants = (catalog.value?.variants ?? []).filter((candidate) => candidate.product_id === item.product_id)
    if (variants.length && !item.variant_id) errors.push('请选择产品变体')
    if (productMaterialOptions(item).length && !String(item.form_values.material_option ?? '').trim()) {
      errors.push('请选择材料/制作项目')
    }
    if (product?.category_code === 'DESIGN_SERVICE') {
      if (!String(item.form_values.design_delivery_format ?? '').trim()) errors.push('请选择设计交付文件格式')
      if (!String(item.form_values.design_delivery_turnaround ?? '').trim()) errors.push('请选择设计交期')
    }

    const materialBindings = (catalog.value?.materials ?? []).filter((binding) =>
      binding.product_id === item.product_id
      && (binding.variant_id == null || binding.variant_id === item.variant_id)
    )
    const materialGroups = new Map<string, CatalogMaterial[]>()
    materialBindings.forEach((binding) => {
      const list = materialGroups.get(binding.selection_group_code) ?? []
      list.push(binding)
      materialGroups.set(binding.selection_group_code, list)
    })
    materialGroups.forEach((bindings) => {
      if (bindings.some((binding) => Boolean(binding.required_flag))
        && !bindings.some((binding) => selected(item.material_selections, binding.material_id))) {
        errors.push('请选择必选材料')
      }
    })
    const fields = catalogFieldsForItem(item)
    fields.filter((field) => field.required && fieldVisible(field, item)).forEach((field) => {
      const value = item.form_values[field.key]
      if (value == null || value === '' || (Array.isArray(value) && !value.length)) errors.push(`${field.label}必填`)
    })
    Object.entries(objectFieldErrors)
      .filter(([key]) => key.startsWith(`${item.order_id}:`))
      .forEach(([, message]) => errors.push(message))
  }

  if (targetStep === 4) {
    for (const rule of uploadRules(item).filter((rule) => rule.required)) {
      if (!uploadedSlotIds(item, rule.code).length) errors.push(`请上传${rule.label}`)
    }
  }

  if (targetStep === 5
    && item.form_values.physical_model_shipping_required
    && !String(item.form_values.physical_model_tracking_no ?? '').trim()) {
    errors.push('请填写实体模型运单号或配送说明')
  }
  if (targetStep === 5
    && product?.category_code === 'CLEAR_ALIGNER'
    && !orthodonticPrescriptionReady[item.order_id]) {
    errors.push('请完成并提交隐形正畸七步处方')
  }

  return errors
}

function itemErrors(item: CaseGroupItem) {
  return Array.from(new Set([1, 2, 3, 4, 5].flatMap((targetStep) => itemStepErrors(item, targetStep))))
}

function selectedTeeth(item: CaseGroupItem) {
  const value = item.form_values.tooth_positions
  const entries = Array.isArray(value)
    ? value.map(String)
    : String(value ?? '').split(/[,，、\s]+/)
  return Array.from(new Set(entries.map((entry) => entry.trim()).filter(Boolean)))
}

function toothModes(item: CaseGroupItem) {
  const value = item.form_values.tooth_modes
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, string>
    : {}
}

function toothModeOptions(item: CaseGroupItem) {
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') {
    return [
      { value: 'MISSING', label: '缺失位' },
      { value: 'CLASP', label: '卡环位' }
    ]
  }
  if (category === 'CONVENTIONAL_ORTHODONTICS') {
    return [
      { value: 'ORTHO_AREA', label: '正畸区域' },
      { value: 'BAND', label: '带环牙位' }
    ]
  }
  if (category === 'CLEAR_ALIGNER') {
    return [{ value: 'ORTHO_AREA', label: '目标矫治牙位' }]
  }
  if (category === 'IMPLANT_RESTORATION') {
    return [
      { value: 'CROWN', label: '单冠' },
      { value: 'BRIDGE', label: '桥' },
      { value: 'ABUTMENT', label: '加基台' },
      { value: 'FRAMEWORK', label: '加桥架' }
    ]
  }
  return [
    { value: 'CROWN', label: '单冠' },
    { value: 'BRIDGE', label: '桥' }
  ]
}

function currentToothMode(item: CaseGroupItem) {
  const stored = String(item.form_values.current_tooth_mode ?? '')
  const options = toothModeOptions(item)
  return options.some((option) => option.value === stored) ? stored : options[0]?.value ?? 'CROWN'
}

function toothSelected(item: CaseGroupItem, tooth: string) {
  return selectedTeeth(item).includes(tooth)
}

function setToothMode(item: CaseGroupItem, tooth: string, mode: string) {
  const modes = { ...toothModes(item) }
  if (modes[tooth] === mode) delete modes[tooth]
  else modes[tooth] = mode
  item.form_values.tooth_modes = modes
  item.form_values.tooth_positions = [...upperTeeth, ...lowerTeeth].filter((candidate) => modes[candidate]).join(',')
}

function applyToothMode(item: CaseGroupItem, targets: string[], mode: string) {
  const modes = { ...toothModes(item) }
  targets.forEach((tooth) => { modes[tooth] = mode })
  item.form_values.tooth_modes = modes
  item.form_values.tooth_positions = [...upperTeeth, ...lowerTeeth].filter((candidate) => modes[candidate]).join(',')
}

function singleClickToothMode(item: CaseGroupItem) {
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') return 'MISSING'
  if (['CONVENTIONAL_ORTHODONTICS', 'CLEAR_ALIGNER'].includes(category)) return 'ORTHO_AREA'
  if (category === 'IMPLANT_RESTORATION') {
    const current = currentToothMode(item)
    if (['ABUTMENT', 'FRAMEWORK'].includes(current)) return current
  }
  return 'CROWN'
}

function dragToothMode(item: CaseGroupItem) {
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') return 'MISSING'
  if (['CONVENTIONAL_ORTHODONTICS', 'CLEAR_ALIGNER'].includes(category)) return 'ORTHO_AREA'
  return 'BRIDGE'
}

function toothClick(item: CaseGroupItem, tooth: string) {
  if (suppressToothClick.value) {
    suppressToothClick.value = false
    return
  }
  setToothMode(item, tooth, singleClickToothMode(item))
}

function beginToothDrag(item: CaseGroupItem, tooth: string, arch: 'UPPER' | 'LOWER') {
  toothDrag.value = {
    orderId: item.order_id,
    arch,
    start: tooth,
    end: tooth,
    moved: false
  }
}

function extendToothDrag(item: CaseGroupItem, tooth: string, arch: 'UPPER' | 'LOWER') {
  const drag = toothDrag.value
  if (!drag || drag.orderId !== item.order_id || drag.arch !== arch || drag.end === tooth) return
  drag.end = tooth
  drag.moved = drag.start !== drag.end
}

function finishToothDrag(item: CaseGroupItem) {
  const drag = toothDrag.value
  toothDrag.value = null
  if (!drag || drag.orderId !== item.order_id || !drag.moved) return
  const archTeeth = drag.arch === 'UPPER' ? upperTeeth : lowerTeeth
  const start = archTeeth.indexOf(drag.start)
  const end = archTeeth.indexOf(drag.end)
  if (start < 0 || end < 0) return
  applyToothMode(item, archTeeth.slice(Math.min(start, end), Math.max(start, end) + 1), dragToothMode(item))
  suppressToothClick.value = true
  window.setTimeout(() => {
    suppressToothClick.value = false
  }, 0)
}

function doubleClickTooth(item: CaseGroupItem, tooth: string) {
  suppressToothClick.value = false
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') {
    setToothMode(item, tooth, 'CLASP')
    return
  }
  if (category === 'CONVENTIONAL_ORTHODONTICS') {
    setToothMode(item, tooth, 'BAND')
    return
  }
  if (category === 'CLEAR_ALIGNER') {
    const arch = String(item.form_values.treatment_arch ?? '')
    const targets = arch === 'UPPER' ? upperTeeth : arch === 'LOWER' ? lowerTeeth : [...upperTeeth, ...lowerTeeth]
    applyToothMode(item, targets, 'ORTHO_AREA')
    return
  }
  applyToothMode(item, [...upperTeeth, ...lowerTeeth], 'CROWN')
}

function toothModeLabel(item: CaseGroupItem, tooth: string) {
  const mode = toothModes(item)[tooth]
  return toothModeOptions(item).find((option) => option.value === mode)?.label ?? ''
}

function toothSelectionSummary(item: CaseGroupItem) {
  const modes = toothModes(item)
  const groups = toothModeOptions(item)
    .map((option) => ({
      label: option.label,
      teeth: [...upperTeeth, ...lowerTeeth].filter((tooth) => modes[tooth] === option.value)
    }))
    .filter((group) => group.teeth.length)
  if (!groups.length) return toothGestureHelp(item)
  return groups.map((group) => `${group.label}：${group.teeth.join('、')}`).join(' ｜ ')
}

function toothLegend(item: CaseGroupItem) {
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') {
    return [{ label: '缺失位', tone: 'single' }, { label: '卡环位', tone: 'special' }]
  }
  if (category === 'CONVENTIONAL_ORTHODONTICS') {
    return [{ label: '正畸区域', tone: 'single' }, { label: '带环牙位', tone: 'special' }]
  }
  if (category === 'IMPLANT_RESTORATION') {
    return [{ label: '单冠 / 单位', tone: 'single' }, { label: '桥体（连续拖拽）', tone: 'bridge' }, { label: '基台标记', tone: 'special' }]
  }
  return [{ label: '单冠 / 单位', tone: 'single' }, { label: '桥体（连续拖拽）', tone: 'bridge' }]
}

function clearTeeth(item: CaseGroupItem) {
  item.form_values.tooth_positions = ''
  item.form_values.tooth_modes = {}
}

function toothSelectionLabel(item: CaseGroupItem) {
  const product = catalog.value?.products.find((candidate) => candidate.product_id === item.product_id)
  if (product?.category_code === 'REMOVABLE_PROSTHETICS') return '缺失牙位'
  if (product?.category_code === 'IMPLANT_RESTORATION') return '种植 / 修复牙位'
  if (product?.category_code === 'CONVENTIONAL_ORTHODONTICS') return '正畸涉及牙位'
  if (product?.category_code === 'CLEAR_ALIGNER') return '隐形正畸目标牙位'
  return '修复牙位'
}

function toothGestureHelp(item: CaseGroupItem) {
  const category = productCategory(item)
  if (category === 'REMOVABLE_PROSTHETICS') return '单击标缺失位，拖拽连续选择缺失位，双击标卡环位'
  if (category === 'CONVENTIONAL_ORTHODONTICS') return '单击或拖拽选择正畸区域，双击标带环牙位'
  if (category === 'CLEAR_ALIGNER') return '单击或拖拽选择目标牙位，双击按已选牙颌快速选择'
  if (category === 'IMPLANT_RESTORATION') return '单击标单冠，拖拽标桥，双击任意牙位全口选择；可切换基台/桥架后点选对应牙位'
  return '单击标单冠，拖拽标桥，双击任意牙位全口选择'
}

function persistedProductSelected(product: CatalogProduct) {
  return Boolean(group.value?.items.some((item) => item.product_id === product.product_id))
}

function productSelected(product: CatalogProduct) {
  return persistedProductSelected(product) || pendingProductIds.value.includes(product.product_id)
}

function removePendingProduct(productId: number) {
  const index = pendingProductIds.value.indexOf(productId)
  if (index < 0) return
  pendingProductIds.value = pendingProductIds.value.filter((_, candidateIndex) => candidateIndex !== index)
}

function copyPendingProduct(product: CatalogProduct) {
  if (busy.value) return
  pendingProductIds.value = [...pendingProductIds.value, product.product_id]
  ElMessage.success(`已复制 ${product.display_name}，点击下一步后分别创建产品订单`)
}

function toggleProductSelection(product: CatalogProduct) {
  if (busy.value) return
  const persistedItem = group.value?.items.find((item) => item.product_id === product.product_id)
  if (persistedItem) {
    void removeItem(persistedItem)
    return
  }
  if (pendingProductIds.value.includes(product.product_id)) {
    removePendingProduct(product.product_id)
  } else {
    pendingProductIds.value = [...pendingProductIds.value, product.product_id]
  }
}

// AI-7：推荐只作建议，必须由医生点击「采用」才加入订单，系统不自动填表。
function recommendationProduct(recommendation: DoctorProductRecommendation) {
  return (catalog.value?.products ?? []).find(
    (product) => String(product.product_id) === recommendation.productId
  )
}

function recommendationSelected(recommendation: DoctorProductRecommendation) {
  const product = recommendationProduct(recommendation)
  return Boolean(product && productSelected(product))
}

async function loadProductRecommendations() {
  recommendLoading.value = true
  recommendError.value = ''
  try {
    productRecommendations.value = await props.gateway.recommendProducts(recommendCaseNote.value.trim())
    recommendNote.value = productRecommendations.value.length
      ? '以上为建议项，请确认后再选择；价格以正式报价为准。'
      : '当前没有可推荐的产品。'
  } catch (cause) {
    productRecommendations.value = []
    recommendNote.value = ''
    recommendError.value = cause instanceof Error ? cause.message : '智能推荐暂时不可用'
  } finally {
    recommendLoading.value = false
  }
}

async function applyRecommendation(recommendation: DoctorProductRecommendation) {
  const product = recommendationProduct(recommendation)
  if (!product) {
    ElMessage.warning('该推荐产品不在当前生效目录中')
    return
  }
  if (productSelected(product)) {
    ElMessage.info('该产品已经在当前病例中')
    return
  }
  toggleProductSelection(product)
  selectedCategoryCode.value = product.category_code
  activeProductGroup.value = ''
  productKeyword.value = ''
  await nextTick()
  document.querySelector<HTMLElement>(`[data-testid="case-add-product-${product.product_id}"]`)
    ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  ElMessage.success(`已暂存 ${product.display_name}，点击下一步后保存`)
}

function categoryIcon(categoryCode: string) {
  return {
    FIXED_RESTORATION: '♛',
    IMPLANT_RESTORATION: '⚙',
    REMOVABLE_PROSTHETICS: '🦷',
    CONVENTIONAL_ORTHODONTICS: '△',
    CLEAR_ALIGNER: '✦',
    DESIGN_SERVICE: '◈'
  }[categoryCode] ?? '🦷'
}

async function saveItem(item: CaseGroupItem, silent = false) {
  if (!group.value) return false
  if (!commitItemObjectFields(item)) {
    if (!silent) ElMessage.warning('请先修正补充信息')
    return false
  }
  try {
    const mainMaterial = primaryMaterialValue(item)
    if (mainMaterial) choosePrimaryMaterial(item, mainMaterial)
    item.form_values = {
      ...item.form_values,
      ...caseSettingsSnapshot()
    }
    const next = await api<CaseGroup>(
      `/order-case-groups/${group.value.group_id}/items/${item.order_id}`,
      {
        method: 'PUT',
        body: JSON.stringify({
          product_id: item.product_id,
          variant_id: item.variant_id,
          relationship_type: item.relationship_type,
          form_values: item.form_values,
          material_selections: item.material_selections,
          accessory_selections: item.accessory_selections,
          file_ids: [
            ...(item.file_ids ?? []),
            ...(itemFiles[item.order_id] ?? []).map((file) => Number(file.file_id))
          ],
          expected_draft_version: group.value.draft_version
        })
      }
    )
    group.value = next
    if (!silent) ElMessage.success(`${item.product_name} 已保存`)
    return true
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '保存配置失败')
    return false
  }
}

async function saveAllItemsUnlocked() {
  if (!group.value) return false
  const orderIds = group.value.items.map((item) => item.order_id)
  for (const orderId of orderIds) {
    const item = group.value.items.find((candidate) => candidate.order_id === orderId)
    if (item && !(await saveItem(item, true))) return false
  }
  notice.value = `草稿已保存 · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
  return true
}

async function saveAllItems() {
  if (busy.value || fileUploading.value) return false
  busy.value = true
  try {
    return await saveAllItemsUnlocked()
  } finally {
    busy.value = false
  }
}

async function changeVariant(item: CaseGroupItem) {
  item.form_values.material_option = ''
  item.material_selections = []
  item.accessory_selections = []
  await saveItem(item, true)
}

async function uploadProductFiles(event: Event, item: CaseGroupItem, slotCode = 'general') {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || fileUploading.value) return
  fileUploading.value = true
  try {
    const uploaded = await props.gateway.uploadOrderFiles(String(item.order_id), files)
    itemFiles[item.order_id] = [...(itemFiles[item.order_id] ?? []), ...uploaded]
    const slotFiles = item.form_values.upload_slot_files
    const nextSlots = slotFiles && typeof slotFiles === 'object' && !Array.isArray(slotFiles)
      ? { ...(slotFiles as Record<string, unknown>) }
      : {}
    nextSlots[slotCode] = [
      ...uploadedSlotIds(item, slotCode),
      ...uploaded.map((file) => Number(file.file_id))
    ]
    item.form_values.upload_slot_files = nextSlots
    await saveItem(item, true)
    ElMessage.success(`${uploaded.length} 个专属文件已上传`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '专属文件上传失败')
  } finally {
    fileUploading.value = false
  }
}

async function uploadSharedFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  const firstItem = group.value?.items[0]
  if (!files.length || !firstItem || !group.value || fileUploading.value) return
  fileUploading.value = true
  try {
    const uploaded = await props.gateway.uploadOrderFiles(String(firstItem.order_id), files)
    sharedFiles.value = [...sharedFiles.value, ...uploaded]
    group.value = await api<CaseGroup>(`/order-case-groups/${group.value.group_id}/shared-files`, {
      method: 'PUT',
      body: JSON.stringify({
        file_ids: [
          ...(group.value.shared_file_ids ?? []),
          ...sharedFiles.value.map((file) => Number(file.file_id))
        ],
        expected_draft_version: group.value.draft_version
      })
    })
    ElMessage.success(`${uploaded.length} 个病例共享文件已上传`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '共享文件上传失败')
  } finally {
    fileUploading.value = false
  }
}

async function nextStep() {
  if (busy.value || fileUploading.value) return
  if (step.value === 1) {
    if (!patientId.value) {
      ElMessage.warning('请选择患者后再进入下一步')
      return
    }
    if (!selectedProductCount.value) {
      ElMessage.warning('请至少选择一个产品')
      return
    }
    const errors = caseStepOneErrors()
    if (errors.length) {
      ElMessage.warning(errors.join('；'))
      return
    }
  }
  busy.value = true
  try {
    if (step.value === 1 && !(await persistPendingProductsUnlocked())) return
    const currentStepErrors = group.value?.items.flatMap((item) => itemStepErrors(item, step.value)) ?? []
    if (currentStepErrors.length) {
      ElMessage.warning(Array.from(new Set(currentStepErrors)).join('；'))
      return
    }
    if (step.value <= 5 && !(await saveAllItemsUnlocked())) return
    step.value = Math.min(6, step.value + 1)
  } finally {
    busy.value = false
  }
}

function priceLabel(_item: CaseGroupItem) {
  return '待报价'
}

async function submitGroup() {
  if (!group.value || busy.value) return
  if (!finalConfirmationComplete.value) {
    ElMessage.warning('请确认报价、制作要求和制作周期口径')
    return
  }
  busy.value = true
  try {
    group.value.items.forEach((item) => {
      item.form_values.doctor_confirmed_quote_status = true
      item.form_values.doctor_confirmed_requirements = true
      item.form_values.doctor_confirmed_cycle_status = true
    })
    if (!(await saveAllItemsUnlocked())) return
    if (incompleteItems.value.length) {
      ElMessage.warning(`还有 ${incompleteItems.value.length} 个子产品配置不完整`)
      return
    }
    const submitted = await api<CaseGroup>(`/order-case-groups/${group.value.group_id}/submit`, {
      method: 'POST',
      body: JSON.stringify({
        idempotency_key: crypto.randomUUID(),
        expected_draft_version: group.value.draft_version
      })
    })
    group.value = submitted
    emit('submitted', submitted)
    ElMessage.success(`病例订单 ${submitted.group_no} 已提交，共 ${submitted.items.length} 个产品`)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '订单提交失败')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    await loadCatalog()
    await restoreDraft()
  } catch (cause) {
    notice.value = cause instanceof Error ? cause.message : '产品信息加载失败，请刷新后重试'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="case-wizard" data-testid="doctor-case-group-wizard">
    <header class="case-wizard__header">
      <strong>{{ group ? '继续编辑订单草稿' : selectedCategoryCode ? `新建${CATEGORY_NAMES[selectedCategoryCode]}订单` : '新建病例订单' }}</strong>
      <small>{{ notice || (selectedCategoryCode ? `第 ${step} / ${steps.length} 步 · ${CATEGORY_NAMES[selectedCategoryCode]}` : '请选择产品大类开始') }}</small>
      <button type="button" data-testid="case-wizard-close" aria-label="关闭新建订单" title="关闭" @click="emit('close')">×</button>
    </header>

    <nav class="case-wizard__steps" aria-label="多产品下单步骤">
      <template v-for="(label, index) in steps" :key="label">
        <button
          v-if="selectedCategoryCode || index === 0"
          type="button"
          :class="{ active: step === index + 1, done: step > index + 1 }"
          :disabled="index + 1 > step"
          @click="step = index + 1"
        >
          <span>{{ step > index + 1 ? '✓' : index + 1 }}</span>
          <strong>{{ label }}</strong>
        </button>
      </template>
    </nav>

    <main v-loading="loading">
      <section v-if="step === 1" class="case-panel case-source-step">
        <div class="case-source-layout">
          <aside class="case-source-sidebar">
            <section class="case-sidebar-section">
              <header>产品大类</header>
              <div class="case-category-cards">
                <button
                  v-for="category in catalogCategories"
                  :key="category.code"
                  type="button"
                  :class="{ active: selectedCategoryCode === category.code }"
                  @click="selectedCategoryCode = category.code; activeProductGroup = ''; productKeyword = ''"
                >
                  <span>{{ categoryIcon(category.code) }}</span>
                  <div><strong>{{ category.name }}</strong><small>{{ catalogProducts.filter((product) => product.category_code === category.code).length }} 项产品</small></div>
                </button>
              </div>
            </section>

            <section v-if="selectedCategoryCode" class="case-sidebar-section case-sidebar-products">
              <header>具体产品 <b>*</b></header>
              <label class="case-sidebar-search"><span>⌕</span><input v-model="productKeyword" placeholder="搜索产品"></label>
              <div class="case-product-subcards">
                <template v-for="productGroup in selectedProductGroups" :key="productGroup.label || selectedCategoryCode">
                  <h4 v-if="productGroup.label">{{ productGroup.label }}</h4>
                  <button
                    v-for="product in productGroup.products"
                    :key="product.product_id"
                    type="button"
                    :class="{ active: productSelected(product) }"
                    :disabled="busy"
                    :title="productSelected(product) ? `取消选择${product.display_name}` : `选择${product.display_name}`"
                    :data-testid="`case-add-product-${product.product_id}`"
                    @click="toggleProductSelection(product)"
                  >
                    <span><strong>{{ product.display_name }}</strong><small>待报价</small></span>
                    <i>{{ productSelected(product) ? '✓' : '＋' }}</i>
                  </button>
                </template>
                <p v-if="!selectedCategoryProducts.length">该分类暂时没有可下单产品。</p>
              </div>
            </section>

            <section v-if="selectedPatient" class="case-sidebar-patient">
              <header>当前患者</header>
              <div><span>{{ selectedPatient.patient_name.slice(0, 1) }}</span><p><strong>{{ selectedPatient.patient_name }}</strong><small>{{ selectedPatient.patient_code }}</small></p></div>
            </section>
          </aside>

          <div class="case-source-content">
            <header class="case-source-intro">
              <h1>开始新订单</h1>
              <p>先从左侧选择一个或多个具体产品，再检索或新建患者；点击下一步时统一保存病例订单。</p>
            </header>

            <div v-if="catalog?.publication_status !== 'ACTIVE'" class="case-alert warning">
              当前暂时没有可下单产品，请刷新页面或联系订单支持。
            </div>

            <section class="case-recommend-card">
              <header>
                <div>
                  <strong>智能推荐</strong>
                  <small>根据本诊所历史下单与病例描述给出建议，需您确认后才会加入订单</small>
                </div>
                <button type="button" :disabled="recommendLoading" @click="loadProductRecommendations">
                  {{ recommendLoading ? '推荐中…' : '让 AI 推荐' }}
                </button>
              </header>
              <label class="case-recommend-input">
                <span>病例描述（可选）</span>
                <input v-model="recommendCaseNote" placeholder="例如：46 缺失，咬合力较大，患者要求美观">
              </label>
              <p v-if="recommendError" class="case-recommend-error">{{ recommendError }}</p>
              <p v-else-if="recommendNote" class="case-recommend-note">{{ recommendNote }}</p>
              <div v-if="productRecommendations.length" class="case-recommend-list">
                <button
                  v-for="recommendation in productRecommendations"
                  :key="recommendation.productId"
                  type="button"
                  :class="{ selected: recommendationSelected(recommendation) }"
                  :disabled="busy || !recommendationProduct(recommendation) || recommendationSelected(recommendation)"
                  @click="applyRecommendation(recommendation)"
                >
                  <span>
                    <strong>{{ recommendation.displayName }}</strong>
                    <small>{{ recommendation.categoryName }} · {{ recommendation.reason }}</small>
                  </span>
                  <i>{{ !recommendationProduct(recommendation) ? '不在当前目录' : recommendationSelected(recommendation) ? '✓ 已采用' : '＋ 采用' }}</i>
                </button>
              </div>
            </section>

            <section class="case-account-card">
              <span>{{ (props.doctorName || '医').slice(0, 1) }}</span>
              <div>
                <small>当前下单账户（自动带出）</small>
                <strong>{{ props.doctorName || '当前医生' }} · {{ props.clinicName || '当前诊所' }}</strong>
                <p>{{ props.clinicContact || '联系方式以账户资料为准' }}</p>
              </div>
              <b>✓ 已自动填写</b>
            </section>

            <div class="case-source-grid">
              <section class="case-config-form case-patient-section">
                <header class="case-section-title">
                  <div><small>👤 患者</small></div>
                </header>
                <div v-if="selectedPatient" class="case-patient-selected">
                  <span>{{ selectedPatient.patient_name.slice(0, 1) }}</span>
                  <div><strong>{{ selectedPatient.patient_name }}</strong><small>{{ selectedPatient.patient_code }} · {{ selectedPatient.doctor_name }}</small></div>
                  <button v-if="!group" type="button" aria-label="重新选择患者" @click="patientId = ''; patientKeyword = ''">×</button>
                </div>
                <div v-else class="case-patient-autocomplete">
                  <label class="case-search"><span>⌕</span><input v-model="patientKeyword" placeholder="输入患者姓名或编号，搜索已有患者…" @focus="patientSearchFocused = true" @blur="closePatientResults"></label>
                  <div v-if="patientSearchFocused || patientKeyword" class="case-patient-dropdown">
                    <button
                      v-for="patient in patientRows"
                      :key="patient.patient_id"
                      type="button"
                      @mousedown.prevent
                      @click="patientId = patient.patient_id; patientKeyword = ''; patientSearchFocused = false"
                    >
                      <strong>{{ patient.patient_name }}</strong><small>{{ patient.patient_code }} · {{ patient.doctor_name }}</small>
                    </button>
                    <p v-if="!patientRows.length">没有匹配患者，可直接新建患者。</p>
                  </div>
                  <p class="case-patient-create-hint">或 <button type="button" @click="newPatientOpen = !newPatientOpen">直接新建患者</button></p>
                </div>
                <div v-if="newPatientOpen" class="case-new-patient">
                  <label><span>患者姓名 *</span><input v-model="newPatient.name"></label>
                  <label><span>出生日期</span><input v-model="newPatient.date_of_birth" type="date"></label>
                  <label><span>性别</span><select v-model="newPatient.gender"><option value="">请选择</option><option value="MALE">男</option><option value="FEMALE">女</option><option value="OTHER">其他</option></select></label>
                  <label><span>联系电话</span><input v-model="newPatient.phone"></label>
                  <label><span>邮箱</span><input v-model="newPatient.email" type="email"></label>
                  <label class="full"><span>病史/用药/过敏</span><textarea v-model="newPatient.medical_notes" rows="2"></textarea></label>
                  <div class="full"><button type="button" class="case-primary" :disabled="newPatientSaving" @click="createPatientFromWizard">{{ newPatientSaving ? '保存中…' : '保存并选中患者' }}</button></div>
                </div>
              </section>

              <section class="case-config-form">
                <header class="case-section-title"><div><small>订单要求</small><h3>出货、到货与运输信息</h3></div></header>
                <div class="case-field-grid">
                  <label class="case-field"><span>订单周期 *</span><select v-model="caseSettings.priority"><option value="NORMAL">正常出货周期</option><option value="RUSH_3_DAYS">3 天加急</option><option value="SAME_DAY">当天出货</option></select></label>
                  <label class="case-field"><span>要求到货日期 *</span><input v-model="caseSettings.required_delivery_date" type="date"></label>
                  <label class="case-field"><span>患者预约时间</span><input v-model="caseSettings.appointment_date" type="date"></label>
                  <label class="case-field"><span>运输类型 *</span><select v-model="caseSettings.shipping_method"><option value="COURIER">快递</option><option value="SALES_DELIVERY">业务员配送</option><option value="SELF_PICKUP">自取</option></select></label>
                  <label class="case-field"><span>订单类型 *</span><select v-model="caseSettings.order_type"><option value="ONLINE">网络订单</option><option value="IMPRESSION">印模订单</option><option value="REWORK">返工订单</option><option value="RETURN">退货订单</option><option value="DESIGN_ONLY">仅设计订单</option></select></label>
                  <label v-if="['IMPRESSION', 'REWORK', 'RETURN'].includes(caseSettings.order_type)" class="case-field"><span>寄模运单号 *</span><input v-model="caseSettings.inbound_tracking_no" placeholder="填写寄回模型的运单号"></label>
                  <label class="case-field full"><span>整单备注</span><textarea v-model="caseSettings.global_notes" rows="3" placeholder="病例整体要求，可使用中文或英文"></textarea></label>
                </div>
                <div class="case-alert warning">请填写期望到货日期；客服将在受理订单时确认可行的制作与配送周期。</div>
              </section>
            </div>

            <aside v-if="selectedProductCount" class="case-basket case-basket-inline">
              <header><strong>已选产品</strong><span>{{ selectedProductCount }} 项</span></header>
              <article v-for="item in group?.items ?? []" :key="item.order_id">
                <div><strong>{{ item.product_name }}</strong><small>产品订单 {{ item.order_no }}</small></div>
                <span>待报价</span>
                <button type="button" @click="copyItem(item)">复制</button>
                <button type="button" class="danger" @click="removeItem(item)">移除</button>
              </article>
              <article v-for="(product, index) in pendingProducts" :key="`pending-${product.product_id}-${index}`">
                <div><strong>{{ product.display_name }}</strong><small>尚未保存，点击下一步后创建产品订单</small></div>
                <span>待报价</span>
                <button type="button" :disabled="busy" @click="copyPendingProduct(product)">复制</button>
                <button type="button" class="danger" :disabled="busy" @click="removePendingProduct(product.product_id)">取消选择</button>
              </article>
            </aside>
          </div>
        </div>
      </section>

      <section v-else-if="step === 2" class="case-panel case-config-panel">
        <header><h1>牙位与制作要求</h1><p>请逐个产品选择牙位，并填写相应的临床与制作要求。</p></header>
        <div class="case-config-layout">
          <aside class="case-item-tabs">
            <button
              v-for="item in group?.items ?? []"
              :key="item.order_id"
              type="button"
              :class="{ active: activeItem?.order_id === item.order_id }"
              @click="selectedOrderId = item.order_id"
            >
              <span>{{ item.line_no }}</span>
              <div><strong>{{ item.product_name }}</strong><small>{{ itemStepErrors(item, 2).length ? `${itemStepErrors(item, 2).length} 项待补` : '本阶段完整' }}</small></div>
            </button>
          </aside>
          <div v-if="activeItem" class="case-config-form">
            <div class="case-config-summary">
              <div><span>产品订单</span><strong>{{ activeItem.order_no }}</strong></div>
              <div><span>当前产品</span><strong>{{ activeItem.product_name }}</strong></div>
              <div><span>价格</span><strong>{{ priceLabel(activeItem) }}</strong></div>
            </div>
            <section v-if="activeProduct?.tooth_rule_code" class="case-tooth-chart full" data-testid="case-fdi-tooth-chart">
              <header>
                <div><strong>{{ toothSelectionLabel(activeItem) }}（FDI） *</strong><small>{{ toothGestureHelp(activeItem) }}</small></div>
                <div><span>已选：{{ selectedTeeth(activeItem).join('、') || '暂无' }}</span><button type="button" :disabled="!selectedTeeth(activeItem).length" @click="clearTeeth(activeItem)">清空</button></div>
              </header>
              <div v-if="productCategory(activeItem) === 'IMPLANT_RESTORATION'" class="case-tooth-modes">
                <span>额外标记：</span>
                <button
                  v-for="option in toothModeOptions(activeItem).filter((candidate) => ['CROWN', 'ABUTMENT', 'FRAMEWORK'].includes(candidate.value))"
                  :key="option.value"
                  type="button"
                  :class="{ active: currentToothMode(activeItem) === option.value }"
                  @click="activeItem.form_values.current_tooth_mode = option.value"
                >{{ option.value === 'CROWN' ? '普通单冠' : option.label }}</button>
              </div>
              <div class="case-tooth-legend" aria-label="牙位图图例">
                <span v-for="item in toothLegend(activeItem)" :key="item.label"><i :class="`is-${item.tone}`"></i>{{ item.label }}</span>
              </div>
              <svg
                class="case-dental-svg"
                viewBox="0 0 700 330"
                role="img"
                aria-label="FDI 牙位选择图"
                @pointerup="finishToothDrag(activeItem)"
              >
                <text x="350" y="13" text-anchor="middle" class="case-dental-jaw-title">上颌 · MAXILLA</text>
                <text x="350" y="221" text-anchor="middle" class="case-dental-jaw-title">下颌 · MANDIBLE</text>
                <line x1="350" y1="18" x2="350" y2="175" class="case-dental-midline"></line>
                <line x1="350" y1="228" x2="350" y2="318" class="case-dental-midline"></line>
                <line x1="30" y1="158" x2="670" y2="158" class="case-dental-occlusion"></line>
                <line x1="30" y1="238" x2="670" y2="238" class="case-dental-occlusion"></line>
                <text x="16" y="100" text-anchor="middle" class="case-dental-side">R</text>
                <text x="684" y="100" text-anchor="middle" class="case-dental-side">L</text>
                <text x="16" y="285" text-anchor="middle" class="case-dental-side">R</text>
                <text x="684" y="285" text-anchor="middle" class="case-dental-side">L</text>
                <g
                  v-for="tooth in upperToothSvg"
                  :key="tooth.number"
                  class="case-svg-tooth"
                  :class="[{ selected: toothSelected(activeItem, tooth.number) }, `mode-${toothModes(activeItem)[tooth.number] || 'NONE'}`]"
                >
                  <path v-for="(path, index) in tooth.rootPaths" :key="index" class="tooth-body" :d="path"></path>
                  <path class="tooth-body" :d="tooth.crownPath"></path>
                  <line class="case-tooth-junction" :x1="tooth.junction.x1" :x2="tooth.junction.x2" :y1="tooth.junction.y" :y2="tooth.junction.y"></line>
                  <text :x="tooth.numberPosition.x" :y="tooth.numberPosition.y" text-anchor="middle" class="case-tooth-number">{{ tooth.number }}</text>
                  <rect
                    class="case-tooth-hit"
                    :x="tooth.hitArea.x"
                    :y="tooth.hitArea.y"
                    :width="tooth.hitArea.width"
                    :height="tooth.hitArea.height"
                    :aria-label="`牙位 ${tooth.number}${toothSelected(activeItem, tooth.number) ? `，${toothModeLabel(activeItem, tooth.number)}` : ''}`"
                    @pointerdown.prevent="beginToothDrag(activeItem, tooth.number, 'UPPER')"
                    @pointerenter="extendToothDrag(activeItem, tooth.number, 'UPPER')"
                    @pointerup="finishToothDrag(activeItem)"
                    @click="toothClick(activeItem, tooth.number)"
                    @dblclick.prevent="doubleClickTooth(activeItem, tooth.number)"
                  ></rect>
                </g>
                <g
                  v-for="tooth in lowerToothSvg"
                  :key="tooth.number"
                  class="case-svg-tooth"
                  :class="[{ selected: toothSelected(activeItem, tooth.number) }, `mode-${toothModes(activeItem)[tooth.number] || 'NONE'}`]"
                >
                  <path class="tooth-body" :d="tooth.crownPath"></path>
                  <line class="case-tooth-junction" :x1="tooth.junction.x1" :x2="tooth.junction.x2" :y1="tooth.junction.y" :y2="tooth.junction.y"></line>
                  <path v-for="(path, index) in tooth.rootPaths" :key="index" class="tooth-body" :d="path"></path>
                  <text :x="tooth.numberPosition.x" :y="tooth.numberPosition.y" text-anchor="middle" class="case-tooth-number">{{ tooth.number }}</text>
                  <rect
                    class="case-tooth-hit"
                    :x="tooth.hitArea.x"
                    :y="tooth.hitArea.y"
                    :width="tooth.hitArea.width"
                    :height="tooth.hitArea.height"
                    :aria-label="`牙位 ${tooth.number}${toothSelected(activeItem, tooth.number) ? `，${toothModeLabel(activeItem, tooth.number)}` : ''}`"
                    @pointerdown.prevent="beginToothDrag(activeItem, tooth.number, 'LOWER')"
                    @pointerenter="extendToothDrag(activeItem, tooth.number, 'LOWER')"
                    @pointerup="finishToothDrag(activeItem)"
                    @click="toothClick(activeItem, tooth.number)"
                    @dblclick.prevent="doubleClickTooth(activeItem, tooth.number)"
                  ></rect>
                </g>
              </svg>
              <div class="case-tooth-summary">{{ toothSelectionSummary(activeItem) }}</div>
            </section>

            <section class="case-config-block">
              <header><h3>制作要求</h3><small>内容会根据当前产品自动调整</small></header>
              <div v-if="productCategory(activeItem) === 'FIXED_RESTORATION'" class="case-field-grid">
                <label class="case-field"><span>咬合 *</span><select v-model="activeItem.form_values.occlusion_level"><option value="">请选择</option><option value="LIGHT">轻</option><option value="NORMAL">正常</option><option value="HEAVY">重</option><option value="CLEARANCE">空开</option></select></label>
                <label v-if="activeItem.form_values.occlusion_level === 'CLEARANCE'" class="case-field"><span>空开距离（mm）*</span><input v-model.number="activeItem.form_values.occlusion_clearance_mm" type="number" min="0" step="0.1"></label>
                <label class="case-field"><span>邻接 *</span><select v-model="activeItem.form_values.contact_level"><option value="">请选择</option><option value="OPEN">空开</option><option value="NORMAL">正常</option><option value="TIGHT">紧</option><option value="POINT">点接触</option><option value="SURFACE">面接触</option></select></label>
                <label class="case-field"><span>染色 *</span><select v-model="activeItem.form_values.stain_level"><option value="">请选择</option><option value="NONE">无</option><option value="LIGHT">轻</option><option value="MEDIUM">中</option><option value="HEAVY">重</option></select></label>
                <label class="case-field"><span>边缘 *</span><select v-model="activeItem.form_values.margin_type"><option value="">请选择</option><option value="METAL">金属边缘</option><option value="PORCELAIN">包瓷边缘</option><option value="THREE_QUARTER_LINGUAL">3/4 金属舌侧边</option></select></label>
              </div>
              <div v-else-if="productCategory(activeItem) === 'REMOVABLE_PROSTHETICS'" class="case-field-grid">
                <label class="case-field"><span>咬合 *</span><select v-model="activeItem.form_values.occlusion_level"><option value="">请选择</option><option value="LIGHT">轻</option><option value="NORMAL">正常</option><option value="HEAVY">重</option><option value="CLEARANCE">空开</option></select></label>
                <label v-if="activeItem.form_values.occlusion_level === 'CLEARANCE'" class="case-field"><span>空开距离（mm）*</span><input v-model.number="activeItem.form_values.occlusion_clearance_mm" type="number" min="0" step="0.1"></label>
                <label class="case-field"><span>染色 *</span><select v-model="activeItem.form_values.stain_level"><option value="">请选择</option><option value="NONE">无</option><option value="LIGHT">轻</option><option value="MEDIUM">中</option><option value="HEAVY">重</option></select></label>
                <label class="case-field"><span>垂直高度（mm）</span><input v-model.number="activeItem.form_values.vertical_height_mm" type="number" min="0" step="0.1"></label>
              </div>
              <div v-else-if="productCategory(activeItem) === 'IMPLANT_RESTORATION'" class="case-field-grid">
                <label class="case-field"><span>固位方式 *</span><select v-model="activeItem.form_values.retention_type"><option value="">请选择</option><option value="SCREW">螺丝固位</option><option value="CEMENT">粘接固位</option></select></label>
                <label class="case-field"><span>种植系统 *</span><select v-model="activeItem.form_values.implant_system"><option value="">请选择</option><option>Nobel Biocare</option><option>Straumann</option><option>Osstem</option><option>BioHorizons</option><option>Zimmer Biomet</option><option>Megagen</option><option>其他</option></select></label>
                <label class="case-field"><span>种植直径 × 长度 *</span><input v-model="activeItem.form_values.implant_diameter_length" placeholder="例如 Ø4.1 × 10mm"></label>
                <label class="case-field"><span>穿龈高度（mm）</span><input v-model.number="activeItem.form_values.transmucosal_height_mm" type="number" min="0" step="0.1"></label>
                <label class="case-field"><span>连接方式 *</span><select v-model="activeItem.form_values.connection_type"><option value="">请选择</option><option value="EXTERNAL">外连接</option><option value="INTERNAL">内连接</option></select></label>
                <label class="case-field"><span>咬合</span><select v-model="activeItem.form_values.occlusion_level"><option value="">请选择</option><option value="LIGHT">轻</option><option value="NORMAL">正常</option><option value="HEAVY">重</option><option value="CLEARANCE">空开</option></select></label>
                <label class="case-field"><span>染色</span><select v-model="activeItem.form_values.stain_level"><option value="">请选择</option><option value="NONE">无</option><option value="LIGHT">轻</option><option value="MEDIUM">中</option><option value="HEAVY">重</option></select></label>
                <label class="case-field case-switch"><input v-model="activeItem.form_values.gingival_porcelain" type="checkbox"><span>是否加牙龈瓷</span></label>
              </div>
              <div v-else-if="productCategory(activeItem) === 'CONVENTIONAL_ORTHODONTICS'" class="case-field-grid">
                <label class="case-field"><span>牙龄 *</span><select v-model="activeItem.form_values.dentition_stage"><option value="">请选择</option><option value="PERMANENT">恒牙</option><option value="PRIMARY">乳牙</option><option value="MIXED">替牙</option></select></label>
                <label class="case-field"><span>错颌畸形类别 *</span><select v-model="activeItem.form_values.angle_class"><option value="">请选择</option><option value="CLASS_I">安氏一类</option><option value="CLASS_II">安氏二类</option><option value="CLASS_III">安氏三类</option></select></label>
                <label class="case-field"><span>骨骼类型 *</span><select v-model="activeItem.form_values.skeletal_type"><option value="">请选择</option><option value="DENTAL">牙型</option><option value="SKELETAL">骨性</option></select></label>
                <div class="case-field full"><span>诉求问题 *</span><div class="case-check-grid"><label v-for="value in ['拥挤', '稀疏', '前突', '地包天']" :key="value"><input type="checkbox" :checked="sourceArray(activeItem, 'orthodontic_concern').includes(value)" @change="toggleSourceArray(activeItem, 'orthodontic_concern', value, ($event.target as HTMLInputElement).checked)">{{ value }}</label></div></div>
              </div>
              <div v-else-if="productCategory(activeItem) === 'CLEAR_ALIGNER'" class="case-field-grid">
                <label class="case-field"><span>矫治牙颌 *</span><select v-model="activeItem.form_values.treatment_arch" data-testid="case-clear-aligner-arch"><option value="">请选择</option><option v-for="option in CLEAR_ALIGNER_ARCH_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
                <label class="case-field"><span>矫治方式 *</span><select v-model="activeItem.form_values.treatment_mode" data-testid="case-clear-aligner-mode"><option value="">请选择</option><option v-for="option in CLEAR_ALIGNER_TREATMENT_OPTIONS" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
                <div class="case-alert info full">七步处方将在“试戴与过程确认”阶段填写；联合矫治时还需选择同一病例中的关联产品。</div>
              </div>
              <div v-else-if="productCategory(activeItem) === 'DESIGN_SERVICE'" class="case-field-grid">
                <label class="case-field"><span>数据格式 *</span><select v-model="activeItem.form_values.delivery_format"><option value="">请选择</option><option>STL</option><option>OBJ</option><option>EXO</option><option>3SHAPE</option></select></label>
                <label class="case-field"><span>设计标准 *</span><select v-model="activeItem.form_values.design_standard"><option value="">请选择</option><option value="GENERAL">通用</option><option value="PERSONALIZED">个性化</option></select></label>
                <label class="case-field"><span>设计时间 *</span><select v-model="activeItem.form_values.design_requirement_turnaround"><option value="">请选择</option><option value="12H">12 小时</option><option value="24H">24 小时</option><option value="3D">3 天</option></select></label>
              </div>
            </section>
            <label class="case-field full"><span>病例说明</span><textarea :value="String(activeItem.form_values.case_note ?? '')" rows="4" placeholder="补充咬合、外形或其他临床要求" @input="updateTextField(activeItem, 'case_note', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
            <button type="button" class="case-primary" :disabled="busy" @click="saveItem(activeItem)">保存当前牙位与制作要求</button>
          </div>
        </div>
      </section>

      <section v-else-if="step === 3" class="case-panel case-config-panel">
        <header><h1>材料与工艺</h1><p>请逐个产品填写对应的材料、色号和制作要求。</p></header>
        <div class="case-config-layout">
          <aside class="case-item-tabs">
            <button
              v-for="item in group?.items ?? []"
              :key="item.order_id"
              type="button"
              :class="{ active: activeItem?.order_id === item.order_id }"
              @click="selectedOrderId = item.order_id"
            >
              <span>{{ item.line_no }}</span>
              <div><strong>{{ item.product_name }}</strong><small>{{ itemStepErrors(item, 3).length ? `${itemStepErrors(item, 3).length} 项待补` : '本阶段完整' }}</small></div>
            </button>
          </aside>
          <div v-if="activeItem" class="case-config-form case-material-form" data-testid="case-material-form">
            <div class="case-current-product">
              <i>{{ categoryIcon(productCategory(activeItem)) }}</i>
              <div>
                <strong>{{ activeItem.product_name }}</strong>
                <small>{{ CATEGORY_NAMES[productCategory(activeItem)] }}<template v-if="activeVariant"> · {{ activeVariant.display_name }}</template></small>
              </div>
              <span>{{ priceLabel(activeItem) }}</span>
            </div>

            <section v-if="productCategory(activeItem) === 'IMPLANT_RESTORATION'" class="case-material-section">
              <header><h3>种植参数</h3></header>
              <div class="case-material-grid">
                <label class="case-field"><span>种植系统 *</span><select v-model="activeItem.form_values.implant_system"><option value="">请选择</option><option>Nobel Biocare</option><option>Straumann</option><option>Osstem</option><option>BioHorizons</option><option>Zimmer Biomet</option><option>Megagen</option><option>其他</option></select></label>
                <label class="case-field"><span>种植直径 × 长度 *</span><input v-model="activeItem.form_values.implant_diameter_length" placeholder="例如 Ø4.1 × 10mm"></label>
                <label class="case-field"><span>连接方式 *</span><select v-model="activeItem.form_values.connection_type"><option value="">请选择</option><option value="INTERNAL">内连接</option><option value="EXTERNAL">外连接</option></select></label>
                <label class="case-field"><span>基台类型</span><select v-model="activeItem.form_values.abutment_type"><option value="">请选择</option><option value="STANDARD">标准基台</option><option value="ANGLED">角度基台</option><option value="CUSTOM">个性化基台</option></select></label>
                <label class="case-field"><span>固位方式 *</span><select v-model="activeItem.form_values.retention_type"><option value="">请选择</option><option value="SCREW">螺丝固位</option><option value="CEMENT">粘接固位</option></select></label>
                <label class="case-field"><span>螺丝开口位置</span><select v-model="activeItem.form_values.screw_access_position"><option value="">请选择</option><option value="BUCCAL">颊侧</option><option value="LINGUAL">舌侧</option><option value="OCCLUSAL">咬合面</option></select></label>
              </div>
            </section>

            <section
              v-if="['FIXED_RESTORATION', 'IMPLANT_RESTORATION', 'REMOVABLE_PROSTHETICS'].includes(productCategory(activeItem)) || primaryMaterialOptions(activeItem).length || activeVariants.length"
              class="case-material-section"
            >
              <header><h3>{{ productCategory(activeItem) === 'IMPLANT_RESTORATION' ? '修复材料' : productCategory(activeItem) === 'REMOVABLE_PROSTHETICS' ? '活动义齿配置' : '材料与工艺' }}</h3></header>
              <div class="case-material-grid">
                <label v-if="primaryMaterialOptions(activeItem).length" class="case-field">
                  <span>主材料 / 制作项目 *</span>
                  <select :value="primaryMaterialValue(activeItem)" data-testid="case-primary-material" @change="choosePrimaryMaterial(activeItem, ($event.target as HTMLSelectElement).value)">
                    <option value="">请选择</option>
                    <option v-for="option in primaryMaterialOptions(activeItem)" :key="option" :value="option">{{ option }}</option>
                  </select>
                </label>
                <label v-if="activeVariants.length" class="case-field">
                  <span>产品规格 *</span>
                  <select v-model.number="activeItem.variant_id" @change="changeVariant(activeItem)">
                    <option :value="null">请选择</option>
                    <option v-for="variant in activeVariants" :key="variant.variant_id" :value="variant.variant_id">{{ variant.display_name }}</option>
                  </select>
                </label>

                <template v-if="productCategory(activeItem) === 'FIXED_RESTORATION'">
                  <label class="case-field"><span>边缘类型</span><select v-model="activeItem.form_values.finish_margin_type"><option value="">请选择</option><option value="SUPRAGINGIVAL">龈上边缘</option><option value="SUBGINGIVAL">龈下边缘</option><option value="SHOULDER_STANDARD">肩台标准</option></select></label>
                  <label class="case-field"><span>牙色系统</span><select v-model="activeItem.form_values.shade_system"><option value="">请选择</option><option value="VITA_16">VITA 16 Classic</option><option value="3D_MASTER">3D Master</option><option value="THREE_ZONE">颈部 / 体部 / 切端分色</option></select></label>
                </template>
                <template v-else-if="productCategory(activeItem) === 'IMPLANT_RESTORATION'">
                  <label class="case-field"><span>牙色系统</span><select v-model="activeItem.form_values.shade_system"><option value="">请选择</option><option value="VITA_16">VITA 16 Classic</option><option value="3D_MASTER">3D Master</option><option value="THREE_ZONE">颈部 / 体部 / 切端分色</option></select></label>
                </template>
                <template v-else-if="productCategory(activeItem) === 'REMOVABLE_PROSTHETICS'">
                  <label class="case-field"><span>卡环设计</span><select v-model="activeItem.form_values.clasp_design"><option value="">无 / 请选择</option><option>Standard I-bar</option><option>Circumferential</option><option>Ball Clasp</option><option>Custom</option><option>Casting Wire Clasp</option><option>Clear Clasp</option><option>Valplast Clasp-clear</option><option>Cast Chrome Clasp</option><option>Wrought Wire Clasp</option><option>Valplast Clasp-pink</option></select></label>
                  <label class="case-field"><span>义齿牙品牌</span><select v-model="activeItem.form_values.denture_teeth_brand"><option value="">请选择</option><option>Huge</option><option>Yamahachi</option><option>Vita</option></select></label>
                  <label class="case-field"><span>牙色系统</span><select v-model="activeItem.form_values.shade_system"><option value="">请选择</option><option value="VITA_16">VITA 16 Classic</option><option value="3D_MASTER">3D Master</option></select></label>
                </template>

                <label v-if="activeItem.form_values.shade_system && activeItem.form_values.shade_system !== 'THREE_ZONE' && ['FIXED_RESTORATION', 'IMPLANT_RESTORATION', 'REMOVABLE_PROSTHETICS'].includes(productCategory(activeItem))" class="case-field"><span>牙色</span><select v-model="activeItem.form_values.shade_value"><option value="">请选择</option><option v-for="shade in activeItem.form_values.shade_system === '3D_MASTER' ? VITA_3D_SHADES : VITA_16_SHADES" :key="shade">{{ shade }}</option></select></label>
                <template v-if="activeItem.form_values.shade_system === 'THREE_ZONE'">
                  <label class="case-field"><span>颈部色</span><select v-model="activeItem.form_values.cervical_shade"><option value="">请选择</option><option v-for="shade in [...VITA_16_SHADES, ...VITA_3D_SHADES]" :key="`c-${shade}`">{{ shade }}</option></select></label>
                  <label class="case-field"><span>体部色</span><select v-model="activeItem.form_values.body_shade"><option value="">请选择</option><option v-for="shade in [...VITA_16_SHADES, ...VITA_3D_SHADES]" :key="`b-${shade}`">{{ shade }}</option></select></label>
                  <label class="case-field"><span>切端色</span><select v-model="activeItem.form_values.incisal_shade"><option value="">请选择</option><option v-for="shade in [...VITA_16_SHADES, ...VITA_3D_SHADES]" :key="`i-${shade}`">{{ shade }}</option></select></label>
                </template>
                <label v-if="productCategory(activeItem) === 'REMOVABLE_PROSTHETICS'" class="case-field"><span>义齿基托颜色</span><select v-model="activeItem.form_values.denture_base_shade"><option value="">请选择</option><option v-for="shade in DENTURE_BASE_SHADES" :key="shade">{{ shade }}</option></select></label>
                <label v-if="['FIXED_RESTORATION', 'IMPLANT_RESTORATION', 'REMOVABLE_PROSTHETICS'].includes(productCategory(activeItem))" class="case-field"><span>抛光程度</span><select v-model="activeItem.form_values.polish_grade"><option value="">请选择</option><option value="STANDARD">普通抛光</option><option value="MIRROR">镜面抛光</option></select></label>
                <label v-if="['FIXED_RESTORATION', 'IMPLANT_RESTORATION', 'REMOVABLE_PROSTHETICS'].includes(productCategory(activeItem))" class="case-field full"><span>材料与色号备注</span><textarea :value="String(activeItem.form_values.material_shade_notes ?? '')" rows="3" placeholder="补充颜色、个性化染色或材料要求" @input="updateTextField(activeItem, 'material_shade_notes', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
              </div>
            </section>

            <section v-if="productCategory(activeItem) === 'FIXED_RESTORATION'" class="case-material-section">
              <header><h3>精密附件（可选）</h3></header>
              <div class="case-check-grid">
                <label v-for="attachment in FIXED_PRECISION_ATTACHMENTS" :key="attachment">
                  <input type="checkbox" :checked="sourceArray(activeItem, 'precision_attachments').includes(attachment)" @change="toggleSourceArray(activeItem, 'precision_attachments', attachment, ($event.target as HTMLInputElement).checked)">
                  {{ attachment }}
                </label>
              </div>
            </section>

            <section v-if="productCategory(activeItem) === 'CONVENTIONAL_ORTHODONTICS'" class="case-material-section">
              <header><h3>正畸附件</h3></header>
              <div class="case-check-grid">
                <label v-for="accessory in ORTHODONTIC_ACCESSORIES" :key="accessory">
                  <input type="checkbox" :checked="sourceArray(activeItem, 'orthodontic_accessories').includes(accessory)" @change="toggleSourceArray(activeItem, 'orthodontic_accessories', accessory, ($event.target as HTMLInputElement).checked)">
                  {{ accessory }}
                </label>
              </div>
              <label class="case-field"><span>附件数量及位置说明</span><textarea :value="String(activeItem.form_values.orthodontic_accessory_notes ?? '')" rows="3" placeholder="例如：16、26 各加一个带环" @input="updateTextField(activeItem, 'orthodontic_accessory_notes', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
            </section>

            <section v-if="productCategory(activeItem) === 'DESIGN_SERVICE'" class="case-material-section">
              <header><h3>设计交付</h3></header>
              <div class="case-material-grid">
                <label class="case-field"><span>交付文件格式 *</span><select v-model="activeItem.form_values.design_delivery_format"><option value="">请选择</option><option>STL</option><option>OBJ</option><option>EXO</option><option>3SHAPE</option></select></label>
                <label class="case-field"><span>交付时间 *</span><select v-model="activeItem.form_values.design_delivery_turnaround"><option value="">请选择</option><option value="6H">6 小时</option><option value="12H">12 小时</option><option value="24H">24 小时</option><option value="48H">48 小时</option></select></label>
              </div>
            </section>

            <section v-if="activeMultipleMaterials.length" class="case-material-section">
              <header><h3>附加材料</h3></header>
              <label v-for="binding in activeMultipleMaterials" :key="binding.material_id" class="case-option">
                <input type="checkbox" :checked="selected(activeItem.material_selections, binding.material_id)" @change="toggleMaterial(binding, ($event.target as HTMLInputElement).checked)">
                <span><strong>{{ binding.display_name }}</strong><small>{{ [binding.brand_name, binding.specification].filter(Boolean).join(' · ') }}</small></span>
                <em>{{ binding.required_flag ? '必选' : '可选' }}</em>
                <input v-if="selected(activeItem.material_selections, binding.material_id)" type="number" min="1" :max="binding.max_quantity ?? undefined" :value="selectionQuantity(activeItem.material_selections, binding.material_id)" @input="setSelectionQuantity('material', binding.material_id, Number(($event.target as HTMLInputElement).value))">
              </label>
            </section>

            <section v-if="activeAccessories.length" class="case-material-section">
              <header><h3>附加选项</h3></header>
              <label v-for="binding in activeAccessories" :key="binding.accessory_id" class="case-option">
                <input type="checkbox" :checked="selected(activeItem.accessory_selections, binding.accessory_id)" @change="toggleAccessory(binding, ($event.target as HTMLInputElement).checked)">
                <span><strong>{{ binding.display_name }}</strong></span>
                <em>{{ binding.required_flag ? '必选' : '可选' }}</em>
                <input v-if="selected(activeItem.accessory_selections, binding.accessory_id)" type="number" min="1" :max="binding.max_quantity ?? undefined" :value="selectionQuantity(activeItem.accessory_selections, binding.accessory_id)" @input="setSelectionQuantity('accessory', binding.accessory_id, Number(($event.target as HTMLInputElement).value))">
              </label>
            </section>

            <section v-if="activeFields.length" class="case-material-section">
              <header><h3>补充要求</h3></header>
              <div class="case-material-grid">
                <template v-for="field in activeFields" :key="field.key">
                  <label v-if="fieldVisible(field, activeItem)" class="case-field" :class="{ full: ['textarea', 'object'].includes(fieldType(field)) }">
                    <span>{{ field.label }}<b v-if="field.required"> *</b></span>
                    <select v-if="fieldType(field) === 'multi_select' && field.options?.length" multiple :value="Array.isArray(activeItem.form_values[field.key]) ? activeItem.form_values[field.key] : []" @change="updateMultiSelectField(activeItem, field.key, $event)">
                      <option v-for="option in field.options" :key="optionValue(option)" :value="optionValue(option)">{{ optionLabel(option) }}</option>
                    </select>
                    <select v-else-if="field.options?.length" v-model="activeItem.form_values[field.key]"><option value="">请选择</option><option v-for="option in field.options" :key="optionValue(option)" :value="optionValue(option)">{{ optionLabel(option) }}</option></select>
                    <textarea v-else-if="fieldType(field) === 'textarea'" :value="String(activeItem.form_values[field.key] ?? '')" rows="3" @input="updateTextField(activeItem, field.key, ($event.target as HTMLTextAreaElement).value)"></textarea>
                    <input v-else-if="fieldType(field) === 'number' || fieldType(field) === 'quantity'" v-model.number="activeItem.form_values[field.key]" type="number" :step="fieldType(field) === 'quantity' ? 1 : 'any'" :min="field.minimum ?? field.min" :max="field.maximum ?? field.max">
                    <label v-else-if="fieldType(field) === 'boolean'" class="case-switch"><input type="checkbox" :checked="Boolean(activeItem.form_values[field.key])" @change="updateBooleanField(activeItem, field.key, ($event.target as HTMLInputElement).checked)"><span>是 / 否</span></label>
                    <input v-else-if="fieldType(field) === 'array' || fieldType(field) === 'multi_select'" :value="Array.isArray(activeItem.form_values[field.key]) ? (activeItem.form_values[field.key] as unknown[]).join('，') : ''" placeholder="多项用逗号分隔" @input="updateArrayField(activeItem, field.key, ($event.target as HTMLInputElement).value)">
                    <template v-else-if="fieldType(field) === 'object'">
                      <textarea :value="objectFieldText(activeItem, field.key)" rows="5" placeholder="请按示例填写补充内容" @input="updateObjectFieldDraft(activeItem, field.key, ($event.target as HTMLTextAreaElement).value)" @blur="commitObjectField(activeItem, field.key)"></textarea>
                      <small v-if="objectFieldErrors[objectFieldKey(activeItem, field.key)]" class="case-field-error">{{ objectFieldErrors[objectFieldKey(activeItem, field.key)] }}</small>
                    </template>
                    <input v-else v-model="activeItem.form_values[field.key]" type="text">
                  </label>
                </template>
              </div>
            </section>
            <div v-if="itemStepErrors(activeItem, 3).length" class="case-alert warning">{{ itemStepErrors(activeItem, 3).join('；') }}</div>
            <button type="button" class="case-primary" :disabled="busy" data-testid="case-save-item" @click="saveItem(activeItem)">保存当前产品</button>
          </div>
        </div>
      </section>

      <section v-else-if="step === 4" class="case-panel">
        <header><h1>资料上传</h1><p>请上传病例共享资料和各产品所需资料；单个文件最大 500MB。</p></header>
        <section class="case-upload-card shared">
          <header><div><strong>病例共享资料</strong><small>同一病例多个产品共用的影像可只上传一次</small></div><span>{{ sharedFiles.length }} 个</span></header>
          <label><input type="file" multiple :disabled="fileUploading || !group?.items.length" @change="uploadSharedFiles"><b>＋ 上传共享资料</b><small>共享资料仍需在下方相应资料槽位中完成分类</small></label>
          <article v-for="file in sharedFiles" :key="file.file_id"><strong>{{ file.name }}</strong><small>{{ file.size_label }}</small></article>
        </section>
        <div class="case-config-layout upload-layout">
          <aside class="case-item-tabs">
            <button v-for="item in group?.items ?? []" :key="item.order_id" type="button" :class="{ active: activeItem?.order_id === item.order_id }" @click="selectedOrderId = item.order_id">
              <span>{{ item.line_no }}</span><div><strong>{{ item.product_name }}</strong><small>{{ uploadRules(item).filter((rule) => rule.required && !uploadedSlotIds(item, rule.code).length).length }} 项必传待补</small></div>
            </button>
          </aside>
          <section v-if="activeItem" class="case-upload-card">
            <header><div><strong>{{ activeItem.product_name }}</strong><small>{{ CATEGORY_NAMES[productCategory(activeItem)] }} · {{ activeItem.order_no }}</small></div><span>{{ itemFiles[activeItem.order_id]?.length ?? 0 }} 个</span></header>
            <div class="case-upload-slots">
              <label v-for="rule in uploadRules(activeItem)" :key="rule.code" :class="{ complete: uploadedSlotIds(activeItem, rule.code).length }">
                <div><strong>{{ rule.label }}</strong><small>{{ rule.required ? '必传' : '选传' }} · {{ rule.accept }}</small></div>
                <span>{{ uploadedSlotIds(activeItem, rule.code).length ? `已上传 ${uploadedSlotIds(activeItem, rule.code).length} 个` : '尚未上传' }}</span>
                <b>＋ 选择文件<input type="file" multiple :accept="rule.accept" :disabled="fileUploading" @change="uploadProductFiles($event, activeItem, rule.code)"></b>
              </label>
            </div>
          </section>
        </div>
      </section>

      <section v-else-if="step === 5" class="case-panel case-config-panel">
        <header><h1>试戴与过程确认</h1><p>请逐个产品选择是否试戴，以及制作过程中需要确认的内容。</p></header>
        <div class="case-config-layout">
          <aside class="case-item-tabs">
            <button v-for="item in group?.items ?? []" :key="item.order_id" type="button" :class="{ active: activeItem?.order_id === item.order_id }" @click="selectedOrderId = item.order_id">
              <span>{{ item.line_no }}</span><div><strong>{{ item.product_name }}</strong><small>{{ item.order_no }}</small></div>
            </button>
          </aside>
          <section v-if="activeItem" class="case-config-form">
            <DoctorOrthodonticPrescription
              v-if="productCategory(activeItem) === 'CLEAR_ALIGNER'"
              :key="activeItem.order_id"
              :token="props.token"
              :order-id="activeItem.order_id"
              :aligner-types="clearAlignerTypes(activeItem)"
              :related-orders="relatedOrders(activeItem)"
              :initial-treatment-arch="String(activeItem.form_values.treatment_arch ?? '')"
              :initial-treatment-mode="String(activeItem.form_values.treatment_mode ?? '')"
              :initial-records="prescriptionInitialRecords(activeItem)"
              @ready="orthodonticPrescriptionReady[activeItem.order_id] = $event"
              @treatment-selection-change="updateClearAlignerSelection(activeItem, $event)"
            />
            <label class="case-process-option">
              <input v-model="activeItem.form_values.try_in_required" type="checkbox">
              <div><strong>成品完成前需要试戴</strong><p>试戴后医生可在原订单继续选择完成成品；试戴费用待报价，不预填金额。</p></div>
            </label>
            <section class="case-config-block">
              <header><h3>制作过程确认</h3><small>增加确认环节可能影响交期，客服受理时会一并确认</small></header>
              <div class="case-process-list">
                <label v-for="option in [{ value: 'CAD_DESIGN', label: 'CAD 设计确认（制作前）' }, { value: 'POST_MILLING_PHOTOS', label: '切削/打印后照片确认' }, { value: 'POST_GLAZING_PHOTOS', label: '上釉后照片确认（质检前）' }]" :key="option.value">
                  <input type="checkbox" :checked="sourceArray(activeItem, 'process_reviews').includes(option.value)" @change="toggleSourceArray(activeItem, 'process_reviews', option.value, ($event.target as HTMLInputElement).checked)">
                  <span>{{ option.label }}</span>
                </label>
              </div>
            </section>
            <label class="case-field"><span>特殊要求</span><textarea :value="String(activeItem.form_values.special_requirements ?? '')" rows="5" placeholder="补充当前产品的特殊制作要求" @input="updateTextField(activeItem, 'special_requirements', ($event.target as HTMLTextAreaElement).value)"></textarea></label>
            <section class="case-config-block">
              <header><h3>模型寄送信息</h3><small>适用所有产品；只有需要寄送实体模型时填写</small></header>
              <label class="case-process-option">
                <input v-model="activeItem.form_values.physical_model_shipping_required" type="checkbox">
                <div><strong>需要寄送实体模型</strong><p>勾选后填写快递/业务员配送信息，客服可据此跟踪模型到厂。</p></div>
              </label>
              <div v-if="activeItem.form_values.physical_model_shipping_required" class="case-field-grid">
                <label class="case-field"><span>运输方式</span><select v-model="activeItem.form_values.physical_model_shipping_method"><option value="">请选择</option><option value="COURIER">快递</option><option value="SALES_DELIVERY">业务员配送</option><option value="SELF_DELIVERY">自行送达</option></select></label>
                <label class="case-field"><span>运单号 / 配送说明</span><input v-model="activeItem.form_values.physical_model_tracking_no" placeholder="填写运单号或配送联系人"></label>
              </div>
            </section>
            <button type="button" class="case-primary" :disabled="busy" @click="saveItem(activeItem)">保存当前确认要求</button>
          </section>
        </div>
      </section>

      <section v-else class="case-panel">
        <header><h1>报价、要求与周期确认</h1><p>请核对全部产品和资料；正式报价与可行交期将在客服受理后确认。</p></header>
        <div class="case-review-head">
          <div><span>病例订单</span><strong>{{ group?.group_no }}</strong></div>
          <div><span>患者</span><strong>{{ selectedPatient?.patient_name }}</strong></div>
          <div><span>产品数</span><strong>{{ group?.items.length ?? 0 }}</strong></div>
          <div><span>共享资料</span><strong>{{ sharedFiles.length }} 个</strong></div>
        </div>
        <div class="case-review-list">
          <article v-for="item in group?.items ?? []" :key="item.order_id" :class="{ incomplete: itemErrors(item).length }">
            <span>{{ item.line_no }}</span>
            <div><strong>{{ item.product_name }}<em v-if="item.variant_name"> · {{ item.variant_name }}</em></strong><small>{{ item.order_no }} · {{ CATEGORY_NAMES[productCategory(item)] }}</small></div>
            <b>{{ priceLabel(item) }}</b>
            <i>{{ itemFiles[item.order_id]?.length ?? 0 }} 个专属文件</i>
            <em>{{ itemErrors(item).length ? itemErrors(item).join('；') : '配置完整' }}</em>
          </article>
        </div>
        <section class="case-final-confirmations">
          <label><input v-model="finalConfirmations.quote" type="checkbox"><div><strong>报价状态确认</strong><p>当前所有产品均为“待报价”，提交后由客服核价并告知正式报价。</p></div></label>
          <label><input v-model="finalConfirmations.requirements" type="checkbox"><div><strong>制作要求确认</strong><p>我已核对牙位、材料、工艺、资料、试戴及过程确认要求。</p></div></label>
          <label><input v-model="finalConfirmations.cycle" type="checkbox"><div><strong>制作周期确认</strong><p>要求到货日为 {{ caseSettings.required_delivery_date || '未填写' }}；正式工期由客服根据产品和资料完整度确认。</p></div></label>
        </section>
        <div v-if="incompleteItems.length" class="case-alert warning">还有 {{ incompleteItems.length }} 个子产品不完整，请返回对应阶段补齐：{{ incompleteItems.map((item) => item.product_name).join('、') }}。</div>
        <div v-else-if="!finalConfirmationComplete" class="case-alert warning">请完成上面三项确认后提交。</div>
        <div v-else class="case-alert success">信息填写完整，可以提交订单。</div>
      </section>
    </main>

    <footer class="case-wizard__footer">
      <div class="case-footer-context">
        <button v-if="group" type="button" :disabled="busy || fileUploading" @click="saveAllItems()">保存草稿</button>
        <span v-if="group">{{ group.items.length }} 个产品 · 均为待报价</span>
      </div>
      <div>
        <button v-if="step > 1" type="button" :disabled="busy || fileUploading" @click="step--">上一步</button>
        <button v-if="step < 6" type="button" class="case-primary" :disabled="busy || fileUploading" @click="nextStep">下一步 →</button>
        <button v-else type="button" class="case-primary" data-testid="case-submit" :disabled="busy || fileUploading || incompleteItems.length > 0 || !finalConfirmationComplete" @click="submitGroup">{{ busy ? '提交中…' : '提交订单' }}</button>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.case-wizard{position:fixed;inset:12px;z-index:10020;display:grid;grid-template-rows:64px 76px minmax(0,1fr) 68px;overflow:hidden;border:1px solid #d9e3ef;border-radius:18px;background:#f5f8fc;box-shadow:0 24px 80px #0f274133;color:#17243a}.case-wizard__header,.case-wizard__footer{display:flex;align-items:center;justify-content:space-between;padding:0 24px;background:#fff;border-bottom:1px solid #e5ebf2}.case-wizard__header>div{display:flex;align-items:center;gap:12px}.case-wizard__header strong,.case-wizard__header small{display:block}.case-wizard__header strong{font-size:17px}.case-wizard__header small{margin-top:3px;color:#718096}.case-wizard__mark{width:36px;height:36px;display:grid;place-items:center;border-radius:11px;background:#1768e5;color:#fff;font-weight:800}.case-wizard button{cursor:pointer}.case-wizard button:disabled{cursor:not-allowed;opacity:.55}.case-wizard__header>button{border:0;background:#f1f5f9;border-radius:9px;padding:9px 13px;color:#526174}.case-wizard__steps{display:grid;grid-template-columns:repeat(5,1fr);padding:12px 8%;background:#fff;border-bottom:1px solid #e5ebf2}.case-wizard__steps button{position:relative;display:flex;align-items:center;justify-content:center;gap:8px;border:0;background:transparent;color:#94a3b8}.case-wizard__steps button:not(:last-child):after{content:"";position:absolute;right:-25%;width:50%;height:2px;background:#e2e8f0}.case-wizard__steps button.done:not(:last-child):after{background:#2e7cf6}.case-wizard__steps span{width:28px;height:28px;display:grid;place-items:center;border-radius:50%;background:#eef2f7;color:#64748b}.case-wizard__steps .active,.case-wizard__steps .done{color:#1768e5}.case-wizard__steps .active span,.case-wizard__steps .done span{background:#1768e5;color:#fff}.case-wizard>main{min-height:0;overflow:auto;padding:22px}.case-panel{max-width:1320px;margin:auto}.case-panel>header{margin-bottom:18px}.case-panel h1{margin:0;font-size:24px}.case-panel>header p{margin:6px 0 0;color:#718096}.case-search{max-width:520px;height:42px;display:flex;align-items:center;gap:9px;padding:0 12px;border:1px solid #d7e0eb;border-radius:10px;background:#fff}.case-search input{flex:1;border:0;outline:0}.case-patient-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:16px}.case-patient-grid button{display:grid;grid-template-columns:42px 1fr 22px;align-items:center;text-align:left;padding:13px;border:1.5px solid #dce5ef;border-radius:12px;background:#fff}.case-patient-grid button.active{border-color:#2e7cf6;background:#f1f7ff}.case-patient-grid i{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;background:#ddebff;color:#1768e5;font-style:normal;font-weight:800}.case-patient-grid strong,.case-patient-grid small{display:block}.case-patient-grid small{margin-top:3px;color:#8291a7}.case-patient-grid em{color:#1768e5;font-style:normal}.case-product-layout{display:grid;grid-template-columns:minmax(0,1fr) 390px;gap:18px;margin-top:16px}.case-catalog,.case-basket,.case-config-form,.case-item-tabs,.case-upload-card,.case-review-list{border:1px solid #dce5ef;border-radius:14px;background:#fff}.case-catalog{padding:16px}.case-catalog section+section{margin-top:18px}.case-catalog h3{margin:0 0 10px}.case-catalog button{width:100%;display:grid;grid-template-columns:1fr 100px 76px;align-items:center;text-align:left;padding:12px;border:1px solid #e5ebf2;border-radius:10px;background:#fff}.case-catalog button+button{margin-top:8px}.case-catalog strong,.case-catalog small{display:block}.case-catalog small{margin-top:3px;color:#8291a7}.case-catalog span{color:#a46810}.case-catalog b{color:#1768e5;text-align:right}.case-basket{overflow:hidden}.case-basket>header{display:flex;justify-content:space-between;padding:14px;border-bottom:1px solid #e5ebf2}.case-basket article{display:grid;grid-template-columns:1fr auto auto auto;gap:8px;align-items:center;padding:12px;border-bottom:1px solid #edf1f5}.case-basket article strong,.case-basket article small{display:block}.case-basket article small{margin-top:3px;color:#8291a7}.case-basket article>span{color:#a46810}.case-basket article button{border:0;background:#edf5ff;border-radius:7px;padding:6px 8px;color:#1768e5}.case-basket article button.danger{background:#fff0f0;color:#c24141}.case-basket>p{padding:30px;text-align:center;color:#94a3b8}.case-config-layout{display:grid;grid-template-columns:270px minmax(0,1fr);gap:16px}.case-item-tabs{align-self:start;overflow:hidden}.case-item-tabs button{width:100%;display:flex;align-items:center;gap:10px;padding:12px;border:0;border-bottom:1px solid #edf1f5;background:#fff;text-align:left}.case-item-tabs button.active{background:#eef6ff;color:#1768e5}.case-item-tabs button>span{width:28px;height:28px;display:grid;place-items:center;border-radius:8px;background:#edf2f7}.case-item-tabs strong,.case-item-tabs small{display:block}.case-item-tabs small{margin-top:3px}.case-config-form{padding:18px}.case-config-summary,.case-review-head{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:12px;border-radius:11px;background:#f5f8fc}.case-config-summary span,.case-config-summary strong,.case-review-head span,.case-review-head strong{display:block}.case-config-summary span,.case-review-head span{color:#8190a5;font-size:12px}.case-config-summary strong,.case-review-head strong{margin-top:4px}.case-field{display:block;margin-top:14px}.case-field>span{display:block;margin-bottom:6px;color:#526174}.case-field b{color:#dc2626}.case-field input,.case-field select,.case-field textarea{width:100%;box-sizing:border-box;border:1px solid #d6e0eb;border-radius:8px;padding:9px;background:#fff}.case-field select[multiple]{min-height:108px}.case-field-error{display:block;margin-top:5px;color:#b42318}.case-config-block{margin-top:18px;padding-top:15px;border-top:1px solid #e5ebf2}.case-config-block>header{display:flex;justify-content:space-between;align-items:end}.case-config-block h3{margin:0}.case-config-block header small{color:#8291a7}.case-option{display:grid;grid-template-columns:22px 1fr auto 80px;gap:10px;align-items:center;margin-top:9px;padding:10px;border:1px solid #e4eaf1;border-radius:9px}.case-option>span strong,.case-option>span small{display:block}.case-option>span small{margin-top:2px;color:#8291a7}.case-option em{font-style:normal;color:#7a8799}.case-option>input[type=number]{width:72px;border:1px solid #d6e0eb;border-radius:7px;padding:6px}.case-field-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 14px}.case-field.full{grid-column:1/-1}.case-switch{display:flex!important;align-items:center;gap:8px}.case-switch input{width:auto}.case-alert{margin-top:14px;padding:11px 13px;border-radius:9px}.case-alert.warning{border:1px solid #f4cf84;background:#fff8e8;color:#8a5a08}.case-alert.success{border:1px solid #a9dfc3;background:#eefbf4;color:#147647}.case-primary{border:0!important;border-radius:9px!important;background:#1768e5!important;color:#fff!important;padding:10px 16px!important}.case-upload-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:14px}.case-upload-card{padding:14px}.case-upload-card>header{display:flex;justify-content:space-between}.case-upload-card header strong,.case-upload-card header small{display:block}.case-upload-card header small{margin-top:3px;color:#8291a7}.case-upload-card>label{display:block;margin-top:12px;padding:18px;border:1.5px dashed #9dbce8;border-radius:10px;background:#f4f8ff;text-align:center}.case-upload-card>label input{display:none}.case-upload-card>label b,.case-upload-card>label small{display:block}.case-upload-card>label b{color:#1768e5}.case-upload-card>label small{margin-top:5px;color:#8291a7}.case-upload-card>article{display:flex;justify-content:space-between;padding:9px 3px;border-bottom:1px solid #edf1f5}.case-upload-card article small{color:#8291a7}.case-review-head{grid-template-columns:repeat(4,1fr);margin-bottom:14px}.case-review-list{overflow:hidden}.case-review-list article{display:grid;grid-template-columns:34px 1fr 110px 110px minmax(150px,1fr);gap:10px;align-items:center;padding:13px;border-bottom:1px solid #edf1f5}.case-review-list article>span{width:28px;height:28px;display:grid;place-items:center;border-radius:8px;background:#edf5ff;color:#1768e5}.case-review-list strong,.case-review-list small{display:block}.case-review-list small{margin-top:3px;color:#8291a7}.case-review-list article>b{color:#a46810}.case-review-list article>i{font-style:normal;color:#526174}.case-review-list article>em{font-style:normal;color:#167547}.case-review-list article.incomplete>em{color:#b45309}.case-wizard__footer{border-top:1px solid #e5ebf2;border-bottom:0}.case-wizard__footer button{border:1px solid #d6e0eb;border-radius:9px;background:#fff;padding:9px 15px}.case-wizard__footer>div{display:flex;gap:9px}@media(max-width:1000px){.case-wizard{inset:0;border-radius:0}.case-patient-grid{grid-template-columns:1fr 1fr}.case-product-layout,.case-config-layout{grid-template-columns:1fr}.case-basket{order:-1}.case-upload-grid{grid-template-columns:1fr}.case-wizard__steps{padding:10px 2%}.case-wizard__steps strong{font-size:11px}.case-review-list article{grid-template-columns:30px 1fr 90px}.case-review-list article>i,.case-review-list article>em{grid-column:2/-1}.case-review-head{grid-template-columns:1fr 1fr}}@media(max-width:640px){.case-wizard{grid-template-rows:58px 64px minmax(0,1fr) 60px}.case-wizard__header{padding:0 12px}.case-wizard__header small{display:none}.case-wizard__steps strong{display:none}.case-patient-grid,.case-field-grid{grid-template-columns:1fr}.case-panel h1{font-size:20px}.case-wizard>main{padding:14px}.case-config-summary,.case-review-head{grid-template-columns:1fr}.case-catalog button{grid-template-columns:1fr}.case-catalog button>span,.case-catalog button>b{margin-top:5px;text-align:left}}
.case-tooth-chart{grid-column:1/-1;margin-top:14px;padding:14px;border:1px solid #d6e0eb;border-radius:12px;background:#f8fbff}.case-tooth-chart>header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:12px}.case-tooth-chart>header strong,.case-tooth-chart>header small{display:block}.case-tooth-chart>header small,.case-tooth-chart>small{margin-top:3px;color:#718096}.case-tooth-chart>header>div:last-child{display:flex;align-items:center;gap:9px;color:#526174}.case-tooth-chart>header button{border:1px solid #d6e0eb;border-radius:7px;background:#fff;padding:5px 9px;color:#526174}.case-tooth-row{display:grid;grid-template-columns:repeat(16,minmax(32px,1fr));gap:6px;margin:6px 0 11px}.case-tooth-row button{min-height:38px;border:1px solid #cbd7e5;border-radius:9px;background:#fff;color:#334155;font-weight:700}.case-tooth-row button:hover{border-color:#6aa5f8;background:#f0f6ff}.case-tooth-row button.selected{border-color:#1768e5;background:#1768e5;color:#fff;box-shadow:0 3px 9px #1768e533}@media(max-width:900px){.case-tooth-row{grid-template-columns:repeat(8,minmax(32px,1fr))}.case-tooth-chart>header{align-items:flex-start;flex-direction:column}.case-tooth-chart>header>div:last-child{width:100%;justify-content:space-between}}@media(max-width:520px){.case-tooth-row{grid-template-columns:repeat(4,minmax(36px,1fr))}}
.case-wizard{inset:0;border-radius:0}.case-wizard__header{background:#0d2c61;color:#fff}.case-wizard__header small{color:#c9d7ec}.case-wizard__header>button{background:transparent;color:#fff}.case-legacy-catalog{max-width:1380px}.case-legacy-catalog-layout{display:grid;grid-template-columns:270px minmax(0,1fr);min-height:650px;border:1px solid #dce5ef;border-radius:14px;background:#fff;overflow:hidden}.case-category-menu{border-right:1px solid #dce5ef;background:#fbfcfe}.case-category-menu>header{padding:22px 18px;border-bottom:1px solid #e5ebf2}.case-category-menu>header small,.case-category-menu>header strong{display:block}.case-category-menu>header small{color:#8291a7}.case-category-menu>header strong{margin-top:5px}.case-category-menu>button{width:100%;display:grid;grid-template-columns:38px 1fr 18px;gap:10px;align-items:center;padding:16px;border:0;border-bottom:1px solid #edf1f5;background:transparent;text-align:left;color:#334155}.case-category-menu>button.active{border-left:4px solid #1768e5;background:#edf5ff;color:#1768e5}.case-category-menu>button>span{font-size:22px;text-align:center}.case-category-menu>button strong,.case-category-menu>button small{display:block}.case-category-menu>button small{margin-top:3px;color:#8291a7}.case-category-menu>button i{font-style:normal}.case-legacy-catalog-main{padding:22px}.case-legacy-catalog-main>header{display:flex;align-items:center;justify-content:space-between;padding-bottom:17px;border-bottom:1px solid #e5ebf2}.case-legacy-catalog-main>header>div{display:flex;align-items:center;gap:12px}.case-legacy-catalog-main>header>div>span{width:46px;height:46px;display:grid;place-items:center;border-radius:12px;background:#edf5ff;color:#1768e5;font-size:24px}.case-legacy-catalog-main h1,.case-legacy-catalog-main p{margin:0}.case-legacy-catalog-main p,.case-legacy-catalog-main>header>small{color:#8291a7}.case-wizard-first-grid{display:grid;grid-template-columns:1fr 1fr;gap:20px;margin-top:18px}.case-wizard-first-grid h3{margin:0 0 10px}.case-wizard-first-grid .case-search{max-width:none}.case-choice-list,.case-product-choice{display:grid;gap:9px;max-height:430px;margin-top:12px;overflow:auto;padding-right:4px}.case-choice-list>button,.case-product-choice>button{display:grid;grid-template-columns:42px 1fr 22px;gap:10px;align-items:center;min-height:68px;padding:11px 13px;border:1.5px solid #dce5ef;border-radius:11px;background:#fff;text-align:left;color:#334155}.case-choice-list>button.active,.case-product-choice>button.active{border-color:#2e7cf6;background:#f1f7ff}.case-choice-list>button>i{width:34px;height:34px;display:grid;place-items:center;border-radius:50%;background:#ddebff;color:#1768e5;font-style:normal;font-weight:800}.case-choice-list strong,.case-choice-list small,.case-product-choice strong,.case-product-choice small,.case-product-choice p{display:block}.case-choice-list small,.case-product-choice small{margin-top:3px;color:#8291a7}.case-choice-list em,.case-product-choice i{color:#1768e5;font-style:normal;font-weight:800}.case-product-choice>button>span{width:36px;height:36px;display:grid;place-items:center;border-radius:9px;background:#edf5ff;color:#1768e5;font-size:18px}.case-product-choice p{margin:4px 0 0;color:#a46810}.case-basket-inline{margin-top:18px}.case-basket-inline article{grid-template-columns:1fr 90px 52px 52px}@media(max-width:1000px){.case-legacy-catalog-layout{grid-template-columns:1fr}.case-category-menu{display:grid;grid-template-columns:repeat(3,1fr);border-right:0}.case-category-menu>header{grid-column:1/-1}.case-wizard-first-grid{grid-template-columns:1fr}.case-choice-list,.case-product-choice{max-height:300px}}@media(max-width:640px){.case-category-menu{grid-template-columns:1fr 1fr}.case-legacy-catalog-main{padding:14px}}
.case-wizard__steps{grid-template-columns:repeat(6,1fr);padding-left:4%;padding-right:4%}.case-source-step{max-width:1380px}.case-account-card{display:grid;grid-template-columns:48px 1fr auto;gap:13px;align-items:center;padding:16px 18px;border:1px solid #bcd7fb;border-radius:14px;background:#edf6ff}.case-account-card>span{width:46px;height:46px;display:grid;place-items:center;border-radius:50%;background:#1768e5;color:#fff;font-weight:800}.case-account-card small,.case-account-card strong,.case-account-card p{display:block;margin:0}.case-account-card small{color:#65809f}.case-account-card strong{margin-top:3px}.case-account-card p{margin-top:3px;color:#718096}.case-account-card b{color:#16805d}.case-source-grid{display:grid;grid-template-columns:1fr 1.25fr;gap:16px;margin-top:16px}.case-section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}.case-section-title small,.case-section-title h3{display:block;margin:0}.case-section-title small{color:#64748b}.case-section-title h3{margin-top:3px}.case-section-title button{border:1px solid #bdd5f5;border-radius:8px;background:#f1f7ff;padding:7px 10px;color:#1768e5}.case-choice-list.compact{max-height:270px}.case-new-patient{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin:12px 0;padding:13px;border:1px solid #c9ddf7;border-radius:10px;background:#f5f9ff}.case-new-patient label span,.case-new-patient label input,.case-new-patient label select,.case-new-patient label textarea{display:block;width:100%;box-sizing:border-box}.case-new-patient label span{margin-bottom:5px;color:#526174}.case-new-patient label input,.case-new-patient label select,.case-new-patient label textarea{border:1px solid #d6e0eb;border-radius:8px;padding:8px}.case-new-patient .full{grid-column:1/-1}.case-catalog-source{margin-top:16px;padding:17px;border:1px solid #dce5ef;border-radius:14px;background:#fff}.case-catalog-source>header{display:flex;align-items:end;justify-content:space-between;gap:20px}.case-catalog-source>header small,.case-catalog-source>header h3{display:block;margin:0}.case-catalog-source>header small{color:#64748b}.case-catalog-source>header h3{margin-top:3px}.case-category-strip{display:grid;grid-template-columns:repeat(6,1fr);gap:8px;margin-top:14px}.case-category-strip button{display:grid;grid-template-columns:32px 1fr;gap:3px 8px;align-items:center;padding:11px;border:1px solid #dce5ef;border-radius:10px;background:#fff;text-align:left}.case-category-strip button.active{border-color:#1768e5;background:#edf5ff;color:#1768e5}.case-category-strip button>span{grid-row:1/3;font-size:22px}.case-category-strip button small{color:#8291a7}.case-source-products{margin-top:14px}.case-source-products section+section{margin-top:15px}.case-source-products h4{margin:0 0 8px;color:#334155}.case-source-products section>div{display:grid;grid-template-columns:repeat(3,1fr);gap:9px}.case-source-products section>div>button{display:grid;grid-template-columns:38px 1fr auto 20px;gap:9px;align-items:center;min-height:68px;padding:11px;border:1px solid #dce5ef;border-radius:10px;background:#fff;text-align:left}.case-source-products section>div>button.active{border-color:#1768e5;background:#edf5ff}.case-source-products button>span{font-size:20px}.case-source-products button small{display:block;margin-top:3px;color:#8291a7}.case-source-products button b{color:#a46810}.case-source-products button i{color:#1768e5;font-style:normal;font-weight:800}.case-radio-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:9px;margin-top:12px}.case-radio-grid label{display:flex;align-items:center;gap:9px;padding:11px;border:1px solid #dce5ef;border-radius:9px}.case-radio-grid label.active{border-color:#1768e5;background:#edf5ff}.case-radio-grid input{width:auto}.case-check-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:8px}.case-check-grid label{display:flex;align-items:center;gap:7px;padding:9px;border:1px solid #e1e8f0;border-radius:8px;background:#fff}.case-tooth-modes{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}.case-tooth-modes button{border:1px solid #cbd7e5;border-radius:8px;background:#fff;padding:7px 12px}.case-tooth-modes button.active{border-color:#1768e5;background:#1768e5;color:#fff}.case-arch-label{display:block!important;width:100%;border:0!important;background:transparent!important;text-align:left;color:#526174!important}.case-tooth-row button{min-height:86px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;padding:4px 2px}.case-tooth-row button .case-tooth-shape{font-size:24px;filter:grayscale(1);opacity:.6}.case-tooth-row button small{font-size:11px}.case-tooth-row button em{font-size:9px;font-style:normal}.case-tooth-row button.selected .case-tooth-shape{filter:none;opacity:1}.case-tooth-row button.mode-BRIDGE,.case-tooth-row button.mode-FRAMEWORK{background:#f59e0b;border-color:#d97706}.case-tooth-row button.mode-CLASP,.case-tooth-row button.mode-BAND,.case-tooth-row button.mode-ABUTMENT{background:#7c3aed;border-color:#6d28d9}.case-upload-card.shared{margin-bottom:14px}.upload-layout{margin-top:14px}.case-upload-slots{display:grid;gap:9px;margin-top:12px}.case-upload-slots>label{display:grid;grid-template-columns:1fr auto auto;gap:12px;align-items:center;padding:12px;border:1px solid #e2e8f0;border-radius:10px}.case-upload-slots>label.complete{border-color:#9bd4b8;background:#f0fbf5}.case-upload-slots strong,.case-upload-slots small{display:block}.case-upload-slots small{margin-top:3px;color:#718096}.case-upload-slots>label>span{color:#a15c09}.case-upload-slots>label.complete>span{color:#147647}.case-upload-slots b{position:relative;border-radius:8px;background:#edf5ff;padding:8px 10px;color:#1768e5}.case-upload-slots b input{position:absolute;inset:0;opacity:0;cursor:pointer}.case-process-option,.case-process-list label,.case-final-confirmations label{display:flex;align-items:flex-start;gap:12px;padding:14px;border:1px solid #dce5ef;border-radius:11px;background:#fff}.case-process-option input,.case-process-list input,.case-final-confirmations input{width:auto;margin-top:4px}.case-process-option strong,.case-process-option p,.case-final-confirmations strong,.case-final-confirmations p{display:block;margin:0}.case-process-option p,.case-final-confirmations p{margin-top:4px;color:#718096}.case-process-list{display:grid;gap:9px;margin-top:10px}.case-final-confirmations{display:grid;gap:10px;margin-top:16px}.case-final-confirmations label:has(input:checked){border-color:#75c79e;background:#f0fbf5}@media(max-width:1000px){.case-source-grid{grid-template-columns:1fr}.case-category-strip{grid-template-columns:repeat(3,1fr)}.case-source-products section>div{grid-template-columns:repeat(2,1fr)}.case-check-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:640px){.case-category-strip,.case-source-products section>div,.case-radio-grid,.case-check-grid,.case-new-patient{grid-template-columns:1fr}.case-catalog-source>header{align-items:stretch;flex-direction:column}.case-upload-slots>label{grid-template-columns:1fr}.case-account-card{grid-template-columns:42px 1fr}.case-account-card b{grid-column:2}}

/* Saved doctor-portal.html visual parity layer. Keep business markup and data semantics unchanged. */
.case-wizard {
  --case-white: #fff;
  --case-off: #f7f9fc;
  --case-off-2: #eef2f8;
  --case-blue-50: #eff6ff;
  --case-blue-100: #dbeafe;
  --case-blue-200: #bfdbfe;
  --case-blue-400: #60a5fa;
  --case-blue-600: #2563eb;
  --case-blue-700: #1d4ed8;
  --case-navy: #0f2554;
  --case-text: #0f172a;
  --case-muted: #475569;
  --case-faint: #94a3b8;
  --case-border: #e2e8f0;
  --case-border-strong: #cbd5e1;
  inset: 0;
  grid-template-rows: 56px 43px minmax(0, 1fr) 60px;
  border: 0;
  border-radius: 0;
  color: var(--case-text);
  font-family: "Plus Jakarta Sans", Inter, "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 13px;
  background: var(--case-white);
  box-shadow: none;
}

.case-wizard__header {
  padding: 0 28px;
  border: 0;
  color: #fff;
  background: var(--case-navy);
}

.case-wizard__header > div {
  gap: 0;
  min-width: 0;
}

.case-wizard__mark {
  display: none;
}

.case-wizard__header strong {
  font-family: Lora, Georgia, "Songti SC", serif;
  font-size: 15px;
  font-weight: 600;
}

.case-wizard__header small {
  margin-top: 2px;
  overflow: hidden;
  color: rgba(255, 255, 255, .5);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-wizard__header > button {
  display: grid;
  width: 32px;
  height: 32px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 8px;
  color: #fff;
  font-size: 18px;
  line-height: 1;
  background: rgba(255, 255, 255, .1);
}

.case-wizard__header > button:hover {
  background: rgba(255, 255, 255, .18);
}

.case-wizard__steps {
  display: flex;
  align-items: stretch;
  justify-content: flex-start;
  gap: 0;
  padding: 0 28px;
  overflow-x: auto;
  border-bottom: 1.5px solid var(--case-border);
  background: var(--case-off);
}

.case-wizard__steps button {
  position: relative;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  padding: 10px 16px 9px;
  border: 0;
  border-bottom: 2px solid transparent;
  border-radius: 0;
  color: var(--case-faint);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  background: transparent;
}

.case-wizard__steps button:not(:last-child)::after {
  display: none;
}

.case-wizard__steps span {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: #64748b;
  font-size: 9px;
  font-weight: 700;
  background: var(--case-border);
}

.case-wizard__steps .active {
  border-bottom-color: var(--case-blue-600);
  color: var(--case-blue-600);
}

.case-wizard__steps .active span {
  color: #fff;
  background: var(--case-blue-600);
}

.case-wizard__steps .done {
  color: #059669;
}

.case-wizard__steps .done span {
  color: #fff;
  background: #059669;
}

.case-wizard > main {
  padding: 24px 32px;
  background: var(--case-white);
}

.case-panel {
  max-width: 1380px;
}

.case-panel > header {
  margin-bottom: 20px;
}

.case-panel h1 {
  font-family: Lora, Georgia, "Songti SC", serif;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -.2px;
}

.case-panel > header p {
  margin-top: 5px;
  color: var(--case-muted);
  font-size: 12px;
}

.case-config-form,
.case-catalog-source,
.case-basket,
.case-item-tabs,
.case-upload-card,
.case-review-list {
  border: 1.5px solid var(--case-border);
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(15, 37, 84, .06), 0 1px 2px rgba(15, 37, 84, .04);
}

.case-config-form {
  padding: 18px 20px;
}

.case-account-card {
  padding: 14px 18px;
  border: 1.5px solid var(--case-blue-200);
  border-radius: 10px;
  background: var(--case-blue-50);
}

.case-recommend-card {
  margin-top: 14px;
  padding: 14px 16px;
  border: 1px solid #e9d5ff;
  border-radius: 12px;
  background: linear-gradient(180deg, #fff, #faf5ff);
}

.case-recommend-card > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.case-recommend-card > header strong,
.case-recommend-card > header small {
  display: block;
}

.case-recommend-card > header small {
  margin-top: 3px;
  color: #7c6f95;
}

.case-recommend-card > header button {
  flex: none;
  padding: 7px 12px;
  border: 1px solid #c4b5fd;
  border-radius: 8px;
  background: #f5f3ff;
  color: #6d28d9;
  font-weight: 700;
}

.case-recommend-input {
  display: block;
  margin-top: 12px;
}

.case-recommend-input span {
  display: block;
  margin-bottom: 5px;
  color: #526174;
}

.case-recommend-input input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px;
  border: 1px solid #d6e0eb;
  border-radius: 8px;
}

.case-recommend-note,
.case-recommend-error {
  margin: 10px 0 0;
  font-size: 12px;
}

.case-recommend-note {
  color: #7c6f95;
}

.case-recommend-error {
  color: #dc2626;
}

.case-recommend-list {
  display: grid;
  gap: 9px;
  margin-top: 12px;
}

.case-recommend-list button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 11px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  text-align: left;
}

.case-recommend-list button.selected {
  border-color: #a78bfa;
  background: #f5f3ff;
}

.case-recommend-list button small {
  display: block;
  margin-top: 3px;
  color: #8291a7;
}

.case-recommend-list button i {
  flex: none;
  color: #6d28d9;
  font-style: normal;
  font-weight: 800;
}

.case-account-card > span {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: var(--case-blue-600);
}

.case-account-card b {
  padding: 3px 9px;
  border: 1px solid #a7f3d0;
  border-radius: 20px;
  color: #059669;
  font-size: 10px;
  background: #ecfdf5;
}

.case-source-grid {
  gap: 18px;
  margin-top: 18px;
}

.case-source-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  min-height: calc(100vh - 203px);
  overflow: hidden;
  border: 1.5px solid var(--case-border);
  border-radius: 10px;
  background: var(--case-white);
  box-shadow: 0 1px 3px rgba(15, 37, 84, .06), 0 1px 2px rgba(15, 37, 84, .04);
}

.case-source-sidebar {
  align-self: stretch;
  border-right: 1.5px solid var(--case-border);
  background: var(--case-off);
}

.case-source-sidebar > header {
  padding: 18px 16px 14px;
  border-bottom: 1.5px solid var(--case-border);
}

.case-source-sidebar > header small,
.case-source-sidebar > header strong {
  display: block;
}

.case-source-sidebar > header small {
  color: var(--case-faint);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: .8px;
  text-transform: uppercase;
}

.case-source-sidebar > header strong {
  margin-top: 4px;
  font-size: 12px;
}

.case-source-sidebar > button {
  display: grid;
  width: 100%;
  grid-template-columns: 36px 1fr 16px;
  align-items: center;
  gap: 9px;
  min-height: 66px;
  padding: 11px 14px;
  border: 0;
  border-bottom: 1px solid var(--case-off-2);
  color: var(--case-muted);
  text-align: left;
  background: transparent;
  transition: color .15s, background .15s, box-shadow .15s;
}

.case-source-sidebar > button:hover {
  color: var(--case-blue-600);
  background: var(--case-blue-50);
}

.case-source-sidebar > button.active {
  color: var(--case-blue-600);
  background: var(--case-blue-50);
  box-shadow: inset 3px 0 var(--case-blue-600);
}

.case-source-sidebar > button > span {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 8px;
  font-size: 19px;
  background: var(--case-white);
}

.case-source-sidebar > button strong,
.case-source-sidebar > button small {
  display: block;
}

.case-source-sidebar > button strong {
  font-size: 12px;
}

.case-source-sidebar > button small {
  margin-top: 3px;
  color: var(--case-faint);
  font-size: 10px;
}

.case-source-sidebar > button i {
  color: currentColor;
  font-style: normal;
  font-weight: 700;
}

.case-source-content {
  min-width: 0;
  padding: 22px 24px 24px;
}

.case-source-intro {
  margin-bottom: 18px;
}

.case-source-intro h1,
.case-source-intro p {
  margin: 0;
}

.case-source-intro p {
  margin-top: 5px;
  color: var(--case-muted);
  font-size: 12px;
}

.case-section-title {
  padding-bottom: 9px;
  border-bottom: 1.5px solid var(--case-blue-100);
}

.case-section-title small {
  color: var(--case-blue-700);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .5px;
  text-transform: uppercase;
}

.case-section-title h3,
.case-config-block h3,
.case-catalog-source h3 {
  font-size: 13px;
  font-weight: 600;
}

.case-section-title button,
.case-tooth-chart button,
.case-tooth-modes button {
  border: 1.5px solid var(--case-blue-200);
  border-radius: 7px;
  color: var(--case-blue-600);
  font-size: 11px;
  font-weight: 600;
  background: var(--case-blue-50);
}

.case-search {
  height: 38px;
  border: 1.5px solid var(--case-border);
  border-radius: 8px;
  background: var(--case-off);
}

.case-search:focus-within {
  border-color: var(--case-blue-400);
  background: var(--case-white);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .1);
}

.case-choice-list > button,
.case-product-choice > button {
  min-height: 58px;
  padding: 9px 12px;
  border: 1.5px solid var(--case-border);
  border-radius: 8px;
}

.case-choice-list > button:hover,
.case-product-choice > button:hover,
.case-category-strip button:hover,
.case-source-products section > div > button:hover {
  border-color: var(--case-blue-400);
  background: var(--case-blue-50);
}

.case-choice-list > button.active,
.case-product-choice > button.active,
.case-category-strip button.active,
.case-source-products section > div > button.active {
  border-color: var(--case-blue-500, #3b82f6);
  background: var(--case-blue-50);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .08);
}

.case-field > span,
.case-new-patient label > span {
  margin-bottom: 5px;
  color: var(--case-muted);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .5px;
  text-transform: uppercase;
}

.case-field input,
.case-field select,
.case-field textarea,
.case-new-patient label input,
.case-new-patient label select,
.case-new-patient label textarea {
  min-height: 36px;
  border: 1.5px solid var(--case-border);
  border-radius: 8px;
  color: var(--case-text);
  background: var(--case-off);
  outline: none;
  transition: border-color .18s, box-shadow .18s, background .18s;
}

.case-field textarea,
.case-new-patient label textarea {
  min-height: 72px;
}

.case-field input:focus,
.case-field select:focus,
.case-field textarea:focus,
.case-new-patient label input:focus,
.case-new-patient label select:focus,
.case-new-patient label textarea:focus {
  border-color: var(--case-blue-400);
  background: var(--case-white);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .1);
}

.case-catalog-source {
  margin-top: 18px;
  padding: 18px 20px;
}

.case-catalog-source > header {
  padding-bottom: 12px;
  border-bottom: 1.5px solid var(--case-blue-100);
}

.case-category-strip {
  gap: 7px;
  margin-top: 12px;
}

.case-category-strip button,
.case-source-products section > div > button {
  border-width: 1.5px;
  border-color: var(--case-border);
  border-radius: 8px;
  transition: border-color .15s, background .15s, box-shadow .15s;
}

.case-category-strip button {
  min-height: 58px;
  padding: 9px 11px;
}

.case-source-products section > div > button {
  min-height: 60px;
  padding: 9px 11px;
}

.case-basket-inline {
  margin-top: 18px;
}

.case-basket > header {
  padding: 11px 14px;
  background: var(--case-off);
}

.case-basket article {
  padding: 10px 14px;
}

.case-alert {
  border-radius: 8px;
  font-size: 11px;
}

.case-alert.warning {
  border: 1px solid #fde68a;
  color: #b45309;
  background: #fffbeb;
}

.case-config-layout {
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 18px;
}

.case-item-tabs button {
  padding: 10px 12px;
}

.case-item-tabs button.active {
  color: var(--case-blue-600);
  background: var(--case-blue-50);
  box-shadow: inset 3px 0 var(--case-blue-600);
}

.case-config-summary,
.case-review-head {
  border: 1px solid var(--case-border);
  border-radius: 8px;
  background: var(--case-off);
}

.case-config-block {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1.5px solid var(--case-off-2);
}

.case-config-block > header {
  padding-bottom: 8px;
  border-bottom: 1.5px solid var(--case-blue-100);
}

.case-tooth-chart {
  border: 1.5px solid var(--case-border);
  border-radius: 10px;
  background: var(--case-white);
}

.case-tooth-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 2px 0 4px;
  color: var(--case-muted);
  font-size: 11px;
}

.case-tooth-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.case-tooth-legend i {
  width: 13px;
  height: 13px;
  box-sizing: border-box;
  border: 1.5px solid #3b82f6;
  border-radius: 3px;
  background: #dbeafe;
}

.case-tooth-legend i.is-bridge {
  border-color: #f59e0b;
  background: #fef3c7;
}

.case-tooth-legend i.is-special {
  border-color: #7c3aed;
  background: #ede9fe;
}

.case-dental-svg {
  display: block;
  width: 100%;
  min-width: 560px;
  user-select: none;
  touch-action: none;
}

.case-dental-jaw-title {
  fill: var(--case-faint);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .8px;
}

.case-dental-midline {
  stroke: var(--case-border);
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.case-dental-occlusion {
  stroke: var(--case-border);
  stroke-width: .5;
}

.case-dental-side {
  fill: var(--case-faint);
  font-size: 9px;
}

.case-svg-tooth .tooth-body {
  fill: #fff;
  stroke: #c0ccdf;
  stroke-width: 1;
  stroke-linejoin: round;
  transition: fill .15s, stroke .15s, stroke-width .15s;
}

.case-svg-tooth .case-tooth-junction {
  stroke: #c0ccdf;
  stroke-width: .5;
  opacity: .4;
}

.case-svg-tooth .case-tooth-number {
  fill: var(--case-faint);
  font-size: 9px;
  font-weight: 400;
  pointer-events: none;
}

.case-svg-tooth:hover .tooth-body {
  fill: var(--case-blue-100);
  stroke: var(--case-blue-400);
}

.case-svg-tooth.mode-CROWN .tooth-body,
.case-svg-tooth.mode-ORTHO_AREA .tooth-body {
  fill: #dbeafe;
  stroke: #3b82f6;
  stroke-width: 1.8;
}

.case-svg-tooth.mode-CROWN .case-tooth-junction,
.case-svg-tooth.mode-ORTHO_AREA .case-tooth-junction {
  stroke: #3b82f6;
}

.case-svg-tooth.mode-CROWN .case-tooth-number,
.case-svg-tooth.mode-ORTHO_AREA .case-tooth-number {
  fill: #1d4ed8;
  font-weight: 600;
}

.case-svg-tooth.mode-BRIDGE .tooth-body,
.case-svg-tooth.mode-FRAMEWORK .tooth-body {
  fill: #fef3c7;
  stroke: #f59e0b;
  stroke-width: 1.8;
}

.case-svg-tooth.mode-BRIDGE .case-tooth-junction,
.case-svg-tooth.mode-FRAMEWORK .case-tooth-junction {
  stroke: #f59e0b;
}

.case-svg-tooth.mode-BRIDGE .case-tooth-number,
.case-svg-tooth.mode-FRAMEWORK .case-tooth-number {
  fill: #92400e;
  font-weight: 600;
}

.case-svg-tooth.mode-MISSING .tooth-body {
  fill: #fff1f2;
  stroke: #e11d48;
  stroke-width: 1.8;
}

.case-svg-tooth.mode-MISSING .case-tooth-junction {
  stroke: #e11d48;
}

.case-svg-tooth.mode-MISSING .case-tooth-number {
  fill: #be123c;
  font-weight: 600;
}

.case-svg-tooth.mode-CLASP .tooth-body,
.case-svg-tooth.mode-BAND .tooth-body,
.case-svg-tooth.mode-ABUTMENT .tooth-body {
  fill: #ede9fe;
  stroke: #7c3aed;
  stroke-width: 1.8;
}

.case-svg-tooth.mode-CLASP .case-tooth-junction,
.case-svg-tooth.mode-BAND .case-tooth-junction,
.case-svg-tooth.mode-ABUTMENT .case-tooth-junction {
  stroke: #7c3aed;
}

.case-svg-tooth.mode-CLASP .case-tooth-number,
.case-svg-tooth.mode-BAND .case-tooth-number,
.case-svg-tooth.mode-ABUTMENT .case-tooth-number {
  fill: #6d28d9;
  font-weight: 600;
}

.case-tooth-hit {
  cursor: pointer;
  fill: transparent;
}

.case-tooth-summary {
  min-height: 34px;
  box-sizing: border-box;
  margin-top: 8px;
  padding: 8px 14px;
  border: 1.5px solid var(--case-blue-200);
  border-radius: 8px;
  color: var(--case-muted);
  font-size: 12px;
  background: var(--case-blue-50);
}

.case-upload-card > label {
  border: 2px dashed var(--case-blue-200);
  border-radius: 10px;
  background: var(--case-blue-50);
}

.case-upload-card > label:hover {
  border-color: var(--case-blue-400);
  background: var(--case-blue-100);
}

.case-process-option,
.case-process-list label,
.case-final-confirmations label,
.case-check-grid label,
.case-radio-grid label,
.case-option,
.case-upload-slots > label {
  border: 1.5px solid var(--case-border);
  border-radius: 8px;
}

.case-wizard__footer {
  padding: 0 28px;
  border-top: 1.5px solid var(--case-border);
  background: var(--case-white);
}

.case-wizard__footer button {
  min-height: 36px;
  padding: 8px 18px;
  border: 1.5px solid var(--case-border-strong);
  border-radius: 9px;
  color: var(--case-text);
  font-size: 12px;
  font-weight: 600;
  background: var(--case-white);
}

.case-wizard__footer .case-primary {
  border-color: var(--case-blue-600) !important;
  background: var(--case-blue-600) !important;
  box-shadow: 0 2px 8px rgba(37, 99, 235, .25);
}

.case-wizard__footer .case-primary:hover {
  background: var(--case-blue-700) !important;
  transform: translateY(-1px);
}

@media (max-width: 1000px) {
  .case-wizard > main {
    padding: 20px;
  }

  .case-wizard__steps {
    padding: 0 12px;
  }

  .case-wizard__steps button {
    padding-right: 12px;
    padding-left: 12px;
  }

  .case-source-layout {
    grid-template-columns: 1fr;
  }

  .case-source-sidebar {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    border-right: 0;
    border-bottom: 1.5px solid var(--case-border);
  }

  .case-source-sidebar > header {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .case-wizard {
    grid-template-rows: 56px 43px minmax(0, 1fr) 60px;
  }

  .case-wizard__header {
    padding: 0 14px;
  }

  .case-wizard__steps strong {
    display: inline;
  }

  .case-wizard > main {
    padding: 14px;
  }

  .case-source-sidebar {
    grid-template-columns: repeat(2, 1fr);
  }

  .case-source-content {
    padding: 16px;
  }
}

/* Pixel-accurate new-order layout from the saved doctor portal reference. */
.case-wizard__header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 32px;
  align-items: center;
  gap: 16px;
}

.case-wizard__header > strong {
  overflow: hidden;
  color: #fff;
  font-family: Lora, Georgia, "Songti SC", serif;
  font-size: 15px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-wizard__header > small {
  margin: 0;
  color: rgba(255, 255, 255, .5);
  font-size: 12px;
  white-space: nowrap;
}

.case-wizard > main {
  padding: 0;
  background: #fff;
}

.case-panel:not(.case-source-step) {
  padding: 24px 32px;
}

.case-source-step {
  width: 100%;
  max-width: none;
  height: 100%;
  margin: 0;
}

.case-source-layout {
  width: 100%;
  height: 100%;
  min-height: 0;
  grid-template-columns: 260px minmax(0, 1fr);
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.case-source-sidebar {
  min-height: 0;
  overflow-y: auto;
  border-right: 1.5px solid #e2e8f0;
  background: #f7f9fc;
}

.case-sidebar-section {
  padding: 14px 12px;
  border-bottom: 1.5px solid #e2e8f0;
}

.case-sidebar-section > header,
.case-sidebar-patient > header {
  margin-bottom: 8px;
  color: #94a3b8;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .8px;
  text-transform: uppercase;
}

.case-sidebar-section > header b {
  color: #e11d48;
}

.case-category-cards {
  display: grid;
  grid-template-columns: 1fr;
  gap: 7px;
}

.case-category-cards > button {
  display: flex;
  width: 100%;
  min-height: 56px;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 2px solid #e2e8f0;
  border-radius: 10px;
  color: #0f172a;
  text-align: left;
  background: #fff;
  transition: all .15s;
}

.case-category-cards > button:hover {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.case-category-cards > button.active {
  border-color: #3b82f6;
  background: #eff6ff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .1);
}

.case-category-cards > button > span {
  width: 24px;
  flex: 0 0 24px;
  font-size: 22px;
  text-align: center;
}

.case-category-cards strong,
.case-category-cards small {
  display: block;
}

.case-category-cards strong {
  color: #0f172a;
  font-size: 12px;
  font-weight: 700;
  line-height: 1.3;
}

.case-category-cards small {
  margin-top: 2px;
  color: #7c3aed;
  font-size: 10px;
  font-weight: 600;
}

.case-sidebar-products {
  padding-top: 12px;
}

.case-sidebar-search {
  display: flex;
  height: 34px;
  align-items: center;
  gap: 6px;
  margin-bottom: 7px;
  padding: 0 9px;
  border: 1.5px solid #e2e8f0;
  border-radius: 7px;
  background: #fff;
}

.case-sidebar-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
}

.case-product-subcards {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.case-product-subcards h4 {
  margin: 8px 0 2px;
  color: #64748b;
  font-size: 10px;
}

.case-product-subcards > button {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) 18px;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1.5px solid #e2e8f0;
  border-radius: 7px;
  color: #475569;
  text-align: left;
  background: #fff;
  transition: all .14s;
}

.case-product-subcards > button:hover,
.case-product-subcards > button.active {
  border-color: #3b82f6;
  color: #2563eb;
  background: #eff6ff;
}

.case-product-subcards > button strong,
.case-product-subcards > button small {
  display: block;
}

.case-product-subcards > button strong {
  overflow: hidden;
  font-size: 11px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.case-product-subcards > button small {
  margin-top: 2px;
  color: #d97706;
  font-size: 9px;
}

.case-product-subcards > button i {
  color: #2563eb;
  font-style: normal;
  font-weight: 700;
  text-align: right;
}

.case-product-subcards > p,
.case-sidebar-notice {
  margin: 0;
  padding: 8px;
  border-radius: 7px;
  color: #b45309;
  font-size: 10px;
  background: #fffbeb;
}

.case-sidebar-patient {
  padding: 12px;
}

.case-sidebar-patient > div {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px;
  border: 1.5px solid #a7f3d0;
  border-radius: 9px;
  background: #ecfdf5;
}

.case-sidebar-patient > div > span {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-weight: 700;
  background: #059669;
}

.case-sidebar-patient p,
.case-sidebar-patient strong,
.case-sidebar-patient small {
  display: block;
  margin: 0;
}

.case-sidebar-patient small {
  margin-top: 2px;
  color: #475569;
  font-size: 10px;
}

.case-source-content {
  min-width: 0;
  min-height: 0;
  padding: 24px 32px;
  overflow-y: auto;
}

.case-source-content > * {
  width: 100%;
  max-width: 800px;
  margin-right: auto;
  margin-left: auto;
}

.case-source-intro {
  margin-bottom: 20px;
}

.case-source-intro h1 {
  font-family: Lora, Georgia, "Songti SC", serif;
  font-size: 18px;
  font-weight: 600;
}

.case-source-intro p {
  margin-top: 6px;
  color: #475569;
  font-size: 13px;
}

.case-account-card {
  grid-template-columns: 38px minmax(0, 1fr) auto;
  gap: 14px;
  margin-bottom: 18px;
  padding: 14px 18px;
}

.case-account-card > span {
  width: 38px;
  height: 38px;
}

.case-account-card small {
  color: #475569;
  font-size: 10px;
}

.case-account-card strong {
  color: #0f172a;
  font-size: 13px;
}

.case-account-card p {
  color: #94a3b8;
  font-size: 10px;
}

.case-source-grid {
  display: block;
  margin-top: 0;
}

.case-source-grid .case-config-form {
  margin-bottom: 22px;
  padding: 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.case-source-grid .case-section-title {
  min-height: 27px;
  margin-bottom: 10px;
  padding-bottom: 7px;
  border-bottom: 1.5px solid #dbeafe;
}

.case-source-grid .case-section-title small {
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .5px;
}

.case-patient-autocomplete {
  position: relative;
}

.case-patient-autocomplete .case-search {
  width: 100%;
  max-width: none;
  height: 38px;
  padding: 0 12px;
  border: 1.5px solid #e2e8f0;
  border-radius: 8px;
  background: #f7f9fc;
}

.case-patient-autocomplete .case-search:focus-within {
  border-color: #60a5fa;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .1);
}

.case-patient-dropdown {
  position: absolute;
  z-index: 200;
  top: 42px;
  right: 0;
  left: 0;
  max-height: 250px;
  overflow-y: auto;
  border: 1.5px solid #e2e8f0;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 8px 32px rgba(15, 37, 84, .1), 0 2px 8px rgba(15, 37, 84, .06);
}

.case-patient-dropdown > button {
  display: block;
  width: 100%;
  padding: 10px 14px;
  border: 0;
  border-bottom: 1px solid #eef2f8;
  text-align: left;
  background: #fff;
}

.case-patient-dropdown > button:hover {
  background: #eff6ff;
}

.case-patient-dropdown strong,
.case-patient-dropdown small {
  display: block;
}

.case-patient-dropdown strong {
  font-size: 13px;
}

.case-patient-dropdown small {
  margin-top: 2px;
  color: #94a3b8;
  font-size: 11px;
}

.case-patient-dropdown > p {
  margin: 0;
  padding: 12px 14px;
  color: #64748b;
}

.case-patient-create-hint {
  margin: 7px 0 0;
  color: #94a3b8;
  font-size: 11px;
}

.case-patient-create-hint > button {
  padding: 0;
  border: 0;
  color: #2563eb;
  font-weight: 600;
  text-decoration: underline;
  background: transparent;
}

.case-patient-selected {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border: 1.5px solid #a7f3d0;
  border-radius: 9px;
  background: #ecfdf5;
}

.case-patient-selected > span {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-weight: 700;
  background: #059669;
}

.case-patient-selected strong,
.case-patient-selected small {
  display: block;
}

.case-patient-selected small {
  margin-top: 2px;
  color: #475569;
  font-size: 11px;
}

.case-patient-selected > button {
  border: 0;
  color: #94a3b8;
  background: transparent;
}

.case-source-grid .case-field-grid {
  gap: 0 14px;
}

.case-source-grid .case-field {
  margin-top: 10px;
}

.case-source-grid .case-field > span {
  margin-bottom: 5px;
  color: #475569;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .5px;
}

.case-source-grid .case-field input,
.case-source-grid .case-field select,
.case-source-grid .case-field textarea {
  padding: 8px 11px;
  border: 1.5px solid #e2e8f0;
  border-radius: 8px;
  color: #0f172a;
  font-size: 13px;
  background: #f7f9fc;
}

.case-source-grid .case-alert.warning {
  margin-top: 8px;
  padding: 0;
  border: 0;
  color: #7c3aed;
  font-size: 10px;
  background: transparent;
}

.case-basket-inline {
  margin-top: 4px;
  border: 1.5px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: none;
}

.case-basket-inline > header {
  background: #f7f9fc;
}

.case-config-panel {
  display: grid;
  width: 100%;
  max-width: none;
  min-height: 100%;
  grid-template-columns: 260px minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
  padding: 0 !important;
}

.case-config-panel > header {
  grid-column: 2;
  width: calc(100% - 64px);
  max-width: 800px;
  margin: 24px auto 18px;
}

.case-config-panel .case-config-layout {
  display: contents;
}

.case-config-panel .case-item-tabs {
  grid-row: 1 / 3;
  grid-column: 1;
  align-self: stretch;
  padding: 14px 12px;
  overflow-y: auto;
  border: 0;
  border-right: 1.5px solid #e2e8f0;
  border-radius: 0;
  background: #f7f9fc;
  box-shadow: none;
}

.case-config-panel .case-item-tabs::before {
  display: block;
  margin-bottom: 8px;
  color: #94a3b8;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .8px;
  text-transform: uppercase;
  content: "已选产品";
}

.case-config-panel .case-item-tabs button {
  margin-bottom: 5px;
  padding: 8px 12px;
  border: 1.5px solid #e2e8f0;
  border-radius: 7px;
  background: #fff;
}

.case-config-panel .case-item-tabs button.active {
  border-color: #3b82f6;
  color: #2563eb;
  background: #eff6ff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, .1);
}

.case-config-panel .case-config-form {
  grid-row: 2;
  grid-column: 2;
  width: calc(100% - 64px);
  max-width: 800px;
  margin: 0 auto 24px;
  padding: 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.case-material-form {
  max-width: 800px;
}

.case-current-product {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1.5px solid var(--case-border);
  border-radius: 10px;
  background: var(--case-off);
}

.case-current-product > i {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 10px;
  color: var(--case-blue-600);
  font-size: 20px;
  font-style: normal;
  background: var(--case-blue-50);
}

.case-current-product strong,
.case-current-product small {
  display: block;
}

.case-current-product strong {
  font-size: 15px;
}

.case-current-product small {
  margin-top: 3px;
  color: var(--case-muted);
  font-size: 11px;
}

.case-current-product > span {
  color: #a16207;
  font-size: 12px;
  font-weight: 700;
}

.case-material-section {
  margin-top: 22px;
}

.case-material-section > header {
  display: block;
  margin-bottom: 10px;
  padding-bottom: 7px;
  border-bottom: 1.5px solid var(--case-blue-100);
}

.case-material-section > header h3 {
  margin: 0;
  color: var(--case-blue-700);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .5px;
}

.case-material-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.case-material-grid .case-field {
  min-width: 0;
  margin-top: 0;
}

.case-material-grid .case-field.full {
  grid-column: 1 / -1;
}

.case-material-section .case-check-grid {
  margin-top: 0;
}

.case-material-section .case-option {
  margin-top: 8px;
}

.case-material-form > .case-primary {
  margin-top: 18px;
}

.case-panel:not(.case-source-step):not(.case-config-panel) {
  width: calc(100% - 64px);
  max-width: 800px;
  margin-right: auto;
  margin-left: auto;
}

.case-footer-context {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #64748b;
  font-size: 11px;
}

.case-wizard__footer > div:last-child {
  display: flex;
  gap: 9px;
}

@media (max-width: 900px) {
  .case-source-layout {
    display: block;
    overflow-y: auto;
  }

  .case-source-sidebar {
    display: block;
    overflow: visible;
    border-right: 0;
    border-bottom: 1.5px solid #e2e8f0;
  }

  .case-category-cards {
    display: flex;
    overflow-x: auto;
  }

  .case-category-cards > button {
    min-width: 190px;
  }

  .case-product-subcards {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .case-source-content {
    overflow: visible;
  }

  .case-config-panel {
    display: block;
  }

  .case-config-panel > header,
  .case-config-panel .case-config-form {
    width: calc(100% - 40px);
  }

  .case-config-panel .case-item-tabs {
    display: flex;
    gap: 6px;
    padding: 10px 20px;
    overflow-x: auto;
    border-right: 0;
    border-bottom: 1.5px solid #e2e8f0;
  }

  .case-config-panel .case-item-tabs::before {
    display: none;
  }

  .case-config-panel .case-item-tabs button {
    min-width: 180px;
  }
}

@media (max-width: 640px) {
  .case-wizard__header {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .case-wizard__header > small {
    display: none;
  }

  .case-product-subcards,
  .case-source-grid .case-field-grid,
  .case-material-grid {
    grid-template-columns: 1fr;
  }

  .case-current-product {
    grid-template-columns: 38px minmax(0, 1fr);
  }

  .case-current-product > i {
    width: 38px;
    height: 38px;
  }

  .case-current-product > span {
    grid-column: 2;
  }
}
</style>
