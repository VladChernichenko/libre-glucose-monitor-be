#!/bin/bash

# Test LibreLinkUp Authentication with correct headers

echo "Testing LibreLinkUp Authentication..."
echo "======================================="
echo ""

# Replace with your credentials
EMAIL="vchernichenko13@gmail.com"
PASSWORD="W38#rTL%#wZSxbZ"

# EU endpoint (based on your region: FR)
URL="https://api-eu.libreview.io/llu/auth/login"

echo "Endpoint: $URL"
echo "Email: $EMAIL"
echo ""

# Make request with correct headers
curl -X POST "$URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json" \
  -H "Product: llu.android" \
  -H "Version: 4.12.0" \
  -H "User-Agent: Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/115.0 Firefox/115.0" \
  -H "Accept-Encoding: gzip, deflate, br" \
  -H "Connection: keep-alive" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  | jq '.'

echo ""
echo "======================================="
echo "Check if authTicket.token is present in the response above"

