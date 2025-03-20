# Email Verification System

## Overview

This document describes the email verification system used in Olli-AI. The system handles validating user email addresses during the registration process.

## Architecture

### Models

- `Verification`: The core model that represents an email verification token. Properties include:
  - `token`: Unique identifier
  - `businessId`: Business the user is registering for
  - `email`: Email address being verified
  - `role`: Role the user will have (e.g., "admin", "user")
  - `createdAt`: When the verification was created
  - `expiresAt`: When the verification expires (48 hours after creation)
  - `verified`: Whether the email has been verified

### Repositories

The system uses a repository pattern to abstract data access:

- `UserRepository.saveVerification`: Saves a verification
- `UserRepository.getVerification`: Gets a verification by token
- `UserRepository.deleteVerification`: Deletes a verification
- `UserRepository.getVerificationByEmail`: Gets the most recent verification for an email

Implementations exist for both MongoDB and Firestore.

### Flow

1. **Creation**: When a user requests to register, a verification token is created with `verified=false`
2. **Email**: An email with the token is sent to the user
3. **Verification**: When the user clicks the link, the token is verified
4. **Registration**: The user completes registration, and the verification is marked as `verified=true`

## API Endpoints

The following endpoints are related to email verification:

- `POST /auth/invite`: Creates a verification and sends an email (endpoint name maintained for backward compatibility)
- `POST /auth/register`: Registers a user using a verification token
- `GET /test-integration/get-latest-verification`: Gets the latest verification token for an email (for testing)
- `GET /test-integration/check-verification`: Checks a verification token's status (for testing)

## Technical Details

- Verification tokens expire after 48 hours
- The `verified` flag is set to `true` when registration is completed
- The system stores verifications in a dedicated collection (`verifications`)

## Migration History

This system was refactored from a previous design that inappropriately used an "Invite" model for email verification. The migration was completed in the following phases:

1. **Phase 1**: Created `Verification` class and repository methods with backward compatibility
2. **Phase 2**: Updated test endpoints and integration tests
3. **Phase 3**: Updated email templates and OpenAPI specification
4. **Phase 4**: Removed deprecated methods and created placeholder for proper team invitation system

## Future Work

A proper team invitation system will be implemented in Phase 5, allowing business owners to invite team members to join their business. This will use a dedicated `Invitation` model and related endpoints.

## Testing

For testing the verification system, refer to:
- `VERIFICATION_TESTING.md`: For manual and automated test procedures
- `test-verification.sh`: For automated testing script