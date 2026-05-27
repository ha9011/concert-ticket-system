# Redis 학습 로드맵 — 콘서트 티켓 시스템

> 이 프로젝트에서 이미 구현한 3가지 패턴(String+TTL, List Queue, Session Store)을 기반으로,
> 실무에서 자주 쓰이는 Redis 패턴을 단계적으로 익혀 나가는 로드맵입니다.

---

## 현재 완료된 패턴

| 패턴 | Redis 자료구조 | 키 예시 | 위치 |
|------|---------------|---------|------|
| 인증 코드 저장 | String + TTL | `auth:{email}` | api-server |
| 이메일 큐 | List (LPUSH/RPOP) | `mail:queue` | api-server ↔ queue-server |
| 세션 저장소 | Spring Session + Hash | `concert:session:{id}` | api-server |

---

### - [x] 패턴 1: 인증 코드 저장 (String + TTL)

**무엇을 했는가**

이메일 회원가입 시 4자리 인증번호를 생성하고, Redis에 3분 TTL로 저장한 뒤, 사용자가 입력한 코드와 비교하여 검증하는 흐름을 구현했다.

**어떻게 구현했는가**

```
[흐름]
POST /api/auth/send-code?email=user@test.com
  → 이메일 중복 확인 (DB)
  → 4자리 랜덤 코드 생성
  → Redis에 저장: SET auth:user@test.com "1234" EX 180
  → 코드 반환

POST /api/auth/verify (SignupRequest: email, code, name, password)
  → Redis에서 조회: GET auth:user@test.com
  → 코드 일치 여부 확인
  → 회원 DB 저장
  → Redis 키 삭제: DEL auth:user@test.com (재사용 방지)
```

핵심 코드 (`AuthService.java`):
```java
// 저장 — opsForValue()는 Redis String 타입 조작용
redisTemplate.opsForValue().set(
    "auth:" + email,   // 키
    code,              // 값 (4자리 숫자 문자열)
    3, TimeUnit.MINUTES // TTL 3분 → Redis의 EX 옵션으로 변환됨
);

// 조회
String savedCode = redisTemplate.opsForValue().get("auth:" + email);

// 삭제 (인증 성공 후 재사용 방지)
redisTemplate.delete("auth:" + email);
```

**왜 이렇게 했는가**

- **왜 Redis인가?** — 인증 코드는 3분 뒤 자동 소멸해야 한다. DB에 저장하면 만료 처리를 위한 스케줄러가 별도로 필요하지만, Redis의 TTL을 쓰면 만료가 자동으로 처리된다.
- **왜 String 타입인가?** — 저장할 데이터가 단순한 "4자리 코드" 하나뿐이다. Key-Value 1:1 매핑이면 String이 가장 적합하다.
- **왜 인증 후 즉시 삭제하는가?** — 같은 코드로 중복 가입을 방지하기 위해. TTL 만료를 기다리지 않고 즉시 무효화한다.

**사용한 Redis 명령어 정리**

| Java 코드 | 실제 Redis 명령어 | 설명 |
|-----------|------------------|------|
| `opsForValue().set(key, value, 3, MINUTES)` | `SET key value EX 180` | 값 저장 + 3분 TTL |
| `opsForValue().get(key)` | `GET key` | 값 조회 (만료 시 null) |
| `delete(key)` | `DEL key` | 키 즉시 삭제 |

**실무에서는 어떻게 하는가**

- **어디서 쓰이나?**: 카카오/네이버 회원가입 시 이메일·SMS 인증, 배달의민족 주문 시 일회용 인증번호, 은행 앱 OTP 코드 등 거의 모든 인증 플로우에서 Redis String + TTL 패턴을 사용한다.
- **실제 이메일 발송**: 현재는 코드를 API 응답으로 직접 반환하지만, 실무에서는 SMTP 서버(AWS SES, SendGrid 등)를 통해 이메일로 발송한다. API 응답에는 "인증번호가 발송되었습니다"만 반환.
- **Rate Limiting**: 동일 이메일로 무한 요청을 방지하기 위해, 발송 횟수를 제한한다 (예: `auth:count:{email}` 키로 1분에 3회 제한).
- **코드 길이 & 형식**: 4자리 숫자는 brute-force에 취약하다. 실무에서는 6자리 이상을 사용하거나, 시도 횟수를 제한(5회 실패 시 코드 무효화)한다.
- **보안**: 코드 검증 실패 시에도 "이메일 또는 인증번호가 올바르지 않습니다" 같은 모호한 메시지를 사용하여 이메일 존재 여부가 노출되지 않도록 한다.

> **💡 면접 포인트**: "Redis TTL을 이용한 임시 데이터 관리"는 가장 기본적인 Redis 활용 사례다. 면접에서 "Redis를 왜 썼나요?"라고 물으면 **"자동 만료가 필요한 임시 데이터였기 때문"**이라고 답하면 된다.

---

### - [x] 패턴 2: 이메일 큐 (List — Producer/Consumer)

**무엇을 했는가**

회원가입 성공 시 환영 이메일 발송을 비동기로 처리하기 위해, api-server가 Redis List에 이메일을 넣고(Producer), queue-server가 꺼내서 처리(Consumer)하는 메시지 큐를 구현했다.

**어떻게 구현했는가**

```
[흐름]
api-server (Producer)                     queue-server (Consumer)
─────────────────────                     ──────────────────────
회원가입 성공                               @PostConstruct로 시작
  → LPUSH mail:queue "user@test.com"       Dispatcher 스레드 (1개):
                                             → BRPOP mail:queue 10초 대기
                                             → 이메일 꺼냄
                                             → Worker Pool에 전달

                                           Worker 스레드 (3개):
                                             → 이메일 처리 (5초 시뮬레이션)
                                             → EmailLog DB 저장
```

핵심 코드 — Producer (`AuthService.java`):
```java
// 회원가입 완료 후, 환영 메일을 큐에 넣음
redisTemplate.opsForList().leftPush("mail:queue", request.getEmail());
// → Redis 명령: LPUSH mail:queue "user@test.com"
// → 리스트의 왼쪽(head)에 삽입
```

핵심 코드 — Consumer (`EmailMultiWorker.java`):
```java
// Dispatcher: 단일 스레드가 큐를 감시
private final ExecutorService dispatcher = Executors.newSingleThreadExecutor();
// Worker Pool: 3개 스레드가 실제 작업 수행
private final ExecutorService workerPool = Executors.newFixedThreadPool(3);

// 큐에서 꺼냄 (최대 10초 블로킹 대기)
String email = redisTemplate.opsForList().rightPop("mail:queue", Duration.ofSeconds(10));
// → Redis 명령: BRPOP mail:queue 10
// → 리스트의 오른쪽(tail)에서 꺼냄 → LPUSH + RPOP = FIFO 순서 보장

if (email != null) {
    workerPool.submit(() -> processEmail(email));  // 워커에게 비동기 위임
}
```

**왜 이렇게 했는가**

- **왜 비동기 큐인가?** — 이메일 발송은 느린 I/O 작업이다. 회원가입 API 응답에서 이메일 발송 완료까지 기다리면 사용자 경험이 나빠진다. 큐에 넣고 즉시 응답하면 사용자는 빠른 응답을 받고, 이메일은 백그라운드에서 처리된다.
- **왜 Redis List인가?** — `LPUSH`/`RPOP` 조합이 자연스러운 FIFO 큐를 형성한다. 별도 메시지 브로커(RabbitMQ, Kafka) 없이 이미 사용 중인 Redis로 간단한 큐를 구현할 수 있다.
- **왜 BRPOP(블로킹)인가?** — 일반 `RPOP`은 큐가 비어 있으면 즉시 null을 반환하므로, while 루프에서 CPU를 낭비하며 계속 폴링하게 된다. `BRPOP`은 데이터가 올 때까지 10초간 블로킹 대기하므로 불필요한 CPU 사용을 줄인다.
- **왜 Dispatcher + Worker Pool 패턴인가?** — 큐에서 꺼내는 작업(BRPOP)은 블로킹이므로 단일 스레드가 전담하고, 실제 처리(이메일 발송, DB 저장)는 여러 워커가 병렬로 처리하여 처리량(throughput)을 높였다.

**사용한 Redis 명령어 정리**

| Java 코드 | 실제 Redis 명령어 | 설명 |
|-----------|------------------|------|
| `opsForList().leftPush(key, value)` | `LPUSH mail:queue "email"` | 리스트 왼쪽에 삽입 |
| `opsForList().rightPop(key, Duration)` | `BRPOP mail:queue 10` | 오른쪽에서 꺼냄 (블로킹) |

**LPUSH + RPOP = FIFO 이해하기**

```
LPUSH로 삽입 →  [C] [B] [A]  → RPOP으로 꺼냄
(왼쪽에 추가)                    (오른쪽에서 제거)

삽입 순서: A → B → C
꺼내는 순서: A → B → C  (선입선출)
```

**실무에서는 어떻게 하는가**

- **어디서 쓰이나?**: 카카오톡 알림톡 발송 큐, 쿠팡 주문 처리 파이프라인, 당근마켓 푸시 알림 큐 등. 소규모 비동기 작업에는 Redis List가 가장 간편하고, 규모가 커지면 Kafka/RabbitMQ로 전환한다.
- **메시지 유실 문제**: 현재 구현은 `RPOP`으로 꺼낸 순간 Redis에서 사라진다. 워커가 처리 중 죽으면 메시지가 유실된다. 실무에서는 `RPOPLPUSH`(또는 Redis 6.2+ `LMOVE`)로 "처리 중" 리스트에 백업하고, 완료 후 삭제하는 **안정적 큐(Reliable Queue)** 패턴을 사용한다.
- **메시지 브로커 전환**: 규모가 커지면 Redis List 대신 RabbitMQ(재처리, DLQ 지원), Kafka(대용량, 순서 보장, 재소비 가능) 등 전용 메시지 브로커로 전환한다.
- **재시도 로직**: 처리 실패 시 일정 횟수 재시도하고, 최종 실패 시 Dead Letter Queue(DLQ)에 보관하여 수동 확인할 수 있게 한다.
- **모니터링**: `LLEN mail:queue`로 대기 메시지 수를 모니터링하고, 임계값 초과 시 알림을 보낸다 (큐가 쌓이면 컨슈머를 스케일 아웃).

> **💡 면접 포인트**: "Redis List를 메시지 큐로 쓸 때의 한계점과 대안"을 물어보면, **메시지 유실 가능성(RPOPLPUSH로 보완)**, **메시지 재소비 불가(Kafka와 차이)**, **모니터링 어려움** 세 가지를 언급하면 된다.

---

### - [x] 패턴 3: 세션 저장소 (Spring Session + Redis)

**무엇을 했는가**

로그인 시 사용자 정보를 서버 세션에 저장하되, 세션 데이터를 WAS 메모리가 아닌 Redis에 저장하도록 Spring Session을 설정했다. 로그아웃 시 세션을 무효화(삭제)한다.

**어떻게 구현했는가**

```
[흐름]
POST /api/auth/login (email, password)
  → DB에서 유저 조회 & 비밀번호 확인
  → session.setAttribute("USER", new SessionUser(user))
  → Spring Session이 자동으로 Redis에 저장
  → 응답 헤더에 SESSION 쿠키 포함

POST /api/auth/logout
  → session.invalidate()
  → Spring Session이 Redis에서 세션 데이터 삭제
```

설정 (`application.yml`):
```yaml
spring:
  session:
    store-type: redis              # 세션을 Redis에 저장
    redis:
      namespace: concert:session   # 키 접두사 → concert:session:{sessionId}
    timeout: 30m                   # 30분 무활동 시 세션 만료
```

핵심 코드 (`AuthController.java`):
```java
// 로그인 — HttpSession에 유저 정보 저장
// Spring Session이 이 호출을 가로채서 Redis에 Hash로 저장함
session.setAttribute("USER", new SessionUser(user));
// → Redis에 저장되는 형태:
//   HSET concert:session:{sessionId} "sessionAttr:USER" {직렬화된 SessionUser}
//   EXPIRE concert:session:{sessionId} 1800

// 로그아웃 — 세션 무효화
session.invalidate();
// → Redis 명령: DEL concert:session:{sessionId}
```

`SessionUser` DTO:
```java
// Serializable 필수 — Redis에 바이트 직렬화되어 저장되기 때문
public class SessionUser implements Serializable {
    private Long id;
    private String email;
    private String name;
}
```

**왜 이렇게 했는가**

- **왜 Redis 세션인가?** — 기본 HttpSession은 WAS 메모리(톰캣)에 저장된다. 서버가 재시작되면 모든 세션이 사라져서 사용자가 로그아웃된다. Redis에 저장하면 서버 재시작과 무관하게 세션이 유지된다.
- **왜 Spring Session인가?** — `session.setAttribute()`/`session.invalidate()` 같은 표준 Servlet API를 그대로 사용하면서, 내부적으로 Redis에 저장되도록 투명하게 전환해준다. 코드 변경 없이 설정만으로 적용 가능.
- **왜 `Serializable`인가?** — Spring Session의 기본 직렬화는 Java Object Serialization이다. `SessionUser`가 `Serializable`을 구현해야 바이트로 변환하여 Redis에 저장할 수 있다.
- **왜 30분 타임아웃인가?** — 보안과 사용성의 균형. 너무 짧으면 자주 재로그인해야 하고, 너무 길면 공유 PC에서 세션 탈취 위험이 커진다.

**Redis에 실제로 저장되는 구조**

```
redis-cli로 확인:
> KEYS concert:session:*
1) "concert:session:sessions:expires:{sessionId}"   ← 만료 관리용
2) "concert:session:sessions:{sessionId}"            ← 실제 세션 데이터 (Hash)

> HGETALL concert:session:sessions:{sessionId}
1) "sessionAttr:USER"              ← 키 (속성 이름)
2) "\xac\xed\x00\x05sr\x00..."    ← 값 (Java 직렬화된 바이트)
3) "creationTime"
4) "1710000000000"
5) "lastAccessedTime"
6) "1710000060000"
7) "maxInactiveInterval"
8) "1800"                           ← 30분 = 1800초
```

**사용한 Redis 명령어 정리 (Spring Session이 내부적으로 수행)**

| 동작 | Redis 명령어 | 설명 |
|------|-------------|------|
| 세션 생성 | `HSET`, `EXPIRE` | Hash에 속성 저장 + TTL 설정 |
| 속성 조회 | `HGET` | 특정 세션 속성 읽기 |
| 세션 갱신 | `EXPIRE` | 요청마다 TTL 리셋 (sliding) |
| 세션 삭제 | `DEL` | 로그아웃 시 즉시 삭제 |

**실무에서는 어떻게 하는가**

- **어디서 쓰이나?**: 네이버, 카카오, 쿠팡 등 대부분의 대형 서비스가 Redis 기반 세션 저장소를 사용한다. 특히 로드밸런서 뒤에 여러 WAS를 두는 환경에서는 필수다. Spring Session + Redis 조합이 Java 생태계에서 가장 보편적이다.
- **다중 서버 환경**: Redis 세션의 가장 큰 장점은 여러 WAS 인스턴스가 세션을 공유할 수 있다는 것이다. 로드밸런서 뒤에 서버 A, B가 있을 때, A에서 로그인한 사용자가 B로 라우팅되어도 동일한 세션을 읽을 수 있다 (Sticky Session 불필요).
- **직렬화 방식**: Java 기본 직렬화는 클래스 변경에 취약하고 사람이 읽을 수 없다. 실무에서는 JSON 직렬화(`GenericJackson2JsonRedisSerializer`)로 변경하여 가독성과 호환성을 높인다.
- **세션 vs JWT**: 최근 MSA 환경에서는 서버 측 세션 대신 JWT(JSON Web Token)를 사용하는 추세도 있다. JWT는 서버에 상태를 저장하지 않지만, 토큰 무효화가 어렵다는 단점이 있다. Redis 세션은 즉시 무효화가 가능하다는 장점이 있다.
- **보안 강화**: 세션 쿠키에 `HttpOnly`, `Secure`, `SameSite=Strict` 플래그를 설정하여 XSS/CSRF 공격을 방어한다.

> **💡 면접 포인트**: "세션 vs JWT"는 단골 질문이다. **Redis 세션은 서버에서 즉시 무효화 가능하지만 저장소 의존**, **JWT는 무상태지만 토큰 무효화 어려움** — 이 트레이드오프를 명확히 설명할 수 있어야 한다.

---

## Phase 1 — 도메인 확장 + Cache-Aside

### - [ ] Unit 1: Concert/Seat 도메인 구축

**학습 목표**
- 콘서트 예매의 핵심 도메인(공연, 좌석)을 JPA 엔티티로 모델링한다.
- REST API를 통해 공연 목록 조회, 좌석 조회 기능을 제공한다.

**구현 대상**
- `Concert` 엔티티 — id, title, venue, dateTime, totalSeats
- `Seat` 엔티티 — id, concertId, seatNumber, status(AVAILABLE/HELD/RESERVED)
- `GET /api/concerts` — 공연 목록
- `GET /api/concerts/{id}/seats` — 특정 공연의 좌석 목록

**Redis 핵심 개념**
- 이 단계에서는 Redis를 사용하지 않음. DB 기반 CRUD를 먼저 안정화한다.

**설계 힌트**
- `Seat.status`는 Enum으로 관리하고, 상태 전이(AVAILABLE → HELD → RESERVED)를 명확히 정의한다.
- 초기 데이터는 `data.sql` 또는 `ApplicationRunner`로 시딩한다.

**검증 방법**
- API 호출로 공연/좌석 CRUD가 정상 동작하는지 확인
- 통합 테스트 작성

---

### - [ ] Unit 2: Cache-Aside 패턴

**학습 목표**
- 가장 보편적인 캐시 패턴인 Cache-Aside(Lazy Loading)를 이해하고 구현한다.
- 캐시 히트/미스 흐름, TTL 설정, 캐시 무효화 전략을 익힌다.

**구현 대상**
- 공연 목록 조회에 Cache-Aside 적용
  - 캐시 히트 → Redis에서 반환
  - 캐시 미스 → DB 조회 → Redis에 저장 → 반환
- 공연 정보 변경 시 캐시 무효화 (Cache Invalidation)

**Redis 핵심 개념**
- **String**: `GET`, `SET`, `DEL`
- **TTL**: 캐시 만료를 통한 데이터 일관성 확보
- **직렬화**: Java 객체 → JSON 변환 (Jackson 사용)

**설계 힌트**
```
키:      concert:list          → 전체 목록 캐시
키:      concert:detail:{id}   → 개별 공연 캐시
TTL:     5분 (학습용으로 짧게)
무효화:  공연 생성/수정/삭제 시 관련 키 DEL
```
- `RedisTemplate<String, String>`을 사용하고 JSON 직렬화를 직접 해 보는 것을 권장.
- Spring `@Cacheable`은 나중에 비교 학습용으로 남겨둔다.

**실무에서는 어디에 쓰이나?**
- **쿠팡 상품 상세 페이지**: 수백만 사용자가 동시에 같은 상품을 조회한다. 매번 DB를 치면 DB가 죽으므로, 상품 정보를 Redis에 캐싱하고 TTL로 갱신한다.
- **네이버 검색어 자동완성**: 인기 검색어 목록을 Redis에 캐싱하여 타이핑할 때마다 DB를 조회하지 않는다.
- **인스타그램 프로필**: 사용자 프로필 정보를 Cache-Aside로 캐싱. 프로필 수정 시에만 캐시 무효화.
- **배달의민족 가게 목록**: 지역별 가게 리스트를 캐싱하여 메인 화면 로딩 속도를 높인다.

**검증 방법**
- 첫 번째 조회: DB 쿼리 로그 출력 확인 (캐시 미스)
- 두 번째 조회: DB 쿼리 없음 확인 (캐시 히트)
- `redis-cli`로 키 존재 여부, TTL 확인
- 공연 수정 후 캐시가 무효화되는지 확인

---

## Phase 2 — 동시성 제어 (분산 락)

### - [ ] Unit 3: SETNX 기반 분산 락 (직접 구현)

**학습 목표**
- 동시 좌석 예매 시 발생하는 동시성 문제를 이해한다.
- Redis `SETNX`를 활용해 분산 락을 직접 구현한다.
- 락의 획득, 해제, 타임아웃 처리를 직접 다뤄본다.

**구현 대상**
- `POST /api/reservations` — 좌석 예매 API
- `SimpleRedisLock` 클래스 — SETNX + TTL 기반 락
  - `tryLock(key, timeout)` → Boolean
  - `unlock(key)` → void

**Redis 핵심 개념**
- **SETNX** (SET if Not eXists): 원자적 키 생성
- **SET key value EX seconds NX**: SETNX + TTL을 하나의 명령으로
- **DEL**: 락 해제

**설계 힌트**
```
키:      lock:seat:{concertId}:{seatNumber}
값:      UUID (락 소유자 식별)
TTL:     5초 (데드락 방지)

흐름:
1. SET lock:seat:1:A1 {uuid} EX 5 NX
2. 성공 → 좌석 상태 변경 → DEL lock:seat:1:A1
3. 실패 → "이미 선택된 좌석" 응답
```
- 반드시 자신의 락만 해제하도록 UUID 비교 로직을 넣는다.
- 락 해제 시 GET + 비교 + DEL이 원자적이지 않은 문제를 인식한다 (→ Unit 4에서 해결).

**실무에서는 어디에 쓰이나?**
- **쿠팡 재고 차감**: 동시에 100명이 마지막 1개 상품을 주문하면? 분산 락으로 한 명만 차감에 성공하도록 보장한다.
- **토스 쿠폰 발급**: 선착순 쿠폰 발급 시 중복 발급을 방지하기 위해 `SETNX` 기반 락을 사용한다.
- **결제 중복 방지**: 사용자가 결제 버튼을 연타해도 한 번만 처리되도록 락을 건다.

**검증 방법**
- 동일 좌석에 동시 요청 2개 전송 → 1개만 성공하는지 확인
- JMeter 또는 `ExecutorService`로 동시성 테스트
- 락 TTL 만료 후 재예매 가능한지 확인

---

### - [ ] Unit 4: Redisson 분산 락

**학습 목표**
- 프로덕션 레벨 분산 락 라이브러리인 Redisson을 사용한다.
- Unit 3의 직접 구현과 비교하여 어떤 문제를 해결하는지 이해한다.
- Watchdog 메커니즘(락 자동 연장)을 학습한다.

**구현 대상**
- Redisson 의존성 추가 및 설정
- `RedissonLockService`로 좌석 예매 락 교체
- AOP 기반 `@DistributedLock` 어노테이션 (선택)

**Redis 핵심 개념**
- **Redisson RLock**: 재진입 가능한 분산 락
- **Watchdog**: 락 보유 중 TTL 자동 갱신 (기본 30초, 10초마다 연장)
- **Lua Script**: Redisson 내부에서 원자적 연산에 사용 (GET + 비교 + DEL 문제 해결)

**설계 힌트**
```java
RLock lock = redisson.getLock("lock:seat:" + concertId + ":" + seatNumber);
boolean acquired = lock.tryLock(3, 5, TimeUnit.SECONDS);
// waitTime=3초, leaseTime=5초
try {
    if (acquired) {
        // 좌석 예매 로직
    }
} finally {
    if (lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```
- Unit 3의 `SimpleRedisLock`과 성능/안정성을 비교해 본다.

**실무에서는 어디에 쓰이나?**
- Unit 3과 동일한 사례이지만, **프로덕션에서는 거의 항상 Redisson 같은 라이브러리를 사용**한다. 직접 구현한 락은 엣지 케이스(네트워크 파티션, GC pause 중 TTL 만료 등)에 취약하다.
- **우아한형제들(배민)**: Redisson 분산 락으로 주문-결제 동시성 제어. 기술 블로그에서 Redisson 활용 사례를 공개한 바 있다.

**검증 방법**
- Unit 3과 동일한 동시성 테스트 수행
- Watchdog 동작 확인: leaseTime을 -1로 설정 후 긴 작업 실행
- `redis-cli MONITOR`로 Redisson이 보내는 명령어 관찰

---

## Phase 3 — 대기열 + Rate Limiting

### - [ ] Unit 5: Sorted Set 대기열

**학습 목표**
- Redis Sorted Set을 활용한 대기열(Waiting Queue)을 구현한다.
- 선착순 티켓팅에서 사용자 순서를 관리하는 방법을 익힌다.

**구현 대상**
- `POST /api/queue/enter` — 대기열 진입 (Sorted Set에 추가)
- `GET /api/queue/rank` — 내 대기 순번 조회
- `GET /api/queue/allow` — 상위 N명 통과 처리 (스케줄러 또는 관리자 API)

**Redis 핵심 개념**
- **Sorted Set**: ZADD, ZRANK, ZRANGE, ZREM, ZSCORE
- **Score**: timestamp를 score로 사용하여 선착순 보장
- **ZCARD**: 대기열 총 인원 조회

**설계 힌트**
```
키:      queue:concert:{concertId}
멤버:    userId
스코어:  System.currentTimeMillis() (진입 시각)

진입:    ZADD queue:concert:1 1710000000000 user:42
순번:    ZRANK queue:concert:1 user:42  → 0부터 시작
통과:    ZRANGE queue:concert:1 0 49    → 상위 50명 조회
         ZREM queue:concert:1 user:42   → 통과된 사용자 제거
```
- 통과된 사용자를 별도 Set(`queue:passed:{concertId}`)에 저장하여 예매 자격을 검증한다.
- 스케줄러(`@Scheduled`)로 주기적으로 상위 N명을 통과시킨다.

**실무에서는 어디에 쓰이나?**
- **인터파크/멜론 티켓팅**: 수만 명이 동시에 몰리는 콘서트 티켓 오픈 시, Sorted Set 대기열로 순서를 관리하고 순차적으로 예매 페이지에 진입시킨다.
- **수강신청 시스템**: 대학교 수강신청 시 동시 접속자를 대기열로 관리하여 서버 과부하를 방지한다.
- **네이버 스마트스토어 타임세일**: 한정 수량 상품 판매 시 대기열로 공정한 순서를 보장한다.
- **놀이공원 가상 대기열**: 에버랜드/롯데월드 인기 놀이기구 예약 시스템.

**검증 방법**
- 여러 사용자가 순서대로 진입했을 때 ZRANK가 올바른지 확인
- 통과 처리 후 대기열에서 제거되고, 예매 API 접근이 가능한지 확인
- `redis-cli ZRANGE queue:concert:1 0 -1 WITHSCORES`로 상태 확인

---

### - [ ] Unit 6: Rate Limiting (Sliding Window)

**학습 목표**
- API 호출 빈도를 제한하는 Rate Limiting을 Redis로 구현한다.
- Fixed Window와 Sliding Window Log 방식의 차이를 이해한다.

**구현 대상**
- Sliding Window Log 기반 Rate Limiter
- `RateLimitInterceptor`로 특정 API에 적용
- 예: 좌석 예매 API를 사용자당 10초에 5회로 제한

**Redis 핵심 개념**
- **Sorted Set**: 요청 timestamp를 score와 member로 저장
- **ZREMRANGEBYSCORE**: 윈도우 밖의 오래된 요청 제거
- **ZCARD**: 윈도우 내 요청 수 카운트

**설계 힌트**
```
키:      ratelimit:{userId}:{endpoint}
멤버:    요청 UUID 또는 nano timestamp (중복 방지)
스코어:  System.currentTimeMillis()

흐름:
1. ZREMRANGEBYSCORE key 0 (now - windowSize)   // 만료 요청 제거
2. ZCARD key                                     // 현재 요청 수
3. if count < limit → ZADD key now requestId     // 요청 허용
4. EXPIRE key windowSize                          // 키 TTL 설정
```
- 1~4를 Lua Script로 묶으면 원자성이 보장된다 (Phase 4 미리보기).

**실무에서는 어디에 쓰이나?**
- **API Gateway**: Kong, AWS API Gateway 등에서 클라이언트별 API 호출 횟수를 Redis로 제한한다. 대부분의 SaaS API(GitHub API, OpenAI API 등)가 Rate Limiting을 적용한다.
- **로그인 시도 제한**: 브루트포스 공격 방어를 위해 동일 IP/계정의 로그인 실패 횟수를 Redis로 추적하고, 5회 실패 시 10분 잠금.
- **크롤링 방어**: 비정상적으로 빠른 요청을 감지하여 봇 트래픽을 차단한다.
- **SMS 발송 제한**: 동일 번호로 1분에 1회, 하루 5회 등의 발송 제한.

**검증 방법**
- 제한 횟수 초과 시 429 Too Many Requests 응답 확인
- 윈도우 시간 경과 후 다시 요청 가능한지 확인
- `redis-cli ZCARD`로 윈도우 내 요청 수 확인

---

## Phase 4 — Pub/Sub, Lua Script, 운영

### - [ ] Unit 7: Pub/Sub + Lua Script

**학습 목표**
- Redis Pub/Sub를 이용한 실시간 이벤트 전파를 구현한다.
- Lua Script로 여러 Redis 명령을 원자적으로 실행하는 방법을 익힌다.

**구현 대상**
- **Pub/Sub**: 좌석 상태 변경 시 실시간 알림
  - 채널: `concert:{concertId}:seat-updates`
  - 메시지: `{ seatNumber, status, userId }`
  - queue-server에서 구독 → 로그 기록 또는 WebSocket 전파 (선택)
- **Lua Script**: Rate Limiter를 Lua Script로 리팩토링
  - Unit 6의 4단계(ZREMRANGEBYSCORE → ZCARD → ZADD → EXPIRE)를 하나의 스크립트로

**Redis 핵심 개념**
- **PUBLISH / SUBSCRIBE**: Fire-and-forget 메시징
- **Pub/Sub 한계**: 메시지 유실 가능, 구독자 없으면 버려짐 (→ Stream과 비교)
- **EVAL / EVALSHA**: Lua Script 실행
- **KEYS / ARGV**: Lua Script 파라미터 전달

**설계 힌트**
```lua
-- rate_limiter.lua
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
local requestId = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
local count = redis.call('ZCARD', key)
if count < limit then
    redis.call('ZADD', key, now, requestId)
    redis.call('EXPIRE', key, math.ceil(window / 1000))
    return 1  -- 허용
end
return 0  -- 거부
```

**실무에서는 어디에 쓰이나?**
- **카카오톡/슬랙 채팅**: 메시지가 전송되면 Pub/Sub로 해당 채팅방의 모든 접속자에게 실시간 전파한다.
- **실시간 알림 시스템**: 주문 상태 변경, 배송 시작 등의 이벤트를 Pub/Sub로 전파하여 앱 푸시 알림을 트리거한다.
- **설정 변경 전파**: 여러 서버에 분산된 캐시를 동시에 무효화할 때, Pub/Sub로 "캐시 삭제" 메시지를 전파한다.
- **실시간 대시보드**: 모니터링 지표, 주식 시세, 스포츠 경기 스코어 등의 실시간 업데이트.

**검증 방법**
- Pub/Sub: 두 터미널에서 `redis-cli SUBSCRIBE` / `PUBLISH`로 기본 동작 확인 후 앱 코드 테스트
- Lua Script: 동일 Rate Limiter 테스트가 통과하는지 확인
- `redis-cli MONITOR`로 Lua 실행이 단일 명령으로 처리되는지 확인

---

### - [ ] Unit 8: 운영 지식 종합

**학습 목표**
- Redis를 프로덕션에서 운영할 때 알아야 할 핵심 개념을 정리한다.
- 이 프로젝트에서 학습한 내용을 실무 관점에서 점검한다.

**학습 대상 (구현보다 이해 중심)**

| 주제 | 핵심 내용 |
|------|----------|
| **메모리 관리** | `maxmemory` 설정, Eviction 정책 (allkeys-lru, volatile-ttl 등) |
| **영속화** | RDB 스냅샷 vs AOF 로그, 하이브리드 전략 |
| **복제 & 센티널** | Master-Replica 구조, Sentinel 자동 페일오버 |
| **클러스터** | 해시 슬롯(16384개), 리샤딩, 클라이언트 리다이렉트 |
| **모니터링** | `INFO` 명령어, `SLOWLOG`, Redis Insight |
| **보안** | ACL, `requirepass`, 바인드 주소, TLS |

**설계 힌트**
- Docker Compose에 Redis Replica를 추가하여 복제를 직접 확인해 본다.
- `redis-cli INFO memory`로 메모리 사용량을 관찰한다.
- `CONFIG SET maxmemory 10mb`로 Eviction 동작을 실험한다.

**검증 방법**
- Redis 설정 변경 후 동작 확인
- 장애 시나리오 시뮬레이션: Master 종료 → Sentinel 페일오버 관찰
- 전체 프로젝트의 Redis 키를 정리하고 네이밍 컨벤션 준수 여부 점검

---

## 부록

### A. 학습 순서 요약

| Unit | 주제 | Phase | 핵심 Redis 개념 | 난이도 |
|------|------|-------|-----------------|--------|
| 1 | Concert/Seat 도메인 구축 | 1 | (없음 — DB 기반) | ★☆☆☆☆ |
| 2 | Cache-Aside 패턴 | 1 | String, GET/SET, TTL, DEL | ★★☆☆☆ |
| 3 | SETNX 분산 락 (직접 구현) | 2 | SETNX, SET NX EX | ★★★☆☆ |
| 4 | Redisson 분산 락 | 2 | RLock, Watchdog, Lua (내부) | ★★★☆☆ |
| 5 | Sorted Set 대기열 | 3 | ZADD, ZRANK, ZRANGE, ZREM | ★★★☆☆ |
| 6 | Rate Limiting (Sliding Window) | 3 | Sorted Set, ZREMRANGEBYSCORE | ★★★★☆ |
| 7 | Pub/Sub + Lua Script | 4 | PUBLISH/SUBSCRIBE, EVAL | ★★★★☆ |
| 8 | 운영 지식 종합 | 4 | 메모리, 복제, 클러스터, 모니터링 | ★★★★★ |

### B. Redis 키 네이밍 컨벤션

| 용도 | 키 패턴 | 자료구조 | TTL |
|------|---------|----------|-----|
| 인증 코드 | `auth:{email}` | String | 3분 |
| 이메일 큐 | `mail:queue` | List | 없음 |
| 세션 | `concert:session:{sessionId}` | Hash | 30분 |
| 공연 목록 캐시 | `concert:list` | String (JSON) | 5분 |
| 공연 상세 캐시 | `concert:detail:{concertId}` | String (JSON) | 5분 |
| 좌석 락 | `lock:seat:{concertId}:{seatNumber}` | String | 5초 |
| 대기열 | `queue:concert:{concertId}` | Sorted Set | 없음 |
| 대기열 통과 | `queue:passed:{concertId}` | Set | 없음 |
| Rate Limit | `ratelimit:{userId}:{endpoint}` | Sorted Set | 윈도우 크기 |
| Pub/Sub 채널 | `concert:{concertId}:seat-updates` | (채널) | — |

**네이밍 규칙**
- 구분자: 콜론(`:`)
- 형식: `도메인:용도:식별자`
- 소문자 + 케밥 표기 (예: `seat-updates`)
- 가변 부분은 중괄호로 표시 (예: `{concertId}`)

---

### C. 실무 Redis 활용 사례 모음

로드맵에서 직접 구현하지 않지만, 실무에서 자주 만나는 Redis 패턴을 카탈로그로 정리한다.

#### 1. 실시간 랭킹 / 리더보드

| 항목 | 내용 |
|------|------|
| **자료구조** | Sorted Set |
| **쓰는 곳** | 게임 랭킹(리그오브레전드 래더), 멜론 실시간 차트, 네이버 인기 검색어, 쿠팡 인기 상품 |
| **왜 Redis?** | Sorted Set의 `ZADD`/`ZRANK`/`ZREVRANGE`가 O(log N)으로 매우 빠르다. RDBMS의 `ORDER BY + LIMIT`은 데이터가 커지면 느려지지만, Redis는 항상 일정한 성능을 보장한다. |
| **핵심 명령어** | `ZADD ranking 1500 user:42` — 점수 등록/갱신<br>`ZREVRANK ranking user:42` — 내 순위 조회 (0부터)<br>`ZREVRANGE ranking 0 9 WITHSCORES` — 상위 10명 조회<br>`ZINCRBY ranking 100 user:42` — 점수 증가 |

```
[예시: 게임 랭킹]
ZADD game:ranking 2100 "player:faker"
ZADD game:ranking 1800 "player:chovy"
ZADD game:ranking 2300 "player:gumayusi"

ZREVRANGE game:ranking 0 2 WITHSCORES
→ 1) "player:gumayusi"  2300
→ 2) "player:faker"     2100
→ 3) "player:chovy"     1800
```

#### 2. 좋아요 / 카운터

| 항목 | 내용 |
|------|------|
| **자료구조** | String (INCR/DECR) |
| **쓰는 곳** | 인스타그램 좋아요 수, 유튜브 조회수, 네이버 뉴스 댓글 수, 재고 수량 관리 |
| **왜 Redis?** | `INCR`/`DECR`이 원자적(atomic)이라 동시에 1000명이 좋아요를 눌러도 정확한 카운트를 보장한다. DB UPDATE는 락 경합이 발생하지만 Redis는 단일 스레드라 자연스럽게 직렬화된다. |
| **핵심 명령어** | `INCR post:123:likes` — 좋아요 +1<br>`DECR post:123:likes` — 좋아요 취소<br>`GET post:123:likes` — 현재 수 조회<br>`INCRBY product:456:stock -1` — 재고 1개 차감 |

> **팁**: 카운터 값은 주기적으로 DB에 동기화(Write-Back)한다. Redis가 날아가도 DB에서 복구할 수 있도록.

#### 3. 최근 본 상품

| 항목 | 내용 |
|------|------|
| **자료구조** | List 또는 Sorted Set |
| **쓰는 곳** | 쿠팡/11번가 "최근 본 상품", 넷플릭스 "계속 시청하기", 유튜브 시청 기록 |
| **왜 Redis?** | 사용자별로 빠르게 최근 N개를 유지해야 하고, 오래된 항목은 자동 제거해야 한다. List의 `LPUSH` + `LTRIM`으로 간단히 구현 가능. |
| **핵심 명령어** | `LPUSH recent:user:42 "product:789"` — 최근 본 상품 추가<br>`LTRIM recent:user:42 0 19` — 최근 20개만 유지<br>`LRANGE recent:user:42 0 9` — 최근 10개 조회 |

```
[Sorted Set 버전 — 시간순 정렬 + 중복 제거]
ZADD recent:user:42 1710000000 "product:789"
ZADD recent:user:42 1710000060 "product:123"
ZREVRANGE recent:user:42 0 9   → 최근 10개 (시간 역순)
```

#### 4. 온라인 유저 추적 / DAU 측정

| 항목 | 내용 |
|------|------|
| **자료구조** | Set 또는 HyperLogLog |
| **쓰는 곳** | 게임 동시 접속자 수, 서비스 DAU(Daily Active Users) 측정, 실시간 접속자 목록 |
| **왜 Redis?** | Set은 중복을 자동 제거하므로 같은 유저가 여러 번 접속해도 1명으로 카운트. HyperLogLog는 메모리 12KB만으로 수억 개의 유니크 카운트를 근사 계산(오차 0.81%) 할 수 있다. |
| **핵심 명령어** | **Set**: `SADD online:users "user:42"` / `SCARD online:users` — 접속자 수<br>**HyperLogLog**: `PFADD dau:2026-03-16 "user:42"` / `PFCOUNT dau:2026-03-16` — DAU |

> **팁**: 정확한 목록이 필요하면 Set, 대략적인 수만 필요하면 HyperLogLog. DAU 같은 대규모 유니크 카운트에는 HyperLogLog가 메모리 효율에서 압도적이다.

#### 5. 피드 타임라인 (Fan-out)

| 항목 | 내용 |
|------|------|
| **자료구조** | List 또는 Sorted Set |
| **쓰는 곳** | 트위터/인스타그램 타임라인, 카카오스토리 피드, 알림 목록 |
| **왜 Redis?** | 팔로워가 많은 사용자가 글을 쓰면, 각 팔로워의 타임라인(List)에 해당 글 ID를 `LPUSH`한다 (Fan-out on write). 피드 조회 시 `LRANGE`로 빠르게 가져온다. |
| **핵심 명령어** | `LPUSH feed:user:42 "post:999"` — 타임라인에 글 추가<br>`LRANGE feed:user:42 0 19` — 최근 20개 피드 조회<br>`LTRIM feed:user:42 0 999` — 타임라인 최대 1000개 유지 |

#### 6. 지리 검색 (GEO)

| 항목 | 내용 |
|------|------|
| **자료구조** | GEO (내부적으로 Sorted Set) |
| **쓰는 곳** | 배달의민족 "가까운 가게", 카카오맵 주변 검색, 당근마켓 동네 반경, 택시 호출 앱 |
| **왜 Redis?** | `GEOADD`로 위경도를 저장하고, `GEORADIUS`로 반경 N km 내의 결과를 빠르게 검색할 수 있다. PostGIS 같은 전문 GIS보다 간단하고 빠르다. |
| **핵심 명령어** | `GEOADD stores 127.027 37.498 "store:1"` — 좌표 등록<br>`GEORADIUS stores 127.027 37.498 3 km COUNT 10 ASC` — 3km 이내 가까운 10곳 |

#### 7. 중복 요청 방지 (멱등성 키)

| 항목 | 내용 |
|------|------|
| **자료구조** | String (SETNX) |
| **쓰는 곳** | 토스/카카오페이 결제 중복 방지, 주문 중복 생성 방지, API 멱등성 보장 |
| **왜 Redis?** | 클라이언트가 보낸 `Idempotency-Key`를 Redis에 `SETNX`로 저장한다. 이미 존재하면 중복 요청이므로 이전 응답을 반환. TTL로 일정 시간 후 자동 정리. |
| **핵심 명령어** | `SET idempotency:{key} {response} EX 86400 NX` — 24시간 TTL로 중복 차단<br>존재하면 → 이전 응답 반환, 없으면 → 요청 처리 후 저장 |

#### 8. 기능 플래그 (Feature Flag)

| 항목 | 내용 |
|------|------|
| **자료구조** | Hash |
| **쓰는 곳** | 카카오/네이버 A/B 테스트, 점진적 배포(Canary), 긴급 기능 OFF |
| **왜 Redis?** | 기능 ON/OFF를 DB 조회 없이 밀리초 단위로 확인할 수 있다. 배포 없이 실시간으로 기능을 켜고 끌 수 있어 장애 대응에 유용하다. |
| **핵심 명령어** | `HSET feature:flags new-checkout "true"` — 기능 활성화<br>`HGET feature:flags new-checkout` — 기능 상태 확인<br>`HSET feature:flags new-checkout "false"` — 긴급 비활성화 |

#### 9. 분산 세마포어 (동시 접속 제한)

| 항목 | 내용 |
|------|------|
| **자료구조** | Sorted Set |
| **쓰는 곳** | 동시 다운로드 수 제한, API 동시 호출 수 제한, 동시 스트리밍 세션 수 제한 (넷플릭스 동시 접속 4대) |
| **왜 Redis?** | Sorted Set에 timestamp를 score로 하여 현재 세션을 관리. 만료된 세션을 `ZREMRANGEBYSCORE`로 정리하고 `ZCARD`로 현재 수를 확인. 분산 락과 유사하지만 N개까지 동시 허용한다는 차이. |
| **핵심 명령어** | `ZADD semaphore:download {now} {sessionId}` — 세션 등록<br>`ZCARD semaphore:download` — 현재 동시 수<br>`ZREM semaphore:download {sessionId}` — 세션 종료 |

---

### D. Redis 학습 팁

#### redis-cli로 직접 실험하기

Redis를 가장 빠르게 이해하는 방법은 `redis-cli`에서 직접 명령어를 쳐보는 것이다.

```bash
# Redis 접속
redis-cli -p 6379

# 현재 저장된 모든 키 확인 (개발 환경에서만!)
KEYS *

# 실시간으로 Redis에 들어오는 모든 명령어 관찰
MONITOR

# Redis 서버 상태 확인
INFO memory          # 메모리 사용량
INFO clients         # 접속 클라이언트 수
INFO stats           # 명령어 처리 통계
INFO keyspace        # DB별 키 개수

# 특정 키의 타입 확인
TYPE concert:session:abc123

# 특정 키의 남은 TTL 확인
TTL auth:user@test.com

# 느린 쿼리 로그 확인
SLOWLOG GET 10
```

> **팁**: Spring Boot 앱을 실행한 상태에서 `redis-cli MONITOR`를 켜 놓으면, 앱이 Redis에 보내는 모든 명령어를 실시간으로 볼 수 있다. 이것만으로도 Redis 동작 원리를 파악하는 데 큰 도움이 된다.

#### 면접 대비 핵심 질문 목록

**기본**
1. Redis는 왜 빠른가? → 인메모리 + 단일 스레드 + I/O 멀티플렉싱
2. Redis의 자료구조 5가지를 설명하라 → String, List, Set, Sorted Set, Hash
3. Redis는 싱글 스레드인데 왜 동시성 문제가 없는가? → 명령어가 원자적으로 실행되므로
4. TTL이란? 어떤 상황에서 쓰는가?

**캐시**
5. Cache-Aside 패턴을 설명하라. 캐시 미스는 어떻게 처리하는가?
6. 캐시 무효화 전략 3가지 (TTL, Write-Through, Write-Behind)
7. Cache Stampede(Thundering Herd)란? 해결 방법은?
8. 캐시와 DB 간 데이터 일관성은 어떻게 보장하는가?

**동시성**
9. Redis로 분산 락을 어떻게 구현하는가? SETNX의 역할은?
10. Redisson의 Watchdog 메커니즘을 설명하라
11. 분산 환경에서 Redis 단일 노드 락의 한계는? (→ RedLock)

**아키텍처**
12. Redis Pub/Sub와 Kafka의 차이점은?
13. Redis를 메시지 큐로 쓸 때의 장단점
14. 세션 저장소로 Redis를 쓰는 이유. JWT와의 비교
15. Redis Cluster와 Sentinel의 차이

**운영**
16. Redis 메모리가 가득 차면? Eviction 정책 설명
17. RDB와 AOF의 차이. 각각 언제 쓰는가?
18. Redis에서 `KEYS *` 명령을 프로덕션에서 쓰면 안 되는 이유 (→ `SCAN` 사용)

#### 추천 학습 리소스

| 리소스 | 유형 | 설명 |
|--------|------|------|
| Redis 공식 문서 (redis.io/docs) | 문서 | 가장 정확하고 최신. 명령어 레퍼런스는 여기가 최고 |
| 우아한형제들 기술 블로그 | 블로그 | Redisson 분산 락, 선착순 이벤트 등 한국어 실무 사례 |
| NHN FORWARD 발표 자료 | 발표 | "Redis 야무지게 사용하기" — 실무 팁 다수 |
| 강대명 "이것이 레디스다" | 책 | 한국어 Redis 입문서, 실습 위주 |
| "Redis in Action" (Manning) | 책 | 패턴별 실습. 영문이지만 예제 코드가 좋음 |
| Redis University (university.redis.com) | 온라인 강의 | Redis Labs 공식 무료 강의. 자격증도 있음 |

#### 학습 순서 추천

```
1단계: redis-cli로 String, List, Set, Sorted Set, Hash 직접 실험 (1~2일)
        ↓
2단계: 이 프로젝트의 완료된 패턴 3개를 redis-cli MONITOR로 관찰 (1일)
        ↓
3단계: Phase 1 (Cache-Aside) 구현하며 캐시 개념 체득 (3~5일)
        ↓
4단계: Phase 2 (분산 락) 구현하며 동시성 제어 이해 (3~5일)
        ↓
5단계: Phase 3~4는 필요할 때 또는 면접 준비 시 (각 3~5일)
        ↓
6단계: 부록 C의 사례들을 redis-cli에서 직접 실험해보기 (수시로)
```
