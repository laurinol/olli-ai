#!/bin/bash

# Test script for the team invitation system
# Usage: ./test-invitations.sh [admin_token] [user_token]

# Set base URL
BASE_URL="http://localhost:8080"

# Colors for better readability
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Check if tokens are provided
ADMIN_TOKEN=${1:-"test-admin-token"}
USER_TOKEN=${2:-"test-user-token"}
BUSINESS_ID="test-business-id"
TEST_EMAIL="test-user@example.com"

echo -e "${YELLOW}=== Team Invitation System Test ====${NC}"
echo "Using Admin Token: ${ADMIN_TOKEN}"
echo "Using User Token: ${USER_TOKEN}"
echo "Testing Business: ${BUSINESS_ID}"
echo "Test Email: ${TEST_EMAIL}"
echo ""

# 1. Create an invitation
echo -e "${CYAN}[TEST 1] Create an invitation${NC}"
INVITE_RESPONSE=$(curl -s -X POST "${BASE_URL}/invitations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d "{\"email\":\"${TEST_EMAIL}\",\"businessId\":\"${BUSINESS_ID}\",\"role\":\"user\"}")

echo "Response: ${INVITE_RESPONSE}"
INVITATION_ID=$(echo ${INVITE_RESPONSE} | grep -o '"id":"[^"]*' | cut -d'"' -f4)

if [ -z "$INVITATION_ID" ]; then
  echo -e "${RED}Failed to create invitation or extract ID${NC}"
  exit 1
else
  echo -e "${GREEN}Successfully created invitation with ID: ${INVITATION_ID}${NC}"
fi

echo ""

# 2. Get invitations for the business
echo -e "${CYAN}[TEST 2] Get all invitations for business${NC}"
INVITES_RESPONSE=$(curl -s -X GET "${BASE_URL}/invitations?businessId=${BUSINESS_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

echo "Response: ${INVITES_RESPONSE}"
if [[ "$INVITES_RESPONSE" == *"$INVITATION_ID"* ]]; then
  echo -e "${GREEN}Successfully retrieved invitations including the new invitation${NC}"
else
  echo -e "${RED}Failed to find the new invitation in the list${NC}"
  exit 1
fi

echo ""

# 3. Get pending invitations
echo -e "${CYAN}[TEST 3] Get pending invitations${NC}"
PENDING_RESPONSE=$(curl -s -X GET "${BASE_URL}/invitations/pending?businessId=${BUSINESS_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

echo "Response: ${PENDING_RESPONSE}"
if [[ "$PENDING_RESPONSE" == *"$INVITATION_ID"* ]]; then
  echo -e "${GREEN}Successfully retrieved pending invitations${NC}"
else
  echo -e "${RED}Failed to find the invitation in pending invitations${NC}"
  exit 1
fi

echo ""

# 4. Get a specific invitation
echo -e "${CYAN}[TEST 4] Get specific invitation${NC}"
INVITATION_RESPONSE=$(curl -s -X GET "${BASE_URL}/invitations/${INVITATION_ID}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

echo "Response: ${INVITATION_RESPONSE}"
if [[ "$INVITATION_RESPONSE" == *"$INVITATION_ID"* && "$INVITATION_RESPONSE" == *"$TEST_EMAIL"* ]]; then
  echo -e "${GREEN}Successfully retrieved the invitation details${NC}"
else
  echo -e "${RED}Failed to retrieve invitation details${NC}"
  exit 1
fi

echo ""

# 5. Check invitations for an email
echo -e "${CYAN}[TEST 5] Check invitations for email${NC}"
CHECK_RESPONSE=$(curl -s -X GET "${BASE_URL}/invitations/check?email=${TEST_EMAIL}")

echo "Response: ${CHECK_RESPONSE}"
if [[ "$CHECK_RESPONSE" == *"$INVITATION_ID"* && "$CHECK_RESPONSE" == *"$BUSINESS_ID"* ]]; then
  echo -e "${GREEN}Successfully checked invitations for the email${NC}"
else
  echo -e "${RED}Failed to find invitations for the email${NC}"
  exit 1
fi

echo ""

# 6. Resend invitation
echo -e "${CYAN}[TEST 6] Resend invitation${NC}"
RESEND_RESPONSE=$(curl -s -X POST "${BASE_URL}/invitations/${INVITATION_ID}/resend" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

echo "Response: ${RESEND_RESPONSE}"
if [[ "$RESEND_RESPONSE" == *"successfully"* ]]; then
  echo -e "${GREEN}Successfully resent the invitation${NC}"
else
  echo -e "${RED}Failed to resend the invitation${NC}"
  exit 1
fi

echo ""

# 7. Accept invitation
echo -e "${CYAN}[TEST 7] Accept invitation${NC}"
ACCEPT_RESPONSE=$(curl -s -X POST "${BASE_URL}/invitations/${INVITATION_ID}/accept" \
  -H "Authorization: Bearer ${USER_TOKEN}")

echo "Response: ${ACCEPT_RESPONSE}"
if [[ "$ACCEPT_RESPONSE" == *"accepted successfully"* ]]; then
  echo -e "${GREEN}Successfully accepted the invitation${NC}"
else
  echo -e "${RED}Failed to accept the invitation${NC}"
  exit 1
fi

echo ""

# 8. Create another invitation for cancellation test
echo -e "${CYAN}[TEST 8] Create another invitation to test cancellation${NC}"
INVITE_RESPONSE2=$(curl -s -X POST "${BASE_URL}/invitations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -d "{\"email\":\"cancel-test@example.com\",\"businessId\":\"${BUSINESS_ID}\",\"role\":\"user\"}")

echo "Response: ${INVITE_RESPONSE2}"
INVITATION_ID2=$(echo ${INVITE_RESPONSE2} | grep -o '"id":"[^"]*' | cut -d'"' -f4)

if [ -z "$INVITATION_ID2" ]; then
  echo -e "${RED}Failed to create second invitation${NC}"
  exit 1
else
  echo -e "${GREEN}Successfully created invitation with ID: ${INVITATION_ID2}${NC}"
fi

echo ""

# 9. Cancel invitation
echo -e "${CYAN}[TEST 9] Cancel invitation${NC}"
CANCEL_RESPONSE=$(curl -s -X DELETE "${BASE_URL}/invitations/${INVITATION_ID2}" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}")

echo "Response: ${CANCEL_RESPONSE}"
if [[ "$CANCEL_RESPONSE" == *"cancelled successfully"* ]]; then
  echo -e "${GREEN}Successfully cancelled the invitation${NC}"
else
  echo -e "${RED}Failed to cancel the invitation${NC}"
  exit 1
fi

echo ""
echo -e "${GREEN}All tests passed successfully!${NC}"