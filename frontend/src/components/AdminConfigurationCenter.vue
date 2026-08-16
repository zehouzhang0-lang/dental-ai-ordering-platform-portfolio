<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { data: T; code?: number; msg?: string }
type Mode = 'catalog' | 'standard-time'
type CatalogSection = 'products' | 'materials' | 'bindings' | 'advanced'
type CatalogVersion = {
  config_version_id: number
  version_no: number
  version_name: string
  publication_status: string
  effective_at: string | null
  lock_version: number
}
type CatalogMaterial = {
  material_id: number
  config_version_id: number
  material_code: string
  display_name: string
  material_family: string | null
  brand_name: string | null
  specification: string | null
  sort_order: number
  status: string
  lock_version: number
}
type CatalogCategory = {
  category_id: number
  config_version_id: number
  category_code: string
  display_name: string
  sort_order: number
  status: 'ACTIVE' | 'INACTIVE'
  lock_version: number
}
type CatalogProduct = {
  product_id: number
  config_version_id: number
  category_id: number
  product_code: string
  display_name: string
  workflow_product_type: string | null
  tooth_rule_code: string | null
  pricing_status: 'PENDING_QUOTE' | 'PRICED'
  base_price_cents: number | null
  currency: string
  sort_order: number
  status: 'ACTIVE' | 'INACTIVE'
  lock_version: number
}
type CatalogPreview = {
  version: CatalogVersion
  categories: CatalogCategory[]
  products: CatalogProduct[]
  materials: CatalogMaterial[]
  material_bindings: Array<Record<string, any>>
  variants: Array<Record<string, any>>
  material_colors: Array<Record<string, any>>
  accessories: Array<Record<string, any>>
  accessory_bindings: Array<Record<string, any>>
  aliases: Array<Record<string, any>>
  rules: Array<Record<string, any>>
}
type StandardVersion = {
  standard_time_version_id: number
  version_no: number
  version_name: string
  publication_status: string
  effective_at: string | null
  lock_version: number
  formal_standard_time_enabled: boolean
}
type StandardNode = {
  standard_time_item_id: number
  chain_id: number
  chain_code: string
  chain_name: string
  product_type: string
  node_id: number
  node_code: string
  process_name: string
  stage_name: string | null
  step_order: number
  standard_duration_minutes: number | null
  status: string
  lock_version: number
}

const props = defineProps<{ token: string; mode: Mode }>()

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')
const search = ref('')
const statusFilter = ref('ALL')
const catalogSection = ref<CatalogSection>('products')

const catalogVersions = ref<CatalogVersion[]>([])
const selectedCatalogVersionId = ref<number | null>(null)
const catalogPreview = ref<CatalogPreview | null>(null)
const catalogDraftName = ref('')
const categoryForm = ref({ category_code: '', display_name: '', sort_order: 0 })
const productForm = ref({ category_id: 0, product_code: '', display_name: '', workflow_product_type: 'REGULAR_CROWN', tooth_rule_code: '', sort_order: 0 })
const materialForm = ref({ material_code: '', display_name: '', material_family: '', brand_name: '', specification: '', sort_order: 0 })
const bindingForm = ref({ product_id: 0, variant_id: null as number | null, material_id: 0, selection_group_code: 'PRIMARY_MATERIAL', selection_mode: 'SINGLE', required: true, min_quantity: 1, max_quantity: 1, price_increment_cents: null as number | null, sort_order: 0 })
const variantForm = ref({ product_id: 0, variant_code: '', display_name: '', attributes: {} as Record<string, unknown>, sort_order: 0 })
const colorForm = ref({ material_id: 0, semantic_type: 'TOOTH_SHADE', color_code: '', display_name: '', sort_order: 0 })
const accessoryForm = ref({ accessory_code: '', display_name: '', quantity_supported: true, sort_order: 0 })
const accessoryBindingForm = ref({ product_id: 0, variant_id: null as number | null, accessory_id: 0, selection_group_code: 'ACCESSORIES', required: false, default: false, min_quantity: 1, max_quantity: 99, price_increment_cents: null as number | null, sort_order: 0 })
const aliasForm = ref({ canonical_type: 'PRODUCT', canonical_id: 0, alias_text: '' })
const ruleForm = ref({ product_id: null as number | null, variant_id: null as number | null, rule_type: 'FORM_SCHEMA', rule_code: '', rule_schema_text: '{\n  \"fields\": []\n}', sort_order: 0 })

const standardVersions = ref<StandardVersion[]>([])
const selectedStandardVersionId = ref<number | null>(null)
const standardNodes = ref<StandardNode[]>([])
const standardDraftName = ref('')
const standardReason = ref('填写或调整工序标准分钟')
const standardBatchMinutes = ref<number | null>(null)

const publicationStatusLabels: Record<string, string> = {
  ACTIVE: '已发布',
  DRAFT: '编辑中',
  INACTIVE: '历史版本'
}
const workflowTypeLabels: Record<string, string> = {
  REGULAR_CROWN: '固定义齿',
  IMPLANT_RESTORATION: '种植修复',
  PRECISION_ATTACHMENT: '精密附件',
  TELESCOPIC_CROWN: '套筒冠',
  VENEER_RESTORATION: '贴面修复',
  REMOVABLE_STEEL: '钢托活动义齿',
  REMOVABLE_ACRYLIC: '胶托活动义齿',
  REMOVABLE_INVISIBLE: '隐形活动义齿',
  ORTHODONTICS: '正畸产品',
  DESIGN_ONLY: '仅设计'
}

function publicationStatusLabel(status: string) {
  return publicationStatusLabels[status] ?? '未知状态'
}

function workflowTypeLabel(type: string | null | undefined) {
  if (!type) return '待选择'
  return workflowTypeLabels[type] ?? '其他工艺'
}

function itemStatusLabel(status: string) {
  return status === 'ACTIVE' ? '启用' : '停用'
}

function createInternalCode(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 7).toUpperCase()}`
}

async function api<T>(path: string, options: RequestInit = {}) {
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
      detail = body.message || body.msg || body.detail || ''
      if (!detail && response.status === 409) {
        detail = '操作未完成：该内容正在使用、存在关联内容，或页面数据已更新，请刷新后再试'
      }
      detail ||= body.error || ''
    } catch {
      detail = ''
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  return await response.json() as ApiResponse<T>
}

function resetFeedback() {
  error.value = ''
  message.value = ''
}

function downloadFile(filename: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

async function run(action: () => Promise<void>, success = '') {
  resetFeedback()
  saving.value = true
  try {
    await action()
    if (success) message.value = success
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作失败'
  } finally {
    saving.value = false
  }
}

async function loadCatalogVersions() {
  loading.value = true
  resetFeedback()
  try {
    const response = await api<CatalogVersion[]>('/admin/catalog/versions')
    catalogVersions.value = response.data
    if (!selectedCatalogVersionId.value || !response.data.some((item) => item.config_version_id === selectedCatalogVersionId.value)) {
      selectedCatalogVersionId.value = response.data.find((item) => item.publication_status === 'ACTIVE')?.config_version_id
        ?? response.data.find((item) => item.publication_status === 'DRAFT')?.config_version_id
        ?? response.data[0]?.config_version_id
        ?? null
    }
    await loadCatalogPreview()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '产品配置加载失败'
  } finally {
    loading.value = false
  }
}

async function loadCatalogPreview() {
  if (!selectedCatalogVersionId.value) return
  const response = await api<CatalogPreview>(`/admin/catalog/versions/${selectedCatalogVersionId.value}/preview`)
  catalogPreview.value = response.data
  const validId = (items: Array<Record<string, any>>, key: string, current: number | null | undefined) => {
    if (current && items.some((item) => Number(item[key]) === Number(current))) return Number(current)
    return Number(items[0]?.[key] ?? 0)
  }
  productForm.value.category_id = validId(response.data.categories, 'category_id', productForm.value.category_id)
  bindingForm.value.product_id = validId(response.data.products, 'product_id', bindingForm.value.product_id)
  bindingForm.value.material_id = validId(response.data.materials, 'material_id', bindingForm.value.material_id)
  bindingForm.value.variant_id = response.data.variants.some((item) => Number(item.variant_id) === Number(bindingForm.value.variant_id))
    ? bindingForm.value.variant_id
    : null
  variantForm.value.product_id = validId(response.data.products, 'product_id', variantForm.value.product_id)
  colorForm.value.material_id = validId(response.data.materials, 'material_id', colorForm.value.material_id)
  accessoryBindingForm.value.product_id = validId(response.data.products, 'product_id', accessoryBindingForm.value.product_id)
  accessoryBindingForm.value.accessory_id = validId(response.data.accessories, 'accessory_id', accessoryBindingForm.value.accessory_id)
  accessoryBindingForm.value.variant_id = response.data.variants.some((item) => Number(item.variant_id) === Number(accessoryBindingForm.value.variant_id))
    ? accessoryBindingForm.value.variant_id
    : null
  if (!aliasOptions().some((item) => Number(item.id) === Number(aliasForm.value.canonical_id))) resetAliasTarget()
}

const catalogIsDraft = computed(() => catalogPreview.value?.version.publication_status === 'DRAFT')
const filteredProducts = computed(() => (catalogPreview.value?.products ?? []).filter((item) => {
  const query = search.value.trim().toLowerCase()
  const categoryName = catalogPreview.value?.categories.find((category) => category.category_id === item.category_id)?.display_name ?? ''
  const matchesSearch = !query || `${item.product_code} ${item.display_name} ${item.workflow_product_type ?? ''} ${categoryName}`.toLowerCase().includes(query)
  const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
  return matchesSearch && matchesStatus
}))
const filteredMaterials = computed(() => (catalogPreview.value?.materials ?? []).filter((item) => {
  const matchesSearch = !search.value.trim() || `${item.material_code} ${item.display_name} ${item.brand_name ?? ''}`.toLowerCase().includes(search.value.trim().toLowerCase())
  const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
  return matchesSearch && matchesStatus
}))
const filteredMaterialBindings = computed(() => (catalogPreview.value?.material_bindings ?? []).filter((item) => {
  const query = search.value.trim().toLowerCase()
  const product = catalogPreview.value?.products.find((entry) => entry.product_id === item.product_id)
  const material = catalogPreview.value?.materials.find((entry) => entry.material_id === item.material_id)
  const matchesSearch = !query || `${product?.display_name ?? ''} ${product?.product_code ?? ''} ${material?.display_name ?? ''} ${material?.material_code ?? ''}`.toLowerCase().includes(query)
  const matchesStatus = statusFilter.value === 'ALL' || item.status === statusFilter.value
  return matchesSearch && matchesStatus
}))
function categoryProductCount(categoryId: number) {
  return catalogPreview.value?.products.filter((product) => product.category_id === categoryId).length ?? 0
}
const catalogCompleteness = computed(() => {
  const preview = catalogPreview.value
  if (!preview) return { complete: 0, missing: 0 }
  const missing = preview.products.filter((product) => !product.workflow_product_type).length
  return { complete: preview.products.length - missing, missing }
})

async function copyCatalogVersion() {
  await run(async () => {
    const response = await api<CatalogVersion>('/admin/catalog/versions', {
      method: 'POST',
      body: JSON.stringify({
        version_name: catalogDraftName.value.trim() || `下单内容修改 ${new Date().toLocaleDateString('zh-CN')}`,
        based_on_version_id: selectedCatalogVersionId.value
      })
    })
    selectedCatalogVersionId.value = response.data.config_version_id
    catalogDraftName.value = ''
    await loadCatalogVersions()
  }, '已进入编辑模式，可以新增或修改产品和材料')
}

async function downloadCatalogTemplate() {
  await run(async () => {
    const response = await api<Record<string, unknown>>('/admin/catalog/import-template')
    downloadFile('产品配置导入模板-CATALOG_V2_1.json', JSON.stringify({
      ...response.data,
      rows: [
        { entity_type: 'MATERIAL', code: 'MATERIAL_CODE', display_name: '材料名称' }
      ]
    }, null, 2), 'application/json;charset=utf-8')
  }, '已下载稳定模板；上传前必须先校验，不会直接写库')
}

function exportCatalogPreview() {
  if (!catalogPreview.value) return
  downloadFile(
    `产品配置-V${catalogPreview.value.version.version_no}.json`,
    JSON.stringify(catalogPreview.value, null, 2),
    'application/json;charset=utf-8'
  )
  message.value = '当前版本已导出'
}

async function validateCatalogImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await run(async () => {
    const parsed = JSON.parse(await file.text()) as Record<string, unknown>
    const response = await api<{ valid: boolean; row_count: number; error_count: number; errors: Array<Record<string, unknown>> }>('/admin/catalog/import-validation', {
      method: 'POST',
      body: JSON.stringify(parsed)
    })
    if (!response.data.valid) {
      throw new Error(`文件中有 ${response.data.error_count} 处内容需要修改，请检查格式和必填项`)
    }
    message.value = `校验通过：${response.data.row_count} 行；本接口只校验，不写入数据`
  })
}

async function createCategory() {
  if (!selectedCatalogVersionId.value) return
  if (!categoryForm.value.display_name.trim()) {
    resetFeedback()
    error.value = '请先填写分类名称'
    return
  }
  await run(async () => {
    categoryForm.value.display_name = categoryForm.value.display_name.trim()
    categoryForm.value.category_code ||= createInternalCode('CATEGORY')
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/categories`, {
      method: 'POST',
      body: JSON.stringify(categoryForm.value)
    })
    categoryForm.value = { category_code: '', display_name: '', sort_order: 0 }
    await loadCatalogPreview()
  }, '分类已添加')
}

async function createProduct() {
  if (!selectedCatalogVersionId.value) return
  const categoryExists = catalogPreview.value?.categories.some((item) => Number(item.category_id) === Number(productForm.value.category_id))
  if (!categoryExists || !productForm.value.display_name.trim()) {
    resetFeedback()
    error.value = !categoryExists ? '请先选择产品分类' : '请先填写产品名称'
    return
  }
  await run(async () => {
    productForm.value.display_name = productForm.value.display_name.trim()
    productForm.value.product_code ||= createInternalCode('PRODUCT')
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/products`, {
      method: 'POST',
      body: JSON.stringify(productForm.value)
    })
    productForm.value.product_code = ''
    productForm.value.display_name = ''
    await loadCatalogPreview()
  }, '产品已保存，未配置价格时保持待报价')
}

function categoryUpdatePayload(category: CatalogCategory, status = category.status) {
  return {
    display_name: category.display_name,
    sort_order: category.sort_order ?? 0,
    status,
    lock_version: category.lock_version
  }
}

async function saveCategory(category: CatalogCategory) {
  if (!category.display_name.trim()) {
    resetFeedback()
    error.value = '分类名称不能为空'
    return
  }
  await run(async () => {
    await api(`/admin/catalog/entities/CATEGORY/${category.category_id}`, {
      method: 'PUT',
      body: JSON.stringify(categoryUpdatePayload(category))
    })
    await loadCatalogPreview()
  }, '分类名称已更新')
}

async function toggleCategory(category: CatalogCategory) {
  await run(async () => {
    await api(`/admin/catalog/entities/CATEGORY/${category.category_id}`, {
      method: 'PUT',
      body: JSON.stringify(categoryUpdatePayload(category, category.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'))
    })
    await loadCatalogPreview()
  }, category.status === 'ACTIVE' ? '分类已停用' : '分类已恢复')
}

async function deleteCategory(category: CatalogCategory) {
  const productCount = categoryProductCount(category.category_id)
  if (productCount > 0) {
    resetFeedback()
    error.value = `该分类下还有 ${productCount} 个产品，请先处理这些产品后再删除分类`
    return
  }
  if (!window.confirm(`确定删除分类“${category.display_name}”吗？`)) return
  await run(async () => {
    await api(`/admin/catalog/entities/CATEGORY/${category.category_id}`, { method: 'DELETE' })
    await loadCatalogPreview()
  }, '未使用的分类已删除')
}

async function createMaterial() {
  if (!selectedCatalogVersionId.value) return
  if (!materialForm.value.display_name.trim()) {
    resetFeedback()
    error.value = '请先填写材料名称'
    return
  }
  await run(async () => {
    materialForm.value.display_name = materialForm.value.display_name.trim()
    materialForm.value.material_code ||= createInternalCode('MATERIAL')
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/materials`, {
      method: 'POST',
      body: JSON.stringify(materialForm.value)
    })
    materialForm.value = { material_code: '', display_name: '', material_family: '', brand_name: '', specification: '', sort_order: 0 }
    await loadCatalogPreview()
  }, '材料已添加')
}

async function bindMaterial() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/material-bindings`, {
      method: 'POST',
      body: JSON.stringify({ ...bindingForm.value, default: false })
    })
    await loadCatalogPreview()
  }, '产品与材料绑定已保存')
}

async function createVariant() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/variants`, {
      method: 'POST',
      body: JSON.stringify(variantForm.value)
    })
    variantForm.value.variant_code = ''
    variantForm.value.display_name = ''
    await loadCatalogPreview()
  }, '产品变体已保存')
}

async function createMaterialColor() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/material-colors`, {
      method: 'POST',
      body: JSON.stringify(colorForm.value)
    })
    colorForm.value.color_code = ''
    colorForm.value.display_name = ''
    await loadCatalogPreview()
  }, '语义色号已保存')
}

async function createAccessory() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/accessories`, {
      method: 'POST',
      body: JSON.stringify(accessoryForm.value)
    })
    accessoryForm.value.accessory_code = ''
    accessoryForm.value.display_name = ''
    await loadCatalogPreview()
  }, '配件已保存')
}

async function bindAccessory() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/accessory-bindings`, {
      method: 'POST',
      body: JSON.stringify(accessoryBindingForm.value)
    })
    await loadCatalogPreview()
  }, '产品与配件绑定已保存')
}

function aliasOptions() {
  if (!catalogPreview.value) return []
  if (aliasForm.value.canonical_type === 'PRODUCT') {
    return catalogPreview.value.products.map((item) => ({ id: item.product_id, name: item.display_name }))
  }
  if (aliasForm.value.canonical_type === 'PRODUCT_VARIANT') {
    return catalogPreview.value.variants.map((item) => ({ id: item.variant_id, name: item.display_name }))
  }
  if (aliasForm.value.canonical_type === 'MATERIAL') {
    return catalogPreview.value.materials.map((item) => ({ id: item.material_id, name: item.display_name }))
  }
  return catalogPreview.value.accessories.map((item) => ({ id: item.accessory_id, name: item.display_name }))
}

function resetAliasTarget() {
  aliasForm.value.canonical_id = Number(aliasOptions()[0]?.id ?? 0)
}

async function createAlias() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/aliases`, {
      method: 'POST',
      body: JSON.stringify(aliasForm.value)
    })
    aliasForm.value.alias_text = ''
    await loadCatalogPreview()
  }, '同义别名已保存，不会创建重复 SKU')
}

async function createRule() {
  if (!selectedCatalogVersionId.value) return
  await run(async () => {
    let ruleSchema: unknown
    try {
      ruleSchema = JSON.parse(ruleForm.value.rule_schema_text)
    } catch {
      throw new Error('规则 JSON 格式不正确')
    }
    await api(`/admin/catalog/versions/${selectedCatalogVersionId.value}/rules`, {
      method: 'POST',
      body: JSON.stringify({
        product_id: ruleForm.value.product_id || null,
        variant_id: ruleForm.value.variant_id || null,
        rule_type: ruleForm.value.rule_type,
        rule_code: ruleForm.value.rule_code,
        rule_schema: ruleSchema,
        sort_order: ruleForm.value.sort_order
      })
    })
    ruleForm.value.rule_code = ''
    await loadCatalogPreview()
  }, '动态字段／牙位／上传／交期规则已保存')
}

async function toggleNamedEntity(entityType: string, entity: Record<string, any>) {
  const idKey = entityType === 'CATEGORY'
    ? 'category_id'
    : entityType === 'VARIANT'
      ? 'variant_id'
      : entityType === 'ACCESSORY'
        ? 'accessory_id'
        : 'material_color_id'
  await run(async () => {
    await api(`/admin/catalog/entities/${entityType}/${entity[idKey]}`, {
      method: 'PUT',
      body: JSON.stringify({
        display_name: entity.display_name,
        sort_order: entity.sort_order ?? 0,
        status: entity.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
        lock_version: entity.lock_version
      })
    })
    await loadCatalogPreview()
  }, entity.status === 'ACTIVE' ? '配置项已停用，新草稿不再可选' : '配置项已恢复')
}

function productUpdatePayload(product: CatalogProduct, status = product.status) {
  return {
    display_name: product.display_name,
    workflow_product_type: product.workflow_product_type,
    tooth_rule_code: product.tooth_rule_code,
    pricing_status: product.pricing_status,
    base_price_cents: product.base_price_cents,
    currency: product.currency || 'CNY',
    sort_order: product.sort_order ?? 0,
    status,
    lock_version: product.lock_version
  }
}

async function saveProduct(product: CatalogProduct) {
  await run(async () => {
    await api(`/admin/catalog/products/${product.product_id}`, {
      method: 'PUT',
      body: JSON.stringify(productUpdatePayload(product))
    })
    await loadCatalogPreview()
  }, '产品内容已更新')
}

async function toggleProduct(product: CatalogProduct) {
  await run(async () => {
    await api(`/admin/catalog/products/${product.product_id}`, {
      method: 'PUT',
      body: JSON.stringify(productUpdatePayload(product, product.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'))
    })
    await loadCatalogPreview()
  }, product.status === 'ACTIVE' ? '产品已停用' : '产品已恢复')
}

async function deleteProduct(product: CatalogProduct) {
  if (!window.confirm(`确定删除草稿产品“${product.display_name}”吗？已绑定或已被订单引用的产品不会被删除。`)) return
  await run(async () => {
    await api(`/admin/catalog/entities/PRODUCT/${product.product_id}`, { method: 'DELETE' })
    await loadCatalogPreview()
  }, '未发布且未引用的草稿产品已删除')
}

async function saveMaterialBinding(item: Record<string, any>, toggle = false) {
  await run(async () => {
    await api(`/admin/catalog/material-bindings/${item.binding_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        selection_group_code: item.selection_group_code,
        required: Boolean(item.required_flag),
        selection_mode: item.selection_mode,
        default: Boolean(item.default_flag),
        min_quantity: item.min_quantity,
        max_quantity: item.max_quantity,
        price_increment_cents: item.price_increment_cents,
        sort_order: item.sort_order ?? 0,
        status: toggle ? (item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE') : item.status,
        lock_version: item.lock_version
      })
    })
    await loadCatalogPreview()
  }, toggle ? '材料绑定状态已更新' : '材料绑定规则已更新')
}

async function saveAccessoryBinding(item: Record<string, any>, toggle = false) {
  await run(async () => {
    await api(`/admin/catalog/accessory-bindings/${item.binding_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        selection_group_code: item.selection_group_code,
        required: Boolean(item.required_flag),
        default: Boolean(item.default_flag),
        min_quantity: item.min_quantity,
        max_quantity: item.max_quantity,
        price_increment_cents: item.price_increment_cents,
        sort_order: item.sort_order ?? 0,
        status: toggle ? (item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE') : item.status,
        lock_version: item.lock_version
      })
    })
    await loadCatalogPreview()
  }, toggle ? '配件绑定状态已更新' : '配件绑定规则已更新')
}

async function saveAlias(item: Record<string, any>, toggle = false) {
  await run(async () => {
    await api(`/admin/catalog/aliases/${item.alias_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        alias_text: item.alias_text,
        status: toggle ? (item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE') : item.status,
        lock_version: item.lock_version
      })
    })
    await loadCatalogPreview()
  }, toggle ? '别名状态已更新' : '别名已更新')
}

async function toggleRule(item: Record<string, any>) {
  await run(async () => {
    const schema = typeof item.rule_schema_json === 'string'
      ? JSON.parse(item.rule_schema_json)
      : item.rule_schema_json
    await api(`/admin/catalog/rules/${item.rule_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        rule_schema: schema,
        sort_order: item.sort_order ?? 0,
        status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
        lock_version: item.lock_version
      })
    })
    await loadCatalogPreview()
  }, item.status === 'ACTIVE' ? '规则已停用' : '规则已恢复')
}

async function toggleMaterial(material: CatalogMaterial) {
  await run(async () => {
    await api(`/admin/catalog/materials/${material.material_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        display_name: material.display_name,
        material_family: material.material_family,
        brand_name: material.brand_name,
        specification: material.specification,
        sort_order: material.sort_order,
        status: material.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
        lock_version: material.lock_version
      })
    })
    await loadCatalogPreview()
  }, material.status === 'ACTIVE' ? '材料已停用，新草稿不再可选' : '材料已恢复')
}

async function saveMaterial(material: CatalogMaterial) {
  await run(async () => {
    await api(`/admin/catalog/materials/${material.material_id}`, {
      method: 'PUT',
      body: JSON.stringify({
        display_name: material.display_name,
        material_family: material.material_family,
        brand_name: material.brand_name,
        specification: material.specification,
        sort_order: material.sort_order,
        status: material.status,
        lock_version: material.lock_version
      })
    })
    await loadCatalogPreview()
  }, '材料内容已更新')
}

async function deleteMaterial(material: CatalogMaterial) {
  if (!window.confirm(`确定删除草稿材料“${material.display_name}”吗？已绑定或已被订单引用的材料不会被删除。`)) return
  await run(async () => {
    await api(`/admin/catalog/materials/${material.material_id}`, { method: 'DELETE' })
    await loadCatalogPreview()
  }, '未发布且未引用的草稿材料已删除')
}

async function publishCatalog() {
  if (!catalogPreview.value) return
  await run(async () => {
    await api(`/admin/catalog/versions/${catalogPreview.value!.version.config_version_id}/publish`, {
      method: 'POST',
      body: JSON.stringify({
        reason: '管理端预览确认后发布',
        lock_version: catalogPreview.value!.version.lock_version
      })
    })
    await loadCatalogVersions()
  }, '下单内容已发布，之后的新订单将使用本次内容')
}

async function loadStandardVersions() {
  loading.value = true
  resetFeedback()
  try {
    const response = await api<StandardVersion[]>('/admin/workflow/standard-times/versions')
    standardVersions.value = response.data
    if (!selectedStandardVersionId.value || !response.data.some((item) => item.standard_time_version_id === selectedStandardVersionId.value)) {
      selectedStandardVersionId.value = response.data.find((item) => item.publication_status === 'DRAFT')?.standard_time_version_id
        ?? response.data[0]?.standard_time_version_id
        ?? null
    }
    await loadStandardNodes()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '标准工时加载失败'
  } finally {
    loading.value = false
  }
}

async function loadStandardNodes() {
  if (!selectedStandardVersionId.value) return
  const response = await api<StandardNode[]>(`/admin/workflow/standard-times/versions/${selectedStandardVersionId.value}/nodes`)
  standardNodes.value = response.data
}

const selectedStandardVersion = computed(() => standardVersions.value.find((item) => item.standard_time_version_id === selectedStandardVersionId.value) ?? null)
const standardIsDraft = computed(() => selectedStandardVersion.value?.publication_status === 'DRAFT')
const formalStandardTimeEnabled = computed(() => selectedStandardVersion.value?.formal_standard_time_enabled === true)
const filteredStandardNodes = computed(() => standardNodes.value.filter((node) => {
  const matchesSearch = !search.value.trim() || `${node.chain_name} ${node.product_type} ${node.process_name}`.toLowerCase().includes(search.value.trim().toLowerCase())
  const matchesStatus = statusFilter.value === 'ALL' || node.status === statusFilter.value
  return matchesSearch && matchesStatus
}))
const standardCoverage = computed(() => {
  const configured = standardNodes.value.filter((node) => node.standard_duration_minutes !== null).length
  return { configured, missing: standardNodes.value.length - configured, total: standardNodes.value.length }
})

async function saveStandardTimes() {
  if (!selectedStandardVersionId.value) return
  await run(async () => {
    await api(`/admin/workflow/standard-times/versions/${selectedStandardVersionId.value}/nodes`, {
      method: 'PUT',
      body: JSON.stringify({
        reason: standardReason.value,
        items: standardNodes.value.map((node) => ({
          node_id: node.node_id,
          standard_duration_minutes: node.standard_duration_minutes === null || node.standard_duration_minutes === undefined || String(node.standard_duration_minutes) === ''
            ? null
            : Number(node.standard_duration_minutes),
          status: node.status,
          lock_version: node.lock_version
        }))
      })
    })
    await loadStandardNodes()
  }, '标准分钟已保存；空值继续保持未配置')
}

async function copyStandardVersion() {
  await run(async () => {
    const response = await api<StandardVersion>('/admin/workflow/standard-times/versions', {
      method: 'POST',
      body: JSON.stringify({
        source_version_id: selectedStandardVersionId.value,
        version_name: standardDraftName.value.trim() || `标准工时草稿 ${new Date().toLocaleDateString('zh-CN')}`
      })
    })
    selectedStandardVersionId.value = response.data.standard_time_version_id
    standardDraftName.value = ''
    await loadStandardVersions()
  }, '已复制为新草稿，原版本保持不变')
}

async function publishStandardTimes() {
  if (!selectedStandardVersion.value) return
  await run(async () => {
    await api(`/admin/workflow/standard-times/versions/${selectedStandardVersion.value!.standard_time_version_id}/publish`, {
      method: 'POST',
      body: JSON.stringify({
        reason: standardReason.value,
        lock_version: selectedStandardVersion.value!.lock_version
      })
    })
    await loadStandardVersions()
  }, '标准工时版本已发布；只影响之后实例化的新工序')
}

function fillStandardTimes() {
  if (standardBatchMinutes.value === null || standardBatchMinutes.value < 0 || standardBatchMinutes.value > 43200) {
    error.value = '批量分钟必须在 0～43200 之间'
    return
  }
  filteredStandardNodes.value.forEach((node) => {
    node.standard_duration_minutes = standardBatchMinutes.value
  })
  message.value = `已填入 ${filteredStandardNodes.value.length} 个当前筛选节点，尚未保存`
}

function exportStandardTimes() {
  const rows = [
    ['template_version', 'chain_code', 'product_type', 'node_code', 'process_name', 'standard_duration_minutes', 'status'],
    ...standardNodes.value.map((node) => [
      'STANDARD_TIME_V1',
      node.chain_code,
      node.product_type,
      node.node_code,
      node.process_name,
      node.standard_duration_minutes ?? '',
      node.status
    ])
  ]
  const csv = `\uFEFF${rows.map((row) => row.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(',')).join('\n')}`
  downloadFile(`标准工时-V${selectedStandardVersion.value?.version_no ?? 'draft'}.csv`, csv, 'text/csv;charset=utf-8')
  message.value = '标准工时模板已导出'
}

async function importStandardTimes(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  resetFeedback()
  try {
    const lines = (await file.text()).replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean)
    if (lines.length < 2) throw new Error('CSV 没有数据行')
    const headers = parseCsvLine(lines[0]).map((value) => value.trim())
    const nodeCodeIndex = headers.indexOf('node_code')
    const durationIndex = headers.indexOf('standard_duration_minutes')
    const statusIndex = headers.indexOf('status')
    const versionIndex = headers.indexOf('template_version')
    if ([nodeCodeIndex, durationIndex, statusIndex, versionIndex].some((index) => index < 0)) {
      throw new Error('CSV 缺少稳定模板列')
    }
    const byNodeCode = new Map(standardNodes.value.map((node) => [node.node_code, node]))
    let applied = 0
    lines.slice(1).forEach((line, index) => {
      const values = parseCsvLine(line)
      if (values[versionIndex] !== 'STANDARD_TIME_V1') throw new Error(`第 ${index + 2} 行模板版本不支持`)
      const node = byNodeCode.get(values[nodeCodeIndex])
      if (!node) throw new Error(`第 ${index + 2} 行工序节点不存在`)
      const raw = values[durationIndex].trim()
      const duration = raw === '' ? null : Number(raw)
      if (duration !== null && (!Number.isInteger(duration) || duration < 0 || duration > 43200)) {
        throw new Error(`第 ${index + 2} 行标准分钟无效`)
      }
      const status = values[statusIndex]
      if (status !== 'ACTIVE' && status !== 'INACTIVE') throw new Error(`第 ${index + 2} 行状态无效`)
      node.standard_duration_minutes = duration
      node.status = status
      applied++
    })
    message.value = `已导入 ${applied} 行到当前草稿，点击“批量保存”后才会写入`
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '标准工时导入失败'
  }
}

function parseCsvLine(line: string) {
  const values: string[] = []
  let current = ''
  let quoted = false
  for (let index = 0; index < line.length; index++) {
    const char = line[index]
    if (char === '"' && quoted && line[index + 1] === '"') {
      current += '"'
      index++
    } else if (char === '"') {
      quoted = !quoted
    } else if (char === ',' && !quoted) {
      values.push(current)
      current = ''
    } else {
      current += char
    }
  }
  values.push(current)
  return values
}

watch(() => props.mode, async () => {
  search.value = ''
  statusFilter.value = 'ALL'
  catalogSection.value = 'products'
  if (props.mode === 'catalog') await loadCatalogVersions()
  else await loadStandardVersions()
})
watch(selectedCatalogVersionId, () => { if (props.mode === 'catalog') void loadCatalogPreview() })
watch(selectedStandardVersionId, () => { if (props.mode === 'standard-time') void loadStandardNodes() })
onMounted(() => props.mode === 'catalog' ? loadCatalogVersions() : loadStandardVersions())
</script>

<template>
  <div class="config-center" :data-testid="mode === 'catalog' ? 'catalog-configuration-center' : 'workflow-standard-time-center'">
    <div class="config-toolbar">
      <label>
        <span>版本</span>
        <select v-if="mode === 'catalog'" v-model.number="selectedCatalogVersionId" data-testid="catalog-version-select">
          <option v-for="version in catalogVersions" :key="version.config_version_id" :value="version.config_version_id">
            {{ version.version_name }} · {{ publicationStatusLabel(version.publication_status) }}
          </option>
        </select>
        <select v-else v-model.number="selectedStandardVersionId">
          <option v-for="version in standardVersions" :key="version.standard_time_version_id" :value="version.standard_time_version_id">
            {{ version.version_name }} · {{ publicationStatusLabel(version.publication_status) }}
          </option>
        </select>
      </label>
      <label class="config-search"><span>搜索</span><input v-model="search" placeholder="搜索编码、名称或工序"></label>
      <label><span>状态</span><select v-model="statusFilter"><option value="ALL">全部</option><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label>
      <button type="button" :disabled="loading" @click="mode === 'catalog' ? loadCatalogVersions() : loadStandardVersions()">刷新</button>
    </div>

    <div v-if="error" class="config-alert error">{{ error }}</div>
    <div v-if="message" class="config-alert success">{{ message }}</div>
    <div v-if="loading" class="config-state">正在加载内容…</div>

    <template v-else-if="mode === 'catalog' && catalogPreview">
      <div class="config-metrics">
        <article><span>分类</span><strong>{{ catalogPreview.categories.length }}</strong><small>当前版本</small></article>
        <article><span>产品</span><strong>{{ catalogPreview.products.length }}</strong><small>{{ catalogCompleteness.missing ? `${catalogCompleteness.missing} 个待完善` : '内容完整' }}</small></article>
        <article><span>材料</span><strong>{{ catalogPreview.materials.length }}</strong><small>可持续补充和调整</small></article>
        <article><span>适用关系</span><strong>{{ catalogPreview.material_bindings.length }}</strong><small>产品可选材料范围</small></article>
      </div>

      <section class="config-card wide version-actions">
        <header><div><span>内容状态</span><h3>{{ catalogIsDraft ? '正在编辑下单内容' : '当前使用中的下单内容' }}</h3></div><b :class="catalogIsDraft ? 'draft' : 'locked'">{{ publicationStatusLabel(catalogPreview.version.publication_status) }}</b></header>
        <div class="standard-actions">
          <input v-if="!catalogIsDraft" v-model="catalogDraftName" placeholder="本次修改名称（可不填）">
          <button v-if="!catalogIsDraft" class="primary" :disabled="saving" data-testid="catalog-copy-version" @click="copyCatalogVersion">开始编辑</button>
          <button :disabled="saving" @click="downloadCatalogTemplate">下载导入模板</button>
          <label class="file-button" :class="{ disabled: saving }"><input type="file" accept=".json,application/json" :disabled="saving" @change="validateCatalogImport">上传并校验</label>
          <button :disabled="saving" @click="exportCatalogPreview">导出当前版本</button>
          <span class="config-note">{{ catalogIsDraft ? '当前内容可以直接修改，确认无误后再发布。' : '点击“开始编辑”后即可新增、修改或停用内容，当前订单不受影响。' }}</span>
        </div>
      </section>

      <nav class="config-tabs" aria-label="下单内容配置分区">
        <button type="button" :class="{ active: catalogSection === 'products' }" data-testid="catalog-tab-products" @click="catalogSection = 'products'">产品内容 <span>{{ catalogPreview.products.length }}</span></button>
        <button type="button" :class="{ active: catalogSection === 'materials' }" data-testid="catalog-tab-materials" @click="catalogSection = 'materials'">材料维护 <span>{{ catalogPreview.materials.length }}</span></button>
        <button type="button" :class="{ active: catalogSection === 'bindings' }" data-testid="catalog-tab-bindings" @click="catalogSection = 'bindings'">适用绑定 <span>{{ catalogPreview.material_bindings.length }}</span></button>
        <button type="button" :class="{ active: catalogSection === 'advanced' }" data-testid="catalog-tab-advanced" @click="catalogSection = 'advanced'">更多配置</button>
      </nav>

      <div v-if="!catalogIsDraft" class="edit-callout">
        <span>当前内容正在使用中，如需新增或调整，请先进入编辑模式。</span>
        <button type="button" :disabled="saving" data-testid="catalog-start-edit" @click="copyCatalogVersion">开始编辑</button>
      </div>

      <div class="config-grid">
        <section v-if="catalogSection === 'products'" class="config-card wide" data-testid="catalog-products-section">
          <header><div><span>01</span><h3>产品内容</h3></div><b :class="catalogIsDraft ? 'draft' : 'locked'">{{ catalogIsDraft ? '可编辑' : '使用中' }}</b></header>
          <p class="section-help">可以新增产品或调整产品名称和生产类型；不再使用的产品建议停用，历史订单仍会保留原内容。</p>
          <div class="config-form two">
            <label class="field-control"><span>分类名称 <em>必填</em></span><input v-model="categoryForm.display_name" :disabled="!catalogIsDraft" data-testid="catalog-category-name" placeholder="例如：固定义齿"></label>
            <button :disabled="saving || !catalogIsDraft" data-testid="catalog-category-create" @click="createCategory">新增分类</button>
          </div>
          <div class="config-table-wrap category-table-wrap">
            <table class="catalog-edit-table category-edit-table">
              <thead><tr><th>分类名称</th><th>产品数量</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="category in catalogPreview.categories" :key="category.category_id" :data-category-id="category.category_id">
                  <td><input v-model="category.display_name" :disabled="!catalogIsDraft"></td>
                  <td>{{ categoryProductCount(category.category_id) }} 个</td>
                  <td><i class="status-pill" :class="category.status.toLowerCase()">{{ itemStatusLabel(category.status) }}</i></td>
                  <td class="row-actions">
                    <button :disabled="saving || !catalogIsDraft" @click="saveCategory(category)">保存</button>
                    <button :disabled="saving || !catalogIsDraft" @click="toggleCategory(category)">{{ category.status === 'ACTIVE' ? '停用' : '恢复' }}</button>
                    <button class="danger" :disabled="saving || !catalogIsDraft" data-testid="catalog-category-delete" :title="categoryProductCount(category.category_id) ? '请先处理分类下的产品' : '删除未使用的分类'" @click="deleteCategory(category)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="config-form two">
            <label class="field-control"><span>所属分类 <em>必填</em></span><select v-model.number="productForm.category_id" :disabled="!catalogIsDraft" data-testid="catalog-product-category"><option :value="0">请选择分类</option><option v-for="item in catalogPreview.categories" :key="item.category_id" :value="item.category_id">{{ item.display_name }}</option></select></label>
            <label class="field-control"><span>产品名称 <em>必填</em></span><input v-model="productForm.display_name" :disabled="!catalogIsDraft" data-testid="catalog-product-name" placeholder="填写对外显示的产品名称"></label>
            <label class="field-control"><span>生产类型 <em>必填</em></span><select v-model="productForm.workflow_product_type" :disabled="!catalogIsDraft"><option v-for="(label, value) in workflowTypeLabels" :key="value" :value="value">{{ label }}</option></select></label>
            <button :disabled="saving || !catalogIsDraft" data-testid="catalog-product-create" @click="createProduct">新增产品</button>
          </div>
          <div class="config-table-wrap">
            <table class="catalog-edit-table product-edit-table">
              <thead><tr><th>产品</th><th>所属分类</th><th>工序类型</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="product in filteredProducts" :key="product.product_id" :data-product-code="product.product_code">
                  <td><input v-model="product.display_name" :disabled="!catalogIsDraft"></td>
                  <td>{{ catalogPreview.categories.find((category) => category.category_id === product.category_id)?.display_name || '未分类' }}</td>
                  <td><select v-model="product.workflow_product_type" :disabled="!catalogIsDraft"><option :value="null">待选择</option><option v-for="(label, value) in workflowTypeLabels" :key="value" :value="value">{{ label }}</option></select></td>
                  <td><i class="status-pill" :class="product.status.toLowerCase()">{{ product.status === 'ACTIVE' ? '启用' : '停用' }}</i></td>
                  <td class="row-actions"><button :disabled="saving || !catalogIsDraft || !product.display_name" data-testid="catalog-product-save" @click="saveProduct(product)">保存</button><button :disabled="saving || !catalogIsDraft" @click="toggleProduct(product)">{{ product.status === 'ACTIVE' ? '停用' : '恢复' }}</button><button class="danger" :disabled="saving || !catalogIsDraft" title="只有未发布且未引用的草稿产品可删除" @click="deleteProduct(product)">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="catalogSection === 'materials'" class="config-card wide" data-testid="catalog-materials-section">
          <header><div><span>02</span><h3>材料维护</h3></div><b>名称、品牌与规格</b></header>
          <p class="section-help">材料名称、类型、品牌和规格都可以持续调整；不再使用的材料建议停用，历史订单仍会保留原内容。</p>
          <div class="config-form two">
            <label class="field-control"><span>材料名称 <em>必填</em></span><input v-model="materialForm.display_name" :disabled="!catalogIsDraft" data-testid="catalog-material-name" placeholder="填写材料名称"></label>
            <label class="field-control"><span>材料类型</span><input v-model="materialForm.material_family" :disabled="!catalogIsDraft" placeholder="例如：氧化锆"></label>
            <label class="field-control"><span>品牌</span><input v-model="materialForm.brand_name" :disabled="!catalogIsDraft" placeholder="例如：Lucitone"></label>
            <label class="field-control"><span>规格</span><input v-model="materialForm.specification" :disabled="!catalogIsDraft" placeholder="填写规格信息"></label>
            <button :disabled="saving || !catalogIsDraft" data-testid="catalog-material-create" @click="createMaterial">新增材料</button>
          </div>
          <div class="config-table-wrap">
            <table class="catalog-edit-table material-edit-table">
              <thead><tr><th>材料名称</th><th>材料类型</th><th>品牌</th><th>规格</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="material in filteredMaterials" :key="material.material_id" :data-material-code="material.material_code">
                  <td><input v-model="material.display_name" :disabled="!catalogIsDraft"></td>
                  <td><input v-model="material.material_family" :disabled="!catalogIsDraft" placeholder="待补充"></td>
                  <td><input v-model="material.brand_name" :disabled="!catalogIsDraft" placeholder="待补充"></td>
                  <td><input v-model="material.specification" :disabled="!catalogIsDraft" placeholder="待补充"></td>
                  <td><i class="status-pill" :class="material.status.toLowerCase()">{{ material.status === 'ACTIVE' ? '启用' : '停用' }}</i></td>
                  <td class="row-actions"><button :disabled="saving || !catalogIsDraft || !material.display_name" data-testid="catalog-material-save" @click="saveMaterial(material)">保存</button><button :disabled="saving || !catalogIsDraft" @click="toggleMaterial(material)">{{ material.status === 'ACTIVE' ? '停用' : '恢复' }}</button><button class="danger" :disabled="saving || !catalogIsDraft" title="只有未发布且未引用的草稿材料可删除" @click="deleteMaterial(material)">删除</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="catalogSection === 'bindings'" class="config-card wide" data-testid="catalog-bindings-section">
          <header><div><span>03</span><h3>产品适用材料</h3></div><b>设置可选范围和数量</b></header>
          <p class="section-help">设置后，下单时只会显示该产品可以使用的材料；以后调整不会改变已有订单。</p>
          <div class="config-form binding">
            <select v-model.number="bindingForm.product_id" :disabled="!catalogIsDraft" data-testid="catalog-binding-product"><option :value="0">选择产品</option><option v-for="item in catalogPreview.products" :key="item.product_id" :value="item.product_id">{{ item.display_name }}</option></select>
            <select v-model.number="bindingForm.material_id" :disabled="!catalogIsDraft" data-testid="catalog-binding-material"><option :value="0">选择材料</option><option v-for="item in catalogPreview.materials" :key="item.material_id" :value="item.material_id">{{ item.display_name }}</option></select>
            <select v-model="bindingForm.selection_mode" :disabled="!catalogIsDraft"><option value="SINGLE">单选</option><option value="MULTIPLE">多选</option></select>
            <input v-model.number="bindingForm.min_quantity" type="number" min="0" :disabled="!catalogIsDraft" placeholder="最小数量">
            <input v-model.number="bindingForm.max_quantity" type="number" min="0" :disabled="!catalogIsDraft" placeholder="最大数量">
            <button :disabled="saving || !catalogIsDraft || !bindingForm.product_id || !bindingForm.material_id" data-testid="catalog-binding-create" @click="bindMaterial">保存绑定</button>
          </div>
          <div class="config-table-wrap"><table><thead><tr><th>产品 / 材料</th><th>选择方式</th><th>必选</th><th>数量</th><th>报价状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in filteredMaterialBindings" :key="item.binding_id"><td><strong>{{ catalogPreview.products.find((product) => product.product_id === item.product_id)?.display_name }}</strong><small>{{ catalogPreview.materials.find((material) => material.material_id === item.material_id)?.display_name }}</small></td><td><select v-model="item.selection_mode" :disabled="!catalogIsDraft"><option value="SINGLE">单选</option><option value="MULTIPLE">多选</option></select></td><td><input v-model="item.required_flag" type="checkbox" :disabled="!catalogIsDraft"></td><td><div class="table-range"><input v-model.number="item.min_quantity" type="number" min="0" :disabled="!catalogIsDraft"><span>～</span><input v-model.number="item.max_quantity" type="number" min="0" :disabled="!catalogIsDraft"></div></td><td>待报价</td><td><button :disabled="saving || !catalogIsDraft" @click="saveMaterialBinding(item)">保存</button><button :disabled="saving || !catalogIsDraft" @click="saveMaterialBinding(item, true)">{{ item.status === 'ACTIVE' ? '停用' : '恢复' }}</button></td></tr></tbody></table></div>
        </section>

        <section v-if="catalogSection === 'advanced'" class="config-card">
          <header><div><span>04</span><h3>产品规格</h3></div><b>{{ catalogPreview.variants.length }} 项</b></header>
          <div class="config-form two">
            <select v-model.number="variantForm.product_id" :disabled="!catalogIsDraft"><option :value="0">选择产品</option><option v-for="item in catalogPreview.products" :key="item.product_id" :value="item.product_id">{{ item.display_name }}</option></select>
            <input v-model="variantForm.variant_code" :disabled="!catalogIsDraft" placeholder="规格编号">
            <input v-model="variantForm.display_name" :disabled="!catalogIsDraft" placeholder="规格名称">
            <button :disabled="saving || !catalogIsDraft || !variantForm.product_id || !variantForm.variant_code || !variantForm.display_name" @click="createVariant">新增变体</button>
          </div>
          <div class="config-list"><div v-for="item in catalogPreview.variants" :key="item.variant_id"><span><strong>{{ item.display_name }}</strong><small>{{ catalogPreview.products.find((product) => product.product_id === item.product_id)?.display_name }}</small></span><i :class="String(item.status).toLowerCase()">{{ itemStatusLabel(item.status) }}</i><button :disabled="saving || !catalogIsDraft" @click="toggleNamedEntity('VARIANT', item)">{{ item.status === 'ACTIVE' ? '停用' : '恢复' }}</button></div></div>
        </section>

        <section v-if="catalogSection === 'materials'" class="config-card wide">
          <header><div><span>05</span><h3>材料语义色号</h3></div><b>牙色／牙龈／基托／矫治器</b></header>
          <div class="config-form two">
            <select v-model.number="colorForm.material_id" :disabled="!catalogIsDraft"><option :value="0">选择材料</option><option v-for="item in catalogPreview.materials" :key="item.material_id" :value="item.material_id">{{ item.display_name }}</option></select>
            <select v-model="colorForm.semantic_type" :disabled="!catalogIsDraft"><option value="TOOTH_SHADE">牙色</option><option value="GINGIVAL_SHADE">牙龈色</option><option value="DENTURE_BASE_SHADE">基托色</option><option value="ALIGNER_COLOR">矫治器色</option></select>
            <input v-model="colorForm.color_code" :disabled="!catalogIsDraft" placeholder="色号">
            <input v-model="colorForm.display_name" :disabled="!catalogIsDraft" placeholder="显示名称">
            <button :disabled="saving || !catalogIsDraft || !colorForm.material_id || !colorForm.color_code || !colorForm.display_name" @click="createMaterialColor">新增色号</button>
          </div>
          <div class="config-list"><div v-for="item in catalogPreview.material_colors" :key="item.material_color_id"><span><strong>{{ item.display_name }}</strong><small>{{ item.color_code }}</small></span><i :class="String(item.status).toLowerCase()">{{ itemStatusLabel(item.status) }}</i><button :disabled="saving || !catalogIsDraft" @click="toggleNamedEntity('MATERIAL_COLOR', item)">{{ item.status === 'ACTIVE' ? '停用' : '恢复' }}</button></div></div>
        </section>

        <section v-if="catalogSection === 'bindings'" class="config-card wide">
          <header><div><span>06</span><h3>配件与产品绑定</h3></div><b>仅维护数量规则，价格待客户确认</b></header>
          <div class="config-form binding">
            <input v-model="accessoryForm.accessory_code" :disabled="!catalogIsDraft" placeholder="配件编号">
            <input v-model="accessoryForm.display_name" :disabled="!catalogIsDraft" placeholder="配件名称">
            <select v-model="accessoryForm.quantity_supported" :disabled="!catalogIsDraft"><option :value="true">支持数量</option><option :value="false">不计数量</option></select>
            <button :disabled="saving || !catalogIsDraft || !accessoryForm.accessory_code || !accessoryForm.display_name" @click="createAccessory">新增配件</button>
          </div>
          <div class="config-form binding">
            <select v-model.number="accessoryBindingForm.product_id" :disabled="!catalogIsDraft"><option :value="0">选择产品</option><option v-for="item in catalogPreview.products" :key="item.product_id" :value="item.product_id">{{ item.display_name }}</option></select>
            <select v-model.number="accessoryBindingForm.accessory_id" :disabled="!catalogIsDraft"><option :value="0">选择配件</option><option v-for="item in catalogPreview.accessories" :key="item.accessory_id" :value="item.accessory_id">{{ item.display_name }}</option></select>
            <input v-model.number="accessoryBindingForm.min_quantity" type="number" min="0" :disabled="!catalogIsDraft" placeholder="最小数量">
            <input v-model.number="accessoryBindingForm.max_quantity" type="number" min="0" :disabled="!catalogIsDraft" placeholder="最大数量">
            <button :disabled="saving || !catalogIsDraft || !accessoryBindingForm.product_id || !accessoryBindingForm.accessory_id" @click="bindAccessory">保存绑定</button>
          </div>
          <table><thead><tr><th>产品 / 配件</th><th>必选</th><th>数量</th><th>报价状态</th><th>操作</th></tr></thead><tbody><tr v-for="item in catalogPreview.accessory_bindings" :key="item.binding_id"><td><strong>{{ catalogPreview.products.find((product) => product.product_id === item.product_id)?.display_name }}</strong><small>{{ catalogPreview.accessories.find((accessory) => accessory.accessory_id === item.accessory_id)?.display_name }}</small></td><td><input v-model="item.required_flag" type="checkbox" :disabled="!catalogIsDraft"></td><td><div class="table-range"><input v-model.number="item.min_quantity" type="number" min="0" :disabled="!catalogIsDraft"><span>～</span><input v-model.number="item.max_quantity" type="number" min="0" :disabled="!catalogIsDraft"></div></td><td>待报价</td><td><button :disabled="saving || !catalogIsDraft" @click="saveAccessoryBinding(item)">保存</button><button :disabled="saving || !catalogIsDraft" @click="saveAccessoryBinding(item, true)">{{ item.status === 'ACTIVE' ? '停用' : '恢复' }}</button></td></tr></tbody></table>
        </section>

        <section v-if="catalogSection === 'advanced'" class="config-card">
          <header><div><span>07</span><h3>其他名称</h3></div><b>用于识别同一产品的不同叫法</b></header>
          <div class="config-form two">
            <select v-model="aliasForm.canonical_type" :disabled="!catalogIsDraft" @change="resetAliasTarget"><option value="PRODUCT">产品</option><option value="PRODUCT_VARIANT">变体</option><option value="MATERIAL">材料</option><option value="ACCESSORY">配件</option></select>
            <select v-model.number="aliasForm.canonical_id" :disabled="!catalogIsDraft"><option :value="0">选择对应内容</option><option v-for="item in aliasOptions()" :key="item.id" :value="item.id">{{ item.name }}</option></select>
            <input v-model="aliasForm.alias_text" :disabled="!catalogIsDraft" placeholder="同义名称，如 Complete Denture">
            <button :disabled="saving || !catalogIsDraft || !aliasForm.canonical_id || !aliasForm.alias_text" @click="createAlias">添加其他名称</button>
          </div>
          <div class="config-list"><div v-for="item in catalogPreview.aliases" :key="item.alias_id"><span><input v-model="item.alias_text" :disabled="!catalogIsDraft"></span><i :class="String(item.status).toLowerCase()">{{ itemStatusLabel(item.status) }}</i><button :disabled="saving || !catalogIsDraft" @click="saveAlias(item)">保存</button><button :disabled="saving || !catalogIsDraft" @click="saveAlias(item, true)">{{ item.status === 'ACTIVE' ? '停用' : '恢复' }}</button></div></div>
        </section>

        <section class="config-card wide publish-card">
          <header><div><span>08</span><h3>检查并发布</h3></div><b>发布后供下单使用</b></header>
          <p class="config-note">发布后，新订单会使用本次产品和材料内容；已有订单仍保留原来的内容。</p>
          <footer><span>{{ catalogCompleteness.complete }} 个产品已完善，{{ catalogCompleteness.missing }} 个还需选择生产类型。</span><button class="primary" data-testid="catalog-publish" :disabled="saving || !catalogIsDraft || catalogCompleteness.missing > 0 || catalogPreview.products.length === 0" @click="publishCatalog">确认发布</button></footer>
        </section>
      </div>
    </template>

    <template v-else-if="mode === 'standard-time' && selectedStandardVersion">
      <div class="config-metrics">
        <article><span>工序数量</span><strong>{{ standardCoverage.total }}</strong><small>按生产流程展示</small></article>
        <article><span>已配置</span><strong>{{ standardCoverage.configured }}</strong><small>统一按分钟</small></article>
        <article><span>待补充</span><strong>{{ standardCoverage.missing }}</strong><small>可以稍后完善</small></article>
        <article><span>当前状态</span><strong class="text">{{ publicationStatusLabel(selectedStandardVersion.publication_status) }}</strong><small>{{ standardIsDraft ? '修改后记得保存' : '如需调整请开始编辑' }}</small></article>
      </div>
      <p class="config-note standard-time-notice" data-testid="standard-time-runtime-notice">
        工序工时尚未发布。请先完善并确认各工序时间，发布后对新订单生效；未填写的工序可以稍后补充。
      </p>
      <section class="config-card wide">
        <header><div><span>工艺配置</span><h3>工序工时设置</h3></div><b :class="standardIsDraft ? 'draft' : 'locked'">{{ standardIsDraft ? '可编辑' : '已发布' }}</b></header>
        <div class="standard-actions">
          <input v-if="!standardIsDraft" v-model="standardDraftName" placeholder="本次修改名称（可不填）">
          <button v-if="!standardIsDraft" class="primary" :disabled="saving" @click="copyStandardVersion">开始编辑</button>
          <input v-model="standardReason" :disabled="!standardIsDraft" placeholder="修改说明">
          <button :disabled="saving || !standardIsDraft" @click="saveStandardTimes">保存修改</button>
          <button class="primary" :disabled="saving || !standardIsDraft || !formalStandardTimeEnabled" @click="publishStandardTimes">{{ formalStandardTimeEnabled ? '确认发布' : '完善后发布' }}</button>
        </div>
        <div class="standard-actions import-actions">
          <input v-model.number="standardBatchMinutes" type="number" min="0" max="43200" :disabled="!standardIsDraft" placeholder="批量填写分钟数">
          <button :disabled="saving || !standardIsDraft" @click="fillStandardTimes">批量填入</button>
          <button :disabled="saving" @click="exportStandardTimes">导出 CSV 模板</button>
          <label class="file-button" :class="{ disabled: saving || !standardIsDraft }"><input type="file" accept=".csv,text/csv" :disabled="saving || !standardIsDraft" @change="importStandardTimes">导入 CSV</label>
        </div>
        <p class="config-note">这里维护每道工序的标准用时；员工实际用时会根据开始、暂停和完成操作自动记录。</p>
        <div class="standard-table-wrap">
          <table><thead><tr><th>产品 / 工艺</th><th>顺序</th><th>工序</th><th>阶段</th><th>标准分钟</th><th>状态</th></tr></thead><tbody><tr v-for="node in filteredStandardNodes" :key="node.standard_time_item_id"><td><strong>{{ workflowTypeLabel(node.product_type) }}</strong><small>{{ node.chain_name }}</small></td><td>{{ node.step_order }}</td><td>{{ node.process_name }}</td><td>{{ node.stage_name || '—' }}</td><td><input v-model.number="node.standard_duration_minutes" type="number" min="0" max="43200" :disabled="!standardIsDraft" placeholder="待填写"></td><td><select v-model="node.status" :disabled="!standardIsDraft"><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></td></tr></tbody></table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.config-center {
  --config-accent: var(--admin-blue, #2563eb);
  --config-text: var(--admin-text, #172033);
  --config-muted: var(--admin-muted, #64748b);
  --config-faint: var(--admin-faint, #94a3b8);
  --config-line: var(--admin-line, #dbe3ec);
  --config-line-soft: var(--admin-line-soft, #edf2f7);
  display: grid;
  gap: 10px;
  padding: 0 0 18px;
  color: var(--config-text);
  font-size: 11px;
}

.config-center * { box-sizing: border-box; }

.config-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1.15fr) minmax(240px, 1.55fr) 132px auto;
  align-items: end;
  gap: 9px;
  padding: 11px 14px;
  border: 1px solid var(--config-line-soft);
  border-radius: 10px;
  background: #f8fafc;
}

.config-toolbar label {
  display: grid;
  min-width: 0;
  gap: 5px;
  color: var(--config-muted);
  font-size: 9px;
  font-weight: 700;
}

.config-toolbar select,
.config-toolbar input,
.config-form input,
.config-form select,
.standard-actions input {
  width: 100%;
  min-width: 0;
  height: 34px;
  padding: 0 9px;
  border: 1px solid var(--config-line);
  border-radius: 7px;
  outline: 0;
  background: #fff;
  color: var(--config-text);
  font-size: 10px;
}

.config-toolbar select:focus,
.config-toolbar input:focus,
.config-form input:focus,
.config-form select:focus,
.standard-actions input:focus,
.config-card td input:focus,
.config-card td select:focus {
  border-color: #93c5fd;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, .08);
}

.config-form textarea {
  width: 100%;
  padding: 8px 9px;
  border: 1px solid var(--config-line);
  border-radius: 7px;
  outline: 0;
  background: #fff;
  color: var(--config-text);
  font: 10px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
  resize: vertical;
}

.config-toolbar button,
.config-form button,
.config-list button,
.standard-actions button,
.config-card footer button,
.file-button {
  display: inline-flex;
  height: 34px;
  align-items: center;
  justify-content: center;
  padding: 0 11px;
  border: 1px solid var(--config-line);
  border-radius: 7px;
  background: #fff;
  color: #475569;
  cursor: pointer;
  font-size: 9px;
  font-weight: 700;
  white-space: nowrap;
}

.config-toolbar > button,
.config-form button,
.standard-actions .primary,
.edit-callout button,
.config-card footer .primary {
  border-color: var(--config-accent);
  background: var(--config-accent);
  color: #fff;
}

.file-button input { display: none; }
.file-button.disabled { cursor: not-allowed; opacity: .45; }
button:disabled, input:disabled, select:disabled, textarea:disabled { cursor: not-allowed; opacity: .52; }

.config-alert {
  padding: 9px 12px;
  border-radius: 7px;
  font-size: 10px;
}

.config-alert.error { background: #fef2f2; color: #b42318; }
.config-alert.success { background: #ecfdf3; color: #067647; }
.config-state { padding: 34px; border: 1px solid var(--config-line-soft); background: #fff; text-align: center; }

.config-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.config-metrics article {
  display: grid;
  min-width: 0;
  gap: 3px;
  padding: 11px 13px;
  border: 1px solid var(--config-line);
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.config-metrics span { color: var(--config-muted); font-size: 9px; font-weight: 700; }
.config-metrics small { overflow: hidden; color: var(--config-faint); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.config-metrics strong { color: var(--config-text); font-size: 22px; line-height: 1.1; }
.config-metrics strong.text { padding-top: 3px; font-size: 14px; }

.config-tabs {
  display: flex;
  min-height: 42px;
  align-items: end;
  gap: 2px;
  padding: 0 12px;
  border: 1px solid var(--config-line-soft);
  border-radius: 10px;
  background: #fff;
}

.config-tabs button {
  display: inline-flex;
  height: 41px;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--config-muted);
  cursor: pointer;
  font-size: 10px;
  font-weight: 700;
}

.config-tabs button span {
  padding: 1px 6px;
  border-radius: 999px;
  background: #f1f5f9;
  color: var(--config-faint);
  font-size: 8px;
}

.config-tabs button.active { border-bottom-color: var(--config-accent); color: var(--config-accent); }
.config-tabs button.active span { background: #eff6ff; color: var(--config-accent); }

.edit-callout {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 12px;
  border: 1px solid #bfdbfe;
  border-radius: 9px;
  background: #eff6ff;
  color: #1e40af;
  font-size: 9px;
  font-weight: 700;
}

.edit-callout button {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--config-accent);
  border-radius: 7px;
  cursor: pointer;
  font-size: 9px;
  font-weight: 700;
}

.config-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.config-card { min-width: 0; padding: 13px 14px; border: 1px solid var(--config-line); border-radius: 10px; background: #fff; }
.config-card.wide { grid-column: 1 / -1; }
.config-card > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; }
.config-card > header > div { display: flex; align-items: center; gap: 8px; }
.config-card h3 { margin: 0; color: var(--config-text); font-size: 13px; }
.config-card header span { color: var(--config-accent); font-size: 9px; font-weight: 800; }
.config-card header b { color: var(--config-muted); font-size: 9px; }
.config-card header b.draft { color: #b54708; }
.config-card header b.locked { color: #027a48; }

.section-help {
  margin: -2px 0 10px;
  padding: 7px 9px;
  border-left: 2px solid #93c5fd;
  background: #f8fafc;
  color: var(--config-muted);
  font-size: 9px;
  line-height: 1.55;
}

.config-form { display: grid; gap: 8px; margin-bottom: 9px; }
.config-form.two { grid-template-columns: 1fr 1fr; }
.config-form.two button, .config-form.two textarea { grid-column: 1 / -1; }
.config-form.binding { grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); }
.field-control { display: grid; min-width: 0; gap: 5px; }
.field-control > span { color: var(--config-muted); font-size: 9px; font-weight: 700; }
.field-control em { margin-left: 3px; color: #dc2626; font-size: 8px; font-style: normal; font-weight: 600; }

.config-list { display: grid; max-height: 290px; overflow: auto; border-top: 1px solid var(--config-line-soft); }
.config-list > div { display: flex; align-items: center; gap: 8px; min-height: 42px; padding: 7px 2px; border-bottom: 1px solid var(--config-line-soft); }
.config-list span { display: grid; min-width: 0; flex: 1; }
.config-list strong { color: var(--config-text); font-size: 10px; }
.config-list small { overflow: hidden; color: var(--config-faint); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.config-list i, .status-pill { display: inline-flex; width: fit-content; padding: 2px 7px; border-radius: 999px; font-size: 8px; font-style: normal; font-weight: 700; }
.config-list i.active, .status-pill.active { background: #ecfdf3; color: #067647; }
.config-list i.inactive, .status-pill.inactive { background: #f1f5f9; color: #64748b; }
.config-list button { height: 27px; padding: 0 8px; }
.danger { color: #b42318 !important; }

.config-table-wrap, .standard-table-wrap { width: 100%; overflow: auto; border: 1px solid var(--config-line-soft); border-radius: 8px; }
.config-card table { width: 100%; border-collapse: collapse; background: #fff; color: #475569; font-size: 10px; }
.config-card th { position: sticky; top: 0; z-index: 1; padding: 8px 9px; border-bottom: 1px solid var(--config-line); background: #f8fafc; color: var(--config-muted); font-size: 8px; font-weight: 800; text-align: left; white-space: nowrap; }
.config-card td { height: 43px; padding: 6px 9px; border-bottom: 1px solid var(--config-line-soft); vertical-align: middle; }
.config-card tbody tr:last-child td { border-bottom: 0; }
.config-card tbody tr:hover { background: #fbfdff; }
.config-card td strong { color: var(--config-text); font-size: 10px; }
.config-card td small { display: block; margin-top: 2px; color: var(--config-faint); font-size: 8px; }
.config-card td input:not([type=checkbox]), .config-card td select { width: 100%; min-width: 72px; height: 29px; padding: 0 7px; border: 1px solid var(--config-line); border-radius: 6px; outline: 0; background: #fff; color: #475569; font-size: 9px; }
.config-card td button { height: 27px; margin: 1px; padding: 0 7px; border: 1px solid var(--config-line); border-radius: 6px; background: #fff; color: #475569; cursor: pointer; font-size: 8px; font-weight: 700; }
.product-edit-table th:nth-child(1) { width: 24%; }
.product-edit-table th:nth-child(2) { width: 15%; }
.product-edit-table th:nth-child(3) { width: 27%; }
.product-edit-table th:last-child { width: 170px; }
.category-table-wrap { max-height: 230px; margin-bottom: 9px; }
.category-edit-table th:first-child { width: 45%; }
.category-edit-table th:last-child { width: 170px; }
.material-edit-table th:last-child { width: 170px; }
.row-actions { min-width: 170px; white-space: nowrap; }
.table-range { display: flex; align-items: center; gap: 4px; }
.table-range input { max-width: 66px; }

.config-card footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; color: var(--config-muted); font-size: 9px; }
.standard-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-bottom: 8px; }
.standard-actions > input { width: auto; min-width: 170px; flex: 1 1 190px; }
.standard-actions .config-note { min-width: 260px; flex: 1; margin: 0; }
.config-note { padding: 8px 10px; border-radius: 7px; background: #fffbeb; color: #854d0e; font-size: 9px; line-height: 1.55; }
.standard-time-notice { margin: 0; border: 1px solid #fde68a; }
.standard-table-wrap { max-height: 560px; }
.standard-table-wrap input, .standard-table-wrap select { width: 102px; }
.standard-table-wrap th:nth-child(2) { width: 56px; }
.standard-table-wrap th:nth-child(5), .standard-table-wrap th:nth-child(6) { width: 120px; }

@media (max-width: 1100px) {
  .config-toolbar { grid-template-columns: 1fr 1fr; }
  .config-metrics { grid-template-columns: 1fr 1fr; }
  .config-grid { grid-template-columns: 1fr; }
  .config-form.binding { grid-template-columns: 1fr 1fr; }
  .config-form.binding button { grid-column: 1 / -1; }
}

@media (max-width: 720px) {
  .config-toolbar, .config-form.two { grid-template-columns: 1fr; }
  .config-tabs { align-items: stretch; padding: 0 6px; overflow-x: auto; }
  .config-tabs button { flex: 0 0 auto; }
  .config-metrics { grid-template-columns: 1fr 1fr; }
  .config-card { padding: 11px; }
  .config-card footer { align-items: stretch; flex-direction: column; }
}
</style>
