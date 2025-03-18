#!/bin/bash

# Script to set up the environment for using Firestore

# Start Firestore emulator container
echo "Starting Firestore emulator container..."
cd $(dirname $0)
docker-compose up -d firestore

# Set environment variables for the application
export DATABASE_PROVIDER=firestore
export QUARKUS_GOOGLE_CLOUD_PROJECT_ID=olli-ai-dev
export QUARKUS_GOOGLE_CLOUD_LOCATION=us-west1
export FIRESTORE_PROJECT_ID=olli-ai-dev

echo "Environment set up for Firestore."
echo "To start the application with Firestore, run:"
echo "mvn quarkus:dev"