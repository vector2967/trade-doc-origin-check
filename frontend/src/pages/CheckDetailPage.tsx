import { useCallback, useEffect, useRef, useState, type MouseEvent, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useOutletContext, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { originChecksApi } from '../api/originChecks'
import type { AgentExtractedMeta, ExtractNode, FieldMeta, ItemCheck, OriginCheckDetail, OriginCheckSummary } from '../api/types'
import { isBusy } from '../checks/groups'
import { useAuth } from '../auth/useAuth'
import { useChecks } from '../checks/useChecks'
import { usePanel, type PanelKind } from '../checks/usePanel'
import { COLLAPSE_ACTIVE_GROUP, keepListParams } from '../checks/listParams'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { IconAlert, IconArrowLeft, IconCheck, IconChevronDown, IconChevronRight, IconChevronUp, IconFile, IconMail, IconRefresh, IconScale, IconSparkle, IconTrash } from '../components/icons'
import { FOLLOW_UP_TITLE } from '../data/followUp'
import { LawPanel, MailPanel, PdfPanel, SidePanel } from '../components/SidePanels'
import { COUNTRY_FIELDS, Country } from '../components/Country'
import { DocChip } from '../components/DocChip'
import { fileTypes, pageTypes } from '../data/docTypes'
import { DOC_DEFS } from '../data/documents'
import { codeComparison, fieldId } from '../data/highlights'
import { dealInfo } from '../data/deal'
import { CODE_BASIS, MISSING_DOC_BASIS, STAGE_LABEL } from '../data/legalBasis'
import { issueLine } from '../data/issueSummary'
import { useOriginCheck } from '../hooks/useOriginCheck'
import { CONFIDENCE_THRESHOLD, DECISION_TONE } from '../styles/tokens'
import { formatDateTime, formatValue } from '../utils/format'
import { memoOf, shortCompany } from '../utils/party'

const STAGE_TONE = { 1: 'danger', 2: 'warning', 3: 'caution' } as const

/** 다시 실행·삭제가 실패했을 때 안내 (API 명세 §2.5-2, §2.5-3) */
function actionMessage(e: unknown) {
  if (!(e instanceof ApiError)) return '요청을 보내지 못했습니다. 네트워크를 확인하고 다시 시도하세요.'
  switch (e.code) {
    case 'CHECK_NOT_FAILED': return '실패한 건만 다시 실행할 수 있어요.'
    case 'CHECK_IN_PROGRESS': return 'AI가 처리 중이라 지금은 삭제할 수 없어요. 끝난 뒤 다시 시도하세요.'
    case 'AGENT_UNAVAILABLE': return 'AI 서비스에 잠시 연결할 수 없어요. 잠시 뒤 다시 시도하세요.'
    default: return e.message
  }
}

/**
 * S-03 심사 상세 (오른쪽 작업 패널). 처리 중 / 완료 / 실패 세 상태.
 * 위쪽 바: 목록 위치(n / N) + 위·아래 이동(↑↓ 또는 k·j).
 * 법령 확인 · 원본 PDF · 후속 문서 버튼 → 오른쪽 사이드 패널이 열렸다 닫힌다(Esc).
 */
export function CheckDetailPage() {
  const { checkId } = useParams()
  const { user } = useAuth()
  const { data, error, loading, refresh } = useOriginCheck(checkId)
  const { filtered, reload, doneAt, setDone, view, patchLocal, removeLocal } = useChecks()
  const { panel, code, page, field, focusKey, open, close, toggle } = usePanel()
  const navigate = useNavigate()
  const { search } = useLocation()
  const { openNew } = useOutletContext<{ openNew: () => void }>()

  // 목록 안 위치와 이전/다음
  const idx = filtered.findIndex((c) => String(c.id) === checkId)
  const prev = idx > 0 ? filtered[idx - 1] : null
  const next = idx >= 0 && idx < filtered.length - 1 ? filtered[idx + 1] : null
  // via: 'step' → 왼쪽 목록이 도착한 묶음을 펼치고 떠난 묶음을 접는다
  const go = (id: number) => navigate(`/checks/${id}${search}`, { state: { via: 'step' } })

  // 완료 처리: 진행 중 목록에서 빼고 다음 건으로 넘어간다 (받은편지함 정리하듯)
  const doneTime = data ? doneAt(data.id) : null
  const canDone = data != null && !isBusy(data)
  const toggleDone = () => {
    if (!data || !canDone) return
    if (doneTime) {
      setDone(data.id, false)
      return
    }
    const target = view === 'open' ? (next ?? prev) : null
    setDone(data.id, true)
    if (view === 'open') navigate(target ? `/checks/${target.id}${search}` : `/checks${keepListParams(search)}`)
  }

  // 다시 실행 · 삭제. 처리 중에는 버튼을 막아 두 번 눌리지 않게 한다
  const [acting, setActing] = useState<'retry' | 'delete' | null>(null)
  const [actionError, setActionError] = useState<{ id: string | undefined; text: string } | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const shownError = actionError && actionError.id === checkId ? actionError.text : null
  const canDelete = data != null && data.status !== 'in_progress'

  const retry = async () => {
    if (!data || acting) return
    const id = data.id
    setActing('retry')
    setActionError(null)
    try {
      const r = await originChecksApi.retry(id)
      // 목록에서는 바로 '점검 중'으로, 상세는 다시 조회해 폴링을 시작한다
      patchLocal(id, { status: r.status, decision: null, invoice_no: null, completed_at: null, done_at: null })
    } catch (e) {
      setActionError({ id: String(id), text: actionMessage(e) })
    } finally {
      setActing(null)
      refresh()
      reload()
    }
  }

  const remove = async () => {
    if (!data || acting) return
    const id = data.id
    setActing('delete')
    setActionError(null)
    try {
      await originChecksApi.remove(id)
      setConfirmDelete(false)
      navigate(`/checks${keepListParams(search)}`)
      removeLocal(id)
    } catch (e) {
      setConfirmDelete(false)
      setActionError({ id: String(id), text: actionMessage(e) })
      refresh()
      reload()
    } finally {
      setActing(null)
    }
  }
  const cancelDelete = useCallback(() => setConfirmDelete(false), [])

  // 처리 중 → 완료/실패가 되면 왼쪽 목록도 갱신
  const lastStatus = useRef<string | undefined>(undefined)
  useEffect(() => {
    if (lastStatus.current && data && isBusy({ status: lastStatus.current }) && !isBusy(data)) reload()
    lastStatus.current = data?.status
  }, [data, reload])

  // 키보드: ↑/k 이전, ↓/j 다음, Esc 패널 닫기
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target instanceof Element ? e.target : null
      if (t?.closest('input, textarea, select, [contenteditable]') || e.metaKey || e.ctrlKey || e.altKey) return
      if (e.key === 'Escape' && panel) close()
      else if (e.key === 'e') toggleDone()
      else if (e.key === 'ArrowUp' || e.key === 'k') {
        e.preventDefault()
        // 맨 위에서 한 번 더 ↑ → 왼쪽 목록이 지금 묶음을 접는다
        if (prev) go(prev.id)
        else window.dispatchEvent(new Event(COLLAPSE_ACTIVE_GROUP))
      }
      else if ((e.key === 'ArrowDown' || e.key === 'j') && next) { e.preventDefault(); go(next.id) }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const completed = data?.status === 'completed'

  return (
    <div className="detail">
      <header className="detail-bar">
        <Link to={`/checks${keepListParams(search)}`} className="btn-icon btn-ghost detail-back" aria-label="목록으로">
          <IconArrowLeft />
        </Link>
        <nav className="crumbs" aria-label="위치">
          <Link to={`/checks${keepListParams(search)}`}>심사목록</Link>
          <IconChevronRight size={14} />
          <span className="crumbs-current">{data ? displayName(data) : '…'}</span>
        </nav>
        <div className="detail-bar-right" data-tour="nav">
          {idx >= 0 && <span className="num secondary detail-pos">{idx + 1} / {filtered.length}</span>}
          <button type="button" className="btn-icon btn-ghost btn-sm" disabled={!prev} onClick={() => prev && go(prev.id)} aria-label="이전 심사" title="이전 (↑)">
            <IconChevronUp />
          </button>
          <button type="button" className="btn-icon btn-ghost btn-sm" disabled={!next} onClick={() => next && go(next.id)} aria-label="다음 심사" title="다음 (↓)">
            <IconChevronDown />
          </button>
        </div>
      </header>

      <div className={`detail-body${panel && data ? ' has-panel' : ''}`}>
        <div className="detail-scroll" key={checkId}>
          {loading && <DetailSkeleton />}
          {!loading && (error || !data) && (
            <div className="detail-empty">
              <IconAlert size={28} />
              <h2>심사를 찾을 수 없습니다</h2>
              <p className="secondary">{error?.message ?? '삭제됐거나 다른 사용자의 심사입니다.'}</p>
            </div>
          )}
          {data && (
            <div className="detail-grid" key={data.id}>
              <article className="detail-content">
                <div className="detail-actions" role="toolbar" aria-label="심사 도구">
                  <PanelButton active={panel === 'law'} onClick={() => toggle('law')} disabled={!completed} icon={<IconScale />}>법령 확인</PanelButton>
                  <PanelButton active={panel === 'pdf'} onClick={() => toggle('pdf')} icon={<IconFile />}>원본 PDF</PanelButton>
                  <PanelButton active={panel === 'mail'} onClick={() => toggle('mail')} disabled={!data.follow_up} icon={<IconMail />}>
                    {data.follow_up ? (FOLLOW_UP_TITLE[data.follow_up.kind] ?? '후속 문서').replace(' 초안', '') : '후속 문서'}
                  </PanelButton>
                  {(canDelete || canDone) && (
                    <span className="detail-actions-end">
                      {canDelete && (
                        <button
                          type="button"
                          className="btn-sm tool-btn delete-btn"
                          onClick={() => setConfirmDelete(true)}
                          disabled={acting != null}
                          title="이 심사와 올린 원본 PDF 삭제"
                        >
                          <IconTrash size={14} />
                          삭제
                        </button>
                      )}
                      {canDone && (
                        <button
                          type="button"
                          className={`btn-sm done-btn${doneTime ? ' is-done' : ''}`}
                          data-tour="done"
                          onClick={toggleDone}
                          title={doneTime ? '진행 중으로 되돌리기 (E)' : '완료 처리하고 다음 건으로 (E)'}
                        >
                          <IconCheck size={14} />
                          {doneTime ? '다시 열기' : '완료 처리'}
                        </button>
                      )}
                    </span>
                  )}
                </div>

                {shownError && <p className="action-error" role="alert"><IconAlert size={14} /> {shownError}</p>}

                <DetailHeader detail={data} doneAt={doneTime} />

                {isBusy(data) && <BusyState status={data.status} queue={data.queue_position ?? null} demo={user?.demo === true} />}
                {data.status === 'failed' && <FailedState detail={data} retrying={acting === 'retry'} disabled={acting != null} onRetry={retry} onReupload={openNew} />}
                {completed && <CompletedState detail={data} onOpen={open} />}
                {(data.files?.length ?? 0) > 0 && <FileList detail={data} onOpenPage={(p) => open('pdf', { page: p })} />}
              </article>
            </div>
          )}
        </div>

        <SidePanel kind={data ? panel : null} onClose={close}>
          {(k) =>
            data &&
            (k === 'law' ? <LawPanel detail={data} focusCode={code} onShowInPdf={(c) => open('pdf', { code: c })} />
              : k === 'pdf' ? <PdfPanel detail={data} page={page} field={field} code={code} focusKey={focusKey} />
                : <MailPanel followUp={data.follow_up} />)
          }
        </SidePanel>
      </div>

      {confirmDelete && data && (
        <ConfirmDialog
          title="이 심사를 삭제할까요?"
          confirmLabel="삭제"
          busyLabel="삭제하는 중…"
          danger
          busy={acting === 'delete'}
          onConfirm={remove}
          onCancel={cancelDelete}
        >
          <p><b>{displayName(data)}</b>의 점검 결과와 올린 원본 PDF가 모두 지워져요. 되돌릴 수 없어요.</p>
        </ConfirmDialog>
      )}
    </div>
  )
}

function PanelButton({ active, onClick, disabled, icon, children }: { active: boolean; onClick: () => void; disabled?: boolean; icon: ReactNode; children: ReactNode }) {
  return (
    <button type="button" className={`btn-sm tool-btn${active ? ' is-active' : ''}`} aria-pressed={active} onClick={onClick} disabled={disabled}>
      {icon}
      {children}
    </button>
  )
}

/** 판정 근거. 에이전트 문장이라 형식이 건마다 달라 나누지 않고 문단 그대로 보여 주고, 길면 3줄까지만 펼쳐 둔다 */
function JudgeReason({ reason }: { reason: string }) {
  const text = reason.trim()
  const ref = useRef<HTMLParagraphElement>(null)
  const [open, setOpen] = useState(false)
  const [long, setLong] = useState(false)

  useEffect(() => {
    const el = ref.current
    if (!el || open) return
    const ro = new ResizeObserver(() => setLong(el.scrollHeight > el.clientHeight + 1))
    ro.observe(el)
    return () => ro.disconnect()
  }, [text, open])

  if (!text) return null
  return (
    <div className="judge-reason">
      <p ref={ref} id="judge-reason-text" className={`verdict-reason judge-reason-text${open ? '' : ' is-clamped'}`}>{text}</p>
      {(long || open) && (
        <button type="button" className="btn-sm btn-ghost judge-reason-more" aria-expanded={open} aria-controls="judge-reason-text" onClick={() => setOpen((v) => !v)}>
          {open ? <>접기 <IconChevronUp size={14} /></> : <>더 보기 <IconChevronDown size={14} /></>}
        </button>
      )}
    </div>
  )
}

const RESULT_TONE: Record<string, string> = { 충족: 'success', 같음: 'success', '6단위 같음': 'caution', 불충족: 'danger', 다름: 'danger' }
const KIND_SIDES = { PSR: ['원산지증명서', '관리대장 허용'], HS: ['인보이스', '수출신고필증'] } as const

/** HS 10자리를 0303.54-0000 처럼 끊어 6단위(소호)가 눈에 들어오게 한다 */
const fmtHs = (v: string | null) => (v && /^\d{10}$/.test(v) ? `${v.slice(0, 4)}.${v.slice(4, 6)}-${v.slice(6)}` : v)

/** 품목별 PSR·HS 비교. 백엔드가 계산한 값이라 형식이 늘 같다 */
function ItemChecks({ checks }: { checks: ItemCheck[] }) {
  return (
    <div className="item-checks">
      <h2 className="item-checks-title">품목별 원산지 기준·HS 비교</h2>
      <table className="item-checks-table">
        <thead>
          <tr><th>구분</th><th>품목</th><th>서류 값</th><th>기준·비교 값</th><th>결과</th></tr>
        </thead>
        <tbody>
          {checks.map((c, i) => {
            const [docSide, ruleSide] = KIND_SIDES[c.kind] ?? ['서류', '기준']
            const val = (v: string | null) => (c.kind === 'HS' ? fmtHs(v) : v) || '—'
            return (
              <tr key={i}>
                <td><span className="item-kind">{c.kind}</span></td>
                <td className="item-name">
                  {c.item}
                  {c.kind === 'PSR' && c.hs && <span className="muted mono"> {fmtHs(c.hs)}</span>}
                </td>
                <td><span className="item-side">{docSide}</span><span className="mono">{val(c.doc_value)}</span></td>
                <td>
                  <span className="item-side">{ruleSide}</span><span className="mono">{val(c.rule_value)}</span>
                  {c.rule_text && c.rule_text !== c.rule_value && <span className="item-rule-text">협정 PSR: {c.rule_text}</span>}
                </td>
                <td><span className={`badge tone-${RESULT_TONE[c.result] ?? 'neutral'}`}>{c.result}</span></td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

/* ───────── 완료 ───────── */
function CompletedState({ detail, onOpen }: { detail: OriginCheckDetail; onOpen: (k: PanelKind, o?: { code?: string; page?: number; field?: string }) => void }) {
  const judge = detail.judge
  const tone = judge ? DECISION_TONE[judge.decision] : 'neutral'
  const codes = judge?.mismatch_codes ?? detail.mismatches.map((m) => m.code)
  const missing = judge?.missing_documents ?? []
  // 대표 문제 하나와 먼저 할 일 (목록 요약과 같은 규칙: 특혜 불가 → 할 일 있는 것 → 나머지)
  const first = issueLine({
    id: detail.id, title: detail.title, file_name: detail.file_name, status: detail.status,
    decision: judge?.decision ?? null, invoice_no: judge?.invoice_no ?? null,
    created_at: detail.created_at, completed_at: detail.completed_at,
    mismatch_codes: codes, missing_documents: missing as string[],
  } satisfies OriginCheckSummary)

  return (
    <>
      {judge && (
        <section className={`verdict tone-${tone}`} aria-label="판정 요약">
          <div className="verdict-stamp">
            <span className={`badge badge-lg stamp tone-${tone}`} data-tour="verdict">{judge.decision}</span>
          </div>
          <div className="verdict-body">
            <p className="verdict-label" title="AI의 1차 판정입니다. 최종 판단은 담당자가 합니다."><IconSparkle size={12} /> AI 1차 판정</p>
            <JudgeReason key={detail.id} reason={judge.reason} />
            {first?.todo && (
              <p className="verdict-next">
                <span className="verdict-next-label">먼저 할 일</span>
                <span className="verdict-next-text">
                  {first.text} → <b>{first.todo}</b>
                  {first.more > 0 && <span className="muted"> · 외 {first.more}건</span>}
                </span>
              </p>
            )}
          </div>
          <dl className="verdict-stats">
            <div><dt>불일치</dt><dd className="num">{codes.length}</dd></div>
            <div><dt>빠진 서류</dt><dd className="num">{missing.length}</dd></div>
          </dl>
        </section>
      )}

      {judge?.item_checks && judge.item_checks.length > 0 && <ItemChecks checks={judge.item_checks} />}

      <section className="section">
        <div className="section-head">
          <h2>확인할 항목 <span className="num muted">{codes.length + missing.length}</span></h2>
          {(codes.length > 0 || missing.length > 0) && (
            <button type="button" className="btn-sm btn-ghost" onClick={() => onOpen('law')}>
              <IconScale size={14} /> 전체 근거
            </button>
          )}
        </div>
        {codes.length === 0 && missing.length === 0 ? (
          <div className="issue-row is-ok">
            <span className="issue-icon text-tone-success"><IconCheck /></span>
            <span>서류 사이에 불일치가 없고 빠진 서류도 없습니다.</span>
          </div>
        ) : (
          <ul className="issue-list">
            {codes.map((c) => {
              // 라벨·단계는 API(mismatches[]) 값을 쓰고, 없을 때만 앱의 코드표로 채운다
              const m = detail.mismatches.find((x) => x.code === c)
              const stage = m?.stage ?? CODE_BASIS[c]?.stage ?? null
              const label = m?.label ?? CODE_BASIS[c]?.label ?? c
              const cmp = codeComparison(detail, c)
              return (
                <li key={c} className="issue-item">
                  <div className="issue-top">
                    <span className={`issue-dot tone-${stage ? STAGE_TONE[stage] : 'neutral'}`} aria-hidden />
                    <span className="issue-main">
                      <span className="issue-label">{label}</span>
                      {stage && <span className={`issue-stage text-tone-${STAGE_TONE[stage]}`}>{stage}단계 · {STAGE_LABEL[stage]}</span>}
                    </span>
                    <span className="issue-actions" data-tour="issue">
                      {cmp.length > 0 && (
                        <button type="button" className="btn-sm btn-ghost" onClick={() => onOpen('pdf', { code: c })}>
                          <IconFile size={14} /> 원본
                        </button>
                      )}
                      <button type="button" className="btn-sm btn-ghost" onClick={() => onOpen('law', { code: c })}>
                        <IconScale size={14} /> 근거
                      </button>
                    </span>
                  </div>
                  {cmp.length > 0 && (
                    <dl className="issue-cmp">
                      {cmp.map((v) => (
                        <div key={v.label} className="issue-cmp-row">
                          <dt><DocChip name={v.doc} /><span>{v.field}</span></dt>
                          <dd className="mono">{v.value}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                </li>
              )
            })}
            {missing.map((m) => {
              const ms = MISSING_DOC_BASIS[m]?.stage
              return (
                <li key={m} className="issue-item">
                  <div className="issue-top">
                    <span className={`issue-dot tone-${ms ? STAGE_TONE[ms] : 'warning'}`} aria-hidden />
                    <span className="issue-main">
                      <span className="issue-label">{m} 없음</span>
                      {ms && <span className={`issue-stage text-tone-${STAGE_TONE[ms]}`}>{ms}단계 · {STAGE_LABEL[ms]} · 누락 서류</span>}
                    </span>
                    <span className="issue-actions">
                      <button type="button" className="btn-sm btn-ghost" onClick={() => onOpen('law')}>
                        <IconScale size={14} /> 근거
                      </button>
                    </span>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <Extraction detail={detail} onOpenField={(p, id) => onOpen('pdf', { page: p, field: id })} />
    </>
  )
}

/* ───────── 서류 내용 (서류마다 읽은 값) ───────── */
type Meta = Record<string, unknown>

function Extraction({ detail, onOpenField }: { detail: OriginCheckDetail; onOpenField: (page: number, id: string) => void }) {
  const docs = DOC_DEFS.map((d) => ({ def: d, list: detail.extracted[d.node] ?? [], meta: (detail.extracted_meta as AgentExtractedMeta)[d.node] ?? [] }))
  const firstPresent = docs.find((d) => d.list.length > 0)?.def.node ?? DOC_DEFS[0].node
  const [active, setActive] = useState<ExtractNode>(firstPresent)
  const [lowOnly, setLowOnly] = useState(false)
  const cur = docs.find((d) => d.def.node === active) ?? docs[0]
  const lowCount = countLow(cur.meta as Meta[])

  return (
    <section className="section">
      <div className="section-head">
        <h2>서류 내용</h2>
        <label className="toggle-label">
          <input type="checkbox" checked={lowOnly} onChange={(e) => setLowOnly(e.target.checked)} />
          확인 필요만 <span className="num muted">{lowCount}</span>
        </label>
      </div>
      <div className="doc-tabs" role="tablist">
        {docs.map(({ def, list }) => (
          <button
            key={def.node}
            type="button"
            role="tab"
            aria-selected={active === def.node}
            className={`doc-tab${list.length === 0 ? ' is-empty' : ''}`}
            onClick={() => setActive(def.node)}
          >
            {def.name}
            {list.length > 1 && <span className="chip-count num">{list.length}</span>}
          </button>
        ))}
      </div>

      {cur.list.length === 0 ? (
        <p className="doc-empty">이 묶음에서 {cur.def.name}를 찾지 못했습니다.</p>
      ) : (
        cur.list.map((doc, di) => {
          const values = doc as unknown as Record<string, unknown>
          const meta = (cur.meta[di] ?? {}) as Meta
          const rows = cur.def.fields.filter(([k]) => !lowOnly || isLow(meta[k]))
          const items = (values.items as Record<string, unknown>[] | undefined) ?? []
          const itemMeta = (meta.items as Meta[] | undefined) ?? []
          return (
            <div key={di} className="doc-block">
              {cur.list.length > 1 && <p className="doc-block-title">{cur.def.name} {di + 1}</p>}
              <dl className="kv">
                {rows.map(([k, label]) => (
                  <KvRow key={k} field={k} label={label} value={values[k]} meta={meta[k] as FieldMeta | null | undefined} onOpenPage={(p) => onOpenField(p, fieldId(cur.def.node, di, k))} />
                ))}
                {rows.length === 0 && <p className="doc-empty">확인이 필요한 항목이 없습니다.</p>}
              </dl>
              {cur.def.items && items.length > 0 && !lowOnly && (
                <div className="items-wrap">
                  <table className="items">
                    <thead>
                      <tr>
                        <th>#</th>
                        {cur.def.items.map(([, l]) => <th key={l}>{l}</th>)}
                      </tr>
                    </thead>
                    <tbody>
                      {items.map((it, ii) => (
                        <tr key={ii}>
                          <td className="num muted">{ii + 1}</td>
                          {cur.def.items!.map(([k]) => {
                            const m = itemMeta[ii]?.[k] as FieldMeta | undefined
                            return (
                              <td
                                key={k}
                                className={`${isDataField(k, it[k]) ? 'mono ' : ''}${isLow(m) ? 'is-low ' : ''}${m && m.page > 0 ? 'is-link' : ''}`}
                                title={m && m.page > 0 ? '눌러서 원본 보기' : undefined}
                                onClick={m && m.page > 0 ? () => onOpenField(m.page, fieldId(cur.def.node, di, `items.${ii}.${k}`)) : undefined}
                              >
                                {COUNTRY_FIELDS.has(k) && it[k] ? <Country value={String(it[k])} mode="raw" /> : formatValue(it[k])}
                              </td>
                            )
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          )
        })
      )}
    </section>
  )
}

/** 번호·코드·수량·금액·날짜 → 고정폭 글꼴(디자인 시스템 '검인' 원칙 3: 번호는 타자기 글꼴로) */
const DATA_KEY = /(_no$|^hs_code$|_date$|^period_|amount|value|weight|quantity|cartons)/
function isDataField(key: string, value: unknown) {
  return typeof value === 'number' || DATA_KEY.test(key)
}

/** 다시 볼 값: 읽은 정확도가 '높음'(90%) 아래. 숫자 대신 '확인 필요' 표시만 한다 */
function isLow(m: unknown) {
  const s = (m as FieldMeta | null | undefined)?.score
  return s != null && s < CONFIDENCE_THRESHOLD.high
}
function countLow(metas: Meta[]) {
  let n = 0
  for (const m of metas) for (const v of Object.values(m)) if (v && typeof v === 'object' && !Array.isArray(v) && isLow(v)) n++
  return n
}

function KvRow({ field, label, value, meta, onOpenPage }: { field: string; label: string; value: unknown; meta: FieldMeta | null | undefined; onOpenPage: (p: number) => void }) {
  const low = isLow(meta)
  const shown = formatValue(value)
  const page = meta && meta.page > 0 ? meta.page : null
  // '확인 필요' 행은 줄 어디를 눌러도 원본이 열린다. 키보드는 오른쪽 p.N 버튼으로 같은 동작을 한다
  const openFromRow = low && page
    ? (e: MouseEvent) => {
        if ((e.target as HTMLElement).closest('button, a')) return // p.N 버튼은 자기 클릭으로 처리
        if (window.getSelection()?.toString()) return // 값을 드래그해 복사하는 중이면 열지 않는다
        onOpenPage(page)
      }
    : undefined
  return (
    <div className={`kv-row${low ? ' is-low' : ''}${openFromRow ? ' is-clickable' : ''}`} onClick={openFromRow} title={openFromRow ? '눌러서 원본에서 보기' : undefined}>
      <dt>{label}</dt>
      <dd>
        {COUNTRY_FIELDS.has(field) && shown !== '—' ? (
          <Country value={String(value)} mode="raw" />
        ) : (
          <span className={shown === '—' ? 'muted' : isDataField(field, value) ? 'mono' : undefined}>{shown}</span>
        )}
        {low && <span className="tag tag-warn">확인 필요</span>}
      </dd>
      <span className="kv-meta">
        {page && (
          <button type="button" className="page-link num" onClick={() => onOpenPage(page)} title="원본에서 보기">
            p.{page}
          </button>
        )}
      </span>
    </div>
  )
}

/* ───────── 처리 중 · 실패 ───────── */
const STEPS = ['PDF 접수', '서류 분류', '값 추출', '서류 대조', '판정']

function BusyState({ status, queue, demo }: { status: string; queue: number | null; demo: boolean }) {
  const at = status === 'queued' ? 0 : 2
  return (
    <section className="busy" aria-live="polite">
      <div className="busy-head">
        <span className="busy-icon"><span className="status-ring tone-info is-busy" /></span>
        <div>
          <p className="busy-title">{status === 'queued' ? '점검 순서를 기다리고 있어요' : 'AI가 서류를 점검하고 있어요'}</p>
          <p className="busy-sub">
            {queue != null && status === 'queued' && <><b>체험 대기열 {queue}번째</b> · </>}
            {demo
              ? '보통 1~2분 걸려요. 그사이 체험용 서류 창에서 다른 폴더도 올려 보세요.'
              : '다른 심사를 보고 있어도 점검은 계속됩니다. 끝나면 이 화면이 저절로 바뀌어요.'}
          </p>
        </div>
      </div>
      <ol className="steps">
        {STEPS.map((s, i) => (
          <li key={s} className={i < at ? 'is-done' : i === at ? 'is-current' : ''}>
            <span className="step-dot">{i < at ? <IconCheck size={11} /> : <span className="num">{i + 1}</span>}</span>
            <span className="step-name">{s}</span>
          </li>
        ))}
      </ol>
      <div className="progress"><span /></div>
    </section>
  )
}

function FailedState({ detail, retrying, disabled, onRetry, onReupload }: { detail: OriginCheckDetail; retrying: boolean; disabled: boolean; onRetry: () => void; onReupload: () => void }) {
  return (
    <section className="failed">
      <span className="failed-icon"><IconAlert size={20} /></span>
      <div className="failed-main">
        <p className="failed-title">점검을 마치지 못했습니다</p>
        <p className="failed-msg">{detail.error?.message ?? '알 수 없는 오류가 났습니다.'}</p>
        {detail.error?.code && <code className="code-tag">{detail.error.code}</code>}
        <div className="failed-actions">
          {/* 같은 파일로 다시 점검. 파일을 바꾸려면 새로 올린다 */}
          <button type="button" className="btn-sm btn-primary" onClick={onRetry} disabled={disabled}>
            <IconRefresh size={14} /> {retrying ? '다시 실행하는 중…' : '다시 실행'}
          </button>
          <button type="button" className="btn-sm" onClick={onReupload} disabled={disabled}>파일 다시 올리기</button>
        </div>
      </div>
    </section>
  )
}

/* ───────── 머리: 송장번호 · 거래 정보 ───────── */
const shortDate = (d: string) => (/^\d{4}-\d{2}-\d{2}/.test(d) ? `${d.slice(5, 7)}.${d.slice(8, 10)}` : d)

/** 상세 머리·경로 표시 이름: 바이어 · 대표 품목 (없으면 관리번호·메모 → 파일명) */
function displayName(detail: OriginCheckDetail) {
  const deal = dealInfo(detail)
  return [shortCompany(deal.buyer), deal.goods].filter(Boolean).join(' · ') || memoOf(detail.title, detail.file_name) || detail.file_name
}

function DetailHeader({ detail, doneAt }: { detail: OriginCheckDetail; doneAt: string | null }) {
  const deal = dealInfo(detail)
  const memo = memoOf(detail.title, detail.file_name)
  const meta = [deal.agreement, deal.shippedOn && `${shortDate(deal.shippedOn)} 선적`, deal.amount].filter(Boolean)
  const hasFiles = (detail.files?.length ?? 0) > 0
  return (
    <header className="detail-head">
      <h1 className="detail-title">{displayName(detail)}</h1>
      {(deal.invoiceNo || deal.destination || meta.length > 0) && (
        <p className="detail-parties">
          {deal.invoiceNo && <span className="mono">{deal.invoiceNo}</span>}
          {deal.destination && <span><Country value={deal.destination} /></span>}
          {meta.map((m) => <span key={m as string}>{m}</span>)}
        </p>
      )}
      <p className="detail-sub">
        {doneAt && <span className="detail-done"><IconCheck size={13} /> {formatDateTime(doneAt)} 완료 처리</span>}
        {memo && <span className="detail-memo">{memo}</span>}
        {!hasFiles && <span><IconFile size={13} /> {detail.file_name}</span>}
        <span className="num" title={`등록 ${formatDateTime(detail.created_at)}${detail.completed_at ? ` · 점검 ${formatDateTime(detail.completed_at)}` : ''}`}>
          {formatDateTime(detail.created_at).slice(0, 10)} 등록
        </span>
      </p>
    </header>
  )
}

/** 올린 파일별로 AI 가 찾은 서류 (기획서 3단계: 사람이 확인). 누르면 원본 PDF 의 그 쪽으로 */
function FileList({ detail, onOpenPage }: { detail: OriginCheckDetail; onOpenPage: (page: number) => void }) {
  const files = detail.files ?? []
  const pages = pageTypes(detail)
  const done = detail.status === 'completed'
  return (
    <section className="section">
      <div className="section-head">
        <h2>올린 파일 <span className="num muted">{files.length}</span></h2>
      </div>
      <ul className="props-files">
        {files.map((f) => {
          const types = done ? fileTypes(f, pages) : []
          const range = f.page_from === f.page_to ? `p.${f.page_from}` : `p.${f.page_from}–${f.page_to}`
          return (
            <li key={`${f.page_from}-${f.name}`}>
              <button type="button" className="props-file" onClick={() => onOpenPage(f.page_from)} title={`${f.name} · 원본에서 보기`}>
                <span className="props-file-icon" aria-hidden><IconFile size={15} /></span>
                <span className="props-file-name">{f.name}</span>
                <span className="props-file-meta">
                  <span className="mono">{range}</span>
                  {types.map((t) => <DocChip key={t} name={t} />)}
                  {done && types.length === 0 && <span className="muted">분류 안 됨</span>}
                  {!done && f.hint_type && <DocChip name={f.hint_type} title={`올릴 때 짐작: ${f.hint_type}`} />}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    </section>
  )
}

function DetailSkeleton() {
  return (
    <div className="detail-grid">
      <div className="detail-content">
        <span className="skeleton-line" style={{ width: 280, height: 28, marginTop: 56 }} />
        <span className="skeleton-line" style={{ width: 200, marginTop: 12 }} />
        <span className="skeleton-block" style={{ height: 120, marginTop: 24 }} />
        <span className="skeleton-block" style={{ height: 180, marginTop: 16 }} />
      </div>
    </div>
  )
}
