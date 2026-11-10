// 백엔드 DTO(com.tradedoc.origincheck.*.dto)와 같은 모양. 키는 snake_case, 추출 결과 키만 워크플로 노드 이름(한글).

// ---- 공통 ----
export interface ErrorResponse {
  code: string
  message: string
  path: string
  timestamp: string
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

// ---- 인증 ----
export interface LoginRequest {
  email: string
  password: string
}

export interface SignupRequest {
  email: string
  password: string
  name: string
  company?: string
}

export interface TokenResponse {
  access_token: string
  token_type: 'bearer'
  expires_in: number
}

export interface MeResponse {
  id: number
  email: string
  name: string
  company: string | null
  expires_at: string
  /** 체험 사용자(계정 없이 들어온 사람). 이전 서버는 안 보내므로 없으면 false */
  demo?: boolean
}

/** GET /api/v1/demo/samples — 체험용 서류 폴더 */
export interface DemoSampleFile {
  name: string
  size: number
  hint_type: string | null
}
export interface DemoSampleSet {
  id: string
  name: string
  files: DemoSampleFile[]
}
export interface DemoSamplesResponse {
  sets: DemoSampleSet[]
}

// ---- enum ----
export type CheckStatus = 'queued' | 'in_progress' | 'completed' | 'failed'
export type Decision = '미적용' | '보완 요청' | '검토 필요' | '특혜 적용'
export type FollowUpKind = '보완_요청_초안' | '미적용_안내' | '검토_메모' | '미분류_안내'
export type RequiredDocument = '인보이스' | '원산지증명서' | '원산지확인서' | 'B/L' | '수출신고필증'
export type Readability = 'clear' | 'unclear' | ''

// ---- 점검 ----
export interface OriginCheckCreatedResponse {
  id: number
  status: CheckStatus
}

export interface OriginCheckSummary {
  id: number
  /** 관리번호·메모 (사용자가 넣은 값, 비우면 파일명) */
  title: string
  file_name: string
  status: CheckStatus
  decision: Decision | null
  invoice_no: string | null
  created_at: string
  completed_at: string | null
  /** 담당자 완료 처리 시각. null 이면 진행 중 (PUT/DELETE /origin-checks/{id}/done) */
  done_at?: string | null

  // ↓ [백엔드 추가 요청] 목록에서 "어느 건인지 · 뭐가 문제인지 · 언제 선적했는지"를 바로 보기 위한 요약 필드.
  //   아직 실제 응답에는 없다(가짜 API 에만 있음). 없으면 화면은 제목·파일명·판정으로 대신 보여 준다.
  /** 판정 결과의 불일치 코드 (judge.mismatch_codes) */
  mismatch_codes?: string[]
  /** 판정 결과의 누락 서류 (judge.missing_documents) */
  missing_documents?: string[]
  /** 바이어 (인보이스 consignee) */
  consignee?: string | null
  /** 대표 품목: 인보이스 첫 품목 품명 */
  primary_item?: string | null
  /** 인보이스 품목 수 (대표 품목 외 n건 표시용) */
  item_count?: number | null
  /** 목적국, 한글 표기 (수출신고필증 destination_country 또는 원산지증명서 importing_country) */
  destination_country?: string | null
  /** 적용 협정 (예: 한-아세안) */
  agreement?: string | null
  /** 선적일 YYYY-MM-DD (B/L laden_on_board_date, 없으면 원산지증명서 departure_date) */
  shipped_on?: string | null
}

export interface CheckError {
  code: 'AGENT_ERROR' | 'AGENT_FAILED' | 'AGENT_TIMEOUT' | 'RESULT_PARSE_ERROR' | 'RESULT_INCOMPLETE' | string
  message: string
}

/** GET /api/v1/origin-checks/{id}. completed 전에는 judge·follow_up 이 null, 목록은 비어 있다. */
/** 상세 응답의 원본 파일 하나. page_from~page_to = 원본 PDF(이어 붙인 PDF)에서의 쪽 (1부터) */
export interface CheckFile {
  name: string
  size: number
  page_from: number
  page_to: number
  hint_type: string | null
}

export interface OriginCheckDetail {
  id: number
  title: string
  file_name: string
  status: CheckStatus
  created_at: string
  completed_at: string | null
  done_at?: string | null
  /** 올린 원본 파일들 (올린 순서). 여러 파일 접수 전에 만든 건은 빈 배열 */
  files?: CheckFile[]
  job_id: string | null
  judge: AgentJudge | null
  mismatches: AgentMismatch[]
  follow_up: AgentFollowUp | null
  extracted: AgentExtracted
  extracted_meta: AgentExtractedMeta
  error: CheckError | null
  /** 체험 건이 Upstage 제출을 기다리는 순번(1부터). 체험 건이 아니거나 대기 중이 아니면 null/없음 */
  queue_position?: number | null
}

export interface AgentJudge {
  invoice_no: string | null
  decision: Decision
  reason: string
  mismatch_codes: string[]
  missing_documents: RequiredDocument[] | string[]
  /** 품목별 PSR·HS 비교(백엔드 계산). 2026-11-09 이전 결과엔 없거나 [] */
  item_checks?: ItemCheck[]
}

/** judge.item_checks[] (API 명세 §4.1-1) */
export interface ItemCheck {
  kind: 'PSR' | 'HS'
  item: string
  hs: string | null
  /** PSR: 원산지증명서 결정기준 / HS: 인보이스 HS 10자리 */
  doc_value: string | null
  /** PSR: 관리대장 허용 기준 / HS: 수출신고필증 HS 10자리 */
  rule_value: string | null
  /** PSR: 관리대장의 협정 PSR 원문 */
  rule_text: string | null
  /** PSR: 충족·불충족·확인불가·비교 비대상 / HS: 같음·6단위 같음·다름·확인불가 */
  result: string
}

export interface AgentMismatch {
  code: string
  /** 1=미적용, 2=보완 요청, 3=검토 필요. 모르는 코드면 null */
  stage: 1 | 2 | 3 | null
  label: string
}

export interface AgentFollowUp {
  kind: FollowUpKind
  /** 마크다운 */
  text: string
}

// ---- 추출 결과 (없는 값은 "" / 0) ----
export interface InvoiceItem {
  description: string
  hs_code: string
  quantity: number
  amount: number
}

export interface InvoiceExtraction {
  invoice_no: string
  invoice_date: string
  shipper: string
  consignee: string
  port_of_loading: string
  port_of_discharge: string
  vessel: string
  incoterm: string
  total_amount: number
  origin_declaration: string
  origin_declaration_auth_no: string
  readability: Readability
  items: InvoiceItem[]
}

export interface PackingListExtraction {
  invoice_no: string
  consignee: string
  goods_description: string
  quantity: number
  cartons: number
  net_weight_kg: number
  gross_weight_kg: number
  readability: Readability
}

export interface BillOfLadingExtraction {
  bl_no: string
  shipper: string
  vessel: string
  voyage_no: string
  port_of_loading: string
  port_of_discharge: string
  transshipment_port: string
  goods_description: string
  gross_weight_kg: number
  laden_on_board_date: string
  readability: Readability
}

export interface CertificateOfOriginItem {
  description: string
  quantity: number
  hs_code: string
  origin_criterion: string
  gross_weight_kg: number
  fob_value: number
}

export interface CertificateOfOriginExtraction {
  reference_no: string
  form_type: string
  exporter: string
  consignee: string
  departure_date: string
  vessel: string
  port_of_discharge: string
  invoice_no: string
  invoice_date: string
  exporter_country: string
  importing_country: string
  issue_date: string
  issued_retroactively: boolean
  readability: Readability
  items: CertificateOfOriginItem[]
}

export interface OriginConfirmationItem {
  agreement: string
  hs_code: string
  description: string
  origin_criterion: string
  fulfilled: 'Y' | 'N' | ''
  origin_country: string
  period_from: string
  period_to: string
}

export interface OriginConfirmationExtraction {
  issue_no: string
  supplier: string
  recipient: string
  issue_date: string
  readability: Readability
  items: OriginConfirmationItem[]
}

export interface ExportDeclarationItem {
  hs_code: string
  description: string
  quantity: number
  origin_country: string
}

export interface ExportDeclarationExtraction {
  declaration_no: string
  declaration_date: string
  acceptance_date: string
  exporter: string
  manufacturer: string
  buyer: string
  destination_country: string
  vessel: string
  invoice_no: string
  payment_amount: string
  readability: Readability
  items: ExportDeclarationItem[]
}

export interface AgentExtracted {
  인보이스_추출: InvoiceExtraction[]
  패킹리스트_추출: PackingListExtraction[]
  BL_추출: BillOfLadingExtraction[]
  원산지증명서_추출: CertificateOfOriginExtraction[]
  원산지확인서_추출: OriginConfirmationExtraction[]
  수출신고필증_추출: ExportDeclarationExtraction[]
}

export type ExtractNode = keyof AgentExtracted

// ---- 신뢰도·위치 ----
/** 페이지 크기 대비 0~1 비율 좌표. 왼쪽 위가 (0, 0) */
export interface Point {
  x: number
  y: number
}

export interface FieldMeta {
  confidence: 'high' | 'medium' | 'low' | string
  score: number | null
  /** 1부터, 묶음 PDF 기준. 위치가 없으면 -1 */
  page: number
  /** 단어마다 꼭짓점 4개(왼쪽 위부터 시계 방향) */
  boxes: Point[][]
}

/** 추출 DTO 와 같은 필드에 FieldMeta 가 붙는다. items 는 품목별 같은 구조. */
export type MetaOf<T> = {
  [K in keyof T]?: T[K] extends (infer I)[] ? MetaOf<I>[] : FieldMeta | null
}

export type DocumentMeta<T> = MetaOf<T> & { source_files: Record<string, unknown>[] }

export interface AgentExtractedMeta {
  인보이스_추출: DocumentMeta<InvoiceExtraction>[]
  패킹리스트_추출: DocumentMeta<PackingListExtraction>[]
  BL_추출: DocumentMeta<BillOfLadingExtraction>[]
  원산지증명서_추출: DocumentMeta<CertificateOfOriginExtraction>[]
  원산지확인서_추출: DocumentMeta<OriginConfirmationExtraction>[]
  수출신고필증_추출: DocumentMeta<ExportDeclarationExtraction>[]
}
