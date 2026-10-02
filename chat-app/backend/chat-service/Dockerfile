# Build-Kontext ist das Repository-Root (siehe docker-compose.yml:
# context: .., dockerfile: backend/chat-service/Dockerfile) - nicht nur
# dieser Service-Ordner. Grund: chat-service haengt per <parent> mit
# relativePath an der Root-pom.xml (echtes Maven-Elternprojekt, siehe
# PLANUNG.md 5); ohne sie im Build-Kontext wuerde Maven versuchen, das
# Elternprojekt aus Maven Central zu laden, wo es nicht existiert.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY backend/chat-service/pom.xml backend/chat-service/pom.xml
COPY backend/batch-writer/pom.xml backend/batch-writer/pom.xml
COPY backend/chat-service/src backend/chat-service/src
RUN mvn -q -pl backend/chat-service -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend/chat-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
