import { useCallback, useEffect, useRef, useState, type DragEvent, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { originChecksApi } from '../api/originChecks'
import { DOC_TYPES, MISSING_EFFECT, hintFromName, type DocType } from '../data/docTypes'
import { countPages } from '../utils/pdfjs'
import { isDemoDrag } from '../demo/demoDrag'
import { DocChip } from './DocChip'
import { IconAlert, IconCheck, IconFile, IconUpload, IconX } from './icons'

/**
 * M-03 심사 생성 (기획서 '서류 업로드 개선').
 * 한 선적 건의 PDF 를 여러 개 받는다(합친 PDF 1개도 됨). 파일명으로 서류 종류를 짐작해 체크리스트를 채우고,
 * 정식 분류는 점검할 때 AI 워크플로가 한다. 필수 서류가 빠져도 막지 않고 한 줄로 알려 준다.
 */
const MAX_FILES = 20
const MAX_TOTAL_BYTES = 100 * 1024 * 1024

interface Item {
  key: string
  file: File
  pages: number | null | 'loading'
  hint: DocType | null
}

export interface IncomingFiles {
  files: File[]
  nonce: number
  /** 파일 이름 → 서류 종류 (체험용 서류처럼 미리 아는 경우). 없으면 이름으로 짐작한다 */
  hints?: Record<string, string | null>
}

const knownHint = (v: string | null | undefined) => (DOC_TYPES as readonly string[]).includes(v ?? '') ? (v as DocType) : null
const isPdf = (f: File) => f.type === 'application/pdf' || /\.pdf$/i.test(f.name)
const mb = (n: number) => `${(n / 1024 / 1024).toFixed(n < 10 * 1024 * 1024 ? 1 : 0)}MB`

/** 만든 심사 (상세로 넘어가고 좌측 목록에 바로 넣는 데 쓴다) */
export interface CreatedCheck {
  id: number
  title: string
  fileName: string
}

export function NewCheckModal({ onClose, onCreated, incoming }: { onClose: () => void; onCreated: (c: CreatedCheck) => void; incoming?: IncomingFiles | null }) {
  const [items, setItems] = useState<Item[]>([])
  const [notes, setNotes] = useState<string[]>([])
  const [memo, setMemo] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [over, setOver] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const itemsRef = useRef(items)
  useEffect(() => {
    itemsRef.current = items
  }, [items])

  const add = useCallback((list: File[], hints?: Record<string, string | null>) => {
    const current = itemsRef.current
    const rejected: string[] = []
    const accepted: Item[] = []
    let total = current.reduce((n, it) => n + it.file.size, 0)
    for (const f of list) {
      if (!isPdf(f)) rejected.push(`${f.name}: PDF가 아니에요`)
      else if (f.size === 0) rejected.push(`${f.name}: 빈 파일이에요`)
      else if (current.length + accepted.length >= MAX_FILES) rejected.push(`${f.name}: 한 번에 ${MAX_FILES}개까지예요`)
      else if (total + f.size > MAX_TOTAL_BYTES) rejected.push(`${f.name}: 합계 100MB를 넘어요`)
      else if (current.some((it) => it.file.name === f.name && it.file.size === f.size)) rejected.push(`${f.name}: 이미 올렸어요`)
      else {
        total += f.size
        accepted.push({ key: `${f.name}-${f.size}-${f.lastModified}-${Math.random()}`, file: f, pages: 'loading', hint: knownHint(hints?.[f.name]) ?? hintFromName(f.name) })
      }
    }
    setNotes(rejected)
    setError(null)
    if (accepted.length === 0) return
    setItems((prev) => [...prev, ...accepted])
    for (const it of accepted) {
      countPages(it.file).then((pages) => setItems((prev) => prev.map((p) => (p.key === it.key ? { ...p, pages } : p))))
    }
  }, [])

  // 화면 어디든 끌어다 놓은 파일 (Layout 이 넘겨준다). 같은 묶음을 두 번 넣지 않게 nonce 로 거른다
  const handled = useRef<number | null>(null)
  useEffect(() => {
    if (!incoming?.files.length || handled.current === incoming.nonce) return
    handled.current = incoming.nonce
    add(incoming.files, incoming.hints)
  }, [incoming, add])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    setOver(false)
    // 체험용 서류 창에서 끌어 온 묶음은 PDF 를 받아야 하므로 화면 전체 처리(Layout)로 넘긴다
    if (isDemoDrag(e.nativeEvent)) return
    e.stopPropagation()
    add(Array.from(e.dataTransfer.files))
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (items.length === 0) {
      setError('PDF를 하나 이상 올리세요.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const created = await originChecksApi.create(items.map((it) => it.file), memo.trim() || undefined, items.map((it) => it.hint))
      // 서버와 같은 규칙의 이름 (목록을 다시 받기 전 좌측에 바로 보일 값)
      const first = items[0].file.name
      const fileName = items.length > 1 ? `${first.replace(/\.pdf$/i, '')} 외 ${items.length - 1}건.pdf` : first
      onCreated({ id: created.id, title: memo.trim() || fileName, fileName })
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '올리지 못했습니다. 다시 시도하세요.')
      setBusy(false)
    }
  }

  // 체크리스트: 있음 / 점검 때 확인(짐작 못 한 파일이 있을 때) / 없음 / 선택
  const unknown = items.some((it) => it.hint == null)
  const status = (t: DocType) => {
    const files = items.filter((it) => it.hint === t)
    if (files.length) return { kind: 'ok' as const, files }
    if (unknown) return { kind: 'maybe' as const, files }
    return { kind: MISSING_EFFECT[t] ? ('missing' as const) : ('optional' as const), files }
  }
  const missing = DOC_TYPES.filter((t) => status(t).kind === 'missing')
  const effect = missing.some((t) => MISSING_EFFECT[t] === '보완 요청') ? '보완 요청' : '검토 필요'
  const totalPages = items.reduce((n, it) => n + (typeof it.pages === 'number' ? it.pages : 0), 0)
  const found = DOC_TYPES.filter((t) => status(t).kind === 'ok').length
  // 진행 단계 표시 (Page Flows 식): 파일 고르기 → 서류 확인 → 점검 시작
  const step = busy ? 2 : items.length > 0 ? 1 : 0

  return (
    <div className="modal-backdrop" onClick={() => !busy && onClose()}>
      <form className="modal upload" onClick={(e) => e.stopPropagation()} onSubmit={submit} aria-label="심사 생성">
        <header className="upload-head">
          <div className="upload-title">
            <h2>심사 생성</h2>
            <p className="upload-sub">한 선적 건의 서류 PDF를 올리면 AI가 나누고, 읽고, 맞대어 봅니다.</p>
          </div>
          <ol className="upload-steps" aria-label="진행 단계">
            {['파일 올리기', '서류 확인', '점검 시작'].map((label, i) => (
              <li key={label} className={i < step ? 'is-done' : i === step ? 'is-current' : undefined} aria-current={i === step ? 'step' : undefined}>
                <span className="upload-step-no">{i < step ? <IconCheck size={11} /> : i + 1}</span>
                {label}
              </li>
            ))}
          </ol>
          <button type="button" className="btn-icon btn-ghost btn-sm upload-close" onClick={onClose} aria-label="닫기" disabled={busy}>
            <IconX />
          </button>
        </header>

        <div className="upload-body">
          <div className="upload-main">
            <label
              className={`dropzone${over ? ' is-over' : ''}${items.length ? ' is-compact' : ''}`}
              onDragOver={(e) => {
                e.preventDefault()
                e.stopPropagation()
                setOver(true)
              }}
              onDragLeave={() => setOver(false)}
              onDrop={onDrop}
            >
              <input
                ref={inputRef}
                id="upload-input"
                type="file"
                accept="application/pdf,.pdf"
                multiple
                hidden
                onChange={(e) => {
                  add(Array.from(e.target.files ?? []))
                  e.target.value = ''
                }}
              />
              <span className="dropzone-icon"><IconUpload size={items.length ? 16 : 22} /></span>
              <span className="dropzone-text">
                <span className="dropzone-main">{items.length ? '파일 더 넣기' : '파일을 여기에 놓거나 눌러서 고르기'}</span>
                <span className="dropzone-sub">PDF · 최대 20개, 합계 100MB까지 · 합친 PDF 1개도 됨</span>
              </span>
              {!items.length && <span className="dropzone-btn" aria-hidden>파일 고르기</span>}
            </label>

            {notes.length > 0 && (
              <ul className="upload-notes" role="alert">
                {notes.map((n) => <li key={n}>{n}</li>)}
              </ul>
            )}

            {items.length > 0 && (
              <ol className="upload-files" aria-label="올릴 파일 (이 순서대로 이어 붙여 점검)">
                {items.map((it) => (
                  <li key={it.key} className="upload-file">
                    <span className="upload-file-icon" aria-hidden><IconFile size={15} /></span>
                    <span className="upload-file-name" title={it.file.name}>{it.file.name}</span>
                    <span className="upload-file-meta num">
                      {it.pages === 'loading' ? '…' : it.pages == null ? '열 수 없음' : `${it.pages}쪽`} · {mb(it.file.size)}
                    </span>
                    <select
                      className={`hint-select${it.hint ? '' : ' is-auto'}`}
                      value={it.hint ?? ''}
                      aria-label={`${it.file.name} 서류 종류`}
                      onChange={(e) => {
                        const v = (e.target.value || null) as DocType | null
                        setItems((prev) => prev.map((p) => (p.key === it.key ? { ...p, hint: v } : p)))
                      }}
                    >
                      <option value="">자동 분류</option>
                      {DOC_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                    <button
                      type="button"
                      className="btn-icon btn-ghost btn-sm"
                      aria-label={`${it.file.name} 빼기`}
                      onClick={() => setItems((prev) => prev.filter((p) => p.key !== it.key))}
                    >
                      <IconX size={14} />
                    </button>
                  </li>
                ))}
              </ol>
            )}

            <label className="upload-memo">
              <span>관리번호·메모 (선택)</span>
              <input id="upload-memo" value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="PO-2611-003" maxLength={200} />
            </label>
          </div>

          <aside className="upload-check" aria-label="서류 체크리스트">
            <div className="upload-check-head">
              <p className="upload-check-title">서류 체크리스트</p>
              <span className="upload-check-count num">{found} / {DOC_TYPES.length}</span>
            </div>
            <div className="upload-check-bar" aria-hidden><span style={{ width: `${(found / DOC_TYPES.length) * 100}%` }} /></div>
            <ul>
              {DOC_TYPES.map((t) => {
                const s = status(t)
                return (
                  <li key={t} className={`is-${s.kind}`}>
                    <DocChip name={t} missing={s.kind === 'missing'} />
                    <span className="upload-check-name">{t}</span>
                    <span className="upload-check-note">
                      {s.kind === 'ok' ? (s.files.length > 1 ? `${s.files.length}개` : '있음') : s.kind === 'maybe' ? '점검 때 확인' : s.kind === 'optional' ? '선택' : ''}
                    </span>
                  </li>
                )
              })}
            </ul>
            {items.length > 0 && (
              <p className="upload-check-foot num">
                {items.length}개 파일{totalPages ? ` · ${totalPages}쪽` : ''}
              </p>
            )}
          </aside>
        </div>

        <footer className="upload-foot">
          <p className="upload-warn" aria-live="polite">
            {error ? (
              <span className="error">{error}</span>
            ) : items.length > 0 && missing.length > 0 ? (
              <>
                <IconAlert size={14} />
                <span>{missing.join(', ')} 없이 점검하면 '{effect}'{effect === '보완 요청' ? '으로' : '로'} 나올 수 있어요.</span>
              </>
            ) : null}
          </p>
          <button type="button" onClick={onClose} disabled={busy}>취소</button>
          <button type="submit" className="btn-primary" disabled={busy}>
            {busy ? '올리는 중…' : '점검 시작'}
          </button>
        </footer>
      </form>
    </div>
  )
}
