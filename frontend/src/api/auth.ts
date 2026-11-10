import { apiFetch } from './client'
import type { LoginRequest, MeResponse, SignupRequest, TokenResponse } from './types'

export const authApi = {
  login: (body: LoginRequest) =>
    apiFetch<TokenResponse>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(body) }),

  signup: (body: SignupRequest) =>
    apiFetch<TokenResponse>('/api/v1/auth/signup', { method: 'POST', body: JSON.stringify(body) }),

  /** 체험하기: 계정 없이 임시 체험 사용자를 만들고 토큰을 받는다 */
  demo: () => apiFetch<TokenResponse>('/api/v1/auth/demo', { method: 'POST' }),

  me: () => apiFetch<MeResponse>('/api/v1/auth/me'),
}
