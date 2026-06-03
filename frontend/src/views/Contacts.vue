<template>
  <div class="contacts-container">
    <div class="contacts-page">
      <section class="contacts-hero">
        <div class="hero-copy">
          <div class="hero-kicker">通讯录</div>
          <h2>联系人与群组</h2>
          <p>在这里统一管理好友、群组和好友申请，点击任意条目即可直接开始聊天。</p>
        </div>
        <div class="hero-stats">
          <div class="stat-card">
            <span class="stat-label">好友</span>
            <strong class="stat-value">{{ contactStore.friends.length }}</strong>
          </div>
          <div class="stat-card">
            <span class="stat-label">群组</span>
            <strong class="stat-value">{{ contactStore.groups.length }}</strong>
          </div>
          <div class="stat-card">
            <span class="stat-label">申请</span>
            <strong class="stat-value">{{ contactStore.friendRequests.length }}</strong>
          </div>
        </div>
      </section>

      <section class="contacts-panel">
        <div class="contacts-toolbar">
          <div class="toolbar-main">
            <el-tabs v-model="activeTab" class="contacts-tabs">
              <el-tab-pane label="好友" name="friends" />
              <el-tab-pane label="群组" name="groups" />
              <el-tab-pane name="requests">
                <template #label>
                  <span class="request-tab-label">
                    好友申请
                    <el-badge v-if="contactStore.friendRequests.length > 0" :value="contactStore.friendRequests.length" :max="99" />
                  </span>
                </template>
              </el-tab-pane>
            </el-tabs>
            <div class="toolbar-copy">
              <h3>{{ currentTabTitle }}</h3>
              <p>{{ currentTabDescription }}</p>
            </div>
          </div>

          <div class="contacts-actions">
            <el-button v-if="activeTab === 'friends'" type="primary" @click="showAddFriendDialog = true">
              <el-icon><Plus /></el-icon>
              添加好友
            </el-button>
            <template v-else-if="activeTab === 'groups'">
              <el-button type="primary" @click="showCreateGroupDialog = true">
                <el-icon><Plus /></el-icon>
                创建群组
              </el-button>
              <el-button plain @click="showJoinGroupDialog = true">
                <el-icon><UserFilled /></el-icon>
                加入群组
              </el-button>
            </template>
          </div>
        </div>

        <div class="contacts-list">
          <div v-if="activeTab === 'friends'" class="contacts-grid">
            <div
              v-for="friend in contactStore.friends"
              :key="friend.id"
              class="contact-item"
              role="button"
              tabindex="0"
              @click="startChatWithFriend(friend)"
              @keydown.enter.prevent="startChatWithFriend(friend)"
              @keydown.space.prevent="startChatWithFriend(friend)"
            >
              <SmartAvatar :src="friend.avatar" :name="friend.nickname || friend.username" :size="48" />
              <div class="contact-info">
                <div class="contact-name">{{ friend.nickname || friend.username }}</div>
                <div class="contact-subtitle">@{{ friend.username }}</div>
                <div class="contact-status" :class="{ online: friend.online }">
                  {{ friend.online ? '在线，可直接发起聊天' : '离线，消息会在上线后送达' }}
                </div>
              </div>
              <el-popconfirm title="确定删除该好友?" @confirm="handleDeleteFriend(friend.id)" @click.stop>
                <template #reference>
                  <el-button class="delete-friend-btn" type="danger" plain size="small" circle @click.stop>
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </template>
              </el-popconfirm>
            </div>
            <el-empty v-if="contactStore.friends.length === 0" :description="currentEmptyDescription" />
          </div>

          <div v-else-if="activeTab === 'groups'" class="contacts-grid">
            <div
              v-for="group in contactStore.groups"
              :key="group.id"
              class="contact-item"
              role="button"
              tabindex="0"
              @click="startChatWithGroup(group)"
              @keydown.enter.prevent="startChatWithGroup(group)"
              @keydown.space.prevent="startChatWithGroup(group)"
            >
              <SmartAvatar :src="group.avatar" :name="group.groupName" :size="48" type="group" />
              <div class="contact-info">
                <div class="contact-name">{{ group.groupName }}</div>
                <div class="contact-subtitle">群号 {{ group.groupNo || '自动生成' }}</div>
                <div class="contact-status">{{ group.memberCount }} 人 · 点击进入群聊</div>
              </div>
            </div>
            <el-empty v-if="contactStore.groups.length === 0" :description="currentEmptyDescription" />
          </div>

          <div v-else-if="activeTab === 'requests'" class="contacts-request-list">
            <div
              v-for="request in contactStore.friendRequests"
              :key="request.id"
              class="contact-item contact-request-item"
            >
              <SmartAvatar :src="request.avatar" :name="request.nickname || request.username" :size="48" />
              <div class="contact-info">
                <div class="contact-name">{{ request.nickname || request.username }}</div>
                <div class="contact-subtitle">@{{ request.username }}</div>
                <div class="contact-status">请求添加你为好友</div>
              </div>
              <div class="request-actions">
                <el-button type="primary" size="small" @click="handleAcceptRequest(request)">接受</el-button>
                <el-button size="small" @click="handleRejectRequest(request)">拒绝</el-button>
              </div>
            </div>
            <el-empty v-if="contactStore.friendRequests.length === 0" :description="currentEmptyDescription" />
          </div>
        </div>
      </section>
    </div>

    <el-dialog v-model="showAddFriendDialog" title="添加好友" width="400px" class="contact-dialog">
      <el-form :model="addFriendForm">
        <el-form-item label="用户名">
          <el-input v-model="addFriendForm.username" placeholder="请输入用户名" @keyup.enter="handleSearchUser">
            <template #append>
              <el-button @click="handleSearchUser">搜索</el-button>
            </template>
          </el-input>
        </el-form-item>
        <div v-if="searchedUser" class="search-result">
          <SmartAvatar :src="searchedUser.avatar" :name="searchedUser.nickname || searchedUser.username" :size="40" />
          <div class="search-result-info">
            <div class="search-result-name">{{ searchedUser.nickname || searchedUser.username }}</div>
            <div class="search-result-username">@{{ searchedUser.username }}</div>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showAddFriendDialog = false">取消</el-button>
        <el-button type="primary" @click="handleAddFriend" :disabled="!searchedUser">发送申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showCreateGroupDialog" title="创建群组" width="540px" class="contact-dialog">
      <el-form :model="createGroupForm" label-width="80px">
        <el-form-item label="群组名称">
          <el-input v-model="createGroupForm.groupName" placeholder="请输入群组名称" />
        </el-form-item>
        <el-form-item label="群公告">
          <el-input
            v-model="createGroupForm.notice"
            type="textarea"
            :rows="2"
            placeholder="请输入群公告（可选）"
          />
        </el-form-item>
        <el-form-item label="选择成员">
          <el-checkbox-group v-model="createGroupForm.memberIds" class="member-picker">
            <el-checkbox
              v-for="f in contactStore.friends"
              :key="f.id"
              :label="f.id"
            >
              {{ f.nickname || f.username }}
            </el-checkbox>
            <div v-if="(contactStore.friends || []).length === 0" class="muted">还没有好友，可以稍后通过群内「邀请成员」加</div>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateGroupDialog = false">取消</el-button>
        <el-button type="primary" @click="handleCreateGroup">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showJoinGroupDialog" title="加入群组" width="440px" class="contact-dialog">
      <el-tabs v-model="joinGroupTab">
        <el-tab-pane label="按群号" name="no">
          <el-form>
            <el-form-item label="群号">
              <el-input v-model="joinGroupForm.groupNo" placeholder="请输入 8 位群号" @keyup.enter="handleJoinGroup">
                <template #append>
                  <el-button @click="handleJoinGroup">加入</el-button>
                </template>
              </el-input>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="按群名" name="name">
          <el-form>
            <el-form-item label="群名">
              <el-input v-model="joinGroupForm.groupName" placeholder="请输入群组名称（精确匹配）" @keyup.enter="handleJoinGroup">
                <template #append>
                  <el-button @click="handleJoinGroup">加入</el-button>
                </template>
              </el-input>
              <div class="muted small">⚠️ 群名可能重复，建议优先用群号</div>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { authApi } from '@/api'
import SmartAvatar from '@/components/SmartAvatar.vue'
import { ElMessage } from 'element-plus'

const router = useRouter()
const contactStore = useContactStore()
const chatStore = useChatStore()

const activeTab = ref('friends')
const showAddFriendDialog = ref(false)
const showCreateGroupDialog = ref(false)
const showJoinGroupDialog = ref(false)
const searchedUser = ref(null)

const addFriendForm = reactive({
  username: ''
})

const createGroupForm = reactive({
  groupName: '',
  notice: '',
  memberIds: []
})

const joinGroupTab = ref('no')
const joinGroupForm = reactive({
  groupNo: '',
  groupName: ''
})

const tabMeta = {
  friends: {
    title: '好友列表',
    description: '查看好友在线状态，点击卡片即可开始私聊。',
    empty: '暂无好友，先去添加几个联系人吧'
  },
  groups: {
    title: '我的群组',
    description: '把常用群集中在这里，建群和加群操作也放在同一工具栏。',
    empty: '暂无群组，可以先创建一个群聊'
  },
  requests: {
    title: '好友申请',
    description: '统一处理待接受的好友请求，避免消息被遗漏。',
    empty: '暂无待处理的好友申请'
  }
}

const currentTabTitle = computed(() => tabMeta[activeTab.value]?.title || '')
const currentTabDescription = computed(() => tabMeta[activeTab.value]?.description || '')
const currentEmptyDescription = computed(() => tabMeta[activeTab.value]?.empty || '暂无数据')

onMounted(async () => {
  await contactStore.fetchFriends()
  await contactStore.fetchGroups()
  await contactStore.fetchPendingRequests()
})

const startChatWithFriend = (friend) => {
  const conversation = {
    id: `private_${friend.id}`,
    type: 'private',
    userId: friend.id,
    name: friend.nickname || friend.username,
    avatar: friend.avatar,
    lastMessage: '',
    lastTime: '',
    unread: 0
  }
  
  chatStore.setCurrentConversation(conversation)
  
  if (!chatStore.conversations.find(c => c.id === conversation.id)) {
    chatStore.conversations.unshift(conversation)
  }
  
  router.push('/chat')
}

const startChatWithGroup = (group) => {
  const conversation = {
    id: `group_${group.id}`,
    type: 'group',
    groupId: group.id,
    name: group.groupName,
    avatar: group.avatar,
    lastMessage: '',
    lastTime: '',
    unread: 0
  }
  
  chatStore.setCurrentConversation(conversation)
  
  if (!chatStore.conversations.find(c => c.id === conversation.id)) {
    chatStore.conversations.unshift(conversation)
  }
  
  router.push('/chat')
}

const handleSearchUser = async () => {
  if (!addFriendForm.username) {
    ElMessage.warning('请输入用户名')
    return
  }
  
  try {
    const res = await authApi.searchUserByUsername(addFriendForm.username)
    searchedUser.value = res.data
  } catch (error) {
    searchedUser.value = null
    ElMessage.error(error.response?.data?.message || '用户不存在')
  }
}

const handleAddFriend = async () => {
  if (!searchedUser.value) {
    ElMessage.warning('请先搜索用户')
    return
  }
  
  try {
    await contactStore.sendFriendRequest(searchedUser.value.id)
    ElMessage.success('好友申请已发送')
    showAddFriendDialog.value = false
    addFriendForm.username = ''
    searchedUser.value = null
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '发送好友申请失败')
  }
}

const handleAcceptRequest = async (user) => {
  try {
    await contactStore.acceptFriendRequest(user.id)
    ElMessage.success('已接受好友申请')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '接受好友申请失败')
  }
}

const handleRejectRequest = async (user) => {
  try {
    await contactStore.rejectFriendRequest(user.id)
    ElMessage.success('已拒绝好友申请')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '拒绝好友申请失败')
  }
}

const handleDeleteFriend = async (friendId) => {
  try {
    await contactStore.deleteFriend(friendId)
    ElMessage.success('已删除好友')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '删除好友失败')
  }
}

const handleCreateGroup = async () => {
  if (!createGroupForm.groupName) {
    ElMessage.warning('请输入群组名称')
    return
  }

  try {
    await contactStore.createGroup({
      groupName: createGroupForm.groupName,
      notice: createGroupForm.notice,
      memberIds: createGroupForm.memberIds || []
    })
    ElMessage.success('群组创建成功')
    showCreateGroupDialog.value = false
    createGroupForm.groupName = ''
    createGroupForm.notice = ''
    createGroupForm.memberIds = []
    await contactStore.fetchGroups()
  } catch (error) {
    console.error('Failed to create group:', error)
  }
}

const handleJoinGroup = async () => {
  try {
    if (joinGroupTab.value === 'no') {
      if (!joinGroupForm.groupNo) {
        ElMessage.warning('请输入群号')
        return
      }
      await contactStore.joinGroupByNo(joinGroupForm.groupNo.trim())
    } else {
      if (!joinGroupForm.groupName) {
        ElMessage.warning('请输入群组名称')
        return
      }
      await contactStore.joinGroupByName(joinGroupForm.groupName)
    }
    ElMessage.success('加入群组成功')
    showJoinGroupDialog.value = false
    joinGroupForm.groupName = ''
    joinGroupForm.groupNo = ''
    await contactStore.fetchGroups()
  } catch (error) {
    // request.js 已 toast, 不再 ElMessage.error
  }
}
</script>

<style scoped>
.contacts-container {
  height: 100%;
  padding: 28px;
  overflow-y: auto;
  background:
    radial-gradient(circle at top right, rgba(64, 158, 255, 0.12), transparent 22%),
    linear-gradient(180deg, #f7faff 0%, #f3f6fb 100%);
}

.contacts-page {
  max-width: 1160px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.contacts-hero {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 28px 32px;
  border-radius: 24px;
  color: #fff;
  background: linear-gradient(135deg, #1f4b7a 0%, #409eff 55%, #6bb7ff 100%);
  box-shadow: 0 18px 48px rgba(31, 75, 122, 0.22);
}

.hero-copy h2 {
  margin: 6px 0 10px;
  font-size: 30px;
  line-height: 1.2;
}

.hero-copy p {
  margin: 0;
  max-width: 560px;
  color: rgba(255, 255, 255, 0.86);
  line-height: 1.7;
}

.hero-kicker {
  font-size: 13px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.75);
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(110px, 1fr));
  gap: 12px;
  min-width: 360px;
}

.stat-card {
  padding: 18px 18px 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(10px);
}

.stat-label {
  display: block;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.78);
}

.stat-value {
  display: block;
  margin-top: 8px;
  font-size: 28px;
  line-height: 1;
  font-weight: 700;
}

.contacts-panel {
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(222, 230, 240, 0.9);
  border-radius: 24px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.contacts-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 18px;
  border-bottom: 1px solid #edf1f7;
}

.toolbar-main {
  min-width: 0;
  flex: 1;
}

.contacts-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.request-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.toolbar-copy {
  margin-top: 14px;
}

.toolbar-copy h3 {
  margin: 0;
  font-size: 20px;
  color: #1f2d3d;
}

.toolbar-copy p {
  margin: 8px 0 0;
  color: #7a8699;
  font-size: 14px;
  line-height: 1.6;
}

.contacts-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 12px;
  min-width: 250px;
}

.contacts-actions .el-button {
  min-width: 132px;
  margin-left: 0;
}

.contacts-list {
  padding: 20px 24px 24px;
}

.contacts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.contacts-request-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.contact-item {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 18px;
  cursor: pointer;
  border-radius: 20px;
  border: 1px solid #edf1f7;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcfe 100%);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  outline: none;
}

.contact-item:hover,
.contact-item:focus-visible {
  transform: translateY(-2px);
  border-color: #bfdbfe;
  box-shadow: 0 14px 28px rgba(64, 158, 255, 0.12);
}

.contact-info {
  flex: 1;
  min-width: 0;
}

.contact-name {
  font-size: 16px;
  color: #1f2d3d;
  font-weight: 600;
}

.contact-subtitle {
  margin-top: 4px;
  color: #8a94a6;
  font-size: 13px;
}

.contact-status {
  font-size: 13px;
  color: #667085;
  margin-top: 10px;
  line-height: 1.5;
}

.contact-status.online {
  color: #16a34a;
}

.search-result {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  background-color: #f5f7fa;
  border-radius: 8px;
  margin-top: 8px;
}

.search-result-info {
  flex: 1;
}

.search-result-name {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.search-result-username {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.request-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-self: center;
}

.contact-request-item {
  cursor: default;
}

.member-picker {
  display: flex;
  flex-direction: column;
  max-height: 220px;
  overflow-y: auto;
  width: 100%;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 8px 12px;
}
.member-picker .el-checkbox {
  margin-right: 0;
  margin-bottom: 4px;
}
.muted {
  color: #909399;
  font-size: 12px;
}

.small {
  margin-top: 8px;
}

:deep(.contact-dialog .el-dialog) {
  border-radius: 26px;
  overflow: hidden;
  background: linear-gradient(180deg, #fbfdff 0%, #f6f9fd 100%);
  box-shadow: 0 28px 60px rgba(15, 23, 42, 0.16);
}

:deep(.contact-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1f7;
}

:deep(.contact-dialog .el-dialog__title) {
  color: #1f2d3d;
  font-weight: 700;
}

:deep(.contact-dialog .el-dialog__body) {
  padding: 20px 24px;
}

:deep(.contact-dialog .el-dialog__footer) {
  padding: 0 24px 22px;
}

:deep(.contact-dialog .el-input__wrapper),
:deep(.contact-dialog .el-textarea__inner) {
  border-radius: 16px;
  box-shadow: 0 0 0 1px #e8eef5 inset;
}

:deep(.contact-dialog .el-input-group__append) {
  border-radius: 0 16px 16px 0;
}

:deep(.contact-dialog .el-tabs__header) {
  margin-bottom: 16px;
}

@media (max-width: 1100px) {
  .contacts-hero,
  .contacts-toolbar {
    flex-direction: column;
  }

  .hero-stats,
  .contacts-actions {
    min-width: 0;
  }

  .contacts-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 768px) {
  .contacts-container {
    padding: 16px;
  }

  .contacts-hero,
  .contacts-toolbar,
  .contacts-list {
    padding-left: 18px;
    padding-right: 18px;
  }

  .hero-stats {
    grid-template-columns: 1fr;
  }

  .contacts-grid {
    grid-template-columns: 1fr;
  }

  .contacts-actions .el-button {
    width: 100%;
  }
}
</style>
