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

ENV RECSYS_MAIN_CLASS=com.recsys.api.gateway.MicroserviceGatewayServer
ENV JAVA_OPTS=""

USER recsys
EXPOSE 8010
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp /app/classes:/app/dependency/* $RECSYS_MAIN_CLASS"]
