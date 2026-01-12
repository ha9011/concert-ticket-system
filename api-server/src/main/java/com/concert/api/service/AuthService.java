package com.concert.api.service;

import com.concert.api.repository.UserRepository;
import com.concert.common.dto.LoginRequest;
import com.concert.common.dto.SessionUser;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import com.concert.common.exception.CustomException;
import com.concert.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final StringRedisTemplate redisTemplate;
    private final UserRepository userRepository;
    
    private static final String AUTH_PREFIX = "auth:";
    private static final String MAIL_QUEUE = "mail:queue";

    // 1. 인증번호 생성 및 TTL 설정 (중복 검사 포함)
    public String sendVerificationCode(String email) {
        // 중복 여부
        if (userRepository.existsByEmail(email)) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        // 랜덤 코드 4자리
        String code = String.format("%04d", new Random().nextInt(10000));

        // TTL 3분 설정
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, 3, TimeUnit.MINUTES);
        
        return code;
    }

    /**
     * 로그인 처리
     * @param request 로그인 정보 (이메일, 비밀번호)
     * @return 세션에 저장할 유저 정보
     */
    public SessionUser login(LoginRequest request) {
        // 1. 이메일로 유저 조회
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 비밀번호 확인 (⚠️ 현재는 평문 비교, 보안상 암호화 필수!)
        if (!user.getPassword().equals(request.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_PASSWORD);
        }

        // 3. 세션용 DTO 반환
        return new SessionUser(user);
    }

    // 2. 인증번호 검증 및 회원가입 처리
    @Transactional
    public User verifyAndSignup(SignupRequest request) {
        String savedCode = redisTemplate.opsForValue().get(AUTH_PREFIX + request.getEmail());
        
        if (savedCode == null || !savedCode.equals(request.getCode())) {
            throw new CustomException(ErrorCode.INVALID_AUTH_CODE);
        }

        // 인증 성공 -> 회원 저장
        User newUser = User.builder()
                .email(request.getEmail())
                .password(request.getPassword())
                .name(request.getName())
                .build();
        
        userRepository.save(newUser);

        // 인증번호 삭제 (재사용 방지)
        redisTemplate.delete(AUTH_PREFIX + request.getEmail());

        // 가입 축하 메일 큐 전송
        redisTemplate.opsForList().leftPush(MAIL_QUEUE, request.getEmail());
        
        return newUser;
    }
}