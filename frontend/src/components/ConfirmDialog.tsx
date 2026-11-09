import { useEffect, type ReactNode } from 'react'
import { IconAlert, IconTrash } from './icons'

/**
 * 되돌릴 수 없는 일(삭제 등) 전에 한 번 묻는 작은 창.
 * Esc·바깥 누르기 = 취소. 열려 있는 동안 뒤 화면 단축키(↑↓·E 등)는 막는다. busy 면 두 번 누를 수 없다.
 */
export function ConfirmDialog({ title, children, confirmLabel, busyLabel, danger, busy, onConfirm, onCancel }: {
  title: string
  children: ReactNode
  confirmLabel: string
  busyLabel?: string
  danger?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !busy) onCancel()
      // 기본 동작(포커스된 버튼의 Enter 등)은 두고, 뒤 화면 단축키로는 넘기지 않는다
      e.stopPropagation()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [busy, onCancel])

  return (
    <div className="modal-backdrop" onClick={() => !busy && onCancel()}>
      <div className="modal confirm" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" onClick={(e) => e.stopPropagation()}>
        <span className={`confirm-icon${danger ? ' is-danger' : ''}`} aria-hidden>{danger ? <IconTrash size={20} /> : <IconAlert size={20} />}</span>
        <h2 id="confirm-title" className="confirm-title">{title}</h2>
        <div className="confirm-body">{children}</div>
        <footer className="confirm-foot">
          {/* 실수로 Enter 를 눌러도 지워지지 않게 처음 포커스는 취소에 */}
          <button type="button" onClick={onCancel} disabled={busy} autoFocus>취소</button>
          <button type="button" className={danger ? 'btn-danger' : 'btn-primary'} onClick={onConfirm} disabled={busy}>
            {busy ? (busyLabel ?? confirmLabel) : confirmLabel}
          </button>
        </footer>
      </div>
    </div>
  )
}
