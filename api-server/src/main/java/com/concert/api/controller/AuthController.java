package com.concert.api.controller;

import com.concert.api.service.AuthService;
import com.concert.common.dto.ApiResponse;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "1. 회원 인증 및 가입", description = "이메일 인증 및 회원가입 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "인증번호 발송", description = "이메일 중복 확인 후 4자리 인증번호를 발송(Redis 저장)합니다.")
    @PostMapping("/send-code")
    public ApiResponse<String> sendCode(@RequestParam String email) {
        String code = authService.sendVerificationCode(email);
        return ApiResponse.success(code, "인증번호 발송에 성공했습니다.");
    }

    @Operation(summary = "인증번호 검증 및 가입", description = "인증번호가 일치하면 즉시 회원가입 처리 후 DB에 저장합니다.")
    @PostMapping("/verify")
    public ApiResponse<Long> verifyAndSignup(@RequestBody SignupRequest request) {
        User user = authService.verifyAndSignup(request);
        return ApiResponse.success(user.getId(), "회원가입 축하합니다.");
    }
}
