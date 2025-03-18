#!/bin/bash
# Test script to create a new task and check if it was enqueued properly

echo "=== RabbitMQ Task Test ==="

# Create a new task
echo "Creating a new task..."
TASK_RESULT=$(curl -s -X GET "http://localhost:8080/auth/sync/local?folderId=test-files&businessId=test-rabbit-fix")
echo "Task creation result: $TASK_RESULT"

# Extract the task ID
TASK_ID=$(echo "$TASK_RESULT" | grep -o '"taskId":"[^"]*"' | sed 's/"taskId":"//;s/"//')

if [ -z "$TASK_ID" ]; then
  echo "Error: Could not extract task ID from response"
  exit 1
fi

echo "Task ID: $TASK_ID"

# Check if the task is in the queue
echo "Checking if task is in the queue..."
QUEUE_RESULT=$(curl -s "http://localhost:8080/test-integration/check-queue-task?taskId=${TASK_ID}")
echo "Queue check result: $QUEUE_RESULT"

# Try to process the task
echo "Processing task..."
PROCESS_RESULT=$(curl -s -X POST "http://localhost:8080/test-integration/process-sync-task?taskId=${TASK_ID}")
echo "Process result: $PROCESS_RESULT"

echo "Done!"