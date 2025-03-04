FROM eclipse-temurin:21-jre-jammy AS release

COPY --chown=root:root --chmod=0644 ./app/build/libs/google-group-resolver.jar /opt/app/application.jar
COPY --chown=root:root --chmod=0755 ./docker-entrypoint.sh /opt/app/docker-entrypoint.sh

# Use root GID for OpenShift compatibility
RUN adduser --system --gid 0 --no-create-home runner

USER runner

WORKDIR /app

RUN mkdir config

ENV LOGLEVEL=INFO

ENV JVM_ARGS="-XX:InitialRAMPercentage=10.0 -XX:MinRAMPercentage=50.0 -XX:MaxRAMPercentage=70.0 -XX:+UseG1GC"
ENV EXTRA_JVM_ARGS=""

ENTRYPOINT [ "/opt/app/docker-entrypoint.sh" ]

EXPOSE 8080
