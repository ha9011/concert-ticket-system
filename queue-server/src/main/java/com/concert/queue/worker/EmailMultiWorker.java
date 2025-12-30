package com.concert.queue.worker;

import com.concert.common.entity.EmailLog;
import com.concert.queue.repository.EmailLogRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component // 기존 EmailWorker의 @Component는 주석 처리하거나 지워주세요!
@RequiredArgsConstructor
public class EmailMultiWorker {

    private final StringRedisTemplate redisTemplate;
    private final EmailLogRepository emailLogRepository;
    
    // 1. 일감 분배자 (Dispatcher) - 큐에서 꺼내는 역할
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
    
    // 2. 실제 작업자들 (Workers) - 10명이 동시에 처리
    private final ExecutorService workerPool = Executors.newFixedThreadPool(3);

    private static final String MAIL_QUEUE = "mail:queue";

    @PostConstruct
    public void start() {
        dispatcher.execute(this::dispatch);
    }

    // [Dispatcher] 큐 감시 및 할당 (단일 쓰레드)
    private void dispatch() {
        log.info("🔥 멀티 쓰레드 이메일 워커 시작 (Workers: 10)");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Blocking Pop (대기)
                String email = redisTemplate.opsForList().rightPop(MAIL_QUEUE, Duration.ofSeconds(10));

                if (email != null) {
                    // 일감이 있으면 워커 풀에 던짐 (비동기 수행)
                    workerPool.submit(() -> processEmail(email));
                }
            } catch (Exception e) {
                log.error("Dispatch Error", e);
            }
        }
    }

    // [Worker] 실제 작업 수행 (멀티 쓰레드)
    private void processEmail(String email) {
        String threadName = Thread.currentThread().getName();
        log.info("[{}] 🚀 작업 시작: {}", threadName, email);

        try {
            // 오래 걸리는 작업 시뮬레이션 (5초)
            TimeUnit.SECONDS.sleep(5);

            // DB 저장
            EmailLog logEntry = EmailLog.builder()
                    .email(email)
                    .type(EmailLog.EmailType.WELCOME)
                    .message("멀티 쓰레드로 처리된 환영 메일")
                    .build();
            
            emailLogRepository.save(logEntry);
            log.info("[{}] ✅ 작업 완료: {}", threadName, email);

        } catch (Exception e) {
            log.error("[{}] ❌ 작업 실패: {}", threadName, email, e);
        }
    }
}
