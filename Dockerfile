FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Build and install common to the local Maven repo inside the container
COPY common/pom.xml ./common/pom.xml
COPY common/src ./common/src
RUN mvn -f common/pom.xml install -DskipTests -B

# Resolve auth-service dependencies (cached layer unless pom.xml changes)
COPY auth-service/pom.xml ./auth-service/pom.xml
RUN mvn -f auth-service/pom.xml dependency:go-offline -B

# Build auth-service
COPY auth-service/src ./auth-service/src
RUN mvn -f auth-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /app/auth-service/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
