import axios from 'axios'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import router from '@/router'

// 后端把所有业务/异常都包成 HTTP 200 + { code, message, data } 的 Result 包装,
// 因此 401 路径必须看 res.code 而不是 HTTP status. 后端在 GlobalExceptionHandler 转译, 这是项目约定.
const UNAUTHORIZED_CODES = new Set([401, 1004, 1005])  // UNAUTHORIZED / TOKEN_EXPIRED / TOKEN_INVALID

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

api.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 单飞 refresh: 同一时刻只发 1 次 /auth/refresh, 其他 401 请求挂到同一个 Promise 上等结果
let refreshingPromise = null

async function tryRefreshAndRetry(originalConfig) {
  const userStore = useUserStore()
  if (!userStore.refreshToken) return null

  if (!refreshingPromise) {
    refreshingPromise = axios
      .post('/api/v1/auth/refresh', { refreshToken: userStore.refreshToken }, { timeout: 10000 })
      .then(res => {
        const data = res.data?.data
        if (!data?.accessToken) throw new Error('refresh response missing accessToken')
        userStore.setToken(data.accessToken)
        userStore.setRefreshToken(data.refreshToken)
        return data.accessToken
      })
      .catch(err => {
        userStore.logout()
        router.push('/login')
        throw err
      })
      .finally(() => {
        refreshingPromise = null
      })
  }

  try {
    const newToken = await refreshingPromise
    // 重放原请求, 但用新 token 重新签
    originalConfig.headers = { ...(originalConfig.headers || {}), Authorization: `Bearer ${newToken}` }
    // 防止重放后的请求又触发 refresh 死循环
    originalConfig._retriedAfterRefresh = true
    return api.request(originalConfig)
  } catch (e) {
    return null
  }
}

api.interceptors.response.use(
  async (response) => {
    const res = response.data
    const cfg = response.config || {}

    // 后端 Result.code 表达业务错误码, 401/1004/1005 走 token 刷新或踢出登录
    if (UNAUTHORIZED_CODES.has(res.code)) {
      // 已经重放过仍 401 → 真的 logout
      if (cfg._retriedAfterRefresh || cfg.url?.includes('/auth/refresh')) {
        const userStore = useUserStore()
        userStore.logout()
        router.push('/login')
        ElMessage.error(res.message || '登录已过期，请重新登录')
        return Promise.reject(new Error(res.message || '未授权'))
      }
      const retried = await tryRefreshAndRetry(cfg)
      if (retried) return retried
      // refresh 失败已在 tryRefreshAndRetry 内部 logout + redirect
      return Promise.reject(new Error(res.message || '未授权'))
    }

    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    // 网络层错误或后端真的返回了 HTTP 401 (理论上目前不会, 但兜底)
    if (error.response?.status === 401) {
      const userStore = useUserStore()
      userStore.logout()
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default api
