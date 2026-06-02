<template>
  <div class="main-container">
    <div class="sidebar">
      <div class="sidebar-header">
        <div class="brand-block">
          <div class="brand-kicker">WeLink</div>
          <div class="brand-title">协作通讯台</div>
        </div>
        <div class="user-info">
          <SmartAvatar :src="userStore.userInfo?.avatar" :name="userStore.userInfo?.username" :size="40" />
          <div class="user-meta">
            <span class="username">{{ userStore.userInfo?.username }}</span>
            <span class="user-hint">已连接即时通讯服务</span>
          </div>
        </div>
      </div>

      <div class="sidebar-overview">
        <div class="overview-card">
          <span class="overview-label">会话</span>
          <strong class="overview-value">{{ chatStore.conversations.length }}</strong>
        </div>
        <div class="overview-card">
          <span class="overview-label">好友</span>
          <strong class="overview-value">{{ contactStore.friends.length }}</strong>
        </div>
        <div class="overview-card">
          <span class="overview-label">群组</span>
          <strong class="overview-value">{{ contactStore.groups.length }}</strong>
        </div>
      </div>

      <div class="sidebar-nav">
        <el-menu
          :default-active="activeMenu"
          class="nav-menu"
          @select="handleMenuSelect"
        >
          <el-menu-item index="/chat">
            <el-icon><ChatDotRound /></el-icon>
            <span>消息</span>
          </el-menu-item>
          <el-menu-item index="/contacts">
            <el-icon><UserFilled /></el-icon>
            <span>联系人</span>
          </el-menu-item>
        </el-menu>
      </div>

      <div class="sidebar-footer">
        <el-button text @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </el-button>
      </div>
    </div>

    <div class="main-content">
      <router-view />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useContactStore } from '@/stores/contact'
import { useChatStore } from '@/stores/chat'
import { wsService } from '@/utils/websocket'
import SmartAvatar from '@/components/SmartAvatar.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const contactStore = useContactStore()
const chatStore = useChatStore()

const activeMenu = computed(() => route.path)
let wsMessageHandler = null

const handleMenuSelect = (index) => {
  router.push(index)
}

const handleLogout = () => {
  userStore.logout()
  router.push('/login')
}

const initWebSocket = () => {
  wsMessageHandler = (data) => {
    if (data.type === 'message' && data.fromUserId) {
      chatStore.addMessage(data)

      if (chatStore.currentConversation) {
        const isCurrentPrivate = chatStore.currentConversation.type === 'private' &&
          ((data.fromUserId === chatStore.currentConversation.userId) ||
           (data.toUserId === chatStore.currentConversation.userId))
        const isCurrentGroup = chatStore.currentConversation.type === 'group' &&
          data.groupId === chatStore.currentConversation.groupId

        if (isCurrentPrivate || isCurrentGroup) {
          scrollToBottom()
        }
      }
    } else if (data.type === 'recall') {
      // 后端广播撤回: 标记本地消息 status=2
      chatStore.markRecalled(data.msgId)
    } else if (data.type === 'system') {
      if (data.action === 'online') {
        contactStore.updateFriendOnlineStatus(data.userId, true)
        chatStore.updateConversationOnlineStatus(data.userId, true)
      } else if (data.action === 'offline') {
        contactStore.updateFriendOnlineStatus(data.userId, false)
        chatStore.updateConversationOnlineStatus(data.userId, false)
      }
    }
  }
  wsService.onMessage(wsMessageHandler)
}

const scrollToBottom = () => {
  const messageList = document.querySelector('.message-list')
  if (messageList) {
    messageList.scrollTop = messageList.scrollHeight
  }
}

onMounted(async () => {
  if (userStore.token) {
    wsService.ensureConnected(userStore.token)
  }
  initWebSocket()
  if (userStore.token) {
    const [friends, groups] = await Promise.all([
      contactStore.fetchFriends(),
      contactStore.fetchGroups()
    ])
    await chatStore.initConversations(friends, groups)
    await chatStore.applyConversationSummaries()
  }
})

onBeforeUnmount(() => {
  if (wsMessageHandler) {
    wsService.offMessage(wsMessageHandler)
  }
})
</script>

<style scoped>
.main-container {
  display: flex;
  width: 100vw;
  height: 100vh;
  padding: 16px;
  gap: 16px;
  background:
    radial-gradient(circle at top left, rgba(64, 158, 255, 0.12), transparent 24%),
    linear-gradient(180deg, #f7faff 0%, #f3f6fb 100%);
  box-sizing: border-box;
}

.sidebar {
  width: 240px;
  background: linear-gradient(180deg, #24415f 0%, #1f344d 100%);
  color: white;
  display: flex;
  flex-direction: column;
  border-radius: 28px;
  box-shadow: 0 22px 50px rgba(31, 52, 77, 0.22);
  overflow: hidden;
}

.sidebar-header {
  padding: 24px 20px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-block {
  margin-bottom: 18px;
}

.brand-kicker {
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.56);
}

.brand-title {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 700;
  color: #fff;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(10px);
}

.user-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.username {
  font-size: 16px;
  font-weight: 600;
  color: #fff;
}

.user-hint {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.66);
}

.sidebar-nav {
  flex: 1;
  padding: 20px 16px;
}

.sidebar-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 0 16px;
}

.overview-card {
  padding: 14px 12px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(10px);
}

.overview-label {
  display: block;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.62);
}

.overview-value {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  line-height: 1;
  color: #fff;
}

.nav-menu {
  background-color: transparent;
  border: none;
}

:deep(.nav-menu .el-menu-item) {
  height: 52px;
  margin-bottom: 8px;
  border-radius: 16px;
  color: rgba(255, 255, 255, 0.8);
  font-size: 15px;
  font-weight: 500;
}

:deep(.nav-menu .el-menu-item:hover),
:deep(.nav-menu .el-menu-item.is-active) {
  background: rgba(255, 255, 255, 0.14);
  color: white;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

:deep(.nav-menu .el-menu-item .el-icon) {
  margin-right: 10px;
}

.sidebar-footer {
  padding: 18px 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

:deep(.sidebar-footer .el-button) {
  color: rgba(255, 255, 255, 0.8);
  width: 100%;
  height: 48px;
  margin: 0;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
}

:deep(.sidebar-footer .el-button:hover) {
  color: white;
  background: rgba(255, 255, 255, 0.14);
}

.main-content {
  flex: 1;
  overflow: hidden;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.64);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.5);
  backdrop-filter: blur(12px);
}

@media (max-width: 900px) {
  .main-container {
    padding: 10px;
    gap: 10px;
  }

  .sidebar {
    width: 220px;
  }
}

@media (max-width: 768px) {
  .main-container {
    flex-direction: column;
    height: auto;
    min-height: 100vh;
  }

  .sidebar {
    width: 100%;
  }

  .sidebar-overview {
    grid-template-columns: repeat(3, minmax(88px, 1fr));
  }
}
</style>
