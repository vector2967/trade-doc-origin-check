import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { originChecksApi } from '../api/originChecks'
import type { AgentFollowUp, OriginCheckDetail } from '../api/types'
import { buildHighlights, CODE_FIELDS } from '../data/highlights'
import { CODE_BASIS, LAWS, MISSING_DOC_BASIS, STAGE_LABEL } from '../data/legalBasis'
import type { PanelKind } from '../checks/usePanel'
import { IconCheck, IconCopy, IconExternal, IconFile, IconMail, IconRefresh, IconScale, IconX } from './icons'
import { Markdown } from './Markdown'
import { markdownToEmail } from '../utils/mailFormat'
import { PdfViewer } from './PdfViewer'
import { FOLLOW_UP_TITLE } from '../data/followUp'

const PANEL_META: Record<PanelKind, { title: string; icon: ReactNode }> = {
  law: { title: '법령 확인', icon: <IconScale /> },
  pdf: { title: '원본 PDF', icon: <IconFile /> },
  mail: { title: '후속 문서', icon: <IconMail /> },
}

const STAGE_TONE = { 1: 'danger', 2: 'warning', 3: 'caution' } as const


/**
 * 오른쪽 사이드 패널 껍데기. 열고 닫을 때 폭이 늘었다 줄며 미끄러진다.
 * 닫히는 동안에도 내용이 사라지지 않게 마지막 패널을 기억해 둔다.
 */
export function SidePanel({ kind, onClose, children }: { kind: PanelKind | null; onClose: () => void; children: (k: PanelKind) => ReactNode }) {
  const [last, setLast] = useState<PanelKind | null>(kind)
  if (kind && kind !== last) setLast(kind)
  const shown = kind ?? last
  return (
    <aside className={`side-panel${kind ? ' is-open' : ''}${shown === 'pdf' ? ' is-wide' : ''}`} aria-hidden={!kind} aria-label={shown ? PANEL_META[shown].title : undefined}>
      {shown && (
        <div className="side-panel-card" inert={!kind} data-tour={kind === 'pdf' ? 'pdf' : undefined}>
          <header className="side-panel-head">
            <span className="side-panel-title">
              {PANEL_META[shown].icon}
              {PANEL_META[shown].title}
            </span>
            <button type="button" className="btn-icon btn-ghost btn-sm" onClick={onClose} aria-label="패널 닫기" title="닫기 (Esc)">
              <IconX />
            </button>
          </header>
          <div className="side-panel-body">{children(shown)}</div>
        </div>
      )}
    </aside>
  )
}

/* ───────── 법령 확인 ───────── */
export function LawPanel({ detail, focusCode, onShowInPdf }: { detail: OriginCheckDetail; focusCode: string | null; onShowInPdf: (code: string) => void }) {
  const codes = detail.judge?.mismatch_codes ?? detail.mismatches.map((m) => m.code)
  const missing = detail.judge?.missing_documents ?? []
  const focusRef = useRef<HTMLElement>(null)

  useEffect(() => {
    focusRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [focusCode])

  return (
    <div className="law">

      {codes.length === 0 && missing.length === 0 && (
        <div className="law-card">
          <p className="law-card-title"><IconCheck /> 걸린 항목이 없습니다</p>
          <LawLinks refs={[{ id: 'ftaAct', topic: '협정관세 적용 신청 · 원산지증빙서류' }, { id: 'aseanRoo', topic: '원산지 규정' }]} />
        </div>
      )}

      {codes.map((code) => {
        const b = CODE_BASIS[code]
        const m = detail.mismatches.find((x) => x.code === code)
        const label = m?.label ?? b?.label ?? code
        const stage = m?.stage ?? b?.stage ?? null
        const focused = code === focusCode
        return (
          <section key={code} ref={focused ? focusRef : undefined} className={`law-card${focused ? ' is-focus' : ''}`}>
            <div className="law-card-head">
              {stage && <span className={`badge tone-${STAGE_TONE[stage]}`}>{stage}단계 · {STAGE_LABEL[stage]}</span>}
              <code className="code-tag">{code}</code>
            </div>
            <p className="law-card-title">{label}</p>
            {CODE_FIELDS[code] && (
              <button type="button" className="btn-sm law-pdf-btn" onClick={() => onShowInPdf(code)}>
                <IconFile size={14} /> 원본에서 위치 보기
              </button>
            )}
            {b ? (
              <dl className="law-dl">
                <dt>판정 규칙</dt>
                <dd>{b.rule}</dd>
                <dt>대조할 곳</dt>
                <dd className="law-chips">{b.check.map((c) => <span key={c} className="tag">{c}</span>)}</dd>
                <dt>처리 방법</dt>
                <dd>{b.resolution}</dd>
                {b.laws.length > 0 && (
                  <>
                    <dt>관련 법령</dt>
                    <dd><LawLinks refs={b.laws} /></dd>
                  </>
                )}
              </dl>
            ) : (
              <p className="secondary">이 코드의 근거 정보가 아직 정리되지 않았습니다.</p>
            )}
          </section>
        )
      })}

      {missing.map((doc) => {
        const m = MISSING_DOC_BASIS[doc]
        return (
          <section key={doc} className="law-card">
            <div className="law-card-head">
              {m && <span className={`badge tone-${STAGE_TONE[m.stage]}`}>{m.stage}단계 · {STAGE_LABEL[m.stage]}</span>}
              <span className="tag">누락 서류</span>
            </div>
            <p className="law-card-title">{doc} 없음</p>
            {m && <p className="secondary">{m.resolution}</p>}
            <LawLinks refs={[{ id: 'ftaAct', topic: '원산지증빙서류의 구비·보관' }]} />
          </section>
        )
      })}

    </div>
  )
}

function LawLinks({ refs }: { refs: { id: keyof typeof LAWS; topic: string; article?: string }[] }) {
  return (
    <ul className="law-links">
      {refs.map((r, i) => {
        const law = LAWS[r.id]
        return (
          <li key={i}>
            <a href={law.url} target="_blank" rel="noreferrer" title={law.name}>
              <span className="law-link-name">{law.short}{r.article ? ` ${r.article}` : ''}</span>
              <IconExternal size={13} />
            </a>
            <span className="law-link-topic">{r.topic}</span>
          </li>
        )
      })}
    </ul>
  )
}

/* ───────── 원본 PDF ─────────
 * 박스는 불일치(빨강) · 신뢰도 낮음(주황) · 중간(노랑)만 보인다. 신뢰도 높은 항목은 눌러서 고른 경우에만 파란 박스로 보인다. */
export function PdfPanel({ detail, page, field, code, focusKey }: { detail: OriginCheckDetail; page: number; field: string | null; code: string | null; focusKey: number }) {
  const [data, setData] = useState<ArrayBuffer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const highlights = useMemo(() => buildHighlights(detail), [detail])

  useEffect(() => {
    let cancelled = false
    originChecksApi
      .fileBlob(detail.id)
      .then((blob) => blob.arrayBuffer())
      .then((buf) => !cancelled && setData(buf))
      .catch((e: unknown) => !cancelled && setError(pdfLoadMessage(e)))
    return () => {
      cancelled = true
    }
  }, [detail.id, attempt])

  return (
    <div className="pdf">
      <div className="pdf-bar">
        <span className="pdf-name" title={detail.file_name}>{detail.file_name}</span>
      </div>
      {error && (
        <div className="pdf-error" role="alert">
          <p className="error">{error}</p>
          <button type="button" className="btn-sm" onClick={() => {
            setError(null)
            setAttempt((n) => n + 1)
          }}>
            <IconRefresh size={14} /> 다시 시도
          </button>
        </div>
      )}
      {!error && !data && <div className="pdf-frame skeleton-block" />}
      {data && (
        <div className="pdf-frame">
          <PdfViewer data={data} highlights={highlights} focusId={field} focusCode={code} focusKey={focusKey} initialPage={page} />
        </div>
      )}
    </div>
  )
}

/** 원본 PDF 를 못 받았을 때: 서버에 파일이 없는 경우와 연결 문제를 나눠 알려 준다 */
function pdfLoadMessage(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 404) return '서버에 원본 PDF 파일이 없습니다. 파일을 다시 올려 주세요.'
    if (e.status >= 500) return '서버 오류로 원본 PDF 를 불러오지 못했습니다. 잠시 뒤 다시 시도해 주세요.'
    return e.message
  }
  return '서버에 연결하지 못해 원본 PDF 를 불러오지 못했습니다. 연결을 확인하고 다시 시도해 주세요.'
}

/* ───────── 후속 문서 (메일 초안 등) ───────── */

/** 서식 있는 복사. 클립보드 API 가 막힌 환경에서는 화면 밖에 그려 선택-복사하는 옛 방식으로 */
async function copyRich(html: string, plain: string): Promise<boolean> {
  try {
    await navigator.clipboard.write([
      new ClipboardItem({
        'text/html': new Blob([html], { type: 'text/html' }),
        'text/plain': new Blob([plain], { type: 'text/plain' }),
      }),
    ])
    return true
  } catch {
    const box = document.createElement('div')
    box.innerHTML = html // 우리가 만든 HTML (본문 글자는 모두 이스케이프됨)
    box.style.cssText = 'position:fixed;left:-9999px;top:0;'
    document.body.appendChild(box)
    const range = document.createRange()
    range.selectNodeContents(box)
    const sel = window.getSelection()
    sel?.removeAllRanges()
    sel?.addRange(range)
    const ok = document.execCommand('copy')
    sel?.removeAllRanges()
    box.remove()
    return ok
  }
}
export function MailPanel({ followUp }: { followUp: AgentFollowUp | null }) {
  const [copied, setCopied] = useState(false)
  if (!followUp) return <p className="panel-intro">이 심사에는 후속 문서가 없습니다.</p>
  const copy = async () => {
    try {
      // 메일에 붙여 넣으면 제목·목록·굵게가 살아 있는 서식으로, 서식을 못 받는 곳에는 깔끔한 글로
      const { html, plain } = markdownToEmail(followUp.text)
      if (!(await copyRich(html, plain))) return
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      /* 클립보드 권한 없음 */
    }
  }
  return (
    <div className="mail">
      <div className="mail-bar">
        <span className="mail-kind">{FOLLOW_UP_TITLE[followUp.kind] ?? followUp.kind}</span>
        <button type="button" className="btn-sm" onClick={copy}>
          {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
          {copied ? '복사됨' : '전체 복사'}
        </button>
      </div>
      <Markdown text={followUp.text} />
    </div>
  )
}
