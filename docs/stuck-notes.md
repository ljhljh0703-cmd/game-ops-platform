# 만들면서 막힌 곳

## 1. 같은 버그 제보 8건이 동시에 들어오면 일부가 실패했다
- 증상: 동시 접수 테스트에서 `ObjectOptimisticLockingFailureException`. 8건이 같은 이슈의 제보 수를 동시에 올리면서 version 충돌이 났고, 재시도 3회로는 모자랐다.
- 해결: 붙일 이슈를 찾은 뒤 그 행을 `PESSIMISTIC_WRITE` 로 잠가 차례로 줄 세웠다. 낙관적 잠금(version)은 상태 변경과의 충돌을 잡는 용도로 남겼다.
- 배운 것: 경합이 드문 곳은 낙관적 잠금, 한 행에 쓰기가 몰리는 곳(인기 이슈 카운터)은 비관적 잠금.

## 2. 잠금 쿼리를 따로 만들었는데도 같은 에러가 났다
- 증상: `select … for update` 를 하는 리포지토리 메서드를 추가했지만 여전히 version 충돌.
- 원인: 같은 트랜잭션에서 이미 그 이슈를 읽어 영속성 컨텍스트(1차 캐시)에 들고 있었다. 잠금 쿼리는 DB에 나갔지만 Hibernate는 캐시에 있던 옛 객체를 돌려줬고, 옛 version으로 저장하다 충돌했다.
- 해결: `EntityManager.refresh(entity, PESSIMISTIC_WRITE)` 로 잠그면서 DB 최신 값으로 다시 읽었다. 잠근 뒤 이슈가 그사이 닫혔으면 붙이지 않고 새 이슈를 만든다.

## 3. 테스트용 PostgreSQL 컨테이너가 안 떴다
- 증상: `Could not find a valid Docker environment`. 로그를 보니 `client version 1.32 is too old. Minimum supported API version is 1.40`.
- 원인: Docker 29부터 오래된 API 버전을 거부하는데, 테스트 컨테이너가 쓰는 Docker 클라이언트가 기본으로 낮은 버전을 보냈다.
- 해결: `src/test/resources/docker-java.properties` 에 `api.version=1.44`.

## 4. (작업 환경 한정) 이미지 빌드 단계에서 의존성 내려받기 실패
- 증상: 이미지 안 Gradle이 `PKIX path building failed`.
- 원인: 개발에 쓴 클라우드 작업 공간이 사내 프록시 인증서를 쓰는데, 빌드 컨테이너에는 그 인증서가 없다. 일반 개발 PC에서는 생기지 않는다.
- 처리: 이 환경에서는 jar를 밖에서 빌드해 실행 단계 이미지만 검증했다. Dockerfile 자체는 그대로 둔다.
