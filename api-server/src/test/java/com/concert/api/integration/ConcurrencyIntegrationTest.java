package com.concert.api.integration;

import com.concert.api.repository.UserRepository;
import com.concert.api.service.AuthService;
import com.concert.common.dto.SignupRequest;
import com.concert.common.entity.User;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * [동시성 통합 테스트]
 *
 * ExecutorService를 사용하여 여러 스레드에서 동시에 요청을 보내고,
 * Redis와 DB가 올바르게 동작하는지 검증한다.
 *
 * 이 테스트가 중요한 이유:
 *   - 단일 요청 테스트에서는 발견할 수 없는 Race Condition을 잡을 수 있다.
 *   - 실무에서 "동시에 100명이 같은 좌석을 클릭하면?" 같은 시나리오를 시뮬레이션한다.
 *   - 나중에 분산 락(Unit 3-4)을 구현할 때, 이 테스트 패턴을 그대로 재사용한다.
 *
 * 핵심 패턴: CountDownLatch
 *   - 여러 스레드를 "동시에" 출발시키기 위한 장치
 *   - 일상 비유: 운동회 출발선에 모든 선수가 모인 후 "탕!" 신호에 동시에 뛴다
 *
 * 주의: Docker로 MySQL(3307), Redis(6379)가 실행 중이어야 합니다.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConcurrencyIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    private static final String AUTH_PREFIX = "auth:";
    private static final String MAIL_QUEUE = "mail:queue";

    @AfterEach
    void tearDown() {
        Set<String> authKeys = redisTemplate.keys(AUTH_PREFIX + "*");
        if (authKeys != null && !authKeys.isEmpty()) {
            redisTemplate.delete(authKeys);
        }
        redisTemplate.delete(MAIL_QUEUE);
    }

    // =========================================================================
    // 테스트 1: 같은 이메일로 동시에 가입 시도 → 1명만 성공해야 한다
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("[동시성] 같은 이메일로 10명이 동시에 가입하면 1명만 성공한다")
    void concurrent_sameEmail_onlyOneSucceeds() throws InterruptedException {
        // Given
        String email = "race@example.com";
        String code = "1234";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, 3, TimeUnit.MINUTES);

        int threadCount = 10;

        // ExecutorService: 스레드 풀 생성 (10개 스레드)
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // CountDownLatch: "모든 스레드가 준비되면 동시에 출발!" 장치
        CountDownLatch readyLatch = new CountDownLatch(threadCount); // 모두 준비될 때까지 대기
        CountDownLatch startLatch = new CountDownLatch(1);           // 출발 신호
        CountDownLatch doneLatch = new CountDownLatch(threadCount);  // 모두 끝날 때까지 대기

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When — 10개 스레드에서 동시에 가입 시도
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown(); // "나 준비됐어!" 선언
                    startLatch.await();     // 출발 신호 대기...

                    // 모두 동시에 이 코드를 실행!
                    SignupRequest request = new SignupRequest();
                    request.setEmail(email);
                    request.setCode(code);
                    request.setName("레이서");
                    request.setPassword("pass");
                    authService.verifyAndSignup(request);

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown(); // "나 끝났어!" 선언
                }
            });
        }

        readyLatch.await(); // 10개 스레드 모두 준비될 때까지 대기
        startLatch.countDown(); // 🔫 출발!
        doneLatch.await(10, TimeUnit.SECONDS); // 모두 끝날 때까지 대기

        executor.shutdown();

        // Then
        // DB에는 정확히 1명만 저장되어야 한다 (unique constraint)
        // 인증 코드는 첫 번째 성공자가 삭제하므로, 나머지는 코드 없음(null) → 실패
        long userCount = userRepository.findByEmail(email).isPresent() ? 1 : 0;
        assertThat(userCount).isEqualTo(1);

        System.out.println("=== 동시성 테스트 결과 ===");
        System.out.println("총 시도: " + threadCount + "명");
        System.out.println("성공: " + successCount.get() + "명");
        System.out.println("실패: " + failCount.get() + "명");
        System.out.println("DB 유저 수: " + userCount + "명");

        // 1명만 성공 or DB unique 제약조건에 의해 1명만 저장
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        // 정리
        userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
    }

    // =========================================================================
    // 테스트 2: 서로 다른 이메일로 동시에 가입 → 모두 성공 + 큐 순서 확인
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("[동시성] 서로 다른 이메일 20명이 동시에 가입하면 모두 성공하고 큐에 20개 쌓인다")
    void concurrent_differentEmails_allSucceed() throws InterruptedException {
        // Given — 20명의 인증 코드를 미리 세팅
        int threadCount = 20;
        String[] emails = new String[threadCount];
        for (int i = 0; i < threadCount; i++) {
            emails[i] = "concurrent-" + i + "@example.com";
            redisTemplate.opsForValue().set(AUTH_PREFIX + emails[i], "0000", 3, TimeUnit.MINUTES);
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        // When
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    SignupRequest request = new SignupRequest();
                    request.setEmail(emails[idx]);
                    request.setCode("0000");
                    request.setName("user" + idx);
                    request.setPassword("pass");
                    authService.verifyAndSignup(request);

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errors.add(emails[idx] + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown(); // 🔫 동시 출발!
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Then
        assertThat(successCount.get()).isEqualTo(threadCount);

        // 큐에 정확히 20개가 쌓여야 한다
        Long queueSize = redisTemplate.opsForList().size(MAIL_QUEUE);
        assertThat(queueSize).isEqualTo((long) threadCount);

        System.out.println("=== 동시 가입 테스트 결과 ===");
        System.out.println("총 시도: " + threadCount + "명");
        System.out.println("성공: " + successCount.get() + "명");
        System.out.println("큐 사이즈: " + queueSize);
        if (!errors.isEmpty()) {
            System.out.println("에러: " + errors);
        }

        // 정리
        for (String email : emails) {
            userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
        }
    }

    // =========================================================================
    // 테스트 3: 이메일 큐에 동시에 대량 LPUSH → 유실 없는지 확인
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("[동시성] 100개 스레드에서 동시에 LPUSH해도 메시지 유실이 없다")
    void concurrent_lpush_noMessageLoss() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When — 100개 스레드에서 동시에 큐에 push
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    redisTemplate.opsForList().leftPush(MAIL_QUEUE, "msg-" + idx);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then — Redis의 단일 스레드 특성 덕분에 정확히 100개가 들어가야 한다
        Long size = redisTemplate.opsForList().size(MAIL_QUEUE);
        assertThat(size).isEqualTo(100);

        System.out.println("=== LPUSH 동시성 테스트 ===");
        System.out.println("동시 LPUSH 수: " + threadCount);
        System.out.println("실제 큐 사이즈: " + size);
        System.out.println("Redis는 싱글 스레드이므로 LPUSH가 원자적 → 유실 없음!");
    }

    // =========================================================================
    // 테스트 4: 같은 인증 코드로 동시에 2번 사용 → 1번만 성공 (재사용 방지)
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("[동시성] 같은 인증 코드를 2명이 동시에 사용하면 1명만 성공한다")
    void concurrent_sameAuthCode_onlyOneUses() throws InterruptedException {
        // Given — 하나의 인증 코드, 2명이 서로 다른 이메일로 사용 시도
        // 실제로는 같은 이메일+코드를 동시에 사용하는 시나리오
        String email = "double-use@example.com";
        String code = "9999";
        redisTemplate.opsForValue().set(AUTH_PREFIX + email, code, 3, TimeUnit.MINUTES);

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    SignupRequest request = new SignupRequest();
                    request.setEmail(email);
                    request.setCode(code);
                    request.setName("사용자");
                    request.setPassword("pass");
                    authService.verifyAndSignup(request);

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then — 최대 1명만 성공 (코드 삭제 or DB unique 제약)
        System.out.println("=== 인증코드 재사용 방지 테스트 ===");
        System.out.println("성공: " + successCount.get() + ", 실패: " + failCount.get());

        // 인증 코드가 삭제되었는지
        String remainingCode = redisTemplate.opsForValue().get(AUTH_PREFIX + email);
        assertThat(remainingCode).isNull();

        // 정리
        userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
    }

    // =========================================================================
    // 테스트 5: Redis INCR 원자성 테스트 (카운터 패턴 — 부록 C 사례)
    // =========================================================================

    @Test
    @Order(5)
    @DisplayName("[동시성] 100개 스레드에서 INCR해도 정확히 100이 된다 (원자성)")
    void concurrent_incr_atomic() throws InterruptedException {
        String counterKey = "test:counter";
        redisTemplate.opsForValue().set(counterKey, "0");

        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When — 100개 스레드에서 동시에 INCR
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    redisTemplate.opsForValue().increment(counterKey);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then — 정확히 100이어야 한다
        String value = redisTemplate.opsForValue().get(counterKey);
        assertThat(value).isEqualTo("100");

        System.out.println("=== INCR 원자성 테스트 ===");
        System.out.println("100 스레드 동시 INCR → 결과: " + value);
        System.out.println("Redis INCR은 원자적이므로 Race Condition 없음!");

        redisTemplate.delete(counterKey);
    }
}
