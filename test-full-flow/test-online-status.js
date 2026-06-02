import WebSocket from 'ws'
import axios from 'axios'

const API_BASE = 'http://localhost:8080/api/v1'
const WS_URL = 'ws://localhost:8081/ws'

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function register(username, password) {
  const res = await axios.post(`${API_BASE}/auth/register`, { username, password })
  return res.data
}

async function login(username, password) {
  const res = await axios.post(`${API_BASE}/auth/login`, { username, password })
  return res.data
}

async function applyFriend(token, friendId) {
  const res = await axios.post(`${API_BASE}/friend/apply/${friendId}`, null, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

async function acceptFriend(token, friendId) {
  const res = await axios.post(`${API_BASE}/friend/accept/${friendId}`, null, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

async function getFriendList(token) {
  const res = await axios.get(`${API_BASE}/friend/list`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

function createWsClient(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL)
    const messages = []

    ws.on('open', () => {
      console.log('  WebSocket connected')
      ws.send(JSON.stringify({ type: 'auth', token }))
    })

    ws.on('message', (data) => {
      const text = data.toString().trim()
      if (!text) return
      try {
        const msg = JSON.parse(text)
        messages.push(msg)
        console.log('  Received:', JSON.stringify(msg).substring(0, 100))
      } catch (e) {
        console.warn('Failed to parse WebSocket message:', text)
      }
    })

    ws.on('error', (err) => {
      console.error('  WebSocket error:', err.message)
      reject(err)
    })

    ws.on('close', () => {
      console.log('  WebSocket closed')
    })

    // 等待认证完成
    const checkAuth = setInterval(() => {
      const authMsg = messages.find(m => m.type === 'auth' && m.status === 'success')
      if (authMsg) {
        clearInterval(checkAuth)
        resolve({
          ws,
          messages,
          send(msg) { ws.send(JSON.stringify(msg)) },
          getMessages() { return [...messages] },
          close() { ws.close() }
        })
      }
    }, 100)

    setTimeout(() => {
      clearInterval(checkAuth)
      reject(new Error('WebSocket auth timeout'))
    }, 5000)
  })
}

async function main() {
  console.log('\n========== 在线状态测试 ==========')
  
  const timestamp = Date.now().toString().slice(-8)
  const userAUsername = `usera${timestamp}`
  const userBUsername = `userb${timestamp}`
  
  try {
    // 1. 注册用户
    console.log('\n[1] 注册用户')
    await register(userAUsername, 'password123')
    console.log(`  ✅ 用户A注册成功: ${userAUsername}`)
    await register(userBUsername, 'password123')
    console.log(`  ✅ 用户B注册成功: ${userBUsername}`)
    
    // 2. 登录用户
    console.log('\n[2] 登录用户')
    const loginA = await login(userAUsername, 'password123')
    const tokenA = loginA.data.accessToken
    const userIdA = loginA.data.userInfo.id
    console.log(`  ✅ 用户A登录成功, userId: ${userIdA}`)
    
    const loginB = await login(userBUsername, 'password123')
    const tokenB = loginB.data.accessToken
    const userIdB = loginB.data.userInfo.id
    console.log(`  ✅ 用户B登录成功, userId: ${userIdB}`)
    
    // 3. 添加好友
    console.log('\n[3] 添加好友')
    await applyFriend(tokenA, userIdB)
    console.log(`  ✅ 用户A向用户B发送好友申请`)
    await acceptFriend(tokenB, userIdA)
    console.log(`  ✅ 用户B接受好友申请`)
    
    // 4. 用户A建立WebSocket连接
    console.log('\n[4] 用户A建立WebSocket连接')
    const wsA = await createWsClient(tokenA)
    console.log('  ✅ 用户A WebSocket认证成功')
    
    await delay(1000)
    
    // 5. 用户B建立WebSocket连接
    console.log('\n[5] 用户B建立WebSocket连接')
    const wsB = await createWsClient(tokenB)
    console.log('  ✅ 用户B WebSocket认证成功')
    
    await delay(1000)
    
    // 6. 检查好友列表中的在线状态
    console.log('\n[6] 检查好友列表中的在线状态')
    const friendListA = await getFriendList(tokenA)
    console.log(`  用户A的好友列表: ${JSON.stringify(friendListA.data)}`)
    
    const friendListB = await getFriendList(tokenB)
    console.log(`  用户B的好友列表: ${JSON.stringify(friendListB.data)}`)
    
    // 检查在线状态
    const friendB = friendListA.data.find(f => f.id === userIdB)
    const friendA = friendListB.data.find(f => f.id === userIdA)
    
    if (friendB && friendB.online === true) {
      console.log('  ✅ 用户A看到用户B在线')
    } else {
      console.log(`  ❌ 用户A看到用户B离线 (online: ${friendB?.online})`)
    }
    
    if (friendA && friendA.online === true) {
      console.log('  ✅ 用户B看到用户A在线')
    } else {
      console.log(`  ❌ 用户B看到用户A离线 (online: ${friendA?.online})`)
    }
    
    // 7. 测试消息发送
    console.log('\n[7] 测试消息发送')
    wsA.send({
      type: 'message',
      toUserId: userIdB,
      msgType: 1,
      content: 'Hello from User A!'
    })
    
    await delay(2000)
    
    // 检查用户B是否收到消息
    const messagesB = wsB.getMessages()
    const receivedMessage = messagesB.find(m => m.type === 'message' && m.content === 'Hello from User A!')
    
    if (receivedMessage) {
      console.log('  ✅ 用户B收到消息')
    } else {
      console.log('  ❌ 用户B未收到消息')
      console.log('  用户B收到的消息:', messagesB.map(m => JSON.stringify(m).substring(0, 100)))
    }
    
    // 8. 清理
    console.log('\n[8] 清理连接')
    wsA.close()
    wsB.close()
    await delay(1000)
    console.log('  ✅ 连接已关闭')
    
    console.log('\n========== 测试完成 ==========')
    
  } catch (error) {
    console.error('\n❌ 测试失败:', error.message)
    if (error.response) {
      console.error('  Response:', error.response.data)
    }
  }
}

main()
