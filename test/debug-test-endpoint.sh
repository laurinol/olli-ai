#!/bin/bash
# Debug test endpoints for Olli AI

# Set MongoDB as the database provider
export DATABASE_PROVIDER=mongodb
export MONGODB_CONNECTION_STRING=mongodb://admin:password@localhost:27017
export MONGODB_DATABASE=olli_test

# Configuration
BASE_URL="http://localhost:8080"
TEST_EMAIL="test-debug-$(date +%s)@example.com"
TEST_BUSINESS_ID="test-business-$(date +%s)"

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

# Helper function to run a command and print response
run_command() {
  local cmd="$1"
  local description="$2"

  echo -e "${YELLOW}> $description${NC}"
  echo -e "${YELLOW}$ $cmd${NC}"

  # Execute the command
  output=$(eval "$cmd" 2>&1)
  status=$?

  if [ $status -eq 0 ]; then
    echo -e "${GREEN}SUCCESS${NC}"
  else
    echo -e "${RED}FAILED (exit code: $status)${NC}"
  fi

  # Print output
  echo "$output"
  echo ""

  # Return output for processing
  echo "$output"
}

# Begin test
clear
section "OLLI AI DEBUG TEST ENDPOINTS"

echo "This script will test the specific endpoints of TestResource."
echo "Test business ID: $TEST_BUSINESS_ID"
echo "Test email: $TEST_EMAIL"
echo ""

# Test the health endpoint
section "1. TESTING HEALTH ENDPOINT"
run_command "curl -s ${BASE_URL}/test-integration/health" "Testing health endpoint"

# Test the debug collections endpoint
section "2. LISTING COLLECTIONS DATA"
run_command "curl -s ${BASE_URL}/test-integration/debug/collections" "Listing collections data"

# Create a test registration to verify DB
section "3. TESTING REGISTRATION FLOW"
run_command "curl -s \"${BASE_URL}/test-integration/test-registration?email=${TEST_EMAIL}&businessId=${TEST_BUSINESS_ID}\"" "Testing full registration flow"

# Create an invite
section "4. CREATING INVITE DIRECTLY"
invite_result=$(run_command "curl -s -X POST -H \"Content-Type: application/json\" -d '{\"email\":\"${TEST_EMAIL}\", \"businessId\":\"${TEST_BUSINESS_ID}\"}' ${BASE_URL}/auth/invite" "Creating invite")

# Get the invite
section "5. GETTING INVITE"
token_result=$(run_command "curl -s \"${BASE_URL}/test-integration/get-latest-invite?email=${TEST_EMAIL}\"" "Getting latest invite token")

# Capture the token
invite_token=$(echo "$token_result" | grep -v ">" | grep -v "SUCCESS" | grep -v "FAILED" | grep -v "^$" | head -1)
echo -e "Invite token: ${GREEN}$invite_token${NC}"

# Check the invite in MongoDB
section "6. CHECKING INVITE IN MONGODB"
run_command "curl -s \"${BASE_URL}/test-integration/check-invite?token=${invite_token}\"" "Checking invite in MongoDB"

# Testing mock sync task
section "7. TESTING MOCK SYNC TASK"
task_id="test-task-$(date +%s)"

# Check queue (should be empty)
run_command "curl -s \"${BASE_URL}/test-integration/check-queue-task?taskId=${task_id}\"" "Checking empty queue"

# Process mock task
run_command "curl -s -X POST \"${BASE_URL}/test-integration/process-sync-task?taskId=${task_id}\"" "Processing mock task"

# List users
section "8. LISTING USERS"
run_command "curl -s ${BASE_URL}/test-integration/list-users" "Listing all users"

# List business users
section "9. LISTING BUSINESS USERS"
run_command "curl -s \"${BASE_URL}/test-integration/list-business-users?businessId=${TEST_BUSINESS_ID}\"" "Listing business users"

echo -e "${GREEN}Debug test completed.${NC}"
exit 0