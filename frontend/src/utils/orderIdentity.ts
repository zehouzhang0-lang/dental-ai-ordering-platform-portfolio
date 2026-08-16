export type StaffOrderIdentitySource = {
  order_no?: string | null
  clinic_name?: string | null
  patient_name?: string | null
  product_type?: string | null
  form_data?: Record<string, unknown> | null
  promised_delivery_date?: string | null
  doctor_requested_delivery_date?: string | null
}

export type StaffOrderIdentity = {
  primary: string
  secondary: string
  reference: string
  systemOrderNo: string
  searchValues: string[]
}

export type StaffOrderIdentityOptions = {
  maskPatient?: boolean
}

const patientKeys = ['patient_name', 'patient', 'patientName']
const toothKeys = ['tooth_position', 'tooth_positions', 'toothPosition', 'tooth', 'teeth', 'tooth_no', 'tooth_number']
const materialKeys = ['material', 'material_name', 'material_spec']
const shadeKeys = ['shade', 'color', 'shade_code', 'shade_system']
const customerReferenceKeys = [
  'customer_case_no',
  'customer_case_number',
  'case_no',
  'case_number',
  'customer_order_no',
  'clinic_order_no',
  'external_order_no',
  'doctor_case_no',
  'patient_code'
]
const dueDateKeys = ['due_date', 'delivery_date', 'requested_delivery_date', 'expected_delivery_date']

function readableValue(value: unknown): string {
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' && Number.isFinite(value)) return String(value)
  if (Array.isArray(value)) return value.map(readableValue).filter(Boolean).join('、')
  return ''
}

function firstFormValue(order: StaffOrderIdentitySource, keys: string[]): string {
  for (const key of keys) {
    const value = readableValue(order.form_data?.[key])
    if (value) return value
  }
  return ''
}

function firstFormEntry(order: StaffOrderIdentitySource, keys: string[]): { key: string; value: string } | null {
  for (const key of keys) {
    const value = readableValue(order.form_data?.[key])
    if (value) return { key, value }
  }
  return null
}

function maskPatientName(value: string): string {
  const name = value.trim()
  if (!name) return ''
  if (name.includes('*')) return name
  if (/^[\u3400-\u9fff]/u.test(name)) return `${Array.from(name)[0]}*`
  const first = Array.from(name)[0] ?? ''
  return first ? `${first.toUpperCase()}***` : ''
}

function compactDate(value: string): string {
  const match = value.match(/^(?:\d{4})[-/]?(\d{1,2})[-/]?(\d{1,2})/)
  if (!match) return value
  return `${Number(match[1])}/${Number(match[2])}`
}

function unique(values: Array<string | null | undefined>): string[] {
  return [...new Set(values.map((value) => String(value ?? '').trim()).filter(Boolean))]
}

export function staffOrderIdentity(
  order: StaffOrderIdentitySource,
  productLabel: string,
  options: StaffOrderIdentityOptions = { maskPatient: true }
): StaffOrderIdentity {
  const systemOrderNo = String(order.order_no ?? '').trim()
  const clinic = String(order.clinic_name ?? '').trim() || '客户待确认'
  const patient = String(order.patient_name ?? '').trim() || firstFormValue(order, patientKeys)
  const patientMasked = maskPatientName(patient) || '患者待确认'
  const patientDisplay = options.maskPatient === false ? (patient || '患者待确认') : patientMasked
  const tooth = firstFormValue(order, toothKeys)
  const material = firstFormValue(order, materialKeys)
  const shade = firstFormValue(order, shadeKeys)
  const customerReferenceEntry = firstFormEntry(order, customerReferenceKeys)
  const customerReference = customerReferenceEntry?.value ?? ''
  const customerReferenceLabel = customerReferenceEntry && ['customer_order_no', 'clinic_order_no', 'external_order_no'].includes(customerReferenceEntry.key)
    ? '客户单号'
    : '客户病例号'
  const dueDate = String(
    order.promised_delivery_date
      ?? order.doctor_requested_delivery_date
      ?? firstFormValue(order, dueDateKeys)
      ?? ''
  ).trim()
  const suffix = systemOrderNo ? systemOrderNo.slice(-6) : '待生成'

  const secondaryParts = unique([
    productLabel,
    material ? `材料 ${material}` : '',
    shade ? `色号 ${shade}` : '',
    dueDate ? `交期 ${compactDate(dueDate)}` : ''
  ])

  return {
    primary: `${clinic} · ${patientDisplay} · ${tooth ? `牙位 ${tooth}` : '牙位待确认'}`,
    secondary: secondaryParts.join(' · '),
    reference: customerReference
      ? `${customerReferenceLabel} ${customerReference} · 系统尾号 ${suffix}`
      : `系统尾号 ${suffix}`,
    systemOrderNo,
    searchValues: unique([
      systemOrderNo,
      clinic,
      patient,
      patientMasked,
      tooth,
      material,
      shade,
      customerReference,
      dueDate,
      productLabel,
      order.product_type,
      ...Object.values(order.form_data ?? {}).map(readableValue)
    ])
  }
}
