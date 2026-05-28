# 부록 — Redis Lua 스크립트 가이드

> 02-실무.md의 §2(인증코드), §4(카운터), §5(분산락) 모두에서 Lua가 등장한다.
> "확인 후 처리(check-and-act)" 패턴이 필요한 곳마다 거의 다 쓰임.
> 이 문서는 Lua가 무엇이고, 왜 필요하고, 어떻게 쓰는지를 한 페이지로 정리.

---

## §1. Lua가 뭔가

- **Lua** = 가볍고 빠른 임베디드 스크립트 언어. (게임 엔진에서 많이 쓰임)
- Redis는 Lua 인터프리터를 **내장**하고 있어, Lua 코드를 Redis에 보내면 Redis 서버가 실행해줌.
- 즉, **"Redis 안에서 도는 미니 함수"**.

---

## §2. 왜 Redis에서 Lua가 필요한가

### 문제: 여러 명령 사이에 다른 요청이 끼어듦 (Race Condition)

```java
// "코드가 일치하면 삭제" 하고 싶음
String saved = redis.get("auth:user@x.com");      // ① 가져옴
if (saved.equals(input)) {
    redis.delete("auth:user@x.com");              // ② 삭제
}
```

```
시각 10:00:00.001  사용자A → GET → "1234" 가져옴
시각 10:00:00.002  사용자B → GET → "1234" 가져옴   ← 끼어듦!
시각 10:00:00.003  사용자A → DEL → 삭제
시각 10:00:00.004  사용자B → DEL → 이미 없음
```

→ ①과 ② 사이에 다른 요청이 끼어들 수 있음. **원자적이지 않음.**

---

## §3. 트랜잭션(MULTI/EXEC)과 비교

### Redis 트랜잭션의 한계

```
MULTI
GET auth:user@x.com
DEL auth:user@x.com
EXEC
```

두 명령을 묶어서 한 번에 실행 → 중간에 끼어들지 않음 ✅
**하지만 "GET 결과 보고 조건 분기"는 불가능.** 그냥 일괄 발사만 가능.

### Lua가 해결

Lua 스크립트는 **조건 분기, 변수, 계산이 다 됨**. 그리고 **실행 중엔 다른 명령이 안 끼어듦** (Redis는 싱글 스레드 + 스크립트는 원자적 실행).

```lua
local saved = redis.call('GET', KEYS[1])
if saved == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 'OK'
else
    return 'MISMATCH'
end
```

→ 이 전체가 **하나의 원자적 단위**로 실행됨.

### 비교표

| 도구 | 묶음 실행 | 조건 분기 | 비고 |
|---|---|---|---|
| 일반 명령 | ❌ | ❌ | Race condition 위험 |
| **트랜잭션 (MULTI/EXEC)** | ✅ | ❌ | 여러 명령 일괄 발사만 |
| **Lua 스크립트** | ✅ | ✅ | 사실상 미니 프로그램 |

### 비유

```
일반 명령    = 카페에서 음료 하나씩 시키기 (사이에 다른 손님 끼어듦)
트랜잭션     = 주문서 적어서 한 번에 내기 (조건은 못 적음)
Lua 스크립트 = 알바생에게 절차 메모 통째로 넘기기 (재고 확인 → 차감 → 알림)
```

---

## §4. 파라미터 규칙 — KEYS와 ARGV

스크립트에 값을 넘길 때는 **두 종류의 배열**로 전달.

```lua
KEYS[1], KEYS[2], KEYS[3], ...    ← Redis 키들
ARGV[1], ARGV[2], ARGV[3], ...    ← 그 외 일반 값들
```

| 종류 | 용도 | 예시 |
|---|---|---|
| **KEYS** | Redis에서 다룰 **키 이름**들 | `"auth:user@x.com"`, `"lock:concert:7"` |
| **ARGV** | 그 외 **일반 값/파라미터** | 사용자 입력, TTL 초, 최대 시도 횟수 |

> ⚠️ **Lua 배열 인덱스는 1부터 시작** (자바·C는 0부터)

### 왜 분리되어 있나?

**Redis Cluster 때문.** 클러스터에서는 키마다 저장 서버가 다른데, Redis가 "어느 서버로 스크립트 보낼지" 판단하려면 사용할 키들을 미리 알아야 함.

```
KEYS = 라우팅에 사용됨 (어느 서버로 보낼지 결정)
ARGV = 그냥 데이터 (라우팅과 무관)
```

→ **키는 무조건 KEYS, 값은 무조건 ARGV**. 섞으면 Cluster에서 깨짐.

### ❌ 잘못된 예

```lua
local stored = redis.call('GET', ARGV[1])   -- ❌ 키를 ARGV에 넣음
local code = KEYS[1]                         -- ❌ 값을 KEYS에 넣음
```

### ✅ 올바른 예

```lua
local stored = redis.call('GET', KEYS[1])    -- 키는 KEYS
if stored == ARGV[1] then ...                -- 값은 ARGV
```

---

## §5. 자료형 주의 — 다 문자열로 들어옴

Java에서 보내는 값은 Lua에선 **모두 문자열**. 숫자 비교/계산 시 `tonumber()` 필수.

```lua
local n = ARGV[1]               -- "5" (문자열)
if n >= 5 then ... end          -- ❌ 문자열 vs 숫자 → 에러

if tonumber(n) >= 5 then ... end   -- ✅ 변환 후 비교
```

반대로 Lua가 반환하는 값도 자바에선 보통 `Long`, `String` 등으로 받음. 반환 타입 명시 필요:

```java
new DefaultRedisScript<>(script, Long.class);   // 반환 타입
```

---

## §6. 자바 (Spring Data Redis) 연동

### 기본 형태

```java
// 1. 스크립트 객체 생성
String script =
    "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
    "    return redis.call('DEL', KEYS[1]) " +
    "else " +
    "    return 0 " +
    "end";

DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);

// 2. 실행
Long result = redisTemplate.execute(
    redisScript,
    List.of("lock:concert:7"),   // KEYS  (List<String>)
    owner                         // ARGV  (가변 인자로 쭉 나열)
);
```

### 매핑 확인

```
KEYS[1] ← "lock:concert:7"     (List의 1번째)
ARGV[1] ← owner                (가변 인자의 1번째)
```

### 스크립트는 미리 만들어두기 (캐싱)

매번 String을 만들지 말고 빈으로 관리:

```java
@Configuration
public class LuaScripts {
    @Bean
    public DefaultRedisScript<Long> releaseLockScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptText("if redis.call('GET', KEYS[1]) == ARGV[1] then "
                      + "return redis.call('DEL', KEYS[1]) else return 0 end");
        s.setResultType(Long.class);
        return s;
    }
}
```

→ Redis는 스크립트를 SHA1 해시로 캐싱해서 두 번째 호출부터는 더 빠름 (EVALSHA).

---

## §7. 실전 패턴 5가지

### 패턴 1: 분산락 안전 해제 (owner 검증 후 DEL)

```lua
-- KEYS[1] = 락 키
-- ARGV[1] = 내 owner UUID
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

```java
redisTemplate.execute(releaseLockScript, List.of(lockKey), owner);
```

### 패턴 2: 인증 코드 검증 (compare-and-delete + 시도 제한)

```lua
-- KEYS[1] = 코드 키, KEYS[2] = 시도 횟수 키
-- ARGV[1] = 입력 코드, ARGV[2] = 최대 시도 횟수
local stored = redis.call('GET', KEYS[1])
if not stored then
    return 'EXPIRED'
end
if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'OK'
end
local attempts = redis.call('INCR', KEYS[2])
redis.call('EXPIRE', KEYS[2], 180)
if tonumber(attempts) >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'BLOCKED'
end
return 'MISMATCH'
```

```java
String result = redisTemplate.execute(
    authVerifyScript,
    List.of("auth:" + email, "auth:attempts:" + email),
    userInput, "5"
);
```

### 패턴 3: 재고 차감 (음수 방어)

```lua
-- KEYS[1] = 재고 키
-- ARGV[1] = 차감할 수량
local stock = tonumber(redis.call('GET', KEYS[1]))
local amount = tonumber(ARGV[1])
if not stock or stock < amount then
    return -1   -- 품절
end
return redis.call('DECRBY', KEYS[1], amount)
```

```java
Long left = redisTemplate.execute(decrStockScript, List.of("stock:concert:1"), "2");
if (left == -1) throw new CustomException(SOLD_OUT);
```

### 패턴 4: SET NX + 값 반환 (선점 시도)

```lua
-- KEYS[1] = 키
-- ARGV[1] = 값, ARGV[2] = TTL 초
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
    return 1   -- 내가 선점함
else
    return redis.call('GET', KEYS[1])   -- 기존 값 반환
end
```

### 패턴 5: TTL 유지하며 값 갱신 (KEEPTTL 대안)

```lua
-- KEYS[1] = 키
-- ARGV[1] = 새 값
local ttl = redis.call('TTL', KEYS[1])
redis.call('SET', KEYS[1], ARGV[1])
if ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl) end
return 'OK'
```

---

## §8. 흔한 함정

| 함정 | 결과 |
|---|---|
| KEYS와 ARGV 혼동 | Cluster에서 라우팅 실패 |
| 인덱스 0부터 시작이라 착각 | `KEYS[0]` 은 `nil` (Lua는 1부터) |
| `tonumber()` 안 씀 | 문자열 비교/연산 → 잘못된 결과 |
| 무거운 스크립트 (반복문 1만회 등) | Redis 싱글 스레드 블로킹 → 전체 지연 |
| 스크립트 안에서 외부 호출 시도 | 불가능 (Redis만 호출 가능) |
| 매번 풀 스크립트 전송 | 네트워크 낭비 → 빈 캐싱 + EVALSHA 활용 |
| 반환 타입 미지정 | 자바에서 ClassCastException |

### Lua 스크립트는 "짧고 빨라야" 한다

Redis는 싱글 스레드. Lua 스크립트가 실행되는 동안 **다른 모든 명령이 대기**.
→ 스크립트 안에서 반복문 1만 회 같은 무거운 연산은 금지. 짧게, 핵심만.

---

## §9. 디버깅 팁

### redis-cli로 직접 실행

```bash
redis-cli EVAL "return redis.call('GET', KEYS[1])" 1 mykey
#                                                  ↑
#                                              KEYS 개수
```

```bash
# 인증 검증 스크립트 직접 테스트
redis-cli EVAL "$(cat verify.lua)" 2 auth:test@x.com auth:attempts:test@x.com 1234 5
```

### 로그 출력 (Redis 서버 로그에 찍힘)

```lua
redis.log(redis.LOG_WARNING, "디버그: stored=" .. tostring(stored))
```

---

## §10. 이 프로젝트 적용 포인트

| 위치 | 적용할 Lua 패턴 |
|---|---|
| `AuthService.verifyCode()` | 패턴 2 (인증 코드 검증) |
| 분산락 해제 (향후 좌석 예매) | 패턴 1 (owner 검증 후 DEL) — Redisson 미사용 시 |
| 좌석 재고 차감 (향후) | 패턴 3 (음수 방어 DECR) |
| 캐시 갱신 시 TTL 유지 | 패턴 5 (TTL 유지 SET) |

---

## §11. 한 줄 정리

> **Lua = "Redis 안에서 돌아가는 미니 함수".**
>
> 트랜잭션이 "여러 명령 묶기"라면, Lua는 **"여러 명령 + 로직"까지 묶기**.
> "확인 후 처리(check-and-act)" 패턴엔 무조건 Lua.
>
> **KEYS = 다룰 키들 🔑, ARGV = 그 외 값들 📝. 인덱스는 1부터. 숫자는 `tonumber()`.**

---

## 외울 템플릿

### Lua 쪽

```lua
-- KEYS[1] = ___
-- ARGV[1] = ___
local x = redis.call('명령', KEYS[1])
if x == ARGV[1] then
    return ...
end
```

### Java 쪽

```java
redisTemplate.execute(
    new DefaultRedisScript<>(script, 반환타입.class),
    List.of(키1, 키2),     // KEYS
    값1, 값2                // ARGV
);
```

이 두 덩어리면 90%의 Lua 패턴이 다 짜진다. 🎯
