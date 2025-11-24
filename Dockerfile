# Dockerfile — в корне проекта
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app
COPY . .

# Сборка через Maven (с fallback на apt, если mvnw нет)
RUN if [ -f mvnw ]; then \
        chmod +x mvnw && ./mvnw clean package -DskipTests -pl :stats-server --also-make; \
    else \
        apt-get update && apt-get install -y maven && \
        mvn clean package -DskipTests -pl :stats-server --also-make; \
    fi

# Финальный образ
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Копируем ТОЛЬКО исполняемый jar (без .original в имени)
COPY --from=builder \
    /app/ewm-stats-service/stats-server/target/stats-server-0.0.1-SNAPSHOT.jar \
    /app/app.jar

EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]