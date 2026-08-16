<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type ApiResponse<T> = { data: T; msg?: string }
type Plan = {
  plan_version_id: number
  version_no: number
  plan_status: string
  plan_file_id: number | null
  design_note: string | null
  plan_snapshot?: Record<string, unknown>
}
type Batch = {
  production_batch_id: number
  batch_no: number
  step_from: number
  step_to: number
  quantity: number
  batch_status: string
}
type ChangeRequest = {
  change_request_id: number
  request_type: string
  request_status: string
  reason: string
}
type OrthodonticCase = {
  configured: boolean
  case_status?: string
  total_steps?: number | null
  plan_versions?: Plan[]
  production_batches?: Batch[]
  change_requests?: ChangeRequest[]
}

const props = defineProps<{
  token: string
  orderId: number | string
  mode: 'DOCTOR' | 'INTERNAL'
  permissions?: string[]
  latestDesignFileId?: number | null
}>()

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const result = ref('')
const data = ref<OrthodonticCase | null>(null)
const designNote = ref('')
const reviewReasons = ref<Record<number, string>>({})
const batchFrom = ref(1)
const batchTo = ref<number | null>(null)
const changeType = ref<'STAGE_ADJUSTMENT' | 'FOLLOW_UP_PROCESSING'>('STAGE_ADJUSTMENT')
const changeReason = ref('')

const plans = computed(() => data.value?.plan_versions ?? [])
const pendingInternal = computed(() => plans.value.filter((plan) => plan.plan_status === 'PENDING_INTERNAL_REVIEW'))
const pendingDoctor = computed(() => plans.value.filter((plan) => plan.plan_status === 'PENDING_DOCTOR_REVIEW'))
const approvedPlan = computed(() => plans.value.find((plan) => plan.plan_status === 'DOCTOR_APPROVED') ?? null)
const canInternalReview = computed(() => props.permissions?.includes('design-draft:internal-review') ?? true)
const canBatch = computed(() => props.permissions?.includes('workflow:orthodontic-batch:manage') ?? true)

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
      const body = await response.json() as { message?: string; msg?: string }
      detail = body.message || body.msg || ''
    } catch {
      detail = ''
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  return (await response.json() as ApiResponse<T>).data
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api<OrthodonticCase>(`/orders/${props.orderId}/orthodontic-case`)
    batchTo.value ||= data.value.total_steps ?? null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '正畸方案加载失败'
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<void>, success: string) {
  busy.value = true
  error.value = ''
  result.value = ''
  try {
    await action()
    result.value = success
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作失败'
  } finally {
    busy.value = false
  }
}

async function createPlan() {
  await run(async () => {
    await api(`/orders/${props.orderId}/orthodontic-plan-versions`, {
      method: 'POST',
      body: JSON.stringify({
        plan_file_id: props.latestDesignFileId || null,
        plan_snapshot: {
          source: 'DESIGN_TASK',
          total_steps: data.value?.total_steps ?? null,
          design_file_id: props.latestDesignFileId || null
        },
        design_note: designNote.value || null
      })
    })
    designNote.value = ''
  }, '已创建不可变方案版本并送内部审核')
}

async function review(plan: Plan, gate: 'internal' | 'doctor', decision: 'APPROVE' | 'REJECT') {
  const reason = reviewReasons.value[plan.plan_version_id]?.trim() || ''
  if (decision === 'REJECT' && !reason) {
    error.value = '驳回必须填写原因'
    return
  }
  await run(async () => {
    await api(`/orthodontic-plan-versions/${plan.plan_version_id}/${gate}-review`, {
      method: 'POST',
      body: JSON.stringify({ decision, reason: reason || null })
    })
  }, decision === 'APPROVE' ? '审核已通过' : '方案已驳回，原版本保留')
}

async function createBatch() {
  if (!approvedPlan.value || !batchTo.value) return
  await run(async () => {
    await api(`/orders/${props.orderId}/orthodontic-production-batches`, {
      method: 'POST',
      body: JSON.stringify({
        plan_version_id: approvedPlan.value!.plan_version_id,
        step_from: Number(batchFrom.value),
        step_to: Number(batchTo.value)
      })
    })
  }, '生产批次已建立；后续修改不会重写此批次')
}

async function createChangeRequest() {
  if (!approvedPlan.value || !changeReason.value.trim()) {
    error.value = '请选择已确认方案并填写申请原因'
    return
  }
  await run(async () => {
    await api(`/orders/${props.orderId}/orthodontic-change-requests`, {
      method: 'POST',
      body: JSON.stringify({
        source_plan_version_id: approvedPlan.value!.plan_version_id,
        request_type: changeType.value,
        reason: changeReason.value.trim()
      })
    })
    changeReason.value = ''
  }, '变更申请已创建；审批后应建立新方案版本或新批次')
}

onMounted(load)
watch(() => props.orderId, load)
</script>

<template>
  <section class="ortho-flow-panel" data-testid="orthodontic-workflow-panel">
    <header><div><strong>正畸方案与批次</strong><small>方案内审 → 医生确认 → 生产批次，三道门禁独立</small></div><button type="button" :disabled="loading" @click="load">刷新</button></header>
    <p v-if="error" class="feedback error">{{ error }}</p>
    <p v-if="result" class="feedback success">{{ result }}</p>
    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!data?.configured" class="empty">医生尚未提交七步正畸处方。</div>
    <template v-else>
      <div class="summary"><span>病例状态 <b>{{ data.case_status }}</b></span><span>总步数 <b>{{ data.total_steps ?? '未确认' }}</b></span><span>方案版本 <b>{{ plans.length }}</b></span><span>生产批次 <b>{{ data.production_batches?.length ?? 0 }}</b></span></div>

      <template v-if="mode === 'INTERNAL'">
        <section class="block">
          <h4>创建新方案版本</h4>
          <textarea v-model="designNote" rows="2" placeholder="本版设计说明"></textarea>
          <small>关联设计文件：{{ latestDesignFileId ? `#${latestDesignFileId}` : '未指定；仍可保存结构化方案快照' }}</small>
          <button type="button" :disabled="busy" @click="createPlan">创建版本并送内审</button>
        </section>
        <section v-if="pendingInternal.length && canInternalReview" class="block">
          <h4>待内部审核</h4>
          <article v-for="plan in pendingInternal" :key="plan.plan_version_id">
            <strong>方案 V{{ plan.version_no }}</strong>
            <input v-model="reviewReasons[plan.plan_version_id]" placeholder="退回原因（驳回必填）">
            <button type="button" :disabled="busy" @click="review(plan, 'internal', 'REJECT')">驳回</button>
            <button type="button" class="primary" :disabled="busy" @click="review(plan, 'internal', 'APPROVE')">通过并交医生</button>
          </article>
        </section>
        <section v-if="approvedPlan && canBatch" class="block">
          <h4>按确认方案建立生产批次</h4>
          <div class="row"><input v-model.number="batchFrom" type="number" min="1" placeholder="起始步"><input v-model.number="batchTo" type="number" min="1" :max="data.total_steps ?? undefined" placeholder="结束步"><button type="button" class="primary" :disabled="busy" @click="createBatch">建立批次</button></div>
          <article v-for="batch in data.production_batches ?? []" :key="batch.production_batch_id"><strong>批次 {{ batch.batch_no }}</strong><span>第 {{ batch.step_from }}～{{ batch.step_to }} 步 · {{ batch.quantity }} 副 · {{ batch.batch_status }}</span></article>
        </section>
      </template>

      <template v-else>
        <section v-if="pendingDoctor.length" class="block">
          <h4>待医生确认方案</h4>
          <article v-for="plan in pendingDoctor" :key="plan.plan_version_id">
            <strong>方案 V{{ plan.version_no }}</strong>
            <span>{{ plan.design_note || '请核对当前方案文件和移动策略' }}</span>
            <input v-model="reviewReasons[plan.plan_version_id]" placeholder="修改意见（驳回必填）">
            <button type="button" :disabled="busy" @click="review(plan, 'doctor', 'REJECT')">驳回并保留历史</button>
            <button type="button" class="primary" :disabled="busy" @click="review(plan, 'doctor', 'APPROVE')">确认当前方案</button>
          </article>
        </section>
        <section v-if="approvedPlan" class="block">
          <h4>阶段调整 / 后续加工申请</h4>
          <div class="row"><select v-model="changeType"><option value="STAGE_ADJUSTMENT">阶段调整</option><option value="FOLLOW_UP_PROCESSING">后续加工</option></select><input v-model="changeReason" placeholder="申请原因"><button type="button" :disabled="busy" @click="createChangeRequest">提交申请</button></div>
          <article v-for="item in data.change_requests ?? []" :key="item.change_request_id"><strong>{{ item.request_type }}</strong><span>{{ item.reason }} · {{ item.request_status }}</span></article>
        </section>
      </template>

      <section class="versions"><article v-for="plan in plans" :key="plan.plan_version_id"><strong>V{{ plan.version_no }}</strong><span>{{ plan.plan_status }}</span><small>{{ plan.design_note || '无备注' }}</small></article></section>
    </template>
  </section>
</template>

<style scoped>
.ortho-flow-panel{display:grid;gap:12px;margin:14px 0;padding:14px;border:1px solid #cbdcf1;border-radius:10px;background:#f8fbff;color:#26384c}.ortho-flow-panel>header{display:flex;justify-content:space-between;align-items:center}.ortho-flow-panel>header strong,.ortho-flow-panel>header small{display:block}.ortho-flow-panel>header small{margin-top:3px;color:#6f8094}.ortho-flow-panel button{border:1px solid #ccd7e4;border-radius:6px;padding:7px 10px;background:#fff}.ortho-flow-panel button.primary{background:#1768e5;border-color:#1768e5;color:#fff}.feedback,.empty{margin:0;padding:10px;border-radius:6px}.feedback.error{background:#fff0f0;color:#a62b26}.feedback.success{background:#eaf8ef;color:#166b3a}.empty{background:#f1f5f9;color:#64748b}.summary{display:grid;grid-template-columns:repeat(4,1fr);gap:7px}.summary span{padding:9px;background:#fff;border-radius:6px;font-size:11px;color:#718096}.summary b{display:block;margin-top:3px;color:#21364d;font-size:13px}.block{display:grid;gap:8px;padding:11px;background:#fff;border-radius:8px}.block h4{margin:0}.block textarea,.block input,.block select{box-sizing:border-box;width:100%;border:1px solid #ced9e7;border-radius:6px;padding:8px}.block article{display:grid;grid-template-columns:110px 1fr auto auto;align-items:center;gap:8px;padding:8px 0;border-top:1px solid #e7edf4}.block small,.block article span{color:#718096}.row{display:flex;gap:8px}.versions{display:flex;flex-wrap:wrap;gap:7px}.versions article{display:grid;min-width:130px;padding:8px;background:#eef4fb;border-radius:6px}.versions span,.versions small{font-size:11px;color:#61748a}@media(max-width:800px){.summary{grid-template-columns:1fr 1fr}.block article{grid-template-columns:1fr}.row{display:grid}}
</style>
