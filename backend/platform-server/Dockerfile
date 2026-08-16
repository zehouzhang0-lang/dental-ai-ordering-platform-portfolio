FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="ai-order-platform-backend"
LABEL org.opencontainers.image.description="Phase-one Spring Boot backend for AI order platform"

WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod
ENV SERVER_PORT=8080
# 业务时区。TZ 管 glibc，-Duser.timezone 管 JVM——只设 TZ 的话，
# 镜像若缺 tzdata 会静默回落到 UTC，而这类错误不报错只让日期差一天。
ENV TZ=Asia/Shanghai
# APP_AUTH_TOKEN_SECRET must be injected by compose or the runtime environment.

ARG JAR_FILE=backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=Asia/Shanghai", "-jar", "/app/app.jar"]
