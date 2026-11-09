import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

// 개발 서버에서 /api 요청은 스프링 백엔드로 넘긴다 → 브라우저 입장에서는 같은 출처라 CORS 설정이 필요 없다
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          // 운영(https, Caddy)처럼 Host 로 사이트를 고르는 대상도 받도록 Host 를 대상 주소로 바꾼다
          changeOrigin: true,
          // 브라우저의 Origin 을 그대로 넘기면 백엔드 CORS 검사에 걸린다(포트가 다르면 403). 프록시는 같은 출처로 취급한다
          configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')),
        },
      },
    },
  }
})
