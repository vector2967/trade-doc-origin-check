import { useState, type ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { AuthModal, type AuthMode } from '../components/AuthModal'
import { DocChip } from '../components/DocChip'
import { IconArrowRight, IconCheck, IconExternal, IconHighlighter, IconLayers, IconMail, IconScale, IconSparkle, LogoMark } from '../components/icons'
import { ThemeToggle } from '../components/ThemeToggle'
import { CODE_BASIS, LAWS, STAGE_LABEL } from '../data/legalBasis'

/**
 * S-01 메인(랜딩). 로그인 상태면 심사목록으로 보낸다.
 *
 * 흐름은 SaaS 랜딩의 정석(Lapa Ninja)을 따른다:
 *   히어로(한 문장 + 주요 버튼 하나 + 실제 제품 화면) → 숫자 띠 → 기능(벤토 격자) → 점검 과정 → 판정 → 자주 묻는 질문 → 마지막 CTA
 * 히어로 오른쪽은 실제 점검 결과 한 장면(송장번호 불일치)을 디자인 시스템 '검인' 부품으로 그린 견본이다.
 * 숫자·문구는 모두 제품에 실제로 있는 값(서류 6종·판정 4가지·최대 20개 파일 등)만 쓴다. 꾸며낸 고객 수·후기는 넣지 않는다.
 */
const DOCS = ['인보이스', '패킹리스트', 'B/L', '원산지증명서', '원산지확인서', '수출신고필증']

const STEPS = [
  { name: '올리기', who: '담당자', line: '한 선적 건의 PDF를 여러 개 그대로 놓기' },
  { name: '서류 나누기', who: 'AI', line: '쪽마다 어떤 서류인지 AI가 분류' },
  { name: '값 읽기', who: 'AI', line: '송장번호·HS·수량·결정기준 추출' },
  { name: '서로 대조', who: 'AI', line: '서류 6종의 같은 칸을 맞대어 비교' },
  { name: '판정', who: 'AI', line: '판정 근거와 보낼 메일 초안까지' },
]

const DECISIONS = [
  { name: '특혜 적용', tone: 'success', line: '그대로 바이어에게 보내도 됩니다.', doc: null },
  { name: '보완 요청', tone: 'warning', line: '서류를 고치거나 다시 받으면 됩니다.', doc: '보완 요청 메일 초안' },
  { name: '미적용', tone: 'danger', line: '서류를 고쳐도 특혜 근거가 없습니다.', doc: '미적용 안내문' },
  { name: '검토 필요', tone: 'caution', line: '담당자가 판단해야 할 차이가 있습니다.', doc: '검토 메모' },
]

const STATS = [
  { num: '6', unit: '종', label: '서로 맞대어 보는 서류' },
  { num: '5', unit: '단계', label: 'PDF 접수부터 판정까지' },
  { num: '4', unit: '가지', label: '바로 움직일 수 있는 판정' },
  { num: '20', unit: '개', label: '한 번에 올리는 PDF' },
]

/** 기능 타일 1: 서류 × 항목 대조표. ok = 같음, bad = 다름, na = 그 서류에 없는 칸 */
const MATRIX_DOCS = ['인보이스', '원산지증명서', '원산지확인서', '수출신고필증']
const MATRIX: { field: string; cells: ('ok' | 'bad' | 'na')[] }[] = [
  { field: '송장번호', cells: ['ok', 'bad', 'na', 'ok'] },
  { field: 'HS 코드', cells: ['ok', 'ok', 'ok', 'ok'] },
  { field: '수량', cells: ['ok', 'ok', 'na', 'ok'] },
  { field: '결정기준', cells: ['na', 'ok', 'ok', 'na'] },
  { field: '원산지', cells: ['na', 'ok', 'ok', 'ok'] },
  { field: '수하인', cells: ['ok', 'ok', 'na', 'na'] },
]

const FAQ = [
  {
    q: '어떤 파일을 올리면 되나요?',
    a: '한 선적 건의 서류 PDF를 그대로 올리면 됩니다. 서류별로 나뉜 파일도, 하나로 합친 PDF도 괜찮습니다. 한 번에 20개, 합계 100MB까지 올릴 수 있습니다.',
  },
  {
    q: '서류가 빠져 있어도 점검할 수 있나요?',
    a: '네. 빠진 서류가 있어도 막지 않고 점검합니다. 다만 빠진 서류에 따라 판정이 ‘보완 요청’이나 ‘검토 필요’로 나올 수 있습니다. 패킹리스트는 선택 서류입니다.',
  },
  {
    q: 'AI 판정을 그대로 믿어도 되나요?',
    a: 'AI가 내는 1차 판정입니다. 판정 근거와 원본 PDF의 위치, 관련 법령을 함께 보여 드리니 최종 판단은 담당자가 내려 주세요.',
  },
  {
    q: 'AI가 값을 잘못 읽으면 어떻게 하나요?',
    a: '읽은 정확도가 낮은 값에는 ‘확인 필요’ 표시가 붙습니다. 눌러서 원본 PDF의 그 자리를 바로 열어 확인할 수 있습니다.',
  },
]

/** 기능 타일 5: 목록 묶음 (막대 길이는 그림일 뿐 실제 건수가 아니다) */
const GROUPS_DEMO = [
  { label: '조치 필요', tone: 'danger', w: 78 },
  { label: '검토 필요', tone: 'caution', w: 46 },
  { label: '점검 중', tone: 'info', w: 24 },
  { label: '특혜 적용', tone: 'success', w: 58 },
]

const SAMPLE_CODE = CODE_BASIS.CO_INVOICE_NO_MISMATCH

export function LandingPage() {
  const { user, loading } = useAuth()
  const [modal, setModal] = useState<AuthMode | null>(null)

  if (!loading && user) return <Navigate to="/checks" replace />

  const signup = () => setModal('signup')
  const login = () => setModal('login')

  return (
    <div className="landing">
      <header className="ld-head">
        <div className="ld-inner ld-head-inner">
          <a href="#top" className="ld-brand"><LogoMark size={26} /> FTA 원산지 점검</a>
          <nav className="ld-nav" aria-label="소개">
            <a href="#features">기능</a>
            <a href="#how">점검 과정</a>
            <a href="#decisions">판정 기준</a>
            <a href="#faq">자주 묻는 질문</a>
          </nav>
          <div className="ld-head-actions">
            <ThemeToggle />
            <button type="button" className="btn-ghost ld-login" onClick={login}>로그인</button>
            <button type="button" className="btn-primary" onClick={signup}>시작하기</button>
          </div>
        </div>
      </header>

      <main id="top">
        {/* ───── 히어로 ───── */}
        <section className="ld-hero">
          <div className="ld-hero-bg" aria-hidden />
          <div className="ld-inner ld-hero-inner">
            <div className="ld-hero-copy">
              <p className="ld-pill">
                <span className="ld-pill-tag"><IconSparkle size={12} /> AI 1차 판정</span>
                수출기업 FTA 담당자를 위한 원산지 서류 점검
              </p>
              <h1 className="ld-title">
                원산지 서류,<br className="ld-br" /> 바이어에게 보내기 전에<br className="ld-br" /> <span className="ld-title-mark">한 번 더 대조</span>합니다
              </h1>
              <p className="ld-lead">
                인보이스·원산지증명서·원산지확인서·B/L·수출신고필증을 맞대어 보고, 협정세율을 받을 수 있는지와 고칠 곳을 알려 드립니다.
              </p>
              <div className="ld-cta">
                <button type="button" className="btn-primary btn-lg ld-cta-main" onClick={signup}>
                  시작하기 <IconArrowRight size={18} />
                </button>
                <a href="#how" className="btn btn-lg ld-cta-sub">점검 과정 보기</a>
              </div>
              <ul className="ld-trust">
                <li><IconCheck size={15} /> PDF 여러 개를 한 번에</li>
                <li><IconCheck size={15} /> 판정 근거·관련 법령</li>
                <li><IconCheck size={15} /> 보낼 메일 초안까지</li>
              </ul>
            </div>

            <HeroVisual />
          </div>

          <div className="ld-inner">
            <div className="ld-docs-strip">
              <span className="ld-docs-label">한 번에 대조하는 서류</span>
              <ul className="ld-docs" aria-label="점검하는 서류">
                {DOCS.map((d) => (
                  <li key={d}><DocChip name={d} /><span>{d}</span></li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        {/* ───── 숫자 띠 ───── */}
        <section className="ld-stats-band" aria-label="한눈에 보기">
          <div className="ld-inner">
            <dl className="ld-stats">
              {STATS.map((s) => (
                <div key={s.label} className="ld-stat">
                  <dt>{s.label}</dt>
                  <dd><span className="num">{s.num}</span><small>{s.unit}</small></dd>
                </div>
              ))}
            </dl>
          </div>
        </section>

        {/* ───── 기능 (벤토) ───── */}
        <section className="ld-section" id="features">
          <div className="ld-inner">
            <SectionHead no="01" kicker="기능" title="담당자가 하던 대조를 화면 하나에 모았습니다">
              서류를 나누고, 값을 읽고, 같은 칸을 맞대어 보는 일은 AI가 합니다. 담당자는 걸린 곳만 확인하면 됩니다.
            </SectionHead>

            <div className="ld-bento">
              <article className="ld-tile ld-tile-wide">
                <div className="ld-tile-copy">
                  <span className="ld-tile-icon"><IconLayers size={18} /></span>
                  <h3>서류 6종, 같은 칸을 맞대어 봅니다</h3>
                  <p>송장번호·HS 코드·수량·결정기준처럼 서류마다 반복되는 칸을 서로 비교해 다른 곳만 골라냅니다.</p>
                </div>
                <div className="ld-matrix" aria-hidden>
                  <span />
                  {MATRIX_DOCS.map((d) => <span key={d} className="ld-matrix-head"><DocChip name={d} /></span>)}
                  {MATRIX.map((r) => (
                    <div key={r.field} className="ld-matrix-row">
                      <span className="ld-matrix-field">{r.field}</span>
                      {r.cells.map((c, i) => (
                        <span key={i} className={`ld-cell is-${c}`}>{c === 'ok' ? <IconCheck size={13} /> : c === 'bad' ? '다름' : '—'}</span>
                      ))}
                    </div>
                  ))}
                </div>
              </article>

              <article className="ld-tile">
                <div className="ld-tile-copy">
                  <span className="ld-tile-icon"><IconHighlighter size={18} /></span>
                  <h3>원본 PDF 위에 형광펜</h3>
                  <p>문제가 된 값의 자리를 원본에 칠해 보여 줍니다. 마우스를 올리면 이유가 나옵니다.</p>
                </div>
                <div className="ld-paper" aria-hidden>
                  <span className="ld-paper-title">CERTIFICATE OF ORIGIN · FORM AK</span>
                  <span className="ld-line" style={{ width: '82%' }} />
                  <span className="ld-line" style={{ width: '64%' }} />
                  <span className="ld-paper-row"><small>Invoice No.</small><mark className="ld-hl ld-hl-red mono">SM-2609-0815</mark></span>
                  <span className="ld-line" style={{ width: '74%' }} />
                  <span className="ld-paper-row"><small>Quantity</small><mark className="ld-hl ld-hl-orange mono">900 CTNS</mark></span>
                  <span className="ld-paper-row"><small>HS Code</small><mark className="ld-hl ld-hl-yellow mono">0303.54</mark></span>
                  <span className="ld-legend">
                    <i className="ld-hl-red" />불일치 <i className="ld-hl-orange" />신뢰도 낮음 <i className="ld-hl-yellow" />중간
                  </span>
                </div>
              </article>

              <article className="ld-tile">
                <div className="ld-tile-copy">
                  <span className="ld-tile-icon"><IconScale size={18} /></span>
                  <h3>판정 근거와 관련 법령</h3>
                  <p>어떤 규칙에 걸렸는지, 무엇을 대조해야 하는지, 관련 법령까지 한 패널에서 확인합니다.</p>
                </div>
                <div className="ld-law-demo" aria-hidden>
                  <span className="badge tone-warning">{SAMPLE_CODE.stage}단계 · {STAGE_LABEL[SAMPLE_CODE.stage]}</span>
                  <b>{SAMPLE_CODE.label}</b>
                  <span className="ld-law-rule">{SAMPLE_CODE.resolution}</span>
                  <span className="ld-law-link">{LAWS.ftaAct.short} <IconExternal size={12} /></span>
                </div>
              </article>

              <article className="ld-tile">
                <div className="ld-tile-copy">
                  <span className="ld-tile-icon"><IconMail size={18} /></span>
                  <h3>보낼 메일 초안까지</h3>
                  <p>보완이 필요하면 요청 메일 초안을, 특혜가 어려우면 안내문을 함께 만들어 둡니다.</p>
                </div>
                <div className="ld-mail-demo" aria-hidden>
                  <span className="ld-mail-row"><small>받는 곳</small>원산지증명서 발급기관(상공회의소)</span>
                  <span className="ld-mail-row"><small>제목</small>원산지증명서 송장번호 정정·재발급 요청</span>
                  <span className="ld-line" style={{ width: '92%' }} />
                  <span className="ld-line" style={{ width: '70%' }} />
                  <span className="ld-mail-copy"><IconCheck size={12} /> 전체 복사</span>
                </div>
              </article>

              <article className="ld-tile">
                <div className="ld-tile-copy">
                  <span className="ld-tile-icon"><IconCheck size={18} /></span>
                  <h3>급한 일부터 묶은 목록</h3>
                  <p>조치 필요 · 검토 필요 · 점검 중 · 특혜 적용 순으로 묶어, 처리할 건부터 보여 줍니다.</p>
                </div>
                <ul className="ld-groups-demo" aria-hidden>
                  {GROUPS_DEMO.map((g) => (
                    <li key={g.label}>
                      <span className={`dot tone-${g.tone}`} />
                      <span>{g.label}</span>
                      <span className="ld-bar"><i className={`tone-${g.tone}`} style={{ width: `${g.w}%` }} /></span>
                    </li>
                  ))}
                </ul>
              </article>
            </div>
          </div>
        </section>

        {/* ───── 점검 과정 ───── */}
        <section className="ld-section ld-section-alt" id="how">
          <div className="ld-inner">
            <SectionHead no="02" kicker="점검 과정" title="올리기만 하면, 나머지 네 단계는 AI가">
              파일 이름이 제각각이어도, 여러 서류가 한 PDF에 합쳐져 있어도 괜찮습니다.
            </SectionHead>
            <ol className="ld-steps">
              {STEPS.map((s, i) => (
                <li key={s.name} className={s.who === 'AI' ? 'is-ai' : undefined}>
                  <span className="ld-step-node num" aria-hidden>{String(i + 1).padStart(2, '0')}</span>
                  <span className="ld-step-who">{s.who}</span>
                  <span className="ld-step-name">{s.name}</span>
                  <span className="ld-step-line">{s.line}</span>
                </li>
              ))}
            </ol>
          </div>
        </section>

        {/* ───── 판정 ───── */}
        <section className="ld-section" id="decisions">
          <div className="ld-inner">
            <SectionHead no="03" kicker="판정 기준" title="판정은 네 가지, 다음 할 일까지 정해 드립니다" />
            <ul className="ld-decisions">
              {DECISIONS.map((d) => (
                <li key={d.name} className={`ld-decision tone-${d.tone}`}>
                  <span className={`badge badge-lg stamp tone-${d.tone}`}>{d.name}</span>
                  <p className="ld-decision-line">{d.line}</p>
                  <p className="ld-decision-doc">
                    {d.doc ? <><IconMail size={14} /> {d.doc} 함께 작성</> : <><IconCheck size={14} /> 추가로 보낼 문서 없음</>}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        {/* ───── 자주 묻는 질문 ───── */}
        <section className="ld-section ld-section-alt" id="faq">
          <div className="ld-inner ld-faq-inner">
            <SectionHead no="04" kicker="자주 묻는 질문" title="처음 쓰기 전에 많이 묻습니다" />
            <div className="ld-faq">
              {FAQ.map((f, i) => (
                <details key={f.q} open={i === 0}>
                  <summary>{f.q}<span className="ld-faq-icon" aria-hidden /></summary>
                  <p>{f.a}</p>
                </details>
              ))}
            </div>
          </div>
        </section>

        {/* ───── 마지막 CTA ───── */}
        <section className="ld-final">
          <div className="ld-inner">
            <div className="ld-final-card">
              <div className="ld-final-copy">
                <p className="ld-final-kicker">다음 선적부터</p>
                <h2 className="ld-final-title">바이어에게 보내기 전, 서류부터 맞대어 보세요</h2>
                <p className="ld-final-lead">PDF를 올리면 판정과 고칠 곳, 보낼 메일 초안까지 한 화면에 정리됩니다.</p>
              </div>
              <div className="ld-final-actions">
                <button type="button" className="btn-lg ld-final-btn" onClick={signup}>시작하기 <IconArrowRight size={18} /></button>
                <button type="button" className="btn-lg ld-final-ghost" onClick={login}>로그인</button>
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="ld-foot">
        <div className="ld-inner ld-foot-inner">
          <span className="ld-foot-brand"><LogoMark size={20} /> FTA 원산지 점검 <span className="muted">· 사카밤바스피스</span></span>
          <span>AI의 1차 판정입니다. 최종 판단은 담당자가 합니다.</span>
        </div>
      </footer>

      {modal && <AuthModal mode={modal} onMode={setModal} onClose={() => setModal(null)} />}
    </div>
  )
}

function SectionHead({ no, kicker, title, children }: { no: string; kicker: string; title: string; children?: ReactNode }) {
  return (
    <header className="ld-section-head">
      <p className="ld-kicker"><span className="num">{no}</span>{kicker}</p>
      <h2 className="ld-h2">{title}</h2>
      {children && <p className="ld-section-lead">{children}</p>}
    </header>
  )
}

/** 히어로 오른쪽: 실제 앱 화면을 줄여 그린 견본 + 떠 있는 원본 PDF 조각 · 메일 초안 알림 */
function HeroVisual() {
  return (
    <figure className="ld-visual" aria-label="점검 결과 예시: 원산지증명서 송장번호가 인보이스와 달라 보완 요청">
      <div className="ld-window">
        <div className="ld-window-bar" aria-hidden>
          <span className="ld-dots"><i /><i /><i /></span>
          <span className="ld-window-url">심사목록 › KL HOME SUPPLY</span>
        </div>
        <div className="ld-window-body">
          <div className="ld-mini-list" aria-hidden>
            <span className="ld-mini-group"><i className="tone-danger" />조치 필요</span>
            <span className="ld-mini-row is-active"><b>KL HOME SUPPLY</b><small className="mono">SM-2609-0044</small></span>
            <span className="ld-mini-row"><i style={{ width: '72%' }} /><i style={{ width: '48%' }} /></span>
            <span className="ld-mini-group"><i className="tone-caution" />검토 필요</span>
            <span className="ld-mini-row"><i style={{ width: '64%' }} /><i style={{ width: '40%' }} /></span>
            <span className="ld-mini-group"><i className="tone-success" />특혜 적용</span>
            <span className="ld-mini-row"><i style={{ width: '58%' }} /><i style={{ width: '44%' }} /></span>
          </div>
          <div className="ld-mini-detail">
            <span className="ld-sample-name">KL HOME SUPPLY · FROZEN MACKEREL</span>
            <span className="ld-sample-meta"><span className="mono">SM-2609-0044</span> · 말레이시아 · 한-아세안</span>
            <div className="ld-sample-verdict">
              <span className="badge badge-lg stamp tone-warning">보완 요청</span>
              <p className="ld-sample-reason">원산지증명서 송장번호가 인보이스와 다릅니다.</p>
            </div>
            <div className="ld-sample-cmp">
              <div><DocChip name="원산지증명서" /><span>송장번호</span><span className="mono"><mark className="ld-mark">SM-2609-0815</mark></span></div>
              <div><DocChip name="인보이스" /><span>송장번호</span><span className="mono">SM-2609-0044</span></div>
            </div>
            <p className="ld-sample-next"><span>먼저 할 일</span>C/O 송장번호 불일치 → <b>재발급 요청</b></p>
          </div>
        </div>
      </div>

      <div className="ld-float ld-float-mail" aria-hidden>
        <span className="ld-float-icon"><IconMail size={16} /></span>
        <span className="ld-float-text"><b>메일 초안 준비됨</b><small>상공회의소 · C/O 정정·재발급 요청</small></span>
      </div>
      <div className="ld-float ld-float-pdf" aria-hidden>
        <span className="ld-float-pdf-head">원본 PDF · p.1</span>
        <span className="ld-line" style={{ width: '86%' }} />
        <span className="ld-line" style={{ width: '60%' }} />
        <span className="ld-paper-row"><small>Invoice No.</small><mark className="ld-hl ld-hl-red mono">SM-2609-0815</mark></span>
        <span className="ld-line" style={{ width: '72%' }} />
      </div>
      <figcaption className="ld-sample-cap">예시 데이터</figcaption>
    </figure>
  )
}
