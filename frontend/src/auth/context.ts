import { createContext } from 'react'
import type { LoginRequest, MeResponse, SignupRequest } from '../api/types'

export interface AuthState {
  user: MeResponse | null
  /** 저장된 토큰으로 내 정보를 확인하는 중 */
  loading: boolean
  login: (req: LoginRequest) => Promise<void>
  signup: (req: SignupRequest) => Promise<void>
  /** 체험하기: 계정 없이 체험 사용자로 들어간다 */
  startDemo: () => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)
