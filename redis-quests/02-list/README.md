# Chapter 02 — List

> Redis의 **양방향 연결 리스트**(deque). 한쪽 끝에 넣고 반대쪽에서 빼면 **FIFO 큐**,
> 같은 쪽에서 넣고 빼면 **LIFO 스택**. 양끝 삽입/삭제가 O(1)이라 **메시지 큐의 기본 도구**다.
>
> 🎯 이 프로젝트의 `mail:queue`(환영 메일 큐)가 바로 List다. **String이 "값 1개"였다면, List는 "순서 있는 값들의 줄(line)"** 이다.

## 왜 List부터인가 (실무 직결)

이 프로젝트는 이미 List를 쓰고 있다:

```
[Producer] api-server   AuthService.java:85
   redisTemplate.opsForList().leftPush("mail:queue", email)   → LPUSH mail:queue {email}

[Consumer] queue-server  EmailMultiWorker.java:44
   redisTemplate.opsForList().rightPop("mail:queue", 10s)     → BRPOP mail:queue 10
```

왼쪽으로 넣고(LPUSH) 오른쪽에서 빼니까(BRPOP) **FIFO**. 그런데 이 구조엔 **메시지 유실**이라는 실무 폭탄이 숨어 있다 (꺼낸 순간 사라지므로 워커가 죽으면 끝). 이 챕터의 목표는 그 폭탄을 **Reliable Queue**로 해체하는 것까지다.

> List는 Redis만으로 충분한 임시 큐/최근 목록과, DB 이력까지 필요한 작업 큐가 갈린다.
> 먼저 [Redis와 DB 저장 기준](../00-redis-db-저장기준.md)을 기준으로 중요도를 판단한다.

## 학습 파일

| 단계 | 파일 | 내용 | 비중 |
|------|------|------|------|
| 1 | [01-실습.md](01-실습.md) | 명령어 직접 입력 드릴 (난이도 mix, 함정 위주) | 70+ 문항 |
| 1 | [01-요약.md](01-요약.md) | 개념 정리 & 실무 활용 (읽기용) | — |
| 2 | [02-실무.md](02-실무.md) | 큐/Reliable Queue/DLQ/최근목록/Fan-out 실전 패턴 | 7 패턴 |
| 3 | [03-시나리오.md](03-시나리오.md) | 실무형 면접 (사고력) — 보스급 1 + 라이트 9 | 10 케이스 |
| 3 | [03-면접질문.md](03-면접질문.md) | 단답형 면접 (이론) | 카테고리 A~G |

## 다루는 명령어

```
# 삽입
LPUSH / RPUSH / LPUSHX / RPUSHX

# 추출
LPOP / RPOP            (Redis 6.2+ COUNT 옵션)

# 조회 (큐를 비우지 않고 들여다보기)
LLEN / LRANGE / LINDEX / LPOS

# 수정
LSET / LINSERT / LREM / LTRIM

# 원자적 이동 (Reliable Queue의 핵심)
RPOPLPUSH / LMOVE                 (RPOPLPUSH는 6.2부터 deprecated)

# 블로킹 (폴링 없이 대기)
BLPOP / BRPOP / BRPOPLPUSH / BLMOVE
LMPOP / BLMPOP                    (Redis 7.0+)
```

## 핵심 멘탈 모델

```
        LPUSH(왼쪽 삽입)                      RPUSH(오른쪽 삽입)
              ↓                                     ↓
   head [ A ][ B ][ C ][ D ][ E ] tail
        ↑                       ↑
   LPOP(왼쪽 추출)          RPOP(오른쪽 추출)

  FIFO 큐  = LPUSH + RPOP  (또는 RPUSH + LPOP)  ← 들어온 순서대로
  LIFO 스택 = LPUSH + LPOP  (또는 RPUSH + RPOP)  ← 마지막에 넣은 게 먼저
```

> 💡 헷갈리면 이 한 줄: **"같은 쪽이면 스택, 반대 쪽이면 큐."**

## 진행 방식

1. `01-실습.md` 위에서부터 순서대로 직접 입력 (답 먼저 보지 말 것)
2. 결과가 헷갈리면 `notes.md`(직접 생성)에 메모
3. 다 풀면 Claude에게 **"List 실습 완료"** → 함정 위주 채점/해설
4. `01-요약.md` 읽고 → `02-실무.md`(패턴) → `03-시나리오.md`(사고력) → `03-면접질문.md`(이론) 순

## 실습 환경

```bash
# Redis 접속
docker exec -it <redis-컨테이너명> redis-cli
# 또는
redis-cli -p 6379

# 별도 터미널 — 실시간 명령어 관찰 (특히 큐 동작 볼 때 강력)
redis-cli -p 6379 MONITOR
```

## 참고

- 이전 챕터: [01-string](../01-string/README.md)
- 전체 로드맵: [REDIS_STUDY_ROADMAP.md](../../REDIS_STUDY_ROADMAP.md)
- String에서 만든 부록(재활용): [Lua 스크립트](../01-string/부록-lua-스크립트.md) · [멱등성](../01-string/부록-멱등성.md)
