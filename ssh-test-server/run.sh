#!/bin/bash
# Run SSH Test Server for Terminox debugging

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_FILE="$SCRIPT_DIR/build/libs/ssh-test-server-1.0.0.jar"

# Build if JAR doesn't exist
if [ ! -f "$JAR_FILE" ]; then
    echo "Building SSH Test Server..."
    cd "$SCRIPT_DIR" && ./gradlew compileKotlin jar --no-daemon -q
fi

# Run with any passed arguments
java --enable-native-access=ALL-UNNAMED -jar "$JAR_FILE" "$@"
