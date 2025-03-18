#!/bin/bash
# Direct test of database operations

# Set MongoDB as the database provider
export DATABASE_PROVIDER=mongodb
export MONGODB_CONNECTION_STRING=mongodb://admin:password@localhost:27017
export MONGODB_DATABASE=olli

# Configuration
BASE_URL="http://localhost:8080"
TEST_EMAIL="test-db-ops@example.com"
TEST_PASSWORD="password123"
TEST_BUSINESS_ID="test-business-$(date +%s)" # Unique ID based on timestamp

# Test steps to create entities
echo "1. Creating invite..."
invite_result=$(curl -v -s -X POST -H "Content-Type: application/json" -d "{\"email\":\"${TEST_EMAIL}\", \"businessId\":\"${TEST_BUSINESS_ID}\"}" ${BASE_URL}/auth/invite)
echo "Result: $invite_result"

# Wait for processing
sleep 1

# Get invite token
echo "2. Getting invite token..."
token_result=$(curl -s "${BASE_URL}/test-integration/get-latest-invite?email=${TEST_EMAIL}")
invite_token=$(echo "$token_result" | tr -d '\n')
echo "Got invite token: $invite_token"

# Register user
echo "3. Registering user..."
register_result=$(curl -v -X POST -H "Content-Type: application/json" -d "{\"email\":\"${TEST_EMAIL}\", \"password\":\"${TEST_PASSWORD}\", \"token\":\"${invite_token}\"}" ${BASE_URL}/auth/register)
echo "Result: $register_result"

# Extract auth token if available
auth_token=$(echo "$register_result" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"\([^"]*\)"/\1/')
if [ -z "$auth_token" ]; then
  auth_token="mock-token-$(date +%s)"
  echo "Using mock token: $auth_token"
else
  echo "Got auth token: $auth_token"
fi

# Wait for processing
sleep 2

# Check DB content
echo "4. Checking database state..."
db_result=$(curl -s "${BASE_URL}/test-integration/debug/collections")
echo "$db_result"

# Do a more detailed check of the MongoDB invites collection for this specific invite
echo "5. Checking specific invite in the database..."
curl -s -X POST "${BASE_URL}/test-integration/check-invite?token=${invite_token}" | grep claimed

echo "Done."