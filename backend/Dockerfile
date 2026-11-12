# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
COPY --from=build /workspace/target/*.jar /app/feilv-server.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/feilv-server.jar"]
