#!/bin/bash
# Test script for MD Anderson STS Authentication
# Usage: ./test-mda-auth.sh <username> <password> <appName> <appKey>

STS_HOST="sts.mdanderson.edu"
STS_URL="https://${STS_HOST}/token"

USERNAME="${1:-your_username}"
PASSWORD="${2:-your_password}"
APP_NAME="${3:-your_app_name}"
APP_KEY="${4:-your_app_key}"

echo "=== MD Anderson STS Authentication Test ==="
echo "URL: $STS_URL"
echo "Username: $USERNAME"
echo "AppName: $APP_NAME"
echo "AppKey: $APP_KEY"
echo ""

# Test 1: JSON format (what we're currently using)
echo "--- Test 1: JSON format ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$STS_URL" \
  -H "Content-Type: application/json" \
  -d "{\"UserName\":\"$USERNAME\",\"AppName\":\"$APP_NAME\",\"AppKey\":\"$APP_KEY\",\"Password\":\"$PASSWORD\"}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "SUCCESS! Token received:"
  echo "$BODY" | head -c 100
  echo "..."
else
  echo "FAILED! Response body:"
  echo "$BODY"
fi
echo ""

# Test 2: Form-urlencoded format (OAuth2 style)
echo "--- Test 2: Form-urlencoded (OAuth2) format ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$STS_URL" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=$USERNAME&password=$PASSWORD&client_id=$APP_NAME&client_secret=$APP_KEY")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "SUCCESS! Token received:"
  echo "$BODY" | head -c 100
  echo "..."
else
  echo "FAILED! Response body:"
  echo "$BODY"
fi
echo ""

# Test 3: Try lowercase field names
echo "--- Test 3: JSON with lowercase field names ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$STS_URL" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USERNAME\",\"appName\":\"$APP_NAME\",\"appKey\":\"$APP_KEY\",\"password\":\"$PASSWORD\"}")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)

echo "HTTP Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "200" ]; then
  echo "SUCCESS! Token received:"
  echo "$BODY" | head -c 100
  echo "..."
else
  echo "FAILED! Response body:"
  echo "$BODY"
fi
echo ""

echo "=== Test Complete ==="
echo ""
echo "If all tests failed, check with MDA for:"
echo "  1. Expected request format (JSON vs form-urlencoded)"
echo "  2. Required field names (case-sensitive)"
echo "  3. Whether AppKey should be in header or body"
echo "  4. Whether additional fields are required (grant_type, scope, etc.)"
