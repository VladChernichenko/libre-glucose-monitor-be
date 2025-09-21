#!/bin/bash

# Render build script for backend - sets version information during deployment

echo "🚀 Starting backend Render build with version information..."

# Set version information
./scripts/set-version.sh

# Build the application with Gradle
echo "🔨 Building backend with Gradle..."
./gradlew build

echo "✅ Backend Render build completed successfully!"
