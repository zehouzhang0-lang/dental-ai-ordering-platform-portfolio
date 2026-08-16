<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { authenticatedFetchKey } from '../utils/authenticatedFetch'

const authenticatedFetch = inject(authenticatedFetchKey, fetch)

// TASK-034 C 批次：管理端角色 / 权限 / 组织管理，关闭客户 CHK064-066。
// 全部为可实操界面，不是只读展示；高风险操作走后端留痕。

type ApiResponse<T> = { code: number; msg: string; data: T }

type Role = {
  role_id: number
  role_code: string
  role_name: string
  data_scope: string
  role_level: number
  status: string
  remark: string | null
  permission_count: number
  menu_count: number
  user_count: number
}
type Permission = {
  permission_id: number
  permission_code: string
  permission_name: string
  module_code: string
}
type RolePermission = {
  role_id: number
  role_code: string
  data_scope: string
  permission_codes: string[]
  menu_codes: string[]
}
type Dept = {
  dept_id: number
  parent_id: number | null
  dept_code: string
  dept_name: string
  sort_order: number
  status: string
  member_count: number
}
type Post = { post_id: number; post_code: string; post_name: string; sort_order: number; status: string }
type User = {
  user_id: number
  username: string
  display_name: string
  status: string
  dept_id: number | null
  dept_name: string | null
  data_scope: string | null
  role_codes: string[]
  post_codes: string[]
}
type Matrix = {
  roles: Role[]
  permissions: Permission[]
  permissions_by_role: Record<string, string[]>
  menus_by_role: Record<string, string[]>
}
type Audit = {
  audit_id: number
  entity_type: string
  entity_label: string | null
  action_type: string
  operator_username: string | null
  reason: string | null
  created_at: string
}

const props = defineProps<{ activeRoute: string; token: string }>()

const loading = ref(false)
const error = ref('')
const notice = ref('')

const roles = ref<Role[]>([])
const permissions = ref<Permission[]>([])
const selectedRoleId = ref<number | null>(null)
const rolePermission = ref<RolePermission | null>(null)
const draftPermissionCodes = ref<Set<string>>(new Set())
const draftDataScope = ref('SELF')

const depts = ref<Dept[]>([])
const posts = ref<Post[]>([])
const users = ref<User[]>([])
const matrix = ref<Matrix | null>(null)
const audits = ref<Audit[]>([])

const newRole = ref({ role_code: '', role_name: '', data_scope: 'SELF', role_level: 30 })
const newDept = ref({ dept_code: '', dept_name: '', parent_id: null as number | null })
const newPost = ref({ post_code: '', post_name: '' })

const dataScopeOptions = ['ALL', 'CLINIC', 'SELF', 'NONE']
const roleLevelOptions = [
  { value: 0, label: '0 · 平台管理员 / 入口角色' },
  { value: 10, label: '10 · 经理级' },
  { value: 20, label: '20 · 主管 / 组长级' },
  { value: 30, label: '30 · 普通岗位' }
]

const selectedRole = computed(() => roles.value.find((item) => item.role_id === selectedRoleId.value) ?? null)
const permissionModules = computed(() => {
  const grouped = new Map<string, Permission[]>()
  for (const item of permissions.value) {
    const list = grouped.get(item.module_code) ?? []
    list.push(item)
    grouped.set(item.module_code, list)
  }
  return [...grouped.entries()].map(([module, items]) => ({ module, items }))
})
const deptTree = computed(() =>
  depts.value.map((dept) => ({
    ...dept,
    depth: dept.parent_id === null ? 0 : 1
  }))
)

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
    if (props.activeRoute === '/admin/rbac/roles') {
      const [roleList, permissionList, userList] = await Promise.all([
        request<Role[]>('/rbac/roles'),
        request<Permission[]>('/rbac/permissions'),
        request<User[]>('/rbac/users')
      ])
      roles.value = roleList
      permissions.value = permissionList
      users.value = userList
      if (selectedRoleId.value === null && roleList.length) {
        await selectRole(roleList[0].role_id)
      }
    } else if (props.activeRoute === '/admin/rbac/org') {
      const [deptList, postList, userList] = await Promise.all([
        request<Dept[]>('/rbac/departments'),
        request<Post[]>('/rbac/posts'),
        request<User[]>('/rbac/users')
      ])
      depts.value = deptList
      posts.value = postList
      users.value = userList
    } else if (props.activeRoute === '/admin/rbac/matrix') {
      const [matrixData, auditList] = await Promise.all([
        request<Matrix>('/rbac/matrix'),
        request<Audit[]>('/rbac/audits?limit=50')
      ])
      matrix.value = matrixData
      audits.value = auditList
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function selectRole(roleId: number) {
  selectedRoleId.value = roleId
  try {
    const detail = await request<RolePermission>(`/rbac/roles/${roleId}/permissions`)
    rolePermission.value = detail
    draftPermissionCodes.value = new Set(detail.permission_codes)
    draftDataScope.value = detail.data_scope
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '角色详情加载失败'
  }
}

function togglePermission(code: string) {
  const next = new Set(draftPermissionCodes.value)
  if (next.has(code)) next.delete(code)
  else next.add(code)
  draftPermissionCodes.value = next
}

async function savePermissions() {
  if (!selectedRoleId.value) return
  await run('权限已保存', () => request(`/rbac/roles/${selectedRoleId.value}/permissions`, {
    method: 'PUT',
    body: JSON.stringify({
      permission_codes: [...draftPermissionCodes.value],
      data_scope: draftDataScope.value,
      reason: '管理端调整角色权限'
    })
  }))
  if (selectedRoleId.value) await selectRole(selectedRoleId.value)
}

async function createRole() {
  await run('角色已创建', () => request('/rbac/roles', {
    method: 'POST',
    body: JSON.stringify({ ...newRole.value, reason: '管理端新建角色' })
  }))
  newRole.value = { role_code: '', role_name: '', data_scope: 'SELF', role_level: 30 }
}

async function toggleRoleStatus(role: Role) {
  const next = role.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  await run(next === 'ACTIVE' ? '角色已恢复' : '角色已停用', () =>
    request(`/rbac/roles/${role.role_id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status: next, reason: '管理端调整角色状态' })
    }))
}

async function createDept() {
  await run('部门已创建', () => request('/rbac/departments', {
    method: 'POST',
    body: JSON.stringify({ ...newDept.value, reason: '管理端新建部门' })
  }))
  newDept.value = { dept_code: '', dept_name: '', parent_id: null }
}

async function createPost() {
  await run('岗位已创建', () => request('/rbac/posts', {
    method: 'POST',
    body: JSON.stringify({ ...newPost.value, reason: '管理端新建岗位' })
  }))
  newPost.value = { post_code: '', post_name: '' }
}

async function assignUserRoles(user: User, roleCodes: string[]) {
  await run('人员角色已更新', () => request(`/rbac/users/${user.user_id}/assignment`, {
    method: 'PUT',
    body: JSON.stringify({ role_codes: roleCodes, reason: '管理端调整人员角色' })
  }))
}

async function assignUserDept(user: User, deptId: number | null) {
  await run('人员部门已更新', () => request(`/rbac/users/${user.user_id}/assignment`, {
    method: 'PUT',
    body: JSON.stringify({ dept_id: deptId, reason: '管理端调整人员部门' })
  }))
}

async function toggleUserStatus(user: User) {
  const next = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await run(next === 'ACTIVE' ? '账号已解锁' : '账号已停用', () =>
    request(`/rbac/users/${user.user_id}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status: next, reason: '管理端调整账号状态' })
    }))
}

async function resetPassword(user: User) {
  try {
    const result = await request<{ temporary_password: string }>(
      `/rbac/users/${user.user_id}/password-reset`,
      { method: 'POST', body: JSON.stringify({ reason: '管理端重置密码' }) }
    )
    notice.value = `已为 ${user.username} 生成一次性初始口令：${result.temporary_password}（请线下转交，系统不保存也无法再次查看）`
    error.value = ''
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '重置密码失败'
  }
}

async function run(successMessage: string, action: () => Promise<unknown>) {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = successMessage
    await load()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作失败'
  } finally {
    loading.value = false
  }
}

function roleHas(roleCode: string, permissionCode: string) {
  return (matrix.value?.permissions_by_role?.[roleCode] ?? []).includes(permissionCode)
}

watch(() => props.activeRoute, load, { immediate: true })
</script>

<template>
  <div class="rbac-shell">
    <p v-if="error" class="rbac-alert is-error">{{ error }}</p>
    <p v-else-if="notice" class="rbac-alert is-ok">{{ notice }}</p>
    <p v-if="loading" class="rbac-alert">处理中…</p>

    <!-- 角色权限 -->
    <template v-if="activeRoute === '/admin/rbac/roles'">
      <section class="rbac-card">
        <header>
          <div><h2>角色管理</h2><small>创建、停用、恢复角色并设置数据范围；授权等级决定谁能分配这个角色</small></div>
        </header>
        <form class="rbac-inline-form" @submit.prevent="createRole">
          <label><span>角色码</span><input v-model="newRole.role_code" placeholder="如 CS_SENIOR" required></label>
          <label><span>角色名称</span><input v-model="newRole.role_name" placeholder="如 高级客服" required></label>
          <label><span>数据范围</span>
            <select v-model="newRole.data_scope">
              <option v-for="scope in dataScopeOptions" :key="scope" :value="scope">{{ scope }}</option>
            </select>
          </label>
          <label><span>授权等级</span>
            <select v-model.number="newRole.role_level">
              <option v-for="item in roleLevelOptions" :key="item.value" :value="item.value">{{ item.label }}</option>
            </select>
          </label>
          <button type="submit" :disabled="loading">＋ 新建角色</button>
        </form>
        <div class="rbac-table-wrap">
          <table class="rbac-table">
            <thead><tr><th>角色</th><th>数据范围</th><th>授权等级</th><th>权限码</th><th>菜单</th><th>人数</th><th>状态</th><th /></tr></thead>
            <tbody>
              <tr
                v-for="role in roles"
                :key="role.role_id"
                :class="{ active: role.role_id === selectedRoleId }"
                @click="selectRole(role.role_id)"
              >
                <td><strong>{{ role.role_name }}</strong><small>{{ role.role_code }}</small></td>
                <td>{{ role.data_scope }}</td>
                <td>{{ role.role_level }}</td>
                <td>{{ role.permission_count }}</td>
                <td>{{ role.menu_count }}</td>
                <td>{{ role.user_count }}</td>
                <td><span :class="role.status === 'ACTIVE' ? 'rbac-badge is-ok' : 'rbac-badge is-muted'">{{ role.status === 'ACTIVE' ? '启用' : '停用' }}</span></td>
                <td><button type="button" :disabled="loading" @click.stop="toggleRoleStatus(role)">{{ role.status === 'ACTIVE' ? '停用' : '恢复' }}</button></td>
              </tr>
              <tr v-if="!roles.length"><td colspan="8" class="rbac-empty">暂无角色数据</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-if="selectedRole" class="rbac-card">
        <header>
          <div><h2>{{ selectedRole.role_name }} · 权限码与数据范围</h2><small>勾选后点击保存；只能授予自己也持有的权限码</small></div>
          <button type="button" :disabled="loading" @click="savePermissions">保存</button>
        </header>
        <label class="rbac-scope"><span>数据范围</span>
          <select v-model="draftDataScope">
            <option v-for="scope in dataScopeOptions" :key="scope" :value="scope">{{ scope }}</option>
          </select>
        </label>
        <div v-for="group in permissionModules" :key="group.module" class="rbac-perm-group">
          <h4>{{ group.module }}</h4>
          <label v-for="item in group.items" :key="item.permission_code" class="rbac-perm">
            <input
              type="checkbox"
              :checked="draftPermissionCodes.has(item.permission_code)"
              @change="togglePermission(item.permission_code)"
            >
            <span><strong>{{ item.permission_name }}</strong><small>{{ item.permission_code }}</small></span>
          </label>
        </div>
      </section>
    </template>

    <!-- 组织架构 -->
    <template v-else-if="activeRoute === '/admin/rbac/org'">
      <section class="rbac-card">
        <header><div><h2>部门与班组</h2><small>支持层级维护；子部门选择上级部门即可</small></div></header>
        <form class="rbac-inline-form" @submit.prevent="createDept">
          <label><span>部门编码</span><input v-model="newDept.dept_code" placeholder="如 CAD_GROUP" required></label>
          <label><span>部门名称</span><input v-model="newDept.dept_name" placeholder="如 CAD 设计组" required></label>
          <label><span>上级部门</span>
            <select v-model.number="newDept.parent_id">
              <option :value="null">（顶级）</option>
              <option v-for="dept in depts" :key="dept.dept_id" :value="dept.dept_id">{{ dept.dept_name }}</option>
            </select>
          </label>
          <button type="submit" :disabled="loading">＋ 新建部门</button>
        </form>
        <div class="rbac-table-wrap">
          <table class="rbac-table">
            <thead><tr><th>部门</th><th>编码</th><th>人数</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="dept in deptTree" :key="dept.dept_id">
                <td :style="{ paddingLeft: `${12 + dept.depth * 20}px` }">{{ dept.depth ? '└ ' : '' }}{{ dept.dept_name }}</td>
                <td>{{ dept.dept_code }}</td>
                <td>{{ dept.member_count }}</td>
                <td>{{ dept.status === 'ACTIVE' ? '启用' : '停用' }}</td>
              </tr>
              <tr v-if="!depts.length"><td colspan="4" class="rbac-empty">暂无部门数据</td></tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="rbac-card">
        <header><div><h2>岗位</h2><small>岗位用于描述职能，权限仍由角色决定</small></div></header>
        <form class="rbac-inline-form" @submit.prevent="createPost">
          <label><span>岗位编码</span><input v-model="newPost.post_code" placeholder="如 CAD_DESIGN" required></label>
          <label><span>岗位名称</span><input v-model="newPost.post_name" placeholder="如 CAD 设计岗" required></label>
          <button type="submit" :disabled="loading">＋ 新建岗位</button>
        </form>
        <div class="rbac-chip-row">
          <span v-for="post in posts" :key="post.post_id" class="rbac-badge">{{ post.post_name }} · {{ post.post_code }}</span>
          <span v-if="!posts.length" class="rbac-empty">暂无岗位数据</span>
        </div>
      </section>

      <section class="rbac-card">
        <header><div><h2>人员分配</h2><small>分配角色与所属部门；停用、解锁与重置密码属账号安全权限</small></div></header>
        <div class="rbac-table-wrap">
          <table class="rbac-table">
            <thead><tr><th>账号</th><th>部门</th><th>角色</th><th>状态</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.user_id">
                <td><strong>{{ user.display_name || user.username }}</strong><small>{{ user.username }}</small></td>
                <td>
                  <select
                    :value="user.dept_id"
                    :disabled="loading"
                    @change="assignUserDept(user, ($event.target as HTMLSelectElement).value ? Number(($event.target as HTMLSelectElement).value) : null)"
                  >
                    <option :value="''">未分配</option>
                    <option v-for="dept in depts" :key="dept.dept_id" :value="dept.dept_id">{{ dept.dept_name }}</option>
                  </select>
                </td>
                <td>
                  <select
                    multiple
                    size="3"
                    :disabled="loading"
                    @change="assignUserRoles(user, Array.from(($event.target as HTMLSelectElement).selectedOptions).map((option) => option.value))"
                  >
                    <option
                      v-for="role in roles"
                      :key="role.role_id"
                      :value="role.role_code"
                      :selected="user.role_codes.includes(role.role_code)"
                    >{{ role.role_name }}</option>
                  </select>
                  <small>{{ user.role_codes.join(' / ') || '未分配' }}</small>
                </td>
                <td><span :class="user.status === 'ACTIVE' ? 'rbac-badge is-ok' : 'rbac-badge is-muted'">{{ user.status }}</span></td>
                <td class="rbac-actions">
                  <button type="button" :disabled="loading" @click="toggleUserStatus(user)">{{ user.status === 'ACTIVE' ? '停用' : '解锁' }}</button>
                  <button type="button" :disabled="loading" @click="resetPassword(user)">重置密码</button>
                </td>
              </tr>
              <tr v-if="!users.length"><td colspan="5" class="rbac-empty">暂无人员数据</td></tr>
            </tbody>
          </table>
        </div>
        <p class="rbac-note">密码只能重置不能查看：重置会生成一次性初始口令，系统不保存明文，审计只记录「发生过重置」。</p>
      </section>
    </template>

    <!-- 权限矩阵 -->
    <template v-else-if="activeRoute === '/admin/rbac/matrix'">
      <section class="rbac-card">
        <header><div><h2>权限矩阵</h2><small>按角色 × 权限码核对；供客户逐项确认</small></div></header>
        <div v-if="matrix" class="rbac-table-wrap is-scroll">
          <table class="rbac-table rbac-matrix">
            <thead>
              <tr>
                <th class="is-sticky">权限码</th>
                <th v-for="role in matrix.roles" :key="role.role_id">{{ role.role_name }}<small>{{ role.data_scope }}</small></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="permission in matrix.permissions" :key="permission.permission_id">
                <td class="is-sticky"><strong>{{ permission.permission_name }}</strong><small>{{ permission.permission_code }}</small></td>
                <td v-for="role in matrix.roles" :key="role.role_id" class="is-center">
                  <span :class="roleHas(role.role_code, permission.permission_code) ? 'rbac-tick' : 'rbac-dash'">
                    {{ roleHas(role.role_code, permission.permission_code) ? '✓' : '—' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="rbac-card">
        <header><div><h2>高风险操作留痕</h2><small>创建 / 停用账号、分配角色权限、组织调整全部记录操作人、时间与修改前后内容</small></div></header>
        <div class="rbac-table-wrap">
          <table class="rbac-table">
            <thead><tr><th>时间</th><th>对象</th><th>动作</th><th>操作人</th><th>原因</th></tr></thead>
            <tbody>
              <tr v-for="item in audits" :key="item.audit_id">
                <td>{{ item.created_at?.replace('T', ' ').slice(0, 19) }}</td>
                <td>{{ item.entity_type }} · {{ item.entity_label || '—' }}</td>
                <td>{{ item.action_type }}</td>
                <td>{{ item.operator_username || '—' }}</td>
                <td>{{ item.reason || '—' }}</td>
              </tr>
              <tr v-if="!audits.length"><td colspan="5" class="rbac-empty">暂无操作记录</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.rbac-shell { display: grid; gap: 14px; padding: 4px; }
.rbac-alert { margin: 0; padding: 10px 14px; border-radius: 10px; background: #f1f5f9; color: #475569; font-size: 13px; }
.rbac-alert.is-error { background: #fef2f2; color: #b91c1c; }
.rbac-alert.is-ok { background: #ecfdf5; color: #047857; }
.rbac-card { border: 1px solid #e2e8f0; border-radius: 14px; background: #fff; overflow: hidden; }
.rbac-card > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; border-bottom: 1px solid #eef2f7; }
.rbac-card h2 { margin: 0; font-size: 15px; }
.rbac-card small { color: #94a3b8; font-size: 12px; }
.rbac-card > header button { padding: 7px 14px; border: 1px solid #7c3aed; border-radius: 8px; background: #7c3aed; color: #fff; font-weight: 700; }
.rbac-inline-form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; padding: 14px 18px; }
.rbac-inline-form label { display: grid; gap: 4px; font-size: 12px; color: #64748b; }
.rbac-inline-form input, .rbac-inline-form select { padding: 7px 9px; border: 1px solid #d6e0eb; border-radius: 8px; }
.rbac-inline-form button { padding: 8px 14px; border: 1px solid #c4b5fd; border-radius: 8px; background: #f5f3ff; color: #6d28d9; font-weight: 700; }
.rbac-table-wrap { overflow-x: auto; }
.rbac-table-wrap.is-scroll { max-height: 520px; overflow: auto; }
.rbac-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.rbac-table th, .rbac-table td { padding: 9px 12px; border-bottom: 1px solid #f1f5f9; text-align: left; vertical-align: top; }
.rbac-table th { background: #f8fafc; color: #64748b; font-size: 12px; position: sticky; top: 0; }
.rbac-table tbody tr.active { background: #faf5ff; }
.rbac-table td small { display: block; color: #94a3b8; }
.rbac-table td select { max-width: 220px; padding: 5px 7px; border: 1px solid #d6e0eb; border-radius: 7px; }
.rbac-table button { padding: 5px 10px; border: 1px solid #cbd5e1; border-radius: 7px; background: #fff; }
.rbac-actions { display: flex; gap: 6px; }
.rbac-empty { color: #94a3b8; text-align: center; }
.rbac-badge { display: inline-block; padding: 3px 9px; border-radius: 999px; background: #f1f5f9; color: #475569; font-size: 12px; }
.rbac-badge.is-ok { background: #ecfdf5; color: #047857; }
.rbac-badge.is-muted { background: #f1f5f9; color: #94a3b8; }
.rbac-chip-row { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 18px 16px; }
.rbac-scope { display: flex; align-items: center; gap: 10px; padding: 12px 18px 0; font-size: 12px; color: #64748b; }
.rbac-scope select { padding: 6px 9px; border: 1px solid #d6e0eb; border-radius: 8px; }
.rbac-perm-group { padding: 12px 18px; border-top: 1px solid #f8fafc; }
.rbac-perm-group h4 { margin: 0 0 8px; color: #475569; font-size: 12px; text-transform: uppercase; }
.rbac-perm { display: inline-flex; align-items: flex-start; gap: 7px; width: 260px; margin: 0 10px 8px 0; font-size: 12px; }
.rbac-perm small { display: block; color: #94a3b8; }
.rbac-matrix th.is-sticky, .rbac-matrix td.is-sticky { position: sticky; left: 0; background: #fff; z-index: 1; }
.rbac-matrix th.is-sticky { background: #f8fafc; z-index: 2; }
.rbac-matrix td.is-center { text-align: center; }
.rbac-tick { color: #059669; font-weight: 800; }
.rbac-dash { color: #cbd5e1; }
.rbac-note { margin: 0; padding: 0 18px 16px; color: #94a3b8; font-size: 12px; }
</style>
