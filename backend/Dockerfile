# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Camada de dependências: só reexecuta quando o pom muda.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuário sem privilégios: nada aqui precisa de root.
RUN addgroup -S techjobs && adduser -S techjobs -G techjobs
COPY --from=build /build/target/*.jar app.jar
USER techjobs

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Readiness e não health geral: o que interessa aqui é "consegue atender requisição",
# o que inclui ter banco. O start-period cobre migrations em base nova.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
