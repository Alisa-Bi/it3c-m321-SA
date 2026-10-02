# Build-Kontext ist das Repository-Root (siehe docker-compose.yml:
# context: .., dockerfile: backend/batch-writer/Dockerfile) - aus
# demselben Grund wie bei chat-service: das Eltern-POM muss im
# Build-Kontext verfuegbar sein.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY backend/batch-writer/pom.xml backend/batch-writer/pom.xml
COPY backend/chat-service/pom.xml backend/chat-service/pom.xml
COPY backend/batch-writer/src backend/batch-writer/src
RUN mvn -q -pl backend/batch-writer -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend/batch-writer/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
