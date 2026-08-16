<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

// TASK-034 D 批次：账号交接与人员转移。
//
// 客户原话「把他账号分配给新同事，并保留之前得服务记录」。界面上的关键设计：
// 执行前必须先看预览——转走什么、转多少、以及**哪些历史记录不会跟着走**。
// 不把后半句摆在操作人眼前，很容易有人以为交接会把绩效也一并转过去。

type ApiResponse<T> = { code: number; msg: string; data: T }

type HandoverItem = {
  object_type: string
  object_label: string
  target_table: string
  target_column: string
  affected_count: number
  object_ids: number[]
}

type Preview = {
  from_user_id: number
  from_user_name: string
  to_user_id: number
  to_user_name: string
  portal_role: string
  total_object_count: number
  items: HandoverItem[]
  historical_records_kept: string[]
}

type Handover = {
  handover_id: number
  handover_no: string
  from_user_name: string
  to_user_name: string
  operator_name: string
  reason: string | null
  source_disabled: boolean
  transferred_object_count: number
  created_at: string
  items: HandoverItem[]
}

type RbacUser = {
  user_id: number
  username: string
  display_name: string
  status: string
}

const props = defineProps<{ activeRoute: string; token: string }>()

const loading = ref(false)
const error = ref('')
const notice = ref('')

const users = ref<RbacUser[]>([])
const handovers = ref<Handover[]>([])
const preview = ref<Preview | null>(null)
const expandedId = ref<number | null>(null)

const draft = ref({
  from_user_id: '',
  successor_user_id: '',
  reason: '',
  disable_source_account: false,
  acknowledged: false
})

const activeUsers = computed(() => users.value.filter((item) => item.status === 'ACTIVE'))

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
    const [userList, handoverList] = await Promise.all([
      request<RbacUser[]>('/rbac/users'),
      request<Handover[]>('/accounts/handovers')
    ])
    users.value = userList
    handovers.value = handoverList
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadPreview() {
  preview.value = null
  if (!draft.value.from_user_id || !draft.value.successor_user_id) return
  loading.value = true
  error.value = ''
  try {
    preview.value = await request<Preview>(
      `/accounts/${draft.value.from_user_id}/handover-preview`
      + `?successor_user_id=${draft.value.successor_user_id}`
    )
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '预览失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!preview.value) {
    error.value = '请先查看转移预览'
    return
  }
  if (!draft.value.acknowledged) {
    error.value = '请先勾选交接确认'
    return
  }
  const summary = `将 ${preview.value.from_user_name} 的 ${preview.value.total_object_count} 项当前负责关系`
    + `转移给 ${preview.value.to_user_name}`
    + (draft.value.disable_source_account ? '，并停用原账号' : '')
    + '。历史记录仍保留原责任人。确认执行？'
  if (!window.confirm(summary)) return

  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await request<Handover>(`/accounts/${draft.value.from_user_id}/handover`, {
      method: 'POST',
      body: JSON.stringify({
        successor_user_id: Number(draft.value.successor_user_id),
        reason: draft.value.reason.trim() || null,
        disable_source_account: draft.value.disable_source_account,
        acknowledged: true
      })
    })
    notice.value = `交接 ${created.handover_no} 已完成，共转移 ${created.transferred_object_count} 项`
    preview.value = null
    draft.value.acknowledged = false
    draft.value.reason = ''
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '交接失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.activeRoute, load, { immediate: true })
watch(() => [draft.value.from_user_id, draft.value.successor_user_id], loadPreview)
</script>

<template>
  <div class="handover-shell">
    <p v-if="error" class="handover-alert is-error">{{ error }}</p>
    <p v-else-if="notice" class="handover-alert is-ok">{{ notice }}</p>
    <p v-if="loading" class="handover-alert">处理中…</p>

    <section class="handover-card">
      <header>
        <div>
          <h2>账号交接</h2>
          <small>把离职或转岗同事的当前负责关系转给承接人；已完成的工序、工时、质检与审核记录保留原责任人</small>
        </div>
      </header>
      <form class="handover-form" data-testid="handover-form" @submit.prevent="submit">
        <label>
          <span>原责任人</span>
          <select v-model="draft.from_user_id" required>
            <option value="" disabled>请选择</option>
            <option v-for="item in users" :key="item.user_id" :value="item.user_id">
              {{ item.display_name }}（{{ item.username }}）{{ item.status === 'ACTIVE' ? '' : ' · 已停用' }}
            </option>
          </select>
        </label>
        <label>
          <span>承接人</span>
          <select v-model="draft.successor_user_id" required>
            <option value="" disabled>请选择</option>
            <option v-for="item in activeUsers" :key="item.user_id" :value="item.user_id">
              {{ item.display_name }}（{{ item.username }}）
            </option>
          </select>
        </label>
        <label class="is-wide"><span>交接原因</span><input v-model="draft.reason" placeholder="如：医生离职 / 技工转岗 / 转诊"></label>

        <div v-if="preview" class="handover-preview" data-testid="handover-preview">
          <div class="handover-preview-head">
            <strong>{{ preview.from_user_name }} → {{ preview.to_user_name }}</strong>
            <span>入口角色 {{ preview.portal_role }} · 共 {{ preview.total_object_count }} 项</span>
          </div>
          <table class="handover-table">
            <thead><tr><th>转移对象</th><th>数量</th><th>落点</th></tr></thead>
            <tbody>
              <tr v-for="item in preview.items" :key="item.object_type">
                <td>{{ item.object_label }}</td>
                <td>{{ item.affected_count }}</td>
                <td class="handover-dim">{{ item.target_table }}.{{ item.target_column }}</td>
              </tr>
            </tbody>
          </table>
          <div class="handover-kept" data-testid="handover-kept">
            <strong>以下记录不会跟着转移，仍归原责任人</strong>
            <ul><li v-for="line in preview.historical_records_kept" :key="line">{{ line }}</li></ul>
          </div>
        </div>

        <label class="is-wide handover-option">
          <input v-model="draft.disable_source_account" type="checkbox">
          <span>同时停用原账号（需要账号停用权限，会一并使其登录会话失效）</span>
        </label>
        <label class="is-wide handover-ack">
          <input v-model="draft.acknowledged" type="checkbox" data-testid="handover-acknowledge">
          <span>我已核对上方转移清单，确认执行本次交接</span>
        </label>
        <div class="is-wide">
          <button type="submit" class="handover-primary" :disabled="loading || !preview">执行交接</button>
        </div>
      </form>
    </section>

    <section class="handover-card">
      <header><div><h2>交接记录</h2><small>操作人、时间、原责任人、承接人、转移对象清单与原因</small></div></header>
      <table class="handover-table" data-testid="handover-record-table">
        <thead><tr><th>交接单号</th><th>原责任人 → 承接人</th><th>操作人</th><th>原因</th><th>转移项数</th><th>原账号</th><th></th></tr></thead>
        <tbody>
          <template v-for="item in handovers" :key="item.handover_id">
            <tr>
              <td><strong>{{ item.handover_no }}</strong><small>{{ item.created_at }}</small></td>
              <td>{{ item.from_user_name }} → {{ item.to_user_name }}</td>
              <td>{{ item.operator_name || '-' }}</td>
              <td>{{ item.reason || '—' }}</td>
              <td>{{ item.transferred_object_count }}</td>
              <td>{{ item.source_disabled ? '已停用' : '保持启用' }}</td>
              <td>
                <button type="button" class="handover-link" @click="expandedId = expandedId === item.handover_id ? null : item.handover_id">
                  {{ expandedId === item.handover_id ? '收起' : '对象清单' }}
                </button>
              </td>
            </tr>
            <tr v-if="expandedId === item.handover_id">
              <td colspan="7" class="handover-detail">
                <div v-for="detail in item.items" :key="detail.object_type" class="handover-detail-row">
                  <strong>{{ detail.object_label }}</strong>
                  <span>{{ detail.affected_count }} 项</span>
                  <em>{{ detail.object_ids.length ? detail.object_ids.join('、') : '本次无对象' }}</em>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!handovers.length"><td colspan="7" class="handover-empty">暂无交接记录</td></tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.handover-shell { display: flex; flex-direction: column; gap: 16px; }
.handover-alert { margin: 0; padding: 10px 14px; border-radius: 10px; background: #f1f5f9; color: #475569; font-size: 13px; }
.handover-alert.is-error { background: #fef2f2; color: #b91c1c; }
.handover-alert.is-ok { background: #ecfdf5; color: #047857; }
.handover-card { border: 1px solid #e2e8f0; border-radius: 14px; background: #fff; overflow: hidden; }
.handover-card > header { padding: 16px 18px; border-bottom: 1px solid #eef2f7; }
.handover-card h2 { margin: 0; color: #0f172a; font-size: 15px; }
.handover-card small { color: #94a3b8; font-size: 12px; }
.handover-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 16px 18px; }
.handover-form label { display: flex; flex-direction: column; gap: 5px; }
.handover-form label > span { color: #64748b; font-size: 12px; }
.handover-form input, .handover-form select { height: 34px; padding: 0 10px; border: 1px solid #cbd5f5; border-radius: 8px; color: #0f172a; font-size: 13px; }
.handover-form .is-wide { grid-column: 1 / -1; }
.handover-preview { grid-column: 1 / -1; padding: 12px; border: 1px solid #dbeafe; border-radius: 10px; background: #f8fafc; }
.handover-preview-head { display: flex; justify-content: space-between; margin-bottom: 8px; color: #0f172a; font-size: 13px; }
.handover-preview-head span { color: #64748b; font-size: 12px; }
.handover-kept { margin-top: 10px; padding: 10px 12px; border-radius: 8px; background: #fffbeb; }
.handover-kept strong { color: #b45309; font-size: 12px; }
.handover-kept ul { margin: 6px 0 0; padding-left: 18px; color: #92400e; font-size: 12px; line-height: 1.7; }
.handover-option, .handover-ack { flex-direction: row !important; align-items: center; gap: 8px !important; }
.handover-option span { color: #475569; font-size: 12px; }
.handover-ack { padding: 10px 12px; border: 1px dashed #f59e0b; border-radius: 10px; background: #fffbeb; }
.handover-ack span { color: #92400e; font-size: 12px; }
.handover-primary { height: 36px; padding: 0 18px; border: 0; border-radius: 9px; background: #2563eb; color: #fff; font-size: 13px; cursor: pointer; }
.handover-primary:disabled { background: #cbd5f5; cursor: not-allowed; }
.handover-table { width: 100%; border-collapse: collapse; }
.handover-table th { padding: 10px 14px; border-bottom: 1px solid #eef2f7; color: #94a3b8; font-size: 12px; text-align: left; font-weight: 600; }
.handover-table td { padding: 10px 14px; border-bottom: 1px solid #f1f5f9; color: #0f172a; font-size: 13px; vertical-align: top; }
.handover-table td small { display: block; color: #94a3b8; font-size: 11px; }
.handover-dim { color: #94a3b8 !important; font-size: 12px !important; }
.handover-link { border: 0; background: none; color: #2563eb; font-size: 12px; cursor: pointer; }
.handover-detail { background: #f8fafc; }
.handover-detail-row { display: flex; gap: 12px; align-items: baseline; padding: 4px 0; font-size: 12px; }
.handover-detail-row strong { min-width: 160px; color: #0f172a; }
.handover-detail-row span { min-width: 60px; color: #64748b; }
.handover-detail-row em { color: #94a3b8; font-style: normal; word-break: break-all; }
.handover-empty { color: #94a3b8; text-align: center; }
</style>
