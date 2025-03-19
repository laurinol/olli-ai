#!/bin/bash
set -e

# Variables
API_URL="http://localhost:8080"
EMAIL="test@example.com"
BUSINESS_ID="test-business-1"
PASSWORD="password123"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Testing Verification System for Email Registration${NC}"
echo "===================================================="

# Step 1: Create a verification
echo -e "${YELLOW}Step 1: Creating verification for ${EMAIL}${NC}"
RESPONSE=$(curl -s -X POST "${API_URL}/auth/invite" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"businessId\":\"${BUSINESS_ID}\"}")

echo "Response: $RESPONSE"

# Check if we got a success message
if echo "$RESPONSE" | grep -q "Verification email sent"; then
  echo -e "${GREEN}✅ Verification created successfully${NC}"
else
  echo -e "${RED}❌ Failed to create verification${NC}"
  exit 1
fi

# Get the verification token from the database
# In a real scenario, we'd extract this from the email or DB
# For testing purposes, we'll extract it directly from MongoDB
echo -e "${YELLOW}Step 2: Retrieving verification token from database${NC}"

TOKEN=$(mongo --quiet mongodb://localhost:27017/olli --eval "db.verifications.findOne({email: '${EMAIL}'}).token" | tr -d '"')

if [ -z "$TOKEN" ]; then
  echo -e "${RED}❌ No verification token found in database${NC}"
  # Try the old invites collection as fallback
  TOKEN=$(mongo --quiet mongodb://localhost:27017/olli --eval "db.invites.findOne({email: '${EMAIL}'}).token" | tr -d '"')
  
  if [ -z "$TOKEN" ]; then
    echo -e "${RED}❌ No invite token found in database either${NC}"
    exit 1
  else
    echo -e "${YELLOW}⚠️ Found token in invites collection (using backward compatibility)${NC}"
  fi
else
  echo -e "${GREEN}✅ Found verification token: ${TOKEN}${NC}"
fi

# Step 3: Register with the verification token
echo -e "${YELLOW}Step 3: Registering user with verification token${NC}"
REGISTER_RESPONSE=$(curl -s -X POST "${API_URL}/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"token\":\"${TOKEN}\"}")

echo "Response: $REGISTER_RESPONSE"

# Check if we got a token back, indicating successful registration
if echo "$REGISTER_RESPONSE" | grep -q "token"; then
  USER_TOKEN=$(echo "$REGISTER_RESPONSE" | grep -o '"token":"[^"]*' | sed 's/"token":"//')
  USER_ID=$(echo "$REGISTER_RESPONSE" | grep -o '"userId":"[^"]*' | sed 's/"userId":"//')
  echo -e "${GREEN}✅ User registered successfully${NC}"
  echo -e "User ID: ${USER_ID}"
  echo -e "Auth Token: ${USER_TOKEN}"
else
  echo -e "${RED}❌ Registration failed${NC}"
  exit 1
fi

# Step 4: Check if the verification is marked as verified
echo -e "${YELLOW}Step 4: Checking if verification is marked as verified${NC}"
VERIFIED=$(mongo --quiet mongodb://localhost:27017/olli --eval "db.verifications.findOne({token: '${TOKEN}'}).verified" || echo "false")

if [ "$VERIFIED" = "true" ]; then
  echo -e "${GREEN}✅ Verification marked as verified${NC}"
else
  echo -e "${RED}❌ Verification not marked as verified${NC}"
  # Try the old invites collection as fallback
  CLAIMED=$(mongo --quiet mongodb://localhost:27017/olli --eval "db.invites.findOne({token: '${TOKEN}'}).claimed" || echo "false")
  
  if [ "$CLAIMED" = "true" ]; then
    echo -e "${YELLOW}⚠️ Invite marked as claimed in the invites collection (using backward compatibility)${NC}"
  else
    echo -e "${RED}❌ Verification not marked in either collection${NC}"
    exit 1
  fi
fi

echo -e "${GREEN}===================================================${NC}"
echo -e "${GREEN}All tests passed successfully!${NC}"
echo -e "${GREEN}Verification system is working as expected.${NC}"
echo -e "${GREEN}===================================================${NC}"