import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { defineConfig, type ProxyOptions } from 'vite'

const backendProxy: ProxyOptions = {
  target: process.env.VITE_BACKEND_TARGET ?? 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure(proxy) {
    proxy.on('proxyReq', (proxyReq) => {
      proxyReq.removeHeader('origin')
    })
  }
}

const websocketTarget = process.env.VITE_WS_TARGET ?? 'ws://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    fs: {
      allow: [fileURLToPath(new URL('..', import.meta.url))]
    },
    port: 5173,
    proxy: {
      '/api': backendProxy,
      '/notifications': backendProxy,
      '/dashboards': backendProxy,
      '/messages': backendProxy,
      '/logistics': backendProxy,
      '/clinics': backendProxy,
      '/patients': backendProxy,
      '/orders': backendProxy,
      '/files': backendProxy,
      '/form-configs': backendProxy,
      '/products': backendProxy,
      '/catalog': backendProxy,
      '/admin': backendProxy,
      '/order-case-groups': backendProxy,
      '/orthodontic-plan-versions': backendProxy,
      '/workflow-chains': backendProxy,
      '/tasks': backendProxy,
      '/process-instance': backendProxy,
      '/check-records': backendProxy,
      '/reworks': backendProxy,
      '/final-inspection-reports': backendProxy,
      '/work-logs': backendProxy,
      '/performance': backendProxy,
      '/staff': backendProxy,
      '/design-tasks': backendProxy,
      '/quality-records': backendProxy,
      '/doctor': backendProxy,
      '/production': backendProxy,
      '/ai': backendProxy,
      '/rbac': backendProxy,
      '/ordering-rules': backendProxy,
      '/exports': backendProxy,
      '/accounts': backendProxy,
      '/ws': {
        target: websocketTarget,
        ws: true,
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReqWs', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        }
      }
    }
  }
})
