import type {
  BillRecord,
  ClinicRole,
  DoctorAccount,
  DoctorFile,
  DoctorFaqAnswer,
  DoctorGateway,
  DoctorProductRecommendation,
  Message,
  MessageThread,
  DoctorNotification,
  DoctorPortalDataset,
  LogisticsRecord,
  OrderDetail,
  OrderDraftInput,
  OrderReview,
  OrderSummary,
  PatientDetail,
  PatientCreateInput,
  PatientUpdateInput,
  PatientSummary,
  PublicProgressItem,
  ReviewDecisionInput
} from '../types/contracts'

const allowedDoctorUploadExtensions = new Set([
  'stl', 'sla', 'ply', 'obj', 'pdf', 'jpg', 'jpeg', 'png', 'webp',
  'dcm', 'dicom', 'zip', 'doc', 'docx', 'txt'
])

function hasAllowedDoctorUploadExtension(filename: string) {
  const separator = filename.lastIndexOf('.')
  if (separator <= 0 || separator === filename.length - 1) return false
  return allowedDoctorUploadExtensions.has(filename.slice(separator + 1).toLowerCase())
}

type LegacyPublicProgressItem = {
  key: string
  label: string
  status: 'DONE' | 'ACTIVE' | 'PENDING'
  occurred_at?: string | null
  note?: string | null
}

type LegacyOrder = {
  order_id: number
  order_no: string
  group_id?: number | null
  patient_id: number | null
  product_type: string
  external_status: string
  editable?: boolean
  form_data?: Record<string, unknown>
  public_message?: string | null
  bill_status?: string | null
  logistics_status?: string | null
  tracking_no?: string | null
  created_at?: string | null
  updated_at?: string | null
  public_progress?: LegacyPublicProgressItem[]
}

type LegacyMessage = {
  msg_id: number
  sender_role: string
  content: string
  created_at?: string | null
}

type LegacyOrderFile = {
  file_id: number
  source_type?: string
  visibility?: string
  original_filename: string
  content_type: string | null
  file_size: number | null
  upload_status: string
  created_at: string
}

type LegacyDesignDraft = {
  draft_id: number
  order_id: number
  version: number
  uploader_user_id: string | number | null
  file_id: number | null
  file_ids: number[]
  file_count: number
  status: string
  upload_note?: string | null
  submitted_at?: string | null
  doctor_visible_at?: string | null
  internal_reject_reason?: string | null
  doctor_reject_reason?: string | null
}

type LegacyCreateOrderResponse = Pick<LegacyOrder, 'order_id' | 'order_no' | 'product_type' | 'external_status' | 'form_data'>

type LegacyDoctorProduct = {
  product_id: number
  product_type: string
  product_name: string
  material_spec: string | null
}

type LegacyFormFieldConfig = {
  product_type: string
  field_key: string
  field_label: string
  field_type: string
  is_required: boolean
  options?: string[]
}

class DoctorApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}

export class DoctorReviewSubmittedRefreshError extends Error {
  constructor(
    message: string,
    readonly submittedReview: OrderReview,
    options?: ErrorOptions
  ) {
    super(message, options)
    this.name = 'DoctorReviewSubmittedRefreshError'
  }
}

export function isDoctorReviewSubmittedRefreshError(
  cause: unknown
): cause is DoctorReviewSubmittedRefreshError {
  return cause instanceof DoctorReviewSubmittedRefreshError
}

type LegacyPatient = {
  patient_id: number
  patient_code: string | null
  patient_name: string
  patient_age: number | null
  patient_gender: string | null
  date_of_birth: string | null
  phone: string | null
  email: string | null
  medical_notes: string | null
  tags: string | null
  treatment_status: PatientSummary['treatment_status']
  treatment_started_at: string | null
  treatment_ended_at: string | null
  oral_description: string | null
  order_count: number
  latest_order_no: string | null
  latest_product_type: string | null
  latest_order_at: string | null
  created_at: string
  updated_at: string
}

type LegacyNotification = {
  notification_id: number
  event: string
  order_id: number | null
  order_no: string | null
  message: string | null
  read_at: string | null
  created_at: string
}

type LegacyAccount = {
  display_name: string
  contact_email: string | null
  shipping_address: string | null
}

type LegacyBill = {
  bill_id: number | null
  order_id: number
  bill_status: string
  payment_status: string
  amount_cents: number | null
  currency: string
  file_id: number | null
}

type LegacyPayment = {
  payment_id: number
  amount_cents: number
  currency: string
  received_at: string
}

type LegacyLogistics = {
  logistics_id: number | null
  order_id: number
  carrier: string | null
  tracking_no: string | null
  logistics_status: string
}

type LegacyList<T> = { items: T[]; total: number; page: number; size: number }

type MultipartInitiateResponse = {
  file_id: number
  upload_id: string
  part_size: number
  part_count: number
}

type MultipartPartUrlResponse = { upload_url: string }

type MultipartPendingUpload = {
  file_id: number
  upload_id: string
  order_id: number
  source_type: string
  visibility: string
  original_filename: string
  content_type: string | null
  file_size: number
  part_size: number
  part_count: number
}

type MultipartPendingUploadsResponse = {
  items: MultipartPendingUpload[]
}

type MultipartStatusResponse = {
  file_id: number
  upload_id: string
  upload_status: string
  part_size: number | null
  part_count: number | null
  completed_parts: Array<{
    part_number: number
    etag: string
    size: number
  }>
}

type MultipartUploadPlan = {
  file_id: number
  upload_id: string
  part_size: number
  part_count: number
  completed_parts: Array<{ part_number: number; etag: string }>
}

const productLabels: Record<string, string> = {
  FIXED_CROWN: '常规牙冠',
  REGULAR_CROWN: '常规牙冠',
  FIXED_BRIDGE: '固定桥',
  IMPLANT_RESTORATION: '种植修复',
  IMPLANT: '种植修复',
  REMOVABLE_DENTURE: '活动义齿',
  REMOVABLE: '活动义齿',
  ORTHODONTICS: '正畸产品',
  ORTHODONTIC: '正畸产品'
}

const statusMap: Record<string, string> = {
  DRAFT: 'DRAFT',
  PENDING_REVIEW: 'UNDER_REVIEW',
  DESIGNING: 'IN_PRODUCTION',
  PRODUCING: 'IN_PRODUCTION',
  QC: 'PRODUCTION_COMPLETED',
  PENDING_SHIP: 'READY_TO_DISPATCH',
  SHIPPED: 'SHIPPED',
  COMPLETED: 'COMPLETED'
}

const publicProgressMilestones = [
  { key: 'review', label: '资料审核', externalStatus: 'PENDING_REVIEW', rank: 0, note: '订单资料正在审核' },
  { key: 'design', label: '方案设计', externalStatus: 'DESIGNING', rank: 1, note: '订单已通过审核，正在进行方案设计' },
  { key: 'production', label: '制作处理中', externalStatus: 'PRODUCING', rank: 2, note: '方案已确认，正在制作' },
  { key: 'final-review', label: '成品复核', externalStatus: 'QC', rank: 3, note: '成品正在复核' },
  { key: 'ready-to-ship', label: '待发货', externalStatus: 'PENDING_SHIP', rank: 4, note: '成品已完成，等待发货' },
  { key: 'shipped', label: '配送中', externalStatus: 'SHIPPED', rank: 5, note: '订单已发货，请在物流页面查看配送信息' },
  { key: 'completed', label: '已完成', externalStatus: 'COMPLETED', rank: 6, note: '订单已完成' }
] as const

const doctorVisibleDraftStatuses = new Set([
  'PENDING_DOCTOR',
  'PENDING_DOCTOR_CONFIRM',
  'PENDING_DOCTOR_REVIEW',
  'DOCTOR_CONFIRMED',
  'DOCTOR_REJECTED'
])

function fallbackPublicProgress(order: LegacyOrder): PublicProgressItem[] {
  const currentRank = order.external_status === 'DRAFT'
    ? -1
    : publicProgressMilestones.find((item) => item.externalStatus === order.external_status)?.rank ?? 0
  return [
    {
      key: 'submitted',
      label: currentRank < 0 ? '订单待提交' : '订单已提交',
      status: currentRank < 0 ? 'ACTIVE' : 'DONE',
      occurred_at: order.created_at || undefined,
      note: currentRank < 0 ? '订单仍为草稿，提交后进入资料审核' : '订单已进入公开处理流程'
    },
    ...publicProgressMilestones.map((milestone): PublicProgressItem => {
      const status = currentRank > milestone.rank
        ? 'DONE'
        : currentRank === milestone.rank
          ? milestone.externalStatus === 'COMPLETED' ? 'DONE' : 'ACTIVE'
          : 'PENDING'
      return {
        key: milestone.key,
        label: milestone.label,
        status,
        occurred_at: status === 'ACTIVE' ? order.updated_at || undefined : undefined,
        note: status === 'ACTIVE' ? milestone.note : undefined
      }
    })
  ]
}

const hiddenFormKey = /(internal|process|worklog|work_log|employee|staff|technician|operator|assignee|inspection|quality|qc|rework|performance|responsibility|工序|员工|技师|质检|返工|工时|绩效|责任)/i
const unsafeDoctorContent = /(内部工序|生产员工|员工编号|技师姓名|入检|出检|质检|工时|返工|绩效|责任分类|internal_status|node_instance|worker_user|assigned_user|work_log|rework|performance|responsibility)/i

function isHiddenDoctorFormKey(key: string): boolean {
  // 医生在下单时主动选择的过程确认节点，不是内部生产工序数据。
  if (key === 'process_reviews') return false
  return hiddenFormKey.test(key)
}

function assertSafeOrderPayload(value: unknown): void {
  const visit = (candidate: unknown): boolean => {
    if (Array.isArray(candidate)) return candidate.some(visit)
    if (!candidate || typeof candidate !== 'object') return false
    return Object.entries(candidate).some(([key, nested]) => isHiddenDoctorFormKey(key) || visit(nested))
  }
  if (visit(value)) throw new DoctorApiError('医生端订单投影包含内部字段，已阻止页面加载', 403)
}

function safeFormSnapshot(form: Record<string, unknown>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(form)
      .filter(([key, value]) => !isHiddenDoctorFormKey(key) && (value == null || ['string', 'number', 'boolean'].includes(typeof value)))
      .map(([key, value]) => [key, asText(value)])
  )
}

function numericFileIds(input: OrderDraftInput): number[] {
  return input.files
    .map((file) => Number(file.file_id))
    .filter((fileId) => Number.isSafeInteger(fileId) && fileId > 0)
}

function unwrap<T>(payload: unknown): T {
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as { data: T }).data
  }
  return payload as T
}

function asText(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

function addDays(value: string | null | undefined, days: number): string {
  if (!value) return '-'
  const date = new Date(value.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return '-'
  date.setDate(date.getDate() + days)
  return date.toLocaleDateString('sv-SE')
}

function fileSizeLabel(bytes: number | null): string {
  if (bytes == null) return '大小未记录'
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function fileKind(file: LegacyOrderFile): DoctorFile['kind'] {
  const extension = file.original_filename.split('.').pop()?.toLowerCase()
  if (extension === 'stl') return 'STL'
  if (extension === 'pdf') return 'PDF'
  if (/^(jpg|jpeg|png|webp)$/.test(extension ?? '') || file.content_type?.startsWith('image/')) return 'IMAGE'
  return 'OTHER'
}

function draftFileIds(draft: LegacyDesignDraft): number[] {
  if (draft.file_ids?.length) return draft.file_ids
  return draft.file_id ? [draft.file_id] : []
}

function reviewVersionStatus(status: string): OrderReview['versions'][number]['status'] {
  if (status === 'DOCTOR_CONFIRMED') return 'APPROVED'
  if (status === 'DOCTOR_REJECTED') return 'REJECTED'
  return 'PENDING'
}

function orderReviewStatus(status: string): OrderReview['status'] {
  if (status === 'DOCTOR_CONFIRMED') return 'APPROVED'
  if (status === 'DOCTOR_REJECTED') return 'REVISION_REQUESTED'
  if (['PENDING_DOCTOR', 'PENDING_DOCTOR_CONFIRM', 'PENDING_DOCTOR_REVIEW'].includes(status)) return 'PENDING_REVIEW'
  return 'WAITING'
}

function notificationCategory(event: string): DoctorNotification['category'] {
  if (event.includes('DESIGN')) return 'REVIEW'
  if (event.includes('MESSAGE')) return 'MESSAGE'
  if (event.includes('BILL') || event.includes('PAYMENT')) return 'BILLING'
  if (event.includes('SHIP') || event.includes('LOGISTICS') || event.includes('DELIVER')) return 'LOGISTICS'
  if (event.includes('ORDER')) return 'ORDER'
  return 'SYSTEM'
}

const notificationTitles: Record<DoctorNotification['category'], string> = {
  ORDER: '订单通知',
  REVIEW: '确认通知',
  MESSAGE: '新消息',
  BILLING: '账单通知',
  LOGISTICS: '物流通知',
  SYSTEM: '系统通知'
}

export class LegacyHttpDoctorGateway implements DoctorGateway {
  private activeRole: ClinicRole = 'DOCTOR'

  constructor(
    private token: string,
    private readonly profile: { displayName: string; clinicName: string },
    private readonly baseUrl = '',
    private readonly authenticatedFetch: typeof fetch = fetch
  ) {}

  updateToken(token: string): void {
    this.token = token
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await this.authenticatedFetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${this.token}`,
        'X-Clinic-Role': this.activeRole,
        ...(init.headers ?? {})
      }
    })
    if (!response.ok) {
      let message = `请求失败：${response.status}`
      try {
        const payload = await response.json() as { message?: string; msg?: string }
        message = payload.message || payload.msg || message
      } catch {
        // 非 JSON 响应保留状态码。
      }
      throw new DoctorApiError(message, response.status)
    }
    return unwrap<T>(await response.json())
  }

  private mapOrder(order: LegacyOrder): OrderSummary {
    const form = order.form_data ?? {}
    const editable = Boolean(order.editable)
    const isDraft = order.external_status === 'DRAFT'
    const patientName = asText(form.patient_name)
    const receiptPending = order.external_status === 'SHIPPED'
    const allowedActions: OrderSummary['allowed_actions'] = isDraft
      ? ['VIEW_ORDER', 'SUBMIT_ORDER']
      : editable
        ? ['VIEW_ORDER', 'SUPPLEMENT_ORDER', 'SEND_MESSAGE']
        : ['VIEW_ORDER', 'SEND_MESSAGE']
    if (receiptPending) allowedActions.push('CONFIRM_RECEIPT')
    return {
      order_id: String(order.order_id),
      order_no: order.order_no,
      group_id: order.group_id ?? null,
      doctor_name: this.profile.displayName,
      patient_id: order.patient_id == null ? '' : String(order.patient_id),
      patient_code: order.patient_id == null ? '-' : `P-${order.patient_id}`,
      patient_name: patientName || (order.patient_id == null ? '未关联' : `患者 ${order.patient_id}`),
      clinic_name: this.profile.clinicName,
      product_type: order.product_type,
      product_name: productLabels[order.product_type] ?? order.product_type,
      tags: [],
      external_status: statusMap[order.external_status] ?? order.external_status,
      current_action: receiptPending
        ? 'RECEIPT_CONFIRMATION_REQUIRED'
        : isDraft
          ? 'NONE'
          : editable
            ? 'SUPPLEMENT_REQUIRED'
            : 'NONE',
      created_at: asText(order.created_at) || asText(form.created_at) || '-',
      due_at: asText(form.due_date) || asText(form.delivery_date) || '-',
      quote: null,
      allowed_actions: allowedActions,
      state_version: 0
    }
  }

  private mapDoctorFile(file: LegacyOrderFile, previewUrl?: string): DoctorFile {
    return {
      file_id: String(file.file_id),
      name: file.original_filename,
      kind: fileKind(file),
      size_label: fileSizeLabel(file.file_size),
      status: ['READY', 'COMPLETED'].includes(file.upload_status)
        ? 'READY'
        : file.upload_status === 'FAILED'
          ? 'FAILED'
          : 'PROCESSING',
      preview_url: previewUrl,
      uploaded_at: file.created_at
    }
  }

  private async mapDesignReview(drafts: LegacyDesignDraft[], files: LegacyOrderFile[]): Promise<OrderReview | null> {
    const visibleDrafts = drafts
      .filter((draft) => doctorVisibleDraftStatuses.has(draft.status))
      .sort((left, right) => left.version - right.version)
    const latest = visibleDrafts.at(-1)
    if (!latest) return null

    const visibleFileIds = [...new Set(visibleDrafts.flatMap(draftFileIds))]
    const previewResults = await Promise.allSettled(visibleFileIds.map(async (fileId) => {
      const result = await this.request<{ preview_url: string }>(`/files/${fileId}/preview-url`)
      return [fileId, result.preview_url] as const
    }))
    const previewUrls = new Map(previewResults.flatMap((result) =>
      result.status === 'fulfilled' ? [result.value] : []))
    const fileLookup = new Map(files.map((file) => [file.file_id, file]))

    return {
      review_id: String(latest.draft_id),
      review_type: 'CAD_DESIGN',
      status: orderReviewStatus(latest.status),
      current_version: latest.version,
      versions: visibleDrafts.map((draft) => ({
        version: draft.version,
        status: reviewVersionStatus(draft.status),
        submitted_at: draft.doctor_visible_at || draft.submitted_at || '-',
        doctor_comment: draft.doctor_reject_reason || undefined,
        files: draftFileIds(draft).map((fileId): DoctorFile => {
          const file = fileLookup.get(fileId)
          if (file) return this.mapDoctorFile(file, previewUrls.get(fileId))
          return {
            file_id: String(fileId),
            name: `设计文件 #${fileId}`,
            kind: 'OTHER',
            size_label: '大小未记录',
            status: 'READY',
            preview_url: previewUrls.get(fileId),
            uploaded_at: draft.doctor_visible_at || draft.submitted_at || '-'
          }
        })
      })),
      allowed_actions: orderReviewStatus(latest.status) === 'PENDING_REVIEW'
        ? ['APPROVE_REVIEW', 'REJECT_REVIEW']
        : [],
      state_version: latest.version
    }
  }

  private async loadDesignReview(orderId: string, knownFiles?: LegacyOrderFile[]): Promise<OrderReview | null> {
    const [draftsResult, filesResult] = await Promise.allSettled([
      this.request<LegacyDesignDraft[]>(`/orders/${encodeURIComponent(orderId)}/design-drafts`),
      knownFiles
        ? Promise.resolve(knownFiles)
        : this.request<LegacyOrderFile[]>(`/orders/${encodeURIComponent(orderId)}/files`)
    ])
    if (draftsResult.status !== 'fulfilled') throw draftsResult.reason
    const files = filesResult.status === 'fulfilled' ? filesResult.value : []
    return this.mapDesignReview(draftsResult.value, files)
  }

  private mapPatient(patient: LegacyPatient, account: DoctorAccount): PatientSummary {
    return {
      patient_id: String(patient.patient_id),
      patient_code: patient.patient_code || `P-${patient.patient_id}`,
      patient_name: patient.patient_name,
      patient_age: patient.patient_age,
      patient_gender: patient.patient_gender,
      date_of_birth: patient.date_of_birth,
      phone: patient.phone ?? '',
      email: patient.email ?? '',
      medical_notes: patient.medical_notes ?? '',
      treatment_status: patient.treatment_status || 'IN_TREATMENT',
      treatment_started_at: patient.treatment_started_at,
      treatment_ended_at: patient.treatment_ended_at,
      clinic_name: account.clinic_name,
      doctor_name: account.display_name,
      tags: (patient.tags ?? '').split(/[,，]/).map((item) => item.trim()).filter(Boolean),
      oral_description: patient.oral_description ?? '',
      latest_order_no: patient.latest_order_no,
      latest_product_name: patient.latest_product_type ? productLabels[patient.latest_product_type] ?? patient.latest_product_type : null,
      latest_order_at: patient.latest_order_at,
      created_at: patient.created_at,
      updated_at: patient.updated_at,
      order_count: patient.order_count
    }
  }

  private patientPayload(input: PatientCreateInput) {
    return {
      patient_name: input.patientName,
      patient_age: input.patientAge,
      patient_gender: input.patientGender,
      date_of_birth: input.dateOfBirth,
      phone: input.phone,
      email: input.email,
      medical_notes: input.medicalNotes,
      tags: input.tags.join('，'),
      treatment_status: input.treatmentStatus,
      treatment_started_at: input.treatmentStartedAt,
      treatment_ended_at: input.treatmentEndedAt,
      oral_description: input.oralDescription
    }
  }

  async loadDataset(): Promise<DoctorPortalDataset> {
    const [ordersResult, patientsResult, notificationsResult, accountResult, productsResult, formConfigsResult] = await Promise.allSettled([
      this.request<LegacyList<LegacyOrder>>('/orders?page=1&size=100'),
      this.request<LegacyList<LegacyPatient>>('/patients?page=1&size=100'),
      this.request<LegacyNotification[]>('/notifications?limit=100'),
      this.request<LegacyAccount>('/doctor/account/settings'),
      this.request<LegacyDoctorProduct[]>('/doctor/products'),
      this.request<LegacyFormFieldConfig[]>('/form-configs')
    ])

    const authFailure = [ordersResult, patientsResult, notificationsResult, accountResult, productsResult, formConfigsResult]
      .find((result): result is PromiseRejectedResult => result.status === 'rejected' && result.reason instanceof DoctorApiError && [401, 403].includes(result.reason.status))
    if (authFailure) throw authFailure.reason
    if (productsResult.status === 'rejected') throw productsResult.reason
    if (formConfigsResult.status === 'rejected') throw formConfigsResult.reason

    if (ordersResult.status === 'fulfilled') assertSafeOrderPayload(ordersResult.value)

    const legacyOrders = ordersResult.status === 'fulfilled' ? ordersResult.value.items : []
    const legacyPatients = patientsResult.status === 'fulfilled' ? patientsResult.value.items : []
    const patientLookup = new Map(legacyPatients.map((patient) => [patient.patient_id, patient]))
    const mappedOrders = legacyOrders.map((order) => {
      const mapped = this.mapOrder(order)
      const patient = order.patient_id == null ? null : patientLookup.get(order.patient_id)
      if (patient) {
        mapped.patient_name = patient.patient_name
        mapped.patient_code = patient.patient_code || `P-${patient.patient_id}`
      }
      return mapped
    })
    const designActionResults = await Promise.allSettled(legacyOrders.map(async (order) => {
      if (order.external_status !== 'DESIGNING') return false
      const drafts = await this.request<LegacyDesignDraft[]>(
        `/orders/${encodeURIComponent(order.order_id)}/design-drafts`
      )
      const latestVisible = drafts
        .filter((draft) => doctorVisibleDraftStatuses.has(draft.status))
        .sort((left, right) => left.version - right.version)
        .at(-1)
      return latestVisible != null && orderReviewStatus(latestVisible.status) === 'PENDING_REVIEW'
    }))
    const failedDesignActionIndex = designActionResults.findIndex((result) => result.status === 'rejected')
    if (failedDesignActionIndex >= 0) {
      const failure = designActionResults[failedDesignActionIndex] as PromiseRejectedResult
      const order = legacyOrders[failedDesignActionIndex]
      const detail = failure.reason instanceof Error ? failure.reason.message : '未知错误'
      if (failure.reason instanceof DoctorApiError) {
        throw new DoctorApiError(`订单 ${order.order_no} 的待确认状态加载失败：${detail}`, failure.reason.status)
      }
      throw new Error(`订单 ${order.order_no} 的待确认状态加载失败：${detail}`, { cause: failure.reason })
    }
    designActionResults.forEach((result, index) => {
      if (result.status !== 'fulfilled' || !result.value) return
      const summary = mappedOrders[index]
      summary.current_action = 'REVIEW_CAD_DESIGN'
      if (!summary.allowed_actions.includes('APPROVE_REVIEW')) summary.allowed_actions.push('APPROVE_REVIEW')
      if (!summary.allowed_actions.includes('REJECT_REVIEW')) summary.allowed_actions.push('REJECT_REVIEW')
    })
    const logisticsCandidates = legacyOrders.filter((order) =>
      order.external_status === 'SHIPPED'
      || Boolean(order.tracking_no)
      || Boolean(order.logistics_status && order.logistics_status !== 'PENDING'))
    const logisticsResults = await Promise.allSettled(logisticsCandidates.map(async (order): Promise<LogisticsRecord | null> => {
      const logistics = await this.request<LegacyLogistics>(`/orders/${order.order_id}/logistics`)
      if (logistics.logistics_id == null) return null
      const status = logistics.logistics_status || order.logistics_status || 'PENDING'
      const canConfirmReceipt = order.external_status === 'SHIPPED'
        && ['SHIPPED', 'DELIVERED_PENDING_CONFIRMATION'].includes(status)
      return {
        logistics_id: String(logistics.logistics_id),
        order_id: String(order.order_id),
        order_no: order.order_no,
        product_name: productLabels[order.product_type] ?? order.product_type,
        carrier: logistics.carrier || '承运商待补充',
        tracking_no: logistics.tracking_no || '运单号待补充',
        status,
        updated_at: order.updated_at || order.created_at || '时间未记录',
        can_confirm_receipt: canConfirmReceipt,
        events: [{
          label: status === 'DELIVERED' ? '医生已确认收货' : status === 'SHIPPED' ? '订单已发货' : '配送状态已更新',
          time: order.updated_at || order.created_at || '时间未记录'
        }]
      }
    }))
    const logistics = logisticsResults.flatMap((result) =>
      result.status === 'fulfilled' && result.value ? [result.value] : [])
    const billResults = await Promise.allSettled(legacyOrders.map(async (order): Promise<BillRecord | null> => {
      const [billResult, paymentsResult] = await Promise.allSettled([
        this.request<LegacyBill>(`/orders/${order.order_id}/bill`),
        this.request<LegacyPayment[]>(`/orders/${order.order_id}/payments`)
      ])
      if (billResult.status !== 'fulfilled') return null

      const bill = billResult.value
      const amount = bill.amount_cents
      if (bill.bill_id == null || amount == null) return null
      const payments = paymentsResult.status === 'fulfilled' ? paymentsResult.value : []
      const recordedPaid = payments.reduce((total, payment) => total + payment.amount_cents, 0)
      const paidAmount = bill.payment_status === 'PAID'
        ? amount
        : Math.min(amount, recordedPaid)
      const orderSummary = mappedOrders.find((item) => item.order_id === String(order.order_id))
      const dueAt = orderSummary?.due_at && orderSummary.due_at !== '-'
        ? orderSummary.due_at.slice(0, 10)
        : addDays(order.created_at, 30)
      const currency = bill.currency || 'CNY'

      return {
        bill_id: `BILL-${order.order_no}`,
        order_id: String(order.order_id),
        order_no: order.order_no,
        clinic_name: this.profile.clinicName,
        doctor_name: this.profile.displayName,
        product_name: productLabels[order.product_type] ?? order.product_type,
        settlement_type: 'PER_ORDER',
        amount: { amount_minor: amount, currency },
        paid: { amount_minor: paidAmount, currency },
        outstanding: { amount_minor: Math.max(0, amount - paidAmount), currency },
        payment_status: bill.payment_status,
        bill_status: bill.bill_status,
        issued_at: order.created_at || '-',
        due_at: dueAt,
        allowed_actions: bill.payment_status === 'PAID' ? ['REQUEST_INVOICE'] : ['PAY_BILL', 'REQUEST_INVOICE']
      }
    }))
    const bills = billResults.flatMap((result) => result.status === 'fulfilled' && result.value ? [result.value] : [])
    const messageResults = await Promise.allSettled(legacyOrders.map((order) =>
      this.request<LegacyMessage[]>(`/orders/${encodeURIComponent(order.order_id)}/messages`)
    ))
    const threads = messageResults.flatMap((result, index): MessageThread[] => {
      if (result.status !== 'fulfilled' || result.value.length === 0) return []
      const order = mappedOrders[index]
      if (!order) return []
      const messages: Message[] = result.value.map((message) => ({
        message_id: String(message.msg_id),
        sender: message.sender_role === 'DOCTOR' ? 'SELF' : 'ORDER_SERVICE',
        content: message.content,
        sent_at: message.created_at || '时间未记录',
        status: 'SENT',
        attachments: []
      }))
      const latestMessage = messages.at(-1)
      return [{
        thread_id: `TH-${order.order_id}`,
        order_id: order.order_id,
        order_no: order.order_no,
        patient_name: order.patient_name,
        product_name: order.product_name,
        unread: false,
        latest_message: latestMessage?.content || '',
        latest_at: latestMessage?.sent_at || order.created_at,
        messages
      }]
    }).sort((left, right) =>
      new Date(right.latest_at).getTime() - new Date(left.latest_at).getTime()
    )

    const accountValue = accountResult.status === 'fulfilled' ? accountResult.value : null
    const account: DoctorAccount = {
      display_name: accountValue?.display_name || this.profile.displayName,
      email: accountValue?.contact_email || '',
      clinic_name: this.profile.clinicName,
      clinic_address: accountValue?.shipping_address || '',
      clinic_contact: '',
      notification_preferences: {},
      members: []
    }

    const patients: PatientSummary[] = legacyPatients.map((patient) => this.mapPatient(patient, account))

    const notifications: DoctorNotification[] = notificationsResult.status === 'fulfilled'
      ? notificationsResult.value.flatMap((item) => {
          const category = notificationCategory(item.event.toUpperCase())
          const content = `${item.event} ${item.message ?? ''}`
          const safeSystemEvent = /(ACCOUNT|SECURITY|PASSWORD|LOGIN|SYSTEM)/i.test(item.event)
          if (unsafeDoctorContent.test(content) || (category === 'SYSTEM' && !safeSystemEvent)) return []
          return [{
            notification_id: String(item.notification_id),
            category,
            title: notificationTitles[category],
            summary: item.message || notificationTitles[category],
            read: Boolean(item.read_at),
            created_at: item.created_at,
            target_type: item.order_id ? 'ORDER' as const : undefined,
            target_id: item.order_id ? String(item.order_id) : undefined
          }]
        })
      : []

    const formConfigsByProduct = formConfigsResult.value.reduce<Map<string, LegacyFormFieldConfig[]>>(
      (grouped, field) => {
        const fields = grouped.get(field.product_type) ?? []
        fields.push(field)
        grouped.set(field.product_type, fields)
        return grouped
      },
      new Map()
    )
    const products = productsResult.value.map((product) => ({
      product_id: String(product.product_id),
      product_type: product.product_type,
      product_name: product.product_name,
      material: product.material_spec || '材料规格待确认',
      quote: null,
      review_capabilities: [],
      form_fields: (formConfigsByProduct.get(product.product_type) ?? []).map((field) => ({
        key: field.field_key,
        label: field.field_label,
        type: field.field_type === 'textarea'
          ? 'TEXTAREA' as const
          : field.field_type === 'select' || field.field_type === 'multi-select'
            ? 'SELECT' as const
            : field.field_type === 'number'
              ? 'NUMBER' as const
              : 'TEXT' as const,
        required: field.is_required,
        ...(field.options?.length ? { options: field.options } : {})
      }))
    }))

    return {
      orders: mappedOrders,
      patients,
      bills,
      statements: [],
      invoiceRefunds: [],
      logistics,
      threads,
      notifications,
      account,
      products
    }
  }

  async switchRole(role: ClinicRole): Promise<DoctorPortalDataset> {
    const previousRole = this.activeRole
    this.activeRole = role
    try {
      return await this.loadDataset()
    } catch (cause) {
      this.activeRole = previousRole
      throw cause
    }
  }

  async loadOrderDetail(orderId: string): Promise<OrderDetail> {
    const legacy = await this.request<LegacyOrder>(`/orders/${encodeURIComponent(orderId)}`)
    assertSafeOrderPayload(legacy)
    const [messagesResult, filesResult, draftsResult] = await Promise.allSettled([
      this.request<LegacyMessage[]>(`/orders/${encodeURIComponent(orderId)}/messages`),
      this.request<LegacyOrderFile[]>(`/orders/${encodeURIComponent(orderId)}/files`),
      this.request<LegacyDesignDraft[]>(`/orders/${encodeURIComponent(orderId)}/design-drafts`)
    ])
    if (draftsResult.status === 'rejected') {
      const detail = draftsResult.reason instanceof Error ? draftsResult.reason.message : '未知错误'
      if (draftsResult.reason instanceof DoctorApiError) {
        throw new DoctorApiError(`订单设计确认状态加载失败：${detail}`, draftsResult.reason.status)
      }
      throw new Error(`订单设计确认状态加载失败：${detail}`, { cause: draftsResult.reason })
    }
    const summary = this.mapOrder(legacy)
    const formSnapshot = safeFormSnapshot(legacy.form_data ?? {})
    const messages = messagesResult.status === 'fulfilled'
      ? messagesResult.value.map((message) => ({
          message_id: String(message.msg_id),
          sender: message.sender_role === 'DOCTOR' ? 'SELF' as const : 'ORDER_SERVICE' as const,
          content: message.content,
          sent_at: message.created_at || '时间未记录',
          status: 'SENT' as const,
          attachments: []
        }))
      : []
    const legacyFiles = filesResult.status === 'fulfilled' ? filesResult.value : []
    const designReview = draftsResult.status === 'fulfilled'
      ? await this.mapDesignReview(draftsResult.value, legacyFiles)
      : null
    const files = legacyFiles
      .filter((file) => file.source_type !== 'DESIGN_DRAFT')
      .map((file) => this.mapDoctorFile(file))
    if (designReview?.status === 'PENDING_REVIEW') {
      summary.current_action = 'REVIEW_CAD_DESIGN'
      if (!summary.allowed_actions.includes('APPROVE_REVIEW')) summary.allowed_actions.push('APPROVE_REVIEW')
      if (!summary.allowed_actions.includes('REJECT_REVIEW')) summary.allowed_actions.push('REJECT_REVIEW')
    }
    const reviews = designReview ? [designReview] : []
    const reviewOptions = designReview ? ['CAD_DESIGN' as const] : []
    return {
      ...summary,
      public_message: legacy.public_message || '暂无公开进度说明。',
      form_snapshot: formSnapshot,
      progress: legacy.public_progress?.length
        ? legacy.public_progress.map((item) => ({
            key: item.key,
            label: item.label,
            status: item.status,
            occurred_at: item.occurred_at || undefined,
            note: item.note || undefined
          }))
        : fallbackPublicProgress(legacy),
      review_options: reviewOptions,
      reviews,
      files,
      messages,
      bill_summary: { bill_status: legacy.bill_status || 'UNKNOWN', payment_status: 'UNKNOWN', outstanding: null }
    }
  }

  async getFilePreviewUrl(fileId: string): Promise<string> {
    const result = await this.request<{ preview_url: string }>(
      `/files/${encodeURIComponent(fileId)}/preview-url`
    )
    if (!result.preview_url) throw new Error('文件预览地址未返回')
    return result.preview_url
  }

  async loadPatientDetail(patientId: string): Promise<PatientDetail> {
    const [patient, response] = await Promise.all([
      this.request<LegacyPatient>(`/patients/${encodeURIComponent(patientId)}`),
      this.request<LegacyList<LegacyOrder>>(`/patients/${encodeURIComponent(patientId)}/orders?page=1&size=100`)
    ])
    const account: DoctorAccount = {
      display_name: this.profile.displayName,
      email: '',
      clinic_name: this.profile.clinicName,
      clinic_address: '',
      clinic_contact: '',
      notification_preferences: {},
      members: []
    }
    return {
      ...this.mapPatient(patient, account),
      notes: patient.medical_notes ?? '',
      orders: response.items.map((order) => ({
        order_id: String(order.order_id),
        order_no: order.order_no,
        product_name: productLabels[order.product_type] ?? order.product_type,
        external_status: statusMap[order.external_status] ?? order.external_status,
        created_at: order.created_at || '-'
      })),
      history_references: []
    }
  }

  async createPatient(input: PatientCreateInput): Promise<PatientSummary> {
    const patient = await this.request<LegacyPatient>('/patients', {
      method: 'POST',
      body: JSON.stringify(this.patientPayload(input))
    })
    const account = { display_name: this.profile.displayName, clinic_name: this.profile.clinicName } as DoctorAccount
    return this.mapPatient(patient, account)
  }

  async updatePatient(input: PatientUpdateInput): Promise<PatientSummary> {
    const patient = await this.request<LegacyPatient>(`/patients/${encodeURIComponent(input.patientId)}`, {
      method: 'PUT',
      body: JSON.stringify(this.patientPayload(input))
    })
    const account = { display_name: this.profile.displayName, clinic_name: this.profile.clinicName } as DoctorAccount
    return this.mapPatient(patient, account)
  }

  async saveDraft(input: OrderDraftInput): Promise<OrderSummary> {
    const payload = {
      product_id: input.productId,
      product_type: input.productType,
      patient_id: Number(input.patientId),
      form_data: { ...input.caseFields, ...input.dynamicFields },
      file_ids: numericFileIds(input),
      review_options: input.reviewOptions,
      ...(input.draftOrderId ? { submit: false } : { is_draft: true })
    }
    const result = input.draftOrderId
      ? await this.request<LegacyCreateOrderResponse>(`/orders/${encodeURIComponent(input.draftOrderId)}`, { method: 'PUT', body: JSON.stringify(payload) })
      : await this.request<LegacyCreateOrderResponse>('/orders', { method: 'POST', body: JSON.stringify(payload) })
    return this.mapOrder({
      ...result,
      patient_id: Number(input.patientId),
      editable: true,
      created_at: new Date().toISOString()
    })
  }

  private async findPendingOrderUpload(orderId: number, file: File): Promise<MultipartPendingUpload | null> {
    const params = new URLSearchParams({ order_id: String(orderId) })
    const response = await this.request<MultipartPendingUploadsResponse>(
      `/files/multipart/pending?${params.toString()}`
    )
    const contentType = file.type || 'application/octet-stream'
    return (response.items ?? []).find((candidate) =>
      candidate.order_id === orderId
      && candidate.source_type === 'ORDER_ATTACHMENT'
      && candidate.visibility === 'DOCTOR'
      && candidate.original_filename === file.name
      && candidate.file_size === file.size
      && (candidate.content_type || 'application/octet-stream') === contentType
    ) ?? null
  }

  private async resumePendingOrderUpload(orderId: number, file: File): Promise<MultipartUploadPlan | null> {
    const candidate = await this.findPendingOrderUpload(orderId, file)
    if (!candidate) return null

    const params = new URLSearchParams({ upload_id: candidate.upload_id })
    const status = await this.request<MultipartStatusResponse>(
      `/files/${candidate.file_id}/multipart/status?${params.toString()}`
    )
    if (
      status.file_id !== candidate.file_id
      || status.upload_id !== candidate.upload_id
      || status.upload_status !== 'PENDING'
    ) {
      throw new Error(`文件 ${file.name} 的待续传记录状态不一致，请稍后重试`)
    }

    const partSize = status.part_size ?? candidate.part_size
    const partCount = status.part_count ?? candidate.part_count
    if (!Number.isSafeInteger(partSize) || partSize <= 0 || !Number.isSafeInteger(partCount) || partCount <= 0) {
      throw new Error(`文件 ${file.name} 的待续传分片信息无效`)
    }
    const completedParts = (status.completed_parts ?? [])
      .filter((part) =>
        Number.isSafeInteger(part.part_number)
        && part.part_number >= 1
        && part.part_number <= partCount
        && Boolean(part.etag?.trim()))
      .map((part) => ({ part_number: part.part_number, etag: part.etag.trim() }))

    return {
      file_id: candidate.file_id,
      upload_id: candidate.upload_id,
      part_size: partSize,
      part_count: partCount,
      completed_parts: completedParts
    }
  }

  async uploadOrderFiles(orderId: string, files: File[]): Promise<DoctorFile[]> {
    const numericOrderId = Number(orderId)
    if (!Number.isSafeInteger(numericOrderId) || numericOrderId <= 0) throw new Error('请先保存有效草稿后再上传文件')
    const uploaded: DoctorFile[] = []
    for (const file of files) {
      if (!hasAllowedDoctorUploadExtension(file.name)) throw new Error(`文件 ${file.name} 的格式不受支持`)
      if (file.size > 500 * 1024 * 1024) throw new Error(`文件 ${file.name} 超过 500MB 限制`)
      const resumed = await this.resumePendingOrderUpload(numericOrderId, file)
      const upload: MultipartUploadPlan = resumed ?? {
        ...await this.request<MultipartInitiateResponse>('/files/multipart/initiate', {
          method: 'POST',
          body: JSON.stringify({
            order_id: numericOrderId,
            source_type: 'ORDER_ATTACHMENT',
            visibility: 'DOCTOR',
            original_filename: file.name,
            content_type: file.type || 'application/octet-stream',
            file_size: file.size,
            part_size: 5 * 1024 * 1024
          })
        }),
        completed_parts: []
      }
      const completedParts = new Map(
        upload.completed_parts.map((part) => [part.part_number, part.etag])
      )
      const parts: Array<{ part_number: number; etag: string }> = []
      for (let partNumber = 1; partNumber <= upload.part_count; partNumber += 1) {
        const completedEtag = completedParts.get(partNumber)
        if (completedEtag) {
          parts.push({ part_number: partNumber, etag: completedEtag })
          continue
        }
        const part = await this.request<MultipartPartUrlResponse>(`/files/${upload.file_id}/multipart/part-url`, {
          method: 'POST',
          body: JSON.stringify({ upload_id: upload.upload_id, part_number: partNumber })
        })
        const offset = (partNumber - 1) * upload.part_size
        const response = await fetch(part.upload_url, {
          method: 'PUT',
          headers: { 'Content-Type': file.type || 'application/octet-stream' },
          body: file.slice(offset, Math.min(offset + upload.part_size, file.size))
        })
        if (!response.ok) throw new Error(`文件 ${file.name} 第 ${partNumber} 分片上传失败`)
        const etag = response.headers.get('ETag')?.replaceAll('"', '').trim()
        if (!etag) throw new Error(`文件 ${file.name} 上传未返回 ETag`)
        parts.push({ part_number: partNumber, etag })
      }
      await this.request(`/files/${upload.file_id}/multipart/complete`, {
        method: 'POST',
        body: JSON.stringify({ upload_id: upload.upload_id, parts })
      })
      const extension = file.name.split('.').pop()?.toLowerCase()
      uploaded.push({
        file_id: String(upload.file_id),
        name: file.name,
        kind: extension === 'stl' ? 'STL' : extension === 'pdf' ? 'PDF' : /^(jpg|jpeg|png)$/.test(extension ?? '') ? 'IMAGE' : 'OTHER',
        size_label: `${Math.max(0.1, file.size / 1024 / 1024).toFixed(1)} MB`,
        status: 'READY',
        uploaded_at: new Date().toISOString()
      })
    }
    return uploaded
  }

  async submitOrder(input: OrderDraftInput): Promise<OrderSummary> {
    const payload = {
      product_id: input.productId,
      product_type: input.productType,
      patient_id: Number(input.patientId),
      form_data: { ...input.caseFields, ...input.dynamicFields },
      file_ids: numericFileIds(input),
      review_options: input.reviewOptions,
      ...(input.draftOrderId ? { submit: true } : { is_draft: false })
    }
    const result = input.draftOrderId
      ? await this.request<LegacyCreateOrderResponse>(`/orders/${encodeURIComponent(input.draftOrderId)}`, { method: 'PUT', body: JSON.stringify(payload) })
      : await this.request<LegacyCreateOrderResponse>('/orders', { method: 'POST', body: JSON.stringify(payload) })
    return this.mapOrder({
      ...result,
      patient_id: Number(input.patientId),
      editable: result.external_status === 'DRAFT',
      public_message: null
    })
  }

  async submitReview(input: ReviewDecisionInput): Promise<OrderReview> {
    const orderId = encodeURIComponent(input.orderId)
    const draftId = encodeURIComponent(input.reviewId)
    const submittedDraft = await this.request<LegacyDesignDraft>(`/orders/${orderId}/design-drafts/${draftId}/doctor-confirm`, {
      method: 'POST',
      body: JSON.stringify({
        action: input.decision === 'APPROVE' ? 'CONFIRM' : 'REJECT',
        doctor_reject_reason: input.decision === 'REJECT' ? input.comment?.trim() || null : null
      })
    })
    try {
      const review = await this.loadDesignReview(input.orderId)
      if (review) return review
      const submittedReview = await this.mapDesignReview([submittedDraft], [])
      if (!submittedReview) throw new Error('提交结果未包含医生可见版本')
      throw new DoctorReviewSubmittedRefreshError(
        '设计确认已提交，但未能重新读取医生可见版本',
        submittedReview
      )
    } catch (cause) {
      if (isDoctorReviewSubmittedRefreshError(cause)) throw cause
      const submittedReview = await this.mapDesignReview([submittedDraft], [])
      if (!submittedReview) throw cause
      throw new DoctorReviewSubmittedRefreshError(
        '设计确认已提交，但最新公开状态读取失败',
        submittedReview,
        { cause }
      )
    }
  }

  async sendMessage(threadId: string, content: string) {
    const orderId = threadId.replace(/^TH-/, '')
    await this.request(`/orders/${encodeURIComponent(orderId)}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content, attachment_file_ids: [] })
    })
    return { message_id: `LOCAL-${Date.now()}`, sender: 'SELF' as const, content, sent_at: new Date().toISOString(), status: 'SENT' as const, attachments: [] }
  }

  async markThreadRead(threadId: string): Promise<void> {
    const orderId = threadId.replace(/^TH-/, '')
    await this.request(`/orders/${encodeURIComponent(orderId)}/messages/read`, { method: 'POST' })
  }

  async markNotificationRead(notificationId: string): Promise<void> {
    await this.request(`/notifications/${encodeURIComponent(notificationId)}/read`, { method: 'POST' })
  }

  async markAllNotificationsRead(): Promise<void> {
    await this.request('/notifications/read-all', { method: 'POST' })
  }

  async confirmReceipt(orderId: string, _stateVersion: number): Promise<void> {
    await this.request(`/orders/${encodeURIComponent(orderId)}/confirm-receipt`, { method: 'POST' })
  }

  async askAssistant(question: string, orderId?: string): Promise<{ answer: string; orderIds: string[] }> {
    const numericOrderId = Number(orderId)
    if (!Number.isSafeInteger(numericOrderId) || numericOrderId <= 0) {
      throw new Error('请选择一个订单或在问题中输入订单号后再查询')
    }
    const payload = await this.request<{ answer: string }>('/ai/order-query', {
      method: 'POST',
      body: JSON.stringify({ order_id: numericOrderId, question })
    })
    return { answer: payload.answer, orderIds: [String(numericOrderId)] }
  }

  async askFaq(question: string, category?: string): Promise<DoctorFaqAnswer> {
    const payload = await this.request<{
      answer: string
      result_status: DoctorFaqAnswer['resultStatus']
      matched_entries: Array<{ question: string }>
      requires_customer_confirmation: boolean
    }>('/ai/faq', {
      method: 'POST',
      body: JSON.stringify(category ? { question, category } : { question })
    })
    return {
      answer: payload.answer,
      resultStatus: payload.result_status,
      matchedQuestions: (payload.matched_entries ?? []).map((entry) => entry.question),
      requiresCustomerConfirmation: Boolean(payload.requires_customer_confirmation)
    }
  }

  async recommendProducts(caseNote?: string): Promise<DoctorProductRecommendation[]> {
    const payload = await this.request<{
      recommendations: Array<{
        product_id: number
        display_name: string
        category_name: string
        reason: string
      }>
    }>('/ai/product-recommendation', {
      method: 'POST',
      body: JSON.stringify({ case_note: caseNote ?? '' })
    })
    return (payload.recommendations ?? []).map((item) => ({
      productId: String(item.product_id),
      displayName: item.display_name,
      categoryName: item.category_name,
      reason: item.reason
    }))
  }
}
