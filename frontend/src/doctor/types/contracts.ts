export type ClinicRole = 'CLINIC_ADMIN' | 'DOCTOR' | 'RECEPTION' | 'NURSE'

export type DoctorPage = 'dashboard' | 'orders' | 'assistant' | 'patients' | 'billing' | 'account' | 'messages'

export type DoctorAction =
  | 'VIEW_ORDER'
  | 'CREATE_ORDER'
  | 'SUBMIT_ORDER'
  | 'SUPPLEMENT_ORDER'
  | 'SEND_MESSAGE'
  | 'APPROVE_REVIEW'
  | 'REJECT_REVIEW'
  | 'PAY_BILL'
  | 'REQUEST_INVOICE'
  | 'REQUEST_REFUND'
  | 'CONFIRM_RECEIPT'
  | 'MANAGE_CLINIC_MEMBERS'

export type ReviewType = 'CAD_DESIGN' | 'POST_MILLING_PHOTOS' | 'POST_GLAZING_PHOTOS'

export type FileKind = 'STL' | 'IMAGE' | 'PDF' | 'OTHER'

export type Money = {
  amount_minor: number
  currency: string
}

export type DoctorSession = {
  userId: number | null
  username: string
  displayName: string
  clinicId: number | null
  clinicName: string
  availableClinicRoles: ClinicRole[]
  activeClinicRole: ClinicRole
  dataScope: 'CLINIC' | 'SELF' | 'ASSIGNED'
  permissions: string[]
}

export type DoctorFile = {
  file_id: string
  name: string
  kind: FileKind
  size_label: string
  status: 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED'
  preview_url?: string
  uploaded_at: string
}

export type ReviewVersion = {
  version: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'
  submitted_at: string
  doctor_comment?: string
  files: DoctorFile[]
}

export type OrderReview = {
  review_id: string
  review_type: ReviewType
  status: 'NOT_REQUESTED' | 'WAITING' | 'PENDING_REVIEW' | 'REVISION_REQUESTED' | 'REVISING' | 'APPROVED' | 'CANCELLED'
  current_version: number
  versions: ReviewVersion[]
  allowed_actions: DoctorAction[]
  state_version: number
}

export type OrderSummary = {
  order_id: string
  order_no: string
  group_id?: number | null
  doctor_name: string
  patient_id: string
  patient_code: string
  patient_name: string
  clinic_name: string
  product_type: string
  product_name: string
  tags: string[]
  external_status: string
  current_action: string
  created_at: string
  due_at: string
  quote: Money | null
  allowed_actions: DoctorAction[]
  state_version: number
}

export type PublicProgressItem = {
  key: string
  label: string
  status: 'DONE' | 'ACTIVE' | 'PENDING'
  occurred_at?: string
  note?: string
}

export type OrderDetail = OrderSummary & {
  public_message: string
  form_snapshot: Record<string, string>
  progress: PublicProgressItem[]
  review_options: ReviewType[]
  reviews: OrderReview[]
  files: DoctorFile[]
  messages: Message[]
  bill_summary: {
    bill_status: string
    payment_status: string
    outstanding: Money | null
  }
}

export type PatientSummary = {
  patient_id: string
  patient_code: string
  patient_name: string
  patient_age: number | null
  patient_gender: string | null
  date_of_birth: string | null
  phone: string
  email: string
  medical_notes: string
  treatment_status: 'IN_TREATMENT' | 'FOLLOW_UP' | 'TREATMENT_ENDED' | 'ARCHIVED'
  treatment_started_at: string | null
  treatment_ended_at: string | null
  clinic_name: string
  doctor_name: string
  tags: string[]
  oral_description: string
  latest_order_no: string | null
  latest_product_name: string | null
  latest_order_at: string | null
  created_at: string
  updated_at: string
  order_count: number
}

export type PatientDetail = PatientSummary & {
  notes: string
  orders: Array<Pick<OrderSummary, 'order_id' | 'order_no' | 'product_name' | 'external_status' | 'created_at'>>
  history_references: Array<{
    order_no: string
    product_name: string
    matched_fields: string[]
    summary: string
  }>
}

export type PatientCreateInput = {
  patientName: string
  patientAge: number | null
  patientGender: string | null
  dateOfBirth: string | null
  phone: string
  email: string
  medicalNotes: string
  treatmentStatus: PatientSummary['treatment_status']
  treatmentStartedAt: string | null
  treatmentEndedAt: string | null
  oralDescription: string
  tags: string[]
}

export type PatientUpdateInput = PatientCreateInput & { patientId: string }

export type BillRecord = {
  bill_id: string
  order_id: string
  order_no: string
  clinic_name: string
  doctor_name: string
  product_name: string
  settlement_type: 'PER_ORDER' | 'MONTHLY'
  amount: Money
  paid: Money
  outstanding: Money
  payment_status: string
  bill_status: string
  issued_at: string
  due_at: string
  allowed_actions: DoctorAction[]
}

export type MonthlyStatement = {
  statement_id: string
  period: string
  clinic_name: string
  order_count: number
  total: Money
  paid: Money
  balance: Money
  status: string
  due_at: string
}

export type InvoiceRefundRecord = {
  record_id: string
  kind: 'INVOICE' | 'REFUND'
  related_no: string
  title: string
  amount: Money
  status: string
  created_at: string
}

export type LogisticsRecord = {
  logistics_id: string
  order_id: string
  order_no: string
  product_name: string
  carrier: string
  tracking_no: string
  status: string
  updated_at: string
  can_confirm_receipt: boolean
  events: Array<{ label: string; time: string; location?: string }>
}

export type Message = {
  message_id: string
  sender: 'SELF' | 'ORDER_SERVICE'
  content: string
  sent_at: string
  status: 'SENDING' | 'SENT' | 'FAILED'
  attachments: DoctorFile[]
  review?: OrderReview
}

export type MessageThread = {
  thread_id: string
  order_id: string
  order_no: string
  patient_name: string
  product_name: string
  unread: boolean
  latest_message: string
  latest_at: string
  messages: Message[]
}

export type DoctorNotification = {
  notification_id: string
  category: 'ORDER' | 'REVIEW' | 'MESSAGE' | 'BILLING' | 'LOGISTICS' | 'SYSTEM'
  title: string
  summary: string
  read: boolean
  created_at: string
  target_type?: 'ORDER' | 'MESSAGE' | 'BILLING' | 'PATIENT'
  target_id?: string
}

export type ClinicMember = {
  member_id: string
  display_name: string
  email: string
  roles: ClinicRole[]
  status: 'PENDING_ACTIVATION' | 'ACTIVE' | 'DISABLED'
  billing_permission: 'NONE' | 'VIEW' | 'FINANCIAL_ACTION'
  logistics_permission: 'NONE' | 'VIEW' | 'RECEIPT'
}

export type DoctorAccount = {
  display_name: string
  email: string
  clinic_name: string
  clinic_address: string
  clinic_contact: string
  notification_preferences: Record<string, { in_app: boolean; email: boolean }>
  members: ClinicMember[]
}

export type ProductOption = {
  product_id: string
  product_type: string
  product_name: string
  material: string
  quote: Money | null
  review_capabilities: ReviewType[]
  form_fields: Array<{
    key: string
    label: string
    type: 'TEXT' | 'TEXTAREA' | 'SELECT' | 'NUMBER'
    required: boolean
    options?: string[]
  }>
}

export type DoctorPortalDataset = {
  orders: OrderSummary[]
  patients: PatientSummary[]
  bills: BillRecord[]
  statements: MonthlyStatement[]
  invoiceRefunds: InvoiceRefundRecord[]
  logistics: LogisticsRecord[]
  threads: MessageThread[]
  notifications: DoctorNotification[]
  account: DoctorAccount
  products: ProductOption[]
}

export type ReviewDecisionInput = {
  orderId: string
  reviewId: string
  decision: 'APPROVE' | 'REJECT'
  comment?: string
  stateVersion: number
  idempotencyKey: string
}

export type OrderDraftInput = {
  draftOrderId?: string
  patientId: string
  productId: string
  productType: string
  caseFields: Record<string, string>
  dynamicFields: Record<string, string>
  reviewOptions: ReviewType[]
  files: DoctorFile[]
}

/** AI-6 牙科 FAQ 应答；示例语料需在界面标注「待甲方确认」。 */
export type DoctorFaqAnswer = {
  answer: string
  resultStatus: 'SUCCESS' | 'SAFE_REFUSAL' | 'NO_MATCH'
  matchedQuestions: string[]
  requiresCustomerConfirmation: boolean
}

/** AI-7 产品推荐建议项；医生必须显式选择才生效，系统不自动填表。 */
export type DoctorProductRecommendation = {
  productId: string
  displayName: string
  categoryName: string
  reason: string
}

export interface DoctorGateway {
  updateToken(token: string): void
  loadDataset(): Promise<DoctorPortalDataset>
  switchRole(role: ClinicRole): Promise<DoctorPortalDataset>
  loadOrderDetail(orderId: string): Promise<OrderDetail>
  getFilePreviewUrl(fileId: string): Promise<string>
  loadPatientDetail(patientId: string): Promise<PatientDetail>
  createPatient(input: PatientCreateInput): Promise<PatientSummary>
  updatePatient(input: PatientUpdateInput): Promise<PatientSummary>
  saveDraft(input: OrderDraftInput): Promise<OrderSummary>
  uploadOrderFiles(orderId: string, files: File[]): Promise<DoctorFile[]>
  submitOrder(input: OrderDraftInput): Promise<OrderSummary>
  submitReview(input: ReviewDecisionInput): Promise<OrderReview>
  sendMessage(threadId: string, content: string): Promise<Message>
  markThreadRead(threadId: string): Promise<void>
  markNotificationRead(notificationId: string): Promise<void>
  markAllNotificationsRead(): Promise<void>
  confirmReceipt(orderId: string, stateVersion: number): Promise<void>
  askAssistant(question: string, orderId?: string): Promise<{ answer: string; orderIds: string[] }>
  askFaq(question: string, category?: string): Promise<DoctorFaqAnswer>
  recommendProducts(caseNote?: string): Promise<DoctorProductRecommendation[]>
}
