package com.concert.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // Auth (400)
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "AUTH_001", "이미 가입된 이메일입니다."),
    INVALID_AUTH_CODE(HttpStatus.BAD_REQUEST, "AUTH_002", "인증번호가 틀렸거나 만료되었습니다."),
    USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "AUTH_003", "가입되지 않은 유저입니다."),
    INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "AUTH_004", "비밀번호가 일치하지 않습니다."),
    
    // Global (500)
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "GLOBAL_001", "서버 내부 오류입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
