#!/bin/bash

echo "🐳 Starting Glucose Monitor Application with Docker..."

# Stop any existing containers
echo "🛑 Stopping existing containers..."
docker-compose down

# Remove any existing images to ensure fresh build
echo "🗑️  Removing existing images..."
docker-compose down --rmi all

# Build and start the services
echo "🔨 Building and starting services..."
docker-compose up --build

echo "✅ Application should be running at http://localhost:8080"
echo "📊 PostgreSQL is available at localhost:5433"
