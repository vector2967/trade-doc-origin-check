import { useCallback, useEffect, useRef, useState } from 'react'
import { demoApi } from '../api/demo'
import { DemoCard } from '../demo/DemoCard'
import { DemoExplorer } from '../demo/DemoExplorer'
import { isDemoDrag, readDemoDrag, type DemoDragPayload } from '../demo/demoDrag'
import { loadDemoSamples } from '../demo/useDemoSamples'
import { Outlet, useLocation, useMatch, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { ChecksProvider } from '../checks/ChecksContext'
import { useChecks } from '../checks/useChecks'
import { CheckSidebar } from './CheckSidebar'
import { keepListParams } from '../checks/listParams'
import { IconFolder, IconUpload } from './icons'
import { NewCheckModal, type CreatedCheck, type IncomingFiles } from './NewCheckModal'
import { Tutorial } from './Tutorial'
import { tutorialSeen } from '../utils/tutorialSeen'

/**
 * 로그인 후 앱 셸 (Linear 형 2단): 왼쪽 심사목록 사이드바 + 오른쪽 작업 패널.
 * 오른쪽 패널 안에서 법령 확인·원본 PDF·메일 초안이 사이드 패널로 열렸다 닫힌다.
 * 좁은 화면(< 900px)에서는 목록 또는 상세 하나만 보인다.
 * 화면 어디든 파일을 끌어 오면 '놓아서 심사 생성' 덮개가 뜨고, 놓으면 업로드 창이 그 파일들로 열린다.
 *
 * 상세는 좌측 목록의 선택을 따른다:
 * - 사용자가 보기(진행 중/완료)나 검색을 바꿔 지금 상세가 목록에서 빠지면 → 새 목록의 첫 건으로(없으면 빈 화면)
 * - 링크·완료 처리 되돌리기처럼 상세 쪽이 바뀌어 목록에 없으면 → 좌측 보기를 그 건이 있는 쪽으로 바꾼다
 * - 심사를 생성하면 → 진행 중 보기로 바꾸고 그 건을 바로 목록에 넣은 뒤 상세로 간다
 */
export function Layout() {
  return (
    <ChecksProvider>
      <Shell />
    </ChecksProvider>
  )
}

function Shell() {
  const { user, logout } = useAuth()
  const { reload, items, filtered, userIntent, consumeIntent, view, q, result, addLocal, viewOf } = useChecks()
  const navigate = useNavigate()
  const { search } = useLocation()
  const checkId = useMatch('/checks/:checkId')?.params.checkId
  const inDetail = checkId != null
  const demo = user?.demo === true
  // 처음 로그인한 사용자에게 한 번만 튜토리얼 (왼쪽 아래 ? 로 다시 보기). 체험 사용자는 서류 창의 안내가 대신한다
  const [showTutorial, setShowTutorial] = useState(() => user != null && !demo && !tutorialSeen(user.id))

  // 상세 ↔ 좌측 선택 맞추기
  useEffect(() => {
    if (userIntent) {
      // 사용자가 좌측 보기·검색을 바꿈 → 주소가 그 값이 된 뒤에 새 목록을 따라간다
      if (userIntent.view !== view || userIntent.q !== q || (userIntent.result != null && userIntent.result !== result)) return
      // 상세가 새 목록 안에 들어온 뒤에야 의도를 지운다 (이동이 끝나기 전에 아래 규칙이 끼어들지 않게)
      if (!checkId || items == null || filtered.some((c) => c.id === Number(checkId))) {
        consumeIntent()
        return
      }
      const first = filtered[0]
      navigate(first ? `/checks/${first.id}${keepListParams(search)}` : `/checks${keepListParams(search)}`, { replace: true })
      return
    }
    if (!checkId || items == null) return
    const id = Number(checkId)
    if (filtered.some((c) => c.id === id)) return
    const item = items.find((c) => c.id === id)
    if (!item) return
    // 진행 중 보기에서 방금 완료 처리한 건은 따라가지 않는다 (상세가 다음 건으로 넘어가는 중)
    const justDone = viewOf(item) === 'done' && item.done_at != null && Date.now() - Date.parse(item.done_at) < 5000
    if (justDone) return
    // 링크로 열었거나 완료를 되돌린 건 → 좌측 보기를 그 건이 있는 쪽으로 (검색은 해제)
    const p = new URLSearchParams(search)
    p.delete('q')
    p.delete('result')
    if (viewOf(item) === 'done') p.set('view', 'done')
    else p.delete('view')
    navigate(`/checks/${id}${keepListParams(`?${p.toString()}`)}`, { replace: true })
  }, [checkId, items, filtered, userIntent, consumeIntent, view, q, result, viewOf, navigate, search])

  const explorer = useExplorerState(demo)
  const { markDropped, minimize: minimizeExplorer } = explorer
  const onCreated = useCallback(
    (c: CreatedCheck) => {
      addLocal({
        id: c.id, title: c.title, file_name: c.fileName, status: 'queued', decision: null, invoice_no: null,
        created_at: new Date().toISOString(), completed_at: null, done_at: null,
      })
      // 진행 중 보기 + 검색 해제 → 점검 중 묶음에 새 건이 보이고 선택된다
      const p = new URLSearchParams(search)
      p.delete('view')
      p.delete('q')
      const qs = keepListParams(`?${p.toString()}`)
      navigate(`/checks/${c.id}${qs}`)
      reload()
      // 체험: 진행 화면이 가리지 않게 서류 창을 접어 둔다
      if (demo) minimizeExplorer()
    },
    [addLocal, navigate, reload, search, demo, minimizeExplorer],
  )
  const [showNew, setShowNew] = useState(false)
  const [incoming, setIncoming] = useState<IncomingFiles | null>(null)
  const [demoLoading, setDemoLoading] = useState(false)
  const [demoError, setDemoError] = useState<string | null>(null)
  // 체험용 서류 창에서 끌어 놓거나 [점검 시작]을 누르면: PDF 를 받아 새 점검 창을 그 파일로 연다
  const startDemoFiles = useCallback(async (p: DemoDragPayload) => {
    setDemoLoading(true)
    setDemoError(null)
    try {
      const set = (await loadDemoSamples()).find((s) => s.id === p.setId)
      const files = await Promise.all(p.files.map((name) => demoApi.file(p.setId, name)))
      const hints = Object.fromEntries((set?.files ?? []).map((f) => [f.name, f.hint_type]))
      markDropped()
      setIncoming({ files, hints, nonce: Date.now() })
      setShowNew(true)
    } catch {
      setDemoError('체험용 서류를 불러오지 못했어요. 다시 시도해 주세요.')
    } finally {
      setDemoLoading(false)
    }
  }, [markDropped])
  const dragging = useGlobalFileDrop(
    useCallback((files: File[]) => {
      setIncoming({ files, nonce: Date.now() })
      setShowNew(true)
    }, []),
    demo ? startDemoFiles : null,
  )
  // 튜토리얼 상세 장에서 보여 줄 예시: 고칠 게 있는 완료 심사 → 아무 완료 심사
  const done = filtered.filter((c) => c.status === 'completed')
  const sample = done.find((c) => c.decision !== '특혜 적용') ?? done[0]
  // 상세 장: 지금 열린 심사(판정이 나온 것)나 예시 심사를 원본 PDF 패널과 함께 띄운다
  const showDetail = useCallback(() => {
    const id = checkId && document.querySelector('[data-tour="verdict"]') ? Number(checkId) : sample?.id
    if (id == null) return
    const p = new URLSearchParams(keepListParams(search))
    p.set('panel', 'pdf')
    // 첫 문제 자리로 바로 가서 형광펜이 보이게
    const code = items?.find((c) => c.id === id)?.mismatch_codes?.[0]
    if (code) p.set('code', code)
    navigate(`/checks/${id}?${p.toString()}`, { replace: true })
  }, [checkId, sample?.id, items, navigate, search])
  const openNew = useCallback(() => {
    setIncoming(null)
    setShowNew(true)
  }, [])

  return (
    <div className={`app-shell${inDetail ? ' is-detail' : ''}`}>
      <CheckSidebar
        extra={demo && user ? (
          <DemoCard
            expiresAt={user.expires_at}
            used={items?.length ?? 0}
            onOpenExplorer={explorer.open}
            onSignup={() => {
              logout()
              navigate('/', { state: { auth: 'signup' } })
            }}
          />
        ) : undefined}
        userName={user?.name ?? ''}
        company={user?.company ?? null}
        onNew={openNew}
        onHelp={() => setShowTutorial(true)}
        onLogout={() => {
          logout()
          navigate('/')
        }}
      />
      <main className="workspace">
        <Outlet context={{ openNew }} />
      </main>
      {showTutorial && user && !showNew && (
        <Tutorial
          userId={user.id}
          onClose={() => setShowTutorial(false)}
          onStart={openNew}
          hasChecks={(items?.length ?? 0) > 0}
          sampleId={sample?.id ?? null}
          onShowDetail={showDetail}
        />
      )}
      {showNew && <NewCheckModal incoming={incoming} onClose={() => setShowNew(false)} onCreated={onCreated} />}
      {demo && !showNew && explorer.state === 'open' && (
        <DemoExplorer onMinimize={explorer.minimize} onClose={explorer.close} onStart={startDemoFiles} showHint={!explorer.dropped} />
      )}
      {demo && !showNew && explorer.state === 'min' && (
        <button type="button" className="demo-explorer-chip" onClick={explorer.open}>
          <IconFolder size={15} /> 체험용 서류
        </button>
      )}
      {(demoLoading || demoError) && (
        <div className={`demo-toast${demoError ? ' is-error' : ''}`} role="status">
          {demoError ?? '체험용 서류를 불러오는 중…'}
          {demoError && <button type="button" className="btn-sm btn-ghost" onClick={() => setDemoError(null)}>닫기</button>}
        </div>
      )}
      {dragging && !showNew && (
        <div className="drop-overlay" aria-hidden>
          <div className="drop-overlay-card">
            <IconUpload size={28} />
            <p className="drop-overlay-title">놓아서 심사 생성</p>
            <p className="drop-overlay-sub">한 선적 건의 PDF를 한꺼번에 놓으세요</p>
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * 창 전체에서 파일 끌기를 감지한다. 놓으면 onDrop(파일들). 브라우저가 PDF 를 열어 버리는 것도 막는다.
 * onDemoDrop 이 있으면(체험 사용자) 체험용 서류 창에서 끌어 온 묶음도 받는다. 창 위에 다시 놓으면 무시한다.
 */
function useGlobalFileDrop(onDrop: (files: File[]) => void, onDemoDrop: ((p: DemoDragPayload) => void) | null) {
  const [dragging, setDragging] = useState(false)
  const depth = useRef(0)
  useEffect(() => {
    const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes('Files') || (onDemoDrop != null && isDemoDrag(e))
    const enter = (e: DragEvent) => {
      if (!hasFiles(e)) return
      depth.current += 1
      setDragging(true)
    }
    const leave = (e: DragEvent) => {
      if (!hasFiles(e)) return
      depth.current = Math.max(0, depth.current - 1)
      if (depth.current === 0) setDragging(false)
    }
    const over = (e: DragEvent) => {
      if (hasFiles(e)) e.preventDefault()
    }
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return
      e.preventDefault()
      depth.current = 0
      setDragging(false)
      if (onDemoDrop && isDemoDrag(e)) {
        if ((e.target as Element | null)?.closest?.('.demo-explorer')) return
        const p = readDemoDrag(e)
        if (p) onDemoDrop(p)
        return
      }
      const files = Array.from(e.dataTransfer?.files ?? [])
      if (files.length) onDrop(files)
    }
    // 업로드 창의 놓기 영역은 drop 을 자기에서 멈춘다 → 덮개 상태는 capture 단계에서 먼저 풀어 둔다
    const reset = () => {
      depth.current = 0
      setDragging(false)
    }
    window.addEventListener('drop', reset, true)
    window.addEventListener('dragend', reset)
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('drop', reset, true)
      window.removeEventListener('dragend', reset)
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [onDrop, onDemoDrop])
  return dragging
}

type ExplorerView = 'open' | 'min' | 'closed'
const EXPLORER_KEY = 'origin-check.demo.explorer'
const DROPPED_KEY = 'origin-check.demo.dropped'
const readSession = (k: string) => {
  try {
    return sessionStorage.getItem(k)
  } catch {
    return null
  }
}
const writeSession = (k: string, v: string) => {
  try {
    sessionStorage.setItem(k, v)
  } catch {
    /* noop */
  }
}

/** 체험용 서류 창: 처음엔 열려 있고, 최소화·닫기 상태와 '한 번 끌어 놓았는지'를 이 탭에서 기억한다 */
function useExplorerState(demo: boolean) {
  const [state, setState] = useState<ExplorerView>(() => (readSession(EXPLORER_KEY) as ExplorerView | null) ?? 'open')
  const [dropped, setDropped] = useState(() => readSession(DROPPED_KEY) === '1')
  const set = useCallback((v: ExplorerView) => {
    setState(v)
    writeSession(EXPLORER_KEY, v)
  }, [])
  const open = useCallback(() => set('open'), [set])
  const minimize = useCallback(() => set('min'), [set])
  const close = useCallback(() => set('closed'), [set])
  const markDropped = useCallback(() => {
    setDropped(true)
    writeSession(DROPPED_KEY, '1')
  }, [])
  return { state: demo ? state : 'closed', dropped, open, minimize, close, markDropped }
}
