FROM maven:3.9-amazoncorretto-17 AS build
WORKDIR /workspace

COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM amazoncorretto:17-alpine
WORKDIR /app

RUN addgroup -S recsys && adduser -S -G recsys recsys
COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/dependency /app/dependency

# Package app classes into a JAR so the classpath is JAR-only at both archive
# generation time and runtime (CDS requires the same classpath entries for both).
RUN jar cf /app/app-classes.jar -C /app/classes .

# Generate one shared AppCDS archive over the common classpath using the runtime JVM.
# All four services share app-classes+dependency; only the main class differs.
# Step 1: run the gateway briefly to collect the loaded class list.
# Step 2: dump a static shared archive from the class list.
# -Xshare:auto at runtime falls back silently if the archive is incompatible.
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
    && chown recsys:recsys /app/app.jsa /app/app-classes.jar

ENV RECSYS_MAIN_CLASS=com.recsys.api.gateway.MicroserviceGatewayServer
ENV JAVA_OPTS=""

USER recsys
EXPOSE 8010
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -XX:SharedArchiveFile=/app/app.jsa -Xshare:auto -cp /app/app-classes.jar:/app/dependency/* $RECSYS_MAIN_CLASS"]
