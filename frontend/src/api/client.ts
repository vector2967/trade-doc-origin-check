import type { ErrorResponse } from './types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const TOKEN_KEY = 'origin-check.access-token'

/** API 명세: 토큰은 메모리나 sessionStorage 에 둔다. 로그아웃은 토큰을 버리면 된다. */
export const tokenStore = {
  get(): string | null {
    try {
      return sessionStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string) {
    try {
      sessionStorage.setItem(TOKEN_KEY, token)
    } catch {
      /* 저장 불가 환경이면 새로고침 때 다시 로그인 */
    }
  },
  clear() {
    try {
      sessionStorage.removeItem(TOKEN_KEY)
    } catch {
      /* noop */
    }
  },
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly path?: string

  constructor(status: number, body: Partial<ErrorResponse>) {
    super(body.message ?? `요청이 실패했습니다 (HTTP ${status})`)
    this.status = status
    this.code = body.code ?? 'UNKNOWN'
    this.path = body.path
  }

  /** 401 UNAUTHORIZED·TOKEN_EXPIRED → 로그인 화면으로 */
  get isAuthError() {
    return this.status === 401 && this.code !== 'INVALID_CREDENTIALS'
  }
}

/** 401 을 받으면 호출된다(AuthProvider 가 등록). */
let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: (() => void) | null) {
  onUnauthorized = fn
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await apiRaw(path, init)
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** 본문을 직접 다룰 때(PDF Blob 등) */
export async function apiRaw(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  const token = tokenStore.get()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers })
  if (!res.ok) {
    let body: Partial<ErrorResponse> = {}
    try {
      body = await res.json()
    } catch {
      /* 본문 없음 */
    }
    const error = new ApiError(res.status, body)
    if (error.isAuthError) {
      tokenStore.clear()
      onUnauthorized?.()
    }
    throw error
  }
  return res
}
