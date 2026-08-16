<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'
import {
  CLEAR_ALIGNER_ARCH_OPTIONS,
  CLEAR_ALIGNER_TREATMENT_OPTIONS
} from './customerOrderSourceSpec'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { data: T }
type AlignerType = { code: string; name: string }
type RelatedOrder = { order_id: number; order_no: string; product_name: string }
type OrthodonticCase = {
  configured: boolean
  case_status?: string
  lock_version?: number
  aligner_type_code?: string
  combined_order_id?: number | null
  total_steps?: number | null
  prescription?: Record<string, any>
}

const props = defineProps<{
  token: string
  orderId: number
  alignerTypes: AlignerType[]
  relatedOrders: RelatedOrder[]
  initialTreatmentArch?: string
  initialTreatmentMode?: string
  initialRecords?: Partial<Record<'facial_photos' | 'intraoral_photos' | 'panoramic' | 'cephalometric' | 'upper_model' | 'lower_model' | 'bite_model', string>>
}>()
const emit = defineEmits<{
  ready: [ready: boolean]
  alignerTypeChange: [code: string]
  treatmentSelectionChange: [selection: { treatment_arch: string; treatment_mode: string }]
}>()

const sectionLabels = [
  '基本信息',
  '资料与模型',
  '临床诊断',
  '矫治器与联合矫治',
  '目标牙位与移动策略',
  '方案参数',
  '预览与提交'
]
const section = ref(1)
const loading = ref(true)
const saving = ref(false)
const lockVersion = ref(0)
const caseStatus = ref('DRAFT')
const alignerTypeCode = ref('')
const combinedOrderId = ref<number | null>(null)
const totalSteps = ref<number | null>(null)
const form = reactive({
  basic_information: {
    chief_concern: '',
    treatment_goal: '',
    medical_note: ''
  },
  records_and_models: {
    facial_photos: '',
    intraoral_photos: '',
    panoramic: '',
    cephalometric: '',
    upper_model: '',
    lower_model: '',
    bite_model: '',
    cbct_or_other: ''
  },
  clinical_diagnosis: {
    dentition_stage: '',
    skeletal_pattern: '',
    crowding: '',
    overjet_overbite: '',
    diagnostic_teeth: ''
  },
  appliance_and_combination: {
    treatment_arch: props.initialTreatmentArch ?? '',
    treatment_mode: props.initialTreatmentMode ?? '',
    appliance_note: '',
    combination_note: ''
  },
  tooth_targets: {
    target_teeth: '',
    movement_strategy: '',
    extraction_or_retention: ''
  },
  plan_parameters: {
    ipr: false,
    attachments: false,
    staging: '',
    special_instruction: ''
  },
  preview_and_submission: {
    template_code: '',
    doctor_confirmation: false
  }
})

const canSubmit = computed(() =>
  Boolean(
    alignerTypeCode.value
    && form.appliance_and_combination.treatment_arch
    && form.appliance_and_combination.treatment_mode
    && (form.appliance_and_combination.treatment_mode !== 'COMBINED' || combinedOrderId.value)
    && form.basic_information.chief_concern.trim()
    && form.basic_information.treatment_goal.trim()
    && (form.appliance_and_combination.treatment_arch === 'LOWER' || form.records_and_models.upper_model.trim())
    && (form.appliance_and_combination.treatment_arch === 'UPPER' || form.records_and_models.lower_model.trim())
    && form.clinical_diagnosis.diagnostic_teeth.trim()
    && form.tooth_targets.target_teeth.trim()
    && totalSteps.value
    && totalSteps.value > 0
    && form.preview_and_submission.doctor_confirmation
  )
)

function emitTreatmentSelection() {
  if (form.appliance_and_combination.treatment_mode !== 'COMBINED') {
    combinedOrderId.value = null
  }
  emit('treatmentSelectionChange', {
    treatment_arch: form.appliance_and_combination.treatment_arch,
    treatment_mode: form.appliance_and_combination.treatment_mode
  })
}

function applyInitialRecords() {
  const initial = props.initialRecords ?? {}
  Object.entries(initial).forEach(([key, value]) => {
    if (value && key in form.records_and_models && !form.records_and_models[key as keyof typeof form.records_and_models]) {
      form.records_and_models[key as keyof typeof form.records_and_models] = value
    }
  })
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
      detail = body.message || body.msg || body.detail || body.error || ''
    } catch {
      detail = ''
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  return (await response.json() as ApiResponse<T>).data
}

function copyObject(target: Record<string, any>, source: unknown) {
  if (!source || typeof source !== 'object') return
  Object.entries(source as Record<string, unknown>).forEach(([key, value]) => {
    if (key in target) target[key] = value
  })
}

function textValue(value: unknown) {
  if (Array.isArray(value)) return value.map((entry) => String(entry)).join(', ')
  return value == null ? '' : String(value)
}

async function load() {
  try {
    applyInitialRecords()
    const data = await api<OrthodonticCase>(`/orders/${props.orderId}/orthodontic-case`)
    alignerTypeCode.value = data.aligner_type_code || props.alignerTypes[0]?.code || ''
    if (alignerTypeCode.value) emit('alignerTypeChange', alignerTypeCode.value)
    if (data.configured) {
      lockVersion.value = data.lock_version ?? 0
      caseStatus.value = data.case_status ?? 'DRAFT'
      combinedOrderId.value = data.combined_order_id ?? null
      totalSteps.value = data.total_steps ?? null
      const prescription = data.prescription ?? {}
      copyObject(form.basic_information, prescription.basic_information)
      copyObject(form.appliance_and_combination, prescription.appliance_and_combination)
      copyObject(form.plan_parameters, prescription.plan_parameters)
      copyObject(form.preview_and_submission, prescription.preview_and_submission)

      const records = prescription.records_and_models as Record<string, any> | undefined
      const models = records?.models as Record<string, any> | undefined
      form.records_and_models.facial_photos = textValue(records?.facial_photos)
      form.records_and_models.intraoral_photos = textValue(records?.intraoral_photos)
      form.records_and_models.panoramic = textValue(records?.panoramic)
      form.records_and_models.cephalometric = textValue(records?.cephalometric)
      form.records_and_models.upper_model = textValue(models?.upper)
      form.records_and_models.lower_model = textValue(models?.lower)
      form.records_and_models.bite_model = textValue(models?.bite)
      form.records_and_models.cbct_or_other = textValue(records?.cbct_or_other)

      copyObject(form.clinical_diagnosis, prescription.clinical_diagnosis)
      form.clinical_diagnosis.diagnostic_teeth = textValue(
        (prescription.clinical_diagnosis as Record<string, any> | undefined)?.diagnostic_teeth
      )
      copyObject(form.tooth_targets, prescription.tooth_targets)
      form.tooth_targets.target_teeth = textValue(
        (prescription.tooth_targets as Record<string, any> | undefined)?.target_teeth
      )
      applyInitialRecords()
    }
    emitTreatmentSelection()
    emit('ready', caseStatus.value === 'PRESCRIPTION_SUBMITTED')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '正畸处方加载失败')
  } finally {
    loading.value = false
  }
}

function list(value: string) {
  return value.split(/[,，\n]/).map((entry) => entry.trim()).filter(Boolean)
}

function payload(submit: boolean) {
  return {
    aligner_type_code: alignerTypeCode.value,
    combined_order_id: combinedOrderId.value || null,
    total_steps: totalSteps.value,
    basic_information: { ...form.basic_information },
    records_and_models: {
      facial_photos: list(form.records_and_models.facial_photos),
      intraoral_photos: list(form.records_and_models.intraoral_photos),
      panoramic: list(form.records_and_models.panoramic),
      cephalometric: list(form.records_and_models.cephalometric),
      models: {
        upper: form.records_and_models.upper_model,
        lower: form.records_and_models.lower_model,
        bite: form.records_and_models.bite_model
      },
      cbct_or_other: list(form.records_and_models.cbct_or_other)
    },
    clinical_diagnosis: {
      ...form.clinical_diagnosis,
      diagnostic_teeth: list(form.clinical_diagnosis.diagnostic_teeth)
    },
    appliance_and_combination: {
      ...form.appliance_and_combination,
      aligner_type_code: alignerTypeCode.value,
      combined_order_id: combinedOrderId.value || null
    },
    tooth_targets: {
      ...form.tooth_targets,
      target_teeth: list(form.tooth_targets.target_teeth)
    },
    plan_parameters: { ...form.plan_parameters },
    preview_and_submission: { ...form.preview_and_submission },
    submit,
    expected_lock_version: lockVersion.value
  }
}

async function save(submit = false) {
  if (saving.value) return
  if (submit && !canSubmit.value) {
    ElMessage.warning('请补齐必填资料并勾选医生确认')
    return
  }
  saving.value = true
  try {
    const data = await api<OrthodonticCase>(`/orders/${props.orderId}/orthodontic-prescription`, {
      method: 'PUT',
      body: JSON.stringify(payload(submit))
    })
    lockVersion.value = data.lock_version ?? lockVersion.value
    caseStatus.value = data.case_status ?? caseStatus.value
    emit('ready', caseStatus.value === 'PRESCRIPTION_SUBMITTED')
    ElMessage.success(submit ? '七步正畸处方已提交' : '正畸处方草稿已保存')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '正畸处方保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="ortho-prescription" data-testid="orthodontic-seven-step">
    <header>
      <div><strong>隐形正畸七步处方</strong><small>方案内审、医生确认与生产审核是三个独立门禁</small></div>
      <b :class="{ ready: caseStatus === 'PRESCRIPTION_SUBMITTED' }">{{ caseStatus === 'PRESCRIPTION_SUBMITTED' ? '处方已提交' : '处方草稿' }}</b>
    </header>
    <nav>
      <button v-for="(label, index) in sectionLabels" :key="label" type="button" :class="{ active: section === index + 1 }" @click="section = index + 1"><span>{{ index + 1 }}</span>{{ label }}</button>
    </nav>

    <div v-if="section === 1" class="ortho-fields">
      <label><span>主诉 *</span><textarea v-model="form.basic_information.chief_concern" rows="3"></textarea></label>
      <label><span>矫治目标 *</span><textarea v-model="form.basic_information.treatment_goal" rows="3"></textarea></label>
      <label class="full"><span>病史与注意事项</span><textarea v-model="form.basic_information.medical_note" rows="3"></textarea></label>
    </div>
    <div v-else-if="section === 2" class="ortho-fields">
      <label><span>面像文件 ID</span><input v-model="form.records_and_models.facial_photos" placeholder="多个用逗号分隔"></label>
      <label><span>口内照文件 ID</span><input v-model="form.records_and_models.intraoral_photos" placeholder="多个用逗号分隔"></label>
      <label><span>全景片文件 ID</span><input v-model="form.records_and_models.panoramic"></label>
      <label><span>头影文件 ID</span><input v-model="form.records_and_models.cephalometric"></label>
      <label><span>上颌模型 *</span><input v-model="form.records_and_models.upper_model"></label>
      <label><span>下颌模型 *</span><input v-model="form.records_and_models.lower_model"></label>
      <label><span>咬合模型</span><input v-model="form.records_and_models.bite_model"></label>
      <label><span>CBCT / 其他</span><input v-model="form.records_and_models.cbct_or_other"></label>
    </div>
    <div v-else-if="section === 3" class="ortho-fields">
      <label><span>牙列阶段</span><input v-model="form.clinical_diagnosis.dentition_stage"></label>
      <label><span>骨性分类</span><input v-model="form.clinical_diagnosis.skeletal_pattern"></label>
      <label><span>拥挤度</span><input v-model="form.clinical_diagnosis.crowding"></label>
      <label><span>覆牙合 / 覆盖</span><input v-model="form.clinical_diagnosis.overjet_overbite"></label>
      <label class="full"><span>诊断牙位 *</span><input v-model="form.clinical_diagnosis.diagnostic_teeth" placeholder="FDI 牙位用逗号分隔"></label>
    </div>
    <div v-else-if="section === 4" class="ortho-fields">
      <label><span>矫治牙颌 *</span><select v-model="form.appliance_and_combination.treatment_arch" data-testid="orthodontic-treatment-arch" @change="emitTreatmentSelection"><option value="">请选择</option><option v-for="item in CLEAR_ALIGNER_ARCH_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
      <label><span>矫治方式 *</span><select v-model="form.appliance_and_combination.treatment_mode" data-testid="orthodontic-treatment-mode" @change="emitTreatmentSelection"><option value="">请选择</option><option v-for="item in CLEAR_ALIGNER_TREATMENT_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
      <label><span>矫治器类型 *</span><select v-model="alignerTypeCode" data-testid="orthodontic-aligner-type" @change="emit('alignerTypeChange', alignerTypeCode)"><option v-for="item in alignerTypes" :key="item.code" :value="item.code">{{ item.name }}</option></select></label>
      <label v-if="form.appliance_and_combination.treatment_mode === 'COMBINED'"><span>联合矫治子订单 *</span><select v-model.number="combinedOrderId"><option :value="null">请选择同一病例中的产品</option><option v-for="item in relatedOrders" :key="item.order_id" :value="item.order_id">{{ item.product_name }} · {{ item.order_no }}</option></select></label>
      <label><span>矫治器说明</span><textarea v-model="form.appliance_and_combination.appliance_note" rows="3"></textarea></label>
      <label><span>联合矫治说明</span><textarea v-model="form.appliance_and_combination.combination_note" rows="3"></textarea></label>
    </div>
    <div v-else-if="section === 5" class="ortho-fields">
      <label><span>目标牙位 *</span><input v-model="form.tooth_targets.target_teeth" placeholder="FDI 牙位用逗号分隔"></label>
      <label><span>拔牙 / 保留策略</span><input v-model="form.tooth_targets.extraction_or_retention"></label>
      <label class="full"><span>移动策略</span><textarea v-model="form.tooth_targets.movement_strategy" rows="5"></textarea></label>
    </div>
    <div v-else-if="section === 6" class="ortho-fields">
      <label><span>总步数 *</span><input v-model.number="totalSteps" type="number" min="1" max="999"></label>
      <label><span>分期策略</span><input v-model="form.plan_parameters.staging"></label>
      <label class="check"><input v-model="form.plan_parameters.ipr" type="checkbox"><span>允许邻面去釉（IPR）</span></label>
      <label class="check"><input v-model="form.plan_parameters.attachments" type="checkbox"><span>允许附件设计</span></label>
      <label class="full"><span>特殊方案要求</span><textarea v-model="form.plan_parameters.special_instruction" rows="4"></textarea></label>
    </div>
    <div v-else class="ortho-review">
      <dl>
        <div><dt>矫治器类型</dt><dd>{{ alignerTypes.find((item) => item.code === alignerTypeCode)?.name || alignerTypeCode }}</dd></div>
        <div><dt>矫治牙颌</dt><dd>{{ CLEAR_ALIGNER_ARCH_OPTIONS.find((item) => item.value === form.appliance_and_combination.treatment_arch)?.label || '未选择' }}</dd></div>
        <div><dt>矫治方式</dt><dd>{{ CLEAR_ALIGNER_TREATMENT_OPTIONS.find((item) => item.value === form.appliance_and_combination.treatment_mode)?.label || '未选择' }}</dd></div>
        <div><dt>总步数</dt><dd>{{ totalSteps || '未填写' }}</dd></div>
        <div><dt>诊断牙位</dt><dd>{{ form.clinical_diagnosis.diagnostic_teeth || '未填写' }}</dd></div>
        <div><dt>目标牙位</dt><dd>{{ form.tooth_targets.target_teeth || '未填写' }}</dd></div>
      </dl>
      <label><span>处方模板 code</span><input v-model="form.preview_and_submission.template_code" placeholder="由已发布配置提供"></label>
      <label class="check"><input v-model="form.preview_and_submission.doctor_confirmation" type="checkbox"><span>我已核对七步处方并确认提交 *</span></label>
      <p>处方提交后，设计员创建方案版本；授权组长内审通过后才发送医生确认；医生确认后才允许生产人员建立批次。</p>
    </div>

    <footer>
      <button type="button" :disabled="saving" @click="save(false)">保存草稿</button>
      <div><button v-if="section > 1" type="button" @click="section--">上一步</button><button v-if="section < 7" type="button" class="primary" @click="section++">下一步</button><button v-else type="button" class="primary" data-testid="orthodontic-prescription-submit" :disabled="saving || !canSubmit" @click="save(true)">提交七步处方</button></div>
    </footer>
  </section>
</template>

<style scoped>
.ortho-prescription{margin:16px 0;padding:16px;border:1px solid #cfe0f5;border-radius:12px;background:#f8fbff}.ortho-prescription>header{display:flex;justify-content:space-between;gap:12px}.ortho-prescription>header strong,.ortho-prescription>header small{display:block}.ortho-prescription>header small{margin-top:4px;color:#6e8098}.ortho-prescription>header b{height:26px;padding:4px 9px;border-radius:20px;background:#fff2cc;color:#8a5a00;font-size:12px}.ortho-prescription>header b.ready{background:#e8f7ee;color:#14733c}.ortho-prescription nav{display:grid;grid-template-columns:repeat(7,1fr);gap:5px;margin:16px 0}.ortho-prescription nav button{display:grid;place-items:center;gap:4px;min-height:58px;border:1px solid #dce6f2;border-radius:8px;background:#fff;color:#66758b;font-size:11px}.ortho-prescription nav button span{width:22px;height:22px;display:grid;place-items:center;border-radius:50%;background:#edf2f8}.ortho-prescription nav button.active{border-color:#2875e8;color:#155fc8}.ortho-prescription nav button.active span{background:#2875e8;color:#fff}.ortho-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.ortho-fields label,.ortho-review>label{display:grid;gap:6px}.ortho-fields label.full{grid-column:1/-1}.ortho-fields span,.ortho-review label span{font-size:12px;color:#536278}.ortho-fields input,.ortho-fields select,.ortho-fields textarea,.ortho-review input{width:100%;box-sizing:border-box;border:1px solid #d4deeb;border-radius:7px;padding:9px 10px;background:#fff}.ortho-fields label.check,.ortho-review label.check{display:flex;align-items:center;gap:8px}.ortho-fields label.check input,.ortho-review label.check input{width:auto}.ortho-review{display:grid;gap:13px}.ortho-review dl{display:grid;grid-template-columns:repeat(4,1fr);gap:9px;margin:0}.ortho-review dl div{padding:12px;border-radius:8px;background:#fff}.ortho-review dt{font-size:11px;color:#77869a}.ortho-review dd{margin:5px 0 0;font-weight:700}.ortho-review p{margin:0;padding:10px;background:#eef6ff;color:#36536f;border-radius:7px;font-size:12px}.ortho-prescription footer{display:flex;justify-content:space-between;margin-top:15px;padding-top:13px;border-top:1px solid #dfe8f2}.ortho-prescription footer div{display:flex;gap:8px}.ortho-prescription footer button{padding:8px 13px;border:1px solid #ced9e7;border-radius:7px;background:#fff}.ortho-prescription footer button.primary{background:#1768e5;border-color:#1768e5;color:#fff}.ortho-prescription footer button:disabled{opacity:.5}@media(max-width:900px){.ortho-prescription nav{grid-template-columns:repeat(4,1fr)}.ortho-fields,.ortho-review dl{grid-template-columns:1fr 1fr}}@media(max-width:600px){.ortho-fields,.ortho-review dl{grid-template-columns:1fr}.ortho-fields label.full{grid-column:auto}}
</style>
