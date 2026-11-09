import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { AuthModal, type AuthMode } from '../components/AuthModal'
import { DocChip } from '../components/DocChip'
import { LogoMark } from '../components/icons'
import { ThemeToggle } from '../components/ThemeToggle'

/**
 * S-01 메인(랜딩). 로그인 상태면 심사목록으로 보낸다.
 * 히어로 오른쪽은 실제 점검 결과 한 장면(송장번호 불일치)을 디자인 시스템 '검인' 부품으로 그린 견본이다.
 */
const STEPS = [
  { name: '올리기', line: '한 선적 건의 PDF를 여러 개 그대로 놓기' },
  { name: '서류 나누기', line: '쪽마다 어떤 서류인지 AI가 분류' },
  { name: '값 읽기', line: '송장번호·HS·수량·결정기준 추출' },
  { name: '서로 대조', line: '서류 6종의 같은 칸을 맞대어 비교' },
  { name: '판정', line: '판정 근거와 보낼 메일 초안까지' },
]

const DECISIONS = [
  { name: '특혜 적용', tone: 'success', line: '그대로 바이어에게 보내도 됩니다.' },
  { name: '보완 요청', tone: 'warning', line: '서류를 고치거나 다시 받으면 됩니다.' },
  { name: '미적용', tone: 'danger', line: '서류를 고쳐도 특혜 근거가 없습니다.' },
  { name: '검토 필요', tone: 'caution', line: '담당자가 판단해야 할 차이가 있습니다.' },
]

export function LandingPage() {
  const { user, loading } = useAuth()
  const [modal, setModal] = useState<AuthMode | null>(null)

  if (!loading && user) return <Navigate to="/checks" replace />

  return (
    <div className="landing">
      <header className="ld-head">
        <div className="ld-inner ld-head-inner">
          <span className="ld-brand"><LogoMark size={24} /> FTA 원산지 점검</span>
          <nav className="ld-head-actions">
            <ThemeToggle />
            <button type="button" className="btn-ghost" onClick={() => setModal('login')}>로그인</button>
            <button type="button" className="btn-primary" onClick={() => setModal('signup')}>시작하기</button>
          </nav>
        </div>
      </header>

      <main>
        <section className="ld-hero">
          <div className="ld-inner ld-hero-inner">
            <div className="ld-hero-copy">
              <p className="ld-eyebrow">수출기업 FTA 담당자를 위한 원산지 서류 점검</p>
              <h1 className="ld-title">원산지 서류, 바이어에게 보내기 전에 한 번 더 대조합니다</h1>
              <p className="ld-lead">
                인보이스·원산지증명서·원산지확인서·B/L·수출신고필증을 맞대어 보고, 협정세율을 받을 수 있는지와 고칠 곳을 알려 드립니다.
              </p>
              <div className="ld-cta">
                <button type="button" className="btn-primary btn-lg" onClick={() => setModal('signup')}>시작하기</button>
                <button type="button" className="btn-lg" onClick={() => setModal('login')}>로그인</button>
              </div>
              <ul className="ld-docs" aria-label="점검하는 서류">
                {['인보이스', '패킹리스트', 'B/L', '원산지증명서', '원산지확인서', '수출신고필증'].map((d) => <li key={d}><DocChip name={d} /></li>)}
              </ul>
            </div>

            <figure className="ld-sample" aria-label="점검 결과 예시">
              <div className="ld-sample-head">
                <span className="ld-sample-name">KL HOME SUPPLY · FROZEN MACKEREL</span>
                <span className="ld-sample-meta"><span className="mono">SM-2609-0044</span> · 말레이시아 · 한-아세안</span>
              </div>
              <span className="badge badge-lg tone-warning">보완 요청</span>
              <p className="ld-sample-reason">원산지증명서 송장번호가 인보이스와 다릅니다.</p>
              <div className="ld-sample-cmp">
                <div><DocChip name="원산지증명서" /><span>송장번호</span><span className="mono"><mark className="ld-mark">SM-2609-0815</mark></span></div>
                <div><DocChip name="인보이스" /><span>송장번호</span><span className="mono">SM-2609-0044</span></div>
              </div>
              <p className="ld-sample-todo">→ 상공회의소에 C/O 정정·재발급 요청 메일 초안 준비됨</p>
              <figcaption className="ld-sample-cap">예시 데이터</figcaption>
            </figure>
          </div>
        </section>

        <section className="ld-section">
          <div className="ld-inner">
            <h2 className="ld-h2">이렇게 점검합니다</h2>
            <ol className="ld-steps">
              {STEPS.map((s, i) => (
                <li key={s.name}>
                  <span className="ld-step-no num">{i + 1}</span>
                  <span className="ld-step-name">{s.name}</span>
                  <span className="ld-step-line">{s.line}</span>
                </li>
              ))}
            </ol>
          </div>
        </section>

        <section className="ld-section ld-section-alt">
          <div className="ld-inner">
            <h2 className="ld-h2">판정은 네 가지입니다</h2>
            <ul className="ld-decisions">
              {DECISIONS.map((d) => (
                <li key={d.name}>
                  <span className={`badge badge-lg tone-${d.tone}`}>{d.name}</span>
                  <span>{d.line}</span>
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="ld-section ld-final">
          <div className="ld-inner ld-final-inner">
            <h2 className="ld-h2">다음 선적 서류부터 점검해 보세요</h2>
            <button type="button" className="btn-primary btn-lg" onClick={() => setModal('signup')}>시작하기</button>
          </div>
        </section>
      </main>

      <footer className="ld-foot">
        <div className="ld-inner ld-foot-inner">
          <span>사카밤바스피스</span>
          <span>AI의 1차 판정입니다. 최종 판단은 담당자가 합니다.</span>
        </div>
      </footer>

      {modal && <AuthModal mode={modal} onMode={setModal} onClose={() => setModal(null)} />}
    </div>
  )
}
