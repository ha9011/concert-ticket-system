# Redis 퀘스트

> 콘서트 티켓 시스템 프로젝트에서 Redis를 자료구조 단위로 깊게 학습한다.
> 각 자료구조 폴더는 **실습 → 실무 → 면접** 순으로 구성.

## 진행도

| 챕터 | 자료구조 | 실습 | 실무 | 면접 | 상태 |
|------|---------|:----:|:----:|:----:|------|
| 01 | [String](01-string/README.md) | ⬜ | ⬜ | ⬜ | 진행 중 |
| 02 | List | - | - | - | 대기 |
| 03 | Set | - | - | - | 대기 |
| 04 | Sorted Set | - | - | - | 대기 |
| 05 | Hash | - | - | - | 대기 |

## 학습 규칙

1. **한 챕터씩, 순서대로** — String 전체(실습→실무→면접) 끝낸 뒤 List로
2. **답을 먼저 보지 않기** — `solutions/`는 풀고 난 뒤 확인용
3. **redis-cli MONITOR 켜놓기** — 별도 터미널에서 명령어 흐름 관찰
4. **헷갈리는 결과는 기록** — 각 자료구조 폴더 `notes.md`에 메모

## 실습 환경

```bash
# Redis 컨테이너 진입
docker exec -it <redis-컨테이너명> redis-cli

# 또는 호스트에서 직접 접속
redis-cli -p 6379

# 별도 터미널 — 실시간 명령어 모니터링
redis-cli -p 6379 MONITOR
```

## 참고

- 전체 로드맵: [REDIS_STUDY_ROADMAP.md](../REDIS_STUDY_ROADMAP.md)
- 프로젝트 가이드: [CLAUDE.md](../CLAUDE.md)
