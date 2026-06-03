<template>
  <div class="chat-container">
    <div class="chat-layout">
      <div class="conversation-list">
        <div class="conversation-header">
          <div class="panel-kicker">消息中心</div>
          <h3>最近会话</h3>
          <p>{{ conversationPanelHint }}</p>
        </div>
        <div class="conversation-items">
          <div
            v-for="conv in chatStore.conversations"
            :key="conv.id"
            class="conversation-item"
            role="button"
            tabindex="0"
            :class="{ active: isCurrentConversation(conv) }"
            @click="selectConversation(conv)"
            @keydown.enter.prevent="selectConversation(conv)"
            @keydown.space.prevent="selectConversation(conv)"
          >
            <div class="avatar-wrap">
              <SmartAvatar :src="conv.avatar" :name="conv.name" :size="44" :type="conv.type" />
              <span v-if="conv.type === 'private' && conv.online" class="online-dot"></span>
            </div>
            <div class="conversation-info">
              <div class="conversation-top">
                <span class="conversation-name">
                  {{ conv.name }}
                  <span v-if="conv.type === 'group'" class="conv-member-count">({{ conv.memberCount || 0 }})</span>
                </span>
                <span class="conversation-time">{{ formatTime(conv.lastTime) }}</span>
              </div>
              <div class="conversation-bottom">
                <span class="last-message">{{ lastMessagePreview(conv.lastMessage) }}</span>
                <el-badge v-if="conv.unread" :value="conv.unread" :max="99" class="unread-badge" />
              </div>
            </div>
          </div>
          <el-empty v-if="chatStore.conversations.length === 0" description="暂无消息" />
        </div>
      </div>

      <div class="chat-window">
        <div v-if="chatStore.currentConversation" class="chat-content">
          <div class="chat-header">
            <div class="chat-title-block">
              <h3>{{ chatStore.currentConversation.name }}</h3>
              <p>{{ currentConversationHint }}</p>
            </div>
            <div class="chat-header-actions">
              <el-button class="header-action-btn" link type="primary" @click="openSearchDrawer">
                搜索消息
              </el-button>
              <el-button
                v-if="chatStore.currentConversation.type === 'group'"
                class="header-action-btn"
                link
                type="primary"
                @click="groupPanelVisible = true"
              >
                群信息
              </el-button>
            </div>
          </div>

          <GroupPanel
            v-if="chatStore.currentConversation?.type === 'group'"
            v-model:visible="groupPanelVisible"
            :group="chatStore.currentConversation"
          />

        <el-drawer
          v-model="searchDrawerVisible"
          title="搜索聊天记录"
          size="420px"
          class="search-drawer"
          destroy-on-close
        >
          <div class="search-panel">
            <div class="search-panel-head">
              <div class="panel-kicker">会话搜索</div>
              <h4>按关键词或时间筛选消息</h4>
              <p>结果支持直接跳转到上下文位置，方便快速回看聊天记录。</p>
            </div>
            <el-input
              v-model="searchForm.keyword"
              clearable
              placeholder="输入关键词，可留空只按类型或日期筛选"
              @keyup.enter="submitSearch()"
            />
            <div class="search-filter-row">
              <el-select v-model="searchForm.msgType" clearable placeholder="消息类型">
                <el-option label="文本" :value="1" />
                <el-option label="图片" :value="2" />
                <el-option label="文件" :value="3" />
                <el-option label="系统" :value="4" />
              </el-select>
            </div>
            <el-date-picker
              v-model="searchRange"
              type="datetimerange"
              start-placeholder="开始时间"
              end-placeholder="结束时间"
              range-separator="至"
              value-format="x"
            />
            <div class="search-action-row">
              <el-button @click="resetSearchFilters">重置</el-button>
              <el-button type="primary" :loading="searchLoading" @click="submitSearch()">
                查询
              </el-button>
            </div>
            <div class="search-summary" v-if="searchExecuted">
              共找到 {{ searchTotal }} 条结果
            </div>
            <div v-if="searchResults.length" class="search-result-list">
              <div
                v-for="msg in searchResults"
                :key="msg.msgId || msg.id"
                class="search-result-item"
                role="button"
                tabindex="0"
                @click="jumpToSearchResult(msg)"
                @keydown.enter.prevent="jumpToSearchResult(msg)"
                @keydown.space.prevent="jumpToSearchResult(msg)"
              >
                <div class="search-result-top">
                  <span class="search-result-sender">{{ searchSenderName(msg) }}</span>
                  <el-tag size="small" type="info">{{ messageTypeLabel(msg.msgType) }}</el-tag>
                </div>
                <div class="search-result-time">{{ formatSearchTime(msg.createdAt || msg.timestamp) }}</div>
                <div class="search-result-content">{{ searchMessagePreview(msg) }}</div>
                <div class="search-result-hint">点击可跳转到原消息上下文</div>
              </div>
            </div>
            <el-empty
              v-else-if="searchExecuted && !searchLoading"
              description="当前条件下没有搜索到聊天记录"
            />
            <el-pagination
              v-if="searchTotal > searchPageSize"
              class="search-pagination"
              background
              layout="prev, pager, next, total"
              :current-page="searchPageNum"
              :page-size="searchPageSize"
              :total="searchTotal"
              @current-change="submitSearch"
            />
          </div>
        </el-drawer>

        <div class="message-list" ref="messageListRef" @scroll="handleScroll">
          <div v-if="loadingMore" class="loading-more-hint">加载更多中...</div>
          <div v-else-if="reachedTop" class="loading-more-hint">没有更多消息</div>
          <div
            v-for="msg in chatStore.messages"
            :key="msg.msgId || msg.id"
            class="message-item"
            :class="{
              'message-self': String(msg.fromUserId) === String(userStore.userInfo?.id),
              'message-anchor-active': activeAnchorMsgId && activeAnchorMsgId === msg.msgId
            }"
            :data-msg-id="msg.msgId || ''"
            @contextmenu.prevent="onContextMenu($event, msg)"
          >
            <SmartAvatar :src="msg.avatar" :name="msg.fromUsername" :size="36" />
            <div class="message-content">
              <div class="message-info">
                <span class="message-sender">{{ msg.fromUsername }}</span>
                <span class="message-time">{{ msg.timestamp }}</span>
                <el-button
                  v-if="canRecall(msg)"
                  link
                  type="danger"
                  size="small"
                  class="recall-btn"
                  @click="onRecall(msg)"
                >撤回</el-button>
              </div>
              <div v-if="msg.status === 2 || msg.recalled" class="message-recalled">
                {{ String(msg.fromUserId) === String(userStore.userInfo?.id) ? '你撤回了一条消息' : (msg.fromUsername || '对方') + '撤回了一条消息' }}
              </div>
              <div v-else class="message-bubble" :class="{ 'bubble-file': msg.msgType === 3, 'bubble-image': msg.msgType === 2 }">
                <img v-if="msg.msgType === 2" :src="msg.content" class="msg-image" @click="previewImage(msg.content)" />
                <div v-else-if="msg.msgType === 3" class="msg-file" @click="downloadFile(msg)">
                  <div class="msg-file-icon" :style="{ background: fileIconColor(displayFileName(msg)) }">
                    {{ fileIconEmoji(displayFileName(msg)) }}
                  </div>
                  <div class="msg-file-info">
                    <div class="msg-file-name">{{ displayFileName(msg) }}</div>
                    <div class="msg-file-meta">{{ fileTypeLabel(displayFileName(msg)) }}{{ fileSizeLabel(msg) }} · 点击下载</div>
                  </div>
                </div>
                <span v-else class="msg-text">{{ msg.content }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="message-input">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="3"
            placeholder="输入消息... (Ctrl+Enter 发送)"
            resize="none"
            @keydown.ctrl.enter="sendMessage"
          />
          <div class="input-actions">
            <input
              ref="fileInput"
              type="file"
              style="display:none"
              @change="handleFileSelect"
            />
            <input
              ref="imageInput"
              type="file"
              accept="image/*"
              style="display:none"
              @change="handleFileSelect"
            />
            <el-button :loading="uploading" @click="$refs.imageInput.click()">🖼️ 图片</el-button>
            <el-button :loading="uploading" @click="$refs.fileInput.click()">📎 文件</el-button>
            <el-button type="primary" @click="sendMessage">发送</el-button>
          </div>
        </div>
      </div>

        <div v-else class="chat-empty-state">
          <el-empty description="选择一个会话开始聊天" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import { useChatStore } from '@/stores/chat'
import { useContactStore } from '@/stores/contact'
import { wsService } from '@/utils/websocket'
import { fileApi } from '@/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import GroupPanel from '@/components/GroupPanel.vue'
import SmartAvatar from '@/components/SmartAvatar.vue'
import dayjs from 'dayjs'

const userStore = useUserStore()
const chatStore = useChatStore()
const contactStore = useContactStore()

const inputMessage = ref('')
const messageListRef = ref(null)
const uploading = ref(false)
const loadingMore = ref(false)
const reachedTop = ref(false)
const groupPanelVisible = ref(false)
const searchDrawerVisible = ref(false)
const searchLoading = ref(false)
const searchExecuted = ref(false)
const searchResults = ref([])
const searchTotal = ref(0)
const searchPageNum = ref(1)
const searchPageSize = 20
const searchRange = ref([])
const activeAnchorMsgId = ref('')
const searchForm = ref({
  keyword: '',
  msgType: null
})
let anchorHighlightTimer = null

const conversationPanelHint = computed(() => {
  const total = chatStore.conversations.length || 0
  return total > 0 ? `最近 ${total} 个会话` : '暂无会话'
})

const currentConversationHint = computed(() => {
  const conv = chatStore.currentConversation
  if (!conv) return ''
  if (conv.type === 'group') {
    return `${conv.memberCount || 0} 人群聊`
  }
  return conv.online ? '对方在线' : '私聊会话'
})

onMounted(async () => {
  await contactStore.fetchFriends()
  await contactStore.fetchGroups()

  if (!chatStore.conversations.length) {
    await chatStore.initConversations(contactStore.friends, contactStore.groups)
    await chatStore.applyConversationSummaries()
  }

  await chatStore.loadOfflineMessages()
})

const formatTime = (time) => {
  if (!time) return ''
  return dayjs(time).format('HH:mm')
}

const isCurrentConversation = (conv) => {
  if (!chatStore.currentConversation) return false
  return (
    (conv.type === 'private' && conv.userId === chatStore.currentConversation.userId) ||
    (conv.type === 'group' && conv.groupId === chatStore.currentConversation.groupId)
  )
}

const selectConversation = async (conv) => {
  resetSearchState()
  chatStore.setCurrentConversation(conv)
  chatStore.clearMessages()
  chatStore.resetHistoryState(conv)
  reachedTop.value = false

  if (conv.type === 'private') {
    await chatStore.loadPrivateHistory(conv.userId)
  } else if (conv.type === 'group') {
    await chatStore.loadGroupHistory(conv.groupId)
  }

  await scrollToBottom()
}

const handleScroll = async () => {
  const el = messageListRef.value
  if (!el || loadingMore.value || reachedTop.value) return
  // 顶部 50px 内触发加载更多, 防止用户慢慢滑时一直触发
  if (el.scrollTop < 50) {
    loadingMore.value = true
    const prevHeight = el.scrollHeight
    const prevTop = el.scrollTop
    const loaded = await chatStore.loadMoreHistory()
    loadingMore.value = false
    if (loaded === 0) {
      reachedTop.value = true
      return
    }
    // 保持视觉滚动位置: 新加内容在头部, 把 scrollTop 顶到原相对位置
    await nextTick()
    const newHeight = el.scrollHeight
    el.scrollTop = prevTop + (newHeight - prevHeight)
  }
}

const resetSearchState = () => {
  searchDrawerVisible.value = false
  searchLoading.value = false
  searchExecuted.value = false
  searchResults.value = []
  searchTotal.value = 0
  searchPageNum.value = 1
  searchRange.value = []
  searchForm.value = {
    keyword: '',
    msgType: null
  }
}

const resetSearchFilters = () => {
  searchExecuted.value = false
  searchResults.value = []
  searchTotal.value = 0
  searchPageNum.value = 1
  searchRange.value = []
  searchForm.value = {
    keyword: '',
    msgType: null
  }
}

const openSearchDrawer = () => {
  if (!chatStore.currentConversation) return
  searchDrawerVisible.value = true
}

const buildSearchPayload = (pageNum = 1) => {
  const conv = chatStore.currentConversation
  const payload = {
    conversationType: conv.type === 'private' ? 1 : 2,
    targetId: conv.type === 'private' ? conv.userId : conv.groupId,
    keyword: searchForm.value.keyword?.trim() || null,
    msgType: searchForm.value.msgType || null,
    pageNum,
    pageSize: searchPageSize
  }
  if (searchRange.value?.length === 2) {
    payload.startTime = Number(searchRange.value[0])
    payload.endTime = Number(searchRange.value[1])
  }
  return payload
}

const submitSearch = async (pageNum = 1) => {
  if (!chatStore.currentConversation) return
  searchLoading.value = true
  try {
    const page = await chatStore.searchMessages(buildSearchPayload(pageNum))
    searchResults.value = page?.records || []
    searchTotal.value = Number(page?.total || 0)
    searchPageNum.value = Number(page?.current || pageNum)
    searchExecuted.value = true
  } catch (e) {
    ElMessage.error(e?.message || '搜索失败')
  } finally {
    searchLoading.value = false
  }
}

const formatSearchTime = (time) => {
  if (!time) return ''
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

const messageTypeLabel = (msgType) => {
  if (msgType === 2) return '图片'
  if (msgType === 3) return '文件'
  if (msgType === 4) return '系统'
  return '文本'
}

const searchMessagePreview = (msg) => {
  if (!msg) return ''
  if (msg.msgType === 2) return '[图片]'
  if (msg.msgType === 3) return `[文件] ${displayFileName(msg)}`
  if (msg.msgType === 4) return msg.content || '[系统消息]'
  return msg.content || ''
}

const searchSenderName = (msg) => {
  if (!msg) return '未知用户'
  if (msg.fromUserId === userStore.userInfo.id) return '我'
  if (chatStore.currentConversation?.type === 'private') {
    return chatStore.currentConversation?.name || `用户${msg.fromUserId}`
  }
  return msg.fromUsername || `用户${msg.fromUserId}`
}

const buildContextPayload = (msg) => {
  const conv = chatStore.currentConversation
  return {
    conversationType: conv.type === 'private' ? 1 : 2,
    targetId: conv.type === 'private' ? conv.userId : conv.groupId,
    msgId: msg.msgId,
    beforeLimit: 15,
    afterLimit: 15
  }
}

const markAnchorActive = (msgId) => {
  activeAnchorMsgId.value = msgId || ''
  if (anchorHighlightTimer) clearTimeout(anchorHighlightTimer)
  if (!msgId) return
  anchorHighlightTimer = setTimeout(() => {
    if (activeAnchorMsgId.value === msgId) {
      activeAnchorMsgId.value = ''
    }
  }, 3000)
}

const scrollToMessageAnchor = async (msgId) => {
  await nextTick()
  const listEl = messageListRef.value
  if (!listEl || !msgId) return false
  const targetEl = Array.from(listEl.querySelectorAll('.message-item[data-msg-id]'))
    .find(el => el.dataset.msgId === msgId)
  if (!targetEl) return false
  targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' })
  markAnchorActive(msgId)
  return true
}

const jumpToSearchResult = async (msg) => {
  if (!msg?.msgId || !chatStore.currentConversation) return
  searchLoading.value = true
  try {
    const context = await chatStore.getMessageContext(buildContextPayload(msg))
    chatStore.mergeMessages(context?.records || [])
    const found = await scrollToMessageAnchor(context?.anchorMsgId || msg.msgId)
    if (!found) {
      ElMessage.warning('已加载消息上下文，但暂未定位到目标消息')
      return
    }
    ElMessage.success('已跳转到原消息位置')
  } catch (e) {
    ElMessage.error(e?.message || '跳转失败')
  } finally {
    searchLoading.value = false
  }
}

const sendMessage = () => {
  if (!inputMessage.value.trim() || !chatStore.currentConversation) return

  const conv = chatStore.currentConversation
  // 生成稳定 msgId, 后端持久化时复用; 前端用它做去重 + 撤回按钮判定
  const msgId = `${userStore.userInfo.id}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const message = {
    msgId,
    content: inputMessage.value.trim(),
    msgType: 1,
    fromUserId: String(userStore.userInfo?.id),
    fromUsername: userStore.userInfo?.nickname || userStore.userInfo?.username,
    timestamp: dayjs().format('YYYY-MM-DD HH:mm:ss')
  }

  if (conv.type === 'private') {
    message.toUserId = conv.userId
    wsService.sendMessage({
      msgId,
      toUserId: conv.userId,
      msgType: 1,
      content: message.content
    })
  } else if (conv.type === 'group') {
    message.groupId = conv.groupId
    wsService.sendMessage({
      msgId,
      groupId: conv.groupId,
      msgType: 1,
      content: message.content
    })
  }

  chatStore.addMessage(message)
  inputMessage.value = ''
  scrollToBottom()
}

const handleFileSelect = async (event) => {
  const file = event.target.files[0]
  if (!file || !chatStore.currentConversation) return

  const maxSize = 1 * 1024 * 1024 * 1024
  if (file.size > maxSize) {
    ElMessage.warning('文件不超过1GB')
    event.target.value = ''
    return
  }

  uploading.value = true
  try {
    const res = await fileApi.upload(file)
    // 后端返回 { fileId, filename, size, mimeType, url } — url 是 /api/v1/files/{fileId} 代理路径, 永不过期
    const url = res.data?.url
    const fileName = res.data?.filename || file.name
    if (!url) {
      ElMessage.error('上传返回数据异常')
      return
    }
    const msgType = file.type?.startsWith('image/') ? 2 : 3
    sendFileMessage(url, fileName, msgType)
  } catch (e) {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

const sendFileMessage = (url, fileName, msgType) => {
  const conv = chatStore.currentConversation
  // content 统一为 url 字符串, 与接收端模板 `:src` / `:href` 直接用 content 匹配.
  // fileName 仅用于本地显示, 不再 JSON 包装以避免发送 / 接收两端解析不一致.
  const msgId = `${userStore.userInfo.id}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

  const message = {
    msgId,
    content: url,
    fileName,
    msgType,
    fromUserId: userStore.userInfo.id,
    fromUsername: userStore.userInfo.username,
    timestamp: dayjs().format('YYYY-MM-DD HH:mm:ss')
  }

  if (conv.type === 'private') {
    // 关键: 必须把 toUserId 写到本地 message 对象, 否则 chat.js 的 isCurrentConvMsg 判定不属于当前会话, 自己看不到
    message.toUserId = conv.userId
    wsService.sendMessage({ toUserId: conv.userId, msgType, content: url, msgId })
  } else {
    message.groupId = conv.groupId
    wsService.sendMessage({ groupId: conv.groupId, msgType, content: url, msgId })
  }

  chatStore.addMessage(message)
  scrollToBottom()
}

const scrollToBottom = async () => {
  await nextTick()
  if (messageListRef.value) {
    messageListRef.value.scrollTop = messageListRef.value.scrollHeight
  }
}

const previewImage = (url) => {
  window.open(url, '_blank')
}

// 从 message content URL 抠出 fileId, 用于按需查 meta + 强制下载
const fileIdFromUrl = (url) => {
  if (!url || typeof url !== 'string') return null
  const m = url.match(/\/api\/v1\/files\/([0-9a-fA-F-]+)/)
  return m ? m[1] : null
}

// 文件消息: 优先用本地 msg.fileName (自己发的当场有), 否则查 fileMetaCache, 没有则触发异步加载
const displayFileName = (msg) => {
  if (msg.fileName) return msg.fileName
  const fid = fileIdFromUrl(msg.content)
  if (!fid) return '文件'
  const cached = chatStore.fileMetaCache[fid]
  if (cached && !cached._loading && cached.filename) return cached.filename
  // 触发异步加载 (响应式, 加载完模板会自动重渲染)
  if (!cached) chatStore.ensureFileMeta(fid)
  return '加载中...'
}

const fileSizeLabel = (msg) => {
  const fid = fileIdFromUrl(msg.content)
  const cached = fid ? chatStore.fileMetaCache[fid] : null
  const size = cached?.size || msg.size
  if (!size || size <= 0) return ''
  if (size < 1024) return ' · ' + size + ' B'
  if (size < 1024 * 1024) return ' · ' + (size / 1024).toFixed(1) + ' KB'
  if (size < 1024 * 1024 * 1024) return ' · ' + (size / 1024 / 1024).toFixed(1) + ' MB'
  return ' · ' + (size / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

// 文件下载: 走代理 ?download=1 让浏览器强制 attachment (PDF/MD/图片都会真下载)
const downloadFile = (msg) => {
  const url = msg.content
  if (!url) return
  const sep = url.includes('?') ? '&' : '?'
  const dlUrl = url + sep + 'download=1'
  const a = document.createElement('a')
  a.href = dlUrl
  a.download = displayFileName(msg) || ''
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

// 撤回判定: 自己发的 + 2 分钟内 + 非已撤回
const canRecall = (msg) => {
  if (String(msg.fromUserId) !== String(userStore.userInfo?.id)) return false
  if (msg.status === 2 || msg.recalled) return false
  if (!msg.msgId) return false  // 客户端临时 id 的不让撤(后端没存)
  const ts = msg.timestamp ? dayjs(msg.timestamp).valueOf() : 0
  if (!ts) return false
  return Date.now() - ts < 120_000
}

const onRecall = async (msg) => {
  try {
    await ElMessageBox.confirm('确定撤回这条消息？', '撤回', { type: 'warning' })
  } catch (e) { return }
  wsService.sendRecall(msg.msgId)
  // 乐观更新本地; 后端广播回来时 markRecalled 是幂等的
  chatStore.markRecalled(msg.msgId)
}

// 右键: 自己 2 分钟内的消息上右键 → 直接弹撤回确认; 其他消息 / 别人消息忽略浏览器默认菜单
const onContextMenu = (event, msg) => {
  if (canRecall(msg)) {
    onRecall(msg)
  }
}

// 文件类型辅助: 根据扩展名给图标 + 颜色
const fileExt = (name) => {
  if (!name) return ''
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

const fileIconEmoji = (name) => {
  const ext = fileExt(name)
  if (['pdf'].includes(ext)) return '📕'
  if (['doc', 'docx'].includes(ext)) return '📘'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return '📗'
  if (['ppt', 'pptx'].includes(ext)) return '📙'
  if (['zip', 'rar', '7z', 'gz', 'tar'].includes(ext)) return '🗜️'
  if (['mp3', 'wav', 'flac', 'ogg', 'm4a'].includes(ext)) return '🎵'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return '🎬'
  if (['txt', 'md', 'log'].includes(ext)) return '📝'
  if (['js', 'ts', 'java', 'py', 'go', 'c', 'cpp', 'h', 'rs', 'php', 'rb', 'json', 'xml', 'yml', 'yaml', 'sql'].includes(ext)) return '💻'
  return '📄'
}

const fileIconColor = (name) => {
  const ext = fileExt(name)
  if (['pdf'].includes(ext)) return 'linear-gradient(135deg,#ff6b6b,#ee5a52)'
  if (['doc', 'docx'].includes(ext)) return 'linear-gradient(135deg,#4dabf7,#339af0)'
  if (['xls', 'xlsx', 'csv'].includes(ext)) return 'linear-gradient(135deg,#51cf66,#37b24d)'
  if (['ppt', 'pptx'].includes(ext)) return 'linear-gradient(135deg,#ffa94d,#ff922b)'
  if (['zip', 'rar', '7z', 'gz', 'tar'].includes(ext)) return 'linear-gradient(135deg,#cc5de8,#ae3ec9)'
  if (['mp3', 'wav', 'flac', 'ogg', 'm4a'].includes(ext)) return 'linear-gradient(135deg,#ff8787,#fa5252)'
  if (['mp4', 'mov', 'avi', 'mkv', 'webm'].includes(ext)) return 'linear-gradient(135deg,#748ffc,#5c7cfa)'
  if (['js', 'ts', 'java', 'py', 'go', 'c', 'cpp', 'h', 'rs', 'php', 'rb', 'json', 'xml', 'yml', 'yaml', 'sql'].includes(ext)) return 'linear-gradient(135deg,#22b8cf,#15aabf)'
  return 'linear-gradient(135deg,#868e96,#495057)'
}

const fileTypeLabel = (name) => {
  const ext = fileExt(name)
  return ext ? ext.toUpperCase() + ' 文件' : '文件'
}

// 头像颜色: 用 name 哈希到固定调色板
const AVATAR_COLORS = [
  '#5b8def', '#69c0ff', '#73d13d', '#ffc53d', '#ff7a45',
  '#ff85c0', '#b37feb', '#36cfc9', '#9254de', '#ffa940'
]
const avatarColor = (name) => {
  if (!name) return '#999'
  let h = 0
  for (let i = 0; i < name.length; i++) h = ((h << 5) - h + name.charCodeAt(i)) | 0
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length]
}

// 对话列表 lastMessage 预览: URL 形态的内容显示为「[图片]」/「[文件]」
const lastMessagePreview = (content) => {
  if (!content) return ''
  if (typeof content !== 'string') return String(content)
  if (content.startsWith('/api/v1/files/') || content.includes('/api/v1/files/')) return '[文件]'
  if (content.startsWith('http://') || content.startsWith('https://')) return '[链接]'
  return content.length > 30 ? content.slice(0, 30) + '…' : content
}
</script>

<style scoped>
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 18px;
  padding: 18px;
  box-sizing: border-box;
  background:
    radial-gradient(circle at top right, rgba(64, 158, 255, 0.08), transparent 18%),
    linear-gradient(180deg, rgba(247, 250, 255, 0.96) 0%, rgba(243, 246, 251, 0.96) 100%);
}

.chat-layout {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 18px;
}

.conversation-list {
  width: 300px;
  display: flex;
  flex-direction: column;
  border-radius: 24px;
  border: 1px solid #e8eef5;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.conversation-header {
  padding: 22px 22px 18px;
  border-bottom: 1px solid #edf1f7;
  background: linear-gradient(180deg, rgba(64, 158, 255, 0.07) 0%, rgba(255, 255, 255, 0.72) 100%);
}

.panel-kicker {
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #8a94a6;
}

.conversation-header h3 {
  margin: 8px 0 6px;
  font-size: 22px;
  color: #1f2d3d;
}

.conversation-header p {
  margin: 0;
  color: #7a8699;
  font-size: 13px;
}

.conversation-items {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 14px 14px;
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  border: 1px solid transparent;
  border-radius: 18px;
  margin-bottom: 10px;
  outline: none;
}

.conversation-item:hover,
.conversation-item:focus-visible {
  transform: translateY(-1px);
  background: #f9fbff;
  border-color: #dbeafe;
  box-shadow: 0 12px 24px rgba(64, 158, 255, 0.09);
}

.conversation-item.active {
  background: linear-gradient(135deg, rgba(64, 158, 255, 0.14) 0%, rgba(64, 158, 255, 0.08) 100%);
  border-color: rgba(64, 158, 255, 0.28);
  box-shadow: 0 12px 24px rgba(64, 158, 255, 0.12);
}

.avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.online-dot {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #52c41a;
  border: 2px solid white;
}

.group-flag {
  position: absolute;
  bottom: -2px;
  right: -2px;
  background: #ffa940;
  color: white;
  font-size: 10px;
  padding: 1px 4px;
  border-radius: 3px;
  border: 1px solid white;
  line-height: 1;
}

.conversation-info {
  flex: 1;
  margin-left: 12px;
  min-width: 0;
}

.conversation-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.conversation-name {
  font-size: 15px;
  color: #1f2d3d;
  font-weight: 600;
}

.conv-member-count {
  margin-left: 4px;
  color: #909399;
  font-size: 12px;
  font-weight: normal;
}

.conversation-time {
  font-size: 12px;
  color: #98a2b3;
}

.conversation-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.last-message {
  font-size: 13px;
  color: #7a8699;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.chat-window {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  border-radius: 28px;
  border: 1px solid #e8eef5;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.chat-content {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.chat-header {
  padding: 22px 24px 18px;
  border-bottom: 1px solid #edf1f7;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  background: linear-gradient(180deg, rgba(64, 158, 255, 0.06) 0%, rgba(255, 255, 255, 0.86) 100%);
}

.chat-title-block h3 {
  margin: 0;
  font-size: 18px;
  color: #1f2d3d;
}

.chat-title-block p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #7a8699;
}

.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

:deep(.header-action-btn.el-button) {
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(64, 158, 255, 0.1);
  color: #409eff;
  border: 1px solid rgba(64, 158, 255, 0.14);
}

:deep(.header-action-btn.el-button:hover) {
  background: rgba(64, 158, 255, 0.16);
}

.search-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.search-panel-head {
  padding: 18px;
  border-radius: 18px;
  border: 1px solid #e8eef5;
  background: linear-gradient(180deg, #ffffff 0%, #f7fbff 100%);
}

.search-panel-head h4 {
  margin: 8px 0 6px;
  font-size: 18px;
  color: #1f2d3d;
}

.search-panel-head p {
  margin: 0;
  color: #7a8699;
  line-height: 1.6;
  font-size: 13px;
}

.search-filter-row {
  display: flex;
  gap: 12px;
}

.search-filter-row :deep(.el-select) {
  width: 100%;
}

.search-action-row {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.search-summary {
  font-size: 13px;
  color: #606266;
}

.search-result-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 160px;
}

.search-result-item {
  padding: 12px;
  border: 1px solid #e8eef5;
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff 0%, #fbfcfe 100%);
  cursor: pointer;
  transition: all 0.2s ease;
  outline: none;
}

.search-result-item:hover,
.search-result-item:focus-visible {
  transform: translateY(-1px);
  border-color: #b3d8ff;
  background: #f5f9ff;
  box-shadow: 0 10px 24px rgba(64, 158, 255, 0.1);
}

.search-result-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.search-result-sender {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.search-result-time {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.search-result-content {
  margin-top: 8px;
  font-size: 14px;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}

.search-result-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #409eff;
}

.search-pagination {
  margin-top: auto;
  justify-content: flex-end;
}

.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background:
    radial-gradient(circle at top, rgba(64, 158, 255, 0.05), transparent 20%),
    linear-gradient(180deg, #f8fbff 0%, #f4f6fb 100%);
}

.loading-more-hint {
  text-align: center;
  color: #999;
  font-size: 12px;
  padding: 8px 0;
}

.message-item {
  display: flex;
  margin-bottom: 20px;
  align-items: flex-start;
}

.message-self {
  flex-direction: row-reverse;
}

.message-content {
  margin: 0 12px;
  max-width: min(62%, 720px);
}

.message-self .message-content {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.message-self .message-info {
  flex-direction: row-reverse;
}

.message-sender {
  font-size: 12px;
  color: #667085;
  font-weight: 600;
}

.message-time {
  font-size: 11px;
  color: #98a2b3;
}

.recall-btn {
  padding: 0 6px !important;
  margin-left: 8px !important;
  height: 20px !important;
  min-height: 0 !important;
  font-size: 12px !important;
  border: 1px solid #ffccc7;
  border-radius: 10px;
  background: #fff2f0;
  color: #ff4d4f !important;
}
.recall-btn:hover {
  background: #ffccc7 !important;
}

.message-recalled {
  padding: 4px 10px;
  font-size: 12px;
  color: #999;
  font-style: italic;
  background: rgba(0,0,0,0.04);
  border-radius: 6px;
  display: inline-block;
}

.message-bubble {
  padding: 12px 16px;
  background-color: white;
  border-radius: 18px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
  border: 1px solid #edf1f7;
  box-shadow: 0 10px 22px rgba(15, 23, 42, 0.06);
  position: relative;
}

.message-bubble.bubble-image,
.message-bubble.bubble-file {
  padding: 6px;
  background-color: white;
}

.message-self .message-bubble {
  background: linear-gradient(135deg, #5ba8ff 0%, #409eff 100%);
  color: #fff;
  border-color: transparent;
}

.message-anchor-active .message-bubble {
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.28), 0 14px 28px rgba(64, 158, 255, 0.15);
  background: #ecf5ff;
  color: #1f2d3d;
}

.message-self .message-bubble.bubble-file {
  background-color: white;
}

.msg-text {
  display: block;
  white-space: pre-wrap;
}

.msg-image {
  max-width: 260px;
  max-height: 260px;
  border-radius: 8px;
  cursor: pointer;
  display: block;
}

.msg-file {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px 10px 10px;
  min-width: 220px;
  max-width: 320px;
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.15s;
}

.msg-file:hover {
  background: #f5f9ff;
}

.msg-file-icon {
  width: 44px;
  height: 44px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: white;
  flex-shrink: 0;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.12);
}

.msg-file-info {
  flex: 1;
  min-width: 0;
}

.msg-file-name {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}

.msg-file-meta {
  font-size: 12px;
  color: #909399;
}

.message-input {
  padding: 18px 24px 24px;
  border-top: 1px solid #edf1f7;
  background: rgba(255, 255, 255, 0.96);
}

.input-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  gap: 10px;
  flex-wrap: wrap;
}

.chat-empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background:
    radial-gradient(circle at top, rgba(64, 158, 255, 0.05), transparent 20%),
    linear-gradient(180deg, #f8fbff 0%, #f4f6fb 100%);
}

:deep(.search-drawer .el-drawer) {
  background: linear-gradient(180deg, #fbfdff 0%, #f3f7fc 100%);
}

:deep(.search-drawer .el-drawer__header) {
  margin-bottom: 0;
  padding: 22px 24px 16px;
  border-bottom: 1px solid #edf1f7;
  color: #1f2d3d;
  font-weight: 600;
}

:deep(.search-drawer .el-drawer__body) {
  padding: 18px 20px 20px;
}

@media (max-width: 1200px) {
  .chat-container {
    padding: 14px;
    gap: 14px;
  }

  .conversation-list {
    width: 280px;
  }
}

@media (max-width: 900px) {
  .chat-container {
    height: auto;
    min-height: 100%;
  }

  .chat-layout {
    flex-direction: column;
  }

  .conversation-list {
    width: 100%;
    min-height: 240px;
  }

  .message-content {
    max-width: 80%;
  }
}

.file-link {
  color: #409eff;
  text-decoration: none;
  word-break: break-all;
}
.file-link:hover {
  text-decoration: underline;
}
</style>
