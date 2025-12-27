package com.concert.api.controller;

import com.concert.api.service.AuthService;
import com.concert.common.dto.ApiResponse;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/send-code")
    public ApiResponse<String> sendCode(@RequestParam String email) {
        String code = authService.sendVerificationCode(email);
        return ApiResponse.success("인증번호 발송 완료: " + code);
    }

    @PostMapping("/verify")
    public ApiResponse<Long> verifyAndSignup(@RequestBody SignupRequest request) {
        User user = authService.verifyAndSignup(request);
        return ApiResponse.success(user.getId());
    }
}
