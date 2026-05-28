# 부록 — Rate Limit 가이드

> 02-실무.md §4 카운터 패턴 B (Fixed Window Rate Limit)의 **전체 흐름**을 깊게 정리.
> Twitter·GitHub·Stripe 같은 API 회사들이 표준으로 쓰는 패턴.

---

## §1. Rate Limit이란

**일정 시간에 N번까지만 호출 허용**하는 트래픽 통제 장치.

```
"이 사용자, 이 IP, 이 API에 대해 분당/시간당/일당 몇 번까지만 허용"
```

초과 시 → HTTP 429 (Too Many Requests) 반환.

### 비유

> **고속도로 톨게이트 🛣️**
> "1분에 60대까지만 통과" 정해놓고, 60대 넘으면 빨간불.
> 1분 지나면 카운터 리셋, 다시 통과 가능.

---

## §2. 왜 필요한가 — 5가지 이유

### 1. 악의적 공격 방어 (DDoS / 브루트포스)
```
공격자: 인증 코드 1234를 1초에 10,000번 시도
→ Rate Limit "분당 5회"로 차단 ✅
```

### 2. 서버 자원 보호
```
한 사용자가 1초에 1만 요청 → 서버 다운
→ "초당 10회"로 다른 사용자 보호
```

### 3. 외부 API 비용 통제
```
PG사 API: 1회 호출당 10원
직원 실수로 무한 루프 → 시간당 100만 원 💸
→ "분당 100회"로 사고 방지
```

### 4. 요금제 차등화
```
무료 플랜:     분당 60회
프로 플랜:     분당 1000회
엔터프라이즈:  무제한
```

### 5. 매크로/봇 차단
```
콘서트 예매에서 봇이 좌석 싹쓸이
→ 사용자당 분당 30회로 매크로 무력화
```

---

## §3. Fixed Window 방식 (가장 단순, 가장 흔함)

**시간을 칸으로 쪼개서, 각 칸마다 N회까지 허용**.

```
12:00 ~ 12:00:59  → 60회 가능 (구간 끝나면 리셋)
12:01 ~ 12:01:59  → 다시 60회 가능
```

### 표준 구현

```java
public boolean allowRequest(String userId) {
    String minute = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    String key = "rl:" + userId + ":" + minute;
    // 예: "rl:user42:202605281430"

    Long count = redisTemplate.opsForValue().increment(key);
    if (count == 1) {
        redisTemplate.expire(key, 1, TimeUnit.MINUTES);   // 첫 INCR 시 TTL 설정
    }

    return count <= 60;   // 분당 60회까지
}
```

### 동작 흐름

```
12:30:05  user42 1번째 요청 → INCR → count=1 → ✅ 허용 + TTL 1분 설정
12:30:10  user42 2번째 요청 → INCR → count=2 → ✅
...
12:30:55  user42 60번째 요청 → INCR → count=60 → ✅
12:30:56  user42 61번째 요청 → INCR → count=61 → ❌ 차단! (HTTP 429)

12:31:00  ⏰ 새 분 시작 → 새 키 "rl:user42:202605281231" 생성
12:31:01  user42 요청 → INCR → count=1 → ✅ (리셋됨)
```

### 핵심 트릭: **키 이름에 시간 박기**

- 분이 바뀌면 자연스럽게 새 키 → 윈도우 자동 리셋
- 이전 분의 키는 TTL로 자동 삭제 (메모리 정리)
- 별도의 "리셋 로직" 필요 없음 ✅

---

## §4. 시간 포맷 = 윈도우 크기

키 이름의 시간 포맷이 곧 윈도우 크기.

| 포맷 | 키 예시 | 윈도우 |
|---|---|---|
| `yyyyMMddHHmm` | `"202605281430"` | **분 단위** |
| `yyyyMMddHH` | `"2026052814"` | **시간 단위** |
| `yyyyMMdd` | `"20260528"` | **일 단위** |
| `yyyyMM` | `"202605"` | **월 단위** |

### 4종 세트

```java
// 분당 60회
key = "rl:" + userId + ":" + format("yyyyMMddHHmm");
redis.expire(key, 1, TimeUnit.MINUTES);

// 시간당 1000회
key = "rl:" + userId + ":" + format("yyyyMMddHH");
redis.expire(key, 1, TimeUnit.HOURS);

// 일당 10000회
key = "rl:" + userId + ":" + format("yyyyMMdd");
redis.expire(key, 1, TimeUnit.DAYS);

// 월당 발송 100통
key = "mail:rl:" + userId + ":" + format("yyyyMM");
redis.expire(key, 31, TimeUnit.DAYS);
```

> **TTL = 윈도우 크기**로 잡으면 자동으로 옛 키 정리.

---

## §5. 다층 Rate Limit — 실무 표준 ⭐

여러 윈도우를 동시에 걸어서 단기·중기·장기 폭주를 모두 차단.

```java
public boolean allowRequest(String userId) {
    // 3중 체크 - 모두 통과해야 허용
    if (incrAndCheck("rl:" + userId + ":" + minute(), 60, 1, MINUTES))  return false;
    if (incrAndCheck("rl:" + userId + ":" + hour(),  1000, 1, HOURS))   return false;
    if (incrAndCheck("rl:" + userId + ":" + day(),  10000, 1, DAYS))    return false;
    return true;
}
```

### 효과

```
정상 사용자: 모든 임계값 안 → ✅ 통과
폭주 봇:    분당 60회 OK → 시간당 1000회에서 차단 ❌
스팸 계정:  분당·시간당 OK → 일당 10000회에서 차단 ❌
```

→ **Twitter, GitHub, Stripe 같은 API 회사들이 실제로 쓰는 방식.**

### 왜 다층이어야 하나?

```
단일 분당 60회만:    정상 사용자 가끔 폭주(이벤트 등)도 막힘
단일 일당 10000회만: 1분에 10000회 한 번에 쏴도 통과 → 서버 죽음

→ 다층으로 "단기 폭주 + 장기 누적" 모두 방어
```

---

## §6. Fixed Window의 약점 — "경계 직전 폭주"

```
12:30:59  user42 → 60번 호출 (분당 한계)
12:31:00  user42 → 새 윈도우 시작 → 또 60번 호출

→ 단 1초 사이에 120번 호출 가능! 😱
```

→ 평균은 분당 60회 같지만, **경계에서는 2배까지 폭주 가능**.

### 언제 문제 되나?
- API가 매우 민감한 경우 (결제, 외부 비용 발생)
- 정교한 봇 방어가 필요한 경우

### 대안: Sliding Window
- 항상 "최근 60초"를 기준으로 계산 → 경계 폭주 없음
- Sorted Set으로 구현 (List/ZSet 단계에서 다룸)

---

## §7. 다른 알고리즘 비교

| 알고리즘 | 정확도 | 메모리 | 복잡도 | 특징 |
|---|---|---|---|---|
| **Fixed Window** | ⭐⭐ | 적음 | ⭐ 매우 쉬움 | 경계 폭주 / 가장 흔함 |
| **Sliding Window Log** | ⭐⭐⭐⭐⭐ | 많음 | ⭐⭐⭐ | 요청 시각 다 기록 |
| **Sliding Window Counter** | ⭐⭐⭐⭐ | 적음 | ⭐⭐⭐ | 이전·현재 윈도우 가중평균 |
| **Token Bucket** | ⭐⭐⭐⭐ | 적음 | ⭐⭐⭐ | 토큰 채우고 소비 / 버스트 허용 |
| **Leaky Bucket** | ⭐⭐⭐ | 중간 | ⭐⭐⭐ | 큐로 일정 속도 처리 |

### 실무 선택 가이드

| 상황 | 추천 |
|---|---|
| 단순한 API 보호 | **Fixed Window** |
| 정밀한 제한 (결제 등) | **Sliding Window Counter** |
| 가끔 버스트 허용 (이벤트 등) | **Token Bucket** |
| 균일한 처리 속도 (큐) | **Leaky Bucket** |

→ 실무 80%는 **Fixed Window** 또는 **Token Bucket**.

### Sliding Window 깊이 보기

Fixed Window의 약점(경계 폭주)을 어떻게 해결하는지 자세히.

#### 원리: "지금부터 N초 전까지"를 항상 계산

칸으로 나누지 않고, **항상 슬라이딩되는 N초 창**을 봄.

```
[Fixed Window]
12:30:00 ─────── 12:30:59  12:31:00 ─────── 12:31:59
  └──── 칸 1 ────┘  └──── 칸 2 ────┘
  60회 사용 가능       60회 사용 가능
→ 12:30:59 ~ 12:31:00 사이엔 두 칸 모두 활성 → 120회 가능 😱


[Sliding Window]
                          [now=12:31:00]
                  ↓ 60초 전 ─────────┘
        12:30:00 ─────── 12:31:00
        ←─── 항상 이 구간만 봄 ───→
→ 60회 이미 했으면 차단 ✅
```

#### 시나리오로 보면

```
12:30:59에 60번 호출 마침

12:31:00 시점 - 새 요청:
  Fixed:    "12:31 윈도우 카운트=0 → ✅ 허용" (또 60번 가능)
  Sliding:  "최근 60초(12:30:00~12:31:00) 호출 수=60 → ❌ 차단"

12:32:00 시점에야 비로소:
  Sliding:  "최근 60초(12:31:00~12:32:00) 호출 수=0 → ✅ 허용"

→ Sliding은 정확히 60초 간격을 유지하도록 강제
```

#### 구현 방식 1: Sliding Window Log (정확함, 무거움)

각 요청 시각을 **Sorted Set**에 저장.

```java
public boolean allowRequest(String userId) {
    String key = "rl:slide:" + userId;
    long now = System.currentTimeMillis();
    long windowStart = now - 60_000;   // 60초 전

    // 1. 60초 이전 요청 기록 삭제
    redis.opsForZSet().removeRangeByScore(key, 0, windowStart);

    // 2. 현재 윈도우 내 요청 수 카운트
    Long count = redis.opsForZSet().zCard(key);

    if (count >= 60) return false;   // ❌ 차단

    // 3. 현재 요청 시각 기록
    redis.opsForZSet().add(key, UUID.randomUUID().toString(), now);
    redis.expire(key, Duration.ofMinutes(2));

    return true;
}
```

**Sorted Set 구조**:
```
"rl:slide:user42":
  score=1716889201234 → "uuid-1"
  score=1716889205678 → "uuid-2"
  score=1716889210999 → "uuid-3"
  (각 요청의 timestamp가 score)
```

✅ **정확함** (밀리초 단위)
❌ 요청마다 ZSet 추가 → 메모리·CPU 부담
→ 결제·외부 비용 발생 API 등 **정밀 필요 시** 사용

#### 구현 방식 2: Sliding Window Counter (가벼움, 근사치)

이전 윈도우 + 현재 윈도우의 **가중 평균**.

```
[12:30 윈도우] 60회        [12:31 윈도우] 10회
                  ↓
            현재 = 12:31:20

12:31:20 시점 "최근 60초" 추정:
  = 12:30 윈도우 잔여비율(40%남음) × 12:30 카운트(60)
  + 12:31 윈도우 카운트(10)
  = 40% × 60 + 10
  = 24 + 10
  = 34회
```

```java
public boolean allowRequest(String userId) {
    long now = System.currentTimeMillis();
    long currentWindow = now / 60_000;
    long prevWindow = currentWindow - 1;

    long currentCount = getCount("rl:" + userId + ":" + currentWindow);
    long prevCount = getCount("rl:" + userId + ":" + prevWindow);

    // 현재 윈도우 내 경과 시간 비율
    double elapsedRatio = (now % 60_000) / 60_000.0;
    // 이전 윈도우의 "아직 살아있는" 비율 = 1 - elapsedRatio
    double estimated = prevCount * (1 - elapsedRatio) + currentCount;

    if (estimated >= 60) return false;

    incr("rl:" + userId + ":" + currentWindow);
    return true;
}
```

✅ **가벼움** (Counter만 사용, ZSet X)
✅ Fixed Window 약점 거의 해결
❌ 근사치 (실제와 약간 다를 수 있음)
→ **실무에서 가장 자주 쓰는 방식.** (Cloudflare, NGINX Plus 등)

#### 세 방식 종합 비교

| 항목 | Fixed Window | Sliding Window Counter | Sliding Window Log |
|---|---|---|---|
| 정확도 | ⭐⭐ (경계 폭주) | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 메모리 | ⭐⭐⭐⭐⭐ 적음 | ⭐⭐⭐⭐⭐ 적음 | ⭐⭐ 많음 |
| CPU | ⭐⭐⭐⭐⭐ 빠름 | ⭐⭐⭐⭐ 빠름 | ⭐⭐⭐ 중간 |
| 구현 난이도 | ⭐ 매우 쉬움 | ⭐⭐⭐ 중간 | ⭐⭐⭐ 중간 |
| 사용처 | 일반 API 보호 | **실무 표준** | 정밀 필요 (결제 등) |

#### 비유

```
[Fixed Window]
시간표: 1교시(09:00~10:00) "60분에 60문제"
       2교시(10:00~11:00) "다시 60분에 60문제"
→ 09:59에 60문제 + 10:00에 60문제 = 2분 사이 120문제 😱

[Sliding Window]
선생님: 항상 "지금부터 1시간 전까지 몇 문제 풀었어?" 체크
→ 09:59에 60문제 했으면 10:00에 더 못 함
→ 10:59가 되어야 다시 60문제 가능
```

> ⚠️ Sliding Window Log는 **Sorted Set**이 핵심. ZSet 학습 후 직접 구현해보면 이해가 훨씬 깊어짐.

---

## §8. HTTP 응답 표준 — 429 Too Many Requests

Rate Limit 초과 시 표준 응답:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30                    ← 30초 후 재시도하라
X-RateLimit-Limit: 60              ← 한도
X-RateLimit-Remaining: 0           ← 남은 횟수
X-RateLimit-Reset: 1716889260      ← 리셋 unix timestamp
Content-Type: application/json

{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "분당 요청 한도를 초과했습니다",
  "retryAfter": 30
}
```

### 표준 헤더 의미

| 헤더 | 의미 |
|---|---|
| `Retry-After` | 몇 초 후 재시도 가능한지 (표준 HTTP) |
| `X-RateLimit-Limit` | 윈도우당 허용 횟수 |
| `X-RateLimit-Remaining` | 현재 남은 횟수 |
| `X-RateLimit-Reset` | 리셋 시각 (Unix timestamp) |

### 정상 응답에도 헤더 포함하면 좋음

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1716889260
```

→ 클라이언트가 "남은 횟수 보고 알아서 조절" 가능.

---

## §9. Spring Boot 인터셉터 패턴 (실무 적용)

전역 적용하려면 인터셉터/필터에 박는 게 표준.

```java
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        String userId = extractUserId(req);   // 세션/JWT에서 추출
        String minute = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String key = "rl:" + userId + ":" + minute;

        Long count = redis.opsForValue().increment(key);
        if (count == 1) {
            redis.expire(key, 1, TimeUnit.MINUTES);
        }

        // 응답 헤더에 항상 정보 제공
        resp.setHeader("X-RateLimit-Limit", "60");
        resp.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, 60 - count)));

        if (count > 60) {
            resp.setStatus(429);
            resp.setHeader("Retry-After", "60");
            resp.getWriter().write("{\"code\":\"RATE_LIMIT_EXCEEDED\"}");
            return false;   // 컨트롤러까지 안 감
        }

        return true;
    }
}

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");   // 로그인 전엔 IP 기반으로 따로
    }
}
```

---

## §10. 흔한 함정

| 함정 | 결과 | 해법 |
|---|---|---|
| TTL 안 줌 | 옛 키가 영원히 남음 → 메모리 누수 | `EXPIRE` 필수 |
| 첫 INCR 시 TTL 안 줌 | 첫 키만 TTL 없음 (race condition) | `if (count == 1) expire(...)` |
| 키 이름에 시간 안 박음 | 윈도우 리셋 못 함 | 시간 포맷 필수 |
| 사용자 식별을 IP로만 함 | NAT 뒤 수천 명이 한 IP 공유 → 잘못 차단 | 인증 후엔 userId, 인증 전엔 IP |
| 인증 전 API에 userId 기반 RL | 비로그인 사용자 식별 불가 | 인증 전엔 IP 기반 |
| 429 응답에 `Retry-After` 없음 | 클라이언트가 언제 재시도할지 모름 | 헤더 포함 |
| 단일 윈도우만 사용 | 단기 또는 장기 폭주 못 막음 | 다층 RL |
| 분산 환경에서 로컬 카운터 | 서버마다 별도 카운터 → 합치면 N배 초과 가능 | Redis 같은 공유 저장소 필수 |

---

## §11. 이 프로젝트 적용 포인트

### 1. 인증 코드 발송 제한 (이미 02-실무.md §2에 나옴)
```java
// AuthService에 추가
String key = "auth:rl:" + email + ":" + format("yyyyMMddHH");
Long count = redis.increment(key);
if (count == 1) redis.expire(key, 1, TimeUnit.HOURS);
if (count > 5) throw new CustomException(TOO_MANY_AUTH_REQUESTS);
// 이메일당 시간당 5회까지만
```

### 2. 로그인 시도 제한 (브루트포스 방어)
```java
String ip = extractIp(request);
String key = "login:rl:" + ip + ":" + format("yyyyMMddHHmm");
if (incr(key, 1, MINUTES) > 10) throw new TooManyLoginException();
// IP당 분당 10회
```

### 3. 좌석 예매 API (매크로 방어)
```java
String key = "book:rl:" + userId + ":" + format("yyyyMMddHHmm");
if (incr(key, 1, MINUTES) > 30) throw new TooManyBookingException();
// 사용자당 분당 30회
```

### 4. 전역 API 보호 (DDoS 1차선)
```java
// 인터셉터에서
String key = "api:rl:" + ip + ":" + format("yyyyMMddHHmm");
if (incr(key, 1, MINUTES) > 1000) return 429;
// IP당 분당 1000회
```

### 5. 다층 보호 (콘서트 오픈일 등 트래픽 폭주 대비)
```java
String userKey = "book:" + userId;
allowOrThrow(userKey + ":" + minute(),  30,   MINUTES);   // 분당 30
allowOrThrow(userKey + ":" + hour(),   200,   HOURS);     // 시간당 200
allowOrThrow(userKey + ":" + day(),    1000,  DAYS);      // 일당 1000
```

---

## §12. 한 줄 정리

> **Rate Limit = "시간당/분당 N번까지만" 출입 통제 🚦**
>
> **Fixed Window 핵심 트릭**:
> 1. **키 이름에 시간 박기** (`"rl:userId:202605281430"`)
> 2. **`INCR` + `EXPIRE`** (첫 INCR 시 TTL 설정)
> 3. **임계값 초과 시 HTTP 429** (+ `Retry-After`)
>
> 시간 포맷이 곧 윈도우 크기:
> - 분 → `yyyyMMddHHmm`
> - 시간 → `yyyyMMddHH`
> - 일 → `yyyyMMdd`
>
> 실무는 **분/시간/일 3중 다층 체크**가 표준.
>
> 경계 폭주가 걱정되면 → **Sliding Window** (Log: 정확/무거움, Counter: 가벼움/근사치).
> 결제처럼 정밀 필요 시 Log, 일반은 Counter 추천. ZSet 학습 후 깊이 파보기.

---

## 외울 템플릿

### Fixed Window 핵심 5줄

```java
String key = "rl:" + userId + ":" + minute();
Long count = redis.increment(key);
if (count == 1) redis.expire(key, 1, TimeUnit.MINUTES);
if (count > LIMIT) throw new RateLimitException();
return;
```

### 다층 RL 구조

```java
allowOrThrow(prefix + minute(), 60,    MINUTES);
allowOrThrow(prefix + hour(),   1000,  HOURS);
allowOrThrow(prefix + day(),    10000, DAYS);
```

---

## 관련 문서

- 02-실무.md §4 — 카운터 패턴 (Fixed Window RL 짧은 소개)
- 02-실무.md §2 — 인증 코드 발송 RL (실전 예시)
- 부록-멱등성.md — 결제 API와 RL 함께 적용 시 참고
