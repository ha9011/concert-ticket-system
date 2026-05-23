# Chapter 01 — String

> Redis의 가장 기본 자료구조. 단순한 Key-Value 1:1 매핑이지만,
> TTL/원자적 카운터/NX·XX 옵션 등으로 응용 범위가 넓다.

## 학습 파일

| 단계 | 파일 | 내용 | 문항 수 |
|------|------|------|---------|
| 1 | [01-실습.md](01-실습.md) | 명령어 직접 입력 드릴 (난이도 mix) | 70+ |
| 2 | 02-실무.md | 실전 시나리오 미션 (Cache-Aside, 인증 코드, 멱등성, 카운터, 락) | TBD |
| 3 | 03-면접.md | 이론 정리 + 면접 질문 | TBD |

## 다루는 명령어

```
SET / GET / DEL / EXISTS / TYPE
STRLEN / APPEND / GETRANGE / SETRANGE
INCR / DECR / INCRBY / DECRBY / INCRBYFLOAT
EXPIRE / PEXPIRE / EXPIREAT / PERSIST / TTL / PTTL
SETNX / SETEX / PSETEX
SET ... [NX|XX] [EX|PX|EXAT|PXAT|KEEPTTL] [GET]
MSET / MGET / MSETNX
GETSET / GETEX / GETDEL
```

## 진행 방식

1. `01-실습.md` 위에서부터 순서대로 직접 입력
2. 결과가 헷갈리면 `notes.md` (직접 만들기)에 메모
3. 다 풀고 나면 Claude에게 "String 실습 완료" 알리기 → 채점 + 함정 해설
4. `02-실무.md`로 진행
