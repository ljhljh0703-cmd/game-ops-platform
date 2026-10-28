# 게임 운영 플랫폼 서버 이미지 — 빌드 단계와 실행 단계를 나눠 실행 이미지를 작게 둔다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 내려받은 라이브러리는 캐시에 남겨 다음 빌드에서 다시 받지 않는다. 인터넷이 잠깐 끊기면 3번까지 다시 시도
ENV GRADLE_OPTS="-Dorg.gradle.internal.http.connectionTimeout=60000 -Dorg.gradle.internal.http.socketTimeout=60000 -Dorg.gradle.internal.repository.max.retries=5"
RUN --mount=type=cache,target=/root/.gradle \
    for i in 1 2 3; do ./gradlew --no-daemon dependencies > /dev/null && break; echo "retry $i"; sleep 10; done
COPY src src
# 규칙 관문용 평가 묶음(build.gradle processResources 가 서버에 함께 묶음)
COPY eval eval
RUN --mount=type=cache,target=/root/.gradle \
    for i in 1 2 3; do ./gradlew --no-daemon bootJar -x test && exit 0; echo "retry $i"; sleep 15; done; exit 1

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
