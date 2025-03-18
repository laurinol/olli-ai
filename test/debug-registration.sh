#!/bin/bash
# Debug script for testing the registration flow

# Set MongoDB as the database provider
export DATABASE_PROVIDER=mongodb
export MONGODB_CONNECTION_STRING=mongodb://admin:password@localhost:27017
export MONGODB_DATABASE=olli

# Configuration
BASE_URL="http://localhost:8080"
TEST_EMAIL="test@example.com"
TEST_PASSWORD="password123"
TEST_BUSINESS_ID="test-business-$(date +%s)" # Unique ID based on timestamp

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper function for section headers
section() {
  echo -e "\n${BLUE}============================================================"
  echo "   $1"
  echo -e "============================================================${NC}\n"
}

# Helper function to format JSON or display raw if jq is not available
format_json() {
  if command -v jq &> /dev/null; then
    echo "$1" | jq '.'
  else
    echo "$1"
  fi
}

# Begin debug
clear
section "OLLI AI REGISTRATION DEBUGGING"

# Step 1: Check MongoDB collections
section "1. CHECKING MONGODB COLLECTIONS"
collections=$(curl -s "${BASE_URL}/test-integration/debug/collections")
echo -e "${GREEN}Collections data:${NC}"
format_json "$collections"

# Step 2: Create a new invite
section "2. CREATING NEW INVITE"
invite_result=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"email\":\"${TEST_EMAIL}\", \"businessId\":\"${TEST_BUSINESS_ID}\"}" ${BASE_URL}/auth/invite)
echo -e "${GREEN}Invite creation result:${NC}"
format_json "$invite_result"

# Step 3: Get the invite token
section "3. RETRIEVING INVITE TOKEN"
sleep 1
token_result=$(curl -s "${BASE_URL}/test-integration/get-latest-invite?email=${TEST_EMAIL}")
invite_token=$(echo "$token_result" | tr -d '\n')
echo -e "${GREEN}Invite token:${NC} $invite_token"

# Step 4: Register with the token
section "4. REGISTERING USER"
register_result=$(curl -s -X POST -H "Content-Type: application/json" -d "{\"email\":\"${TEST_EMAIL}\", \"password\":\"${TEST_PASSWORD}\", \"token\":\"${invite_token}\"}" ${BASE_URL}/auth/register)
echo -e "${GREEN}Registration result:${NC}"
format_json "$register_result"

# Step 5: Check MongoDB collections again
section "5. CHECKING MONGODB COLLECTIONS AFTER REGISTRATION"
sleep 1
collections=$(curl -s "${BASE_URL}/test-integration/debug/collections")
echo -e "${GREEN}Collections data:${NC}"
format_json "$collections"

# Step 6: List users
section "6. LISTING USERS"
users=$(curl -s "${BASE_URL}/test-integration/list-users")
echo -e "${GREEN}Users:${NC}"
format_json "$users"

# Step 7: List business users
section "7. LISTING BUSINESS USERS"
business_users=$(curl -s "${BASE_URL}/test-integration/list-business-users")
echo -e "${GREEN}Business users:${NC}"
format_json "$business_users"

# Final summary
section "DEBUG COMPLETE"
echo -e "${GREEN}Registration debug process completed.${NC}"
echo -e "Check the output above for any errors or issues."