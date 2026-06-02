import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

function toWebSocketTarget(target) {
  if (!target) return 'ws://localhost:8081'
  if (target.startsWith('ws://') || target.startsWith('wss://')) return target
  if (target.startsWith('http://')) return `ws://${target.slice('http://'.length)}`
  if (target.startsWith('https://')) return `wss://${target.slice('https://'.length)}`
  return target
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  const apiTarget = env.VITE_DEV_PROXY_API_TARGET || 'http://localhost:8080'
  const wsTarget = toWebSocketTarget(env.VITE_DEV_PROXY_WS_TARGET || env.VITE_WS_URL || apiTarget)

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': resolve(__dirname, 'src')
      }
    },
    server: {
      port: 3000,
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true
        },
        '/ws': {
          target: wsTarget,
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
