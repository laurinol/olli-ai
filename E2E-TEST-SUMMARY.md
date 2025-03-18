# E2E Test Issues and Solutions

## Primary Issues

1. **Path Mismatch**: TestResource is using `/test-integration` but test endpoints were hardcoded with `/test`.
   - **Solution**: Updated all endpoint paths in EndToEndIntegrationTest.java to match TestResource.

2. **MongoDB Configuration**: The test uses a different database (`olli_test`) than the development one.
   - **Solution**: Ensured E2E tests configure the correct database through E2ETestProfile.

3. **TestResource Compilation Issues**: 
   - **Solution**: Fixed missing imports and compile errors in TestResource.

4. **MongoDB Repository Implementation**:
   - Issues with invite.withClaimed() not being properly saved in MongoUserRepository.
   - Fixed type safety in FirestoreUserRepository for getInviteByEmail.

5. **TaskQueueService Injection**:
   - Switched from direct RabbitMqTaskService injection to interface injection with Factory qualifier.

6. **SyncTask Field Naming**:
   - Ensured field names in mock task data match expected names in SyncTask class.

## Diagnostic Endpoints Added

1. `/test-integration/health`: Basic health check to confirm that the application is running.

2. `/test-integration/get-latest-invite`: Retrieves the latest invite for a given email.

3. `/test-integration/check-queue-task`: Verifies a task exists in the queue.

4. `/test-integration/process-sync-task`: Processes a sync task (or creates a mock one if needed).

5. `/test-integration/check-invite`: Direct MongoDB check for an invite to see its claimed status.

6. `/test-integration/test-registration`: Simplified diagnostic endpoint for testing registration.

7. `/test-integration/debug/collections`: Lists all collections with counts for diagnostics.

8. `/test-integration/list-users`: Lists all users in the database.

9. `/test-integration/list-business-users`: Lists business users for a business ID.

## E2E Test Flow

The E2E test follows this sequence:
1. Create an invite for a new user
2. Get the invite token
3. Register using the token
4. Verify the business was created
5. Add a local directory for syncing
6. Verify task was added to queue
7. Process the sync task
8. Verify processing was completed
9. Query the synced documents

## Current Status

- Fixed the path mismatch in the test file
- Fixed compilation issues in TestResource
- Added synchronous fallback for the test-registration endpoint
- Created a debug-test-endpoint.sh script for testing specific endpoints
- Enhanced logging for MongoDB operations

## Next Steps

1. Run the debug-test-endpoint.sh script to verify each endpoint works correctly
2. Fix remaining issues in TestResource-full endpoints (ongoing work)
3. Verify that claimed status is properly saved in MongoDB
4. Complete E2E test flow validation
5. Ensure that all database entities are properly created
6. Ensure the MongoDB connection is properly responding to write operations