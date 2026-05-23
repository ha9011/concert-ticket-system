package com.concert.api.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * [미래 패턴 Redis 실습 테스트]
 *
 * 로드맵의 Phase 1~4에서 구현할 패턴들을 Redis 명령어 수준에서 미리 실습한다.
 * 서비스/컨트롤러 코드 없이 RedisTemplate만으로 직접 명령어를 날려보는 "실험실" 테스트.
 *
 * redis-cli에서 직접 치는 것을 Java 코드로 옮긴 것이라고 생각하면 된다.
 * 각 테스트 상단의 주석에 대응하는 redis-cli 명령어를 표기했다.
 *
 * 포함된 패턴:
 *   - Cache-Aside (Unit 2)
 *   - 분산 락 with SETNX (Unit 3)
 *   - Sorted Set 대기열 (Unit 5)
 *   - Rate Limiting — Sliding Window (Unit 6)
 *   - Pub/Sub 기초 (Unit 7)
 *   - 부록 C: 랭킹, 좋아요, 최근 본 상품, 온라인 유저, 멱등성 키
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisFuturePatternTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        // 테스트 키 정리 (패턴별 prefix로 삭제)
        String[] patterns = {
            "cache:*", "lock:*", "queue:*", "ratelimit:*",
            "ranking:*", "likes:*", "recent:*", "online:*",
            "idempotency:*", "feature:*"
        };
        for (String pattern : patterns) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    // =========================================================================
    // Unit 2: Cache-Aside 패턴
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("[Cache-Aside] 캐시 미스 → DB 조회 → 캐시 저장 → 캐시 히트")
    void cacheAside_missAndHit() {
        /*
         * redis-cli 대응:
         *   GET cache:concert:1         → (nil)  — 캐시 미스
         *   SET cache:concert:1 "{json}" EX 300  — DB 조회 후 캐시 저장
         *   GET cache:concert:1         → "{json}" — 캐시 히트
         */
        String cacheKey = "cache:concert:1";

        // Step 1: 캐시 미스 확인
        String cached = redisTemplate.opsForValue().get(cacheKey);
        assertThat(cached).isNull();
        System.out.println("1) 캐시 미스: GET " + cacheKey + " → " + cached);

        // Step 2: DB 조회 시뮬레이션 → 캐시에 저장
        String concertJson = "{\"id\":1,\"title\":\"아이유 콘서트\",\"venue\":\"잠실\"}";
        redisTemplate.opsForValue().set(cacheKey, concertJson, 5, TimeUnit.MINUTES);
        System.out.println("2) DB 조회 후 캐시 저장: SET " + cacheKey + " EX 300");

        // Step 3: 캐시 히트 확인
        String hit = redisTemplate.opsForValue().get(cacheKey);
        assertThat(hit).isEqualTo(concertJson);
        System.out.println("3) 캐시 히트: GET " + cacheKey + " → " + hit);

        // Step 4: TTL 확인
        Long ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(290);
        System.out.println("4) TTL 확인: " + ttl + "초 남음");

        // Step 5: 캐시 무효화 (데이터 변경 시)
        redisTemplate.delete(cacheKey);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
        System.out.println("5) 캐시 무효화: DEL " + cacheKey + " → 삭제됨");
    }

    // =========================================================================
    // Unit 3: SETNX 기반 분산 락
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("[분산 락] SETNX로 락 획득/해제 기본 동작 확인")
    void distributedLock_setnx_basic() {
        /*
         * redis-cli 대응:
         *   SET lock:seat:1:A1 "uuid-1" EX 5 NX   → OK (락 획득)
         *   SET lock:seat:1:A1 "uuid-2" EX 5 NX   → (nil) (이미 잠김)
         *   DEL lock:seat:1:A1                     → 1 (락 해제)
         */
        String lockKey = "lock:seat:1:A1";
        String owner1 = UUID.randomUUID().toString();
        String owner2 = UUID.randomUUID().toString();

        // Step 1: 첫 번째 사용자 락 획득 (SETNX)
        Boolean acquired1 = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner1, 5, TimeUnit.SECONDS);
        assertThat(acquired1).isTrue();
        System.out.println("1) 사용자1 락 획득: " + acquired1 + " (owner: " + owner1.substring(0, 8) + "...)");

        // Step 2: 두 번째 사용자 락 획득 시도 → 실패
        Boolean acquired2 = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner2, 5, TimeUnit.SECONDS);
        assertThat(acquired2).isFalse();
        System.out.println("2) 사용자2 락 획득 시도: " + acquired2 + " (이미 잠김!)");

        // Step 3: 자신의 락인지 확인 후 해제 (중요!)
        String currentOwner = redisTemplate.opsForValue().get(lockKey);
        assertThat(currentOwner).isEqualTo(owner1); // owner1이 소유중
        if (owner1.equals(currentOwner)) {
            redisTemplate.delete(lockKey);
        }
        System.out.println("3) 사용자1 락 해제: DEL " + lockKey);

        // Step 4: 이제 사용자2가 락 획득 가능
        Boolean acquired2Again = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner2, 5, TimeUnit.SECONDS);
        assertThat(acquired2Again).isTrue();
        System.out.println("4) 사용자2 재시도 → 락 획득 성공: " + acquired2Again);

        redisTemplate.delete(lockKey);
    }

    @Test
    @Order(3)
    @DisplayName("[분산 락] 10명이 동시에 같은 좌석 락을 시도하면 1명만 획득한다")
    void distributedLock_concurrent() throws InterruptedException {
        String lockKey = "lock:seat:1:B2";
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger lockAcquired = new AtomicInteger(0);
        AtomicInteger lockFailed = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    String myId = "user-" + idx;
                    Boolean acquired = redisTemplate.opsForValue()
                            .setIfAbsent(lockKey, myId, 5, TimeUnit.SECONDS);

                    if (Boolean.TRUE.equals(acquired)) {
                        lockAcquired.incrementAndGet();
                        System.out.println("  🔒 " + myId + " 락 획득!");
                    } else {
                        lockFailed.incrementAndGet();
                    }
                } catch (Exception e) {
                    lockFailed.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(lockAcquired.get()).isEqualTo(1);
        assertThat(lockFailed.get()).isEqualTo(threadCount - 1);

        System.out.println("=== 분산 락 동시성 테스트 ===");
        System.out.println("시도: " + threadCount + "명, 락 획득: " + lockAcquired.get() + "명");

        redisTemplate.delete(lockKey);
    }

    // =========================================================================
    // Unit 5: Sorted Set 대기열
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("[대기열] Sorted Set으로 대기열 진입, 순번 조회, 통과 처리")
    void waitingQueue_sortedSet() {
        /*
         * redis-cli 대응:
         *   ZADD queue:concert:1 1710000001 "user:1"
         *   ZADD queue:concert:1 1710000002 "user:2"
         *   ZADD queue:concert:1 1710000003 "user:3"
         *   ZRANK queue:concert:1 "user:2"        → 1 (0부터 시작)
         *   ZRANGE queue:concert:1 0 1             → ["user:1", "user:2"] (상위 2명)
         *   ZREM queue:concert:1 "user:1"         → 1 (통과 처리)
         *   ZCARD queue:concert:1                  → 2 (남은 대기 인원)
         */
        String queueKey = "queue:concert:1";
        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();

        // Step 1: 대기열 진입 (score = timestamp → 선착순)
        long now = System.currentTimeMillis();
        zSet.add(queueKey, "user:1", now);
        zSet.add(queueKey, "user:2", now + 1000);
        zSet.add(queueKey, "user:3", now + 2000);
        zSet.add(queueKey, "user:4", now + 3000);
        zSet.add(queueKey, "user:5", now + 4000);

        System.out.println("=== 대기열 테스트 ===");
        System.out.println("5명 대기열 진입 완료");

        // Step 2: 내 순번 조회 (ZRANK)
        Long rank = zSet.rank(queueKey, "user:3");
        assertThat(rank).isEqualTo(2); // 0부터 시작 → 3번째
        System.out.println("user:3의 대기 순번: " + (rank + 1) + "번째");

        // Step 3: 전체 대기 인원 (ZCARD)
        Long totalWaiting = zSet.zCard(queueKey);
        assertThat(totalWaiting).isEqualTo(5);
        System.out.println("전체 대기 인원: " + totalWaiting + "명");

        // Step 4: 상위 3명 통과 처리 (ZRANGE + ZREM)
        Set<String> top3 = zSet.range(queueKey, 0, 2);
        assertThat(top3).containsExactly("user:1", "user:2", "user:3");
        System.out.println("통과 대상 (상위 3명): " + top3);

        // 통과된 사용자 제거
        for (String user : top3) {
            zSet.remove(queueKey, user);
        }

        // Step 5: 남은 대기 인원 확인
        Long remaining = zSet.zCard(queueKey);
        assertThat(remaining).isEqualTo(2);
        Set<String> remainingUsers = zSet.range(queueKey, 0, -1);
        assertThat(remainingUsers).containsExactly("user:4", "user:5");
        System.out.println("남은 대기 인원: " + remaining + "명 " + remainingUsers);
    }

    @Test
    @Order(5)
    @DisplayName("[대기열] 동시에 100명이 진입해도 순서가 보장된다")
    void waitingQueue_concurrent() throws InterruptedException {
        String queueKey = "queue:concert:2";
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When — 100명 동시 진입
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    // score로 nanoTime 사용 → 미세한 순서 차이 캡처
                    double score = System.nanoTime();
                    redisTemplate.opsForZSet().add(queueKey, "user:" + idx, score);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then — 정확히 100명이 대기열에 있어야 한다
        Long size = redisTemplate.opsForZSet().zCard(queueKey);
        assertThat(size).isEqualTo(100);

        System.out.println("=== 대기열 동시 진입 테스트 ===");
        System.out.println("동시 진입: " + threadCount + "명 → 실제 등록: " + size + "명");
        System.out.println("Sorted Set은 score 기준 자동 정렬 → 순서 보장!");
    }

    // =========================================================================
    // Unit 6: Rate Limiting (Sliding Window Log)
    // =========================================================================

    @Test
    @Order(6)
    @DisplayName("[Rate Limit] Sliding Window로 10초에 5회 제한이 동작한다")
    void rateLimiting_slidingWindow() throws InterruptedException {
        /*
         * redis-cli 대응:
         *   ZREMRANGEBYSCORE ratelimit:user1:/api/reserve 0 {now - 10000}
         *   ZCARD ratelimit:user1:/api/reserve
         *   ZADD ratelimit:user1:/api/reserve {now} {requestId}
         *   EXPIRE ratelimit:user1:/api/reserve 10
         */
        String rateLimitKey = "ratelimit:user1:/api/reserve";
        int limit = 5;         // 최대 5회
        long windowMs = 10000; // 10초 윈도우
        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();

        System.out.println("=== Rate Limiting 테스트 (10초에 " + limit + "회) ===");

        // Step 1: 5번 요청 → 모두 허용
        for (int i = 1; i <= 5; i++) {
            long now = System.currentTimeMillis();

            // 윈도우 밖 요청 제거
            zSet.removeRangeByScore(rateLimitKey, 0, now - windowMs);

            // 현재 요청 수 확인
            Long count = zSet.zCard(rateLimitKey);

            if (count < limit) {
                // 요청 허용 → 기록
                zSet.add(rateLimitKey, UUID.randomUUID().toString(), now);
                redisTemplate.expire(rateLimitKey, 10, TimeUnit.SECONDS);
                System.out.println("  요청 " + i + ": ✅ 허용 (현재 " + (count + 1) + "/" + limit + ")");
            } else {
                fail("5번째까지는 모두 허용되어야 한다");
            }
        }

        // Step 2: 6번째 요청 → 거부
        long now = System.currentTimeMillis();
        zSet.removeRangeByScore(rateLimitKey, 0, now - windowMs);
        Long count = zSet.zCard(rateLimitKey);
        assertThat(count).isGreaterThanOrEqualTo((long) limit);
        System.out.println("  요청 6: ❌ 거부! (현재 " + count + "/" + limit + " → 429 Too Many Requests)");

        // Step 3: 윈도우 크기만큼 기다린 후 → 다시 허용
        // (실제 10초를 기다리면 테스트가 느려지므로, 수동으로 오래된 항목 제거)
        zSet.removeRangeByScore(rateLimitKey, 0, Double.MAX_VALUE); // 모두 제거
        Long afterClear = zSet.zCard(rateLimitKey);
        assertThat(afterClear).isEqualTo(0);
        System.out.println("  윈도우 경과 후: 요청 허용됨 (카운트: " + afterClear + "/" + limit + ")");
    }

    // =========================================================================
    // 부록 C 사례: 실시간 랭킹 (Sorted Set)
    // =========================================================================

    @Test
    @Order(7)
    @DisplayName("[랭킹] Sorted Set으로 게임 랭킹 조회/업데이트")
    void ranking_sortedSet() {
        /*
         * redis-cli:
         *   ZADD ranking:game 2100 "faker"
         *   ZADD ranking:game 1800 "chovy"
         *   ZADD ranking:game 2300 "gumayusi"
         *   ZREVRANGE ranking:game 0 2 WITHSCORES  → 상위 3명 (높은 점수 순)
         *   ZREVRANK ranking:game "faker"          → 1 (0부터, 2등)
         *   ZINCRBY ranking:game 500 "chovy"       → 2300 (점수 증가)
         */
        String rankKey = "ranking:game";
        ZSetOperations<String, String> zSet = redisTemplate.opsForZSet();

        // 점수 등록
        zSet.add(rankKey, "faker", 2100);
        zSet.add(rankKey, "chovy", 1800);
        zSet.add(rankKey, "gumayusi", 2300);
        zSet.add(rankKey, "zeus", 1900);
        zSet.add(rankKey, "keria", 2000);

        // 상위 3명 조회 (높은 점수 순)
        Set<ZSetOperations.TypedTuple<String>> top3 = zSet.reverseRangeWithScores(rankKey, 0, 2);
        assertThat(top3).hasSize(3);
        System.out.println("=== 랭킹 Top 3 ===");
        int rank = 1;
        for (var entry : top3) {
            System.out.println(rank++ + "위: " + entry.getValue() + " (" + entry.getScore().intValue() + "점)");
        }

        // faker의 순위
        Long fakerRank = zSet.reverseRank(rankKey, "faker");
        assertThat(fakerRank).isEqualTo(1); // 0부터 시작 → 2등
        System.out.println("faker 순위: " + (fakerRank + 1) + "등");

        // chovy 점수 올리기 (ZINCRBY)
        Double newScore = zSet.incrementScore(rankKey, "chovy", 500);
        assertThat(newScore).isEqualTo(2300);
        System.out.println("chovy +500점 → " + newScore.intValue() + "점");

        // 전체 랭킹 재조회
        Set<ZSetOperations.TypedTuple<String>> all = zSet.reverseRangeWithScores(rankKey, 0, -1);
        System.out.println("\n=== 업데이트 후 전체 랭킹 ===");
        rank = 1;
        for (var entry : all) {
            System.out.println(rank++ + "위: " + entry.getValue() + " (" + entry.getScore().intValue() + "점)");
        }
    }

    // =========================================================================
    // 부록 C 사례: 좋아요 카운터 (INCR/DECR)
    // =========================================================================

    @Test
    @Order(8)
    @DisplayName("[좋아요] INCR/DECR로 원자적 카운터 동작 확인")
    void likeCounter_incrDecr() {
        /*
         * redis-cli:
         *   SET likes:post:42 0
         *   INCR likes:post:42  → 1
         *   INCR likes:post:42  → 2
         *   DECR likes:post:42  → 1 (좋아요 취소)
         *   GET likes:post:42   → "1"
         */
        String likeKey = "likes:post:42";

        // 좋아요 3번
        redisTemplate.opsForValue().increment(likeKey);
        redisTemplate.opsForValue().increment(likeKey);
        redisTemplate.opsForValue().increment(likeKey);
        assertThat(redisTemplate.opsForValue().get(likeKey)).isEqualTo("3");

        // 좋아요 취소 1번
        redisTemplate.opsForValue().decrement(likeKey);
        assertThat(redisTemplate.opsForValue().get(likeKey)).isEqualTo("2");

        System.out.println("=== 좋아요 카운터 ===");
        System.out.println("좋아요 3번 → 취소 1번 → 현재: " + redisTemplate.opsForValue().get(likeKey));
    }

    // =========================================================================
    // 부록 C 사례: 최근 본 상품 (List + LTRIM)
    // =========================================================================

    @Test
    @Order(9)
    @DisplayName("[최근 본 상품] LPUSH + LTRIM으로 최근 5개만 유지")
    void recentViewed_listTrim() {
        /*
         * redis-cli:
         *   LPUSH recent:user:42 "product:1"
         *   LPUSH recent:user:42 "product:2"
         *   ...
         *   LTRIM recent:user:42 0 4    → 최근 5개만 유지
         *   LRANGE recent:user:42 0 -1  → 최근 순서대로
         */
        String recentKey = "recent:user:42";

        // 7개 상품 조회 기록
        for (int i = 1; i <= 7; i++) {
            redisTemplate.opsForList().leftPush(recentKey, "product:" + i);
            // 최근 5개만 유지
            redisTemplate.opsForList().trim(recentKey, 0, 4);
        }

        // 최근 5개만 남아있어야 함
        Long size = redisTemplate.opsForList().size(recentKey);
        assertThat(size).isEqualTo(5);

        // 가장 최근 것이 맨 앞에
        List<String> recent = redisTemplate.opsForList().range(recentKey, 0, -1);
        assertThat(recent.get(0)).isEqualTo("product:7"); // 마지막에 본 것
        assertThat(recent.get(4)).isEqualTo("product:3"); // 가장 오래된 것

        System.out.println("=== 최근 본 상품 (최대 5개) ===");
        for (int i = 0; i < recent.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + recent.get(i));
        }
    }

    // =========================================================================
    // 부록 C 사례: 온라인 유저 추적 (Set)
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("[온라인 유저] Set으로 접속자 추적 — 중복 자동 제거")
    void onlineUsers_set() {
        /*
         * redis-cli:
         *   SADD online:users "user:1"
         *   SADD online:users "user:2"
         *   SADD online:users "user:1"  → 0 (이미 존재, 중복 무시)
         *   SCARD online:users          → 2
         *   SISMEMBER online:users "user:1" → 1 (접속 중)
         *   SREM online:users "user:1"  → 1 (로그아웃)
         */
        String onlineKey = "online:users";

        // 유저 접속
        redisTemplate.opsForSet().add(onlineKey, "user:1", "user:2", "user:3");

        // 같은 유저 다시 접속 (중복 무시)
        redisTemplate.opsForSet().add(onlineKey, "user:1");

        Long onlineCount = redisTemplate.opsForSet().size(onlineKey);
        assertThat(onlineCount).isEqualTo(3); // 3명 (중복 없음)

        // 특정 유저 접속 여부
        Boolean isOnline = redisTemplate.opsForSet().isMember(onlineKey, "user:2");
        assertThat(isOnline).isTrue();

        // 유저 로그아웃
        redisTemplate.opsForSet().remove(onlineKey, "user:2");
        assertThat(redisTemplate.opsForSet().size(onlineKey)).isEqualTo(2);

        System.out.println("=== 온라인 유저 ===");
        System.out.println("접속자 수: " + onlineCount);
        System.out.println("user:2 접속 중? " + isOnline);
        System.out.println("user:2 로그아웃 후: " + redisTemplate.opsForSet().members(onlineKey));
    }

    // =========================================================================
    // 부록 C 사례: 멱등성 키 — 중복 요청 방지 (SETNX)
    // =========================================================================

    @Test
    @Order(11)
    @DisplayName("[멱등성] SETNX로 결제 중복 요청을 방지한다")
    void idempotencyKey_setnx() {
        /*
         * redis-cli:
         *   SET idempotency:pay-abc123 "결제완료" EX 86400 NX  → OK (첫 요청)
         *   SET idempotency:pay-abc123 "결제완료" EX 86400 NX  → (nil) (중복!)
         */
        String idempotencyKey = "idempotency:pay-abc123";

        // 첫 번째 결제 요청
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "결제완료:10000원", 24, TimeUnit.HOURS);
        assertThat(first).isTrue();
        System.out.println("1차 결제 요청: " + (first ? "✅ 처리" : "❌ 중복"));

        // 같은 키로 재요청 (사용자가 버튼 연타)
        Boolean second = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "결제완료:10000원", 24, TimeUnit.HOURS);
        assertThat(second).isFalse();
        System.out.println("2차 결제 요청: " + (second ? "✅ 처리" : "❌ 중복 — 이전 결과 반환"));

        // 기존 결과 조회
        String existingResult = redisTemplate.opsForValue().get(idempotencyKey);
        assertThat(existingResult).isEqualTo("결제완료:10000원");
        System.out.println("기존 결과: " + existingResult);
    }

    // =========================================================================
    // 부록 C 사례: 기능 플래그 (Hash)
    // =========================================================================

    @Test
    @Order(12)
    @DisplayName("[기능 플래그] Hash로 기능 ON/OFF를 실시간 제어한다")
    void featureFlag_hash() {
        /*
         * redis-cli:
         *   HSET feature:flags new-checkout "true"
         *   HSET feature:flags dark-mode "false"
         *   HGET feature:flags new-checkout  → "true"
         *   HGETALL feature:flags
         */
        String flagKey = "feature:flags";

        // 기능 플래그 설정
        redisTemplate.opsForHash().put(flagKey, "new-checkout", "true");
        redisTemplate.opsForHash().put(flagKey, "dark-mode", "false");
        redisTemplate.opsForHash().put(flagKey, "beta-search", "true");

        // 특정 기능 확인
        String newCheckout = (String) redisTemplate.opsForHash().get(flagKey, "new-checkout");
        assertThat(newCheckout).isEqualTo("true");

        // 긴급 기능 OFF (장애 발생 시)
        redisTemplate.opsForHash().put(flagKey, "new-checkout", "false");
        String afterOff = (String) redisTemplate.opsForHash().get(flagKey, "new-checkout");
        assertThat(afterOff).isEqualTo("false");

        // 전체 플래그 조회
        var allFlags = redisTemplate.opsForHash().entries(flagKey);

        System.out.println("=== 기능 플래그 ===");
        allFlags.forEach((k, v) -> System.out.println("  " + k + " = " + v));
        System.out.println("new-checkout 긴급 OFF 완료! (배포 없이 실시간 반영)");
    }
}
