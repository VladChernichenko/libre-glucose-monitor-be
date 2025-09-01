#!/bin/bash

echo "Testing Authentication Endpoints"
echo "================================="

# Test the auth test endpoint
echo "1. Testing auth test endpoint..."
curl -X GET http://localhost:8080/api/auth/test

echo -e "\n\n2. Testing user registration..."
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "fullName": "Test User",
    "password": "password123"
  }'

echo -e "\n\n3. Testing user login..."
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "password123"
  }'

echo -e "\n\nTest completed!"

