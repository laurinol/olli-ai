# Migration Plan: Invite → Verification

This document outlines our plan for migrating from the misused `Invite` system to a proper `Verification` system for email verification during registration.

## Current Status

✅ = Completed  
🔄 = In Progress  
❌ = Not Started

### Phase 1: Initial Migration (✅)

- ✅ Create `Verification` class with appropriate naming (verified vs claimed) and expiration time (48h vs 7d)
- ✅ Update `UserRepository` interface with new verification methods and backward compatibility
- ✅ Implement MongoDB repository methods for Verification
- ✅ Implement Firestore repository methods for Verification
- ✅ Update `/invite` endpoint in AuthController to use Verification
- ✅ Update `/register` endpoint in AuthController to use Verification
- ✅ Add appropriate error handling and logging
- ✅ Create test script (`test-verification.sh`) for validating changes
- ✅ Add testing documentation

### Phase 2: Test Endpoints and Integration Tests (✅)

- ✅ Update test endpoints to use Verification instead of Invite
- ✅ Add backward compatibility to test endpoints
- ✅ Update collection debug endpoints to include verifications
- ✅ Update integration tests to use the new verification system

### Phase 3: Frontend and Email Templates (🔄)

- ❌ Update "Invites" language in frontend to use "Verification" for email verification
- ❌ Update email templates and subject lines to properly reflect verification purpose

### Phase 4: Cleanup and New Invitation System (❌)

- ❌ Remove deprecated methods in UserRepository after confirming all code is migrated
- ❌ Implement a proper Invitation system for team members
- ❌ Update API documentation and endpoint paths

## Testing

To validate the current implementation:

1. Run the automated test script:
   ```bash
   ./test/test-verification.sh
   ```

2. For manual testing:
   - Follow instructions in `VERIFICATION_TESTING.md`

## Backward Compatibility

The current implementation maintains backward compatibility through:

1. Adapter methods in the `UserRepository` interface
2. Keeping the old endpoint paths (`/auth/invite`)
3. Supporting both "invite" and "verification" terminology in responses

This allows for a phased migration approach, minimizing disruption to the system while improving the domain model.

## Motivation

The primary motivation for this migration was to address a conceptual mismatch in our domain model:

1. **Invite** should represent invitations from business owners to team members
2. **Verification** should handle email verification during user registration

By separating these concerns, we've improved the clarity of our domain model and paved the way for a proper invitation system in the future.