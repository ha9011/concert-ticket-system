# 04. Sorted Set (ZSet) — 점수로 정렬되는 집합

> Redis Sorted Set: **각 멤버에 score(점수)가 붙어, 그 score 순으로 자동 정렬되는 집합.**
> 한 문장: **"Set의 중복 없음 + score 기반 정렬·범위 조회·순위(rank)가 O(log N)."**

> 공통 기준: Redis를 단독 저장소로 둘지, DB와 함께 둘지 헷갈리면
> 먼저 [Redis와 DB 저장 기준](../00-redis-db-저장기준.md)을 본다.

## 이 챕터 구성 (5종)

| 파일 | 내용 |
|------|------|
| [01-실습.md](01-실습.md) | 명령어 드릴 72문항 — ZADD옵션/ZRANGE 3축/랭킹/POP/집합연산/대기열·리더보드 미션 |
| [01-요약.md](01-요약.md) | 개념 정리 — score 정렬, ZRANGE 3축(rank·score·lex), 동점 사전순, listpack/skiplist, WEIGHTS/AGGREGATE |
| [02-실무.md](02-실무.md) | 실무 패턴 — 리더보드 / 대기열 / 우선순위 큐 / Rate Limit / 지연 큐 / 가중 추첨 / 최근목록 |
| [03-시나리오.md](03-시나리오.md) | 10 실전 케이스 (보스급 1 + 라이트 9) |
| [03-면접질문.md](03-면접질문.md) | 단답 33문항 (구조·명령어·랭킹·대기열/RateLimit·집합연산·인코딩·실전) |

## 학습 순서

**실습 → 요약 → 실무 → 시나리오 → 면접질문** 순서로.

1. **01-실습** — redis-cli로 직접 쳐보며 동작 체득. 답 보지 말고 추측 먼저.
2. **01-요약** — 실습 후 개념 정리.
3. **02-실무** — 실제 서비스에서 ZSet이 어디 쓰이는지.
4. **03-시나리오** — 장애/설계 상황에 적용.
5. **03-면접질문** — 단답으로 마무리 점검.

## ZSet의 핵심 한 장

```
   ZSet board = { ("faker", 2100), ("chovy", 1800), ("gumayusi", 2300) }
                        ↓ score 순으로 자동 정렬
   ZREVRANGE board 0 2 WITHSCORES → gumayusi(2300), faker(2100), chovy(1800)
   ZRANK board "faker"            → 순위(낮은 score부터 0)
   ZRANGEBYSCORE board 1900 2200  → score 구간 조회
```

- **멤버 = 유일(Set처럼 중복 없음)**, 각 멤버에 **score(double)** 가 1개.
- **score 순 자동 정렬** → 랭킹/범위 조회/순위가 O(log N).
- **동점(같은 score)이면 멤버 이름 사전순(lexicographical)** 으로 정렬.

## 이 프로젝트와의 연결

콘서트 티켓 시스템에서 ZSet은 **로드맵 Phase 3의 핵심 자료구조**다:

- **선착순 대기열** (`queue:concert:{id}`): score=진입 시각(ms) → `ZRANK`으로 내 순번, `ZRANGE 0 N`으로 상위 N명 통과. (Phase 3 Unit 5)
- **Rate Limiting** (Sliding Window Log): score=요청 시각 → `ZREMRANGEBYSCORE`로 윈도우 밖 제거 + `ZCARD`로 카운트. (Phase 3 Unit 6)
- **가중 추첨**: Set 추첨(SPOP)의 한계였던 "응모 많이 한 사람 유리"를 score=누적 가중치로 해결.
- **실시간 좌석 인기 랭킹** / **최근 예매 활동**: score=timestamp 또는 카운트.

> 즉 이 챕터를 끝내면 로드맵 **Unit 5(대기열)·Unit 6(Rate Limit)** 구현의 사전 준비가 끝난다.

## 참고

- 전체 로드맵: [REDIS_STUDY_ROADMAP.md](../../REDIS_STUDY_ROADMAP.md)
- 도구 선택 가이드: [MESSAGING_TOOL_GUIDE.md](../../MESSAGING_TOOL_GUIDE.md)
- 이전 챕터: [03-set](../03-set/README.md) (ZSet은 "Set + score 정렬"이라 직접 비교하며 보면 좋다)
