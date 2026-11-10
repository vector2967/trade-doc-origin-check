import { useEffect, useState } from 'react'
import { IconClock, IconFolder } from '../components/icons'

/** 체험 사용자가 할 수 있는 점검 건수. 실제 제한은 서버가 하고(429 DEMO_LIMIT), 여기서는 안내만 한다 */
export const DEMO_CHECK_LIMIT = 3

function left(expiresAt: string, now: number) {
  const ms = Date.parse(expiresAt) - now
  if (!Number.isFinite(ms) || ms <= 0) return { text: '곧 끝나요', low: true }
  const m = Math.ceil(ms / 60_000)
  return { text: m >= 60 ? `${Math.floor(m / 60)}시간 ${m % 60}분 남음` : `${m}분 남음`, low: m <= 10 }
}

/** 사이드바 위쪽 '체험 모드' 카드: 남은 시간 · 사용한 건수 · 서류 창 열기 · 가입하기 */
export function DemoCard({ expiresAt, used, onOpenExplorer, onSignup }: { expiresAt: string; used: number; onOpenExplorer: () => void; onSignup: () => void }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(t)
  }, [])
  const t = left(expiresAt, now)
  const full = used >= DEMO_CHECK_LIMIT

  return (
    <section className="demo-card" aria-label="체험 모드">
      <p className="demo-card-head">
        <span className="demo-card-tag">체험 모드</span>
        <span className={`demo-card-time${t.low ? ' is-low' : ''}`}><IconClock size={13} /> {t.text}</span>
      </p>
      <p className="demo-card-line">
        체험 점검 <b className="num">{Math.min(used, DEMO_CHECK_LIMIT)}</b>/{DEMO_CHECK_LIMIT}건 사용 · 체험 데이터는 24시간 뒤 지워져요
      </p>
      <div className="demo-card-actions">
        <button type="button" className="btn-sm" onClick={onOpenExplorer} disabled={full} title={full ? '체험 점검을 모두 썼어요' : undefined}>
          <IconFolder size={14} /> 체험용 서류
        </button>
        <button type="button" className="btn-sm btn-primary" onClick={onSignup}>가입하기</button>
      </div>
    </section>
  )
}
