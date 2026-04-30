#!/bin/sh
# Gradle Wrapper Script

DIRNAME="$(dirname "$(readlink -f "$0")")"
APP_NAME="Gradle"
APP_BASE_NAME="$(basename "$0")"
DEFAULT_JVM_OPTS='"-Xmx2048m" "-Dfile.encoding=UTF-8"'

# Find java
if [ -n "$JAVA_HOME" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
else
    JAVA_EXE="java"
fi

if [ ! -x "$JAVA_EXE" ]; then
    echo "ERROR: Java not found. Set JAVA_HOME."
    exit 1
fi

# Gradle distribution
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_VERSION="8.7"
GRADLE_DIST="https\://services.gradle.org/distributions/gradle-8.7-bin.zip"
GRADLE_HOME="$GRADLE_USER_HOME/wrapper/dists/gradle-8.7-bin/$GRADLE_VERSION"

# Download Gradle if not present
if [ ! -d "$GRADLE_HOME" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    mkdir -p "$GRADLE_USER_HOME/wrapper/dists/gradle-8.7-bin"
    ZIP_FILE="$GRADLE_USER_HOME/wrapper/dists/gradle-8.7-bin/gradle-8.7-bin.zip"
    if [ ! -f "$ZIP_FILE" ]; then
        curl -L -o "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-8.7-bin.zip" 2>/dev/null
    fi
    echo "Extracting..."
    unzip -q "$ZIP_FILE" -d "$GRADLE_USER_HOME/wrapper/dists/gradle-8.7-bin/"
    mv "$GRADLE_USER_HOME/wrapper/dists/gradle-8.7-bin/gradle-8.7" "$GRADLE_HOME" 2>/dev/null || true
fi

# Find gradle jar
GRADLE_JAR=$(find "$GRADLE_HOME" -name "gradle-launcher-*.jar" 2>/dev/null | head -1)
if [ -z "$GRADLE_JAR" ]; then
    # Direct use
    GRADLE_BIN="$GRADLE_HOME/bin/gradle"
    if [ -x "$GRADLE_BIN" ]; then
        exec "$GRADLE_BIN" "$@"
    fi
    echo "ERROR: Gradle not found"
    exit 1
fi

eval set -- $DEFAULT_JVM_OPTS -classpath "$GRADLE_JAR" org.gradle.launcher.GradleMain "$@"
exec "$JAVA_EXE" "$@"
