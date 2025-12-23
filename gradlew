#!/bin/sh

# Gradle Wrapper script for SphereAgent Android

APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
APP_BASE_NAME=$(basename "$0")
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# Java command
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Execute Gradle wrapper
exec "$JAVACMD" \
    -Xmx256m \
    -Dorg.gradle.appname="$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
