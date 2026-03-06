# gaetteok-backend

Spring Boot + Kotlin 백엔드 초안입니다.
현재 저장소에서는 추후 별도 Repository로 분리할 전제의 임시 작업 디렉터리로 관리합니다.

## 현재 상태

- 프론트 계약에 맞춘 HTTP 컨트롤러 포함
- in-memory `GameFacade` 구현 포함
- 상태 머신 포함:
  - `LOBBY -> ROUND_START -> DRAWING -> ROUND_END -> GAME_FINISHED`
- 점수 계산, 힌트 공개, 관전자 승인 후 다음 턴 편입 포함
- `commandId` 기반 기본 멱등성 포함
- realtime gateway는 아직 인터페이스만 존재

## 아직 없는 것

- 영속 저장소
- 분산 락 / 멀티 인스턴스 대응
- Socket.IO 호환 gateway 실제 구현
- Spring WebSocket/STOMP 직접 구현
- Gradle wrapper

## 이 디렉터리를 분리 리포지토리로 옮길 때

1. `backend-spring/` 디렉터리만 새 Repository로 이동
2. 루트 기준 문서 링크를 새 Repository 구조에 맞게 수정
3. Gradle wrapper 추가
4. CI와 테스트 파이프라인 구성
5. realtime gateway 구현 또는 별도 gateway Repository 구성

## 주요 파일

- `src/main/kotlin/com/gaetteok/backend/api/controller/*`
- `src/main/kotlin/com/gaetteok/backend/api/dto/ApiDtos.kt`
- `src/main/kotlin/com/gaetteok/backend/game/model/GameModels.kt`
- `src/main/kotlin/com/gaetteok/backend/game/service/InMemoryGameFacade.kt`
- `src/main/kotlin/com/gaetteok/backend/realtime/RealtimeGateway.kt`

## 실행 전제

- Java 21
- Gradle 또는 Gradle wrapper

## 참고 문서

- `../docs/realtime-contract.md`
- `../docs/springboot-backend-blueprint.md`
- `../docs/springboot-migration.md`
