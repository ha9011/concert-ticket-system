package com.concert.api.service;

import com.concert.api.repository.UserRepository;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import com.concert.common.exception.CustomException;
import com.concert.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [서비스 계층 통합 테스트]
 * - 목표: 실제 DB(MySQL)와 Redis에 데이터가 잘 들어가고 나오는지 검증한다.
 * - 환경: @SpringBootTest를 사용하여 실제 빈들을 모두 로드한다.
 */
@SpringBootTest
@Transactional // 테스트가 끝나면 DB 변경 사항(INSERT 등)을 모두 롤백(취소)한다.
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Redis Key 상수 (서비스 코드랑 맞춰야 함)
    private static final String AUTH_PREFIX = "auth:";
    private static final String MAIL_QUEUE = "mail:queue";

    /**
     * [테스트 종료 후 정리 작업]
     * - @Transactional 덕분에 DB는 알아서 비워지지만, Redis는 롤백이 안 됩니다.
     * - 그래서 테스트가 끝날 때마다(@AfterEach) Redis에 넣은 데이터를 직접 지워줘야
     *   다음 테스트가 깨끗한 환경에서 돌아갑니다.
     */
    @AfterEach
    void tearDown() {
        // 테스트에서 사용한 키 패턴을 찾아서 삭제 (실무에선 flushAll 조심!)
        // 여기서는 간단하게 테스트에 쓴 키들만 명시적으로 지우겠습니다.
        redisTemplate.delete(AUTH_PREFIX + "test@example.com");
        redisTemplate.delete(AUTH_PREFIX + "duplicate@example.com");
        redisTemplate.delete(MAIL_QUEUE);
    }

    // =================================================================================
    // 1. sendVerificationCode (인증번호 발송) 테스트
    // =================================================================================

    @Test
    @DisplayName("인증번호 발송 시 Redis에 코드가 저장되어야 한다")
    void sendVerificationCode_Success() {
        // [Given]
        String email = "test@example.com";

        // [When] 실제 서비스 메서드 호출
        String code = authService.sendVerificationCode(email);

        // [Then]
        // 1. 리턴된 코드가 4자리 숫자인지 확인
        assertThat(code).hasSize(4);
        
        // 2. Redis에 실제로 값이 저장되었는지 확인 (가장 중요!)
        String savedCode = redisTemplate.opsForValue().get(AUTH_PREFIX + email);
        assertThat(savedCode).isEqualTo(code);

        // 3. TTL(만료 시간)이 설정되었는지 확인 (대략 180초 근처여야 함)
        Long expire = redisTemplate.getExpire(AUTH_PREFIX + email, TimeUnit.SECONDS);
        assertThat(expire).isGreaterThan(0); // 만료 시간이 존재해야 함
    }

    @Test
    @DisplayName("이미 가입된 이메일로 인증번호 요청 시 예외가 발생해야 한다")
    void sendVerificationCode_Fail_Duplicate() {
        // [Given] 미리 DB에 유저 한 명을 저장해 둠 (@Transactional이라 끝나면 사라짐)
        User existingUser = User.builder()
                .email("duplicate@example.com")
                .name("기존유저")
                .build();
        userRepository.save(existingUser);

        // [When & Then] 중복 이메일로 요청하면 CustomException 터져야 함
        assertThatThrownBy(() -> authService.sendVerificationCode("duplicate@example.com"))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_EMAIL);
    }

    // =================================================================================
    // 2. verifyAndSignup (검증 및 가입) 테스트
    // =================================================================================

    @Test
    @DisplayName("올바른 인증번호 입력 시 회원가입이 완료되고 메일 큐에 쌓여야 한다")
    void verifyAndSignup_Success() {
        // [Given] 1. Redis에 인증번호 미리 세팅 (마치 sendCode를 호출한 것처럼)
        String email = "new@example.com";
        String code = "1234";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, 3, TimeUnit.MINUTES);

        // 2. 가입 요청 객체 생성
        SignupRequest request = new SignupRequest();
        request.setEmail(email);
        request.setCode(code);
        request.setName("신규유저");
        request.setPassword("password");

        // [When] 가입 시도
        User savedUser = authService.verifyAndSignup(request);

        // [Then]
        // 1. 리턴된 유저 정보 확인
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getId()).isNotNull(); // ID가 발급되었는지 (DB 저장 확인)
        assertThat(savedUser.getEmail()).isEqualTo(email);

        // 2. 실제 DB 조회해서 진짜 있는지 재확인
        boolean exists = userRepository.existsByEmail(email);
        assertThat(exists).isTrue();

        // 3. 인증번호 사용 후 Redis에서 삭제되었는지 확인
        String redisCode = redisTemplate.opsForValue().get(AUTH_PREFIX + email);
        assertThat(redisCode).isNull();

        // 4. 메일 발송 큐(List)에 이메일이 들어갔는지 확인
        String queuedEmail = redisTemplate.opsForList().rightPop(MAIL_QUEUE); // 꺼내서 확인
        assertThat(queuedEmail).isEqualTo(email);
    }

    @Test
    @DisplayName("틀린 인증번호 입력 시 예외가 발생해야 한다")
    void verifyAndSignup_Fail_WrongCode() {
        // [Given] Redis엔 "1234"라고 저장해 둠
        String email = "wrong@example.com";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, "1234", 3, TimeUnit.MINUTES);

        // 사용자는 "9999"를 입력함
        SignupRequest request = new SignupRequest();
        request.setEmail(email);
        request.setCode("9999");

        // [When & Then] CustomException(INVALID_AUTH_CODE) 발생해야 함
        assertThatThrownBy(() -> authService.verifyAndSignup(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_AUTH_CODE);
    }
}
