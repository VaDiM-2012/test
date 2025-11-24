# Dockerfile — лежит в корне проекта
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Копируем всё (включая mvnw и главный pom.xml)
COPY . .

# Используем Maven Wrapper — он уже есть в проекте Практикума
RUN ./mvnw clean package -DskipTests -pl :stats-server --also-make

# Финальный образ
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
# Копируем только нужный jar из модуля stats-server
COPY --from=builder /app/ewm-stats-service/stats-server/target/stats-server-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]