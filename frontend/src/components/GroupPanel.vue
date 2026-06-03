<template>
  <el-drawer
    v-model="visible"
    :title="`群信息 - ${group?.name || ''}`"
    direction="rtl"
    size="440px"
    class="group-drawer"
    @open="loadAll"
  >
    <div v-loading="loading" class="group-panel">
      <div v-if="groupInfo" class="group-overview">
        <SmartAvatar :src="group?.avatar" :name="group?.name || groupInfo.groupName" :size="56" type="group" />
        <div class="overview-copy">
          <h3>{{ group?.name || groupInfo.groupName }}</h3>
          <p>{{ members.length }} 位成员 · {{ isOwner ? '你是群主' : (isMember ? '你已加入该群' : '群信息预览') }}</p>
        </div>
      </div>

      <div v-if="groupInfo" class="group-meta">
        <div class="meta-card">
          <div class="meta-card-label">群号</div>
          <div class="meta-card-value">{{ groupInfo.groupNo || '—' }}</div>
          <el-button v-if="groupInfo.groupNo" size="small" link type="primary" @click="copyGroupNo">复制</el-button>
        </div>
        <div class="meta-card meta-card-notice">
          <div class="meta-card-label">群公告</div>
          <div class="meta-card-value notice-text">{{ groupInfo.notice || '暂无群公告' }}</div>
          <el-button v-if="isOwner" size="small" link type="primary" @click="onEditNotice">编辑</el-button>
        </div>
      </div>

      <div class="section-row">
        <div class="section-title">群成员 ({{ members.length }})</div>
        <el-button v-if="isMember" size="small" type="primary" plain @click="showInviteDialog = true">
          ➕ 邀请成员
        </el-button>
      </div>
      <div class="member-list">
        <div v-for="m in members" :key="m.userId" class="member-item">
          <SmartAvatar :src="m.avatar" :name="memberName(m)" :size="36" />
          <div class="member-info">
            <span class="member-name">{{ memberName(m) }}{{ idEq(m.userId, userStore.userInfo?.id) ? ' (我)' : '' }}</span>
            <el-tag v-if="m.role === 2" type="danger" size="small">群主</el-tag>
            <el-tag v-else-if="m.role === 1" type="warning" size="small">管理员</el-tag>
          </div>
          <div class="member-actions">
            <el-button
              v-if="!idEq(m.userId, userStore.userInfo?.id) && !isFriend(m.userId)"
              size="small"
              type="success"
              link
              @click="onAddFriend(m)"
            >+ 加好友</el-button>
            <el-button
              v-if="isOwner && !idEq(m.userId, userStore.userInfo?.id)"
              size="small"
              type="primary"
              link
              @click="onTransfer(m.userId)"
            >转让</el-button>
            <el-button
              v-if="isOwner && !idEq(m.userId, userStore.userInfo?.id)"
              size="small"
              type="danger"
              link
              @click="onKick(m.userId, memberName(m))"
            >踢出</el-button>
          </div>
        </div>
      </div>

      <div class="actions">
        <el-button v-if="isOwner" type="danger" @click="onDissolve" :loading="acting">
          解散群组
        </el-button>
        <el-button v-else-if="isMember" type="warning" @click="onQuit" :loading="acting">
          退出群组
        </el-button>
      </div>
    </div>

    <!-- 邀请成员对话框: 两种邀请方式 -->
    <el-dialog v-model="showInviteDialog" title="邀请成员" width="480px" append-to-body class="group-invite-dialog">
      <el-tabs v-model="inviteTab">
        <el-tab-pane label="从好友邀请" name="friend">
          <el-checkbox-group v-model="inviteSelected" class="invite-picker">
            <el-checkbox
              v-for="f in invitableFriends"
              :key="f.id"
              :label="f.id"
            >
              {{ f.nickname || f.username }}
            </el-checkbox>
            <div v-if="invitableFriends.length === 0" class="muted">所有好友都已在群里，或者你还没好友</div>
          </el-checkbox-group>
        </el-tab-pane>
        <el-tab-pane label="按用户名邀请" name="username">
          <p class="muted small">输入用户名（不是昵称），每行一个，可邀请非好友。</p>
          <el-input
            v-model="inviteUsernames"
            type="textarea"
            :rows="6"
            placeholder="例如&#10;testuser&#10;testuser0002"
          />
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="showInviteDialog = false">取消</el-button>
        <el-button
          type="primary"
          @click="onInvite"
          :loading="acting"
          :disabled="inviteTab === 'friend' ? inviteSelected.length === 0 : !inviteUsernames.trim()"
        >邀请</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup>
import { ref, computed } from 'vue'
import { groupApi, friendApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import SmartAvatar from '@/components/SmartAvatar.vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const props = defineProps({
  group: { type: Object, default: null }
})
const emit = defineEmits(['close'])

const visible = defineModel('visible', { default: false })
const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()
const members = ref([])
const groupInfo = ref(null)
const loading = ref(false)
const acting = ref(false)
const showInviteDialog = ref(false)
const inviteTab = ref('friend')
const inviteSelected = ref([])
const inviteUsernames = ref('')

function idEq(a, b) {
  if (a == null || b == null) return false
  return String(a) === String(b)
}

function isFriend(uid) {
  return (contactStore.friends || []).some(f => idEq(f.id, uid))
}

const isOwner = computed(() => {
  const me = userStore.userInfo?.id
  if (!me) return false
  const meRow = members.value.find(m => idEq(m.userId, me))
  return meRow?.role === 2
})

const isMember = computed(() => {
  const me = userStore.userInfo?.id
  if (!me) return false
  return members.value.some(m => idEq(m.userId, me))
})

const invitableFriends = computed(() => {
  const memberIds = new Set(members.value.map(m => String(m.userId)))
  return (contactStore.friends || []).filter(f => !memberIds.has(String(f.id)))
})

function memberName(m) {
  return m.nickname || m.username || `用户${m.userId}`
}

async function loadAll() {
  if (!props.group?.groupId) return
  loading.value = true
  try {
    if (!contactStore.friends || contactStore.friends.length === 0) {
      await contactStore.fetchFriends()
    }
    const [infoRes, memRes] = await Promise.all([
      groupApi.getInfo(props.group.groupId),
      groupApi.getMembers(props.group.groupId)
    ])
    groupInfo.value = infoRes.data
    members.value = memRes.data || []
  } catch (e) {
    ElMessage.error('加载群信息失败')
  } finally {
    loading.value = false
  }
}

async function loadMembers() {
  if (!props.group?.groupId) return
  try {
    const res = await groupApi.getMembers(props.group.groupId)
    members.value = res.data || []
  } catch (e) { /* */ }
}

async function copyGroupNo() {
  try {
    await navigator.clipboard.writeText(groupInfo.value.groupNo)
    ElMessage.success('群号已复制')
  } catch (e) {
    ElMessage.warning('复制失败，群号: ' + groupInfo.value.groupNo)
  }
}

async function onEditNotice() {
  try {
    const result = await ElMessageBox.prompt('请输入群公告', '编辑群公告', {
      inputType: 'textarea',
      inputValue: groupInfo.value?.notice || '',
      inputPlaceholder: '群公告会显示在所有成员的群信息里',
      inputValidator: (v) => v == null || v.length <= 500 || '不能超过 500 字',
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    const newNotice = (result.value ?? '').trim()
    await groupApi.updateNotice(props.group.groupId, newNotice)
    groupInfo.value = { ...groupInfo.value, notice: newNotice }
    ElMessage.success('群公告已更新')
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    // 其他失败 request.js 已 toast
  }
}

async function onAddFriend(m) {
  try {
    await ElMessageBox.confirm(`向 ${memberName(m)} 发送好友申请？`, '添加好友', { type: 'info' })
  } catch (e) { return }
  try {
    await friendApi.sendRequest(m.userId)
    ElMessage.success('已发送好友申请')
  } catch (e) { /* request.js toast */ }
}

async function onTransfer(newOwnerId) {
  try {
    await ElMessageBox.confirm('确定要把群主转让给该成员吗？转让后你将变为普通成员', '转让群主', { type: 'warning' })
  } catch (e) { return }
  acting.value = true
  try {
    await groupApi.transferOwnership(props.group.groupId, newOwnerId)
    ElMessage.success('已转让群主')
    await loadMembers()
  } catch (e) { /* */ } finally { acting.value = false }
}

async function onKick(targetId, name) {
  try {
    await ElMessageBox.confirm(`确定要把 ${name} 踢出群？`, '踢出群成员', { type: 'warning' })
  } catch (e) { return }
  acting.value = true
  try {
    await groupApi.kick(props.group.groupId, targetId)
    ElMessage.success('已踢出')
    await loadMembers()
  } catch (e) { /* */ } finally { acting.value = false }
}

async function onInvite() {
  acting.value = true
  try {
    if (inviteTab.value === 'friend') {
      if (inviteSelected.value.length === 0) return
      await groupApi.invite(props.group.groupId, inviteSelected.value.slice())
      ElMessage.success(`已邀请 ${inviteSelected.value.length} 位好友`)
    } else {
      const names = inviteUsernames.value.split(/[\s,，;；]+/).map(s => s.trim()).filter(Boolean)
      if (names.length === 0) return
      const res = await groupApi.inviteByUsername(props.group.groupId, names)
      const data = res.data || {}
      const invited = data.invited || 0
      const notFound = data.notFound || []
      let msg = `已邀请 ${invited} 位用户`
      if (notFound.length > 0) msg += `；未找到: ${notFound.join(', ')}`
      if (invited > 0) ElMessage.success(msg)
      else ElMessage.warning(msg)
    }
    showInviteDialog.value = false
    inviteSelected.value = []
    inviteUsernames.value = ''
    await loadMembers()
  } catch (e) { /* */ } finally { acting.value = false }
}

async function onDissolve() {
  try {
    await ElMessageBox.confirm('解散群后所有成员将被移除，操作不可撤回。确认解散？', '解散群组', {
      type: 'error', confirmButtonText: '确认解散'
    })
  } catch (e) { return }
  acting.value = true
  try {
    await groupApi.dissolve(props.group.groupId)
    ElMessage.success('群组已解散')
    afterLeave()
  } catch (e) { /* */ } finally { acting.value = false }
}

async function onQuit() {
  try {
    await ElMessageBox.confirm('确认退出该群？退出后不再接收该群消息', '退出群组', { type: 'warning' })
  } catch (e) { return }
  acting.value = true
  try {
    await groupApi.quit(props.group.groupId)
    ElMessage.success('已退出群组')
    afterLeave()
  } catch (e) { /* */ } finally { acting.value = false }
}

function afterLeave() {
  visible.value = false
  const idx = chatStore.conversations.findIndex(c => c.type === 'group' && idEq(c.groupId, props.group.groupId))
  if (idx >= 0) chatStore.conversations.splice(idx, 1)
  chatStore.currentConversation = null
  chatStore.clearMessages()
  emit('close')
}
</script>

<style scoped>
.group-panel {
  padding: 4px 6px 0;
  display: flex;
  flex-direction: column;
  height: 100%;
}

.group-overview {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border-radius: 22px;
  background: linear-gradient(135deg, rgba(64, 158, 255, 0.12) 0%, rgba(64, 158, 255, 0.06) 100%);
  border: 1px solid rgba(64, 158, 255, 0.12);
  margin-bottom: 16px;
}

.overview-copy h3 {
  margin: 0;
  font-size: 20px;
  color: #1f2d3d;
}

.overview-copy p {
  margin: 8px 0 0;
  color: #7a8699;
  font-size: 13px;
}

.group-meta {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.meta-card {
  flex: 1;
  padding: 16px 18px;
  border-radius: 18px;
  background: #f8fbff;
  border: 1px solid #e8eef5;
}
.meta-card-label {
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #8a94a6;
}
.meta-card-value {
  margin-top: 10px;
  color: #303133;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-all;
}
.meta-card-notice .notice-text {
  white-space: pre-wrap;
  color: #595959;
}
.section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.section-title {
  font-size: 15px;
  color: #1f2d3d;
  font-weight: 600;
}
.member-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 4px;
}
.member-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 18px;
  border: 1px solid #edf1f7;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcfe 100%);
  margin-bottom: 10px;
}
.member-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.member-name {
  font-size: 14px;
  color: #1f2d3d;
  font-weight: 600;
}
.member-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.actions {
  padding: 18px 0 6px;
  display: flex;
  justify-content: center;
}
.invite-picker {
  display: flex;
  flex-direction: column;
  max-height: 280px;
  overflow-y: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 12px;
}
.invite-picker .el-checkbox {
  margin-right: 0;
  margin-bottom: 4px;
}
.muted {
  color: #909399;
  font-size: 12px;
  padding: 4px;
}
.small {
  margin-bottom: 8px;
}

:deep(.group-drawer .el-drawer) {
  background: linear-gradient(180deg, #fbfdff 0%, #f4f7fc 100%);
}

:deep(.group-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1f7;
  color: #1f2d3d;
  font-weight: 700;
}

:deep(.group-drawer .el-drawer__body) {
  padding: 18px 20px 20px;
}

:deep(.group-invite-dialog .el-dialog) {
  border-radius: 26px;
  overflow: hidden;
  background: linear-gradient(180deg, #fbfdff 0%, #f6f9fd 100%);
  box-shadow: 0 28px 60px rgba(15, 23, 42, 0.16);
}

:deep(.group-invite-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1f7;
}

:deep(.group-invite-dialog .el-dialog__body) {
  padding: 20px 24px;
}

:deep(.group-invite-dialog .el-dialog__footer) {
  padding: 0 24px 22px;
}

:deep(.group-invite-dialog .el-textarea__inner) {
  border-radius: 16px;
  box-shadow: 0 0 0 1px #e8eef5 inset;
}

@media (max-width: 520px) {
  .group-meta {
    flex-direction: column;
  }
}
</style>
