# CLAUDE.md

이 파일은 Claude Code (claude.ai/code)가 이 저장소에서 작업할 때 참고하는 가이드입니다.

## 빌드 및 실행 명령어

```bash
# 인프라 실행 (MySQL 3307, Redis 6379)
docker-compose up -d

# 전체 모듈 빌드
./gradlew build

# api-server 실행 (포트 8080)
./gradlew :api-server:bootRun

# queue-server 실행 (포트 8081)
./gradlew :queue-server:bootRun

# 전체 테스트 실행
./gradlew test

# 특정 모듈 테스트 실행
./gradlew :api-server:test
./gradlew :queue-server:test

# 단일 테스트 클래스 실행
./gradlew :api-server:test --tests "com.concert.api.controller.AuthControllerTest"
```

## 아키텍처

**Gradle 멀티 모듈 Spring Boot 3.2.1 (Java 17)** 기반 콘서트 티켓 시스템.

### 모듈 구조

- **common-module** — 공유 라이브러리 (JAR, 부트 불가). JPA 엔티티(`User`, `EmailLog`), DTO(`ApiResponse<T>`, `SignupRequest`, `LoginRequest`, `SessionUser`), 예외 처리(`CustomException`, `ErrorCode` enum), Swagger 설정 포함.
- **api-server** — REST API (포트 8080). 인증 엔드포인트(`/api/auth/*`), Spring Session + Redis 기반 세션 관리. common-module 의존.
- **queue-server** — 비동기 이메일 워커 (포트 8081). Redis에서 꺼내 DB에 이메일 로그를 저장하는 멀티스레드 큐 프로세서. common-module 의존.

### Redis 사용 패턴 (3가지)

1. **인증 코드**: `auth:{email}` → 4자리 코드, TTL 3분
2. **이메일 큐**: `mail:queue` → api-server(프로듀서)와 queue-server(컨슈머) 간 FIFO 큐로 사용되는 Redis List
3. **세션 저장소**: `concert:session:{id}` → Spring Session, 30분 타임아웃 (api-server 전용)

### 큐 처리 방식

`EmailMultiWorker`는 디스패처 + 워커 풀 패턴 사용:
- 디스패처 스레드 1개가 `mail:queue`에서 블로킹 `rightPop` 수행
- 워커 스레드 3개가 `ExecutorService`를 통해 이메일을 동시 처리
- 기존 단일 스레드 `EmailWorker`는 존재하지만 비활성화 상태 (`@Component` 없음)

### 예외 처리

`ErrorCode` enum이 HTTP 상태, 코드 문자열, 메시지를 정의. `CustomException`이 `ErrorCode`를 감싸고, `GlobalExceptionHandler`(`@RestControllerAdvice`)가 `ApiResponse` 객체로 변환.

### 데이터

두 서버 모두 동일한 MySQL 데이터베이스(`concert_db`, 포트 3307) 공유. JPA `ddl-auto: update` 사용 — 스키마는 Hibernate가 관리.
