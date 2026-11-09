import type { ExtractNode, FieldMeta, OriginCheckDetail } from '../api/types'
import { CONFIDENCE_THRESHOLD, type HighlightLevel } from '../styles/tokens'
import { DOC_DEFS } from './documents'
import { CODE_BASIS } from './legalBasis'
import { formatValue } from '../utils/format'

/**
 * 원본 PDF 하이라이트 (화면명세서 §5.3).
 * 추출 메타(extracted_meta)의 page·boxes(페이지 대비 0~1 좌표) → 박스 목록.
 * 불일치 코드와 관련된 항목은 빨강, 나머지는 신뢰도로 주황(낮음)·노랑(중간)·초록(높음).
 */

/** 불일치 코드 → 대조한 항목. 'items.' 로 시작하면 모든 품목의 그 칸 */
export const CODE_FIELDS: Record<string, [ExtractNode, string][]> = {
  CO_INVOICE_NO_MISMATCH: [['원산지증명서_추출', 'invoice_no'], ['인보이스_추출', 'invoice_no']],
  CO_QTY_MISMATCH: [['원산지증명서_추출', 'items.quantity'], ['인보이스_추출', 'items.quantity']],
  CO_CRITERION_MISMATCH: [['원산지증명서_추출', 'items.origin_criterion'], ['원산지확인서_추출', 'items.origin_criterion']],
  CO_CRITERION_NOT_PSR: [['원산지증명서_추출', 'items.origin_criterion'], ['원산지증명서_추출', 'items.hs_code']],
  CO_ORIGIN_MISMATCH: [['원산지증명서_추출', 'exporter_country'], ['원산지확인서_추출', 'items.origin_country']],
  CO_CONSIGNEE_MISMATCH: [['원산지증명서_추출', 'consignee'], ['인보이스_추출', 'consignee']],
  CO_DESCRIPTION_MISMATCH: [['원산지증명서_추출', 'items.description'], ['인보이스_추출', 'items.description']],
  CO_HS_MISMATCH: [['인보이스_추출', 'items.hs_code'], ['원산지증명서_추출', 'items.hs_code'], ['원산지확인서_추출', 'items.hs_code'], ['수출신고필증_추출', 'items.hs_code']],
  CO_HS_MINOR_DIFF: [['인보이스_추출', 'items.hs_code'], ['원산지확인서_추출', 'items.hs_code'], ['수출신고필증_추출', 'items.hs_code']],
  CO_TRANSPORT_MISMATCH: [
    ['원산지증명서_추출', 'vessel'], ['원산지증명서_추출', 'port_of_discharge'],
    ['BL_추출', 'vessel'], ['BL_추출', 'port_of_discharge'], ['BL_추출', 'transshipment_port'],
  ],
  CO_ISSUE_DATE_LATE: [['원산지증명서_추출', 'issue_date'], ['원산지증명서_추출', 'issued_retroactively'], ['BL_추출', 'laden_on_board_date']],
  SUP_CRITERION_NOT_MET: [['원산지확인서_추출', 'items.fulfilled']],
  SUP_ORIGIN_MISMATCH: [['원산지확인서_추출', 'items.origin_country']],
  SUP_EXPIRED: [['원산지확인서_추출', 'items.period_from'], ['원산지확인서_추출', 'items.period_to'], ['수출신고필증_추출', 'declaration_date']],
  SUP_RECIPIENT_MISMATCH: [['원산지확인서_추출', 'recipient'], ['인보이스_추출', 'shipper']],
  SUP_AGREEMENT_MISMATCH: [['원산지확인서_추출', 'items.agreement']],
  EXPDEC_INVOICE_NO_MISMATCH: [['수출신고필증_추출', 'invoice_no'], ['인보이스_추출', 'invoice_no']],
  EXPDEC_QTY_MISMATCH: [['수출신고필증_추출', 'items.quantity'], ['인보이스_추출', 'items.quantity']],
  QTY_MISMATCH: [['패킹리스트_추출', 'quantity'], ['인보이스_추출', 'items.quantity']],
  NET_GT_GROSS: [['패킹리스트_추출', 'net_weight_kg'], ['패킹리스트_추출', 'gross_weight_kg']],
}

export interface Highlight {
  /** `${node}.${문서순번}.${필드}` 또는 `${node}.${문서순번}.items.${품목순번}.${필드}` */
  id: string
  page: number
  /** 페이지 대비 0~1 */
  rect: { x: number; y: number; w: number; h: number }
  level: Exclude<HighlightLevel, 'selected'>
  /** 툴팁: 서류 · 항목 · 값 · 신뢰도 */
  label: string
  codes: string[]
  /** 마우스를 올렸을 때 뜨는 힌트 카드용 */
  docName: string
  fieldLabel: string
  value: string
  score: number | null
  /** 걸린 불일치 코드마다: 짧은 설명 + 대조한 다른 서류의 값 */
  reasons: HighlightReason[]
}

export interface HighlightReason {
  code: string
  title: string
  others: { label: string; value: string }[]
}

export const fieldId = (node: ExtractNode, docIndex: number, path: string) => `${node}.${docIndex}.${path}`

function toRect(meta: FieldMeta) {
  const pts = meta.boxes.flat()
  if (pts.length === 0) return null
  const xs = pts.map((p) => p.x)
  const ys = pts.map((p) => p.y)
  const pad = 0.003
  const x = Math.max(0, Math.min(...xs) - pad)
  const y = Math.max(0, Math.min(...ys) - pad)
  return { x, y, w: Math.min(1, Math.max(...xs) + pad) - x, h: Math.min(1, Math.max(...ys) + pad) - y }
}

function levelOf(score: number | null) {
  if (score == null) return 'high' as const
  if (score < CONFIDENCE_THRESHOLD.low) return 'low' as const
  if (score < CONFIDENCE_THRESHOLD.high) return 'mid' as const
  return 'high' as const
}

export function buildHighlights(detail: OriginCheckDetail): Highlight[] {
  const codes = detail.judge?.mismatch_codes ?? detail.mismatches.map((m) => m.code)
  // 필드 키(node + 경로) → 걸린 코드들
  const codeByField = new Map<string, string[]>()
  for (const c of codes) {
    for (const [node, path] of CODE_FIELDS[c] ?? []) {
      const k = `${node}:${path}`
      codeByField.set(k, [...(codeByField.get(k) ?? []), c])
    }
  }

  const valueOf = (node: ExtractNode, path: string) => fieldValue(detail, node, path)
  const reasonsFor = (node: ExtractNode, matchPath: string, hitCodes: string[]): HighlightReason[] =>
    hitCodes.map((code) => ({
      code,
      title: detail.mismatches.find((m) => m.code === code)?.label ?? CODE_BASIS[code]?.label ?? code,
      others: (CODE_FIELDS[code] ?? [])
        .filter(([n, p]) => !(n === node && p === matchPath))
        .map(([n, p]) => valueOf(n, p))
        .filter((v): v is NonNullable<typeof v> => v != null)
        .map(({ label, value }) => ({ label, value })),
    }))

  const out: Highlight[] = []
  for (const def of DOC_DEFS) {
    const metas = (detail.extracted_meta[def.node] ?? []) as unknown as Record<string, unknown>[]
    const values = (detail.extracted[def.node] ?? []) as unknown as Record<string, unknown>[]
    metas.forEach((meta, di) => {
      const push = (m: FieldMeta | null | undefined, path: string, matchPath: string, label: string, value: unknown) => {
        if (!m || m.page < 1 || !m.boxes?.length) return
        const rect = toRect(m)
        if (!rect) return
        const hitCodes = codeByField.get(`${def.node}:${matchPath}`) ?? []
        const pct = m.score != null ? ` · 신뢰도 ${Math.round(m.score * 100)}%` : ''
        out.push({
          id: fieldId(def.node, di, path),
          page: m.page,
          rect,
          level: hitCodes.length ? 'mismatch' : levelOf(m.score),
          label: `${def.name} · ${label} · ${formatValue(value)}${pct}`,
          codes: hitCodes,
          docName: def.name,
          fieldLabel: label,
          value: formatValue(value),
          score: m.score,
          reasons: reasonsFor(def.node, matchPath, hitCodes),
        })
      }
      for (const [key, label] of def.fields) push(meta[key] as FieldMeta, key, key, label, values[di]?.[key])
      const itemMetas = (meta.items as Record<string, FieldMeta>[] | undefined) ?? []
      const itemVals = (values[di]?.items as Record<string, unknown>[] | undefined) ?? []
      itemMetas.forEach((im, ii) => {
        for (const [key, label] of def.items ?? []) {
          push(im?.[key], `items.${ii}.${key}`, `items.${key}`, `품목 ${ii + 1} ${label}`, itemVals[ii]?.[key])
        }
      })
    })
  }
  return out
}

/** (서류, 경로) → 화면 라벨과 값. 품목 칸은 모든 품목 값을 이어 붙인다. 서류가 없으면 null */
export function fieldValue(detail: OriginCheckDetail, node: ExtractNode, path: string) {
  const def = DOC_DEFS.find((d) => d.node === node)
  const doc = (detail.extracted[node] ?? [])[0] as unknown as Record<string, unknown> | undefined
  if (!def || !doc) return null
  if (path.startsWith('items.')) {
    const key = path.slice(6)
    const label = def.items?.find(([k]) => k === key)?.[1] ?? key
    const vals = ((doc.items as Record<string, unknown>[] | undefined) ?? []).map((it) => formatValue(it[key]))
    return { doc: def.name, label: `${def.name} · ${label}`, field: label, value: vals.join(', ') || '—' }
  }
  const label = def.fields.find(([k]) => k === path)?.[1] ?? path
  return { doc: def.name, label: `${def.name} · ${label}`, field: label, value: formatValue(doc[path]) }
}

/** 불일치 코드 하나에 대해 서류별로 대조한 값 (상세 화면 '확인할 항목'용) */
export function codeComparison(detail: OriginCheckDetail, code: string) {
  return (CODE_FIELDS[code] ?? [])
    .map(([n, p]) => fieldValue(detail, n, p))
    .filter((v): v is NonNullable<typeof v> => v != null)
}
