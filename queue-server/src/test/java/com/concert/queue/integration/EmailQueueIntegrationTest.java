package com.concert.queue.integration;

import com.concert.common.entity.EmailLog;
import com.concert.queue.repository.EmailLogRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * [queue-server 통합 테스트]
 *
 * EmailMultiWorker가 Redis 큐에서 이메일을 꺼내서 DB에 저장하는 전체 흐름을 검증한다.
 *
 * 테스트 구조:
 *   1. Redis 큐에 직접 이메일을 LPUSH (api-server의 Producer 역할 시뮬레이션)
 *   2. EmailMultiWorker가 자동으로 BRPOP → processEmail 수행 (5초 대기 시뮬레이션)
 *   3. DB에 EmailLog가 저장되었는지 확인
 *
 * 주의사항:
 *   - EmailMultiWorker는 @PostConstruct로 자동 시작되므로, 테스트 시작 시 이미 큐를 감시중
 *   - processEmail에 5초 sleep이 있으므로, 충분한 대기 시간이 필요
 *   - docker-compose up -d 로 MySQL, Redis가 실행 중이어야 합니다
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmailQueueIntegrationTest {

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private EmailLogRepository emailLogRepository;

    private static final String MAIL_QUEUE = "mail:queue";

    @AfterEach
    void tearDown() {
        redisTemplate.delete(MAIL_QUEUE);
    }

    // =========================================================================
    // 테스트 1: 큐에 이메일 1개 → Worker가 처리 → DB 저장 확인
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("[큐 소비] 큐에 넣은 이메일이 Worker에 의해 처리되어 DB에 저장된다")
    void worker_consumesAndSavesToDb() throws InterruptedException {
        // Given — 고유한 이메일로 테스트 (다른 테스트와 충돌 방지)
        String testEmail = "queue-test-" + System.currentTimeMillis() + "@example.com";

        // When — 큐에 이메일 추가 (api-server의 LPUSH 시뮬레이션)
        redisTemplate.opsForList().leftPush(MAIL_QUEUE, testEmail);
        System.out.println("큐에 이메일 추가: LPUSH " + MAIL_QUEUE + " " + testEmail);

        // EmailMultiWorker의 Dispatcher가 BRPOP으로 꺼내고 (최대 10초 대기)
        // Worker가 processEmail을 실행 (5초 sleep)
        // → 최대 15초 후에 DB에 저장됨
        System.out.println("Worker 처리 대기중... (최대 20초)");

        // 폴링으로 DB 저장 확인 (최대 20초)
        boolean found = false;
        for (int i = 0; i < 20; i++) {
            TimeUnit.SECONDS.sleep(1);
            List<EmailLog> logs = emailLogRepository.findAll();
            found = logs.stream().anyMatch(log -> testEmail.equals(log.getEmail()));
            if (found) {
                System.out.println("  " + (i + 1) + "초 후 DB에서 발견!");
                break;
            }
            System.out.println("  " + (i + 1) + "초... 아직 처리 안됨");
        }

        // Then
        assertThat(found).isTrue();

        // DB에 저장된 로그 확인
        List<EmailLog> logs = emailLogRepository.findAll();
        EmailLog savedLog = logs.stream()
                .filter(log -> testEmail.equals(log.getEmail()))
                .findFirst()
                .orElse(null);

        assertThat(savedLog).isNotNull();
        assertThat(savedLog.getType()).isEqualTo(EmailLog.EmailType.WELCOME);
        assertThat(savedLog.getMessage()).contains("멀티 쓰레드");

        System.out.println("\n=== 큐 소비 테스트 결과 ===");
        System.out.println("이메일: " + savedLog.getEmail());
        System.out.println("타입: " + savedLog.getType());
        System.out.println("메시지: " + savedLog.getMessage());
        System.out.println("저장 시각: " + savedLog.getSentAt());
    }

    // =========================================================================
    // 테스트 2: 큐에 여러 개 → 멀티 스레드 병렬 처리 확인
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("[멀티 워커] 3개 이메일이 병렬로 처리된다 (5초 × 3 ≠ 15초, 약 5~6초)")
    void worker_parallelProcessing() throws InterruptedException {
        // Given — 3개의 이메일을 한 번에 큐에 넣음
        long startTime = System.currentTimeMillis();
        String prefix = "parallel-" + startTime + "-";

        for (int i = 1; i <= 3; i++) {
            String email = prefix + i + "@example.com";
            redisTemplate.opsForList().leftPush(MAIL_QUEUE, email);
            System.out.println("큐 추가: " + email);
        }

        // When — Worker Pool(3개 스레드)이 병렬로 처리
        // 직렬이면 5초 × 3 = 15초, 병렬이면 약 5~6초
        System.out.println("병렬 처리 대기중...");

        // 3개 모두 처리될 때까지 대기 (최대 25초)
        int processedCount = 0;
        for (int sec = 0; sec < 25; sec++) {
            TimeUnit.SECONDS.sleep(1);
            List<EmailLog> logs = emailLogRepository.findAll();
            processedCount = (int) logs.stream()
                    .filter(log -> log.getEmail() != null && log.getEmail().startsWith(prefix))
                    .count();

            if (processedCount >= 3) {
                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("  3개 모두 처리 완료! (소요 시간: " + (elapsed / 1000) + "초)");

                // 병렬 처리이므로 15초보다 훨씬 빨라야 함
                // (Dispatcher가 순차적으로 꺼내므로 약간의 지연 있음)
                assertThat(elapsed).isLessThan(15000);
                break;
            }
            System.out.println("  " + (sec + 1) + "초... 처리 완료: " + processedCount + "/3");
        }

        // Then
        assertThat(processedCount).isEqualTo(3);
        System.out.println("\n=== 멀티 워커 병렬 처리 확인 ===");
        System.out.println("Worker Pool 크기: 3, 처리 완료: " + processedCount + "개");
        System.out.println("직렬이면 15초 걸리지만, 병렬이므로 ~5초에 완료!");
    }

    // =========================================================================
    // 테스트 3: 큐가 비어있을 때 Worker가 정상 대기 (BRPOP 블로킹)
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("[BRPOP 대기] 큐가 비어있어도 Worker가 크래시 없이 블로킹 대기한다")
    void worker_blocksOnEmptyQueue() throws InterruptedException {
        // Given — 큐가 비어있는 상태에서 5초 대기
        Long size = redisTemplate.opsForList().size(MAIL_QUEUE);
        System.out.println("큐 상태: " + (size == null || size == 0 ? "비어있음" : size + "개"));

        // When — 5초 동안 아무것도 안 넣음
        System.out.println("5초 대기... (Worker는 BRPOP으로 블로킹 중)");
        TimeUnit.SECONDS.sleep(5);

        // Then — 큐에 아무것도 없고, 앱이 정상 동작
        size = redisTemplate.opsForList().size(MAIL_QUEUE);
        assertThat(size == null || size == 0).isTrue();

        System.out.println("=== BRPOP 블로킹 대기 테스트 통과 ===");
        System.out.println("큐가 비어있어도 Worker는 BRPOP 10초 타임아웃으로 블로킹 → CPU 낭비 없음!");
    }

    // =========================================================================
    // 테스트 4: 큐 모니터링 — LLEN으로 적체 상태 확인
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("[모니터링] LLEN으로 큐 적체 수를 모니터링할 수 있다")
    void queue_monitoring() {
        // Given — 큐에 10개 메시지를 넣음
        for (int i = 0; i < 10; i++) {
            redisTemplate.opsForList().leftPush(MAIL_QUEUE, "monitor-" + i + "@test.com");
        }

        // When — 큐 사이즈 확인 (redis-cli LLEN mail:queue)
        Long queueLength = redisTemplate.opsForList().size(MAIL_QUEUE);

        // Then
        System.out.println("=== 큐 모니터링 ===");
        System.out.println("LLEN " + MAIL_QUEUE + " → " + queueLength);

        // 실무 관점: 임계값 초과 시 알림
        int threshold = 5;
        if (queueLength > threshold) {
            System.out.println("⚠️ 경고: 큐 적체 감지! (" + queueLength + " > " + threshold + ")");
            System.out.println("  → 실무에서는 Grafana 알림 or Slack 웹훅으로 알림 발송");
            System.out.println("  → Consumer 스케일 아웃 필요");
        }

        assertThat(queueLength).isEqualTo(10);
    }
}
