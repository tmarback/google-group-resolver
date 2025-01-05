#!/bin/bash

set -e

exec java -XX:+UseContainerSupport \
    $JVM_ARGS $EXTRA_JVM_ARGS \
    -Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true \
    -server \
    -jar /opt/app/application.jar \
    "$@"