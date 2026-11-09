import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/useAuth'
import { IconBuilding, IconCheck, IconEye, IconEyeOff, IconLock, IconMail, IconUser, IconX, LogoMark } from './icons'

export type AuthMode = 'login' | 'signup'

/**
 * M-01 로그인 / M-02 회원가입 (화면명세서 §2). 한 모달 안에서 내용만 바꾼다.
 * ESC · 바깥 클릭 · X 로 닫고, 첫 칸에 자동 포커스, Enter 로 제출.
 * 넓은 화면은 왼쪽에 서비스 소개 면 + 오른쪽 입력 (Mobbin 의 분할형 로그인), 위쪽 탭으로 로그인·가입을 오간다.
 */
export function AuthModal({ mode, onMode, onClose }: { mode: AuthMode; onMode: (m: AuthMode) => void; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal auth" role="dialog" aria-modal="true" aria-labelledby="auth-title" onClick={(e) => e.stopPropagation()}>
        <aside className="auth-aside" aria-hidden>
          <span className="auth-aside-brand"><LogoMark size={26} /> FTA 원산지 점검</span>
          <p className="auth-aside-title">서류 대조는 AI가,<br />판단은 담당자가.</p>
          <ul className="auth-aside-points">
            <li><IconCheck size={15} /> 서류 6종의 같은 칸을 맞대어 비교</li>
            <li><IconCheck size={15} /> 원본 PDF에 문제 자리를 형광펜으로</li>
            <li><IconCheck size={15} /> 판정 근거·법령·메일 초안까지</li>
          </ul>
          <div className="auth-aside-card">
            <span className="badge badge-lg stamp tone-warning">보완 요청</span>
            <span className="auth-aside-card-text">
              <b>원산지증명서 송장번호 ≠ 인보이스</b>
              <small>상공회의소에 보낼 메일 초안 준비됨</small>
            </span>
          </div>
        </aside>

        <div className="auth-main">
          <button type="button" className="btn-icon btn-ghost btn-sm auth-close" onClick={onClose} aria-label="닫기">
            <IconX />
          </button>
          <div className="auth-brand">
            <LogoMark size={28} />
          </div>
          <div className="auth-tabs" role="tablist" aria-label="로그인 또는 회원가입">
            <button type="button" role="tab" aria-selected={mode === 'login'} className="auth-tab" onClick={() => onMode('login')}>로그인</button>
            <button type="button" role="tab" aria-selected={mode === 'signup'} className="auth-tab" onClick={() => onMode('signup')}>회원가입</button>
          </div>
          {mode === 'login' ? <LoginForm onSwitch={() => onMode('signup')} /> : <SignupForm onSwitch={() => onMode('login')} />}
        </div>
      </div>
    </div>
  )
}

function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}

/** 앞에 아이콘이 붙는 입력 칸 */
function IconInput({ icon, children }: { icon: ReactNode; children: ReactNode }) {
  return (
    <span className="input-icon">
      {icon}
      {children}
    </span>
  )
}

function PasswordInput({ id, name, placeholder, minLength, autoComplete }: { id: string; name: string; placeholder?: string; minLength?: number; autoComplete: string }) {
  const [show, setShow] = useState(false)
  return (
    <span className="password input-icon">
      <IconLock size={16} />
      <input id={id} name={name} type={show ? 'text' : 'password'} required minLength={minLength} placeholder={placeholder} autoComplete={autoComplete} />
      <button type="button" className="btn-icon btn-ghost btn-sm" onClick={() => setShow((v) => !v)} aria-label={show ? '비밀번호 숨기기' : '비밀번호 보기'}>
        {show ? <IconEyeOff size={16} /> : <IconEye size={16} />}
      </button>
    </span>
  )
}

function LoginForm({ onSwitch }: { onSwitch: () => void }) {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    setBusy(true)
    setError(null)
    try {
      await login({ email: String(f.get('email')).trim(), password: String(f.get('password')) })
      navigate('/checks')
    } catch (err) {
      // 어느 쪽이 틀렸는지 구분하지 않는다
      setError(err instanceof ApiError && err.status === 401 ? '이메일 또는 비밀번호가 맞지 않습니다.' : err instanceof ApiError ? err.message : '로그인하지 못했습니다. 다시 시도하세요.')
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="auth-form">
      <div className="auth-heading">
        <h2 id="auth-title">다시 오셨네요</h2>
        <p className="auth-sub">이메일로 로그인하고 점검을 이어 가세요.</p>
      </div>
      <Field label="이메일">
        <IconInput icon={<IconMail size={16} />}>
          <input id="login-email" name="email" type="email" required autoFocus autoComplete="email" placeholder="name@company.com" />
        </IconInput>
      </Field>
      <Field label="비밀번호">
        <PasswordInput id="login-password" name="password" autoComplete="current-password" />
      </Field>
      {error && <p className="error auth-error" role="alert">{error}</p>}
      <button type="submit" className="btn-primary btn-lg auth-submit" disabled={busy}>{busy ? '로그인 중…' : '로그인'}</button>
      <p className="auth-switch">
        처음이세요? <button type="button" className="link" onClick={onSwitch}>회원가입</button>
      </p>
    </form>
  )
}

function SignupForm({ onSwitch }: { onSwitch: () => void }) {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    const f = new FormData(e.currentTarget)
    if (f.get('password') !== f.get('passwordConfirm')) {
      setError('비밀번호 확인이 일치하지 않습니다.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await signup({
        email: String(f.get('email')).trim(),
        password: String(f.get('password')),
        name: String(f.get('name')).trim(),
        company: String(f.get('company') ?? '').trim() || undefined,
      })
      navigate('/checks') // 가입 성공 → 자동 로그인
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '가입하지 못했습니다. 다시 시도하세요.')
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="auth-form">
      <div className="auth-heading">
        <h2 id="auth-title">계정 만들기</h2>
        <p className="auth-sub">가입하면 바로 첫 심사를 만들 수 있습니다.</p>
      </div>
      <div className="field-row">
        <Field label="이름">
          <IconInput icon={<IconUser size={16} />}>
            <input id="signup-name" name="name" required maxLength={50} autoFocus autoComplete="name" placeholder="홍길동" />
          </IconInput>
        </Field>
        <Field label="회사명 (선택)">
          <IconInput icon={<IconBuilding size={16} />}>
            <input id="signup-company" name="company" maxLength={100} autoComplete="organization" placeholder="사카밤바스피스" />
          </IconInput>
        </Field>
      </div>
      <Field label="이메일">
        <IconInput icon={<IconMail size={16} />}>
          <input id="signup-email" name="email" type="email" required autoComplete="email" placeholder="name@company.com" />
        </IconInput>
      </Field>
      <Field label="비밀번호" hint="8자 이상">
        <PasswordInput id="signup-password" name="password" minLength={8} autoComplete="new-password" />
      </Field>
      <Field label="비밀번호 확인">
        <PasswordInput id="signup-password2" name="passwordConfirm" autoComplete="new-password" />
      </Field>
      {error && <p className="error auth-error" role="alert">{error}</p>}
      <button type="submit" className="btn-primary btn-lg auth-submit" disabled={busy}>{busy ? '가입 중…' : '가입하고 시작하기'}</button>
      <p className="auth-switch">
        이미 계정이 있나요? <button type="button" className="link" onClick={onSwitch}>로그인</button>
      </p>
    </form>
  )
}
