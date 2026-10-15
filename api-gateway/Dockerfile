# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS compilacion
WORKDIR /compilacion

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Evita problemas si Windows dejó el Maven Wrapper con finales de línea CRLF.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw

COPY src/ src/
# La imagen se construye sin compilar ni ejecutar pruebas; se verifican por separado.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS ejecucion
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 despacho \
    && useradd --uid 10001 --gid despacho --no-create-home --shell /usr/sbin/nologin despacho

COPY --from=compilacion --chown=10001:10001 /compilacion/target/api-gateway-*.jar /app/aplicacion.jar

ENV PORT=8080
USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${PORT}/actuator/health" > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/aplicacion.jar"]
