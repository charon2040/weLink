import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/api'
import { wsService } from '@/utils/websocket'

// 后端 Long ID 现在序列化为 String, 但 localStorage 里可能有旧的 Number ID, 统一规范成 String
function normalizeUserInfo(info) {
  if (!info) return null
  if (info.id != null) info.id = String(info.id)
  return info
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  let userInfoInitial = null
  try {
    userInfoInitial = normalizeUserInfo(JSON.parse(localStorage.getItem('userInfo') || 'null'))
    // 立即写回 localStorage 让新格式持久化
    if (userInfoInitial) localStorage.setItem('userInfo', JSON.stringify(userInfoInitial))
  } catch (e) {
    localStorage.removeItem('userInfo')
  }
  const userInfo = ref(userInfoInitial)

  function setToken(newToken) {
    token.value = newToken
    if (newToken) localStorage.setItem('token', newToken)
    else localStorage.removeItem('token')
  }

  function setRefreshToken(newRefresh) {
    refreshToken.value = newRefresh || ''
    if (newRefresh) localStorage.setItem('refreshToken', newRefresh)
    else localStorage.removeItem('refreshToken')
  }

  function setUserInfo(info) {
    const normalized = normalizeUserInfo(info)
    userInfo.value = normalized
    if (normalized) localStorage.setItem('userInfo', JSON.stringify(normalized))
    else localStorage.removeItem('userInfo')
  }

  async function login(loginForm) {
    const res = await authApi.login(loginForm)
    setToken(res.data.accessToken)
    setRefreshToken(res.data.refreshToken)
    setUserInfo(res.data.userInfo)
    wsService.connect(res.data.accessToken)
    return res.data
  }

  async function register(registerForm) {
    return await authApi.register(registerForm)
  }

  async function fetchUserInfo(userId) {
    const res = await authApi.getUserInfo(userId)
    return res.data
  }

  function logout() {
    wsService.disconnect()
    setToken('')
    setRefreshToken('')
    setUserInfo(null)
  }

  return {
    token,
    refreshToken,
    userInfo,
    setToken,
    setRefreshToken,
    setUserInfo,
    login,
    register,
    fetchUserInfo,
    logout
  }
})
