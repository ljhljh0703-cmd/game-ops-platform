# 게임 운영 플랫폼 서버 이미지 — 빌드 단계와 실행 단계를 나눠 실행 이미지를 작게 둔다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# 규칙 관문용 평가 묶음(build.gradle processResources 가 서버에 함께 묶음)
COPY eval eval
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
