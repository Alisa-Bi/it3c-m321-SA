# Build-Stage: kompiliert die Anwendung mit Maven, Ergebnis ist eine ausfuehrbare JAR-Datei.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q clean package -DskipTests

# Laufzeit-Stage: enthaelt nur das JRE und die fertige JAR-Datei, kein Build-Werkzeug.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
