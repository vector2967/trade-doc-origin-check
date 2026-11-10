import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from '../api/auth'
import { setUnauthorizedHandler, tokenStore } from '../api/client'
import type { LoginRequest, MeResponse, SignupRequest } from '../api/types'
import { AuthContext } from './context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.get() != null)

  const logout = useCallback(() => {
    tokenStore.clear()
    setUser(null)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null))
    return () => setUnauthorizedHandler(null)
  }, [])

  useEffect(() => {
    if (!tokenStore.get()) return
    authApi
      .me()
      .then(setUser)
      .catch(() => logout())
      .finally(() => setLoading(false))
  }, [logout])

  const login = useCallback(async (req: LoginRequest) => {
    const { access_token } = await authApi.login(req)
    tokenStore.set(access_token)
    setUser(await authApi.me())
  }, [])

  const signup = useCallback(async (req: SignupRequest) => {
    const { access_token } = await authApi.signup(req) // 가입 성공 → 자동 로그인
    tokenStore.set(access_token)
    setUser(await authApi.me())
  }, [])

  const startDemo = useCallback(async () => {
    const { access_token } = await authApi.demo()
    tokenStore.set(access_token)
    setUser(await authApi.me())
  }, [])

  const value = useMemo(() => ({ user, loading, login, signup, startDemo, logout }), [user, loading, login, signup, startDemo, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
