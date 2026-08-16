<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

// TASK-034 E 批次：导出管控与留痕。
//
// 客户三条要求在界面上的落点：
//   「导出需要反复确认」  —— 敏感数据集要先勾确认框、再过一道浏览器确认，最后还要他人审批；
//   「客户信息、地址、账单的导出是需要批准的」—— 敏感项提交后进入待审批，自己批不了；
//   「别的数据需要导出留痕」—— 留痕页展示操作人、时间、范围、行数、字段清单五项。
//
// 医生端没有任何导出入口，这是唯一的导出界面。

type ApiResponse<T> = { code: number; msg: string; data: T }

type Dataset = {
  dataset_code: string
  display_name: string
  sensitivity: 'SENSITIVE' | 'NORMAL'
  permission_code: string | null
  field_list: string[]
  description: string | null
  requires_approval: boolean
  available_to_me: boolean
}

type ExportRequest = {
  export_request_id: number
  request_no: string
  dataset_code: string
  dataset_name: string | null
  sensitivity: string
  filters: Record<string, unknown>
  reason: string | null
  requested_by_user_id: number | null
  requested_by_name: string | null
  requested_at: string
  approval_status: 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED'
  approved_by_name: string | null
  approved_at: string | null
  approval_comment: string | null
  download_count: number
  last_downloaded_at: string | null
  downloadable: boolean
}

type ExportAudit = {
  export_audit_id: number
  request_no: string
  dataset_code: string
  dataset_name: string | null
  sensitivity: string
  operator_name: string | null
  exported_at: string
  filters: Record<string, unknown>
  row_count: number
  field_list: string[]
  approved_by_name: string | null
}

const props = defineProps<{ activeRoute: string; token: string }>()

const loading = ref(false)
const error = ref('')
const notice = ref('')

const datasets = ref<Dataset[]>([])
const requests = ref<ExportRequest[]>([])
const audits = ref<ExportAudit[]>([])

const draft = ref({
  dataset_code: '',
  created_from: '',
  created_to: '',
  status: '',
  reason: '',
  acknowledged: false
})

const selectedDataset = computed(
  () => datasets.value.find((item) => item.dataset_code === draft.value.dataset_code) ?? null
)
const statusLabels: Record<string, string> = {
  NOT_REQUIRED: '无需审批',
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回'
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await authenticatedFetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${props.token}`,
      ...(options.headers ?? {})
    }
  })
  if (!response.ok) {
    let detail = String(response.status)
    try {
      const body = await response.json()
      detail = body?.message || body?.msg || detail
    } catch {
      // 非 JSON 错误响应保留状态码
    }
    throw new Error(response.status === 403 ? `无权执行该操作（${detail}）` : `请求失败：${detail}`)
  }
  const payload = await response.json() as ApiResponse<T>
  return payload.data
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    if (props.activeRoute === '/admin/export/center') {
      const [datasetList, requestList] = await Promise.all([
        request<Dataset[]>('/exports/datasets'),
        request<ExportRequest[]>('/exports')
      ])
      datasets.value = datasetList
      requests.value = requestList
      if (!draft.value.dataset_code && datasetList.length) {
        draft.value.dataset_code = datasetList.find((item) => item.available_to_me)?.dataset_code ?? ''
      }
    } else {
      audits.value = await request<ExportAudit[]>('/exports/audits')
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function buildFilters() {
  const filters: Record<string, string> = {}
  if (draft.value.created_from) filters.created_from = draft.value.created_from
  if (draft.value.created_to) filters.created_to = draft.value.created_to
  if (draft.value.status.trim()) filters.status = draft.value.status.trim()
  return filters
}

async function submitRequest() {
  const dataset = selectedDataset.value
  if (!dataset) return
  // 第一道确认：必须勾选。后端也会校验 acknowledged，界面上绕过去也没用。
  if (!draft.value.acknowledged) {
    error.value = '请先勾选导出确认'
    return
  }
  // 第二道确认：敏感数据再弹一次，对应客户「导出需要反复确认」。
  if (dataset.requires_approval
    && !window.confirm(`「${dataset.display_name}」属敏感数据，提交后需要他人批准才能下载。确认提交申请？`)) {
    return
  }
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await request<ExportRequest>('/exports', {
      method: 'POST',
      body: JSON.stringify({
        dataset_code: dataset.dataset_code,
        filters: buildFilters(),
        reason: draft.value.reason.trim() || null,
        acknowledged: true
      })
    })
    notice.value = created.approval_status === 'PENDING'
      ? `申请 ${created.request_no} 已提交，等待审批后才能下载`
      : `申请 ${created.request_no} 已创建，可直接下载（本次导出会留痕）`
    draft.value.acknowledged = false
    draft.value.reason = ''
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '提交失败'
  } finally {
    loading.value = false
  }
}

async function decide(item: ExportRequest, approved: boolean) {
  const comment = window.prompt(approved ? '批准说明（可留空）' : '驳回原因', '')
  if (comment === null) return
  loading.value = true
  error.value = ''
  try {
    await request(`/exports/${item.export_request_id}/${approved ? 'approve' : 'reject'}`, {
      method: 'POST',
      body: JSON.stringify({ comment })
    })
    notice.value = `${item.request_no} 已${approved ? '批准' : '驳回'}`
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作失败'
  } finally {
    loading.value = false
  }
}

async function download(item: ExportRequest) {
  loading.value = true
  error.value = ''
  try {
    const response = await authenticatedFetch(`/exports/${item.export_request_id}/download`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${props.token}` }
    })
    if (!response.ok) {
      throw new Error(response.status === 409 ? '该申请当前不可下载' : `下载失败（${response.status}）`)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${item.dataset_code}-${item.request_no}.csv`
    anchor.click()
    URL.revokeObjectURL(url)
    notice.value = `${item.request_no} 已导出，本次导出已留痕`
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '下载失败'
  } finally {
    loading.value = false
  }
}

function filterSummary(filters: Record<string, unknown>) {
  const parts = Object.entries(filters ?? {})
    .filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => `${key}=${value}`)
  return parts.length ? parts.join('，') : '全部'
}

watch(() => props.activeRoute, load, { immediate: true })
</script>

<template>
  <div class="export-shell">
    <p v-if="error" class="export-alert is-error">{{ error }}</p>
    <p v-else-if="notice" class="export-alert is-ok">{{ notice }}</p>
    <p v-if="loading" class="export-alert">处理中…</p>

    <!-- 导出中心 -->
    <template v-if="activeRoute === '/admin/export/center'">
      <section class="export-card">
        <header>
          <div><h2>申请数据导出</h2><small>客户信息、地址、账单与价格属敏感数据，提交后需他人批准；其余导出直接留痕</small></div>
        </header>
        <form class="export-form" data-testid="export-request-form" @submit.prevent="submitRequest">
          <label>
            <span>数据集</span>
            <select v-model="draft.dataset_code" required>
              <option value="" disabled>请选择</option>
              <option
                v-for="item in datasets"
                :key="item.dataset_code"
                :value="item.dataset_code"
                :disabled="!item.available_to_me"
              >{{ item.display_name }}{{ item.requires_approval ? '（敏感 · 需审批）' : '' }}{{ item.available_to_me ? '' : ' · 无权限' }}</option>
            </select>
          </label>
          <label><span>创建日期从</span><input v-model="draft.created_from" type="date"></label>
          <label><span>创建日期至</span><input v-model="draft.created_to" type="date"></label>
          <label><span>状态筛选</span><input v-model="draft.status" placeholder="留空为全部"></label>
          <label class="is-wide"><span>导出事由</span><input v-model="draft.reason" placeholder="敏感数据导出建议填写事由，便于审批"></label>

          <div v-if="selectedDataset" class="export-fields">
            <strong>本次将导出的字段</strong>
            <p>{{ selectedDataset.field_list.join('、') }}</p>
            <p v-if="selectedDataset.description" class="export-hint">{{ selectedDataset.description }}</p>
          </div>

          <label class="is-wide export-ack">
            <input v-model="draft.acknowledged" type="checkbox" data-testid="export-acknowledge">
            <span>我确认本次导出的范围与字段无误，并对导出数据的使用负责</span>
          </label>
          <div class="is-wide">
            <button type="submit" class="export-primary" :disabled="loading || !draft.dataset_code">
              {{ selectedDataset?.requires_approval ? '提交审批申请' : '创建导出并留痕' }}
            </button>
          </div>
        </form>
      </section>

      <section class="export-card">
        <header><div><h2>导出申请</h2><small>敏感申请由他人审批；申请人本人不能批自己的申请</small></div></header>
        <table class="export-table" data-testid="export-request-table">
          <thead><tr><th>申请单号</th><th>数据集</th><th>范围</th><th>申请人</th><th>状态</th><th>下载次数</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in requests" :key="item.export_request_id">
              <td><strong>{{ item.request_no }}</strong><small>{{ item.requested_at }}</small></td>
              <td>{{ item.dataset_name || item.dataset_code }}<em v-if="item.sensitivity === 'SENSITIVE'" class="export-tag">敏感</em></td>
              <td class="export-range">{{ filterSummary(item.filters) }}</td>
              <td>{{ item.requested_by_name || '-' }}</td>
              <td>
                <span :class="`export-status is-${item.approval_status.toLowerCase()}`">{{ statusLabels[item.approval_status] }}</span>
                <small v-if="item.approved_by_name">{{ item.approved_by_name }}</small>
              </td>
              <td>{{ item.download_count }}</td>
              <td class="export-actions">
                <button v-if="item.downloadable" type="button" :disabled="loading" @click="download(item)">下载</button>
                <template v-if="item.approval_status === 'PENDING'">
                  <button type="button" :disabled="loading" @click="decide(item, true)">批准</button>
                  <button type="button" :disabled="loading" @click="decide(item, false)">驳回</button>
                </template>
              </td>
            </tr>
            <tr v-if="!requests.length"><td colspan="7" class="export-empty">暂无导出申请</td></tr>
          </tbody>
        </table>
      </section>
    </template>

    <!-- 导出留痕 -->
    <template v-else>
      <section class="export-card">
        <header><div><h2>导出留痕</h2><small>每次实际下载记录一条：操作人、时间、导出范围、行数、字段清单</small></div></header>
        <table class="export-table" data-testid="export-audit-table">
          <thead><tr><th>时间</th><th>操作人</th><th>数据集</th><th>导出范围</th><th>行数</th><th>字段清单</th><th>批准人</th></tr></thead>
          <tbody>
            <tr v-for="item in audits" :key="item.export_audit_id">
              <td>{{ item.exported_at }}<small>{{ item.request_no }}</small></td>
              <td>{{ item.operator_name || '-' }}</td>
              <td>{{ item.dataset_name || item.dataset_code }}<em v-if="item.sensitivity === 'SENSITIVE'" class="export-tag">敏感</em></td>
              <td class="export-range">{{ filterSummary(item.filters) }}</td>
              <td>{{ item.row_count }}</td>
              <td class="export-range">{{ item.field_list.join('、') }}</td>
              <td>{{ item.approved_by_name || '—' }}</td>
            </tr>
            <tr v-if="!audits.length"><td colspan="7" class="export-empty">暂无导出记录</td></tr>
          </tbody>
        </table>
        <p class="export-note">医生端没有任何导出入口；全部导出都在这里发生并留痕。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.export-shell { display: flex; flex-direction: column; gap: 16px; }
.export-alert { margin: 0; padding: 10px 14px; border-radius: 10px; background: #f1f5f9; color: #475569; font-size: 13px; }
.export-alert.is-error { background: #fef2f2; color: #b91c1c; }
.export-alert.is-ok { background: #ecfdf5; color: #047857; }
.export-card { border: 1px solid #e2e8f0; border-radius: 14px; background: #fff; overflow: hidden; }
.export-card > header { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px; border-bottom: 1px solid #eef2f7; }
.export-card h2 { margin: 0; color: #0f172a; font-size: 15px; }
.export-card small { color: #94a3b8; font-size: 12px; }
.export-form { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 16px 18px; }
.export-form label { display: flex; flex-direction: column; gap: 5px; }
.export-form label > span { color: #64748b; font-size: 12px; }
.export-form input, .export-form select { height: 34px; padding: 0 10px; border: 1px solid #cbd5f5; border-radius: 8px; color: #0f172a; font-size: 13px; }
.export-form .is-wide { grid-column: 1 / -1; }
.export-fields { grid-column: 1 / -1; padding: 10px 12px; border-radius: 10px; background: #f8fafc; }
.export-fields strong { color: #0f172a; font-size: 12px; }
.export-fields p { margin: 4px 0 0; color: #475569; font-size: 12px; line-height: 1.6; }
.export-hint { color: #94a3b8 !important; }
.export-ack { flex-direction: row !important; align-items: center; gap: 8px !important; padding: 10px 12px; border: 1px dashed #f59e0b; border-radius: 10px; background: #fffbeb; }
.export-ack span { color: #92400e; font-size: 12px; }
.export-primary { height: 36px; padding: 0 18px; border: 0; border-radius: 9px; background: #2563eb; color: #fff; font-size: 13px; cursor: pointer; }
.export-primary:disabled { background: #cbd5f5; cursor: not-allowed; }
.export-table { width: 100%; border-collapse: collapse; }
.export-table th { padding: 10px 14px; border-bottom: 1px solid #eef2f7; color: #94a3b8; font-size: 12px; text-align: left; font-weight: 600; }
.export-table td { padding: 10px 14px; border-bottom: 1px solid #f1f5f9; color: #0f172a; font-size: 13px; vertical-align: top; }
.export-table td small { display: block; color: #94a3b8; font-size: 11px; }
.export-range { max-width: 260px; color: #475569 !important; font-size: 12px !important; word-break: break-all; }
.export-tag { margin-left: 6px; padding: 1px 6px; border-radius: 5px; background: #fee2e2; color: #b91c1c; font-size: 11px; font-style: normal; }
.export-status { padding: 2px 8px; border-radius: 6px; font-size: 12px; }
.export-status.is-pending { background: #fef3c7; color: #b45309; }
.export-status.is-approved { background: #d1fae5; color: #047857; }
.export-status.is-rejected { background: #fee2e2; color: #b91c1c; }
.export-status.is-not_required { background: #e0e7ff; color: #4338ca; }
.export-actions { display: flex; gap: 6px; }
.export-actions button { height: 28px; padding: 0 10px; border: 1px solid #cbd5f5; border-radius: 7px; background: #fff; color: #334155; font-size: 12px; cursor: pointer; }
.export-empty { color: #94a3b8; text-align: center; }
.export-note { margin: 0; padding: 0 18px 16px; color: #94a3b8; font-size: 12px; }
</style>
