<template>
  <el-tabs v-model="tab">
    <el-tab-pane label="菜单管理" name="menus">
      <el-button type="primary" @click="editMenu()">新增菜单</el-button>
      <el-table :data="menus" border stripe style="margin-top:12px">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="code" label="编码" width="150" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="path" label="路径" />
        <el-table-column prop="sort" label="排序" width="80" />
        <el-table-column label="操作" width="180"><template #default="{ row }"><el-button size="small" @click="editMenu(row)">编辑</el-button><el-button size="small" type="danger" @click="disableMenu(row)">停用</el-button></template></el-table-column>
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="角色管理" name="roles">
      <el-button type="primary" @click="editRole()">新增角色</el-button>
      <el-table :data="roles" border stripe style="margin-top:12px">
        <el-table-column prop="id" label="ID" width="80" /><el-table-column prop="code" label="编码" /><el-table-column prop="name" label="名称" /><el-table-column label="操作"><template #default="{ row }"><el-button size="small" @click="editRole(row)">编辑</el-button><el-button size="small" @click="assign(row)">配置菜单</el-button></template></el-table-column>
      </el-table>
    </el-tab-pane>
    <el-tab-pane label="用户管理" name="users">
      <el-button type="primary" @click="createUser">新增管理员</el-button>
      <el-table :data="users" border stripe style="margin-top:12px"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="mobile" label="账号" /><el-table-column prop="nickname" label="昵称" /><el-table-column prop="role" label="角色" /><el-table-column prop="status" label="状态" /><el-table-column label="操作"><template #default="{ row }"><el-button size="small" @click="toggleUser(row)">{{ row.status === 1 ? '禁用' : '启用' }}</el-button><el-button size="small" @click="assignUser(row)">分配角色</el-button></template></el-table-column></el-table>
    </el-tab-pane>
  </el-tabs>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '../api'
const tab = ref('menus'); const menus = ref([]); const roles = ref([]); const users = ref([])
const load = async () => { menus.value = await adminApi.systemMenus(); roles.value = await adminApi.systemRoles(); users.value = await adminApi.systemUsers() }
const editMenu = (row = {}) => ElMessageBox.prompt('请输入菜单名称', '菜单管理', { inputValue: row.name || '', inputValidator: v => !!v || '名称必填' }).then(async ({ value }) => { await adminApi.saveMenu({ id: row.id, code: row.code || value.toLowerCase().replace(/\s+/g, '_'), name: value, path: row.path || '/' + value, parentId: row.parentId || 0, sort: row.sort || 100, enabled: 1 }); load() }).catch(() => {})
const disableMenu = async row => { await adminApi.deleteMenu(row.id); ElMessage.success('菜单已停用'); load() }
const editRole = (row = {}) => ElMessageBox.prompt('请输入角色名称', '角色管理', { inputValue: row.name || '', inputValidator: v => !!v || '名称必填' }).then(async ({ value }) => { await adminApi.saveRole({ id: row.id, code: row.code || value.toUpperCase().replace(/\s+/g, '_'), name: value, enabled: 1 }); load() }).catch(() => {})
const assign = async row => { await adminApi.assignRoleMenus(row.id, menus.value.map(m => m.id)); ElMessage.success('菜单权限已保存') }
const assignUser = (row) => ElMessageBox.prompt('请输入角色ID，多个用逗号分隔', '分配角色', { inputValue: (row.roleIds || []).join(',') }).then(async ({ value }) => { const ids = value.split(',').map(v => Number(v.trim())).filter(Boolean); await adminApi.assignUserRoles(row.id, ids); ElMessage.success('角色已分配'); load() }).catch(() => {})
const createUser = () => ElMessageBox.prompt('请输入管理员账号', '新增管理员').then(({ value }) => ElMessageBox.prompt('请输入至少8位密码', '设置密码').then(async p => { await adminApi.createAdmin({ username: value, password: p.value, nickname: value }); ElMessage.success('创建成功'); load() })).catch(() => {})
const toggleUser = async row => { await adminApi.changeUserStatus(row.id, row.status === 1 ? 0 : 1); load() }
onMounted(load)
</script>
