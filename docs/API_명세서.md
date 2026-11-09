# FTA 원산지 서류 점검 API 명세서 (v2 — Spring Boot)

> 기준 코드: `backend/` (Java 21 · Spring Boot 4.1). DTO 는 `backend/src/main/java/com/tradedoc/origincheck/**/dto`, 프론트 타입은 `frontend/src/api/types.ts`.
> 파이썬(FastAPI) 버전 명세에서 바뀐 점은 §7.

## 1. 개요

프론트가 선적 1건의 서류 묶음 PDF를 올리면, 백엔드가 접수하고 **비동기로** AI agent(Upstage Studio 워크플로)에 넘긴다. agent 처리는 수 분(최대 5~10분 예상) 걸리므로 HTTP 요청 하나로 기다리지 않는다.

```
프론트                               백엔드 (Spring)                               Upstage
  │ POST /origin-checks (PDF) ───▶ PDF 저장, DB에 점검 건(queued)
  │ ◀── 202 {id, status}
  │                                 [워커, 5초마다]
  │                                 queued  → 파일 업로드 + responses.create ──────▶
  │                                            agent_job_id 저장, in_progress
  │                                 in_progress → responses.retrieve(include=all) ──▶
  │                                            completed 면 노드 출력 → AgentCheckResult 로 바꿔
  │                                            result_json 에 통째로 저장 → completed / failed
  │ GET /origin-checks/{id} (5초마다)
  │ ◀── queued·in_progress: {id, status, …빈 값}
  │ ◀── completed: {id, status, judge, mismatches, follow_up, extracted, extracted_meta}  ← 결과는 한 번에
```

| 항목 | 내용 |
|---|---|
| Base URL | `/api/v1` |
| 인증 | JWT(HS256). `POST /auth/login`·`/auth/signup` 으로 받은 토큰을 `Authorization: Bearer <token>` 으로 보낸다. 로그인·가입·health 외 모두 필요 (§2.1) |
| 호출 방식 | **비동기.** 접수(202) → 상세를 몇 초 간격으로 조회 → `status` 가 `completed`/`failed` 가 되면 끝. 서버를 재시작해도 DB 기준으로 이어서 처리한다 |
| 처리 시간 제한 | 제출 후 30분(`AGENT_TIMEOUT`)이 지나면 `failed` (`AGENT_TIMEOUT`) |
| 제출 재시도 | 제출 중 일시적 오류(연결 끊김·시간 초과·5xx·429)는 `queued` 로 둔 채 10초·20초·40초… 간격으로 최대 5번 다시 한다. 4xx(인증·권한 등)는 바로 `failed` |
| 업로드 | `multipart/form-data`, PDF 1~20개, 합계 100MB |
| 응답 형식 | `application/json; charset=utf-8`. 키는 snake_case, 추출 결과의 키만 워크플로 스키마 이름(한글) 그대로 |
| 값 규칙 | 워크플로가 내는 값(판정·서류명·노드 이름)은 한글 표기 그대로. 추출 결과에서 문서에 없는 값은 문자열 `""`, 숫자 `0`, 참거짓 `false` |
| 권한 | 점검 건은 만든 사용자만 볼 수 있다. 남의 건은 404 |

## 2. 엔드포인트

| Method | Path | 인증 | 설명 |
|---|---|---|---|
| `POST` | `/api/v1/auth/signup` | - | 회원가입. 성공하면 바로 토큰을 준다(자동 로그인) |
| `POST` | `/api/v1/auth/login` | - | 로그인. JWT 발급 |
| `GET` | `/api/v1/auth/me` | O | 내 정보 |
| `POST` | `/api/v1/origin-checks` | O | 서류 묶음 PDF 접수 → 202 |
| `GET` | `/api/v1/origin-checks` | O | 내 점검 목록(최신순, 페이지) |
| `GET` | `/api/v1/origin-checks/{id}` | O | 점검 상세 + 결과 전체 |
| `GET` | `/api/v1/origin-checks/{id}/file` | O | 원본 PDF (PDF 뷰어·하이라이트용) |
| `PUT` | `/api/v1/origin-checks/{id}/done` | O | 담당자 완료 처리 (진행 중 목록에서 뺀다) |
| `DELETE` | `/api/v1/origin-checks/{id}/done` | O | 완료 처리 되돌리기 |
| `POST` | `/api/v1/origin-checks/{id}/retry` | O | 실패한 건 다시 실행 (같은 파일) |
| `DELETE` | `/api/v1/origin-checks/{id}` | O | 점검 건 삭제 (원본 파일 포함) |
| `GET` | `/api/v1/health` | - | `{"ok": true}` |

### 2.1 인증 (JWT)

| 항목 | 내용 |
|---|---|
| 방식 | 이메일·비밀번호로 가입/로그인하면 JWT access token 을 준다. refresh token 은 없다 — 만료되면 다시 로그인 |
| 서명 | HS256, 서버 비밀 키(`JWT_SECRET`, 32바이트 이상)로 서명. 다른 알고리즘·서명 없는 토큰은 거절 |
| 유효 시간 | 기본 60분(`JWT_EXPIRES_MIN`). 응답의 `expires_in`(초) |
| 클레임 | `sub`(사용자 ID), `email`, `iss`(`fta-origin-check-api`), `iat`, `exp` |
| 프론트 처리 | 401 `TOKEN_EXPIRED`·`UNAUTHORIZED` 를 받으면 로그인 화면으로. 토큰은 메모리나 sessionStorage 에 두고, 로그아웃은 토큰을 버리면 된다(서버 로그아웃 API 없음) |
| 비밀번호 저장 | PBKDF2-HMAC-SHA256 60만 회, salt 16바이트. 원문은 저장하지 않는다 |
| 이메일 | 앞뒤 공백을 빼고 소문자로 바꿔 저장·비교한다 |

#### `POST /api/v1/auth/signup` — 회원가입

요청 `application/json` — `SignupRequest`

| 필드 | 타입 | 필수 | 규칙 |
|---|---|---|---|
| `email` | string | O | 이메일 형식, ≤255 |
| `password` | string | O | 8~200자 |
| `name` | string | O | ≤50 |
| `company` | string | | ≤100 |

응답 **201** — `TokenResponse` (아래). 오류: 400 `VALIDATION_ERROR` · 403 `SIGNUP_NOT_ALLOWED`(`SIGNUP_ALLOWED_EMAILS` 에 없는 이메일) · 409 `EMAIL_ALREADY_EXISTS` · 503 `AUTH_NOT_CONFIGURED`

#### `POST /api/v1/auth/login` — 로그인

요청 `{"email": "tester@example.com", "password": "********"}` — `LoginRequest`

응답 200 — `TokenResponse`

| 필드 | 타입 | 설명 |
|---|---|---|
| `access_token` | string | JWT |
| `token_type` | `"bearer"` | 항상 bearer |
| `expires_in` | integer | 토큰 유효 시간(초) |

오류: 400 `VALIDATION_ERROR` · 401 `INVALID_CREDENTIALS`(이메일·비밀번호 중 어느 쪽이 틀렸는지 알려 주지 않음) · 503 `AUTH_NOT_CONFIGURED`

#### `GET /api/v1/auth/me` — 내 정보

응답 200 — `MeResponse`

```json
{"id": 1, "email": "tester@example.com", "name": "테스터", "company": "사카밤바스피스", "expires_at": "2026-11-07T16:11:49Z"}
```

### 2.2 `POST /api/v1/origin-checks` — 점검 접수

| 위치 | 이름 | 타입 | 필수 | 설명 |
|---|---|---|---|---|
| form | `file` | file (`application/pdf`), 1~20개 | O | 한 선적 건의 서류 PDF. 서류마다 따로 올려도 되고(같은 이름 `file` 로 여러 번), 6종을 이어 붙인 PDF 1개도 된다. 합계 100MB (파일 하나는 따로 제한하지 않음) |
| form | `title` | string | | 관리번호·메모(≤200). 비우면 파일명(여러 개면 `<첫 파일> 외 n건.pdf`) |
| form | `hint` | string, `file` 과 같은 수·순서 | | 화면이 파일명으로 짐작한 서류 종류(`인보이스` `패킹리스트` `B/L` `원산지증명서` `원산지확인서` `수출신고필증`). 모르는 값은 버린다. 판정에는 쓰지 않는다 |

파일이 여러 개면 서버가 올린 순서대로 PDF 하나로 이어 붙여 점검한다(워크플로·판정 규칙은 그대로). 원본은 파일별로 보관하고, 상세 응답 `files[]` 에 이어 붙인 PDF 에서의 쪽 범위를 준다(§3).

```bash
curl -X POST "$BASE/api/v1/origin-checks" -H "Authorization: Bearer $TOKEN" \
  -F "file=@set04.pdf;type=application/pdf" -F "title=9월 말레이시아 선적"

# 서류를 따로따로
curl -X POST "$BASE/api/v1/origin-checks" -H "Authorization: Bearer $TOKEN" \
  -F "file=@CI_SM-2609-0044.pdf;type=application/pdf" -F "hint=인보이스" \
  -F "file=@FORM_AK_0815.pdf;type=application/pdf" -F "hint=원산지증명서" \
  -F "file=@scan_0412.pdf;type=application/pdf" -F "hint="
```

응답 **202** — `OriginCheckCreatedResponse`, `Location: /api/v1/origin-checks/{id}`

```json
{"id": 1, "status": "queued"}
```

오류: 400 `INVALID_FILE`(빈 파일·`%PDF`로 시작하지 않음·열 수 없거나 암호가 걸린 PDF·`file` 파트 없음) · 400 `VALIDATION_ERROR`(21개 이상) · 413 `FILE_TOO_LARGE`(합계 100MB 초과) · 415 `UNSUPPORTED_MEDIA_TYPE`(`application/pdf`·`application/octet-stream` 이 아님)

### 2.3 `GET /api/v1/origin-checks` — 내 점검 목록

| 쿼리 | 기본 | 설명 |
|---|---|---|
| `page` | 0 | 0부터 |
| `size` | 20 | 1~100 |
| `status` | | `queued` · `in_progress` · `completed` · `failed` 로 거르기 |

응답 200 — `PageResponse<OriginCheckSummary>`

```json
{
  "items": [
    {"id": 1, "title": "set04 테스트", "file_name": "사카밤바스피스_미션2_set04.pdf", "status": "completed",
     "decision": "보완 요청", "invoice_no": "SM-2609-0044",
     "consignee": "KL HOME SUPPLY SDN. BHD.", "primary_item": "FROZEN MACKEREL", "item_count": 1,
     "destination_country": "MALAYSIA", "shipped_on": "2026-10-07",
     "created_at": "2026-11-07T15:11:53.583786Z", "completed_at": "2026-11-07T15:12:01.088887Z", "done_at": null}
  ],
  "page": 0, "size": 20, "total_elements": 1, "total_pages": 1
}
```

`decision`·`invoice_no`·`completed_at` 은 완료 전에는 null. `done_at` 은 담당자가 완료 처리한 시각, 진행 중이면 null (§2.5-1).

목록 요약 필드 — 점검이 완료될 때 추출 결과에서 뽑아 저장해 둔 값이다(목록 조회 때 `result_json` 을 다시 읽지 않는다).
완료 전(`queued`·`in_progress`·`failed`)이거나 서류에 값이 없으면 null. 같은 종류 서류가 여러 건이면 첫 번째 것을 쓴다.

| 필드 | 타입 | 출처 |
|---|---|---|
| `consignee` | string \| null | 인보이스 consignee(바이어) |
| `primary_item` | string \| null | 인보이스 첫 품목의 품명 |
| `item_count` | int \| null | 인보이스 품목 수("외 n건" 표시용). 품목이 없으면 null |
| `destination_country` | string \| null | 수출신고필증 `destination_country`, 없으면 원산지증명서 `importing_country`. 영문 원문 그대로(예: `MALAYSIA`) |
| `shipped_on` | string(YYYY-MM-DD) \| null | B/L `laden_on_board_date`, 없으면 원산지증명서 `departure_date`. `2026/10/07`·`07 OCT 2026` 같은 표기도 YYYY-MM-DD 로 맞추고, 못 맞추면 null |

### 2.4 `GET /api/v1/origin-checks/{id}` — 점검 상세

응답 200 — `OriginCheckResponse` (§3). 프론트는 `status` 가 `queued`/`in_progress` 인 동안 5초 간격으로 다시 조회한다.

| `status` | 내용 |
|---|---|
| `queued` | 접수됨, agent 제출 전. 결과 필드는 빈 값(`judge`·`follow_up` null, 목록 `[]`, `extracted`·`extracted_meta` 는 키 6개에 빈 배열) |
| `in_progress` | agent 처리 중. `job_id` 가 채워진다. 결과 필드는 빈 값 |
| `completed` | 결과 전체. `error` 가 있으면 결과 일부가 빠졌다는 뜻(`RESULT_INCOMPLETE`) |
| `failed` | `error.code` · `error.message` 에 사유 |

오류: 404 `ORIGIN_CHECK_NOT_FOUND` (없거나 남의 건)

### 2.5 `GET /api/v1/origin-checks/{id}/file` — 원본 PDF

응답 200 `application/pdf`, `Content-Disposition: inline; filename*=UTF-8''…`. 인증 헤더가 필요하므로 프론트는 `fetch` → Blob → object URL 로 뷰어에 넘긴다.

### 2.5-1 `PUT` · `DELETE /api/v1/origin-checks/{id}/done` — 담당자 완료 처리

담당자가 결과를 확인하고 할 일을 마쳤다는 표시. 화면은 `done_at` 이 있는 건을 '완료' 보기로 옮기고 '진행 중' 목록에서 뺀다.
점검 결과(판정)는 바꾸지 않는다.

| 요청 | 동작 | 응답 200 |
|---|---|---|
| `PUT …/done` | `done_at` 을 지금 시각으로. 이미 완료면 처음 시각을 유지 | `OriginCheckSummary` (목록 한 건과 같은 모양) |
| `DELETE …/done` | `done_at` 을 null 로 (진행 중으로 되돌림). 상태와 관계없이 된다 | 〃 |

```json
{"id": 1, "title": "PO-2610-118", "file_name": "사카밤바스피스_미션2_set04.pdf", "status": "completed",
 "decision": "보완 요청", "invoice_no": "SM-2609-0044",
 "consignee": "KL HOME SUPPLY SDN. BHD.", "primary_item": "FROZEN MACKEREL", "item_count": 1,
 "destination_country": "MALAYSIA", "shipped_on": "2026-10-07",
 "created_at": "2026-11-07T15:11:53.583786Z", "completed_at": "2026-11-07T15:12:01.088887Z",
 "done_at": "2026-11-08T12:21:40.120511Z"}
```

오류: 404 `ORIGIN_CHECK_NOT_FOUND` (없거나 남의 건), 409 `CHECK_NOT_FINISHED` (`queued`·`in_progress` 인 건을 완료 처리하려 함)

### 2.5-2 `POST /api/v1/origin-checks/{id}/retry` — 실패한 건 다시 실행

`failed` 인 건을 같은 파일로 다시 점검한다. 파일을 다시 올릴 필요가 없다.

- 응답 `202` `{ "id": 42, "status": "queued" }` — 접수(§2.2)와 같은 모양. 이후 상세를 다시 폴링한다
- 이전 결과·오류·`done_at` 은 지워지고, 제출 재시도 횟수도 처음부터 센다
- 오류: 404 `ORIGIN_CHECK_NOT_FOUND`, 409 `CHECK_NOT_FAILED` (`failed` 가 아닌 건)

### 2.5-3 `DELETE /api/v1/origin-checks/{id}` — 점검 건 삭제

점검 건과 올린 원본 파일·합친 PDF, Upstage 쪽에 남은 파일까지 지운다. 되돌릴 수 없다.

- 응답 `204` (본문 없음)
- `queued`·`completed`·`failed` 는 지울 수 있다. `in_progress`(AI 가 처리 중)는 409 `CHECK_IN_PROGRESS` — 끝난 뒤 다시 시도
- Upstage 쪽 파일을 못 지우면 502 `AGENT_UNAVAILABLE` 이고 아무것도 지우지 않는다(잠시 뒤 다시 시도)
- 오류: 404 `ORIGIN_CHECK_NOT_FOUND`

### 2.6 오류 코드

모든 오류 본문은 `ErrorResponse` `{code, message, path, timestamp}` (§4.8).

| HTTP | code | 언제 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 요청 값 검증 실패(형식·길이·필수), 잘못된 쿼리 값. `message` 에 사유 |
| 400 | `INVALID_FILE` | 빈 파일, PDF 가 아님, `file` 파트 없음 |
| 401 | `UNAUTHORIZED` | 토큰이 없거나 서명·형식이 틀림 |
| 401 | `TOKEN_EXPIRED` | 토큰 만료 — 다시 로그인 |
| 401 | `INVALID_CREDENTIALS` | 로그인 실패 |
| 403 | `SIGNUP_NOT_ALLOWED` | 가입 허용 목록(`SIGNUP_ALLOWED_EMAILS`)에 없는 이메일 |
| 404 | `ORIGIN_CHECK_NOT_FOUND` · `NOT_FOUND` | 점검 건 없음 · 경로 없음 |
| 409 | `EMAIL_ALREADY_EXISTS` | 가입된 이메일 |
| 409 | `CHECK_NOT_FINISHED` | 처리 중인 점검 건을 완료 처리하려 함 |
| 409 | `CHECK_NOT_FAILED` | 실패가 아닌 건을 다시 실행하려 함 |
| 409 | `CHECK_IN_PROGRESS` | AI 가 처리 중인 건을 삭제하려 함 |
| 413 | `FILE_TOO_LARGE` | 합계 100MB 초과 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | PDF 가 아닌 Content-Type |
| 500 | `INTERNAL_ERROR` | 서버 오류 |
| 502 | `AGENT_UNAVAILABLE` | 삭제 중 Upstage 쪽 파일을 지우지 못함 |
| 503 | `AUTH_NOT_CONFIGURED` | 서버에 `JWT_SECRET` 이 없음 |

agent 쪽 실패는 HTTP 오류가 아니라 점검 건의 `status: failed` 와 `error.code` 로 알려 준다.

| `error.code` | 언제 |
|---|---|
| `AGENT_ERROR` | agent 호출 실패. 제출: 4xx 는 바로, 일시적 오류(네트워크·5xx·429)는 재시도를 다 써도 안 될 때. 결과 조회: 4xx(작업 없음·권한 없음)면 바로 (일시적 오류는 시간 초과까지 계속 조회) |
| `AGENT_FAILED` | agent 작업이 failed·cancelled·incomplete 로 끝남 |
| `AGENT_TIMEOUT` | 제출 후 `AGENT_TIMEOUT`(기본 30분) 안에 안 끝남 |
| `RESULT_PARSE_ERROR` | agent 응답을 결과 형식으로 바꾸지 못함 |
| `RESULT_INCOMPLETE` | (status 는 completed) 판정 노드 출력 등 일부가 없음 |

## 3. `OriginCheckResponse` — 점검 상세

점검 건 정보 + AI agent 결과(`AgentCheckResult`)를 한 객체로 준다. 결과 부분은 DB `origin_checks.result_json` 에 저장된 그대로다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | integer | 점검 건 ID |
| `title` | string | 심사 제목 |
| `file_name` | string | 올린 파일명 |
| `status` | `CheckStatus` | `queued` · `in_progress` · `completed` · `failed` |
| `created_at` | string (date-time) | 접수 시각 (UTC) |
| `completed_at` | string (date-time) \| null | 끝난 시각 (completed·failed) |
| `done_at` | string (date-time) \| null | 담당자 완료 처리 시각. 진행 중이면 null |
| `files` | `{name, size, page_from, page_to, hint_type}`[] | 올린 원본 파일(올린 순서). `page_from`~`page_to` = 원본 PDF(§2.5, 이어 붙인 PDF)에서 그 파일이 차지하는 쪽(1부터). `hint_type` = 올릴 때의 짐작, 없으면 null. 이 기능 전에 만든 건은 `[]` |
| `job_id` | string \| null | agent 작업 ID (Upstage responses id) |
| `judge` | `AgentJudge` \| null | 원산지_판정 결과 |
| `mismatches` | `AgentMismatch`[] | mismatch_codes 마다 단계와 화면용 설명 |
| `follow_up` | `AgentFollowUp` \| null | 후속 노드 결과. 특혜 적용이면 null |
| `extracted` | `AgentExtracted` | 6종 서류 추출 결과 |
| `extracted_meta` | `AgentExtractedMeta` | extracted 의 값마다 신뢰도·위치 |
| `error` | `CheckError` \| null | `{code, message}` (§2.6) |

예시(set04, completed, 일부 생략):

```json
{
  "id": 1, "title": "set04 테스트", "file_name": "사카밤바스피스_미션2_set04.pdf", "status": "completed",
  "created_at": "2026-11-07T15:11:53.583786Z", "completed_at": "2026-11-07T15:12:01.088887Z",
  "job_id": "job_5x8z7SKWb8mqQY2WT9Ra6t",
  "judge": {
    "invoice_no": "SM-2609-0044", "decision": "보완 요청",
    "reason": "원산지증명서의 송장번호는 SM-2609-0815이고 인보이스의 송장번호는 SM-2609-0044로 서로 다릅니다. …",
    "mismatch_codes": ["CO_INVOICE_NO_MISMATCH"], "missing_documents": []
  },
  "mismatches": [{"code": "CO_INVOICE_NO_MISMATCH", "stage": 2, "label": "원산지증명서 송장번호 ≠ 인보이스"}],
  "follow_up": {"kind": "보완_요청_초안", "text": "### 요청 메일 초안\n\n…"},
  "extracted": {"인보이스_추출": ["…"], "패킹리스트_추출": ["…"], "BL_추출": ["…"], "원산지증명서_추출": ["…"], "원산지확인서_추출": ["…"], "수출신고필증_추출": ["…"]},
  "extracted_meta": {
    "원산지증명서_추출": [{
      "reference_no": {"confidence": "high", "score": 0.9954, "page": 1,
                       "boxes": [[{"x": 0.6462, "y": 0.0583}, {"x": 0.7427, "y": 0.0579}, {"x": 0.7427, "y": 0.0664}, {"x": 0.6463, "y": 0.0669}]]},
      "…": "필드마다 같은 형식", "items": ["…"], "source_files": [{}]
    }],
    "…": "나머지 5종도 같은 구조"
  },
  "error": null
}
```

## 4. 결과 스키마

### 4.1 `AgentJudge` — `judge`

원산지_판정 노드 결과. 채점 정답지(`answer.json`)의 `answer`와 같은 5개 키다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `invoice_no` | string \| null | 인보이스 송장번호. 인보이스가 없으면 수출신고필증 송품장부호. 못 읽으면 null |
| `decision` | `Decision` | 판정. 특혜 적용 · 보완 요청 · 미적용 · 검토 필요 |
| `reason` | string | 1~2문장, 실제 값 포함 |
| `mismatch_codes` | string[] | 불일치 코드(§5). 없으면 []. 코드표에 없는 값이 와도 그대로 둔다 |
| `missing_documents` | string[] | 빠진 서류(`RequiredDocument` 표기). 없으면 [] |

### 4.2 `AgentMismatch` — `mismatches[]`

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | string | 불일치 코드(§5) |
| `stage` | integer \| null | 1=미적용, 2=보완 요청, 3=검토 필요. 코드표에 없는 코드면 null |
| `label` | string | 화면 표시용 짧은 설명. 코드표에 없는 코드면 코드 그대로 |

### 4.3 `AgentFollowUp` — `follow_up`

| 필드 | 타입 | 설명 |
|---|---|---|
| `kind` | `FollowUpKind` | 후속 노드 이름 |
| `text` | string | 노드 원문(마크다운) |

판정 → `kind`: `보완 요청` → `보완_요청_초안`(요청 메일 초안) / `미적용` → `미적용_안내`(바이어 안내문) / `검토 필요` → `검토_메모`(담당자 메모) / `특혜 적용` → 없음(`null`). 미분류 서류가 있으면 `미분류_안내`가 올 수 있다

### 4.4 `AgentExtracted` — `extracted`

추출 스키마 이름(한글)을 키로 하는 배열 6개. 같은 종류 서류가 2건이면 원소가 2개(`DUPLICATE_DOCUMENT`), 없으면 빈 배열.

| 필드 | 타입 | 설명 |
|---|---|---|
| `인보이스_추출` | `InvoiceExtraction`[] | 인보이스 추출 결과 |
| `패킹리스트_추출` | `PackingListExtraction`[] | 패킹리스트 추출 결과 |
| `BL_추출` | `BillOfLadingExtraction`[] | B/L 추출 결과 |
| `원산지증명서_추출` | `CertificateOfOriginExtraction`[] | 원산지증명서 추출 결과 |
| `원산지확인서_추출` | `OriginConfirmationExtraction`[] | 원산지확인서 추출 결과 |
| `수출신고필증_추출` | `ExportDeclarationExtraction`[] | 수출신고필증 추출 결과 |

### 4.5 추출 결과 (서류별)

#### `인보이스_추출` — `InvoiceExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `invoice_no` | string | 송장번호. 'Invoice No.' 칸 값을 표기 그대로. 예: 'HA-2609-0014'. 없으면 빈 문자열. |
| `invoice_date` | string | YYYY-MM-DD |
| `shipper` | string | Shipper/Exporter 회사명만. 주소는 뺀다. 없으면 빈 문자열. |
| `consignee` | string | Consignee 회사명만. 주소는 뺀다. 없으면 빈 문자열. |
| `port_of_loading` | string | Port of Loading 표기 그대로. 없으면 빈 문자열. |
| `port_of_discharge` | string | Port of Discharge / Final Destination 표기 그대로. 없으면 빈 문자열. |
| `vessel` | string | Vessel 선박명(항차 'V.xxx'는 뺀다). 없으면 빈 문자열. |
| `incoterm` | string | 인도조건 약어와 장소. 예: 'FOB INCHEON'. 없으면 빈 문자열. |
| `total_amount` | number | TOTAL 금액. 통화·쉼표 빼고 숫자만. 없으면 0. |
| `origin_declaration` | string | 한-EU 원산지신고 문안의 원산지 국가. 없으면 빈 문자열 |
| `origin_declaration_auth_no` | string | 원산지신고 문안의 인증수출자 번호(customs authorisation No.). 예: 'KR/1788/347'. 없으면 빈 문자열. |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |
| `items` | `InvoiceItem`[] | 품목 줄마다 하나 |

`items[]` — `InvoiceItem`

| 필드 | 타입 | 설명 |
|---|---|---|
| `description` | string | 품명 표기 그대로. |
| `hs_code` | string | 숫자만, 보통 10자리. 예: 6109100000 |
| `quantity` | number | 수량. 쉼표·단위 빼고 숫자만. |
| `amount` | number | 금액(USD). 쉼표 빼고 숫자만. |

#### `패킹리스트_추출` — `PackingListExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `invoice_no` | string | 패킹리스트에 적힌 송장번호('No. & Date of Invoice' 칸). 표기 그대로. 없으면 빈 문자열. |
| `consignee` | string | To Applicant / Consignee 회사명만. 주소는 뺀다. 없으면 빈 문자열. |
| `goods_description` | string | Goods Description의 품명을 표기 그대로. 없으면 빈 문자열. |
| `quantity` | number | 총 수량. 쉼표 빼고 숫자만. 예: '64,444 PCS' → 64444. 없으면 0. |
| `cartons` | number | 총 포장 수. 'TOTAL: 204 CARTONS' → 204. 없으면 0. |
| `net_weight_kg` | number | 총 순중량(Net Weight) kg. TOTAL 줄의 값. 쉼표 빼고 숫자만. 예: '12,345.0 KG' → 12345.0. 없으면 0. |
| `gross_weight_kg` | number | 총 총중량(Gross Weight) kg. TOTAL 줄의 값. 순중량과 바꿔 넣지 않는다. 없으면 0. |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |

#### `BL_추출` — `BillOfLadingExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `bl_no` | string | B/L 번호. 표기 그대로. 예: 'BL781098'. 없으면 빈 문자열. |
| `shipper` | string | Shipper/Exporter 회사명만. 주소는 뺀다. 없으면 빈 문자열. |
| `vessel` | string | Ocean Vessel(본선) 선박명. 항차는 뺀다. 예: 'EVER GIVEN'. 없으면 빈 문자열. |
| `voyage_no` | string | Voyage No. 예: '8737W'. 없으면 빈 문자열. |
| `port_of_loading` | string | Port of Loading(선적항) 표기 그대로. 예: 'BUSAN, KOREA'. 없으면 빈 문자열. |
| `port_of_discharge` | string | Port of Discharge(양하항) 표기 그대로. 없으면 빈 문자열. |
| `transshipment_port` | string | 환적항. 없으면 빈 문자열 |
| `goods_description` | string | Description of Goods의 품명. 괄호 안 수량은 뺀다. 예: 'MEN'S COTTON T-SHIRTS'. 없으면 빈 문자열. |
| `gross_weight_kg` | number | Gross Weight kg. 쉼표·단위 빼고 숫자만. 예: '13,567.7 KGS' → 13567.7. 없으면 0. |
| `laden_on_board_date` | string | 본선 적재일 YYYY-MM-DD |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |

#### `원산지증명서_추출` — `CertificateOfOriginExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `reference_no` | string | 증명서 번호(Reference No.). 표기 그대로. 예: 'AK-26-00001', 'AK-26-00010-R1'. 없으면 빈 문자열. |
| `form_type` | string | 서식 이름. 예: 'FORM AK'. 없으면 빈 문자열. |
| `exporter` | string | 1번 Goods consigned from의 회사명만. 없으면 빈 문자열. |
| `consignee` | string | 2번 Goods consigned to의 회사명만. 없으면 빈 문자열. |
| `departure_date` | string | 3번 Departure date. YYYY-MM-DD. 없으면 빈 문자열. |
| `vessel` | string | 3번 Vessel's name (항차 'V.xxx'는 뺀다). 없으면 빈 문자열. |
| `port_of_discharge` | string | 3번 Port of Discharge. 없으면 빈 문자열. |
| `invoice_no` | string | 10번 칸 송장번호(원문 값) |
| `invoice_date` | string | 10번 칸의 송장 날짜. YYYY-MM-DD. 없으면 빈 문자열. |
| `exporter_country` | string | 11번 신고문 'produced in' 뒤 국가. 예: 'REPUBLIC OF KOREA'. 없으면 빈 문자열. |
| `importing_country` | string | 11번 신고문 'exported to' 뒤 국가. 예: 'VIETNAM'. 없으면 빈 문자열. |
| `issue_date` | string | 12번 Certification 칸 발급일 |
| `issued_retroactively` | boolean | 'ISSUED RETROACTIVELY' 표시가 있으면 true, 없으면 false. |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |
| `items` | `CertificateOfOriginItem`[] | 품목 줄마다 하나 |

`items[]` — `CertificateOfOriginItem`

| 필드 | 타입 | 설명 |
|---|---|---|
| `description` | string | 7번 칸의 품명(포장 수·수량 문구는 뺀다). 예: 'COTTON T-SHIRTS'. |
| `quantity` | number | 7번 칸 괄호 안 수량. 숫자만. |
| `hs_code` | string | 숫자만, 보통 6자리. 예: 610910 |
| `origin_criterion` | string | 예: WO, CTH, CTC, RVC 40% |
| `gross_weight_kg` | number | 9번 칸 총중량 KGS. 숫자만. |
| `fob_value` | number | 9번 칸 FOB 금액(USD). 쉼표 빼고 숫자만. |

#### `원산지확인서_추출` — `OriginConfirmationExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `issue_no` | string | 1. 발급번호. 표기 그대로. 예: 'OC-2026-0001'. 없으면 빈 문자열. |
| `supplier` | string | 2. 공급하는 자의 상호만. 예: '(주)한빛니트'. 없으면 빈 문자열. |
| `recipient` | string | 3. 공급받는 자의 상호만. 없으면 빈 문자열. |
| `issue_date` | string | 끝의 작성일. YYYY-MM-DD. 없으면 빈 문자열. |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |
| `items` | `OriginConfirmationItem`[] | 품목 줄마다 하나 |

`items[]` — `OriginConfirmationItem`

| 필드 | 타입 | 설명 |
|---|---|---|
| `agreement` | string | 협정명칭. 예: 한-아세안 FTA |
| `hs_code` | string | 숫자만, 적힌 자릿수 그대로(테스트 데이터는 6자리) |
| `description` | string | 7. 품명·규격. |
| `origin_criterion` | string | 예: WO, CTH, CTSH, BD |
| `fulfilled` | `"Y"` \| `"N"` \| `""` | 충족 여부 |
| `origin_country` | string | 11. 원산지. 예: 'KR'. |
| `period_from` | string | 포괄확인기간 시작일. 단수 확인서면 빈 문자열 |
| `period_to` | string | 12. 포괄확인기간 종료일. YYYY-MM-DD. 없으면 빈 문자열. |

#### `수출신고필증_추출` — `ExportDeclarationExtraction`

| 필드 | 타입 | 설명 |
|---|---|---|
| `declaration_no` | string | 신고번호 표기 그대로. 없으면 빈 문자열. |
| `declaration_date` | string | 신고일자. YYYY-MM-DD로 정규화. 예: 2026/09/09 → 2026-09-09. 없으면 빈 문자열. |
| `acceptance_date` | string | 신고수리일자. YYYY-MM-DD. 신고일자가 아니다. 없으면 빈 문자열. |
| `exporter` | string | 수출대행자·수출화주 상호(괄호 안 사업자번호는 뺀다). 없으면 빈 문자열. |
| `manufacturer` | string | 제조자 상호. 없으면 빈 문자열. |
| `buyer` | string | 구매자 상호. 없으면 빈 문자열. |
| `destination_country` | string | 목적국. 예: 'VIETNAM'. 없으면 빈 문자열. |
| `vessel` | string | 선박명. 없으면 빈 문자열. |
| `invoice_no` | string | 송품장부호 |
| `payment_amount` | string | 표기 그대로. 예: FOB-USD-35,100.00 |
| `readability` | `"clear"` \| `"unclear"` \| `""` | 핵심 값을 모두 읽었으면 clear, 아니면 unclear. 서류가 없으면 빈 문자열 |
| `items` | `ExportDeclarationItem`[] | 품목 줄마다 하나 |

`items[]` — `ExportDeclarationItem`

| 필드 | 타입 | 설명 |
|---|---|---|
| `hs_code` | string | 세번부호, 숫자만 10자리 |
| `description` | string | 거래품명. |
| `quantity` | number | 수량. 쉼표·단위 빼고 숫자만. |
| `origin_country` | string | 원산지. 예: 'KR'. |

### 4.6 `AgentExtractedMeta` — `extracted_meta`

`extracted`와 **같은 구조**(스키마 이름 → 문서 배열 → 필드, `items`는 품목 배열)이고, 값마다 `FieldMeta`가 붙는다. n번째 문서의 메타는 `extracted`의 같은 키 n번째 문서의 것이다. 문서마다 `source_files`(예시에서는 `[{}]`)가 붙는다.

| 필드 | 타입 | 설명 |
|---|---|---|
| `인보이스_추출` | `InvoiceMeta`[] | 인보이스 추출 결과의 필드별 메타 |
| `패킹리스트_추출` | `PackingListMeta`[] | 패킹리스트 추출 결과의 필드별 메타 |
| `BL_추출` | `BillOfLadingMeta`[] | B/L 추출 결과의 필드별 메타 |
| `원산지증명서_추출` | `CertificateOfOriginMeta`[] | 원산지증명서 추출 결과의 필드별 메타 |
| `원산지확인서_추출` | `OriginConfirmationMeta`[] | 원산지확인서 추출 결과의 필드별 메타 |
| `수출신고필증_추출` | `ExportDeclarationMeta`[] | 수출신고필증 추출 결과의 필드별 메타 |

#### `FieldMeta`

| 필드 | 타입 | 설명 |
|---|---|---|
| `confidence` | string | 신뢰도 등급. 예시 값은 "high", "medium", "low" |
| `score` | number \| null | 신뢰도 점수 (0~1) |
| `page` | integer | 값이 있는 페이지(1부터, 묶음 PDF 기준). 위치가 없으면 -1 |
| `boxes` | `Point`[][] | 값이 적힌 영역. 단어마다 꼭짓점 4개(왼쪽 위부터 시계 방향)의 다각형 |

#### `Point`

| 필드 | 타입 | 설명 |
|---|---|---|
| `x` | number | 가로 위치. 페이지 너비 대비 0~1 |
| `y` | number | 세로 위치. 페이지 높이 대비 0~1 |

**좌표 쓰는 법 (프론트):** 원본 PDF(`GET /api/v1/origin-checks/{id}/file`)를 렌더링(pdf.js 등)하고, `page`번째 페이지 위에 `x × 페이지 너비`, `y × 페이지 높이`로 다각형을 그린다. 백엔드는 페이지 이미지를 주지 않는다. `page`는 묶음 PDF 전체 기준 페이지 번호다.

**신뢰도 표시:** `confidence`가 `low`인 값은 화면에서 "확인 필요"로 표시한다. 예시(set04)에서는 인보이스 `total_amount`, `origin_declaration`, `origin_declaration_auth_no`, B/L `transshipment_port`, `readability`(B/L·원산지확인서·수출신고필증)가 `low`다.

### 4.7 enum

| 이름 | 값 |
|---|---|
| `Decision` | `미적용` · `보완 요청` · `검토 필요` · `특혜 적용` |
| `RequiredDocument` | `인보이스` · `원산지증명서` · `원산지확인서` · `B/L` · `수출신고필증` (패킹리스트는 선택 서류라 없음) |
| `FollowUpKind` | `보완_요청_초안` · `미적용_안내` · `검토_메모` · `미분류_안내` |
| `CheckStatus` | `queued` · `in_progress` · `completed` · `failed` |
| `MismatchCode` | §5 |

### 4.8 `ErrorResponse` (모든 오류 공통)

| 필드 | 타입 | 설명 |
|---|---|---|
| `code` | string | §2.6 오류 코드 |
| `message` | string | 사람이 읽는 오류 설명 |
| `path` | string | 요청 경로 |
| `timestamp` | string (date-time) | 발생 시각 (ISO-8601, UTC) |

## 5. 불일치 코드표

판정은 코드의 단계로 정해진다(`mismatches[].stage`와 같다): 1단계 코드가 하나라도 있으면 미적용 → 아니면 2단계 코드 또는 인보이스·원산지증명서·원산지확인서 누락이면 보완 요청 → 아니면 3단계 코드 또는 B/L·수출신고필증 누락·판독 불확실이면 검토 필요 → 아무것도 없으면 특혜 적용.

| 단계 | 판정 | 코드 | 조건 |
|---|---|---|---|
| 1 | 미적용 | `SUP_CRITERION_NOT_MET` | 원산지확인서 충족 여부(fulfilled)가 N |
| 1 | 미적용 | `SUP_ORIGIN_MISMATCH` | 원산지확인서 원산지가 KR이 아님 |
| 2 | 보완 요청 | `CO_INVOICE_NO_MISMATCH` | 원산지증명서 invoice_no와 인보이스 invoice_no가 다름 |
| 2 | 보완 요청 | `CO_QTY_MISMATCH` | 원산지증명서 품목 수량 합계와 인보이스 품목 수량 합계가 다름 |
| 2 | 보완 요청 | `CO_CRITERION_MISMATCH` | 원산지증명서 결정기준이 대응표상 원산지확인서 결정기준과 다름 |
| 2 | 보완 요청 | `CO_ORIGIN_MISMATCH` | 원산지증명서 생산국과 원산지확인서 원산지가 다름 |
| 2 | 보완 요청 | `CO_CONSIGNEE_MISMATCH` | 원산지증명서 수하인이 다른 회사 |
| 2 | 보완 요청 | `CO_DESCRIPTION_MISMATCH` | 원산지증명서 품명이 다른 물품 |
| 2 | 보완 요청 | `CO_HS_MISMATCH` | HS 코드 앞 6자리가 서류마다 다름 |
| 2 | 보완 요청 | `SUP_EXPIRED` | 원산지확인서 포괄확인기간이 수출신고일을 포함하지 않음 |
| 2 | 보완 요청 | `SUP_RECIPIENT_MISMATCH` | 원산지확인서 공급받는 자와 인보이스 수출자가 다름 |
| 3 | 검토 필요 | `DUPLICATE_DOCUMENT` | 같은 종류 문서가 2건 이상 |
| 3 | 검토 필요 | `EXPDEC_INVOICE_NO_MISMATCH` | 수출신고필증 송품장부호와 인보이스 invoice_no가 다름 |
| 3 | 검토 필요 | `CO_HS_MINOR_DIFF` | 10자리 HS가 앞 6자리는 같고 뒤 4자리가 다름 |
| 3 | 검토 필요 | `CO_TRANSPORT_MISMATCH` | 원산지증명서 선박·양하항이 B/L과 다르거나 B/L에 환적항이 있음 |
| 3 | 검토 필요 | `CO_ISSUE_DATE_LATE` | 원산지증명서 발급일이 적재일보다 3일 넘게 늦고 소급발급 표시 없음 |
| 3 | 검토 필요 | `SUP_AGREEMENT_MISMATCH` | 원산지확인서 협정명칭이 목적국 협정 대응표와 다름 |
| 3 | 검토 필요 | `CO_CRITERION_NOT_PSR` | 원산지증명서 결정기준이 협정 품목별원산지기준(PSR)에 없음 |
| 3 | 검토 필요 | `EXPDEC_QTY_MISMATCH` | 수출신고필증 수량 합계와 인보이스 수량 합계가 다름 |
| 3 | 검토 필요 | `QTY_MISMATCH` | 패킹리스트 수량과 인보이스 수량이 다름 |
| 3 | 검토 필요 | `NET_GT_GROSS` | 패킹리스트 순중량이 총중량보다 큼 |

## 6. Upstage 연동 (백엔드 내부)

`UpstageClient` 가 파이썬 버전의 openai SDK 호출을 그대로 옮겼다(OpenAI 호환 API, `https://api.upstage.ai/v2`, 헤더 `Authorization: Bearer <UPSTAGE_API_KEY>`, `x-project-id`).

실제 호출로 확인함(2026-11-08, set04 → 35초 만에 `보완 요청` · `CO_INVOICE_NO_MISMATCH`, 6종 추출·좌표 모두 채워짐).
- 에이전트 `agt_5HHQZgemm6hmRVLHyGu4dG`, 설정 `7`(2026-11-09부터, 이전 `5`), 프로젝트 `proj_UVMuo7ZXt5heYMoei8ocJH` (Studio "API로 이용하기" 화면 값).
- `x-project-id` 헤더가 **필요하다.** 빼면 파일이 키의 기본 프로젝트에 올라가서 작업 생성이 403 `No access to file` 로 실패한다. 키가 그 프로젝트에 권한이 없으면 403 `project_access_forbidden`.
- `include[]=all` 쿼리로 모든 노드 출력을 받는다.

| 단계 | 호출 |
|---|---|
| 1. PDF 업로드 | `POST /files` multipart (`file`, `purpose=user_data`) → `id` |
| 2. 워크플로 실행 | `POST /responses` `{"model": <agent_id>, "input": [{"role": "user", "content": [{"type": "input_file", "file_id": …}]}], "config_id": <config_id>}` → `id`, `status` |
| 3. 결과 조회 | `GET /responses/{id}?include[]=all` — 기본값(`last`)은 마지막 노드만 주므로 `all` 이 필요 |

`UpstageResponseParser` 가 3의 응답을 `AgentCheckResult` 로 바꾼다(파이썬 `to_check_result`).
- `output[].model` 에 노드 이름이 들어 있다(`Information Extract - 인보이스_추출`, `Instruct - 원산지_판정` 등).
- 판정 노드 텍스트는 JSON 이거나 5줄 `key: value` 다. 아는 키로 시작하지 않는 줄은 앞 값에 이어 붙인다(설정 7은 reason 끝에 품목별 PSR·HS 비교를 한 줄씩 적는다). `additional_values.enum`(분기에 실제로 쓰인 결정값)이 있으면 그 값을 우선한다. decision 이 모르는 값이면 `검토 필요`.
- 추출 노드 `additional_values` 의 `{_value, confidence, confidence_score, page, word_coordinates}` → `FieldMeta`.
- 인용 표시 `【†1】` 는 지운다.

로컬·프론트 개발 때는 `AGENT_MODE=fake` 로 Upstage 를 부르지 않고 `AGENT_FAKE_RESULT`(예: `src/test/resources/check_result_set04.json`)를 `AGENT_FAKE_DELAY` 뒤에 결과로 돌려준다.

## 7. 파이썬(FastAPI) 버전에서 바뀐 점

| 항목 | 이전 | 지금 |
|---|---|---|
| 호출 방식 | `POST /origin-checks` 하나로 결과까지(동기) | 접수 202 + 상세 조회(비동기). agent 처리가 5~10분 걸릴 수 있어서 |
| 엔드포인트 | login · me · origin-checks | + signup, 목록, 상세, 원본 PDF, health |
| 로그인 | `username` | `email` (화면명세서 M-01·M-02 기준) |
| 사용자 저장 | 관리자가 만든 JSON 파일 | MySQL `users` 테이블 |
| 결과 저장 | 없음 | MySQL `origin_checks.result_json` |
| 응답 최상위 | `AgentCheckResult` (`job_id`, `status`, …) | `OriginCheckResponse` = 점검 건 정보 + `AgentCheckResult` 필드 |
| `error` | any | `{code, message}` \| null |
| `mismatch_codes` | enum | 문자열(모르는 코드가 와도 응답이 깨지지 않게). `mismatches[].stage` 는 모르는 코드면 null |
| 코드표 | 20개 | 21개 (`CO_CRITERION_NOT_PSR` 추가 — 동료 레포 판정 프롬프트 기준) |
| `FollowUpKind` | 3개 | 4개 (`미분류_안내` 추가) |
| 검증 오류 | 422 (FastAPI 기본) | 400 `VALIDATION_ERROR` |

## 8. 아직 정해지지 않은 것

| # | 항목 | 지금 가정 |
|---|---|---|
| 1 | 미분류 문서 결과의 위치 | `미분류_안내` 노드가 오면 `follow_up` 에 넣는다 |
| 2 | 서버 여러 대 운영 | 워커가 같은 건을 두 번 제출할 수 있다. 여러 대로 늘릴 때 행 잠금 필요 |
| 3 | 로그인 시도 제한, 비밀번호 변경·탈퇴, 심사 삭제 | 없음 |
| 4 | 파일 저장소 | 로컬 디스크(`STORAGE_DIR`). S3 등은 `FileStorage` 구현만 추가 |

## 9. 서버 설정 (환경 변수)

루트 `.env.example` 참고. 백엔드는 `../.env` 를 읽는다.

| 이름 | 기본 | 설명 |
|---|---|---|
| `DB_HOST` · `DB_PORT` · `DB_NAME` · `DB_USERNAME` · `DB_PASSWORD` | localhost · 3306 · origin_check · origin_check · origin_check | MySQL. 스키마는 Flyway(`db/migration`)가 만든다 |
| `JWT_SECRET` | (없음) | 32바이트 이상. 없으면 인증 API 가 503 |
| `JWT_EXPIRES_MIN` | 60 | 토큰 유효 시간(분) |
| `SIGNUP_ALLOWED_EMAILS` | (없음 = 누구나) | 가입 허용 이메일. 쉼표로 여러 개, `@acme.com` 은 도메인 전체. 공개 서버에서는 꼭 채운다 |
| `CORS_ALLOWED_ORIGINS` | http://localhost:5173 | 쉼표로 여러 개 |
| `AGENT_MODE` | upstage | `upstage` · `fake` |
| `AGENT_FAKE_RESULT` · `AGENT_FAKE_DELAY` | · 10s | fake 모드 결과 파일 · 지연 |
| `AGENT_POLL_INTERVAL_MS` | 5000 | 워커 주기 |
| `AGENT_TIMEOUT` | 30m | 처리 시간 제한 |
| `AGENT_SUBMIT_MAX_ATTEMPTS` | 5 | 일시적 오류일 때 제출 최대 시도 수 |
| `AGENT_SUBMIT_RETRY_DELAY` | 10s | 첫 재시도 대기(시도마다 두 배, 최대 10분) |
| `UPSTAGE_API_KEY` | (없음) | upstage 모드에서 필수. 코드·저장소에 적지 않는다 |
| `STORAGE_DIR` | ./data/uploads | 원본 PDF 저장 위치(운영 이미지는 /data/uploads) |
| `UPSTAGE_PROJECT_ID` · `UPSTAGE_AGENT_ID` · `UPSTAGE_CONFIG_ID` | proj_UVMuo7ZXt5heYMoei8ocJH · agt_5HHQZgemm6hmRVLHyGu4dG · 7 | §6 |
| `STORAGE_DIR` | ./data/uploads | 업로드 PDF 저장 위치 |
