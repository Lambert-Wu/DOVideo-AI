import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://localhost:9090'
  const minio = env.MINIO_ENDPOINT || 'http://localhost:9000'
  const apiProxy = Object.fromEntries(
    ['/user', '/media', '/analysis', '/admin', '/health'].map(path => [
      path,
      {
        target: backend,
        changeOrigin: true,
        // 通过隧道访问时，浏览器会带上外部 Origin（如 *.trycloudflare.com），
        // 代理转发后会被后端的 CORS 白名单拒绝。转发前去掉 Origin，让后端按同源处理。
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        }
      }
    ])
  )
  return {
    envDir: '..',
    plugins: [vue()],
    server: {
      allowedHosts: ['.trycloudflare.com', '.ngrok-free.dev'],
      proxy: {
        ...apiProxy,
        // 预签名播放/封面地址的对外前缀（后端 MINIO_PUBLIC_BASE=/minio）。
        // 去掉 /minio 前缀后转发给 MinIO，保证签名里的 path 与 host 仍然匹配，
        // 浏览器侧则是同源地址，避免 localhost 与 https/http 混合内容问题。
        '/minio': {
          target: minio,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/minio/, '')
        }
      }
    }
  }
})
