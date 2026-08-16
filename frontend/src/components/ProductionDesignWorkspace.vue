<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

type LoginUser = {
  userId: string | number | null
  roles: string[]
  permissions: string[]
}

type ApiResponse<T> = {
  code: number
  msg: string
  data: T
}

type DesignFile = {
  file_id: number
  original_filename?: string
  name?: string
  content_type?: string | null
  file_size?: number | null
  created_at?: string | null
}

type ReviewHistory = {
  event_type?: string
  action?: string
  actor_user_id?: string | number | null
  actor_name?: string | null
  reason?: string | null
  from_status?: string | null
  to_status?: string | null
  created_at?: string | null
}

type DesignDraft = {
  draft_id: number
  order_id: number
  version: number
  uploader_user_id: string | number | null
  file_id?: number | null
  file_ids?: number[]
  files?: DesignFile[]
  file_count?: number
  status: string
  upload_note?: string | null
  submitted_at?: string | null
  internal_reject_reason?: string | null
  doctor_reject_reason?: string | null
  review_history?: ReviewHistory[]
}

type DesignTask = {
  task_id: number
  order_id: number
  order_no: string
  product_type: string
  order_status?: string
  status: string
  assigned_user_id?: string | number | null
  assigned_user_name?: string | null
  claimed_user_id?: string | number | null
  claimed_user_name?: string | null
  claimed_at?: string | null
  updated_at?: string | null
  latest_draft?: DesignDraft | null
  drafts?: DesignDraft[]
  review_history?: ReviewHistory[]
  allowed_actions?: string[]
}

type StaffOption = {
  user_id: string | number
  display_name: string
  user_type: string
  status: string
}

type MultipartInitiateResponse = {
  file_id: number
  upload_id: string
  part_size: number
  part_count: number
}

type MultipartPartUrlResponse = {
  upload_url: string
}

const props = defineProps<{
  activeRoute: string
  token: string
  user: LoginUser | null
}>()

const tasks = ref<DesignTask[]>([])
const loading = ref(false)
const pageError = ref('')
const pageResult = ref('')
const busyTaskId = ref<number | null>(null)
const previewingFileId = ref<number | null>(null)
const selectedFiles = ref<Record<number, File[]>>({})
const uploadedFileIds = ref<Record<number, number[]>>({})
const uploadNotes = ref<Record<number, string>>({})
const submissionKeys = ref<Record<number, string>>({})
const internalRejectReasons = ref<Record<number, string>>({})
const transferTargetIds = ref<Record<number, string>>({})
const transferReasons = ref<Record<number, string>>({})
const expandedTaskIds = ref<number[]>([])
const transferCandidates = ref<StaffOption[]>([])

const mode = computed<'POOL' | 'MINE' | 'REVIEW' | 'MANAGE'>(() => {
  if (props.activeRoute === '/production/design-tasks/pool') return 'POOL'
  if (props.activeRoute === '/production/design-reviews') return 'REVIEW'
  if (props.activeRoute === '/admin/design-tasks') return 'MANAGE'
  return 'MINE'
})

const heading = computed(() => {
  if (mode.value === 'POOL') return {
    eyebrow: '设计协同 / 领取',
    title: '设计任务池',
    description: '仅可领取尚未被他人领取的设计任务；并发领取以服务端结果为准。'
  }
  if (mode.value === 'REVIEW') return {
    eyebrow: '设计协同 / 内审',
    title: '设计内审',
      description: '由具备设计内审权限的负责人审核版本；客服不参与技术设计审核。'
  }
  if (mode.value === 'MANAGE') return {
    eyebrow: '设计协同 / 管理',
    title: '设计任务管理',
    description: '查看完整设计任务，按真实员工账号转派，并处理管理员可执行的内部审核。'
  }
  return {
    eyebrow: '设计协同 / 执行',
    title: '我的设计任务',
    description: '上传多文件版本、提交内审，并根据内审或医生意见继续追加新版本。'
  }
})

const emptyText = computed(() => {
  if (mode.value === 'POOL') return '当前没有可领取的设计任务'
  if (mode.value === 'REVIEW') return '当前没有待内部审核的设计版本'
  if (mode.value === 'MANAGE') return '当前没有设计任务'
  return '当前账号还没有领取设计任务'
})

function collection<T>(value: T[] | { items?: T[] } | null | undefined): T[] {
  if (Array.isArray(value)) return value
  return value?.items ?? []
}

async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
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
      const payload = await response.json() as { message?: string; msg?: string }
      detail = payload.message || payload.msg || ''
    } catch {
      // 非 JSON 响应使用状态码兜底。
    }
    throw new Error(detail || `请求失败（${response.status}）`)
  }
  const payload = await response.json() as ApiResponse<T> | T
  if (payload && typeof payload === 'object' && 'data' in payload) {
    return (payload as ApiResponse<T>).data
  }
  return payload as T
}

function endpoint() {
  if (mode.value === 'POOL') return '/design-tasks/pool'
  if (mode.value === 'REVIEW') return '/design-tasks/internal-review-queue'
  if (mode.value === 'MANAGE') return '/design-tasks/manage'
  return '/design-tasks/mine'
}

async function loadTransferCandidates() {
  if (mode.value !== 'MANAGE' || !props.token) {
    transferCandidates.value = []
    return
  }
  const allCandidates: StaffOption[] = []
  let page = 1
  let total = 0
  do {
    const response = await apiFetch<{ items?: StaffOption[]; total?: number; size?: number }>(
      `/staff/workload?page=${page}&size=100`
    )
    const items = collection(response)
    allCandidates.push(...items)
    total = response.total ?? allCandidates.length
    if (items.length === 0) break
    page += 1
  } while (allCandidates.length < total)
  transferCandidates.value = allCandidates.filter((staff) =>
    staff.user_type === 'WORKER' && staff.status === 'ACTIVE')
}

async function loadWorkspace() {
  const [, candidatesResult] = await Promise.allSettled([loadTasks(), loadTransferCandidates()])
  if (candidatesResult.status === 'rejected') {
    transferCandidates.value = []
    pageError.value ||= candidatesResult.reason instanceof Error
      ? candidatesResult.reason.message
      : '可转派员工加载失败'
  }
}

async function loadTasks() {
  if (!props.token) return
  loading.value = true
  pageError.value = ''
  try {
    const response = await apiFetch<DesignTask[] | { items?: DesignTask[] }>(endpoint())
    tasks.value = collection(response)
  } catch (error) {
    tasks.value = []
    pageError.value = error instanceof Error ? error.message : '设计任务加载失败'
  } finally {
    loading.value = false
  }
}

function allowed(task: DesignTask, action: string) {
  return (task.allowed_actions ?? []).includes(action)
}

function taskAssignee(task: DesignTask) {
  return task.assigned_user_name
    || task.claimed_user_name
    || (task.assigned_user_id ?? task.claimed_user_id ? `员工 #${task.assigned_user_id ?? task.claimed_user_id}` : '尚未领取')
}

function draftFiles(draft?: DesignDraft | null): DesignFile[] {
  if (!draft) return []
  if (draft.files?.length) return draft.files
  return (draft.file_ids ?? (draft.file_id ? [draft.file_id] : [])).map((fileId): DesignFile => ({ file_id: fileId }))
}

function latestDraft(task: DesignTask) {
  if (task.latest_draft) return task.latest_draft
  return [...(task.drafts ?? [])].sort((left, right) => right.version - left.version)[0] ?? null
}

function allDrafts(task: DesignTask) {
  const drafts = task.drafts?.length ? task.drafts : task.latest_draft ? [task.latest_draft] : []
  return [...drafts].sort((left, right) => right.version - left.version)
}

function statusLabel(status?: string | null) {
  const labels: Record<string, string> = {
    OPEN: '待领取',
    CLAIMED: '已领取',
    SUBMITTED: '已提交',
    INTERNAL_REVIEW: '内部审核中',
    INTERNAL_REJECTED: '内审退回',
    PENDING_REVIEW: '待内部审核',
    PENDING_DOCTOR: '待医生确认',
    PENDING_DOCTOR_CONFIRM: '待医生确认',
    DOCTOR_CONFIRMED: '医生已确认',
    DOCTOR_REJECTED: '医生要求修改',
    CANCELLED: '已取消',
    DRAFT: '草稿'
  }
  return status ? labels[status] ?? status : '状态未记录'
}

function productLabel(productType?: string | null) {
  const labels: Record<string, string> = {
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
  return productType ? labels[productType] ?? productType : '产品未记录'
}

function compactDateTime(value?: string | null) {
  if (!value) return '时间未记录'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date)
}

function fileSizeLabel(value?: number | null) {
  if (value == null) return '大小未记录'
  if (value < 1024 * 1024) return `${Math.max(1, Math.round(value / 1024))} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function toggleHistory(taskId: number) {
  expandedTaskIds.value = expandedTaskIds.value.includes(taskId)
    ? expandedTaskIds.value.filter((id) => id !== taskId)
    : [...expandedTaskIds.value, taskId]
}

function onFileSelection(taskId: number, event: Event) {
  const input = event.target as HTMLInputElement
  selectedFiles.value[taskId] = Array.from(input.files ?? [])
  uploadedFileIds.value[taskId] = []
  delete submissionKeys.value[taskId]
}

async function runTaskAction(task: DesignTask, action: () => Promise<unknown>, success: string) {
  busyTaskId.value = task.task_id
  pageError.value = ''
  pageResult.value = ''
  try {
    await action()
    pageResult.value = success
    await loadTasks()
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '操作失败'
  } finally {
    busyTaskId.value = null
  }
}

async function claimTask(task: DesignTask) {
  await runTaskAction(
    task,
    () => apiFetch(`/design-tasks/${task.task_id}/claim`, { method: 'POST', body: '{}' }),
    `订单 ${task.order_no} 的设计任务已领取`
  )
}

async function transferTask(task: DesignTask) {
  const target = transferTargetIds.value[task.task_id]?.trim() ?? ''
  const reason = transferReasons.value[task.task_id]?.trim() ?? ''
  if (!/^[1-9]\d*$/.test(target) || !reason) {
    pageError.value = '转派前请填写有效的目标员工 ID 和转派原因。'
    return
  }
  await runTaskAction(
    task,
    () => apiFetch(`/design-tasks/${task.task_id}/transfer`, {
      method: 'POST',
      body: JSON.stringify({ new_user_id: target, reason })
    }),
    `订单 ${task.order_no} 的设计任务已完成转派`
  )
}

async function uploadOne(orderId: number, file: File): Promise<number> {
  if (file.size > 500 * 1024 * 1024) throw new Error(`文件 ${file.name} 超过 500MB 限制`)
  const upload = await apiFetch<MultipartInitiateResponse>('/files/multipart/initiate', {
    method: 'POST',
    body: JSON.stringify({
      order_id: orderId,
      source_type: 'DESIGN_DRAFT',
      visibility: 'INTERNAL',
      original_filename: file.name,
      content_type: file.type || 'application/octet-stream',
      file_size: file.size,
      part_size: 5 * 1024 * 1024
    })
  })
  const parts: Array<{ part_number: number; etag: string }> = []
  for (let partNumber = 1; partNumber <= upload.part_count; partNumber += 1) {
    const part = await apiFetch<MultipartPartUrlResponse>(`/files/${upload.file_id}/multipart/part-url`, {
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
  await apiFetch(`/files/${upload.file_id}/multipart/complete`, {
    method: 'POST',
    body: JSON.stringify({ upload_id: upload.upload_id, parts })
  })
  return upload.file_id
}

async function createDraft(task: DesignTask) {
  const files = selectedFiles.value[task.task_id] ?? []
  if (!files.length && !(uploadedFileIds.value[task.task_id]?.length)) {
    pageError.value = '请选择至少一个设计文件。'
    return
  }
  if (!submissionKeys.value[task.task_id]) submissionKeys.value[task.task_id] = crypto.randomUUID()
  const submissionKey = submissionKeys.value[task.task_id]
  await runTaskAction(task, async () => {
    let fileIds = uploadedFileIds.value[task.task_id] ?? []
    for (const file of files.slice(fileIds.length)) {
      fileIds = [...fileIds, await uploadOne(task.order_id, file)]
      uploadedFileIds.value[task.task_id] = fileIds
    }
    await apiFetch(`/orders/${task.order_id}/design-drafts`, {
      method: 'POST',
      body: JSON.stringify({
        file_ids: fileIds,
        submission_key: submissionKey,
        upload_note: uploadNotes.value[task.task_id]?.trim() || null
      })
    })
    selectedFiles.value[task.task_id] = []
    uploadedFileIds.value[task.task_id] = []
    uploadNotes.value[task.task_id] = ''
    delete submissionKeys.value[task.task_id]
  }, `订单 ${task.order_no} 已新增一个完整设计版本`)
}

async function previewDesignFile(fileId: number) {
  previewingFileId.value = fileId
  pageError.value = ''
  try {
    const result = await apiFetch<{ preview_url: string }>(`/files/${fileId}/preview-url`)
    window.open(result.preview_url, '_blank', 'noopener,noreferrer')
  } catch (error) {
    pageError.value = error instanceof Error ? error.message : '设计文件预览失败'
  } finally {
    previewingFileId.value = null
  }
}

async function submitDraft(task: DesignTask) {
  const draft = latestDraft(task)
  if (!draft) return
  await runTaskAction(
    task,
    () => apiFetch(`/orders/${task.order_id}/design-drafts/${draft.draft_id}/submit`, {
      method: 'POST',
      body: JSON.stringify({ submission_key: crypto.randomUUID() })
    }),
    `设计稿 V${draft.version} 已提交内部审核`
  )
}

async function internalReview(task: DesignTask, action: 'APPROVE' | 'REJECT') {
  const draft = latestDraft(task)
  if (!draft) return
  const reason = internalRejectReasons.value[task.task_id]?.trim() ?? ''
  if (action === 'REJECT' && !reason) {
    pageError.value = '退回设计稿前必须填写修改原因。'
    return
  }
  await runTaskAction(
    task,
    () => apiFetch(`/orders/${task.order_id}/design-drafts/${draft.draft_id}/internal-review`, {
      method: 'POST',
      body: JSON.stringify({
        action,
        internal_reject_reason: action === 'REJECT' ? reason : null,
        reason: action === 'REJECT' ? reason : null
      })
    }),
    action === 'APPROVE'
      ? `设计稿 V${draft.version} 已通过内审并提交医生确认`
      : `设计稿 V${draft.version} 已退回原设计人员`
  )
}

onMounted(loadWorkspace)
watch(() => props.activeRoute, () => void loadWorkspace())
</script>

<template>
  <section class="design-workspace route-panel" data-testid="production-design-workspace">
    <header class="design-heading">
      <div>
        <span>{{ heading.eyebrow }}</span>
        <h1>{{ heading.title }}</h1>
        <p>{{ heading.description }}</p>
      </div>
      <button type="button" :disabled="loading" @click="loadWorkspace">
        {{ loading ? '刷新中…' : '刷新任务' }}
      </button>
    </header>

    <div class="design-scope-note">
      <strong>{{ mode === 'REVIEW' ? '负责人权限点' : '当前执行口径' }}</strong>
      <p v-if="mode === 'REVIEW'">只有服务端返回 INTERNAL_REVIEW 操作权限时才显示审核按钮；审核通过后文件才对医生可见。</p>
      <p v-else>设计文件先上传为 INTERNAL；同一批多文件在创建版本时统一提交，失败重试沿用同一 submission_key。</p>
    </div>

    <p v-if="pageError" class="design-feedback is-error">{{ pageError }}</p>
    <p v-if="pageResult" class="design-feedback is-success">{{ pageResult }}</p>

    <div v-if="loading && tasks.length === 0" class="design-empty">设计任务加载中…</div>
    <div v-else-if="tasks.length === 0" class="design-empty">{{ emptyText }}</div>
    <div v-else class="design-grid">
      <article v-for="task in tasks" :key="task.task_id" class="design-card">
        <header>
          <div>
            <small>{{ task.order_no }} · 任务 #{{ task.task_id }}</small>
            <h2>{{ productLabel(task.product_type) }}</h2>
          </div>
          <span :class="{ rejected: task.status.includes('REJECT'), confirmed: task.status === 'DOCTOR_CONFIRMED' }">
            {{ statusLabel(task.status) }}
          </span>
        </header>

        <dl>
          <div><dt>当前执行人</dt><dd>{{ taskAssignee(task) }}</dd></div>
          <div><dt>最近更新</dt><dd>{{ compactDateTime(task.updated_at || task.claimed_at) }}</dd></div>
          <div><dt>当前版本</dt><dd>{{ latestDraft(task) ? `V${latestDraft(task)?.version}` : '尚未上传' }}</dd></div>
        </dl>

        <section v-if="latestDraft(task)" class="draft-summary">
          <div>
            <strong>设计稿 V{{ latestDraft(task)?.version }}</strong>
            <span>{{ statusLabel(latestDraft(task)?.status) }}</span>
          </div>
          <p v-if="latestDraft(task)?.upload_note">{{ latestDraft(task)?.upload_note }}</p>
          <p v-if="latestDraft(task)?.internal_reject_reason" class="reject-note">内审意见：{{ latestDraft(task)?.internal_reject_reason }}</p>
          <p v-if="latestDraft(task)?.doctor_reject_reason" class="reject-note">医生意见：{{ latestDraft(task)?.doctor_reject_reason }}</p>
          <ul v-if="draftFiles(latestDraft(task)).length">
            <li v-for="file in draftFiles(latestDraft(task))" :key="file.file_id">
              <span>
                {{ file.original_filename || file.name || `设计文件 #${file.file_id}` }}
                <small>{{ fileSizeLabel(file.file_size) }}</small>
              </span>
              <button type="button" :disabled="previewingFileId === file.file_id" @click="previewDesignFile(file.file_id)">
                {{ previewingFileId === file.file_id ? '加载中…' : '预览' }}
              </button>
            </li>
          </ul>
        </section>

        <div v-if="allowed(task, 'CLAIM')" class="design-actions">
          <button class="primary" type="button" :disabled="busyTaskId === task.task_id" @click="claimTask(task)">
            {{ busyTaskId === task.task_id ? '领取中…' : '领取任务' }}
          </button>
        </div>

        <section v-if="allowed(task, 'UPLOAD_DRAFT')" class="draft-upload">
          <label>
            <span>选择本次版本文件（可多选）</span>
            <input type="file" multiple @change="onFileSelection(task.task_id, $event)">
          </label>
          <p v-if="selectedFiles[task.task_id]?.length">
            已选择 {{ selectedFiles[task.task_id].length }} 个文件：
            {{ selectedFiles[task.task_id].map((file) => file.name).join('、') }}
          </p>
          <label>
            <span>版本说明</span>
            <textarea v-model="uploadNotes[task.task_id]" rows="2" placeholder="说明本次修改或设计重点"></textarea>
          </label>
          <button class="primary" type="button" :disabled="busyTaskId === task.task_id" @click="createDraft(task)">
            {{ busyTaskId === task.task_id ? '上传并建版中…' : '上传并创建版本' }}
          </button>
        </section>

        <div v-if="allowed(task, 'SUBMIT_DRAFT') && latestDraft(task)" class="design-actions">
          <button class="primary" type="button" :disabled="busyTaskId === task.task_id" @click="submitDraft(task)">
            提交 V{{ latestDraft(task)?.version }} 内审
          </button>
        </div>

        <section v-if="allowed(task, 'INTERNAL_REVIEW') && latestDraft(task)" class="internal-review">
          <label>
            <span>内审退回原因</span>
            <textarea v-model="internalRejectReasons[task.task_id]" rows="2" placeholder="退回时必填；通过时无需填写"></textarea>
          </label>
          <div class="design-actions">
            <button type="button" :disabled="busyTaskId === task.task_id" @click="internalReview(task, 'REJECT')">退回修改</button>
            <button class="primary" type="button" :disabled="busyTaskId === task.task_id" @click="internalReview(task, 'APPROVE')">通过并提交医生</button>
          </div>
        </section>

        <section v-if="allowed(task, 'TRANSFER_TASK')" class="task-transfer">
          <strong>管理员转派</strong>
          <div>
            <select v-model="transferTargetIds[task.task_id]" aria-label="目标设计人员">
              <option value="">选择目标设计人员</option>
              <option v-for="staff in transferCandidates" :key="String(staff.user_id)" :value="String(staff.user_id)">
                {{ staff.display_name }}（{{ staff.user_id }}）
              </option>
            </select>
            <input v-model="transferReasons[task.task_id]" placeholder="转派原因（必填）">
            <button type="button" :disabled="busyTaskId === task.task_id" @click="transferTask(task)">确认转派</button>
          </div>
        </section>

        <footer v-if="allDrafts(task).length > 1 || task.review_history?.length">
          <button type="button" @click="toggleHistory(task.task_id)">
            {{ expandedTaskIds.includes(task.task_id) ? '收起版本与审核记录' : '查看版本与审核记录' }}
          </button>
          <div v-if="expandedTaskIds.includes(task.task_id)" class="history-list">
            <article v-for="draft in allDrafts(task)" :key="draft.draft_id">
              <strong>V{{ draft.version }} · {{ statusLabel(draft.status) }}</strong>
              <span>{{ compactDateTime(draft.submitted_at) }}</span>
              <p v-if="draft.internal_reject_reason || draft.doctor_reject_reason">
                {{ draft.internal_reject_reason || draft.doctor_reject_reason }}
              </p>
              <div v-if="draftFiles(draft).length" class="history-file-actions">
                <button
                  v-for="file in draftFiles(draft)"
                  :key="file.file_id"
                  type="button"
                  :disabled="previewingFileId === file.file_id"
                  @click="previewDesignFile(file.file_id)"
                >
                  {{ previewingFileId === file.file_id ? '加载中…' : `预览文件 #${file.file_id}` }}
                </button>
              </div>
            </article>
            <article v-for="(item, index) in task.review_history ?? []" :key="`${item.event_type || item.action}-${item.created_at}-${index}`">
              <strong>{{ item.actor_name || (item.actor_user_id ? `账号 #${item.actor_user_id}` : '系统') }} · {{ item.event_type || item.action || '状态变更' }}</strong>
              <span>{{ compactDateTime(item.created_at) }}</span>
              <p v-if="item.reason">{{ item.reason }}</p>
            </article>
          </div>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.design-workspace {
  min-height: calc(100vh - 96px);
  padding: 28px;
  color: #16312d;
  background: #f4f7f5;
}

.design-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 18px;
}

.design-heading span {
  color: #70817e;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: .12em;
  text-transform: uppercase;
}

.design-heading h1 {
  margin: 6px 0;
  font-size: 30px;
}

.design-heading p,
.design-scope-note p {
  margin: 0;
  color: #64736f;
}

button {
  border: 1px solid #cbd6d2;
  border-radius: 9px;
  padding: 9px 14px;
  color: #23423c;
  background: #fff;
  cursor: pointer;
}

button.primary {
  border-color: #1e725f;
  color: #fff;
  background: #1e725f;
}

button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.design-scope-note,
.design-feedback,
.design-empty {
  margin: 0 0 18px;
  border: 1px solid #dce5e2;
  border-radius: 12px;
  padding: 14px 16px;
  background: #fff;
}

.design-scope-note strong {
  display: block;
  margin-bottom: 4px;
}

.design-feedback.is-error {
  border-color: #f1c8c4;
  color: #9c3028;
  background: #fff4f2;
}

.design-feedback.is-success {
  border-color: #b9dccf;
  color: #17604f;
  background: #eef9f5;
}

.design-empty {
  padding: 64px 20px;
  color: #71817d;
  text-align: center;
}

.design-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 18px;
}

.design-card {
  border: 1px solid #d9e2df;
  border-radius: 16px;
  padding: 18px;
  background: #fff;
  box-shadow: 0 8px 24px rgb(36 67 60 / 6%);
}

.design-card > header {
  display: flex;
  justify-content: space-between;
  gap: 18px;
}

.design-card > header small {
  color: #73827f;
}

.design-card h2 {
  margin: 5px 0 0;
  font-size: 20px;
}

.design-card > header > span {
  height: fit-content;
  border-radius: 999px;
  padding: 5px 9px;
  color: #7d5b15;
  background: #fff3d5;
}

.design-card > header > span.rejected {
  color: #a0362e;
  background: #ffebe8;
}

.design-card > header > span.confirmed {
  color: #17604f;
  background: #e5f6f0;
}

dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 16px 0;
}

dl div {
  border-radius: 10px;
  padding: 10px;
  background: #f6f8f7;
}

dt {
  margin-bottom: 3px;
  color: #7a8985;
  font-size: 12px;
}

dd {
  margin: 0;
  font-weight: 700;
}

.draft-summary,
.draft-upload,
.internal-review,
.task-transfer {
  margin-top: 14px;
  border-top: 1px solid #edf1ef;
  padding-top: 14px;
}

.draft-summary > div,
.task-transfer > div,
.design-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.draft-summary p,
.draft-upload p,
.history-list p {
  margin: 8px 0;
  color: #62726e;
  font-size: 13px;
}

.draft-summary .reject-note {
  color: #a0362e;
}

.draft-summary ul {
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.draft-summary li {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  color: #4d625d;
  font-size: 13px;
}

label {
  display: grid;
  gap: 6px;
  margin-bottom: 10px;
  color: #50635e;
  font-size: 13px;
  font-weight: 700;
}

input,
select,
textarea {
  box-sizing: border-box;
  width: 100%;
  border: 1px solid #cdd8d4;
  border-radius: 8px;
  padding: 9px 10px;
  color: #203d37;
  background: #fff;
  font: inherit;
}

.task-transfer > div {
  margin-top: 8px;
}

.design-card > footer {
  margin-top: 14px;
}

.history-list {
  display: grid;
  gap: 8px;
  margin-top: 10px;
}

.history-list article {
  border-left: 3px solid #b9ccc6;
  padding: 4px 0 4px 10px;
}

.history-list strong,
.history-list span {
  display: block;
}

.history-list span {
  margin-top: 2px;
  color: #7b8986;
  font-size: 12px;
}

@media (max-width: 760px) {
  .design-workspace {
    padding: 18px;
  }

  .design-heading,
  .task-transfer > div {
    flex-direction: column;
  }

  .design-grid {
    grid-template-columns: 1fr;
  }

  dl {
    grid-template-columns: 1fr;
  }
}
</style>
