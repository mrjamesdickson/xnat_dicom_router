#!/bin/bash
#
# XNAT Batch Anonymizer - Wrapper Script
#
# This script wraps the Java application for easy use.
#
# Usage:
#   ./batch-anonymizer.sh receiver --all          # Start all DICOM receivers
#   ./batch-anonymizer.sh anonymize -i <dir> ...  # Anonymize files
#   ./batch-anonymizer.sh upload -f <zip> ...     # Upload to XNAT
#   ./batch-anonymizer.sh status                  # Show status
#   ./batch-anonymizer.sh log [--summary]         # Show upload log
#

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="$SCRIPT_DIR/build/libs/batch-anonymizer-1.0.0.jar"
CONFIG_FILE="${CONFIG_FILE:-$SCRIPT_DIR/../config.yaml}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check if JAR exists
if [ ! -f "$JAR_FILE" ]; then
    echo -e "${YELLOW}JAR file not found. Building...${NC}"
    cd "$SCRIPT_DIR"

    if command -v gradle >/dev/null 2>&1; then
        gradle shadowJar
    elif [ -f "./gradlew" ]; then
        ./gradlew shadowJar
    else
        echo -e "${RED}Error: gradle not found. Please install gradle or use the gradlew wrapper.${NC}"
        exit 1
    fi

    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${RED}Error: Build failed. JAR file not created.${NC}"
        exit 1
    fi

    echo -e "${GREEN}Build complete.${NC}"
fi

# Find Java 11+
find_java() {
    # Check JAVA_HOME first
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        return
    fi

    # Try common Java 11+ locations
    for java_path in \
        "/usr/local/opt/openjdk@11/bin/java" \
        "/usr/local/opt/openjdk/bin/java" \
        "/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home/bin/java" \
        "/Library/Java/JavaVirtualMachines/adoptopenjdk-11.jdk/Contents/Home/bin/java" \
        "$HOME/Library/Java/JavaVirtualMachines/corretto-11*/Contents/Home/bin/java" \
        "$HOME/Library/Java/JavaVirtualMachines/corretto-17*/Contents/Home/bin/java" \
        "$HOME/Library/Java/JavaVirtualMachines/corretto-18*/Contents/Home/bin/java" \
        "/usr/lib/jvm/java-11-openjdk/bin/java" \
        "/usr/lib/jvm/java-17-openjdk/bin/java"
    do
        # Handle glob patterns
        for expanded in $java_path; do
            if [ -x "$expanded" ]; then
                echo "$expanded"
                return
            fi
        done
    done

    # Fall back to system java
    if command -v java >/dev/null 2>&1; then
        echo "java"
    fi
}

JAVA_CMD=$(find_java)

if [ -z "$JAVA_CMD" ]; then
    echo -e "${RED}Error: Java not found. Please install Java 11 or later.${NC}"
    exit 1
fi

JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" = "1" ]; then
    # Old version format (1.8)
    JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f2)
fi

if [ "$JAVA_VERSION" -lt 11 ] 2>/dev/null; then
    echo -e "${RED}Error: Java 11+ required. Found version $JAVA_VERSION${NC}"
    echo "Please install Java 11 or set JAVA_HOME to a Java 11+ installation."
    exit 1
fi

echo -e "${GREEN}Using Java $JAVA_VERSION${NC}"

# Set memory options
JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"

# Run the application
exec "$JAVA_CMD" $JAVA_OPTS -jar "$JAR_FILE" -c "$CONFIG_FILE" "$@"
