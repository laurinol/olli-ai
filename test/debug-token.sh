#!/bin/bash
# Debug script for directly testing registration with a token

# Configuration
BASE_URL="http://localhost:8080"
TEST_EMAIL="test@example.com"
TEST_PASSWORD="password123"
TEST_BUSINESS_ID="test-business-$(date +%s)" # Unique ID based on timestamp

# Step 1: Create an invite
echo "Creating invite for $TEST_EMAIL in business $TEST_BUSINESS_ID"
curl -s -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"${TEST_EMAIL}\", \"businessId\":\"${TEST_BUSINESS_ID}\"}" \
  ${BASE_URL}/auth/invite

# Step 2: Get the invite token directly
echo -e "\n\nRetrieving the token"
TOKEN=$(curl -s ${BASE_URL}/test/get-latest-invite?email=${TEST_EMAIL})
echo "Raw token: $TOKEN"

# Step 3: Register with the token
echo -e "\n\nRegistering with token: $TOKEN"
curl -v -s -X POST -H "Content-Type: application/json" \
  -d "{\"email\":\"${TEST_EMAIL}\", \"password\":\"${TEST_PASSWORD}\", \"token\":\"${TOKEN}\"}" \
  ${BASE_URL}/auth/register

echo -e "\n\nDone!"