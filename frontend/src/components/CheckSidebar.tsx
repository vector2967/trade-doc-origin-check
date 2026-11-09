import { useEffect, useRef, useState } from 'react'
import { NavLink, useLocation, useMatch } from 'react-router-dom'
import type { OriginCheckSummary } from '../api/types'
import { RESULT_FILTERS, type CheckGroup, type GroupKey } from '../checks/groups'
import { useChecks } from '../checks/useChecks'
import { COLLAPSE_ACTIVE_GROUP, keepListParams } from '../checks/listParams'
import { formatRelative } from '../utils/format'
import { itemLabel, memoOf, shortCompany } from '../utils/party'
import { IconChevronDown, IconChevronRight, IconHelp, IconLogout, IconPlus, IconSearch, LogoMark } from './icons'
import { Country } from './Country'
import { ThemeToggle } from './ThemeToggle'

/**
 * 왼쪽 사이드바: 할 일 기준 묶음(조치 필요 · 검토 필요 · 점검 중 · 특혜 적용) + 검색.
 * 한 줄 = 바이어·대표 품목 / 송장번호·목적국. 문제 내용은 상세에서 본다. (사용자: 수출기업 FTA 담당자) 색은 문제 표시 하나에만 쓴다.
 */
export function CheckSidebar({ userName, company, onNew, onLogout, onHelp }: {
  userName: string
  company: string | null
  onNew: () => void
  onLogout: () => void
  onHelp: () => void
}) {
  const { items, groups, error, truncated, q, setFilter, reload, view, setView, viewCounts, doneAt, notice, result } = useChecks()
  const { search, state } = useLocation()
  const checkId = useMatch('/checks/:checkId')?.params.checkId
  const listSearch = keepListParams(search)
  // 묶음 펼침: 사용자가 바꾼 것만 기억하고 나머지는 기본값
  // 기본값 = 특혜 적용은 접힘, 완료 보기의 달 묶음은 가장 최근 달만 펼침, 나머지는 펼침
  const [opened, setOpened] = useState<Map<GroupKey, boolean>>(() => new Map())
  const isOpen = (k: GroupKey) => opened.get(k) ?? (k === 'done' ? false : k.startsWith('m-') ? k === groups[0]?.key : true)
  const setOpen = (changes: [GroupKey, boolean][]) =>
    setOpened((prev) => {
      const next = new Map(prev)
      for (const [k, v] of changes) next.set(k, v)
      return next
    })
  const toggle = (k: GroupKey) => setOpen([[k, !isOpen(k)]])
  const total = groups.reduce((n, g) => n + g.items.length, 0)

  // ↑↓(이전·다음)으로 옮겨 갈 때: 도착한 묶음은 펼치고, 다른 묶음으로 넘어왔으면 떠난 묶음은 접는다
  const prevId = useRef(checkId)
  useEffect(() => {
    const from = prevId.current
    prevId.current = checkId
    if (!checkId || from === checkId || (state as { via?: string } | null)?.via !== 'step') return
    const groupOf = (id: string | undefined) => groups.find((g) => g.items.some((c) => String(c.id) === id))?.key
    const to = groupOf(checkId)
    const left = groupOf(from)
    if (!to) return
    setOpened((prev) => {
      const next = new Map(prev)
      next.set(to, true)
      if (left && left !== to) next.set(left, false)
      return next
    })
  }, [checkId, state, groups])

  // 맨 위 심사에서 한 번 더 ↑ → 지금 보고 있는 심사가 든 묶음을 접는다 (그 한 건만 남는다)
  useEffect(() => {
    const onCollapse = () => {
      const key = groups.find((g) => g.items.some((c) => String(c.id) === checkId))?.key
      if (key) setOpened((prev) => new Map(prev).set(key, false))
    }
    window.addEventListener(COLLAPSE_ACTIVE_GROUP, onCollapse)
    return () => window.removeEventListener(COLLAPSE_ACTIVE_GROUP, onCollapse)
  }, [groups, checkId])

  // 선택된 심사가 바뀌면(새로 만든 건 포함) 그 행이 보이게 스크롤
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!checkId) return
    const id = requestAnimationFrame(() => {
      listRef.current?.querySelector('a.sb-row.active')?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    })
    return () => cancelAnimationFrame(id)
  }, [checkId, total, opened])

  return (
    <aside className="sidebar" aria-label="심사목록">
      <div className="sb-head">
        <NavLink to={`/checks${listSearch}`} className="sb-brand">
          <LogoMark />
          <span>FTA 원산지 점검</span>
        </NavLink>
        <div className="sb-head-actions">
          <ThemeToggle />
          <button type="button" className="btn-icon sb-new" onClick={onNew} data-tour="new" aria-label="심사 생성" title="심사 생성">
            <IconPlus size={18} />
          </button>
        </div>
      </div>

      <div className="sb-controls">
        <label className="search search-sm">
          <IconSearch />
          <input type="search" value={q} onChange={(e) => setFilter({ q: e.target.value })} placeholder="바이어·품목·송장번호 검색" aria-label="심사 검색" />
        </label>
        <div className="seg" role="tablist" aria-label="보기" data-tour="views">
          <button type="button" role="tab" aria-selected={view === 'open'} className="seg-btn" onClick={() => setView('open')}>
            진행 중 <span className="num">{items ? viewCounts.open : '–'}</span>
          </button>
          <button type="button" role="tab" aria-selected={view === 'done'} className="seg-btn" onClick={() => setView('done')}>
            완료 <span className="num">{items ? viewCounts.done : '–'}</span>
          </button>
        </div>
        {view === 'done' && (
          <div className="sb-chips" role="radiogroup" aria-label="결과">
            {RESULT_FILTERS.map((r) => (
              <button key={r.key} type="button" role="radio" aria-checked={result === r.key} className="sb-chip" onClick={() => setFilter({ result: r.key })}>
                {r.label}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="sb-list-wrap" ref={listRef} data-tour="list">
        {error && (
          <div className="sb-empty">
            <p className="error">{error}</p>
            <button type="button" className="btn-sm" onClick={reload}>다시 시도</button>
          </div>
        )}
        {!error && items == null && <SidebarSkeleton />}
        {!error && items != null && total === 0 && (
          <div className="sb-empty">
            {items.length === 0 ? (
              <>
                <p>아직 심사가 없습니다.</p>
                <button type="button" className="btn-sm btn-primary" onClick={onNew}><IconPlus size={14} /> 심사 생성</button>
              </>
            ) : q === '' && view === 'done' && result !== 'all' ? (
              <p>{result === 'ok' ? '특혜 적용' : '특혜 미적용'}으로 완료한 심사가 없습니다.</p>
            ) : q === '' && view === 'done' ? (
              <p>완료한 심사가 없습니다.</p>
            ) : q === '' ? (
              <p>남은 심사가 없습니다.</p>
            ) : (
              <>
                <p>검색 결과가 없습니다.</p>
                <button type="button" className="btn-sm" onClick={() => setFilter({ tab: 'all', q: '' })}>검색 지우기</button>
              </>
            )}
          </div>
        )}
        {total > 0 &&
          groups.map((g) =>
            g.items.length === 0 ? null : (
              <Group
                key={g.key}
                group={g}
                open={isOpen(g.key)}
                activeId={checkId}
                onToggle={() => toggle(g.key)}
                search={listSearch}
                doneAt={doneAt}
              />
            ),
          )}
        {truncated && <p className="sb-note">최근 500건까지만 보여 줍니다.</p>}
        {notice && <p className="sb-notice error" role="alert">{notice}</p>}
      </div>

      <div className="sb-foot">
        <span className="avatar" aria-hidden>{userName.slice(0, 1) || '?'}</span>
        <span className="sb-user">
          <span className="sb-user-name">{userName}</span>
          {company && <span className="sb-user-sub">{company}</span>}
        </span>
        <button type="button" className="btn-icon btn-ghost" onClick={onHelp} data-tour="help" aria-label="사용법 다시 보기" title="사용법 다시 보기">
          <IconHelp />
        </button>
        <button type="button" className="btn-icon btn-ghost" onClick={onLogout} aria-label="로그아웃" title="로그아웃">
          <IconLogout />
        </button>
      </div>
    </aside>
  )
}

/** 접으면 나머지는 숨기고, 지금 보고 있는 심사 한 건만 남긴다 */
function Group({ group: g, open, activeId, onToggle, search, doneAt }: { group: CheckGroup; open: boolean; activeId?: string; onToggle: () => void; search: string; doneAt: (id: number) => string | null }) {
  const shown = open ? g.items : g.items.filter((c) => String(c.id) === activeId)
  return (
    <section className="sb-group">
      <button type="button" className="sb-group-head" onClick={onToggle} aria-expanded={open}>
        {open ? <IconChevronDown size={14} /> : <IconChevronRight size={14} />}
        <span className={`sb-group-mark tone-${g.tone}`} aria-hidden />
        <span className="sb-group-label">{g.label}</span>
        <span className="sb-group-count num">{g.items.length}</span>
      </button>
      {shown.length > 0 && (
        <ul className="sb-list">
          {shown.map((c) => (
            <li key={c.id}>
              <SidebarRow check={c} search={search} doneAt={g.key.startsWith('m-') ? doneAt(c.id) : null} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** 2026-10-07 → 10.07 */
const shortDate = (d: string) => `${d.slice(5, 7)}.${d.slice(8, 10)}`

function SidebarRow({ check: c, search, doneAt }: { check: OriginCheckSummary; search: string; doneAt: string | null }) {
  const memo = memoOf(c.title, c.file_name)
  // 첫 줄: 바이어 · 대표 품목 (요약 필드가 없으면 관리번호·메모 → 파일명)
  const name = [shortCompany(c.consignee), itemLabel(c.primary_item, c.item_count)].filter(Boolean).join(' · ') || memo || c.file_name
  // 둘째 줄: 송장번호 · 목적국 (+ 메모) · 오른쪽에 선적일
  const sub = [c.invoice_no, c.destination_country].filter(Boolean)

  return (
    <NavLink to={`/checks/${c.id}${search}`} className="sb-row" title={c.consignee ?? undefined}>
      <span className="sb-name">{name}</span>
      <span className="sb-r2">
        <span className="sb-sub">
          {sub.length > 0 ? (
            <>
              {c.invoice_no && <span className="mono">{c.invoice_no}</span>}
              {c.destination_country && <span><Country value={c.destination_country} /></span>}
            </>
          ) : (
            <span>{memo && name !== memo ? memo : c.file_name}</span>
          )}
        </span>
        <span className="sb-date num">
          {c.shipped_on ? `${shortDate(c.shipped_on)} 선적` : doneAt ? `${formatRelative(doneAt)} 완료` : formatRelative(c.created_at)}
        </span>
      </span>
    </NavLink>
  )
}

function SidebarSkeleton() {
  return (
    <ul className="sb-list" aria-busy="true" aria-label="불러오는 중">
      {Array.from({ length: 6 }, (_, i) => (
        <li key={i} className="sb-row is-skeleton">
          <span className="skeleton-line" style={{ width: '45%' }} />
          <span className="skeleton-line" style={{ width: '75%' }} />
        </li>
      ))}
    </ul>
  )
}
