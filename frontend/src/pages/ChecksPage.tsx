import type { ReactNode } from 'react'
import { useLocation, useNavigate, useOutletContext } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { GROUPS, type GroupKey } from '../checks/groups'
import { useChecks } from '../checks/useChecks'
import { keepListParams } from '../checks/listParams'
import { IconAlert, IconArrowRight, IconCheck, IconClock, IconEye, IconPlus, IconUpload } from '../components/icons'

/** 묶음 카드에 붙이는 아이콘과 한 줄 설명 (무엇을 해야 하는 묶음인지) */
const CARD_META: Partial<Record<GroupKey, { icon: ReactNode; hint: string }>> = {
  action: { icon: <IconAlert size={18} />, hint: '서류를 고치거나 다시 받아야 해요' },
  review: { icon: <IconEye size={18} />, hint: '담당자 판단이 필요한 차이가 있어요' },
  busy: { icon: <IconClock size={18} />, hint: 'AI가 서류를 맞대어 보는 중이에요' },
  done: { icon: <IconCheck size={18} />, hint: '그대로 보내도 되는 건이에요' },
}

const todayLabel = () => new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', weekday: 'long' }).format(new Date())

/** /checks — 아직 심사를 고르지 않았을 때 오른쪽 패널. 진행 중 건수를 왼쪽 목록과 같은 묶음·순서로 + 심사 생성 */
export function ChecksPage() {
  const { items, viewOf, groups, view, sort } = useChecks()
  const { user } = useAuth()
  const navigate = useNavigate()
  const { search } = useLocation()
  const open = (items ?? []).filter((c) => viewOf(c) === 'open')

  // 카드를 누르면 그 묶음의 첫 건을 연다. via: 'step' → 왼쪽 목록이 그 묶음을 펼친다
  const openGroup = (key: string) => {
    const shown = view === 'open' ? groups.find((g) => g.key === key)?.items : null
    const list = shown ?? open.filter(GROUPS.find((g) => g.key === key)!.match)
      .sort((a, b) => (sort === 'latest' ? -1 : 1) * a.created_at.localeCompare(b.created_at))
    const first = list[0]
    if (!first) return
    const p = new URLSearchParams(keepListParams(search))
    p.delete('view')
    p.delete('result')
    const qs = p.toString()
    navigate(`/checks/${first.id}${qs ? `?${qs}` : ''}`, { state: { via: 'step' } })
  }
  const { openNew } = useOutletContext<{ openNew: () => void }>()
  const urgent = open.filter(GROUPS[0].match).length

  return (
    <div className="detail">
      <div className="home">
        <header className="home-head">
          <p className="home-date">{todayLabel()}</p>
          <h1 className="home-title">{user?.name ? `${user.name}님, ` : ''}오늘 처리할 심사예요</h1>
          <p className="home-sub">
            {items == null
              ? '심사 목록을 불러오는 중이에요.'
              : urgent > 0
                ? <>조치가 필요한 심사가 <b className="num">{urgent}건</b> 있어요. 왼쪽 목록에서 고르거나 아래 카드로 묶음의 첫 건을 여세요.</>
                : '왼쪽 목록에서 심사를 고르거나 아래 카드로 묶음의 첫 건을 여세요.'}
          </p>
        </header>

        {items != null && items.length > 0 && (
          <div className="home-cards">
            {GROUPS.map((g) => {
              const n = open.filter(g.match).length
              const meta = CARD_META[g.key]
              return (
                <button key={g.key} type="button" className={`home-card tone-${g.tone}`} disabled={n === 0} onClick={() => openGroup(g.key)}>
                  <span className="home-card-top">
                    <span className="home-card-icon">{meta?.icon}</span>
                    <IconArrowRight size={16} className="home-card-go" />
                  </span>
                  <span className="home-card-num num">{n}</span>
                  <span className="home-card-label">{g.label}</span>
                  {meta && <span className="home-card-hint">{meta.hint}</span>}
                </button>
              )
            })}
          </div>
        )}

        <button type="button" className="home-drop" onClick={openNew}>
          <span className="home-drop-icon"><IconUpload size={22} /></span>
          <span className="home-drop-text">
            <strong>새 심사 만들기</strong>
            <span>한 선적 건의 서류 PDF를 이 화면 어디에나 끌어다 놓아도 시작돼요</span>
          </span>
          <span className="home-drop-btn" aria-hidden><IconPlus size={16} /> 심사 생성</span>
        </button>
      </div>
    </div>
  )
}
