package com.concert.api.integration;

import com.concert.api.repository.UserRepository;
import com.concert.api.service.AuthService;
import com.concert.common.dto.LoginRequest;
import com.concert.common.dto.SessionUser;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import com.concert.common.exception.CustomException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * [Redis 3대 패턴 통합 테스트]
 *
 * 이미 구현된 3가지 Redis 패턴을 실제 Redis에 연결하여 검증한다.
 *   패턴 1: 인증 코드 (String + TTL)
 *   패턴 2: 이메일 큐 (List — LPUSH/RPOP)
 *   패턴 3: 세션 저장소 (Spring Session + Redis Hash)
 *
 * 기존 AuthServiceTest와의 차이점:
 *   - 서비스 계층뿐 아니라 컨트롤러 → 서비스 → Redis 전 구간을 검증
 *   - redis-cli로 직접 확인하던 것을 코드로 자동화
 *   - TTL 만료, 세션 생성/삭제까지 Redis 내부 상태를 검증
 *
 * 주의: Docker로 MySQL(3307), Redis(6379)가 실행 중이어야 합니다.
 *   docker-compose up -d
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisPatternIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private static final String AUTH_PREFIX = "auth:";
    private static final String MAIL_QUEUE = "mail:queue";

    @AfterEach
    void tearDown() {
        // 테스트에서 사용한 Redis 키 정리
        Set<String> authKeys = redisTemplate.keys(AUTH_PREFIX + "*");
        if (authKeys != null && !authKeys.isEmpty()) {
            redisTemplate.delete(authKeys);
        }
        redisTemplate.delete(MAIL_QUEUE);

        // 테스트용 유저 정리
        userRepository.findByEmail("pattern-test@example.com")
                .ifPresent(user -> userRepository.delete(user));
        userRepository.findByEmail("session-test@example.com")
                .ifPresent(user -> userRepository.delete(user));
    }

    // =========================================================================
    // 패턴 1: 인증 코드 (String + TTL)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("[패턴1] 인증 코드가 Redis String으로 저장되고 TTL이 설정된다")
    void pattern1_authCode_storedWithTTL() {
        // Given
        String email = "pattern-test@example.com";

        // When — 인증 코드 발송
        String code = authService.sendVerificationCode(email);

        // Then — Redis에서 직접 확인 (redis-cli GET auth:pattern-test@example.com 과 동일)
        String redisKey = AUTH_PREFIX + email;

        // 1) 코드가 4자리인지
        assertThat(code).hasSize(4);
        assertThat(code).matches("\\d{4}"); // 숫자로만 구성

        // 2) Redis에 실제 저장되었는지
        String savedCode = redisTemplate.opsForValue().get(redisKey);
        assertThat(savedCode).isEqualTo(code);

        // 3) TTL이 설정되었는지 (3분 = 180초, 약간의 오차 허용)
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        assertThat(ttl).isBetween(170L, 180L);

        // 4) 키 타입이 String인지 (redis-cli TYPE auth:... 과 동일)
        String type = redisTemplate.type(redisKey).code();
        assertThat(type).isEqualTo("string");

        System.out.println("=== 패턴 1 검증 결과 ===");
        System.out.println("Redis Key : " + redisKey);
        System.out.println("저장된 코드 : " + savedCode);
        System.out.println("남은 TTL  : " + ttl + "초");
        System.out.println("키 타입   : " + type);
    }

    @Test
    @Order(2)
    @DisplayName("[패턴1] 인증 성공 후 Redis에서 코드가 삭제된다 (재사용 방지)")
    void pattern1_authCode_deletedAfterVerification() {
        // Given — Redis에 인증 코드 세팅
        String email = "pattern-test@example.com";
        String code = "5678";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, 3, TimeUnit.MINUTES);

        SignupRequest request = new SignupRequest();
        request.setEmail(email);
        request.setCode(code);
        request.setName("패턴테스트");
        request.setPassword("pass1234");

        // When — 인증 & 회원가입
        authService.verifyAndSignup(request);

        // Then — Redis에서 코드가 삭제되었는지 확인
        String deletedCode = redisTemplate.opsForValue().get(AUTH_PREFIX + email);
        assertThat(deletedCode).isNull(); // 삭제되었으면 null

        System.out.println("=== 인증 후 코드 삭제 확인 ===");
        System.out.println("Redis GET " + AUTH_PREFIX + email + " → " + deletedCode + " (null = 삭제됨)");
    }

    @Test
    @Order(3)
    @DisplayName("[패턴1] 만료된 인증 코드로 가입 시도하면 실패한다")
    void pattern1_authCode_expiredCodeFails() {
        // Given — TTL 1초로 매우 짧게 설정
        String email = "expired@example.com";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, "1234", 1, TimeUnit.SECONDS);

        // 1.5초 대기 → TTL 만료
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

        SignupRequest request = new SignupRequest();
        request.setEmail(email);
        request.setCode("1234");

        // When & Then — 만료된 코드이므로 예외 발생
        assertThatThrownBy(() -> authService.verifyAndSignup(request))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> {
                    CustomException ce = (CustomException) ex;
                    assertThat(ce.getErrorCode().getCode()).isEqualTo("AUTH_002");
                });

        System.out.println("=== TTL 만료 테스트 통과 ===");
        System.out.println("1초 후 GET → " + redisTemplate.opsForValue().get(AUTH_PREFIX + email) + " (null = 만료됨)");
    }

    // =========================================================================
    // 패턴 2: 이메일 큐 (List — LPUSH/RPOP, FIFO)
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("[패턴2] 회원가입 성공 시 이메일이 Redis List 큐에 LPUSH된다")
    void pattern2_emailQueue_pushedOnSignup() {
        // Given
        String email = "pattern-test@example.com";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, "1111", 3, TimeUnit.MINUTES);

        SignupRequest request = new SignupRequest();
        request.setEmail(email);
        request.setCode("1111");
        request.setName("큐테스트");
        request.setPassword("pass1234");

        // When
        authService.verifyAndSignup(request);

        // Then — 큐에 이메일이 들어갔는지 확인
        // redis-cli LLEN mail:queue 와 동일
        Long queueSize = redisTemplate.opsForList().size(MAIL_QUEUE);
        assertThat(queueSize).isGreaterThanOrEqualTo(1);

        // redis-cli RPOP mail:queue 와 동일 (FIFO이므로 오른쪽에서 꺼냄)
        String queuedEmail = redisTemplate.opsForList().rightPop(MAIL_QUEUE);
        assertThat(queuedEmail).isEqualTo(email);

        // 키 타입이 list인지 확인
        // 주의: 모든 요소를 꺼내면 키 자체가 사라짐 (Redis 특성)

        System.out.println("=== 패턴 2 검증 결과 ===");
        System.out.println("큐 사이즈 (pop 전): " + queueSize);
        System.out.println("꺼낸 이메일: " + queuedEmail);
    }

    @Test
    @Order(5)
    @DisplayName("[패턴2] 여러 회원가입 시 큐에 FIFO 순서로 쌓인다")
    void pattern2_emailQueue_fifoOrdering() {
        // Given — 3명의 인증 코드를 미리 세팅
        String[] emails = {"first@example.com", "second@example.com", "third@example.com"};
        for (String email : emails) {
            redisTemplate.opsForValue().set(AUTH_PREFIX + email, "0000", 3, TimeUnit.MINUTES);
        }

        // When — 순서대로 가입
        for (String email : emails) {
            SignupRequest req = new SignupRequest();
            req.setEmail(email);
            req.setCode("0000");
            req.setName("user");
            req.setPassword("pass");
            authService.verifyAndSignup(req);
        }

        // Then — RPOP으로 꺼내면 넣은 순서대로 나와야 함 (FIFO)
        // LPUSH는 왼쪽에 삽입, RPOP은 오른쪽에서 꺼냄 → 선입선출
        for (String email : emails) {
            String popped = redisTemplate.opsForList().rightPop(MAIL_QUEUE);
            assertThat(popped).isEqualTo(email);
        }

        // 큐가 비어있는지 확인
        Long remaining = redisTemplate.opsForList().size(MAIL_QUEUE);
        assertThat(remaining).isEqualTo(0);

        System.out.println("=== FIFO 순서 검증 통과 ===");
        System.out.println("삽입 순서: first → second → third");
        System.out.println("꺼낸 순서: first → second → third (선입선출 확인)");

        // 테스트 유저 정리
        for (String email : emails) {
            userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
        }
    }

    @Test
    @Order(6)
    @DisplayName("[패턴2] LLEN으로 큐 대기 메시지 수를 모니터링할 수 있다")
    void pattern2_emailQueue_monitoring() {
        // Given — 큐에 직접 5개의 메시지를 넣음
        for (int i = 1; i <= 5; i++) {
            redisTemplate.opsForList().leftPush(MAIL_QUEUE, "user" + i + "@test.com");
        }

        // When — 큐 사이즈 확인 (redis-cli LLEN mail:queue)
        Long size = redisTemplate.opsForList().size(MAIL_QUEUE);

        // Then
        assertThat(size).isEqualTo(5);

        // 전체 목록 조회 (redis-cli LRANGE mail:queue 0 -1)
        var allItems = redisTemplate.opsForList().range(MAIL_QUEUE, 0, -1);
        assertThat(allItems).hasSize(5);

        System.out.println("=== 큐 모니터링 ===");
        System.out.println("LLEN mail:queue → " + size);
        System.out.println("LRANGE mail:queue 0 -1 → " + allItems);
    }

    // =========================================================================
    // 패턴 3: 세션 저장소 (Spring Session + Redis)
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("[패턴3] 로그인 시 Redis에 세션이 생성되고 쿠키가 반환된다")
    void pattern3_session_createdOnLogin() throws Exception {
        // Given — 테스트용 유저 DB에 생성
        User testUser = User.builder()
                .email("session-test@example.com")
                .password("pass1234")
                .name("세션테스트")
                .build();
        userRepository.save(testUser);

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("session-test@example.com");
        loginRequest.setPassword("pass1234");

        // When — 로그인 API 호출
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("session-test@example.com"))
                .andReturn();

        // Then — 세션 쿠키가 반환되었는지 확인
        String sessionCookie = result.getResponse().getHeader("Set-Cookie");
        System.out.println("=== 패턴 3 세션 생성 ===");
        System.out.println("Set-Cookie: " + sessionCookie);

        // Redis에 세션 키가 생성되었는지 확인
        // Spring Session은 concert:session:sessions:{id} 형태로 저장
        Set<String> sessionKeys = redisTemplate.keys("concert:session:*");
        assertThat(sessionKeys).isNotEmpty();

        System.out.println("Redis 세션 키: " + sessionKeys);
    }

    @Test
    @Order(8)
    @DisplayName("[패턴3] 로그아웃 시 세션이 무효화된다")
    void pattern3_session_invalidatedOnLogout() throws Exception {
        // Given — 유저 생성 & 로그인
        if (!userRepository.existsByEmail("session-test@example.com")) {
            User testUser = User.builder()
                    .email("session-test@example.com")
                    .password("pass1234")
                    .name("세션테스트")
                    .build();
            userRepository.save(testUser);
        }

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail("session-test@example.com");
        loginRequest.setPassword("pass1234");

        // 로그인하여 세션 획득
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        // 로그인 후 세션을 가져옴
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        // When — 같은 세션으로 로그아웃
        mockMvc.perform(post("/api/auth/logout")
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // Then — 세션이 무효화되었는지 확인
        assertThat(session.isInvalid()).isTrue();

        System.out.println("=== 세션 무효화 확인 ===");
        System.out.println("세션 ID: " + session.getId());
        System.out.println("세션 상태: " + (session.isInvalid() ? "무효화됨(invalidated)" : "유효"));
    }

    // =========================================================================
    // 전체 플로우 테스트
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("[전체 플로우] 인증코드 발송 → 가입 → 큐 확인 → 로그인 → 세션 확인 → 로그아웃")
    void fullFlow_sendCode_signup_login_logout() throws Exception {
        String email = "fullflow@example.com";

        // === Step 1: 인증 코드 발송 ===
        MvcResult sendCodeResult = mockMvc.perform(post("/api/auth/send-code")
                        .param("email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        // Redis에서 코드 확인
        String code = redisTemplate.opsForValue().get(AUTH_PREFIX + email);
        assertThat(code).isNotNull().hasSize(4);
        System.out.println("Step 1 — 인증코드 발송: " + code);

        // === Step 2: 인증 & 회원가입 ===
        SignupRequest signupReq = new SignupRequest();
        signupReq.setEmail(email);
        signupReq.setCode(code);
        signupReq.setName("풀플로우");
        signupReq.setPassword("password123");

        mockMvc.perform(post("/api/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signupReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 인증 코드 삭제 확인
        assertThat(redisTemplate.opsForValue().get(AUTH_PREFIX + email)).isNull();
        System.out.println("Step 2 — 가입 완료, 인증코드 삭제됨");

        // === Step 3: 이메일 큐 확인 ===
        String queuedEmail = redisTemplate.opsForList().rightPop(MAIL_QUEUE);
        assertThat(queuedEmail).isEqualTo(email);
        System.out.println("Step 3 — 큐에서 꺼냄: " + queuedEmail);

        // === Step 4: 로그인 ===
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail(email);
        loginReq.setPassword("password123");

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andReturn();

        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();
        System.out.println("Step 4 — 로그인 성공, 세션 ID: " + session.getId());

        // === Step 5: 로그아웃 ===
        mockMvc.perform(post("/api/auth/logout").session(session))
                .andExpect(status().isOk());

        assertThat(session.isInvalid()).isTrue();
        System.out.println("Step 5 — 로그아웃 완료, 세션 무효화됨");
        System.out.println("\n=== 전체 플로우 테스트 통과! ===");

        // 정리
        userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
    }
}
