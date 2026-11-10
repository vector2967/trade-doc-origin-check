package com.tradedoc.origincheck.common;

import org.springframework.http.HttpStatus;

/** API 오류 코드. 응답 본문은 {@link ErrorResponse}. */
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 다시 로그인하세요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않습니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    SIGNUP_NOT_ALLOWED(HttpStatus.FORBIDDEN, "가입이 허용되지 않은 이메일입니다. 관리자에게 문의하세요."),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "PDF 파일을 열 수 없습니다."),
    FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "업로드 한도(합계 100MB)를 넘었습니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF 파일만 올릴 수 있습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),
    ORIGIN_CHECK_NOT_FOUND(HttpStatus.NOT_FOUND, "점검 건을 찾을 수 없습니다."),
    CHECK_NOT_FINISHED(HttpStatus.CONFLICT, "처리 중인 점검 건은 완료 처리할 수 없습니다."),
    CHECK_NOT_FAILED(HttpStatus.CONFLICT, "실패한 점검 건만 다시 실행할 수 있습니다."),
    CHECK_IN_PROGRESS(HttpStatus.CONFLICT, "AI 가 처리 중인 점검 건은 삭제할 수 없습니다. 끝난 뒤 다시 시도하세요."),
    AGENT_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI agent 쪽 파일을 지우지 못했습니다. 잠시 뒤 다시 시도하세요."),
    DEMO_SAMPLES_ONLY(HttpStatus.FORBIDDEN, "체험에서는 체험용 서류 창의 파일만, 한 폴더 안에서 골라 올릴 수 있어요."),
    DEMO_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "체험에서는 점검을 3건까지 할 수 있어요. 가입하면 계속 쓸 수 있어요."),
    DEMO_DAILY_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "오늘 체험 점검이 모두 찼어요. 내일 다시 오거나 가입해서 써 주세요."),
    DEMO_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤에 다시 시도해 주세요."),
    AUTH_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "서버 인증 설정(JWT_SECRET)이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
