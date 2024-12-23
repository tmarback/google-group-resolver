#!/bin/bash

set -e

exec java -XX:+UseContainerSupport $JVM_ARGS $EXTRA_JVM_ARGS -server -jar /opt/app/application.jar "$@"