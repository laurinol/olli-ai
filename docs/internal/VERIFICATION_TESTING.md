# Testing the New Verification System

This document outlines how to test the new Verification system which replaces the misused Invite system for email verification during registration.

## Background

Previously, the `Invite` class was used for email verification during registration, which was conceptually incorrect as invitations should be used for inviting team members to a business. We've refactored this to use a dedicated `Verification` class for email verification with:

- More appropriate naming (verified vs claimed)
- Shorter expiration time (48 hours vs 7 days)
- Clearer user messaging in emails

The implementation maintains backward compatibility through adapter methods, allowing for a phased transition.

## Testing Prerequisites

1. Make sure the backend application is running:
   ```bash
   cd /Users/kai/Source/olli-ai/backend
   mvn quarkus:dev
   ```

2. Ensure MongoDB is running (used by the test script):
   ```bash
   docker ps | grep mongodb
   ```

## Manual Testing

### 1. Create a verification (previously "invite")

Make a POST request to the `/auth/invite` endpoint (endpoint name remains the same for backward compatibility):

```bash
curl -X POST "http://localhost:8080/auth/invite" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","businessId":"test-business-1"}'
```

Expected response: `{"message":"Verification email sent"}`

### 2. Verify the verification was created

Check MongoDB to confirm the verification was created:

```bash
mongo --quiet mongodb://localhost:27017/olli --eval "db.verifications.findOne({email: 'test@example.com'})"
```

Note: The verification should have:
- `verified: false`
- `expiresAt` set to 48 hours from creation time

### 3. Register with the verification token

Extract the token from the verification:

```bash
TOKEN=$(mongo --quiet mongodb://localhost:27017/olli --eval "db.verifications.findOne({email: 'test@example.com'}).token" | tr -d '"')
```

Then use it to register:

```bash
curl -X POST "http://localhost:8080/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"test@example.com\",\"password\":\"password123\",\"token\":\"$TOKEN\"}"
```

Expected response: A JSON object containing `token` and `userId`

### 4. Verify the verification was marked as verified

Check MongoDB to confirm the verification status:

```bash
mongo --quiet mongodb://localhost:27017/olli --eval "db.verifications.findOne({token: '$TOKEN'}).verified"
```

Expected response: `true`

## Automated Testing

We've created a test script that walks through the entire verification flow:

```bash
cd /Users/kai/Source/olli-ai/backend
./test/test-verification.sh
```

This script will:
1. Create a verification for a test email
2. Retrieve the verification token from the database
3. Register with the verification token
4. Check if the verification is marked as verified

## Backward Compatibility

The implementation maintains backward compatibility through adapter methods in the `UserRepository` interface. This means:

1. Old code that uses `Invite` will continue to work
2. Endpoints will use the new `Verification` system internally
3. The system will gradually transition to using `Verification` exclusively

You can test this compatibility by examining:
- The invites collection in MongoDB (should receive records through the adapter methods)
- Endpoints that still use the old terminology but operate on the new system

## Future Steps

After confirming the functionality works correctly, we will:

1. Update remaining endpoints that use `Invite` for email verification
2. Update frontend code to reflect the new terminology
3. Update documentation and API specs
4. Eventually remove the deprecated adapter methods when all code is migrated
5. Implement a proper invitation system for team members