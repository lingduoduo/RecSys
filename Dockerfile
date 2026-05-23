FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN addgroup --system recsys && adduser --system --ingroup recsys recsys
COPY --from=build /workspace/target/classes /app/classes
COPY --from=build /workspace/target/dependency /app/dependency

ENV RECSYS_MAIN_CLASS=com.recsys.microservice.MicroserviceGatewayServer
ENV JAVA_OPTS=""

USER recsys
EXPOSE 8010
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp /app/classes:/app/dependency/* $RECSYS_MAIN_CLASS"]
