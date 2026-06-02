import WebSocket from 'ws'
import axios from 'axios'

const API_BASE = 'http://localhost:8080/api/v1'
const WS_URL = 'ws://localhost:8081/ws'

// ==================== 工具函数 ====================

function log(section, msg) {
  console.log(`\n${'='.repeat(60)}`)
  console.log(`  ${section}`)
  console.log(`${'='.repeat(60)}`)
  console.log(msg)
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// ==================== HTTP API 封装 ====================

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

async function createGroup(token, groupName, memberIds) {
  const res = await axios.post(`${API_BASE}/group`, { groupName, memberIds }, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

async function getGroupList(token) {
  const res = await axios.get(`${API_BASE}/group/list`, {
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

async function getPrivateHistory(token, userId, targetId) {
  const res = await axios.get(`${API_BASE}/message/history/private`, {
    params: { userId, targetId, pageNum: 1, pageSize: 50 },
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

async function getGroupHistory(token, groupId) {
  const res = await axios.get(`${API_BASE}/message/history/group`, {
    params: { groupId, pageNum: 1, pageSize: 50 },
    headers: { Authorization: `Bearer ${token}` }
  })
  return res.data
}

// ==================== WebSocket 封装 ====================

function createWsClient(token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_URL)
    const messages = []

    ws.on('open', () => {
      ws.send(JSON.stringify({ type: 'auth', token }))
    })

    ws.on('message', (data) => {
      const text = data.toString().trim()
      if (!text) return
      try {
        const msg = JSON.parse(text)
        messages.push(msg)
      } catch (e) {
        console.warn('Failed to parse WebSocket message:', text)
      }
    })

    ws.on('error', (err) => reject(err))

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
          getLastMessage() { return messages[messages.length - 1] },
          clearMessages() { messages.length = 0 },
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

// ==================== 主测试流程 ====================

async function main() {
  let passed = 0
  let failed = 0

  function check(name, condition) {
    if (condition) {
      console.log(`  ✅ ${name}`)
      passed++
    } else {
      console.log(`  ❌ ${name}`)
      failed++
    }
  }

  try {
    // 使用时间戳确保每次测试都是新用户（用户名限制4-20字符）
    const timestamp = Date.now().toString().slice(-8)
    const aliceUsername = `alice${timestamp}`
    const bobUsername = `bob${timestamp}`

    // ===== 步骤 1: 注册 =====
    log('步骤 1: 注册用户', `注册 ${aliceUsername} 和 ${bobUsername}`)
    const regAlice = await register(aliceUsername, 'pass123456')
    console.log(`  Alice 注册响应: code=${regAlice.code}, message=${regAlice.message}`)
    check('Alice 注册成功', regAlice.code === 200)

    const regBob = await register(bobUsername, 'pass123456')
    console.log(`  Bob 注册响应: code=${regBob.code}, message=${regBob.message}`)
    check('Bob 注册成功', regBob.code === 200)

    // ===== 步骤 2: 登录 =====
    log('步骤 2: 用户登录', '获取 Token 和用户 ID')
    const loginAlice = await login(aliceUsername, 'pass123456')
    check('Alice 登录成功', loginAlice.code === 200)
    const aliceToken = loginAlice.data.accessToken
    const aliceId = loginAlice.data.userInfo.id
    console.log(`  Alice ID: ${aliceId}`)

    const loginBob = await login(bobUsername, 'pass123456')
    check('Bob 登录成功', loginBob.code === 200)
    const bobToken = loginBob.data.accessToken
    const bobId = loginBob.data.userInfo.id
    console.log(`  Bob ID: ${bobId}`)

    // ===== 步骤 3: 申请好友 =====
    log('步骤 3: 申请好友', 'Alice 向 Bob 发送好友申请')
    const applyRes = await applyFriend(aliceToken, bobId)
    console.log(`  好友申请响应: code=${applyRes.code}, message=${applyRes.message}`)
    check('好友申请发送成功', applyRes.code === 200)

    // ===== 步骤 4: 接受好友申请 =====
    log('步骤 4: 接受好友申请', 'Bob 接受 Alice 的好友申请')
    const acceptRes = await acceptFriend(bobToken, aliceId)
    console.log(`  好友接受响应: code=${acceptRes.code}, message=${acceptRes.message}`)
    check('好友申请接受成功', acceptRes.code === 200)

    // 验证好友列表
    const aliceFriends = await getFriendList(aliceToken)
    check('Alice 好友列表包含 Bob', aliceFriends.data.some(f => f.id === bobId))

    const bobFriends = await getFriendList(bobToken)
    check('Bob 好友列表包含 Alice', bobFriends.data.some(f => f.id === aliceId))

    // ===== 步骤 5: WebSocket 连接 & 发送私聊消息 =====
    log('步骤 5: 私聊消息', 'Alice 和 Bob 连接 WebSocket，Alice 发送消息给 Bob')

    // Bob 先连接（接收方先上线）
    const bobWs = await createWsClient(bobToken)
    check('Bob WebSocket 连接成功', bobWs.getLastMessage()?.status === 'success')
    bobWs.clearMessages()

    await delay(500)

    // Alice 连接并发送消息
    const aliceWs = await createWsClient(aliceToken)
    check('Alice WebSocket 连接成功', aliceWs.getLastMessage()?.status === 'success')
    aliceWs.clearMessages()

    await delay(500)

    // Alice 发送私聊消息给 Bob
    aliceWs.send({
      type: 'message',
      toUserId: bobId,
      msgType: 1,
      content: '你好 Bob，这是一条测试消息！'
    })

    await delay(1000)

    // Bob 应该收到消息
    const bobReceived = bobWs.getMessages().find(m => m.type === 'message' && m.fromUserId === aliceId)
    check('Bob 收到 Alice 的私聊消息', !!bobReceived)
    if (bobReceived) {
      check('消息内容正确', bobReceived.content === '你好 Bob，这是一条测试消息！')
      console.log(`  收到消息: "${bobReceived.content}"`)
    }

    // Alice 应该收到发送确认
    const aliceAck = aliceWs.getMessages().find(m => m.type === 'message' && m.status === 'success')
    check('Alice 收到发送确认', !!aliceAck)

    // ===== 步骤 6: 检查私聊历史记录 =====
    log('步骤 6: 检查私聊历史记录', '从数据库查询私聊消息')
    const history = await getPrivateHistory(aliceToken, aliceId, bobId)
    console.log(`  历史记录响应: code=${history.code}, data=${JSON.stringify(history.data)}`)
    check('私聊历史记录返回成功', history.code === 200)
    check('私聊历史记录包含消息', history.data && history.data.records && history.data.records.length > 0)
    if (history.data && history.data.records && history.data.records.length > 0) {
      const lastMsg = history.data.records[history.data.records.length - 1]
      check('最后一条消息内容正确', lastMsg.content === '你好 Bob，这是一条测试消息！')
      console.log(`  历史记录消息: "${lastMsg.content}"`)
    }

    // ===== 步骤 7: 创建群聊 =====
    log('步骤 7: 创建群聊', 'Alice 创建群组并邀请 Bob')
    const groupRes = await createGroup(aliceToken, '测试群聊', [bobId])
    check('群组创建成功', groupRes.code === 200)
    const groupId = groupRes.data.id
    console.log(`  群组 ID: ${groupId}`)
    console.log(`  群组名称: ${groupRes.data.groupName}`)

    // 验证群组列表
    const aliceGroups = await getGroupList(aliceToken)
    check('Alice 群组列表包含新群', aliceGroups.data.some(g => g.id === groupId))

    const bobGroups = await getGroupList(bobToken)
    check('Bob 群组列表包含新群', bobGroups.data.some(g => g.id === groupId))

    // ===== 步骤 8: 群发消息 =====
    log('步骤 8: 群发消息', 'Alice 在群聊中发送消息，Bob 接收')

    bobWs.clearMessages()

    // Alice 发送群消息
    aliceWs.send({
      type: 'message',
      groupId: groupId,
      msgType: 1,
      content: '大家好，这是群聊测试消息！'
    })

    await delay(1000)

    // Bob 应该收到群消息
    const bobGroupMsg = bobWs.getMessages().find(m => m.type === 'message' && m.groupId === groupId)
    check('Bob 收到群聊消息', !!bobGroupMsg)
    if (bobGroupMsg) {
      check('群消息内容正确', bobGroupMsg.content === '大家好，这是群聊测试消息！')
      console.log(`  收到群消息: "${bobGroupMsg.content}"`)
    }

    // ===== 步骤 9: 检查群聊历史记录 =====
    log('步骤 9: 检查群聊历史记录', '从数据库查询群聊消息')
    const groupHistory = await getGroupHistory(aliceToken, groupId)
    console.log(`  群聊历史记录响应: code=${groupHistory.code}, data=${JSON.stringify(groupHistory.data)}`)
    check('群聊历史记录返回成功', groupHistory.code === 200)
    check('群聊历史记录包含消息', groupHistory.data && groupHistory.data.records && groupHistory.data.records.length > 0)
    if (groupHistory.data && groupHistory.data.records && groupHistory.data.records.length > 0) {
      const lastGroupMsg = groupHistory.data.records[groupHistory.data.records.length - 1]
      check('群聊最后一条消息内容正确', lastGroupMsg.content === '大家好，这是群聊测试消息！')
      console.log(`  群聊历史记录: "${lastGroupMsg.content}"`)
    }

    // ===== 清理 =====
    aliceWs.close()
    bobWs.close()
    await delay(500)

    // ===== 总结 =====
    log('测试总结', `通过: ${passed} | 失败: ${failed} | 总计: ${passed + failed}`)
    if (failed === 0) {
      console.log('\n  🎉 所有测试通过！')
    } else {
      console.log(`\n  ⚠️  有 ${failed} 个测试失败`)
    }

  } catch (error) {
    console.error('\n❌ 测试过程中发生异常:', error.message)
    console.error(error.stack)
    process.exit(1)
  }
}

main()
