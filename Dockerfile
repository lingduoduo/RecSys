FROM maven:3.9-amazoncorretto-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
RUN jar cf /workspace/app-classes.jar -C /workspace/target/classes .

# Build a minimal glibc JRE with jlink (this stage is glibc, matching the runtime base).
# Detect modules from the real classpath, then union a safety set for reflectively-loaded
# modules jdeps cannot see (crypto, JNDI, JDBC, management, instrumentation, locales) that
# Spring and ONNX Runtime rely on.
# binutils provides objcopy, which jlink's --strip-debug shells out to (not in the base image).
RUN yum install -y binutils >/dev/null 2>&1
RUN set -eux; \
    MODS="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 17 \
              -cp '/workspace/target/dependency/*' --recursive /workspace/app-classes.jar 2>/dev/null || echo java.base)"; \
    MODS="${MODS},jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,java.naming,java.management,java.sql,jdk.zipfs,java.security.jgss,java.instrument,jdk.jfr,jdk.localedata"; \
    jlink --add-modules "$MODS" --strip-debug --no-man-pages --no-header-files \
          --compress=2 --output /opt/jre; \
    /opt/jre/bin/java -version

FROM debian:12-slim
WORKDIR /app

# glibc C++/OpenMP runtime libs that ONNX Runtime's native .so needs (libc6/libgcc-s1 are
# already in the base; libstdc++6 + libgomp1 are not). This is what fixes the ONNX crash.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libstdc++6 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r recsys && useradd -r -g recsys recsys

COPY --from=build /opt/jre /opt/jre
ENV PATH="/opt/jre/bin:${PATH}"

COPY --from=build /workspace/app-classes.jar /app/app-classes.jar
COPY --from=build /workspace/target/dependency /app/dependency

# Regenerate the shared AppCDS archive with the glibc jlink JVM (the musl Phase-2 archive
# would be rejected cross-build). -Xshare:auto at runtime falls back silently if incompatible.
RUN (timeout -s TERM 40 java \
         -XX:DumpLoadedClassList=/app/app.classlist \
         -cp '/app/app-classes.jar:/app/dependency/*' com.recsys.api.gateway.MicroserviceGatewayServer \
         || true) \
    && test -s /app/app.classlist \
    && java -Xshare:dump \
         -XX:SharedClassListFile=/app/app.classlist \
         -XX:SharedArchiveFile=/app/app.jsa \
         -cp '/app/app-classes.jar:/app/dependency/*' \
    && rm -f /app/app.classlist \
    && test -s /app/app.jsa \
    && chown recsys:recsys /app/app.jsa

ENV RECSYS_MAIN_CLASS=com.recsys.api.gateway.MicroserviceGatewayServer
ENV JAVA_OPTS=""

USER recsys
EXPOSE 8010
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=/app/app.jsa -Xshare:auto -cp /app/app-classes.jar:/app/dependency/* $RECSYS_MAIN_CLASS"]
