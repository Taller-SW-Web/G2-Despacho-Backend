# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS compilacion
WORKDIR /compilacion
COPY src/EmisorSeguridadLocal.java ./
RUN javac --add-modules jdk.httpserver -encoding UTF-8 -d clases EmisorSeguridadLocal.java

# Esta etapa solo se ejecuta si se solicita --target pruebas.
FROM compilacion AS pruebas
COPY pruebas/EmisorSeguridadLocalPruebas.java ./
RUN javac --add-modules jdk.httpserver -encoding UTF-8 -cp clases -d clases EmisorSeguridadLocalPruebas.java \
    && java --add-modules jdk.httpserver -cp clases EmisorSeguridadLocalPruebas

FROM eclipse-temurin:21-jre-jammy AS ejecucion
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 despacho \
    && useradd --uid 10001 --gid despacho --no-create-home --shell /usr/sbin/nologin despacho
COPY --from=compilacion --chown=10001:10001 /compilacion/clases/ /app/clases/
ENV PORT=8090 HABILITAR_SEGURIDAD_LOCAL=false
USER 10001:10001
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
    CMD curl --fail --silent --max-time 4 "http://127.0.0.1:${PORT}/actuator/health" > /dev/null || exit 1
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "-cp", "/app/clases", "EmisorSeguridadLocal"]
