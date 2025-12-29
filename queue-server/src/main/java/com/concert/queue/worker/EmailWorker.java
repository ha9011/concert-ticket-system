package com.concert.queue.worker;

import com.concert.common.entity.EmailLog;
import com.concert.queue.repository.EmailLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailWorker implements CommandLineRunner {

    private final StringRedisTemplate redisTemplate;
    private final EmailLogRepository emailLogRepository;
    
    // 워커 쓰레드 풀 (단일 쓰레드로 순차 처리)
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static final String MAIL_QUEUE = "mail:queue";

    @Override
    public void run(String... args) {
        // 서버 시작 시 별도 쓰레드에서 작업 시작
        executorService.execute(this::processMailQueue);
    }

    private void processMailQueue() {
        log.info("📩 이메일 발송 워커가 시작되었습니다. (Queue: {})", MAIL_QUEUE);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Blocking Pop: 데이터가 없으면 최대 10초 대기, 있으면 즉시 가져옴
                String email = redisTemplate.opsForList().rightPop(MAIL_QUEUE, Duration.ofSeconds(10));
                if (email != null) {
                    log.info("📩 이메일을 전송하겠습니다. (email: {})", email);
                    sendEmailAndLog(email);
                }
            } catch (Exception e) {
                log.error("이메일 처리 중 오류 발생", e);
                // 에러 발생 시 잠시 대기 (무한 루프 방지)
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void sendEmailAndLog(String email) {
        log.info("🚀 이메일 발송 처리 중: {} {}", email, System.currentTimeMillis());
        
        try {
            // 실제 발송 로직 대신 5초 대기 (발송 흉내)
            Thread.sleep(5000);

            // DB에 로그 저장
            EmailLog logEntry = EmailLog.builder()
                    .email(email)
                    .type(EmailLog.EmailType.WELCOME)
                    .message("회원가입을 진심으로 축하합니다!")
                    .build();
            
            emailLogRepository.save(logEntry);
            log.info("✅ 이메일 발송 및 로그 저장 완료: {}", email);

        } catch (Exception e) {
            log.error("이메일 발송 실패: {}", email, e);
            // 실패 시 다시 큐에 넣거나(DLQ), 별도 처리가 필요함
        }
    }
}
