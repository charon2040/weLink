const DEFAULT_WS_PATH = '/ws'

function normalizeWsUrl(rawUrl) {
  if (!rawUrl) return null

  let resolvedUrl
  try {
    if (/^wss?:\/\//i.test(rawUrl)) {
      resolvedUrl = new URL(rawUrl)
    } else {
      if (typeof window === 'undefined') {
        console.warn('Relative VITE_WS_URL requires window.location:', rawUrl)
        return null
      }
      resolvedUrl = new URL(rawUrl, window.location.origin)
    }
  } catch (error) {
    console.warn('Invalid WebSocket URL config:', rawUrl, error)
    return null
  }

  if (resolvedUrl.protocol === 'http:') resolvedUrl.protocol = 'ws:'
  if (resolvedUrl.protocol === 'https:') resolvedUrl.protocol = 'wss:'
  if (!/^wss?:$/i.test(resolvedUrl.protocol)) {
    console.warn('Unsupported WebSocket protocol:', resolvedUrl.protocol)
    return null
  }

  if (!resolvedUrl.pathname || resolvedUrl.pathname === '/') {
    resolvedUrl.pathname = DEFAULT_WS_PATH
  }

  return resolvedUrl.toString()
}

function resolveWebSocketUrl() {
  const configuredUrl = normalizeWsUrl(import.meta.env.VITE_WS_URL)
  if (configuredUrl) {
    return configuredUrl
  }

  if (typeof window === 'undefined') {
    throw new Error('VITE_WS_URL is required when window is unavailable')
  }

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${DEFAULT_WS_PATH}`
}

const MESSAGE_TYPES = {
  AUTH: 'auth',
  MESSAGE: 'message',
  ACK: 'ack',
  HEARTBEAT: 'heartbeat',
  SYSTEM: 'system'
}

function getDeviceId() {
  const ua = navigator.userAgent.toLowerCase()
  if (ua.includes('iphone') || ua.includes('ipad')) return 'ios'
  if (ua.includes('android')) return 'android'
  if (ua.includes('electron') || ua.includes('desktop')) return 'desktop'
  return 'web'
}

class WebSocketService {
  constructor() {
    this.ws = null
    this.isConnected = false
    this.reconnectTimer = null
    this.heartbeatTimer = null
    this.reconnectAttempts = 0
    this.maxReconnectAttempts = 5
    this.reconnectInterval = 3000
    this.heartbeatInterval = 30000
    this.messageHandlers = []
    this.token = null
    this.deviceId = getDeviceId()
    this.cursors = {}
  }

  connect(token, onSyncComplete) {
    // 已连接 / 正在连接 → 不重复创建; 否则旧 ws 仍在 CONNECTING 时会被丢弃但 server 端会
    // 看到孤儿连接, 引发"踢旧 session"循环
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      this.token = token
      return
    }

    this.token = token

    try {
      const wsUrl = resolveWebSocketUrl()
      this.ws = new WebSocket(wsUrl)

      this.ws.onopen = () => {
        console.log('WebSocket connected:', wsUrl, 'device:', this.deviceId)
        this.isConnected = true
        this.reconnectAttempts = 0

        this.sendAuth()
        this.startHeartbeat()
        this.notifyHandlers({ type: 'connected' })

        if (onSyncComplete) {
          onSyncComplete()
        }
      }

      this.ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          this.handleMessage(data)
        } catch (error) {
          console.error('Failed to parse message:', error)
        }
      }

      this.ws.onclose = (event) => {
        console.log('WebSocket disconnected. code=' + event.code + ' reason=' + (event.reason || '(empty)') + ' wasClean=' + event.wasClean)
        this.isConnected = false
        this.stopHeartbeat()
        this.notifyHandlers({ type: 'disconnected' })
        this.tryReconnect()
      }

      this.ws.onerror = (error) => {
        console.error('WebSocket error:', error)
        this.notifyHandlers({ type: 'error', error })
      }
    } catch (error) {
      console.error('Failed to create WebSocket:', error)
      this.tryReconnect()
    }
  }

  sendAuth() {
    if (this.ws && this.token) {
      this.send({
        type: MESSAGE_TYPES.AUTH,
        token: this.token,
        deviceId: this.deviceId
      })
    }
  }

  sendMessage(message) {
    if (this.ws && this.isConnected) {
      this.send({
        type: MESSAGE_TYPES.MESSAGE,
        ...message
      })
    } else {
      console.warn('WebSocket is not connected')
    }
  }

  sendAck(msgId) {
    if (this.ws && this.isConnected) {
      this.send({
        type: MESSAGE_TYPES.ACK,
        msgId: msgId
      })
    }
  }

  sendRecall(msgId) {
    if (this.ws && this.isConnected) {
      this.send({
        type: 'recall',
        msgId: msgId
      })
    }
  }

  sendHeartbeat() {
    if (this.ws && this.isConnected) {
      this.send({
        type: MESSAGE_TYPES.HEARTBEAT
      })
    }
  }

  send(data) {
    try {
      this.ws.send(JSON.stringify(data))
    } catch (error) {
      console.error('Failed to send message:', error)
    }
  }

  handleMessage(data) {
    if (data.type === MESSAGE_TYPES.MESSAGE) {
      this.updateCursor(data)
    }

    switch (data.type) {
      case MESSAGE_TYPES.MESSAGE:
        this.notifyHandlers(data)
        if (data.msgId) {
          this.sendAck(data.msgId)
        }
        break
      case MESSAGE_TYPES.SYSTEM:
        this.notifyHandlers(data)
        break
      case MESSAGE_TYPES.ACK:
        this.notifyHandlers(data)
        break
      default:
        this.notifyHandlers(data)
    }
  }

  updateCursor(data) {
    let convKey
    let seq
    if (data.toUserId && data.fromUserId) {
      const min = Math.min(data.fromUserId, data.toUserId)
      const max = Math.max(data.fromUserId, data.toUserId)
      convKey = `single:${min}:${max}`
      seq = data.conversationSeq
    } else if (data.groupId) {
      convKey = `group:${data.groupId}`
      seq = data.groupSeq
    }
    if (convKey && seq != null) {
      const current = this.cursors[convKey] || 0
      if (seq > current) {
        this.cursors[convKey] = seq
      }
    }
  }

  syncMessages(syncApi) {
    const cursors = { ...this.cursors }
    return syncApi(cursors).then(response => {
      const data = response.data || response
      if (data.cursors) {
        Object.assign(this.cursors, data.cursors)
      }
      return data.messages || []
    }).catch(err => {
      console.error('Failed to sync messages:', err)
      return []
    })
  }

  startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      this.sendHeartbeat()
    }, this.heartbeatInterval)
  }

  stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  tryReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.log('Max reconnect attempts reached')
      return
    }

    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
    }

    this.reconnectAttempts++
    this.reconnectTimer = setTimeout(() => {
      console.log(`Reconnecting... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`)
      if (this.token) {
        this.connect(this.token)
      }
    }, this.reconnectInterval)
  }

  onMessage(handler) {
    this.messageHandlers.push(handler)
  }

  offMessage(handler) {
    const index = this.messageHandlers.indexOf(handler)
    if (index > -1) {
      this.messageHandlers.splice(index, 1)
    }
  }

  notifyHandlers(data) {
    this.messageHandlers.forEach(handler => {
      try {
        handler(data)
      } catch (error) {
        console.error('Error in message handler:', error)
      }
    })
  }

  disconnect() {
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.isConnected = false
    this.token = null
  }

  ensureConnected(token) {
    if (!token) return
    // OPEN 或 CONNECTING 都视为已连接, 避免在 onopen 前再次 connect
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      this.token = token
      return
    }
    this.connect(token)
  }
}

export const wsService = new WebSocketService()
export default wsService
