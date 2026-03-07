# gaetteok-backend

Spring Boot 4 + WebFlux + Kotlin 백엔드입니다.
현재 저장소에서는 추후 별도 Repository로 분리할 전제의 임시 작업 디렉터리로 관리합니다.

## 현재 상태

- 프론트 계약에 맞춘 HTTP 컨트롤러 포함
- raw WebSocket(`/ws/rooms`) handler 포함
- domain/application/infrastructure 분리 기준으로 정리
- domain model 중심 application service 구현 포함
- PostgreSQL + Spring Data JPA 영속 어댑터 포함
- local cache 기반 realtime connection registry 포함
- 상태 머신 포함:
  - `LOBBY -> ROUND_START -> DRAWING -> ROUND_END -> GAME_FINISHED`
- 점수 계산, 힌트 공개, 관전자 승인 후 다음 턴 편입 포함
- `commandId` 기반 기본 멱등성 포함

## 아직 없는 것

- 분산 락 / 멀티 인스턴스 대응
- 외부 cache store adapter 구현

## 이 디렉터리를 분리 리포지토리로 옮길 때

1. `backend-spring/` 디렉터리만 새 Repository로 이동
2. 루트 기준 문서 링크를 새 Repository 구조에 맞게 수정
3. Gradle wrapper 추가
4. CI와 테스트 파이프라인 구성
5. realtime gateway 구현 또는 별도 gateway Repository 구성

## 주요 파일

- `src/main/kotlin/com/gaetteok/backend/application/game/*`
- `src/main/kotlin/com/gaetteok/backend/domain/game/*`
- `src/main/kotlin/com/gaetteok/backend/api/controller/*`
- `src/main/kotlin/com/gaetteok/backend/config/*`
- `src/main/kotlin/com/gaetteok/backend/api/dto/ApiDtos.kt`
- `src/main/kotlin/com/gaetteok/backend/infrastructure/persistence/*`
- `src/main/resources/application-local.yml`
- `src/main/resources/application-prd.yml`
- `src/main/resources/db/schema.sql`
- `src/main/kotlin/com/gaetteok/backend/realtime/*`

## 실행 전제

- Java 21
- Gradle 또는 Gradle wrapper

## 로컬 실행

```bash
docker compose -f ../compose.yml up --build
```

브라우저 접속:

- FE: `http://localhost:3000`
- BE: `http://localhost:8080`
- PostgreSQL: `localhost:5432`

## 백엔드만 단독 실행

```bash
export SPRING_PROFILES_ACTIVE=local
export GAETTEOK_DB_URL=jdbc:postgresql://127.0.0.1:5432/gaetteok
export GAETTEOK_DB_USERNAME=gaetteok
export GAETTEOK_DB_PASSWORD=gaetteok
./gradlew bootRun
```

- 기본 포트: `8080`
- 기본 영속 저장소: `PostgreSQL`
- 기본 프로파일: `local`

## Spring Profile

- `local`
  - 로컬 PostgreSQL 기본값 사용
  - `spring.jpa.hibernate.ddl-auto=update`
  - Compose와 로컬 개발 기본 프로파일
- `prd`
  - DB 접속 정보는 환경 변수 필수
  - `spring.jpa.hibernate.ddl-auto=validate`
  - 운영 DB 스키마는 `src/main/resources/db/schema.sql` 기준으로 사전 관리 전제

## Persistence 원칙

- 영속 저장소는 `PostgreSQL + Spring Data JPA`
- domain model과 JPA entity는 분리 유지
- 기준 스키마 SQL은 `src/main/resources/db/schema.sql`에 유지
- Redis가 필요한 영역은 현재 `RealtimeConnectionCache` 인터페이스 뒤의 local cache로 유지
- 이후 외부 cache store로 교체할 때 application/domain 계층 변경 없이 adapter만 교체

## 참고 문서

- `../docs/realtime-contract.md`
- `../docs/springboot-backend-blueprint.md`
- `../docs/springboot-migration.md`
