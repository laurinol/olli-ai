# End-to-End Integration Test Plan for Olli AI

This document outlines a comprehensive test plan for end-to-end integration testing of the Olli AI application. The tests verify the complete flow from user registration to document syncing and querying with the RAG system.

## Test Environment Setup

Prerequisites:
- Docker Compose environment running:
  - Firestore emulator
  - RabbitMQ
  - Ollama with embeddings model
  - Minio S3 emulator
  - SFTP test server
- Test files in `/test/test-files/` directory
- Application running in dev mode with OIDC authentication disabled

## Test Sequence

### 1. Business Registration and Authentication

**Goal**: Verify a business owner can register and authenticate.

**Steps**:
1. Create a business invite
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     -d '{"email":"test@example.com", "businessId":"test-business"}' \
     http://localhost:8080/auth/invite
   ```

2. Get the invite token from the test endpoint
   ```bash
   curl http://localhost:8080/test/get-latest-invite?email=test@example.com
   ```

3. Register with the token
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     -d '{"email":"test@example.com", "password":"password123", "token":"<TOKEN>"}' \
     http://localhost:8080/auth/register
   ```

4. Verify the business was created
   ```bash
   curl -H "Authorization: Bearer <AUTH_TOKEN>" \
     http://localhost:8080/auth/profile
   ```

### 2. Document Synchronization

**Goal**: Verify documents can be synced from a storage provider.

**Steps**:
1. Add local directory for scanning
   ```bash
   curl -H "Authorization: Bearer <AUTH_TOKEN>" \
     "http://localhost:8080/auth/sync/local?folderId=test-files&businessId=test-business"
   ```

2. Verify task was added to queue
   ```bash
   curl "http://localhost:8080/test/check-queue-task?taskId=<TASK_ID>"
   ```

3. Process the task
   ```bash
   curl -X POST "http://localhost:8080/test/process-sync-task?taskId=<TASK_ID>"
   ```

4. Verify task processing status
   ```bash
   curl -H "Authorization: Bearer <AUTH_TOKEN>" \
     "http://localhost:8080/auth/sync/status?taskId=<TASK_ID>"
   ```

### 3. Chat Query Using RAG

**Goal**: Verify the ability to query documents via the RAG system.

**Steps**:
1. Submit a query about the documents
   ```bash
   curl -X POST -H "Content-Type: application/json" \
     -H "Authorization: Bearer <AUTH_TOKEN>" \
     -d '{"prompt": "What is in the contract with ACME?"}' \
     http://localhost:8080/chat/query
   ```

2. Verify response contains relevant information from the documents
   - Response should mention contract details or ACME

## Implementation Notes

To implement this as an automated test:

1. **Use TestContainers**: For managing Docker dependencies
2. **Use REST Assured**: For API interaction
3. **Configure Test Profile**: Create a specific testing configuration
4. **Mock External Services**: When needed instead of calling real services
5. **Set Up Test Data**: Prepare consistent test documents

The test should run through all steps in sequence, with each step depending on the successful completion of previous steps.

## Extending the Tests

Additional tests can verify:
- Multiple storage providers
- Error handling scenarios
- Different document types
- Authorization boundaries

## Expected Outcomes

The test verifies:
1. End-to-end functional flow works correctly
2. Document synchronization works from storage to embedded vector database
3. RAG queries correctly retrieve and process document information

This test plan serves as a blueprint for implementing automated integration tests and can also be used for manual verification.