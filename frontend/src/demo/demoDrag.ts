/** 체험용 서류 창에서 끌어 온 묶음. 브라우저는 페이지 안 요소로 File 을 만들어 주지 않으므로 이름만 싣고, 놓을 때 PDF 를 받는다 */
export const DEMO_MIME = 'application/x-origin-demo'

export interface DemoDragPayload {
  setId: string
  files: string[]
}

export const isDemoDrag = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes(DEMO_MIME)

export function readDemoDrag(e: DragEvent): DemoDragPayload | null {
  try {
    const v = JSON.parse(e.dataTransfer?.getData(DEMO_MIME) || 'null') as DemoDragPayload | null
    return v && typeof v.setId === 'string' && Array.isArray(v.files) && v.files.length > 0 ? v : null
  } catch {
    return null
  }
}

/** 끌 때 따라오는 그림: 종이 여러 장이 겹친 모양 + 개수 */
export function setDemoDragImage(e: React.DragEvent, count: number, label: string) {
  const el = document.createElement('div')
  el.className = 'demo-drag-ghost'
  el.innerHTML = `<span class="demo-drag-sheets">${'<i></i>'.repeat(Math.min(count, 3))}</span><span class="demo-drag-label"></span><b class="demo-drag-count">${count}</b>`
  el.querySelector('.demo-drag-label')!.textContent = label
  document.body.appendChild(el)
  e.dataTransfer.setDragImage(el, 24, 24)
  setTimeout(() => el.remove(), 0)
}
