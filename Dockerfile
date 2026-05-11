# ── Этап 1: сборка ──────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build
COPY pom.xml .
# Скачиваем зависимости отдельным слоем — кешируется пока pom.xml не изменился
RUN mvn dependency:go-offline -Dmaven.test.skip=true

COPY src ./src
RUN mvn package -DskipTests

# ── Этап 2: образ для запуска ────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /build/target/alert-agent-*.jar app.jar

ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
