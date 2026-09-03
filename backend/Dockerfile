FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=builder --chown=spring:spring /workspace/build/libs/*.jar app.jar
USER spring:spring
EXPOSE 8013
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
