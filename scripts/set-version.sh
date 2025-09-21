#!/bin/bash

# Script to set version information for the backend build
# This should be run before building the application

# Get git commit hash
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo "unknown")
GIT_SHORT_COMMIT=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")

# Get build number (from CI or timestamp)
if [ -z "$BUILD_NUMBER" ]; then
    BUILD_NUMBER=$(date +%Y%m%d%H%M%S)
fi

# Get build time
BUILD_TIME=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Get branch name
GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")

# Update application.yml with version information
if [ -f "src/main/resources/application.yml" ]; then
    # Use environment variables that Spring Boot can pick up
    export BUILD_NUMBER=$BUILD_NUMBER
    export GIT_COMMIT=$GIT_COMMIT
    export BUILD_TIME=$BUILD_TIME
    
    echo "✅ Backend version information set:"
    echo "   Git Commit: $GIT_SHORT_COMMIT"
    echo "   Build Number: $BUILD_NUMBER"
    echo "   Build Time: $BUILD_TIME"
    echo "   Branch: $GIT_BRANCH"
    echo ""
    echo "🔧 Environment variables exported for Spring Boot"
else
    echo "❌ application.yml not found"
    exit 1
fi
