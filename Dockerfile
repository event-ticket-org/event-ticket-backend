# syntax=docker/dockerfile:1

# The jar is built here rather than copied in, so the image is a function of the commit and
# not of whatever was last built on somebody's laptop.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY contracts/ contracts/
COPY src/ src/

# No tests. They run against real Postgres and real MinIO through Testcontainers, so running
# them here would mean a Docker daemon inside a Docker build; CI runs the suite on every pull
# request, which is the right place for a failing test to stop things.
#
# The cache mount keeps Maven Central out of every rebuild without the usual trick of copying
# the POM alone and running `dependency:go-offline` first - which resolves plugins
# incompletely and fails in ways that read like a broken build.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests package && cp target/*.jar application.jar

# Unpacked into layers, so a code change rebuilds the application layer rather than two
# hundred megabytes of unchanged dependencies.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /application

# curl is here for one reason: the container's healthcheck reads /actuator/health, and
# `depends_on: service_healthy` is only worth anything if the check can tell an open port from
# an application that has finished starting. A JRE image has no HTTP client on the command
# line.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Nothing here needs root, and a process that writes nothing outside /tmp has no reason to be
# able to.
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

COPY --from=build --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/application/ ./

USER spring
EXPOSE 8080

# No active profile, deliberately. The development credentials live in the dev profile and
# this image does not have it, so a container started without JWT_SECRET, TICKET_CODE_KEY and
# the rest refuses to boot rather than running on secrets published in this repository.
ENTRYPOINT ["java", "-jar", "application.jar"]
