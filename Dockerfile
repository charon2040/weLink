FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN apk add --no-cache wget && addgroup --system welink && adduser --system --ingroup welink welink

COPY target/WeLink-0.0.1-SNAPSHOT.jar app.jar

USER welink

EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=60s \
    CMD wget -qO- http://127.0.0.1:${SERVER_PORT}/actuator/health || exit 1

ENV SPRING_PROFILES_ACTIVE=sharding
ENV SERVER_PORT=8080
ENV WELINK_WEBSOCKET_PORT=8081
ENV WELINK_INSTANCE_ID=instance-1
ENV WELINK_REDIS_HOST=redis-cluster
ENV WELINK_REDIS_PORT=6379
ENV JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE} --server.port=${SERVER_PORT} --welink.websocket.port=${WELINK_WEBSOCKET_PORT} --welink.instance.id=${WELINK_INSTANCE_ID}"]
