import { defineStore } from 'pinia'
import { ref } from 'vue'
import { messageApi, fileApi } from '@/api'
import { useUserStore } from '@/stores/user'
import dayjs from 'dayjs'

export const useChatStore = defineStore('chat', () => {
  const conversations = ref([])
  const currentConversation = ref(null)
  const messages = ref([])
  // 历史滚动加载状态 (key=conv.id)
  const historyState = ref({})
  // 文件元数据缓存 (fileId -> {filename, size, mimeType}), 避免重复请求
  const fileMetaCache = ref({})
  const userStore = useUserStore()

  function getHistoryState(conv) {
    if (!conv) return null
    let st = historyState.value[conv.id]
    if (!st) {
      st = { page: 1, hasMore: true, loading: false }
      historyState.value[conv.id] = st
    }
    return st
  }

  function resetHistoryState(conv) {
    if (!conv) return
    historyState.value[conv.id] = { page: 1, hasMore: true, loading: false }
  }

  function isCurrentConvMsg(msg) {
    const cur = currentConversation.value
    if (!cur) return false
    if (cur.type === 'private') {
      return String(msg.fromUserId) === String(cur.userId) || String(msg.toUserId) === String(cur.userId)
    }
    if (cur.type === 'group') {
      return String(msg.groupId) === String(cur.groupId)
    }
    return false
  }

  function addMessage(message) {
    const msg = {
      ...message,
      timestamp: message.createdAt || message.timestamp || dayjs().format('YYYY-MM-DD HH:mm:ss')
    }

    // 去重: 优先按 msgId, 否则按 content+timestamp 模糊去重
    const exists = messages.value.some(m =>
      (m.msgId && msg.msgId && m.msgId === msg.msgId) ||
      (!m.msgId && !msg.msgId && m.content === msg.content && m.timestamp === msg.timestamp)
    )
    if (exists) return

    const conv = conversations.value.find(c =>
      (c.type === 'private' && (String(c.userId) === String(msg.fromUserId) || String(c.userId) === String(msg.toUserId))) ||
      (c.type === 'group' && String(c.groupId) === String(msg.groupId))
    )

    if (conv) {
      conv.lastMessage = msg.content
      conv.lastTime = msg.timestamp

      const isSelf = userStore.userInfo?.id != null && String(msg.fromUserId) === String(userStore.userInfo.id)
      const isViewing = isCurrentConvMsg(msg)
      if (!isSelf && !isViewing) {
        conv.unread = (conv.unread || 0) + 1
      }
    }

    if (isCurrentConvMsg(msg)) {
      messages.value.push(msg)
    }
  }

  async function setCurrentConversation(conversation) {
    currentConversation.value = conversation

    const conv = conversations.value.find(c =>
      (c.type === 'private' && c.userId === conversation.userId) ||
      (c.type === 'group' && c.groupId === conversation.groupId)
    )

    if (conv) {
      conv.unread = 0
      try {
        await messageApi.markConversationAsRead({
          conversationType: conv.type === 'private' ? 1 : 2,
          targetId: conv.type === 'private' ? conv.userId : conv.groupId
        })
      } catch (e) {
        console.error('Failed to mark conversation as read', e)
      }
    }
  }

  async function initConversations(friends, groups) {
    conversations.value = []

    if (friends) {
      friends.forEach(friend => {
        conversations.value.push({
          id: `private_${friend.id}`,
          type: 'private',
          userId: friend.id,
          name: friend.nickname || friend.username,
          avatar: friend.avatar,
          lastMessage: '',
          lastTime: '',
          unread: 0,
          online: friend.online
        })
      })
    }

    if (groups) {
      groups.forEach(group => {
        conversations.value.push({
          id: `group_${group.id}`,
          type: 'group',
          groupId: group.id,
          name: group.groupName,
          avatar: group.avatar,
          memberCount: group.memberCount || 0,
          ownerId: group.ownerId,
          lastMessage: '',
          lastTime: '',
          unread: 0
        })
      })
    }
  }

  // 将历史结果与本地已有消息合并 (按 msgId 去重 + timestamp 升序), 避免 WS 实时消息被历史覆盖.
  function mergeHistory(records) {
    const incoming = (records || []).slice().reverse()
    const seen = new Set()
    for (const m of messages.value) {
      if (m.msgId) seen.add(m.msgId)
    }
    const merged = [...messages.value]
    for (const m of incoming) {
      if (!m.msgId || !seen.has(m.msgId)) {
        merged.push(m)
        if (m.msgId) seen.add(m.msgId)
      }
    }
    merged.sort((a, b) => {
      const ta = a.timestamp || ''
      const tb = b.timestamp || ''
      return ta < tb ? -1 : ta > tb ? 1 : 0
    })
    messages.value = merged
  }

  function mergeMessages(records) {
    mergeHistory(records)
  }

  async function loadPrivateHistory(userId, page = 1, pageSize = 50) {
    const res = await messageApi.getPrivateHistory({
      userId: userStore.userInfo.id,
      targetId: userId,
      pageNum: page,
      pageSize
    })
    mergeHistory(res.data?.records)
    return res.data
  }

  async function loadGroupHistory(groupId, page = 1, pageSize = 50) {
    const res = await messageApi.getGroupHistory({
      groupId,
      pageNum: page,
      pageSize
    })
    mergeHistory(res.data?.records)
    return res.data
  }

  async function searchMessages(params) {
    const res = await messageApi.searchMessages(params)
    return res.data
  }

  async function getMessageContext(params) {
    const res = await messageApi.getMessageContext(params)
    return res.data
  }

  /**
   * 滑动加载更多: 拉下一页历史 prepend 到 messages 头部. 返回新加载的条数(0 = 无更多).
   */
  async function loadMoreHistory() {
    const conv = currentConversation.value
    if (!conv) return 0
    const st = getHistoryState(conv)
    if (st.loading || !st.hasMore) return 0
    st.loading = true
    try {
      const pageSize = 50
      const nextPage = st.page + 1
      let res
      if (conv.type === 'private') {
        res = await messageApi.getPrivateHistory({
          userId: userStore.userInfo.id,
          targetId: conv.userId,
          pageNum: nextPage,
          pageSize
        })
      } else {
        res = await messageApi.getGroupHistory({
          groupId: conv.groupId,
          pageNum: nextPage,
          pageSize
        })
      }
      const records = res?.data?.records || []
      if (records.length === 0) {
        st.hasMore = false
        return 0
      }
      // 后端返回 records 按 created_at desc, prepend 要 reverse 后倒序排
      const incoming = records.slice().reverse()
      const seen = new Set()
      for (const m of messages.value) if (m.msgId) seen.add(m.msgId)
      const fresh = incoming.filter(m => !m.msgId || !seen.has(m.msgId))
      if (fresh.length === 0) {
        st.hasMore = false
        return 0
      }
      messages.value = [...fresh, ...messages.value]
      st.page = nextPage
      // 满页时仍可能有更多; 不满页则到底
      if (records.length < pageSize) st.hasMore = false
      return fresh.length
    } catch (e) {
      console.error('loadMoreHistory failed', e)
      return 0
    } finally {
      st.loading = false
    }
  }

  async function loadOfflineMessages() {
    const res = await messageApi.getOfflineMessages()
    const offlineMessages = res.data || []
    offlineMessages.forEach(msg => {
      const conv = conversations.value.find(c =>
        (c.type === 'private' && c.userId === msg.fromUserId) ||
        (c.type === 'group' && c.groupId === msg.groupId)
      )

      if (conv) {
        conv.lastMessage = msg.content
        conv.lastTime = msg.createdAt || msg.timestamp
      }
    })
    return offlineMessages
  }

  async function applyConversationSummaries() {
    try {
      const res = await messageApi.getConversationSummaries()
      const summaries = res.data || []
      summaries.forEach(s => {
        let conv
        if (s.conversationType === 1 || s.conversationType === 'PRIVATE') {
          conv = conversations.value.find(c => c.type === 'private' && c.userId === s.targetId)
        } else {
          conv = conversations.value.find(c => c.type === 'group' && c.groupId === s.targetId)
        }
        if (conv) {
          conv.lastMessage = s.lastMessage || conv.lastMessage
          conv.lastTime = s.lastTime || conv.lastTime
          conv.unread = s.unreadCount || 0
        }
      })
    } catch (e) {
      console.error('Failed to load conversation summaries', e)
    }
  }

  function updateConversationOnlineStatus(userId, online) {
    const conv = conversations.value.find(c => c.type === 'private' && c.userId === userId)
    if (conv) {
      conv.online = online
    }
  }

  function clearMessages() {
    messages.value = []
  }

  /**
   * 标记一条消息为已撤回. 来自:
   *   1) 后端推送 {type:"recall", msgId}
   *   2) 自己发起 sendRecall 后等服务端回执
   * 找到 messages 数组里对应 msgId 的消息, 改 status=2 + 用 "[消息已撤回]" 占位.
   */
  function markRecalled(msgId) {
    if (!msgId) return
    const idx = messages.value.findIndex(m => m.msgId === msgId)
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        status: 2,
        recalled: true,
        content: '[消息已撤回]'
      }
    }
    // 同时更新对应会话的 lastMessage 显示
    const m = idx >= 0 ? messages.value[idx] : null
    if (m) {
      const conv = conversations.value.find(c =>
        (c.type === 'private' && (c.userId === m.fromUserId || c.userId === m.toUserId)) ||
        (c.type === 'group' && c.groupId === m.groupId)
      )
      if (conv && conv.lastMessage && !conv.lastMessage.startsWith('[')) {
        conv.lastMessage = '[消息已撤回]'
      }
    }
  }

  /**
   * 按需加载文件 meta (filename/size/mimeType), 缓存防止重复请求.
   * 用于历史拉回的文件消息 — message 表里只存 content URL, 没存原始 filename.
   */
  async function ensureFileMeta(fileId) {
    if (!fileId) return null
    if (fileMetaCache.value[fileId]) return fileMetaCache.value[fileId]
    // 占位防并发
    fileMetaCache.value[fileId] = { _loading: true }
    try {
      const res = await fileApi.getMeta(fileId)
      const meta = res.data || {}
      fileMetaCache.value[fileId] = {
        filename: meta.filename || '',
        size: meta.size || 0,
        mimeType: meta.mimeType || ''
      }
      return fileMetaCache.value[fileId]
    } catch (e) {
      delete fileMetaCache.value[fileId]
      return null
    }
  }

  return {
    conversations,
    currentConversation,
    messages,
    addMessage,
    setCurrentConversation,
    initConversations,
    loadPrivateHistory,
    loadGroupHistory,
    mergeMessages,
    searchMessages,
    getMessageContext,
    loadMoreHistory,
    historyState,
    resetHistoryState,
    loadOfflineMessages,
    applyConversationSummaries,
    updateConversationOnlineStatus,
    clearMessages,
    markRecalled,
    ensureFileMeta,
    fileMetaCache
  }
})
