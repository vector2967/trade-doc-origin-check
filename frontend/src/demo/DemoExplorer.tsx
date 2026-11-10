import { useCallback, useEffect, useRef, useState, type DragEvent as ReactDragEvent, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react'
import type { DemoSampleSet } from '../api/types'
import { IconChevronRight, IconFile, IconFolder, IconMinus, IconX } from '../components/icons'
import { DEMO_MIME, setDemoDragImage, type DemoDragPayload } from './demoDrag'
import { useDemoSamples } from './useDemoSamples'

/**
 * 체험용 서류 창 (파일 탐색기 느낌). 화면 안에 떠 있는 창이며 브라우저 새 창이 아니다.
 * - 왼쪽: 선적 건 폴더 / 오른쪽: 그 폴더의 PDF. 폴더를 열면 전부 선택된 상태로 시작한다
 * - 고르기: 클릭 · Shift+클릭(범위) · Cmd/Ctrl+클릭(더하기·빼기) · Cmd/Ctrl+A · 머리 체크박스
 * - 끌기: 고른 파일(또는 왼쪽 폴더째)을 화면으로 끌어다 놓으면 Layout 이 받아 새 점검 창을 연다
 * - 끌 수 없는 환경(터치·키보드)은 [점검 시작] 버튼으로 같은 일을 한다. 좁은 화면에서는 아래 시트가 된다
 */
const POS_KEY = 'origin-check.demo.explorer-pos'
const W = 600
const H = 380

type Pos = { x: number; y: number }

function initialPos(): Pos {
  try {
    const v = JSON.parse(sessionStorage.getItem(POS_KEY) || 'null') as Pos | null
    if (v && Number.isFinite(v.x) && Number.isFinite(v.y)) return clamp(v)
  } catch {
    /* 저장소를 못 쓰면 기본 자리 */
  }
  return clamp({ x: window.innerWidth - W - 24, y: window.innerHeight - H - 24 })
}
function clamp(p: Pos): Pos {
  return {
    x: Math.min(Math.max(8, p.x), Math.max(8, window.innerWidth - W - 8)),
    y: Math.min(Math.max(8, p.y), Math.max(8, window.innerHeight - 48)),
  }
}

const kb = (n: number) => `${Math.max(1, Math.round(n / 1024)).toLocaleString()}KB`

export function DemoExplorer({ onMinimize, onClose, onStart, showHint }: {
  onMinimize: () => void
  onClose: () => void
  onStart: (p: DemoDragPayload) => void
  /** 아직 한 번도 끌어 놓지 않았으면 위쪽에 안내 띠 */
  showHint: boolean
}) {
  const { sets, error, retry } = useDemoSamples()
  const [setId, setSetId] = useState<string | null>(null)
  // null = 지금 폴더의 파일 전부 (폴더를 열면 전부 선택된 상태로 시작)
  const [picks, setSelected] = useState<Set<string> | null>(null)
  const [cursor, setCursor] = useState(0)
  const anchor = useRef(0)
  const [pos, setPos] = useState<Pos>(initialPos)
  const listRef = useRef<HTMLDivElement>(null)

  const current: DemoSampleSet | null = sets?.find((s) => s.id === setId) ?? sets?.[0] ?? null
  const files = current?.files ?? []
  const selected = picks ?? new Set(files.map((f) => f.name))

  const openSet = useCallback((s: DemoSampleSet) => {
    setSetId(s.id)
    setSelected(null)
    setCursor(0)
    anchor.current = 0
  }, [])

  // 창 옮기기 (제목 줄을 잡고)
  const drag = useRef<{ dx: number; dy: number } | null>(null)
  const onTitleDown = (e: PointerEvent) => {
    if ((e.target as HTMLElement).closest('button') || window.innerWidth < 768) return
    drag.current = { dx: e.clientX - pos.x, dy: e.clientY - pos.y }
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }
  const onTitleMove = (e: PointerEvent) => {
    if (!drag.current) return
    setPos(clamp({ x: e.clientX - drag.current.dx, y: e.clientY - drag.current.dy }))
  }
  const onTitleUp = () => {
    if (!drag.current) return
    drag.current = null
    try {
      sessionStorage.setItem(POS_KEY, JSON.stringify(pos))
    } catch {
      /* noop */
    }
  }
  useEffect(() => {
    const onResize = () => setPos((p) => clamp(p))
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])

  // 고르기
  const clickRow = (i: number, e: MouseEvent) => {
    const name = files[i].name
    setCursor(i)
    if (e.shiftKey) {
      const [a, b] = [Math.min(anchor.current, i), Math.max(anchor.current, i)]
      setSelected(new Set(files.slice(a, b + 1).map((f) => f.name)))
      return
    }
    anchor.current = i
    if (e.metaKey || e.ctrlKey) {
      setSelected((prev) => {
        const next = new Set(prev ?? files.map((f) => f.name))
        if (next.has(name)) next.delete(name)
        else next.add(name)
        return next
      })
      return
    }
    setSelected(new Set([name]))
  }
  const toggle = (name: string) =>
    setSelected((prev) => {
      const next = new Set(prev ?? files.map((f) => f.name))
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  const allOn = files.length > 0 && files.every((f) => selected.has(f.name))
  const toggleAll = () => setSelected(allOn ? new Set() : new Set(files.map((f) => f.name)))

  const picked = files.filter((f) => selected.has(f.name)).map((f) => f.name)
  const start = () => current && picked.length > 0 && onStart({ setId: current.id, files: picked })

  // 끌기: 고르지 않은 줄을 잡으면 그 줄만 (탐색기와 같은 동작)
  const dragFiles = (e: ReactDragEvent, names: string[], label: string) => {
    if (!current || names.length === 0) return
    const payload: DemoDragPayload = { setId: current.id, files: names }
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData(DEMO_MIME, JSON.stringify(payload))
    e.dataTransfer.setData('text/plain', names.join('\n'))
    setDemoDragImage(e, names.length, label)
  }
  const onRowDragStart = (e: ReactDragEvent, name: string) => {
    let names = picked
    if (!selected.has(name)) {
      names = [name]
      setSelected(new Set([name]))
    }
    dragFiles(e, names, names.length > 1 ? `${names.length}개 파일` : name)
  }
  const onFolderDragStart = (e: ReactDragEvent, s: DemoSampleSet) => {
    if (s.id !== current?.id) openSet(s)
    const payload: DemoDragPayload = { setId: s.id, files: s.files.map((f) => f.name) }
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData(DEMO_MIME, JSON.stringify(payload))
    e.dataTransfer.setData('text/plain', s.name)
    setDemoDragImage(e, s.files.length, s.name)
  }

  // 키보드: ↑↓ 이동 · Space 고르기 · Enter 시작 · Cmd/Ctrl+A 전체 · Esc 최소화
  const onListKey = (e: KeyboardEvent) => {
    if (!files.length) return
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const i = Math.min(files.length - 1, Math.max(0, cursor + (e.key === 'ArrowDown' ? 1 : -1)))
      setCursor(i)
      if (e.shiftKey) setSelected((prev) => new Set(prev ?? files.map((f) => f.name)).add(files[i].name))
    } else if (e.key === ' ') {
      e.preventDefault()
      toggle(files[cursor].name)
    } else if (e.key === 'Enter') {
      e.preventDefault()
      start()
    } else if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'a') {
      e.preventDefault()
      setSelected(new Set(files.map((f) => f.name)))
    }
  }
  const onWindowKey = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.stopPropagation()
      onMinimize()
    }
  }

  return (
    <section
      className="demo-explorer"
      style={{ left: pos.x, top: pos.y }}
      role="dialog"
      aria-label="체험용 서류"
      onKeyDown={onWindowKey}
    >
      <header className="dx-title" onPointerDown={onTitleDown} onPointerMove={onTitleMove} onPointerUp={onTitleUp} onPointerCancel={onTitleUp}>
        <span className="dx-title-name"><IconFolder size={15} /> 체험용 서류</span>
        <span className="dx-title-btns">
          <button type="button" className="dx-win-btn" onClick={onMinimize} aria-label="최소화" title="최소화"><IconMinus size={14} /></button>
          <button type="button" className="dx-win-btn is-close" onClick={onClose} aria-label="닫기" title="닫기"><IconX size={14} /></button>
        </span>
      </header>

      <div className="dx-path">
        <span>체험용 서류</span>
        {current && <><IconChevronRight size={12} /><b>{current.name}</b></>}
      </div>

      {showHint && (
        <p className="dx-hint">
          파일을 골라 <b>왼쪽 화면으로 끌어다 놓아</b> 보세요. 일부만 빼고 올려도 돼요(예: 원산지확인서 빼기).
        </p>
      )}

      <div className="dx-body">
        <nav className="dx-nav" aria-label="폴더">
          {sets?.map((s) => (
            <button
              key={s.id}
              type="button"
              className={`dx-folder${s.id === current?.id ? ' is-active' : ''}`}
              onClick={() => openSet(s)}
              draggable
              onDragStart={(e) => onFolderDragStart(e, s)}
              title={`${s.name} · 폴더째 끌어다 놓을 수 있어요`}
            >
              <IconFolder size={15} />
              <span>{s.name}</span>
            </button>
          ))}
        </nav>

        <div className="dx-files" ref={listRef} role="grid" aria-multiselectable tabIndex={0} onKeyDown={onListKey} aria-label={current ? `${current.name} 파일` : '파일'}>
          {error ? (
            <div className="dx-empty">
              <p>체험용 서류를 불러오지 못했어요.</p>
              <button type="button" className="btn-sm" onClick={retry}>다시 시도</button>
            </div>
          ) : !sets ? (
            <div className="dx-empty"><p className="muted">불러오는 중…</p></div>
          ) : (
            <>
              <div className="dx-row dx-head" role="row">
                <span role="columnheader"><input type="checkbox" checked={allOn} onChange={toggleAll} aria-label="전체 선택" /></span>
                <span role="columnheader">이름</span>
                <span role="columnheader" className="dx-type">종류</span>
                <span role="columnheader" className="dx-size">크기</span>
              </div>
              {files.map((f, i) => {
                const on = selected.has(f.name)
                return (
                  <div
                    key={f.name}
                    role="row"
                    aria-selected={on}
                    className={`dx-row${on ? ' is-on' : ''}${i === cursor ? ' is-cursor' : ''}`}
                    onClick={(e) => clickRow(i, e)}
                    onDoubleClick={start}
                    draggable
                    onDragStart={(e) => onRowDragStart(e, f.name)}
                  >
                    <span role="gridcell" onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" checked={on} onChange={() => toggle(f.name)} aria-label={`${f.name} 선택`} tabIndex={-1} />
                    </span>
                    <span role="gridcell" className="dx-name"><IconFile size={15} /> <span>{f.name}</span></span>
                    <span role="gridcell" className="dx-type">{f.hint_type ?? 'PDF'}</span>
                    <span role="gridcell" className="dx-size num">{kb(f.size)}</span>
                  </div>
                )
              })}
            </>
          )}
        </div>
      </div>

      <footer className="dx-foot">
        <span className="dx-count">{files.length}개 중 <b>{picked.length}</b>개 선택</span>
        <button type="button" className="btn-primary btn-sm" onClick={start} disabled={picked.length === 0}>
          점검 시작
        </button>
      </footer>
    </section>
  )
}
