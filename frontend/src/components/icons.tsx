/** 선 아이콘 모음 (24 그리드, stroke). 크기는 size, 색은 currentColor */
import type { ReactNode } from 'react'

function Svg({ size = 16, className, children }: { size?: number; className?: string; children: ReactNode }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden className={className}>
      {children}
    </svg>
  )
}
type P = { size?: number; className?: string }

export const IconPlus = (p: P) => <Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>
export const IconMinus = (p: P) => <Svg {...p}><path d="M5 12h14" /></Svg>
export const IconSearch = (p: P) => <Svg {...p}><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></Svg>
export const IconX = (p: P) => <Svg {...p}><path d="M18 6 6 18M6 6l12 12" /></Svg>
export const IconChevronUp = (p: P) => <Svg {...p}><path d="m18 15-6-6-6 6" /></Svg>
export const IconChevronDown = (p: P) => <Svg {...p}><path d="m6 9 6 6 6-6" /></Svg>
export const IconChevronRight = (p: P) => <Svg {...p}><path d="m9 18 6-6-6-6" /></Svg>
export const IconArrowLeft = (p: P) => <Svg {...p}><path d="M19 12H5M12 19l-7-7 7-7" /></Svg>
export const IconArrowRight = (p: P) => <Svg {...p}><path d="M5 12h14M12 5l7 7-7 7" /></Svg>
export const IconLink = (p: P) => <Svg {...p}><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" /><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" /></Svg>
export const IconCopy = (p: P) => <Svg {...p}><rect x="9" y="9" width="12" height="12" rx="2" /><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1" /></Svg>
export const IconCheck = (p: P) => <Svg {...p}><path d="M20 6 9 17l-5-5" /></Svg>
export const IconScale = (p: P) => (
  <Svg {...p}><path d="M12 3v18M7 21h10M5 7h14M5 7l-3 7a3 3 0 0 0 6 0zM19 7l-3 7a3 3 0 0 0 6 0z" /></Svg>
)
export const IconFile = (p: P) => (
  <Svg {...p}><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /></Svg>
)
export const IconFiles = (p: P) => (
  <Svg {...p}><path d="M15 3H9a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7z" /><path d="M15 3v4h4" /><path d="M7 7H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h7a2 2 0 0 0 2-2v-1" /></Svg>
)
export const IconMail = (p: P) => <Svg {...p}><rect x="3" y="5" width="18" height="14" rx="2" /><path d="m3 7 9 6 9-6" /></Svg>
export const IconAlert = (p: P) => <Svg {...p}><path d="M12 9v4M12 17h.01" /><path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" /></Svg>
export const IconHelp = (p: P) => <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.4M12 17h.01" /></Svg>
export const IconLogout = (p: P) => <Svg {...p}><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" /></Svg>
export const IconExternal = (p: P) => <Svg {...p}><path d="M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" /></Svg>
export const IconUpload = (p: P) => <Svg {...p}><path d="M12 16V4M7 9l5-5 5 5" /><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" /></Svg>
export const IconEye = (p: P) => <Svg {...p}><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" /><circle cx="12" cy="12" r="3" /></Svg>
export const IconEyeOff = (p: P) => <Svg {...p}><path d="M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-2.2 3.1M6.6 6.6C3.9 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6" /><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M2 2l20 20" /></Svg>
export const IconTrash = (p: P) => <Svg {...p}><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6" /></Svg>
export const IconRefresh = (p: P) => <Svg {...p}><path d="M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16m0 4v-4h-4" /></Svg>
export const IconInbox = (p: P) => (
  <Svg {...p}><path d="M22 12h-6l-2 3h-4l-2-3H2" /><path d="M5.5 5.1 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z" /></Svg>
)
export const IconLock = (p: P) => <Svg {...p}><rect x="4" y="11" width="16" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></Svg>
export const IconUser = (p: P) => <Svg {...p}><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></Svg>
export const IconBuilding = (p: P) => <Svg {...p}><path d="M4 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16M16 9h2a2 2 0 0 1 2 2v10M2 21h20M8 7h4M8 11h4M8 15h4" /></Svg>
export const IconClock = (p: P) => <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></Svg>
export const IconSparkle = (p: P) => <Svg {...p}><path d="M12 3l1.8 4.9L18.7 9.7l-4.9 1.8L12 16.4l-1.8-4.9L5.3 9.7l4.9-1.8z" /><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" /></Svg>
export const IconHighlighter = (p: P) => <Svg {...p}><path d="m9 11-6 6v3h9l3-3" /><path d="m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4" /></Svg>
export const IconLayers = (p: P) => <Svg {...p}><path d="m12 3 9 5-9 5-9-5z" /><path d="m3 13 9 5 9-5" /></Svg>
export const IconShield = (p: P) => <Svg {...p}><path d="M12 3 4 6v6c0 5 3.4 8.3 8 9 4.6-.7 8-4 8-9V6z" /><path d="m9 12 2 2 4-4" /></Svg>

/**
 * 서비스 로고 마크 (D3 · 도장 모노그램): 세관 도장처럼 살짝 기울인 테두리 안에 FTA, 모서리에 주황 확인 인장.
 * 글자는 폰트가 아니라 선으로 그려 어디서나 같게 보인다. 작게 쓸수록(파비콘·사이드바) 획을 굵게 한다.
 * 색: 바탕 = --color-accent, 획 = --color-on-accent, 인장 = --color-logo-seal, 인장 체크 = --color-brand-ink
 */
export const LogoMark = ({ size = 22 }: P) => {
  const k = size <= 20 ? 1.3 : size <= 28 ? 1.12 : 1
  return (
    <svg className="logo-mark" width={size} height={size} viewBox="0 0 48 48" aria-hidden>
      <rect className="lm-tile" width="48" height="48" rx="12" fill="var(--color-accent)" />
      <g transform="rotate(-7 24 24)" fill="none" stroke="var(--color-on-accent)" strokeLinecap="round" strokeLinejoin="round">
        <rect className="lm-ink" x="7" y="13" width="34" height="22" rx="4" strokeWidth={1.8 * k} />
        <path
          className="lm-ink"
          d="M13 18.5v11M13 18.5h5M13 23.6h4M20.5 18.5h6.5M23.75 18.5v11M29 29.5L32.75 18.5L36.5 29.5M30.5 25.6h4.5"
          strokeWidth={2.4 * k}
        />
      </g>
      <circle className="lm-seal" cx="39" cy="35" r={k > 1 ? 5.5 : 4.5} fill="var(--color-logo-seal)" />
      <path
        className="lm-seal-check"
        d="M37.1 35.1l1.4 1.4 2.5-2.7"
        fill="none"
        stroke="var(--color-brand-ink)"
        strokeWidth={1.5 * k}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
