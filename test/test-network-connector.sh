#!/bin/bash
# Test script for NetworkConnector with Samba

echo "Testing NetworkConnector with Samba server..."

# Start Docker containers if not already running
if ! docker ps | grep -q "samba"; then
  echo "Starting Samba server container..."
  docker-compose -f docker-compose.yml up -d samba
  
  # Wait for Samba server to be ready
  echo "Waiting for Samba server to start..."
  sleep 5
fi

# Run the test with appropriate system properties
echo "Running NetworkConnectorTest with system properties for Samba server..."
cd ..
mvn test -Dtest=NetworkConnectorTest \
  -Dsamba.host=localhost \
  -Dsamba.share=share \
  -Dsamba.username=testuser \
  -Dsamba.password=testpass

echo "Done!"