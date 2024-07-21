FROM eclipse-temurin:21-jre-jammy AS release

COPY ./app/build/libs/google-group-resolver.jar /opt/app/application.jar
COPY --chmod=0755 ./docker-entrypoint.sh /opt/app/docker-entrypoint.sh

# Use root GID for OpenShift compatibility
RUN adduser --system --gid 0 --no-create-home runner

USER runner

WORKDIR /app

RUN mkdir config

ENV LOGLEVEL INFO

ENV JVM_ARGS "-Xms100m -XX:MaxHeapFreeRatio=10 -XX:MinHeapFreeRatio=5 -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000"
ENV EXTRA_JVM_ARGS ""

ENTRYPOINT [ "/opt/app/docker-entrypoint.sh" ]

EXPOSE 8080
