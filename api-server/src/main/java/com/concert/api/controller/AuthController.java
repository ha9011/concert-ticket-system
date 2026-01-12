package com.concert.api.controller;

import com.concert.api.service.AuthService;
import com.concert.common.dto.ApiResponse;
import com.concert.common.dto.LoginRequest;
import com.concert.common.dto.SessionUser;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "1. 회원 인증 및 가입", description = "이메일 인증 및 회원가입 관련 API")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하고 세션을 생성합니다.")
    @PostMapping("/login")
    public ApiResponse<SessionUser> login(@RequestBody @Valid LoginRequest request, HttpSession session) {
        SessionUser loginUser = authService.login(request);
        
        // [핵심] 세션에 로그인 유저 정보 저장 (Redis에 저장됨)
        session.setAttribute("USER", loginUser);
        
        return ApiResponse.success(loginUser, "로그인에 성공했습니다.");
    }

    @Operation(summary = "로그아웃", description = "현재 세션을 무효화하여 로그아웃 처리합니다.")
    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpSession session) {
        session.invalidate(); // 세션 삭제
        return ApiResponse.success(null, "로그아웃되었습니다.");
    }

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
